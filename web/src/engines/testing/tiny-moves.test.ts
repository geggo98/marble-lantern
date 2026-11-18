import { describe, expect, it } from 'vitest';
import { CELL_COUNT, cellId } from '../../board/cells';
import { initialOwners } from '../../game/board-state';
import { makeMove } from '../engine';
import { tinyLegalMoves } from './tiny-moves';

const owners = (placed: Array<[number, number, 0 | 1]>): Int8Array => {
  const o = new Int8Array(CELL_COUNT).fill(-1);
  for (const [q, r, side] of placed) o[cellId(q, r)] = side;
  return o;
};

describe('tinyLegalMoves (test helper)', () => {
  it('gives a lone piece in the centre its six steps', () => {
    const moves = tinyLegalMoves(owners([[0, 0, 0]]), 0);
    expect(moves).toHaveLength(6);
  });

  it('adds a jump over an adjacent piece', () => {
    const moves = tinyLegalMoves(owners([[0, 0, 0], [1, 0, 1]]), 0);
    expect(moves).toContain(makeMove(cellId(0, 0), cellId(2, 0)));
    expect(moves).not.toContain(makeMove(cellId(0, 0), cellId(1, 0))); // occupied
    expect(moves).toHaveLength(5 + 1);
  });

  it('follows jump chains', () => {
    const moves = tinyLegalMoves(owners([[0, 0, 0], [1, 0, 1], [2, 1, 1]]), 0);
    expect(moves).toContain(makeMove(cellId(0, 0), cellId(2, 2)));
  });

  it('lists no move twice and none for the other side\'s pieces', () => {
    const moves = tinyLegalMoves(initialOwners(), 0);
    expect(new Set(moves).size).toBe(moves.length);
    expect(moves.length).toBeGreaterThan(0);
    const owner = initialOwners();
    for (const m of moves) expect(owner[m & 0xff]).toBe(0);
  });
});
