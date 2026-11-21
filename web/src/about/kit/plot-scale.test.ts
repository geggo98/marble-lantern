// Scales, ticks and the two view boxes of the Plot (design notes, section 6, "Plot"). Expected values are hand calculations.
import { describe, expect, it } from 'vitest';
import { PLOT_COMPACT, PLOT_WIDE, categoryScale, formatCompact, linearScale, linearTicks, logScale, logTicks, plotBoxFor } from './plot-scale';

describe('view boxes', () => {
  it('has the sizes of the design notes', () => {
    expect([PLOT_WIDE.width, PLOT_WIDE.height, PLOT_WIDE.margin]).toEqual([640, 280, { left: 52, right: 16, top: 28, bottom: 44 }]);
    expect([PLOT_COMPACT.width, PLOT_COMPACT.height, PLOT_COMPACT.margin]).toEqual([260, 180, { left: 40, right: 10, top: 28, bottom: 40 }]);
  });

  it('switches at a measured width of 640 px: 639 is compact, 640 is wide', () => {
    expect(plotBoxFor(320).name).toBe('compact');
    expect(plotBoxFor(639).name).toBe('compact');
    expect(plotBoxFor(640).name).toBe('wide');
    expect(plotBoxFor(1280).name).toBe('wide');
  });

  it('takes the wide box while the width is unknown (0), and honours a brief height', () => {
    expect(plotBoxFor(0).name).toBe('wide');
    expect(plotBoxFor(300, 160)).toMatchObject({ name: 'compact', width: 260, height: 160 });
  });
});

describe('scales', () => {
  it('maps a linear domain to a range, also reversed (y axes) and outside the domain', () => {
    const s = linearScale([0, 10], [100, 200]);
    expect([s(0), s(5), s(10), s(20)]).toEqual([100, 150, 200, 300]);
    const y = linearScale([0, 11], [220, 28]);
    expect(y(0)).toBe(220);
    expect(y(11)).toBe(28);
  });

  it('maps a log domain by the logarithm: one decade is a third of three decades', () => {
    const s = logScale([10, 10000], [0, 300]);
    expect(s(10)).toBeCloseTo(0, 9);
    expect(s(100)).toBeCloseTo(100, 9);
    expect(s(1000)).toBeCloseTo(200, 9);
    expect(s(10000)).toBeCloseTo(300, 9);
    expect(s(500)).toBeCloseTo(100 * Math.log10(50), 9);
  });

  it('refuses a log axis through zero, and a value that is not positive', () => {
    expect(() => logScale([0, 10], [0, 1])).toThrow(RangeError);
    expect(() => logScale([-1, 10], [0, 1])).toThrow(RangeError);
    expect(() => logScale([1, 10], [0, 1])(0)).toThrow(RangeError);
    expect(() => linearScale([3, 3], [0, 1])).toThrow(RangeError);
  });

  it('puts category i in the middle of its band', () => {
    const c = categoryScale(4, [0, 400]);
    expect([c(0), c(1), c(3)]).toEqual([50, 150, 350]);
    expect(() => categoryScale(0, [0, 1])).toThrow(RangeError);
  });
});

describe('ticks', () => {
  it('linear: round steps inside the domain', () => {
    expect(linearTicks(0, 100)).toEqual([0, 20, 40, 60, 80, 100]);
    expect(linearTicks(0, 11)).toEqual([0, 5, 10]);
    const t = linearTicks(0.44, 0.64);
    expect(t.every((v) => v >= 0.44 && v <= 0.64)).toBe(true);
    expect(t.length).toBeGreaterThanOrEqual(3);
  });

  it('log: powers of ten are the major ticks, 2 to 5 times each power the minor ticks', () => {
    const t = logTicks(50, 100000);
    expect(t.major).toEqual([100, 1000, 10000, 100000]);
    expect(t.minor).toEqual([50, 200, 300, 400, 500, 2000, 3000, 4000, 5000, 20000, 30000, 40000, 50000]);
  });

  it('log: labelled values replace the powers and leave the minor ticks that are not labelled', () => {
    const t = logTicks(700, 700000, [1000, 5000, 20000, 100000, 500000, 9e9]);
    expect(t.major).toEqual([1000, 5000, 20000, 100000, 500000]); // 9e9 is outside the domain
    expect(t.minor).toEqual([2000, 3000, 4000, 30000, 40000, 50000, 200000, 300000, 400000]); // 5000 and 20000 are major
  });

  it('log: a domain that includes zero is an error', () => {
    expect(() => logTicks(0, 10)).toThrow(RangeError);
  });

  it('formats 1500 as 1.5k, 20000 as 20k, 1000000 as 1M and small numbers unchanged', () => {
    expect([formatCompact(1500), formatCompact(20000), formatCompact(1e6), formatCompact(0.05), formatCompact(0), formatCompact(999)]).toEqual(['1.5k', '20k', '1M', '0.05', '0', '999']);
  });
});
