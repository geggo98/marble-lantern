import { describe, expect, it } from 'vitest';
import { SCORE } from '../engines/analysis';
import { advantageOfOutcome, describeAdvantage } from './advantage';

describe('describeAdvantage', () => {
  it('says nobody is ahead below 30 points', () => {
    expect(describeAdvantage(0)).toMatchObject({ lead: 'none', level: 'even' });
    expect(describeAdvantage(29)).toMatchObject({ lead: 'none', level: 'even' });
    expect(describeAdvantage(-29)).toMatchObject({ lead: 'none', level: 'even' });
  });
  it('names the leader and the size of the lead', () => {
    expect(describeAdvantage(30)).toMatchObject({ lead: 'you', level: 'slight' });
    expect(describeAdvantage(-30)).toMatchObject({ lead: 'engine', level: 'slight' });
    expect(describeAdvantage(99)).toMatchObject({ level: 'slight' });
    expect(describeAdvantage(100)).toMatchObject({ level: 'clear' });
    expect(describeAdvantage(199)).toMatchObject({ level: 'clear' });
    expect(describeAdvantage(200)).toMatchObject({ level: 'big' });
    expect(describeAdvantage(-4999)).toMatchObject({ lead: 'engine', level: 'big' });
  });
  it('uses race wording from 5000 points and mate wording from 999000, with the plies', () => {
    expect(describeAdvantage(SCORE.raceBase)).toMatchObject({ lead: 'you', level: 'race' });
    expect(describeAdvantage(-6000)).toMatchObject({ lead: 'engine', level: 'race' });
    expect(describeAdvantage(SCORE.mate - 3)).toMatchObject({ lead: 'you', level: 'mate', plies: 3 });
    expect(describeAdvantage(-(SCORE.mate - 4))).toMatchObject({ lead: 'engine', level: 'mate', plies: 4 });
  });
  it('gives the tanh squash as value, within -1 and 1', () => {
    expect(describeAdvantage(0).value).toBe(0);
    expect(describeAdvantage(100).value).toBeCloseTo(Math.tanh(100 / 160), 10);
    expect(describeAdvantage(-SCORE.mate).value).toBeCloseTo(-1, 10);
  });
});

describe('advantageOfOutcome', () => {
  it('fills the track for the winner, whatever the last analysed position said', () => {
    expect(advantageOfOutcome({ kind: 'win', winner: 1 }, 1)).toEqual({ value: 1, lead: 'you', level: 'over' });
    expect(advantageOfOutcome({ kind: 'win', winner: 0 }, 1)).toEqual({ value: -1, lead: 'engine', level: 'over' });
  });
  it('shows nobody ahead after a draw or the move limit', () => {
    expect(advantageOfOutcome({ kind: 'move-limit' }, 1)).toMatchObject({ lead: 'none', level: 'even' });
    expect(advantageOfOutcome({ kind: 'draw' }, 1)).toMatchObject({ lead: 'none', level: 'even' });
  });
});
