import { describe, expect, it } from 'vitest';
import { SCORE } from '../engines/analysis';
import { MIN_VISITS, TRAP_DELTA, findDivergences, needsProbe } from './divergence';

// Classical scores relative to a best of 100: loss 20 = top, 60 = good, 120 = fair, 250 = weak, 400 = poor (bands.ts: 30, 90, 180, 300).
const BEST = 100;
const S = { top: 80, good: 40, fair: -20, weak: -150, poor: -300 };

/** Moves 1..n; move 1 is classically best unless scores say otherwise. */
function pos(scores: number[], visits: number[], values: number[]) {
  const moves = scores.map((_, i) => i + 1);
  return { classical: { moves, scores }, net: { moves, visits, values } };
}
const none = new Map<number, number>();

describe('constants', () => {
  it('have the values calibrated by the simulation of 2026-11-27', () => {
    expect(TRAP_DELTA).toBe(0.25);
    expect(MIN_VISITS).toBe(16);
  });
});

describe('trap: delta boundary (root values, best move has enough visits)', () => {
  // move 1 best classically, move 2 favourite; values exact in binary
  const at = (v1: number) => pos([BEST, S.top], [MIN_VISITS, 100], [v1, 0.5]);
  it('a value exactly TRAP_DELTA below the favourite is a trap', () => {
    const { classical, net } = at(0.25);
    expect(findDivergences(classical, net, none).trap).toEqual({ move: 1, netValue: 0.25, favouriteValue: 0.5, classicalBand: 'much-better' });
  });
  it('a value just under the delta is no trap', () => {
    const { classical, net } = at(0.2501);
    expect(findDivergences(classical, net, none).trap).toBeUndefined();
  });
  it('a value far below is a trap', () => {
    const { classical, net } = at(-0.9);
    expect(findDivergences(classical, net, none).trap?.move).toBe(1);
  });
});

describe('trap: other cases', () => {
  it('none when the favourite is the classically best move', () => {
    const { classical, net } = pos([BEST, S.poor], [100, 5], [0.5, -0.9]);
    expect(findDivergences(classical, net, none).trap).toBeUndefined();
  });
  it('the best move is the classical maximum, wherever it stands in the list', () => {
    const classical = { moves: [7, 3, 9], scores: [S.top, BEST, S.fair] };
    const net = { moves: [7, 3, 9], visits: [100, 30, 1], values: [0.6, -0.5, 0] };
    expect(findDivergences(classical, net, none).trap).toMatchObject({ move: 3, netValue: -0.5, favouriteValue: 0.6 });
  });
  it('ties between best moves go to the one with the most net visits, then the lowest code', () => {
    const classical = { moves: [4, 5, 6, 8], scores: [BEST, BEST, BEST, S.top] };
    const net = { moves: [4, 5, 6, 8], visits: [20, 30, 30, 200], values: [-0.9, -0.9, -0.9, 0.5] };
    expect(findDivergences(classical, net, none).trap?.move).toBe(5);
  });
  it('favourite ties go to the lowest move code', () => {
    // moves 2 and 3 both have 50 visits; move 2 is fair, move 3 would be top: the special must be move 2
    const classical = { moves: [1, 3, 2], scores: [BEST, S.top, S.fair] };
    const net = { moves: [1, 3, 2], visits: [10, 50, 50], values: [0, 0, 0] };
    expect(findDivergences(classical, net, none).special?.move).toBe(2);
  });
});

describe('visits below MIN_VISITS: probes', () => {
  const p = () => pos([BEST, S.top], [MIN_VISITS - 1, 100], [-0.9, 0.5]);
  it('no probe, no trap, although the root value would show one', () => {
    const { classical, net } = p();
    expect(findDivergences(classical, net, none).trap).toBeUndefined();
  });
  it('one probe missing, no trap', () => {
    const { classical, net } = p();
    expect(findDivergences(classical, net, new Map([[1, -0.9]])).trap).toBeUndefined();
    expect(findDivergences(classical, net, new Map([[2, 0.5]])).trap).toBeUndefined();
  });
  it('probe against probe, not against the root value', () => {
    const { classical, net } = p();
    // probes agree (gap 0): no trap even though root values differ by 1.4
    expect(findDivergences(classical, net, new Map([[1, 0.4], [2, 0.4]])).trap).toBeUndefined();
    // probes differ by exactly the delta: trap, with the probe values reported
    expect(findDivergences(classical, net, new Map([[1, 0.25], [2, 0.5]])).trap).toMatchObject({ move: 1, netValue: 0.25, favouriteValue: 0.5 });
  });
  it('exactly MIN_VISITS uses the root value and needs no probe', () => {
    const { classical, net } = pos([BEST, S.top], [MIN_VISITS, 100], [-0.9, 0.5]);
    expect(findDivergences(classical, net, none).trap?.move).toBe(1);
    expect(needsProbe(classical, net)).toEqual([]);
  });
  it('needsProbe names the best move and the favourite below MIN_VISITS', () => {
    const { classical, net } = p();
    expect(needsProbe(classical, net)).toEqual([1, 2]);
  });
  it('needsProbe is empty when the best move is the favourite', () => {
    const { classical, net } = pos([BEST, S.top], [3, 2], [0, 0]);
    expect(needsProbe(classical, net)).toEqual([]);
  });
  it('a best move that the net never visited counts as 0 visits', () => {
    const classical = { moves: [1, 2], scores: [BEST, S.top] };
    const net = { moves: [2], visits: [100], values: [0.5] };
    expect(needsProbe(classical, net)).toEqual([1, 2]);
    expect(findDivergences(classical, net, new Map([[1, -0.5], [2, 0.5]])).trap?.move).toBe(1);
  });
});

describe('special: classical band of the favourite', () => {
  const specialFor = (favScore: number) => {
    const { classical, net } = pos([BEST, favScore], [1, 100], [0.1, 0.2]);
    return findDivergences(classical, net, none).special;
  };
  it('top and good: no special', () => {
    expect(specialFor(S.top)).toBeUndefined();
    expect(specialFor(S.good)).toBeUndefined();
  });
  it.each([
    ['fair', S.fair, 'equal'],
    ['weak', S.weak, 'worse'],
    ['poor', S.poor, 'much-worse'],
  ] as const)('%s: special', (_n, score, band) => {
    expect(specialFor(score)).toEqual({ move: 2, netValue: 0.2, favouriteValue: 0.2, classicalBand: band });
  });
  it('no special when the favourite is the best move', () => {
    const { classical, net } = pos([BEST, S.poor], [100, 1], [0.1, 0.2]);
    expect(findDivergences(classical, net, none).special).toBeUndefined();
  });
  it('the band edges: loss 180 is fair, 181 is weak', () => {
    const fair = pos([BEST, BEST - 180], [1, 9], [0, 0]);
    const weak = pos([BEST, BEST - 181], [1, 9], [0, 0]);
    expect(findDivergences(fair.classical, fair.net, none).special?.classicalBand).toBe('equal');
    expect(findDivergences(weak.classical, weak.net, none).special?.classicalBand).toBe('worse');
  });
  it('special and trap can occur together, at most one of each', () => {
    const r = pos([BEST, S.top, S.poor, S.weak], [40, 100, 100, 100], [-0.5, 0.5, 0.5, 0.5]);
    // favourite = move 3 (poor; tie on visits with 2 and 4 goes to the lowest code, which is 2 = top)
    expect(findDivergences(r.classical, r.net, none).special).toBeUndefined();
    const r2 = pos([BEST, S.poor, S.weak], [40, 100, 100], [-0.5, 0.5, 0.5]);
    const out = findDivergences(r2.classical, r2.net, none);
    expect(out.special?.move).toBe(2);
    expect(out.trap?.move).toBe(1);
  });
});

describe('no marks', () => {
  const cases: Array<[string, number[]]> = [
    ['best score a forced win', [SCORE.mate - 3, S.poor]],
    ['best score a forced loss', [-(SCORE.mate - 3), -(SCORE.mate - 5)]],
    ['best score in the race band', [SCORE.raceBase, S.poor]],
    ['best score a negative race score', [-SCORE.raceBase - 200, -SCORE.raceBase - 800]],
  ];
  it.each(cases)('%s', (_n, scores) => {
    const { classical, net } = pos(scores, [1, 100], [0.9, -0.9]);
    expect(findDivergences(classical, net, new Map([[1, 0.9], [2, -0.9]]))).toEqual({});
    expect(needsProbe(classical, net)).toEqual([]);
  });
  it('the largest ordinary score still marks', () => {
    const { classical, net } = pos([SCORE.raceBase - 1, S.poor], [1, 100], [0, 0]);
    expect(findDivergences(classical, net, none).special?.move).toBe(2);
  });
  it('one move only', () => {
    const r = pos([BEST], [100], [0.5]);
    expect(findDivergences(r.classical, r.net, none)).toEqual({});
    expect(needsProbe(r.classical, r.net)).toEqual([]);
  });
  it('no moves at all', () => {
    expect(findDivergences({ moves: [], scores: [] }, { moves: [], visits: [], values: [] }, none)).toEqual({});
  });
});

describe('length mismatch', () => {
  it('throws RangeError for classical and net arrays', () => {
    const ok = pos([BEST, S.top], [1, 2], [0, 0]);
    expect(() => findDivergences({ moves: [1, 2], scores: [BEST] }, ok.net, none)).toThrow(RangeError);
    expect(() => findDivergences(ok.classical, { ...ok.net, visits: [1] }, none)).toThrow(RangeError);
    expect(() => findDivergences(ok.classical, { ...ok.net, values: [0] }, none)).toThrow(RangeError);
    expect(() => needsProbe({ moves: [1, 2], scores: [BEST] }, ok.net)).toThrow(RangeError);
  });
});
