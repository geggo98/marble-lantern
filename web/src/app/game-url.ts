// The game in the URL fragment (docs/web-ui.md, section "Game URL"). Pure: no DOM, no engine.
//
//   #g=v1.<payload>[&h=0|1][&l=<level>]
//
// payload: every move as 14 bits (from 7 bit, to 7 bit, cells 0 to 120), big-endian bit stream, zero-padded to whole
//          bytes, base64url without padding.
// h:       the side the human plays (0 moves first). l: the strength level: an index into NODE_LEVELS (0 to 4) or a custom node budget (skill/level.ts).
//
// Parsing is strict: a link that is not exactly what the encoder writes is reported as broken, never repaired.
import { MOVE_LIMIT } from '../game/board-state';
import type { Side } from '../game/board-state';
import { makeMove, moveFrom, moveTo } from '../engines/engine';
import { CELL_COUNT } from '../board/cells';
import { isValidLevel } from '../skill/level';
import type { Screen } from '../ui/Chrome';

export const GAME_URL_VERSION = 'v1';
const BITS_PER_MOVE = 14;

export type GameUrlReason = 'version' | 'length' | 'alphabet' | 'padding' | 'cell' | 'too-long' | 'field';

export class GameUrlError extends Error {
  constructor(readonly reason: GameUrlReason, detail: string) {
    super(`game link: ${reason}: ${detail}`);
    this.name = 'GameUrlError';
  }
}

export interface GameLink {
  /** Moves from the start position, oldest first (`from | (to << 8)`). */
  moves: readonly number[];
  humanSide?: Side;
  level?: number;
}

export type ParsedHash =
  | { kind: 'screen'; screen: Screen }
  | { kind: 'game'; link: GameLink }
  | { kind: 'broken'; reason: GameUrlReason };

// ---- base64url ----

const ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';
const INDEX = new Map([...ALPHABET].map((c, i) => [c, i]));

export function base64urlEncode(bytes: Uint8Array): string {
  let out = '';
  let acc = 0;
  let bits = 0;
  for (const byte of bytes) {
    acc = (acc << 8) | byte;
    bits += 8;
    while (bits >= 6) {
      out += ALPHABET[(acc >>> (bits - 6)) & 63];
      bits -= 6;
    }
    acc &= (1 << bits) - 1;
  }
  if (bits > 0) out += ALPHABET[(acc << (6 - bits)) & 63];
  return out;
}

/** The bytes, or null when the text is not canonical base64url without padding (stray characters, length 1 mod 4, non-zero spare bits). */
export function base64urlDecode(text: string): Uint8Array | null {
  if (text.length % 4 === 1) return null;
  const bytes: number[] = [];
  let acc = 0;
  let bits = 0;
  for (const char of text) {
    const value = INDEX.get(char);
    if (value === undefined) return null;
    acc = (acc << 6) | value;
    bits += 6;
    if (bits >= 8) {
      bytes.push((acc >>> (bits - 8)) & 255);
      bits -= 8;
    }
    acc &= (1 << bits) - 1;
  }
  if (acc !== 0) return null; // spare bits must be zero, or two texts would mean the same bytes
  return Uint8Array.from(bytes);
}

// ---- moves ----

export function encodeMoves(moves: readonly number[]): string {
  const bytes = new Uint8Array(Math.ceil((moves.length * BITS_PER_MOVE) / 8));
  let bit = 0;
  for (const move of moves) {
    const value = (moveFrom(move) << 7) | moveTo(move);
    for (let i = BITS_PER_MOVE - 1; i >= 0; i--, bit++) {
      if ((value >>> i) & 1) bytes[bit >>> 3]! |= 0x80 >>> (bit & 7);
    }
  }
  return base64urlEncode(bytes);
}

export function decodeMoves(payload: string): number[] {
  if (/[^A-Za-z0-9_-]/.test(payload)) throw new GameUrlError('alphabet', 'a character is not in the base64url alphabet');
  if (payload.length % 4 === 1) throw new GameUrlError('length', 'the text has an impossible length');
  const bytes = base64urlDecode(payload);
  if (!bytes) throw new GameUrlError('padding', 'spare bits of the last character are not zero');
  const count = Math.floor((bytes.length * 8) / BITS_PER_MOVE);
  if (Math.ceil((count * BITS_PER_MOVE) / 8) !== bytes.length) {
    throw new GameUrlError('length', `${bytes.length} bytes are not a whole number of moves`);
  }
  if (count > MOVE_LIMIT) throw new GameUrlError('too-long', `${count} moves, the limit is ${MOVE_LIMIT}`);
  const moves: number[] = [];
  let bit = 0;
  for (let n = 0; n < count; n++) {
    let value = 0;
    for (let i = 0; i < BITS_PER_MOVE; i++, bit++) value = (value << 1) | ((bytes[bit >>> 3]! >>> (7 - (bit & 7))) & 1);
    const from = value >>> 7;
    const to = value & 127;
    if (from >= CELL_COUNT || to >= CELL_COUNT) throw new GameUrlError('cell', `move ${n + 1} names a cell above ${CELL_COUNT - 1}`);
    moves.push(makeMove(from, to));
  }
  for (; bit < bytes.length * 8; bit++) {
    if ((bytes[bit >>> 3]! >>> (7 - (bit & 7))) & 1) throw new GameUrlError('padding', 'padding bits are not zero');
  }
  return moves;
}

// ---- fragment ----

/** The fragment including the leading "#". */
export function formatGameFragment(link: GameLink): string {
  let out = `#g=${GAME_URL_VERSION}.${encodeMoves(link.moves)}`;
  if (link.humanSide !== undefined) out += `&h=${link.humanSide}`;
  if (link.level !== undefined) out += `&l=${link.level}`;
  return out;
}

/** Strict parser for the game fragment; throws GameUrlError. */
export function parseGameFragment(fragment: string): GameLink {
  const fields = fragment.replace(/^#/, '').split('&');
  const first = fields[0]!;
  if (!first.startsWith(`g=${GAME_URL_VERSION}.`)) throw new GameUrlError('version', 'no "g=v1." prefix');
  const link: { moves: number[]; humanSide?: Side; level?: number } = {
    moves: decodeMoves(first.slice(`g=${GAME_URL_VERSION}.`.length)),
  };
  const seen = new Set<string>();
  for (const field of fields.slice(1)) {
    const match = /^(?<key>[a-z])=(?<value>.*)$/.exec(field);
    const key = match?.groups?.key;
    const value = match?.groups?.value ?? '';
    if (!key || seen.has(key)) throw new GameUrlError('field', `bad or repeated field "${field}"`);
    seen.add(key);
    if (key === 'h' && (value === '0' || value === '1')) link.humanSide = Number(value) as Side;
    else if (key === 'l' && /^(0|[1-9]\d{0,6})$/.test(value) && isValidLevel(Number(value))) link.level = Number(value);
    else throw new GameUrlError('field', `bad value in "${field}"`);
  }
  return link;
}

/** Tells a game link from a route. Anything that is not `#/bench`, `#/about` or `#g=...` is the play screen. */
export function parseHash(hash: string): ParsedHash {
  const body = hash.replace(/^#/, '');
  if (body === 'g' || body.startsWith('g=')) {
    try {
      return { kind: 'game', link: parseGameFragment(hash) };
    } catch (e) {
      if (e instanceof GameUrlError) return { kind: 'broken', reason: e.reason };
      throw e;
    }
  }
  const name = body.replace(/^\//, '');
  return { kind: 'screen', screen: name === 'bench' || name === 'about' ? name : 'play' };
}
