// Banding of score losses for the move guides. Pure functions, no React.
// The thresholds come from the measured scale in docs/web-ui.md, "Analysis API": about 30 points per step of progress
// towards the goal corner, 200 points clearly winning, scores of one position vary by about 20 points between budgets.
import { SCORE, scoreBand } from '../engines/analysis';

export type Band = 'much-better' | 'better' | 'equal' | 'worse' | 'much-worse';

/** From best to worst: the order of the legend and of the marks. */
export const BAND_ORDER: readonly Band[] = ['much-better', 'better', 'equal', 'worse', 'much-worse'];

/** One step of progress in points. */
export const STEP = 30;

/**
 * A forced win or loss is exact arithmetic: a win in 3 plies and a win in 9 plies are both a win. Collapse them, so that two
 * winning moves are never told apart by a few points of mate distance.
 */
export function normaliseScore(score: number): number {
  return scoreBand(score) === 'mate' ? Math.sign(score) * SCORE.mate : score;
}

/**
 * The one scale of the guides, for pieces and for targets: how far a move lies below the best move of the position, in 1, 3,
 * 6 and 10 steps of progress: top (within the search noise), good, fair, weak, poor. A piece is rated by its best move, so the
 * band of a piece is the best band among its targets. The ranks reuse the band names, so that colours and marks are shared.
 */
export function rankBand(move: number, best: number): Band {
  const loss = Math.max(0, normaliseScore(best) - normaliseScore(move));
  if (loss <= STEP) return 'much-better';
  if (loss <= 3 * STEP) return 'better';
  if (loss <= 6 * STEP) return 'equal';
  if (loss <= 10 * STEP) return 'worse';
  return 'much-worse';
}

export const bandRank = (band: Band): number => BAND_ORDER.indexOf(band);
