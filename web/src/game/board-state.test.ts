import { describe, expect, it } from 'vitest';
import { BOTTOM_CORNER, CELL_COUNT, TOP_CORNER, cellId } from '../board/cells';
import { makeMove } from '../engines/engine';
import { MOVE_LIMIT, applyMove, hasWon, initialOwners, outcomeOf, replay, sideToMove } from './board-state';

const count = (o: Int8Array, side: number): number => o.filter((x) => x === side).length;

describe('start position', () => {
  it('has 10 pieces per side in the two start corners and nothing else', () => {
    const o = initialOwners();
    expect(count(o, 0)).toBe(10);
    expect(count(o, 1)).toBe(10);
    expect(TOP_CORNER.every((c) => o[c] === 0)).toBe(true);
    expect(BOTTOM_CORNER.every((c) => o[c] === 1)).toBe(true);
    expect(o).toHaveLength(CELL_COUNT);
  });
});

describe('moves', () => {
  it('alternates the side to move, starting with side 0', () => {
    expect([0, 1, 2, 3].map(sideToMove)).toEqual([0, 1, 0, 1]);
  });

  it('moves a piece and keeps the piece counts', () => {
    const o = initialOwners();
    const from = cellId(1, -5);
    const to = cellId(0, -4); // empty centre cell next to the top corner
    applyMove(o, makeMove(from, to));
    expect(o[from]).toBe(-1);
    expect(o[to]).toBe(0);
    expect(count(o, 0)).toBe(10);
  });

  it('refuses a move from an empty cell and onto an occupied cell', () => {
    expect(() => applyMove(initialOwners(), makeMove(cellId(0, 0), cellId(1, 0)))).toThrow(RangeError);
    expect(() => applyMove(initialOwners(), makeMove(cellId(1, -5), cellId(2, -5)))).toThrow(RangeError);
  });

  it('replays a history', () => {
    const history = [makeMove(cellId(1, -5), cellId(0, -4)), makeMove(cellId(-1, 5), cellId(0, 4))];
    const o = replay(history);
    expect(o[cellId(0, -4)]).toBe(0);
    expect(o[cellId(0, 4)]).toBe(1);
  });
});

describe('win (Definition 1, blockadeRule = OPPONENT_FILLS)', () => {
  const fill = (cells: readonly number[], side: number, skip = -1): Int8Array => {
    const o = new Int8Array(CELL_COUNT).fill(-1);
    for (const c of cells) if (c !== skip) o[c] = side;
    return o;
  };

  it('is won when the goal is full of own pieces', () => {
    expect(hasWon(fill(BOTTOM_CORNER, 0), 0)).toBe(true);
    expect(hasWon(fill(TOP_CORNER, 1), 1)).toBe(true);
  });

  it('is not won with one goal cell empty', () => {
    expect(hasWon(fill(BOTTOM_CORNER, 0, BOTTOM_CORNER[3]), 0)).toBe(false);
  });

  it('is not won when the goal holds only opponent pieces', () => {
    expect(hasWon(fill(BOTTOM_CORNER, 1), 0)).toBe(false);
  });

  it('is won when the goal is full and at least one piece is the player\'s own', () => {
    const o = fill(BOTTOM_CORNER, 1);
    o[BOTTOM_CORNER[0]!] = 0;
    expect(hasWon(o, 0)).toBe(true);
  });

  it('names the mover of the last ply as the winner', () => {
    const o = fill(BOTTOM_CORNER, 0);
    expect(outcomeOf(o, [1])).toEqual({ kind: 'win', winner: 0 });
    expect(outcomeOf(o, [1, 2])).toBeNull(); // side 1 moved last and has not won
  });

  it('ends without a winner at the move limit, but a win on the last ply still wins', () => {
    const empty = new Int8Array(CELL_COUNT).fill(-1);
    expect(MOVE_LIMIT).toBe(200);
    expect(outcomeOf(empty, new Array(199).fill(0))).toBeNull();
    expect(outcomeOf(empty, new Array(200).fill(0))).toEqual({ kind: 'move-limit' });
    expect(outcomeOf(fill(TOP_CORNER, 1), new Array(200).fill(0))).toEqual({ kind: 'win', winner: 1 });
  });

  it('has no outcome at the start', () => {
    expect(outcomeOf(initialOwners(), [])).toBeNull();
  });
});
