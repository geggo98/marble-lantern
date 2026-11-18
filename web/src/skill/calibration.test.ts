import { describe, expect, it } from 'vitest';
import { estimateSkill, isotonicNonIncreasing, ladderViolations, levelPosition, nodesForStat, resolvedTop } from './calibration';
import type { Calibration } from './calibration';
import { LOSS_CAP } from './stats';

describe('isotonicNonIncreasing (pool adjacent violators)', () => {
  it('leaves a non-increasing series alone', () => {
    expect(isotonicNonIncreasing([9, 7, 7, 3, 1])).toEqual([9, 7, 7, 3, 1]);
  });
  it('pools a violation into the weighted mean', () => {
    expect(isotonicNonIncreasing([10, 4, 6, 2])).toEqual([10, 5, 5, 2]);
    const r = isotonicNonIncreasing([10, 4, 8], [1, 1, 2]);
    expect(r[0]).toBe(10);
    expect(r[1]).toBeCloseTo(20 / 3, 12);
    expect(r[2]).toBeCloseTo(20 / 3, 12);
  });
  it('cascades: a pooled block can violate its left neighbour', () => {
    const r = isotonicNonIncreasing([5, 4, 9, 9]);
    for (const x of r) expect(x).toBeCloseTo(27 / 4, 12);
  });
  it('handles empty and single input', () => {
    expect(isotonicNonIncreasing([])).toEqual([]);
    expect(isotonicNonIncreasing([3])).toEqual([3]);
  });
  it('result is non-increasing and keeps the weighted total', () => {
    const v = [30, 12, 40, 8, 9, 2, 5, 1];
    const w = [3, 1, 2, 5, 1, 4, 2, 2];
    const r = isotonicNonIncreasing(v, w);
    for (let i = 1; i < r.length; i++) expect(r[i]!).toBeLessThanOrEqual(r[i - 1]! + 1e-12);
    const total = (a: number[]): number => a.reduce((s, x, i) => s + x * w[i]!, 0);
    expect(total(r)).toBeCloseTo(total(v), 9);
  });
});

describe('ladderViolations', () => {
  it('counts the places where a larger budget has a larger loss', () => {
    expect(ladderViolations([9, 7, 8, 3, 4, 1])).toBe(2);
    expect(ladderViolations([9, 7, 7, 3])).toBe(0);
  });
});

const points = [
  { nodes: 1000, stat: 80 },
  { nodes: 10_000, stat: 40 },
  { nodes: 100_000, stat: 20 },
];

describe('nodesForStat', () => {
  it('interpolates linearly between ladder points on log(nodes)', () => {
    expect(nodesForStat(60, points).nodes).toBeCloseTo(Math.sqrt(1000 * 10_000), 6);
    expect(nodesForStat(30, points).nodes).toBeCloseTo(Math.sqrt(10_000 * 100_000), 6);
  });
  it('returns the ladder point itself at a ladder value', () => {
    expect(nodesForStat(40, points).nodes).toBeCloseTo(10_000, 6);
  });
  it('clamps at both ends and says so', () => {
    expect(nodesForStat(500, points)).toEqual({ nodes: 1000, clamp: 'weak' });
    expect(nodesForStat(80, points).clamp).toBeNull(); // exactly at the end is not beyond it
    expect(nodesForStat(0, points)).toEqual({ nodes: 100_000, clamp: 'strong' });
  });
  it('is monotone: a lower loss never maps to fewer nodes', () => {
    let last = 0;
    for (let s = 100; s >= 0; s -= 2.5) {
      const n = nodesForStat(s, points).nodes;
      expect(n).toBeGreaterThanOrEqual(last);
      last = n;
    }
  });
  it('puts a value on a plateau in the middle (in log nodes) of the plateau', () => {
    const flat = [{ nodes: 1000, stat: 50 }, { nodes: 4000, stat: 30 }, { nodes: 16_000, stat: 30 }, { nodes: 64_000, stat: 10 }];
    expect(nodesForStat(30, flat).nodes).toBeCloseTo(Math.sqrt(4000 * 16_000), 6);
  });
  it('with a single point returns that point', () => {
    expect(nodesForStat(5, [{ nodes: 500, stat: 20 }])).toEqual({ nodes: 500, clamp: 'strong' });
  });
});

describe('resolvedTop', () => {
  const nodes = [1000, 2000, 5000, 10_000, 20_000];
  it('is the first budget whose smoothed value is within one standard error of the last', () => {
    expect(resolvedTop(nodes, [20, 15, 10, 8.5, 8], [1, 1, 1, 1, 1])).toBe(10_000);
    expect(resolvedTop(nodes, [20, 15, 10, 9.5, 8], [1, 1, 1, 1, 1])).toBe(20_000);
    expect(resolvedTop(nodes, [8, 8, 8, 8, 8], [0.1, 0.1, 0.1, 0.1, 0.1])).toBe(1000);
  });
  it('takes the standard error of the rung itself', () => {
    expect(resolvedTop(nodes, [20, 15, 10, 9.5, 8], [1, 1, 1, 1.6, 1])).toBe(10_000);
  });
});

describe('levelPosition', () => {
  const levels = [1000, 5000, 20_000, 100_000, 500_000];
  it('is the index at a level and fractional between two', () => {
    expect(levelPosition(20_000, levels)).toBeCloseTo(2, 9);
    expect(levelPosition(Math.sqrt(5000 * 20_000), levels)).toBeCloseTo(1.5, 9);
  });
  it('goes below 0 and above the last index outside the levels', () => {
    expect(levelPosition(500, levels)).toBeLessThan(0);
    expect(levelPosition(2_000_000, levels)).toBeGreaterThan(4);
  });
});

const cal: Calibration = {
  v: 1,
  statistic: { name: 'mean of losses capped at the cap', cap: LOSS_CAP },
  analysisNodes: 20_000,
  engine: { id: 'cpp-wasm', version: 'test' },
  date: '2026-01-01',
  openingPlies: 4,
  seeds: [1],
  ladder: points.map((p) => ({ ...p, moves: 100, games: 4, median: 0, se: 1, smoothed: p.stat })),
  resolvedUpTo: 100_000,
};

describe('estimateSkill', () => {
  const quiet = (n: number, loss: number): number[] => Array.from({ length: n }, () => loss);
  it('gives no estimate below 8 analysed moves, and says how many there are', () => {
    expect(estimateSkill(quiet(7, 40), cal)).toEqual({ enough: false, n: 7, needed: 8 });
    expect(estimateSkill([], cal)).toEqual({ enough: false, n: 0, needed: 8 });
  });
  it('gives an estimate at 8 moves, with an interval that contains it', () => {
    const e = estimateSkill([10, 80, 30, 50, 45, 20, 60, 35], cal);
    if (!e.enough) throw new Error('expected an estimate');
    expect(e.n).toBe(8);
    expect(e.lowNodes).toBeLessThanOrEqual(e.nodes);
    expect(e.highNodes).toBeGreaterThanOrEqual(e.nodes);
    expect(e.lowNodes).toBeLessThan(e.highNodes);
  });
  it('maps equal losses to the ladder value', () => {
    const e = estimateSkill(quiet(20, 40), cal);
    if (!e.enough) throw new Error('expected an estimate');
    expect(e.nodes).toBeCloseTo(10_000, 3);
  });
  it('a player without any loss gets the strongest end, a hopeless one the weakest', () => {
    const best = estimateSkill(quiet(12, 0), cal);
    const worst = estimateSkill(quiet(12, 1_000_000), cal);
    if (!best.enough || !worst.enough) throw new Error('expected estimates');
    expect(best.nodes).toBe(100_000);
    expect(best.clamp).toBe('strong');
    expect(worst.nodes).toBe(1000);
    expect(worst.clamp).toBe('weak');
  });
  it('ignores rungs above resolvedUpTo: better than the last resolved rung is "strong" at that rung', () => {
    const cut = { ...cal, resolvedUpTo: 10_000 };
    const e = estimateSkill(Array.from({ length: 12 }, () => 30), cut); // 30 would lie between 10,000 and 100,000
    if (!e.enough) throw new Error('expected an estimate');
    expect(e.nodes).toBe(10_000);
    expect(e.clamp).toBe('strong');
  });
  it('gets stronger when the losses get smaller', () => {
    const at = (loss: number): number => {
      const e = estimateSkill(quiet(12, loss), cal);
      if (!e.enough) throw new Error('expected an estimate');
      return e.nodes;
    };
    expect(at(10)).toBeGreaterThan(at(30));
    expect(at(30)).toBeGreaterThan(at(70));
  });
});
