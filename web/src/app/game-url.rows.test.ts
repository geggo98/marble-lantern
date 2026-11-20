// The game URL on the 5-row board: &t=5, and the 4-row links stay as they were.
import { describe, expect, it } from 'vitest';
import { applyMove, initialOwners } from '../game/board-state';
import { seeded } from '../engines/testing/fake-engine';
import { tinyLegalMoves } from '../engines/testing/tiny-moves';
import { moveLimit } from '../game/variant';
import type { Variant } from '../game/variant';
import { encodeMoves, formatGameFragment, parseGameFragment, parseHash } from './game-url';
import type { GameLink } from './game-url';

const FIVE: Variant = { jump: 'adjacent', players: 2, rows: 5 };
const FIVE_THREE: Variant = { jump: 'long', players: 3, rows: 5, camps: 'open', ranking: 'full' };

function randomGame(seed: number, plies: number, variant: Variant): number[] {
  const random = seeded(seed);
  const owner = initialOwners(variant);
  const moves: number[] = [];
  for (let i = 0; i < plies; i++) {
    const legal = tinyLegalMoves(owner, i % variant.players, variant);
    if (legal.length === 0) break;
    const move = legal[Math.floor(random() * legal.length)]!;
    applyMove(owner, move);
    moves.push(move);
  }
  return moves;
}
/** Legal-looking padding: the cells are all valid, so `n` moves of any cells encode; the parser does not replay them. */
const filler = (n: number): number[] => Array.from({ length: n }, (_, i) => ((i % 100) | (((i + 1) % 100) << 8)));
const isBroken = (fragment: string, reason?: string): boolean => {
  const p = parseHash(fragment);
  return p.kind === 'broken' && (reason === undefined || p.reason === reason);
};

describe('t=5', () => {
  it('writes &t=5 for 5 rows and nothing for 4 rows', () => {
    const moves = randomGame(1, 6, FIVE);
    expect(formatGameFragment({ moves, humanSide: 0, variant: FIVE })).toBe(`#g=v1.${encodeMoves(moves)}&h=0&t=5`);
    expect(formatGameFragment({ moves, humanSide: 0 })).not.toContain('t=');
    expect(formatGameFragment({ moves, humanSide: 0, variant: { jump: 'adjacent', players: 2, rows: 4 } })).not.toContain('t=');
  });

  it('round-trips 2 and 3 players, with the other fields, and reads the variant back with rows 5', () => {
    const two: GameLink = { moves: randomGame(2, 20, FIVE), humanSide: 1, level: 2, variant: FIVE };
    expect(parseGameFragment(formatGameFragment(two))).toEqual(two);
    const three: GameLink = { moves: randomGame(3, 20, FIVE_THREE), variant: FIVE_THREE, seats: ['human', 'engine', 'engine'] };
    const text = formatGameFragment(three);
    expect(text).toMatch(/&j=long&p=3&s=hee&r=full&c=open&t=5$/);
    expect(parseGameFragment(text)).toEqual(three);
  });

  it('every 4-row link parses as before: no rows in the variant, isDefault for the default game', () => {
    const moves = randomGame(4, 10, { jump: 'adjacent', players: 2 });
    const link = parseGameFragment(`#g=v1.${encodeMoves(moves)}&h=0`);
    expect(link.variant).toBeUndefined();
    const long = parseGameFragment(`#g=v1.${encodeMoves(moves)}&h=0&j=long`);
    expect(long.variant).toEqual({ jump: 'long', players: 2 });
    expect(long.variant).not.toHaveProperty('rows');
  });

  it('a 5-row game at the 2-player limit of 250 plies parses; 251 is too long', () => {
    expect(parseGameFragment(`#g=v1.${encodeMoves(filler(250))}&h=0&t=5`).moves).toHaveLength(250);
    expect(isBroken(`#g=v1.${encodeMoves(filler(251))}&h=0&t=5`, 'too-long')).toBe(true);
  });

  it('a 5-row 3-player game of 375 plies (more than 300) round-trips; 376 is too long', () => {
    const seats = '&p=3&s=hee';
    const ok = parseGameFragment(`#g=v1.${encodeMoves(filler(375))}${seats}&t=5`);
    expect(ok.moves).toHaveLength(375);
    expect(isBroken(`#g=v1.${encodeMoves(filler(376))}${seats}&t=5`, 'too-long')).toBe(true);
  });

  it('the 4-row limits did not move: 201 plies of 2 players and 301 of 3 players are still too long without t=5', () => {
    expect(isBroken(`#g=v1.${encodeMoves(filler(201))}&h=0`, 'too-long')).toBe(true);
    expect(isBroken(`#g=v1.${encodeMoves(filler(301))}&p=3&s=hee`, 'too-long')).toBe(true);
    expect(parseGameFragment(`#g=v1.${encodeMoves(filler(300))}&p=3&s=hee`).moves).toHaveLength(300);
  });

  it('a link longer than 300 plies needs t=5: without it the same payload is broken (3 players)', () => {
    expect(isBroken(`#g=v1.${encodeMoves(filler(320))}&p=3&s=hee`, 'too-long')).toBe(true);
    expect(parseGameFragment(`#g=v1.${encodeMoves(filler(320))}&p=3&s=hee&t=5`).moves).toHaveLength(320);
  });

  it('t=4, t=6, an empty t, a repeated t and a lower-case value are broken (field)', () => {
    const base = `#g=v1.${encodeMoves(filler(3))}&h=0`;
    for (const tail of ['&t=4', '&t=6', '&t=', '&t=5&t=5', '&t=5x', '&T=5']) expect(isBroken(base + tail, 'field'), tail).toBe(true);
  });

  it('the move limit table is 200/300 on 4 rows and 250/375 on 5 rows', () => {
    expect([2, 3].map((players) => moveLimit({ jump: 'adjacent', players: players as 2 | 3 }))).toEqual([200, 300]);
    expect([2, 3].map((players) => moveLimit({ jump: 'long', players: players as 2 | 3, rows: 5 }))).toEqual([250, 375]);
  });
});
