import { describe, expect, it } from 'vitest';
import { MOVE_LIMIT, initialOwners, applyMove, sideToMove } from '../game/board-state';
import { seeded } from '../engines/testing/fake-engine';
import { tinyLegalMoves } from '../engines/testing/tiny-moves';
import { makeMove } from '../engines/engine';
import {
  GameUrlError, base64urlDecode, base64urlEncode, decodeMoves, encodeMoves, formatGameFragment, parseHash,
} from './game-url';

/** A random game of the tiny generator: every move is legal for it, as far as the fake engine is concerned. */
function randomGame(seed: number, plies: number): number[] {
  const random = seeded(seed);
  const owner = initialOwners();
  const moves: number[] = [];
  for (let i = 0; i < plies; i++) {
    const legal = tinyLegalMoves(owner, sideToMove(i));
    if (legal.length === 0) break;
    const move = legal[Math.floor(random() * legal.length)]!;
    applyMove(owner, move);
    moves.push(move);
  }
  return moves;
}

const reasonOf = (fn: () => unknown): string => {
  try {
    fn();
  } catch (e) {
    if (e instanceof GameUrlError) return e.reason;
    throw e;
  }
  return 'no error';
};

describe('base64url', () => {
  it('matches the RFC 4648 test vectors without padding', () => {
    const enc = (s: string): string => base64urlEncode(new TextEncoder().encode(s));
    expect([enc(''), enc('f'), enc('fo'), enc('foo'), enc('foob'), enc('fooba'), enc('foobar')]).toEqual(
      ['', 'Zg', 'Zm8', 'Zm9v', 'Zm9vYg', 'Zm9vYmE', 'Zm9vYmFy'],
    );
  });

  it('uses - and _ instead of + and /', () => {
    expect(base64urlEncode(new Uint8Array([0xfb, 0xff, 0xbf]))).toBe('-_-_');
  });

  it('round-trips random bytes of every length from 0 to 40', () => {
    const random = seeded(5);
    for (let n = 0; n <= 40; n++) {
      const bytes = Uint8Array.from({ length: n }, () => Math.floor(random() * 256));
      expect([...base64urlDecode(base64urlEncode(bytes))!]).toEqual([...bytes]);
    }
  });

  it('refuses padding, the standard alphabet, spaces, a length of 1 mod 4 and non-zero trailing bits', () => {
    expect(base64urlDecode('Zg==')).toBeNull();
    expect(base64urlDecode('+/+/')).toBeNull();
    expect(base64urlDecode('Zm 9v')).toBeNull();
    expect(base64urlDecode('Zm9vY')).toBeNull(); // 5 characters: the last one carries no whole byte
    expect(base64urlDecode('Zh')).toBeNull(); // "Zg" is the canonical form of one byte; "Zh" has a stray bit
  });
});

describe('encodeMoves and decodeMoves', () => {
  it('packs a move in 14 bits: 4 moves take 7 bytes, 5 moves take 9 bytes', () => {
    expect(base64urlDecode(encodeMoves([1, 2, 3, 4].map((x) => makeMove(x, x + 1))))).toHaveLength(7);
    expect(base64urlDecode(encodeMoves([1, 2, 3, 4, 5].map((x) => makeMove(x, x + 1))))).toHaveLength(9);
  });

  it('has a fixed example: 0 to 1 and 120 to 119 are the bits 00000000000001 11110001110111, padded with zeros', () => {
    const bytes = base64urlDecode(encodeMoves([makeMove(0, 1), makeMove(120, 119)]))!;
    expect([...bytes]).toEqual([0b00000000, 0b00000111, 0b11000111, 0b01110000]);
  });

  it('encodes no moves as the empty payload', () => {
    expect(encodeMoves([])).toBe('');
    expect(decodeMoves('')).toEqual([]);
  });

  it('round-trips random legal games of many lengths, 40 seeds', () => {
    for (let seed = 1; seed <= 40; seed++) {
      const moves = randomGame(seed, (seed * 5) % (MOVE_LIMIT + 1));
      expect(decodeMoves(encodeMoves(moves))).toEqual(moves);
    }
  });

  it('round-trips the extreme cells 0 and 120 and a full game of the move limit', () => {
    const extreme = [makeMove(0, 120), makeMove(120, 0), makeMove(60, 61)];
    expect(decodeMoves(encodeMoves(extreme))).toEqual(extreme);
    const full = randomGame(9, MOVE_LIMIT);
    expect(full).toHaveLength(MOVE_LIMIT);
    expect(decodeMoves(encodeMoves(full))).toEqual(full);
  });

  it('measured sizes: 100 plies are 175 bytes and 234 characters; the move limit is 350 bytes and 467 characters', () => {
    const size = (plies: number): [number, number] => {
      const text = encodeMoves(randomGame(3, plies));
      return [base64urlDecode(text)!.length, text.length];
    };
    expect(size(100)).toEqual([175, 234]);
    expect(size(MOVE_LIMIT)).toEqual([350, 467]);
  });

  it('refuses a cell above 120', () => {
    // from = 121 (1111001), to = 0 (0000000): 11110010 00000000
    const bad = base64urlEncode(Uint8Array.from([0b11110010, 0b00000000]));
    expect(reasonOf(() => decodeMoves(bad))).toBe('cell');
  });

  it('refuses a length that no whole number of moves produces, and non-zero padding bits', () => {
    expect(reasonOf(() => decodeMoves(base64urlEncode(new Uint8Array(1))))).toBe('length'); // 8 bits: no move
    expect(reasonOf(() => decodeMoves(base64urlEncode(new Uint8Array(8))))).toBe('length'); // 4 moves need 7 bytes
    const good = base64urlDecode(encodeMoves([makeMove(1, 2)]))!;
    const dirty = Uint8Array.from(good);
    dirty[dirty.length - 1] = dirty[dirty.length - 1]! | 1;
    expect(reasonOf(() => decodeMoves(base64urlEncode(dirty)))).toBe('padding');
  });

  it('refuses more moves than the move limit', () => {
    const many = Array.from({ length: MOVE_LIMIT + 1 }, () => makeMove(0, 1));
    expect(reasonOf(() => decodeMoves(encodeMoves(many)))).toBe('too-long');
  });

  it('refuses characters outside the alphabet', () => {
    expect(reasonOf(() => decodeMoves('AAAA*'))).toBe('alphabet');
  });
});

describe('formatGameFragment and parseHash', () => {
  const moves = randomGame(11, 30);

  it('writes #g=v1.<payload> and the optional fields h and l in a fixed order', () => {
    expect(formatGameFragment({ moves: [] })).toBe('#g=v1.');
    expect(formatGameFragment({ moves: [], humanSide: 0 })).toBe('#g=v1.&h=0');
    expect(formatGameFragment({ moves, humanSide: 1, level: 3 })).toBe(`#g=v1.${encodeMoves(moves)}&h=1&l=3`);
  });

  it('parses what it wrote, for 40 random games and all field combinations', () => {
    for (let seed = 1; seed <= 40; seed++) {
      const link = {
        moves: randomGame(seed, seed * 3),
        ...(seed % 2 ? { humanSide: (seed % 4 === 1 ? 1 : 0) as 0 | 1 } : {}),
        ...(seed % 3 === 0 ? { level: seed % 5 } : {}),
      };
      expect(parseHash(formatGameFragment(link))).toEqual({ kind: 'game', link });
    }
  });

  it('keeps the routes: #/bench and #/about, and anything else is the play screen', () => {
    expect(parseHash('#/bench')).toEqual({ kind: 'screen', screen: 'bench' });
    expect(parseHash('#/about')).toEqual({ kind: 'screen', screen: 'about' });
    expect(parseHash('#/play')).toEqual({ kind: 'screen', screen: 'play' });
    expect(parseHash('')).toEqual({ kind: 'screen', screen: 'play' });
    expect(parseHash('#')).toEqual({ kind: 'screen', screen: 'play' });
    expect(parseHash('#whatever')).toEqual({ kind: 'screen', screen: 'play' });
    expect(parseHash('#gx=1')).toEqual({ kind: 'screen', screen: 'play' });
  });

  it('reports a broken link with the reason, never throws', () => {
    const payload = encodeMoves(moves);
    const broken = (hash: string): string => {
      const parsed = parseHash(hash);
      return parsed.kind === 'broken' ? parsed.reason : parsed.kind;
    };
    expect(broken('#g=')).toBe('version');
    expect(broken(`#g=${payload}`)).toBe('version');
    expect(broken(`#g=v2.${payload}`)).toBe('version');
    expect(broken('#g=v1.***')).toBe('alphabet');
    expect(broken(`#g=v1.${payload.slice(0, -2)}`)).toBe('length'); // cut off
    expect(broken(`#g=v1.${payload}&h=2`)).toBe('field');
    expect(broken(`#g=v1.${payload}&l=-1`)).toBe('field');
    expect(broken(`#g=v1.${payload}&l=9`)).toBe('field');
    expect(broken(`#g=v1.${payload}&l=1.5`)).toBe('field');
    expect(broken(`#g=v1.${payload}&x=1`)).toBe('field');
    expect(broken(`#g=v1.${payload}&h=1&h=1`)).toBe('field');
    expect(broken(`#g=v1.${payload}&h`)).toBe('field');
  });

  it('survives random corruption of a valid fragment: it parses or reports, and never throws', () => {
    const random = seeded(77);
    const good = formatGameFragment({ moves, humanSide: 1, level: 2 });
    const alphabet = 'AZaz09-_+/=&.#%~ é';
    let broken = 0;
    for (let i = 0; i < 2000; i++) {
      const chars = [...good];
      const edits = 1 + Math.floor(random() * 3);
      for (let k = 0; k < edits; k++) {
        const at = Math.floor(random() * chars.length);
        const roll = random();
        if (roll < 0.4) chars[at] = alphabet[Math.floor(random() * alphabet.length)]!;
        else if (roll < 0.7) chars.splice(at, 1);
        else chars.splice(at, 0, alphabet[Math.floor(random() * alphabet.length)]!);
      }
      const parsed = parseHash(chars.join(''));
      expect(['game', 'broken', 'screen']).toContain(parsed.kind);
      if (parsed.kind === 'broken') broken++;
    }
    expect(broken).toBeGreaterThan(100); // a random edit often breaks the link; the check has teeth
  });
});
