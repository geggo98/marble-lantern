import { describe, expect, it } from 'vitest';
import { SCORE } from '../engines/analysis';
import { BAND_ORDER, bandRank, normaliseScore, rankBand } from './bands';
import type { Band } from './bands';

describe('BAND_ORDER', () => {
  it('lists the bands from best to worst', () => {
    expect(BAND_ORDER).toEqual(['much-better', 'better', 'equal', 'worse', 'much-worse']);
  });
});

describe('normaliseScore', () => {
  it('keeps ordinary and race scores', () => {
    expect(normaliseScore(123)).toBe(123);
    expect(normaliseScore(-6000)).toBe(-6000);
  });
  it('makes all forced wins equal, whatever the number of plies, and all forced losses equal', () => {
    expect(normaliseScore(SCORE.mate - 1)).toBe(normaliseScore(SCORE.mate - 9));
    expect(normaliseScore(-(SCORE.mate - 2))).toBe(normaliseScore(-(SCORE.mate - 8)));
    expect(normaliseScore(SCORE.mate - 1)).toBeGreaterThan(0);
    expect(normaliseScore(-(SCORE.mate - 1))).toBeLessThan(0);
  });
  it('treats the first score of the mate band as mate, and the last one below it as race', () => {
    expect(normaliseScore(SCORE.mateBound)).toBe(SCORE.mate);
    expect(normaliseScore(SCORE.mateBound - 1)).toBe(SCORE.mateBound - 1);
  });
});

describe('rankBand: targets and pieces share the scale', () => {
  it('compares normalised scores: two winning moves are both top', () => {
    expect(rankBand(SCORE.mate - 9, SCORE.mate - 1)).toBe('much-better');
  });
  it('a move that loses against a position that wins is poor', () => {
    expect(rankBand(-(SCORE.mate - 2), SCORE.mate - 1)).toBe('much-worse');
  });
});

describe('rankBand', () => {
  it('ranks a piece by how far its best move lies below the best move of the position', () => {
    expect(rankBand(1000, 1000)).toBe('much-better');
    expect(rankBand(970, 1000)).toBe('much-better'); // 30 below: within the noise
    expect(rankBand(969, 1000)).toBe('better');
    expect(rankBand(910, 1000)).toBe('better'); // 90 below
    expect(rankBand(909, 1000)).toBe('equal');
    expect(rankBand(820, 1000)).toBe('equal'); // 180 below
    expect(rankBand(819, 1000)).toBe('worse');
    expect(rankBand(700, 1000)).toBe('worse'); // 300 below
    expect(rankBand(699, 1000)).toBe('much-worse');
  });
  it('does not call two mates different', () => {
    expect(rankBand(SCORE.mate - 9, SCORE.mate - 1)).toBe('much-better');
  });
  it('never gives a piece a better rank than the best', () => {
    expect(rankBand(1200, 1000)).toBe('much-better');
  });
  it('gives each band a distinct rank', () => {
    expect((['much-better', 'better', 'equal', 'worse', 'much-worse'] as Band[]).map(bandRank)).toEqual([0, 1, 2, 3, 4]);
  });
});
