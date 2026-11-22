// The advantage indicator's wording, pure. Scores are seen from the human; a positive value means the human is ahead.
import { SCORE, advantage, scoreBand } from '../engines/analysis';

export type AdvantageLevel = 'even' | 'slight' | 'clear' | 'big' | 'race' | 'mate' | 'over';

export interface AdvantageInfo {
  /** -1 (engine wins) to 1 (human wins), from analysis.ts `advantage`. */
  value: number;
  lead: 'you' | 'engine' | 'none';
  level: AdvantageLevel;
  /** Plies to the forced end, for level "mate". */
  plies?: number;
}

/** Points below which nobody is ahead: one step of progress, above the search noise of about 20 points. */
export const EVEN_LIMIT = 30;
export const SLIGHT_LIMIT = 100;
export const CLEAR_LIMIT = 200;

/**
 * The strip of a finished game: the result is certain, so no analysis is wanted and the last shown value (the position before the final move,
 * e.g. "wins in 2 plies") must not stay. A win fills the track for the winner; any other end shows nobody ahead.
 * `humanSide` is the seat of the person. Three-player results never reach this strip (it has its own bar).
 */
export function advantageOfOutcome(outcome: { kind: string; winner?: number }, humanSide: number): AdvantageInfo {
  if (outcome.kind !== 'win') return { value: 0, lead: 'none', level: 'even' };
  return outcome.winner === humanSide ? { value: 1, lead: 'you', level: 'over' } : { value: -1, lead: 'engine', level: 'over' };
}

export function describeAdvantage(humanScore: number): AdvantageInfo {
  const value = advantage(humanScore);
  const a = Math.abs(humanScore);
  const band = scoreBand(humanScore);
  if (band === 'ordinary' && a < EVEN_LIMIT) return { value, lead: 'none', level: 'even' };
  const lead = humanScore > 0 ? 'you' : 'engine';
  if (band === 'mate') return { value, lead, level: 'mate', plies: SCORE.mate - a };
  if (band === 'race') return { value, lead, level: 'race' };
  return { value, lead, level: a < SLIGHT_LIMIT ? 'slight' : a < CLEAR_LIMIT ? 'clear' : 'big' };
}
