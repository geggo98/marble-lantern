// V13: the TypeScript schedule against the Java formula, and the presets against lr-presets.json.
// The expected values were computed outside the code under test: the formula of LearningRateSchedule.rate() (nn/src/main/java/sternhalma/nn/
// LearningRateSchedule.java, line 100 for generation 0, line 115 for the self-play cosine) evaluated in Python for the sample steps below.
import { describe, expect, it } from 'vitest';
import { curve, presetSchedules, rateAt, rateText, sameSchedule, scientific } from './lr';
import type { Schedule } from './lr';

const near = (a: number, b: number): void => {
  expect(Math.abs(a - b) / Math.abs(b), `${a} against ${b}`).toBeLessThan(1e-12);
};

describe('rateAt equals the Java formula at six sample steps', () => {
  // generation 0: lr 1e-3, warm-up 200, 5800 steps, cosine to 0.1 lr (docs/training.md line 841)
  const gen0: Schedule = { base: 1e-3, final: 1e-4, warmup: 200, steps: 5800 };
  it.each([
    [0, 5e-6],
    [99, 5e-4],
    [199, 1e-3],
    [200, 1e-3],
    [3000, 0.0005498737527526728],
    [5799, 1e-4],
  ])('generation 0, step %i', (step, expected) => near(rateAt(gen0, step), expected));

  // sweep 1 winner: 3e-4 cosine to 1e-5 over 15,000 steps, warm-up 100
  const sweep: Schedule = { base: 3e-4, final: 1e-5, warmup: 100, steps: 15000 };
  it.each([
    [0, 2.9999999999999997e-6],
    [50, 0.000153],
    [99, 3e-4],
    [100, 3e-4],
    [7500, 0.00015651341511877456],
    [14999, 1e-5],
  ])('sweep 1 winner, step %i', (step, expected) => near(rateAt(sweep, step), expected));

  it('a constant rate (final = base) stays flat after the warm-up', () => {
    const constant: Schedule = { base: 3e-4, final: 3e-4, warmup: 100, steps: 15000 };
    for (const step of [99, 100, 7000, 14999]) near(rateAt(constant, step), 3e-4);
  });
});

describe('curve', () => {
  const s: Schedule = { base: 3e-4, final: 1e-5, warmup: 100, steps: 15000 };
  it('starts at step 0, ends at the last step and contains the warm-up corner', () => {
    const c = curve(s);
    expect(c[0]).toEqual({ x: 0, y: rateAt(s, 0) });
    expect(c[c.length - 1]).toEqual({ x: 14999, y: rateAt(s, 14999) });
    expect(c.some((p) => p.x === 99)).toBe(true);
    expect(c.some((p) => p.x === 100)).toBe(true);
    for (let i = 1; i < c.length; i++) expect((c[i] as { x: number }).x).toBeGreaterThan((c[i - 1] as { x: number }).x);
  });
});

describe('presets come from lr-presets.json', () => {
  const p = presetSchedules();
  it('generation 0: 1e-3, warm-up 200, 5800 steps, cosine to a tenth', () => {
    expect(p.gen0.base).toBe(1e-3);
    expect(p.gen0.warmup).toBe(200);
    expect(p.gen0.steps).toBe(5800);
    near(p.gen0.final, 1e-4);
  });
  it('constant 3e-4 and the sweep 1 winner share the 15,000 steps; only the winner decays', () => {
    expect(p.constant).toEqual({ base: 3e-4, final: 3e-4, warmup: 100, steps: 15000 });
    expect(p.sweep1).toEqual({ base: 3e-4, final: 1e-5, warmup: 100, steps: 15000 });
    expect(sameSchedule(p.constant, p.sweep1)).toBe(false);
    expect(sameSchedule(p.sweep1, { ...p.sweep1 })).toBe(true);
  });
});

describe('number text', () => {
  it('writes rates like the docs', () => {
    expect(rateText(3e-4)).toBe('3e-4');
    expect(rateText(1.5e-5)).toBe('1.5e-5');
    expect(rateText(0)).toBe('0');
    expect(scientific(3e-4)).toEqual({ mantissa: '3', exponent: -4 });
  });
});
