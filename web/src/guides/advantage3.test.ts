import { describe, expect, it } from 'vitest';
import { advantage, scoreScale } from '../engines/analysis';
import { SHARE_TEMPERATURE, placeOf, seatShares } from './advantage3';

const percents = (evals: number[]): (number | null)[] => seatShares(evals).map((s) => s.percent);

describe('seatShares', () => {
  it('shows equal values as equal shares, the rounding remainder going to the first seat', () => {
    expect(percents([0, 0, 0])).toEqual([34, 33, 33]);
    expect(percents([-250, -250, -250])).toEqual([34, 33, 33]);
    for (const s of seatShares([7, 7, 7])) expect(s.share).toBeCloseTo(1 / 3, 12);
  });

  it('matches a hand calculation: values 0, -T, -2T give 66.52 %, 24.47 %, 9.00 %', () => {
    // T = 120: weights 1, e^-1, e^-2; sum 1.50321; shares 0.66524, 0.24473, 0.08999 -> 66, 24, 9 plus the remainder to seat 0
    const t = SHARE_TEMPERATURE;
    expect(t).toBe(120);
    const shares = seatShares([0, -t, -2 * t]);
    expect(shares[0]!.share).toBeCloseTo(0.66524, 4);
    expect(shares[1]!.share).toBeCloseTo(0.24473, 4);
    expect(shares[2]!.share).toBeCloseTo(0.08999, 4);
    expect(shares.map((s) => s.percent)).toEqual([67, 24, 9]);
  });

  it('adds up to exactly 100 for any values, and does not depend on a common offset', () => {
    let seed = 12345; // a fixed linear congruential generator: the same cases on every run
    const next = (): number => (seed = (seed * 1103515245 + 12345) % 2147483648) / 2147483648;
    for (let i = 0; i < 500; i++) {
      const evals = [0, 1, 2].map(() => Math.round((next() - 0.5) * 3000));
      const out = seatShares(evals);
      expect(out.reduce((a, s) => a + s.percent!, 0), evals.join(',')).toBe(100);
      expect(out.reduce((a, s) => a + s.share!, 0)).toBeCloseTo(1, 12);
      const shifted = seatShares(evals.map((e) => e + 777));
      shifted.forEach((s, k) => expect(s.share!).toBeCloseTo(out[k]!.share!, 12));
    }
  });

  it('ranks the seats like their values', () => {
    const [a, b, c] = seatShares([100, -30, 20]).map((s) => s.share!) as [number, number, number];
    expect(a).toBeGreaterThan(c);
    expect(c).toBeGreaterThan(b);
  });

  it('moves like the two-player bar: the odds of two seats equal the two-player odds of the same own-score difference', () => {
    // evaluate3 differences are 1.5 times the own-score difference; the two-player bar shows advantage = tanh(s / (2 scale)),
    // i.e. odds exp(s / scale) of win against loss
    const own = 60; // own-score difference of seats 0 and 1, seat 2 level with seat 1
    const [s0, s1] = seatShares([1.5 * own, 0, 0]).map((s) => s.share!) as [number, number, number];
    const winOdds = (1 + advantage(own)) / (1 - advantage(own));
    expect(s0 / s1).toBeCloseTo(winOdds, 9);
    expect(winOdds).toBeCloseTo(Math.exp(own / scoreScale), 9);
  });

  it('shows the place of a finished seat and shares among the others only', () => {
    const out = seatShares([1_000_000, 0, 120]);
    expect(out[0]).toEqual({ seat: 0, percent: null, share: null, place: 1 });
    expect(out.slice(1).map((s) => s.percent)).toEqual([27, 73]); // e^-1 / (1 + e^-1) = 26.9 %
    const last = seatShares([10, -1_000_000, 130]);
    expect(last[1]).toMatchObject({ place: 3, percent: null });
    expect(last.map((s) => s.percent)).toEqual([27, null, 73]);
  });

  it('shows places only once at most one seat is still playing', () => {
    expect(seatShares([1_000_000, 500_000, -1_000_000]).map((s) => [s.percent, s.place])).toEqual([[null, 1], [null, 2], [null, 3]]);
    expect(seatShares([1_000_000, 500_000, 40]).map((s) => s.place)).toEqual([1, 2, 3]); // the remaining seat takes the free place
    expect(seatShares([1_000_000, -1_000_000, 40]).map((s) => s.place)).toEqual([1, 3, 2]);
  });

  it('survives mate-sized values without overflow, and rejects bad input', () => {
    const out = seatShares([999_000, -999_000, 0]);
    expect(out.map((s) => s.percent)).toEqual([100, 0, 0]);
    expect(() => seatShares([1, 2])).toThrow(RangeError);
    expect(() => seatShares([1, 2, Number.NaN])).toThrow(RangeError);
  });
});

describe('placeOf', () => {
  it('knows the three rank constants of a full-ranking game and nothing else', () => {
    expect([1_000_000, 500_000, -1_000_000].map(placeOf)).toEqual([1, 2, 3]);
    expect([0, 999_999, -999_999, 1_000_001].map(placeOf)).toEqual([null, null, null, null]);
  });
});
