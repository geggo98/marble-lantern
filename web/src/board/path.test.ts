import { describe, expect, it } from 'vitest';
import { CELL_COUNT, cellId } from './cells';
import { jumpPath } from './path';

const board = (...cells: Array<[number, number]>): boolean[] => {
  const occupied = new Array<boolean>(CELL_COUNT).fill(false);
  for (const [q, r] of cells) occupied[cellId(q, r)] = true;
  return occupied;
};
const id = (q: number, r: number): number => cellId(q, r);

describe('jumpPath', () => {
  it('returns [from, to] for a single step', () => {
    expect(jumpPath(id(0, 0), id(1, 0), board([0, 0]))).toEqual([id(0, 0), id(1, 0)]);
  });

  it('finds a single jump over a neighbour', () => {
    expect(jumpPath(id(0, 0), id(2, 0), board([0, 0], [1, 0]))).toEqual([id(0, 0), id(2, 0)]);
  });

  it('finds a chain of two jumps through the middle landing cell', () => {
    // (0,0) jumps over (1,0) to (2,0), then over (2,1) to (2,2)
    const occupied = board([0, 0], [1, 0], [2, 1]);
    expect(jumpPath(id(0, 0), id(2, 2), occupied)).toEqual([id(0, 0), id(2, 0), id(2, 2)]);
  });

  it('prefers the chain with fewer jumps', () => {
    // Direct jump to (2,0) exists, so a detour through other cells must not appear.
    const occupied = board([0, 0], [1, 0], [2, 1], [3, 0]);
    expect(jumpPath(id(0, 0), id(2, 0), occupied)).toEqual([id(0, 0), id(2, 0)]);
  });

  it('returns null when there is no chain', () => {
    expect(jumpPath(id(0, 0), id(3, 3), board([0, 0]))).toBeNull();
  });

  it('returns null for from === to', () => {
    expect(jumpPath(id(0, 0), id(0, 0), board([0, 0]))).toBeNull();
  });

  it('does not jump over an empty cell', () => {
    expect(jumpPath(id(0, 0), id(2, 0), board([0, 0]))).toBeNull();
  });

  it('does not land on an occupied cell', () => {
    expect(jumpPath(id(0, 0), id(2, 0), board([0, 0], [1, 0], [2, 0]))).toBeNull();
  });
});
