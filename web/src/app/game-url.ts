// The game in the URL fragment (docs/web-ui.md, section "Game URL"). Pure: no DOM, no engine.
//
//   #g=v1.<payload>[&h=0|1][&l=<level>][&j=long][&p=3&s=<seats>][&r=full][&c=pass|open]
//
// payload: every move as 14 bits (from 7 bit, to 7 bit, cells 0 to 120), big-endian bit stream, zero-padded to whole
//          bytes, base64url without padding.
// h:       the side the human plays (0 moves first). l: the strength level: an index into NODE_LEVELS (0 to 4) or a custom node budget (skill/level.ts).
// j:       the jump rule, only "long" (omitted for the adjacent rule).  p: the player count, only "3" (omitted for two players).
// s:       three players only, and then required: one character per seat, "h" a human and "e" an engine, e.g. "hhe" (hot-seat) or "hee";
//          at least one and at most two humans. Two players keep h; three players have no h.
// r:       the ranking rule, only "full" (omitted for first-winner rules), and only with p=3: after a win the game goes on for the
//          lower ranks (Play on for 2nd place). Without it the moves after a win are illegal, as before.
// c:       the foreign-corner rule (docs/rules.md, "Foreign corners"), only "pass" (pass-through) or "open" (omitted for closed corners).
//
// Parsing is strict: a link that is not exactly what the encoder writes is reported as broken, never repaired.
import type { Side } from '../game/board-state';
import { DEFAULT_VARIANT, campsOf, isDefault, moveLimit, rankingOf, withCamps } from '../game/variant';
import type { ForeignCamps, Variant } from '../game/variant';
import type { ControllerSeat } from '../game/game';
import { makeMove, moveFrom, moveTo } from '../engines/engine';
import { CELL_COUNT } from '../board/cells';
import { isValidLevel } from '../skill/level';
import type { Screen } from '../ui/Chrome';

export const GAME_URL_VERSION = 'v1';
const BITS_PER_MOVE = 14;
/** The longest game of any variant (300 plies for three players); parseGameFragment narrows it to the variant of the link. */
const MAX_MOVES = 300;

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
  /** Two players: the side the human plays. */
  humanSide?: Side;
  level?: number;
  /** Missing means the default variant (adjacent jumps, two players). */
  variant?: Variant;
  /** Three players: who plays each seat. Always present for three players, never for two. */
  seats?: readonly ControllerSeat[];
}

/** The variant of a link. */
export const variantOfLink = (link: GameLink): Variant => link.variant ?? DEFAULT_VARIANT;

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

export function decodeMoves(payload: string, limit = moveLimit(DEFAULT_VARIANT)): number[] {
  if (/[^A-Za-z0-9_-]/.test(payload)) throw new GameUrlError('alphabet', 'a character is not in the base64url alphabet');
  if (payload.length % 4 === 1) throw new GameUrlError('length', 'the text has an impossible length');
  const bytes = base64urlDecode(payload);
  if (!bytes) throw new GameUrlError('padding', 'spare bits of the last character are not zero');
  const count = Math.floor((bytes.length * 8) / BITS_PER_MOVE);
  if (Math.ceil((count * BITS_PER_MOVE) / 8) !== bytes.length) {
    throw new GameUrlError('length', `${bytes.length} bytes are not a whole number of moves`);
  }
  if (count > limit) throw new GameUrlError('too-long', `${count} moves, the limit is ${limit}`);
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

/** The fragment including the leading "#". Three players need `seats` (and ignore `humanSide`); two players ignore `seats`. */
export function formatGameFragment(link: GameLink): string {
  const variant = variantOfLink(link);
  let out = `#g=${GAME_URL_VERSION}.${encodeMoves(link.moves)}`;
  if (variant.players === 2 && link.humanSide !== undefined) out += `&h=${link.humanSide}`;
  if (link.level !== undefined) out += `&l=${link.level}`;
  if (variant.jump === 'long') out += '&j=long';
  if (variant.players === 3) {
    if (!link.seats) throw new GameUrlError('field', 'a three-player link needs its seats');
    out += `&p=3&s=${formatSeats(link.seats, 3)}`;
    if (rankingOf(variant) === 'full') out += '&r=full';
  } else if (rankingOf(variant) === 'full') {
    throw new GameUrlError('field', 'full ranking is for three players');
  }
  const camps = campsOf(variant);
  if (camps !== 'closed') out += `&c=${camps === 'open' ? 'open' : 'pass'}`;
  return out;
}

const SEAT_CHAR: Readonly<Record<ControllerSeat, string>> = { human: 'h', engine: 'e' };

function formatSeats(seats: readonly ControllerSeat[], players: number): string {
  const text = seats.map((s) => SEAT_CHAR[s]).join('');
  if (!validSeatText(text, players)) throw new GameUrlError('field', `seats ${text} do not fit a game of ${players} players`);
  return text;
}

/** One character per seat; at least one and at most two humans (hot-seat has two, a game against two engines one). */
function validSeatText(text: string, players: number): boolean {
  const humans = text.split('h').length - 1;
  return text.length === players && /^[he]+$/.test(text) && humans >= 1 && humans <= 2;
}

/** Strict parser for the game fragment; throws GameUrlError. */
export function parseGameFragment(fragment: string): GameLink {
  const fields = fragment.replace(/^#/, '').split('&');
  const first = fields[0]!;
  if (!first.startsWith(`g=${GAME_URL_VERSION}.`)) throw new GameUrlError('version', 'no "g=v1." prefix');
  const moves = decodeMoves(first.slice(`g=${GAME_URL_VERSION}.`.length), MAX_MOVES);
  const link: { moves: number[]; humanSide?: Side; level?: number; variant?: Variant; seats?: ControllerSeat[] } = { moves };
  let jump: Variant['jump'] = 'adjacent';
  let players: Variant['players'] = 2;
  let ranking: Variant['ranking'] = 'first';
  let camps: ForeignCamps = 'closed';
  let seatText: string | null = null;
  const seen = new Set<string>();
  for (const field of fields.slice(1)) {
    const match = /^(?<key>[a-z])=(?<value>.*)$/.exec(field);
    const key = match?.groups?.key;
    const value = match?.groups?.value ?? '';
    if (!key || seen.has(key)) throw new GameUrlError('field', `bad or repeated field "${field}"`);
    seen.add(key);
    if (key === 'h' && (value === '0' || value === '1')) link.humanSide = Number(value) as Side;
    else if (key === 'l' && /^(0|[1-9]\d{0,6})$/.test(value) && isValidLevel(Number(value))) link.level = Number(value);
    else if (key === 'j' && value === 'long') jump = 'long';
    else if (key === 'p' && value === '3') players = 3;
    else if (key === 'r' && value === 'full') ranking = 'full';
    else if (key === 'c' && (value === 'pass' || value === 'open')) camps = value === 'open' ? 'open' : 'pass-through';
    else if (key === 's' && /^[he]{3}$/.test(value)) seatText = value;
    else throw new GameUrlError('field', `bad value in "${field}"`);
  }
  // the fields that belong together
  if (players === 3) {
    if (seatText === null) throw new GameUrlError('field', 'a three-player link needs s=<seats>');
    if (link.humanSide !== undefined) throw new GameUrlError('field', 'h is for two players; three players use s');
    if (!validSeatText(seatText, 3)) throw new GameUrlError('field', `bad seats "${seatText}"`);
    link.seats = [...seatText].map((c): ControllerSeat => (c === 'h' ? 'human' : 'engine'));
  } else if (seen.has('s')) {
    throw new GameUrlError('field', 's is for three players; two players use h');
  }
  if (ranking === 'full' && players !== 3) throw new GameUrlError('field', 'r=full is for three players');
  const variant: Variant = withCamps(ranking === 'full' ? { jump, players, ranking } : { jump, players }, camps);
  if (!isDefault(variant)) link.variant = variant;
  if (moves.length > moveLimit(variant)) throw new GameUrlError('too-long', `${moves.length} moves, the limit is ${moveLimit(variant)}`);
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
