import { describe, expect, it } from 'vitest';
import { LOSS_CAP, MIN_MOVES, bootstrapInterval, cappedLoss, lossStat, median } from './stats';

describe('cappedLoss', () => {
  it('keeps small losses, caps big ones, and never goes below zero', () => {
    expect(cappedLoss(0)).toBe(0);
    expect(cappedLoss(35)).toBe(35);
    expect(cappedLoss(LOSS_CAP)).toBe(LOSS_CAP);
    expect(cappedLoss(LOSS_CAP + 1)).toBe(LOSS_CAP);
    expect(cappedLoss(1_999_000)).toBe(LOSS_CAP); // a mate score against a normal one
    expect(cappedLoss(-5)).toBe(0);
  });
});

describe('median', () => {
  it('takes the middle, and the mean of the two middle values for an even count', () => {
    expect(median([5, 1, 3])).toBe(3);
    expect(median([4, 1, 3, 2])).toBe(2.5);
    expect(median([7])).toBe(7);
  });
});

describe('lossStat', () => {
  it('is null without moves', () => {
    expect(lossStat([])).toBeNull();
  });
  it('is the mean of the capped losses, with the median and the count next to it', () => {
    expect(lossStat([0, 10, 20, 30])).toEqual({ n: 4, mean: 15, median: 15 });
  });
  it('lets one blunder count at most the cap', () => {
    const quiet = Array.from({ length: 9 }, () => 10);
    const withBlunder = lossStat([...quiet, 1_000_000])!;
    expect(withBlunder.mean).toBeCloseTo((9 * 10 + LOSS_CAP) / 10, 10);
    expect(withBlunder.median).toBe(10);
  });
});

describe('bootstrapInterval', () => {
  const losses = [0, 0, 10, 25, 40, 5, 60, 0, 15, 90, 120, 30];
  it('is deterministic for a seed and contains the statistic', () => {
    const a = bootstrapInterval(losses, { seed: 7 });
    const b = bootstrapInterval(losses, { seed: 7 });
    expect(a).toEqual(b);
    const mean = lossStat(losses)!.mean;
    expect(a.low).toBeLessThanOrEqual(mean);
    expect(a.high).toBeGreaterThanOrEqual(mean);
    expect(a.high).toBeGreaterThan(a.low);
  });
  it('is narrower with more moves of the same kind', () => {
    const many = Array.from({ length: 10 }, () => losses).flat();
    const wide = bootstrapInterval(losses, { seed: 3 });
    const narrow = bootstrapInterval(many, { seed: 3 });
    expect(narrow.high - narrow.low).toBeLessThan((wide.high - wide.low) * 0.6);
  });
  it('collapses when every loss is equal', () => {
    expect(bootstrapInterval([5, 5, 5, 5], { seed: 1 })).toEqual({ low: 5, high: 5 });
  });
});

it('asks for at least 8 moves', () => {
  expect(MIN_MOVES).toBe(8);
});
