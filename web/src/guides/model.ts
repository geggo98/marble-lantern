// From an analysis result to what the board draws: a band for each move and for each movable piece. Pure.
import type { AnalysisResult } from '../engines/engine';
import { moveFrom } from '../engines/engine';
import { rankBand } from './bands';
import type { Band } from './bands';

export interface GuideView {
  /** Value of the position for the mover: the best score. */
  position: number;
  /** Band of each analysed move (key: the engine's move code, from | to << 8), against the best move of the position. */
  moves: ReadonlyMap<number, Band>;
  /** Band of each cell that holds a movable piece: its best move against the best move of the position. */
  pieces: ReadonlyMap<number, Band>;
  /** The scores, by move code, for the advantage after a move. */
  scores: ReadonlyMap<number, number>;
}

/** Pieces and targets share one scale: the loss against the best move of the position (`rankBand`). */
export function buildView(result: AnalysisResult): GuideView {
  const { moves, scores } = result;
  if (moves.length !== scores.length) throw new RangeError('analysis result: moves and scores differ in length');
  const position = scores.length === 0 ? 0 : Math.max(...scores);
  const moveBands = new Map<number, Band>();
  const scoreMap = new Map<number, number>();
  const best = new Map<number, number>();
  moves.forEach((move, i) => {
    const score = scores[i]!;
    moveBands.set(move, rankBand(score, position));
    scoreMap.set(move, score);
    const from = moveFrom(move);
    best.set(from, Math.max(best.get(from) ?? -Infinity, score));
  });
  const pieces = new Map<number, Band>();
  for (const [from, score] of best) pieces.set(from, rankBand(score, position));
  return { position, moves: moveBands, pieces, scores: scoreMap };
}
