// The scale of the scores that Engine.analyse and ChooseResult.score report, for the UI. The numbers come from the search
// (search/.../AlphaBeta.java, Scores.java, RaceEvaluator.java and their C++ twins); the calibration is in docs/web-ui.md,
// section "Analysis API".

/** Score bands of the search. A score is an integer from the point of view of the side that makes the move. */
export const SCORE = {
  /** A win at search height h scores MATE - h; a loss scores -(MATE - h). A move that wins at once scores MATE - 1. */
  mate: 1_000_000,
  /** |score| >= mateBound is a forced win or loss in a known number of plies: MATE - |score| plies. */
  mateBound: 999_000,
  /** RACE_BASE <= |score| < mateBound: exact race arithmetic (race table) as a leaf value. */
  raceBase: 5000,
  /** One race move of difference is worth this many points inside the race band. */
  raceUnit: 200,
  /** A game that ends without a winner scores its outcome value (-1..1) times this. */
  endScale: 10_000,
} as const;

/**
 * The scale of the ordinary band: P(mover wins) is about 1 / (1 + exp(-score / scoreScale)). Measured on 4,407 searched positions
 * of 60 self-play games (see docs/web-ui.md): about 30 points per step of progress towards the goal corner, 100 points = 78 %,
 * 200 points = 92 %, 300 points = 98 %.
 */
export const scoreScale = 80;

// Three players: Engine.analyse scores are paranoid scores of the three-player search from the point of view of the mover (the
// mover maximises, both others minimise), in the same units and with the same mate bounds as above: the mean over both opponents
// of the linear "own minus opponent" evaluation, so about 30 points per step of progress. scoreScale was fitted for two players;
// for three it is not calibrated, so use differences between moves and the ranking, not the probability. Engine.evaluate3 gives
// the static leaf score of each absolute seat (index = seat), higher is better for that seat.

/** Default node budget per scored move for move guides: all legal moves of a mid-game position take about 0.1 to 0.2 s on cpp-wasm. */
export const GUIDE_NODES = 4000;

/** A budget for skill estimates, which may take a few seconds in the background: about 0.7 s for 68 legal moves on cpp-wasm. */
export const SKILL_NODES = 20_000;

export type ScoreBand = 'ordinary' | 'race' | 'mate';

/** Which band a score lies in; a race or mate score is exact arithmetic, not an estimate. */
export function scoreBand(score: number): ScoreBand {
  const a = Math.abs(score);
  if (a >= SCORE.mateBound) return 'mate';
  return a >= SCORE.raceBase ? 'race' : 'ordinary';
}

/**
 * Squashes a score into an advantage between -1 (the mover is lost) and 1 (the mover wins): 2 / (1 + exp(-score / scoreScale)) - 1.
 * Race and mate scores give values within 1e-27 of the ends.
 */
export function advantage(score: number): number {
  return Math.tanh(score / (2 * scoreScale));
}

/**
 * How much worse a move is than the best one, in points, never negative. A search with a node limit is noisy: scores of one
 * position differ by about 20 points between budgets, so losses below that are not evidence of a mistake.
 */
export function scoreLoss(best: number, played: number): number {
  return Math.max(0, best - played);
}
