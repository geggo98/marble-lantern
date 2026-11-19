// The turn order under full ranking, by hand: who finishes in which order, who is to move. Whole games against the real engines:
// engines/ranking.cross.test.ts.
import { describe, expect, it } from 'vitest';
import { applyMove, completeRanking, goalCells, initialOwners, newFinishers, outcomeOf, playState } from './board-state';
import type { Player } from './board-state';
import { makeMove } from '../engines/engine';
import type { Variant } from './variant';
import { tinyLegalMoves } from '../engines/testing/tiny-moves';

const full: Variant = { jump: 'adjacent', players: 3, ranking: 'full' };
const first: Variant = { jump: 'adjacent', players: 3 };

/** A board in which each of `seats` has filled its goal with own pieces. */
function boardWithGoals(seats: readonly Player[]): Int8Array {
  const owner = new Int8Array(initialOwners(full).length).fill(-1);
  for (const seat of seats) for (const c of goalCells(full)[seat]!) owner[c] = seat;
  return owner;
}

describe('newFinishers', () => {
  it('lists the seats whose goal is full in turn order after the mover: mover 0 sees 1 before 2', () => {
    expect(newFinishers(boardWithGoals([1, 2]), 0, [], full)).toEqual([1, 2]);
  });
  it('mover 1 sees 2 before 0: the order follows the mover, not the seat number', () => {
    expect(newFinishers(boardWithGoals([0, 2]), 1, [], full)).toEqual([2, 0]);
  });
  it('the mover comes last', () => {
    expect(newFinishers(boardWithGoals([0, 1]), 0, [], full)).toEqual([1, 0]);
  });
  it('at most two seats finish; a seat that is out finishes only once', () => {
    expect(newFinishers(boardWithGoals([0, 1, 2]), 0, [], full)).toEqual([1, 2]);
    expect(newFinishers(boardWithGoals([0, 1, 2]), 0, [1], full)).toEqual([2]);
    expect(newFinishers(boardWithGoals([0, 1, 2]), 0, [1, 2], full)).toEqual([]);
  });
  it('a goal that is full of the opponents pieces only does not count (Definition 1 needs one own piece)', () => {
    const owner = boardWithGoals([]);
    for (const c of goalCells(full)[1]!) owner[c] = 0;
    expect(newFinishers(owner, 2, [], full)).toEqual([]);
  });
});

describe('playState', () => {
  it('first-winner rules: ply % players, nobody is out', () => {
    const s = playState([makeMove(0, 1), makeMove(2, 3)], first);
    expect(s).toMatchObject({ sideToMove: 2, finishers: [], lastMover: 1, firstFinishAt: null, finishedAt: [] });
  });
  it('an empty history: seat 0 moves, nobody moved before', () => {
    expect(playState([], full)).toMatchObject({ sideToMove: 0, lastMover: null, finishers: [] });
  });
  it('full ranking before any finish plays like first-winner rules', () => {
    const owner = initialOwners(full);
    const history: number[] = [];
    for (let ply = 0; ply < 4; ply++) {
      expect(playState(history, full).sideToMove, `ply ${ply}`).toBe(ply % 3);
      const move = tinyLegalMoves(owner, ply % 3, full)[0]!;
      applyMove(owner, move);
      history.push(move);
    }
  });
});

describe('completeRanking', () => {
  it('two seats out: the seat left takes the last rank', () => {
    expect(completeRanking([2, 0])).toEqual([2, 0, 1]);
    expect(completeRanking([1, 2])).toEqual([1, 2, 0]);
  });
  it('fewer than two seats out: not complete', () => {
    expect(completeRanking([])).toBeNull();
    expect(completeRanking([1])).toBeNull();
  });
});

describe('outcomeOf with full ranking', () => {
  it('is null while fewer than two seats are out, even at a full first goal', () => {
    expect(outcomeOf(initialOwners(full), [], full)).toBeNull();
  });
});
