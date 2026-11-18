// Banding of score differences for the move guides. Pure functions, no React.
// The thresholds come from the measured scale in docs/web-ui.md, "Analysis API": about 30 points per step of progress
// towards the goal corner, 200 points clearly winning, scores of one position vary by about 20 points between budgets.
import { SCORE, scoreBand } from '../engines/analysis';

export type Band = 'much-better' | 'better' | 'equal' | 'worse' | 'much-worse';

/** From best to worst: the order of the legend and of the marks. */
export const BAND_ORDER: readonly Band[] = ['much-better', 'better', 'equal', 'worse', 'much-worse'];

/** One step of progress in points. */
export const STEP = 30;

/** Differences up to this size (both directions) count as "about equal": one step, above the search noise of about 20. */
export const EQUAL_HALF_WIDTH = STEP;

/** From this difference on a move is "much" better or worse: three steps. */
export const MUCH = 3 * STEP;

/**
 * A forced win or loss is exact arithmetic: a win in 3 plies and a win in 9 plies are both a win. Collapse them, so that two
 * winning moves are never told apart by a few points of mate distance.
 */
export function normaliseScore(score: number): number {
  return scoreBand(score) === 'mate' ? Math.sign(score) * SCORE.mate : score;
}

/** Band of a move whose score differs from the reference by `delta` points; positive is better. */
export function deltaBand(delta: number): Band {
  if (delta >= MUCH) return 'much-better';
  if (delta > EQUAL_HALF_WIDTH) return 'better';
  if (delta >= -EQUAL_HALF_WIDTH) return 'equal';
  if (delta > -MUCH) return 'worse';
  return 'much-worse';
}

/** Band of a move with score `score` against the reference value `reference` of the position. */
export function moveBand(score: number, reference: number): Band {
  return deltaBand(normaliseScore(score) - normaliseScore(reference));
}

/**
 * Band of a piece: how far its best move lies below the best move overall, in 1, 3, 6 and 10 steps of progress: top (within
 * the noise), good, fair, weak, poor. The ranks reuse the band names, so that colours and marks are shared.
 */
export function pieceBand(pieceBest: number, best: number): Band {
  const loss = Math.max(0, normaliseScore(best) - normaliseScore(pieceBest));
  if (loss <= STEP) return 'much-better';
  if (loss <= 3 * STEP) return 'better';
  if (loss <= 6 * STEP) return 'equal';
  if (loss <= 10 * STEP) return 'worse';
  return 'much-worse';
}

export const pieceRank = (band: Band): number => BAND_ORDER.indexOf(band);
