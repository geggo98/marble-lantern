import { describe, expect, it } from 'vitest';
import { SCORE } from '../engines/analysis';
import { BAND_ORDER, EQUAL_HALF_WIDTH, deltaBand, moveBand, normaliseScore, pieceBand, pieceRank } from './bands';
import type { Band } from './bands';

describe('deltaBand', () => {
  it('has an "about equal" band of at least +-30 points (search noise is about 20)', () => {
    expect(EQUAL_HALF_WIDTH).toBeGreaterThanOrEqual(30);
    expect(deltaBand(30)).toBe('equal');
    expect(deltaBand(-30)).toBe('equal');
    expect(deltaBand(0)).toBe('equal');
    expect(deltaBand(20)).toBe('equal');
    expect(deltaBand(-20)).toBe('equal');
  });
  it('separates the five bands at the thresholds', () => {
    expect(deltaBand(31)).toBe('better');
    expect(deltaBand(89)).toBe('better');
    expect(deltaBand(90)).toBe('much-better');
    expect(deltaBand(5000)).toBe('much-better');
    expect(deltaBand(-31)).toBe('worse');
    expect(deltaBand(-89)).toBe('worse');
    expect(deltaBand(-90)).toBe('much-worse');
    expect(deltaBand(-5000)).toBe('much-worse');
  });
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

describe('moveBand', () => {
  it('compares normalised scores: two winning moves are equal', () => {
    expect(moveBand(SCORE.mate - 9, SCORE.mate - 1)).toBe('equal');
  });
  it('a move that loses against a position that wins is much worse', () => {
    expect(moveBand(-(SCORE.mate - 2), SCORE.mate - 1)).toBe('much-worse');
  });
  it('uses the difference to the reference', () => {
    expect(moveBand(100, 100)).toBe('equal');
    expect(moveBand(60, 100)).toBe('worse');
    expect(moveBand(130, 0)).toBe('much-better');
  });
});

describe('pieceBand', () => {
  it('ranks a piece by how far its best move lies below the best move of the position', () => {
    expect(pieceBand(1000, 1000)).toBe('much-better');
    expect(pieceBand(970, 1000)).toBe('much-better'); // 30 below: within the noise
    expect(pieceBand(969, 1000)).toBe('better');
    expect(pieceBand(910, 1000)).toBe('better'); // 90 below
    expect(pieceBand(909, 1000)).toBe('equal');
    expect(pieceBand(820, 1000)).toBe('equal'); // 180 below
    expect(pieceBand(819, 1000)).toBe('worse');
    expect(pieceBand(700, 1000)).toBe('worse'); // 300 below
    expect(pieceBand(699, 1000)).toBe('much-worse');
  });
  it('does not call two mates different', () => {
    expect(pieceBand(SCORE.mate - 9, SCORE.mate - 1)).toBe('much-better');
  });
  it('never gives a piece a better rank than the best', () => {
    expect(pieceBand(1200, 1000)).toBe('much-better');
  });
  it('gives each band a distinct rank', () => {
    expect((['much-better', 'better', 'equal', 'worse', 'much-worse'] as Band[]).map(pieceRank)).toEqual([0, 1, 2, 3, 4]);
  });
});
