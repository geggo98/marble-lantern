// Model of figure J5: the derived table of the design spec (section 5), the verdict thresholds, the nearest-batch rule, the split of the
// C++ twin, and the number format. Expected values are written out by hand from the spec, not computed with the code under test.
import { describe, expect, it } from 'vitest';
import {
  BATCHES, J5_DATA, callPoint, cpuPerPosition, durationParts, factorValue, isUseActive, kernelsOf, nearestBatch, percentText, presetFor, rateValue, seriesOf, sig2,
  splitFor, verdictOf, widthPercent,
} from './j5-model';
import type { J5Data, Machine, Net } from './j5-model';

const rest = (m: Machine, n: Net): number[] => BATCHES.map((b) => Math.round(callPoint(m, n, b).share * 100));

describe('derived table of the spec (rest share at batch 1, 16, 64, 256, 1,024)', () => {
  it.each([
    ['mac', 'SMALL', [98, 78, 39, 6, 0]],
    ['mac', 'LARGE', [95, 41, 11, 1, 0]],
    ['t4', 'SMALL', [95, 64, 16, 2, 0]],
    ['t4', 'LARGE', [94, 51, 16, 2, 0]],
  ] as const)('%s %s', (m, n, expected) => {
    expect(rest(m, n)).toEqual(expected);
  });

  it.each([
    ['mac', 'SMALL', 57],
    ['mac', 'LARGE', 21],
    ['t4', 'SMALL', 18],
    ['t4', 'LARGE', 17],
  ] as const)('factor per position at batch 256, %s %s: %d', (m, n, expected) => {
    expect(factorValue(callPoint(m, n, 256).factor)).toBe(expected);
  });

  it.each([
    ['mac', 'SMALL', { unit: 'us', value: 340 }],
    ['mac', 'LARGE', { unit: 'ms', value: 1.8 }],
    ['t4', 'SMALL', { unit: 'us', value: 200 }],
    ['t4', 'LARGE', { unit: 'ms', value: 1.1 }],
  ] as const)('one CPU thread per position, %s %s', (m, n, expected) => {
    expect(durationParts(cpuPerPosition(m, n))).toEqual(expected);
  });

  it('prints the batch-1 call of Mac SMALL as 510 µs and its work as 8.4 µs', () => {
    const p = callPoint('mac', 'SMALL', 1);
    expect(durationParts(p.ms)).toEqual({ unit: 'us', value: 510 });
    expect(durationParts(p.work)).toEqual({ unit: 'us', value: 8.4 });
  });

  it('has the largest batch without any rest, exactly 0 (no floating point residue), for all four series', () => {
    for (const m of ['mac', 't4'] as const) {
      for (const n of ['SMALL', 'LARGE'] as const) {
        const p = callPoint(m, n, 1024);
        expect(p.rest, `${m} ${n}`).toBe(0);
        expect(p.work).toBe(p.ms);
        expect(percentText(p.share)).toBe('0 %');
      }
    }
  });

  it('keeps work plus rest equal to the call time at every batch', () => {
    for (const b of BATCHES) {
      const p = callPoint('t4', 'LARGE', b);
      expect(p.work + p.rest).toBeCloseTo(p.ms, 9);
      expect(p.work).toBeGreaterThan(0);
    }
  });
});

describe('verdict', () => {
  it('switches at 50 % and at 10 % of rest, the limit belonging to the higher verdict', () => {
    expect(verdictOf(0.99)).toBe('dominates');
    expect(verdictOf(0.5)).toBe('dominates');
    expect(verdictOf(0.4999)).toBe('mixed');
    expect(verdictOf(0.1)).toBe('mixed');
    expect(verdictOf(0.0999)).toBe('amortized');
    expect(verdictOf(0)).toBe('amortized');
  });

  it('follows the data: Mac SMALL is dominated at 1, mixed at 64 (39 %), amortized at 256', () => {
    expect(BATCHES.map((b) => callPoint('mac', 'SMALL', b).verdict)).toEqual(['dominates', 'dominates', 'mixed', 'amortized', 'amortized']);
    expect(BATCHES.map((b) => callPoint('t4', 'LARGE', b).verdict)).toEqual(['dominates', 'dominates', 'mixed', 'amortized', 'amortized']);
  });
});

describe('series and nearest batch', () => {
  it('has the five stops for every series, and a call time of batch x 1000 / evals', () => {
    for (const m of ['mac', 't4'] as const) {
      for (const n of ['SMALL', 'LARGE'] as const) {
        expect(seriesOf(m, n).map((r) => r.batch)).toEqual([1, 16, 64, 256, 1024]);
      }
    }
    expect(seriesOf('mac', 'SMALL')[3]!.ms).toBeCloseTo((256 * 1000) / 111409, 9);
  });

  it('agrees with the printed Mac times of docs/tornado.md within rounding', () => {
    for (const n of ['SMALL', 'LARGE'] as const) {
      J5_DATA.machines.mac.series[n].forEach((row, i) => {
        const ms = seriesOf('mac', n)[i]!.ms;
        expect(Math.abs(ms - row.ms!) / row.ms!, `${n} ${row.batch}`).toBeLessThan(0.02);
      });
    }
  });

  it('takes the nearest batch on the log scale and the smaller one at a tie', () => {
    expect(nearestBatch(BATCHES, 1)).toBe(1);
    expect(nearestBatch(BATCHES, 20)).toBe(16);
    expect(nearestBatch(BATCHES, 2)).toBe(1); // log2: 1 is 1 away, 16 is 3 away
    expect(nearestBatch([1, 4, 16], 2)).toBe(1); // tie: the smaller
    expect(nearestBatch([1, 4, 16], 8)).toBe(4);
    expect(nearestBatch(BATCHES, 5000)).toBe(1024);
  });

  it('uses the nearest batch when a series lacks the size (later data changes)', () => {
    const data = structuredClone(J5_DATA) as J5Data;
    data.machines.t4.series.SMALL = data.machines.t4.series.SMALL.filter((r) => r.batch !== 64);
    expect(callPoint('t4', 'SMALL', 64, data).batch).toBe(16); // tie on the log scale: the smaller one
    expect(callPoint('t4', 'SMALL', 256, data).batch).toBe(256);
  });

  it('has 11 kernels for SMALL and 15 for LARGE on both machines', () => {
    expect([kernelsOf('mac', 'SMALL'), kernelsOf('mac', 'LARGE'), kernelsOf('t4', 'SMALL'), kernelsOf('t4', 'LARGE')]).toEqual([11, 15, 11, 15]);
  });
});

describe('split of the C++ twin', () => {
  it('has no split for Mac SMALL, nor for Mac LARGE at 16, nor for the T4 at 16 and 64', () => {
    for (const b of BATCHES) expect(splitFor('mac', 'SMALL', b), `mac SMALL ${b}`).toBeNull();
    expect(splitFor('mac', 'LARGE', 16)).toBeNull();
    for (const n of ['SMALL', 'LARGE'] as const) {
      expect(splitFor('t4', n, 16)).toBeNull();
      expect(splitFor('t4', n, 64)).toBeNull();
    }
  });

  it('has the Mac split as GPU time and outside time, with the copies as a number only', () => {
    const s = splitFor('mac', 'LARGE', 1)!;
    expect(s.total).toBe(0.786);
    expect(s.segs.map((x) => [x.id, x.fill])).toEqual([['gpu', 'solid'], ['outside', 'hatch']]);
    expect(s.segs[0]!.ms).toBe(0.529);
    expect(s.segs[1]!.ms).toBeCloseTo(0.257, 9);
    expect(s.copies).toBeCloseTo(0.005, 9);
    expect(s.segs[0]!.ms + s.segs[1]!.ms).toBeCloseTo(s.total, 9);
  });

  it('has the T4 split as copy in, kernels, copy out, summing to the device path', () => {
    const s = splitFor('t4', 'SMALL', 256)!;
    expect(s.segs.map((x) => [x.id, x.fill])).toEqual([['h2d', 'dots'], ['kernel', 'solid'], ['d2h', 'dots']]);
    expect(s.total).toBeCloseTo(0.269 + 2.43 + 0.044, 9);
    expect(s.copies).toBeUndefined();
    // the copies are 11 % of the device path (facts/gpu.md): the figure must not suggest that they are the cost
    expect((s.segs[0]!.ms + s.segs[2]!.ms) / s.total).toBeCloseTo(0.114, 2);
  });
});

describe('cards and presets', () => {
  it('marks the pool from batch 64, the single search at batch 1, and never the tree', () => {
    expect(BATCHES.map((b) => isUseActive('pool', b))).toEqual([false, false, true, true, true]);
    expect(BATCHES.map((b) => isUseActive('one', b))).toEqual([true, false, false, false, false]);
    expect(BATCHES.map((b) => isUseActive('tree', b))).toEqual([false, false, false, false, false]);
  });

  it('presses `one` at batch 1, `pool` at batch 256 and nothing elsewhere', () => {
    expect(BATCHES.map((b) => presetFor(b))).toEqual(['one', null, null, 'pool', null]);
  });
});

describe('number format', () => {
  it('prints two significant digits, µs below 1 ms and ms from 1 ms', () => {
    expect(durationParts(0.51)).toEqual({ unit: 'us', value: 510 });
    expect(durationParts(0.0084)).toEqual({ unit: 'us', value: 8.4 });
    expect(durationParts(2.3)).toEqual({ unit: 'ms', value: 2.3 });
    expect(durationParts(48.1)).toEqual({ unit: 'ms', value: 48 });
    expect(durationParts(1.0)).toEqual({ unit: 'ms', value: 1 });
    expect(durationParts(0.01)).toEqual({ unit: 'us', value: 10 });
    expect(durationParts(0.1)).toEqual({ unit: 'us', value: 100 });
  });

  it('never prints 1000 µs: a value that rounds to 1 ms goes to ms', () => {
    expect(durationParts(0.9996)).toEqual({ unit: 'ms', value: 1 });
    expect(durationParts(0.9994)).toEqual({ unit: 'ms', value: 1 });
    expect(durationParts(0.9949)).toEqual({ unit: 'us', value: 990 });
  });

  it('rounds rates to two significant digits as J1 does', () => {
    expect(rateValue(111409)).toBe(110000);
    expect(rateValue(1968)).toBe(2000);
    expect(rateValue(25980)).toBe(26000);
    expect(sig2(0)).toBe(0);
  });

  it('writes shares: 0 %, < 1 %, and whole percent', () => {
    expect(percentText(0)).toBe('0 %');
    expect(percentText(0.004)).toBe('< 1 %');
    expect(percentText(0.0099)).toBe('< 1 %');
    expect(percentText(0.01)).toBe('1 %');
    expect(percentText(0.984)).toBe('98 %');
  });

  it('writes the factor as 1 below 1.05', () => {
    expect(factorValue(1)).toBe(1);
    expect(factorValue(1.04)).toBe(1);
    expect(factorValue(1.05)).toBe(1.1);
    expect(factorValue(56.7)).toBe(57);
  });

  it('keeps a bar width between 0 and 100 percent', () => {
    expect(widthPercent(1, 4)).toBe(25);
    expect(widthPercent(0, 4)).toBe(0);
    expect(widthPercent(5, 4)).toBe(100);
    expect(widthPercent(1, 0)).toBe(0);
  });
});
