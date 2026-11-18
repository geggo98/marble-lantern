import { describe, expect, it } from 'vitest';
import { describeLevel } from './describe';

const levels = [1000, 5000, 20_000, 100_000, 500_000];

describe('describeLevel', () => {
  it('says "at level k" close to a level (numbers count from 1)', () => {
    expect(describeLevel(20_000, levels)).toEqual({ kind: 'at', level: 3 });
    expect(describeLevel(22_000, levels)).toEqual({ kind: 'at', level: 3 }); // within 15 % of a step
  });
  it('says "between level k and k+1" otherwise', () => {
    expect(describeLevel(Math.sqrt(5000 * 20_000), levels)).toEqual({ kind: 'between', low: 2, high: 3 });
    expect(describeLevel(60_000, levels)).toEqual({ kind: 'between', low: 3, high: 4 });
  });
  it('says below the first and above the last level outside the presets', () => {
    expect(describeLevel(300, levels)).toEqual({ kind: 'below' });
    expect(describeLevel(2_000_000, levels)).toEqual({ kind: 'above' });
    expect(describeLevel(1000, levels)).toEqual({ kind: 'at', level: 1 });
    expect(describeLevel(500_000, levels)).toEqual({ kind: 'at', level: 5 });
  });
});
