import { describe, expect, it } from 'vitest';
import { CELLS, cellId } from '../board/cells';
import { applyMove, goalCells, hasWon, initialOwners, outcomeOf, replay, startCells } from './board-state';
import { makeMove } from '../engines/engine';
import { DEFAULT_VARIANT, campsOf, isDefault, moveLimit, playsOn, rankingOf, sameVariant, sideAt, sidesOf, variantKey, withCamps, withRanking } from './variant';
import type { Variant } from './variant';

const THREE: Variant = { jump: 'adjacent', players: 3 };

describe('variant helpers', () => {
  it('the default is adjacent jumps with two players, and only it is default', () => {
    expect(DEFAULT_VARIANT).toEqual({ jump: 'adjacent', players: 2 });
    expect(isDefault(DEFAULT_VARIANT)).toBe(true);
    expect(isDefault({ jump: 'long', players: 2 })).toBe(false);
    expect(isDefault(THREE)).toBe(false);
    expect(isDefault({ jump: 'long', players: 3 })).toBe(false);
  });

  it('lists the seats and the move limit: 200 plies for two players, 300 for three, whatever the jump rule', () => {
    expect(sidesOf(DEFAULT_VARIANT)).toEqual([0, 1]);
    expect(sidesOf(THREE)).toEqual([0, 1, 2]);
    expect(moveLimit(DEFAULT_VARIANT)).toBe(200);
    expect(moveLimit({ jump: 'long', players: 2 })).toBe(200);
    expect(moveLimit(THREE)).toBe(300);
    expect(moveLimit({ jump: 'long', players: 3 })).toBe(300);
  });

  it('the side to move is ply % players', () => {
    expect([0, 1, 2, 3, 4].map((ply) => sideAt(ply, DEFAULT_VARIANT))).toEqual([0, 1, 0, 1, 0]);
    expect([0, 1, 2, 3, 4].map((ply) => sideAt(ply, THREE))).toEqual([0, 1, 2, 0, 1]);
  });

  it('compares and names variants', () => {
    expect(sameVariant(THREE, { jump: 'adjacent', players: 3 })).toBe(true);
    expect(sameVariant(THREE, { jump: 'long', players: 3 })).toBe(false);
    expect(variantKey({ jump: 'long', players: 2 })).toBe('long-2');
  });
});

describe('board state for three players', () => {
  it('starts with 10 pieces in each of the corners r < -4, s < -4 and q < -4, in that order', () => {
    const owner = initialOwners(THREE);
    expect(owner.filter((x) => x >= 0)).toHaveLength(30);
    const where = (seat: number): Set<number> => new Set(CELLS.filter((c) => owner[c.id] === seat).map((c) => c.id));
    const inCorner = (seat: number, test: (q: number, r: number, s: number) => boolean): boolean =>
      CELLS.filter((c) => test(c.q, c.r, -c.q - c.r)).every((c) => where(seat).has(c.id));
    expect(inCorner(0, (_q, r) => r < -4)).toBe(true);
    expect(inCorner(1, (_q, _r, s) => s < -4)).toBe(true);
    expect(inCorner(2, (q) => q < -4)).toBe(true);
    expect([0, 1, 2].map((seat) => where(seat).size)).toEqual([10, 10, 10]);
  });

  it('the goal of each seat is the opposite corner, empty at the start', () => {
    const owner = initialOwners(THREE);
    for (const goal of goalCells(THREE)) {
      expect(goal).toHaveLength(10);
      expect(goal.every((c) => owner[c] === -1)).toBe(true);
    }
    const centre = (cells: readonly number[]): [number, number] => [
      cells.reduce((a, c) => a + CELLS[c]!.q, 0) / cells.length,
      cells.reduce((a, c) => a + CELLS[c]!.r, 0) / cells.length,
    ];
    startCells(THREE).forEach((start, seat) => {
      const [sq, sr] = centre(start);
      const [gq, gr] = centre(goalCells(THREE)[seat]!);
      expect([sq + gq, sr + gr]).toEqual([0, 0]); // the goal is the point reflection of the start corner
    });
  });

  it('the mover of ply n is n % 3, and that seat wins when it fills its goal', () => {
    const owner = new Int8Array(121).fill(-1);
    for (const c of goalCells(THREE)[2]!) owner[c] = 2;
    expect(hasWon(owner, 2, THREE)).toBe(true);
    expect(hasWon(owner, 0, THREE)).toBe(false);
    expect(outcomeOf(owner, [0, 0, 0], THREE)).toEqual({ kind: 'win', winner: 2 });
    expect(outcomeOf(owner, [0, 0], THREE)).toBeNull(); // seat 1 moved last and has not won
  });

  it('ends in a draw at 300 plies for three players, not at 200', () => {
    const empty = new Int8Array(121).fill(-1);
    expect(outcomeOf(empty, new Array(200).fill(0), THREE)).toBeNull();
    expect(outcomeOf(empty, new Array(299).fill(0), THREE)).toBeNull();
    expect(outcomeOf(empty, new Array(300).fill(0), THREE)).toEqual({ kind: 'move-limit' });
  });

  it('replays moves of all three seats', () => {
    const history = [
      makeMove(cellId(1, -5), cellId(0, -4)),
      makeMove(cellId(4, 1), cellId(3, 1)),
      makeMove(cellId(-5, 1), cellId(-4, 1)),
    ];
    const owner = replay(history, THREE);
    expect([owner[cellId(0, -4)], owner[cellId(3, 1)], owner[cellId(-4, 1)]]).toEqual([0, 1, 2]);
    expect(() => applyMove(initialOwners(THREE), makeMove(cellId(0, 0), cellId(1, 0)))).toThrow(RangeError);
  });

  it('two-player start and goals are unchanged', () => {
    expect(initialOwners(DEFAULT_VARIANT)).toEqual(initialOwners());
    expect(startCells(DEFAULT_VARIANT)).toHaveLength(2);
  });
});

describe('ranking rule of a variant', () => {
  const FULL: Variant = { jump: 'adjacent', players: 3, ranking: 'full' };

  it('a missing field is first-winner rules, and the default variant keeps its shape', () => {
    expect(rankingOf(THREE)).toBe('first');
    expect(rankingOf(FULL)).toBe('full');
    expect(DEFAULT_VARIANT).toEqual({ jump: 'adjacent', players: 2 });
    expect(isDefault({ jump: 'adjacent', players: 2, ranking: 'first' })).toBe(true);
    expect(isDefault({ jump: 'adjacent', players: 2, ranking: 'full' })).toBe(false);
  });

  it('the ranking takes part in equality and in the key, and a first-winner key is unchanged', () => {
    expect(sameVariant(THREE, { ...THREE, ranking: 'first' })).toBe(true);
    expect(sameVariant(THREE, FULL)).toBe(false);
    expect(variantKey(THREE)).toBe('adjacent-3');
    expect(variantKey({ ...THREE, ranking: 'first' })).toBe('adjacent-3');
    expect(variantKey(FULL)).toBe('adjacent-3-full');
  });

  it('a seat plays on after a first winner only with full ranking and three players', () => {
    expect(playsOn(FULL)).toBe(true);
    expect(playsOn(THREE)).toBe(false);
    expect(playsOn({ jump: 'long', players: 2, ranking: 'full' })).toBe(false);
  });

  it('withRanking sets or drops the field', () => {
    expect(withRanking(THREE, 'full')).toEqual(FULL);
    expect(withRanking(FULL, 'first')).toEqual(THREE);
    expect('ranking' in withRanking(FULL, 'first')).toBe(false);
  });
});

describe('the foreign-corner rule (camps)', () => {
  const base: Variant = { jump: 'adjacent', players: 2 };

  it('is closed when the field is missing, and closed drops the field again', () => {
    expect(campsOf(base)).toBe('closed');
    expect(withCamps(base, 'open')).toEqual({ ...base, camps: 'open' });
    expect(withCamps(withCamps(base, 'open'), 'closed')).toEqual(base);
  });

  it('makes a variant non-default and different, and only the other rules change the key', () => {
    expect(isDefault(withCamps(base, 'closed'))).toBe(true);
    expect(isDefault(withCamps(base, 'pass-through'))).toBe(false);
    expect(sameVariant(base, { ...base, camps: 'closed' })).toBe(true);
    expect(sameVariant(base, withCamps(base, 'open'))).toBe(false);
    expect(sameVariant(withCamps(base, 'open'), withCamps(base, 'pass-through'))).toBe(false);
    expect(variantKey(base)).toBe('adjacent-2');
    expect(variantKey(withCamps(base, 'open'))).toBe('adjacent-2-open');
    expect(variantKey(withCamps({ jump: 'long', players: 3, ranking: 'full' }, 'pass-through'))).toBe('long-3-full-pass-through');
  });
});
