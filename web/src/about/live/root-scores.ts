// The root-scores board (Fig. 2.1b): the score of every legal move of a position, from the move-guide analysis (`analyse`, 4,000 nodes per
// move) sorted into the five bands of the guides. The figure reads `RootScoresData` from the committed example (root-scores-mid.json) or,
// in My-game mode, from this adapter. Nothing here touches engine code.
import type { AnalysisResult, Engine } from '../../engines/engine';
import { moveFrom } from '../../engines/engine';
import { GUIDE_NODES } from '../../engines/analysis';
import { replay } from '../../game/board-state';
import { buildView } from '../../guides/model';
import { bandRank } from '../../guides/bands';
import type { Band } from '../../guides/bands';
import type { Variant } from '../../game/variant';
import { moveText } from '../figures/ab-model';

export interface RootMove {
  from: number;
  to: number;
  /** `(q,r)->(q,r)`, as in the other data files. */
  text: string;
  score: number;
  band: Band;
}

export interface RootScoresData {
  meta: {
    ply: number;
    /** The seat that moves: the scores are from its point of view. */
    sideToMove: 0 | 1;
    /** Node budget for each scored move. */
    nodes: number;
    legalMoves: number;
  };
  /** owner[cell]: seat 0 or 1, or -1 for an empty cell, before the move. */
  cells: number[];
  /** Every legal move, best score first; moves with equal scores keep the order of the engine's move list. */
  moves: RootMove[];
}

/** The part of the engine this adapter calls. */
export type RootScoresEngine = Pick<Engine, 'analyse'>;

/** Builds the figure data from an analysis result of the position after `history`. */
export function buildRootScores(history: readonly number[], variant: Variant, analysis: AnalysisResult, nodes: number): RootScoresData {
  const view = buildView(analysis);
  const rows: RootMove[] = analysis.moves.map((move, i) => {
    const from = moveFrom(move);
    const to = move >>> 8;
    return { from, to, text: moveText(from, to), score: analysis.scores[i]!, band: view.moves.get(move)! };
  });
  // Array.prototype.sort is stable: equal scores keep the engine's order.
  rows.sort((a, b) => b.score - a.score);
  return {
    meta: { ply: history.length, sideToMove: (history.length % 2) as 0 | 1, nodes, legalMoves: rows.length },
    cells: Array.from(replay(history, variant)),
    moves: rows,
  };
}

/** One `analyse` call for all legal moves. */
export async function runRootScores(
  engine: RootScoresEngine,
  history: readonly number[],
  variant: Variant,
  signal?: AbortSignal,
  nodes: number = GUIDE_NODES,
): Promise<RootScoresData> {
  const analysis = await engine.analyse(history, null, nodes, signal, variant);
  return buildRootScores(history, variant, analysis, nodes);
}

/** The cells of the movable pieces with the best band of each piece (the board's discs). */
export function pieceBands(moves: readonly RootMove[]): Map<number, Band> {
  const best = new Map<number, Band>();
  for (const m of moves) {
    const have = best.get(m.from);
    if (have === undefined || bandRank(m.band) < bandRank(have)) best.set(m.from, m.band);
  }
  return best;
}
