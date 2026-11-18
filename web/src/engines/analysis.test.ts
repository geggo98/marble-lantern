import { describe, expect, it } from 'vitest';
import { advantage, GUIDE_NODES, scoreBand, scoreLoss, scoreScale, SCORE, SKILL_NODES } from './analysis';

describe('score bands', () => {
  it('match the bands of the search: ordinary below 5000, race up to the mate bound, mate above', () => {
    expect(scoreBand(0)).toBe('ordinary');
    expect(scoreBand(4999)).toBe('ordinary');
    expect(scoreBand(-4999)).toBe('ordinary');
    expect(scoreBand(5000)).toBe('race');
    expect(scoreBand(-5000 - 200 * 3)).toBe('race');
    expect(scoreBand(SCORE.mateBound - 1)).toBe('race');
    expect(scoreBand(SCORE.mateBound)).toBe('mate');
    expect(scoreBand(-(SCORE.mate - 2))).toBe('mate');
    expect(scoreBand(SCORE.mate - 1)).toBe('mate');
  });
});

describe('advantage', () => {
  it('is 0 for an even position, odd in the score, increasing, and within -1..1', () => {
    expect(advantage(0)).toBe(0);
    let last = -1;
    for (const s of [-SCORE.mate, -5000, -400, -100, -30, -1, 0, 1, 30, 100, 400, 5000, SCORE.mate]) {
      const a = advantage(s);
      expect(a).toBeGreaterThanOrEqual(last);
      expect(Math.abs(a)).toBeLessThanOrEqual(1);
      expect(advantage(-s)).toBeCloseTo(-a, 12);
      last = a;
    }
  });

  it('is the logistic win probability of the measured scale: 100 points are 78 %, 200 are 92 %', () => {
    expect(scoreScale).toBe(80);
    const winProbability = (s: number): number => (advantage(s) + 1) / 2;
    expect(winProbability(100)).toBeCloseTo(1 / (1 + Math.exp(-100 / 80)), 12);
    expect(winProbability(100)).toBeCloseTo(0.777, 2);
    expect(winProbability(200)).toBeCloseTo(0.924, 2);
    expect(advantage(SCORE.mate - 1)).toBe(1);
    expect(advantage(-SCORE.mate + 1)).toBe(-1);
  });
});

describe('scoreLoss', () => {
  it('is the distance to the best score and never negative', () => {
    expect(scoreLoss(120, 90)).toBe(30);
    expect(scoreLoss(120, 120)).toBe(0);
    expect(scoreLoss(90, 120)).toBe(0);
    expect(scoreLoss(SCORE.mate - 1, 40)).toBe(SCORE.mate - 41);
  });
});

describe('budgets', () => {
  it('are positive integers and the skill budget is the larger one', () => {
    expect(Number.isInteger(GUIDE_NODES) && GUIDE_NODES > 0).toBe(true);
    expect(SKILL_NODES).toBeGreaterThan(GUIDE_NODES);
  });
});
