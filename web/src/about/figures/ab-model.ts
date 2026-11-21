// The model of V5 (alpha-beta tree of the start position): the visit order of `ab-tree-start.json` turned into the columns of the icicle
// tree, the readouts and the narration of each step. Pure functions; every number comes from the data file.
import { CELLS } from '../../board/cells';
import type { CellState, IcicleColumn } from '../kit';

export interface Visit {
  id: number;
  parent: number;
  iteration: 1 | 2;
  move: { from: number; to: number };
  order: number;
  depthLeft: number;
  alpha: number;
  beta: number;
  legal: number;
  score: number;
  cutoff: boolean;
  skipped: number;
}

export interface AbData {
  meta: { depth: number; nodes: number; minimaxNodes: number; infinity: number; rootMoves: number; rootJumps: number; bestMove: { from: number; to: number; text: string }; score: number };
  cells: readonly number[];
  rootMoves: readonly { from: number; to: number; kind: 'jump' | 'step' }[];
  visits: readonly Visit[];
}

/** `(q,r)->(q,r)` in the axial coordinates of the engine, as in the data file (`meta.bestMove.text`). */
export function moveText(from: number, to: number): string {
  const a = CELLS[from]!;
  const b = CELLS[to]!;
  return `(${a.q},${a.r})->(${b.q},${b.r})`;
}

/** A bound of the search window; the data file stores infinity as ±(meta.infinity). */
export function bound(value: number, infinity: number): string {
  if (value >= infinity) return '+∞';
  if (value <= -infinity) return '−∞';
  return value < 0 ? `−${-value}` : String(value);
}

/** The root-move column of a visit: root moves are visited in order, replies belong to the column of their parent. */
export function columnOf(data: AbData, visit: Visit): number {
  if (visit.parent === 0) return visit.order;
  return columnOf(data, data.visits.find((v) => v.id === visit.parent)!);
}

/** The visits that are children of `parent`, in visit order. */
export const childrenOf = (data: AbData, parent: number): Visit[] => data.visits.filter((v) => v.parent === parent);

/** The replies of one root move: the iteration-2 node of the move and its replies. */
function iterationTwoNode(data: AbData, column: number): Visit | undefined {
  return data.visits.find((v) => v.parent === 0 && v.iteration === 2 && v.order === column);
}

/** The step (visit id) at which the cut-off of a node becomes visible: the visit of its last searched child. */
export function cutStep(data: AbData, node: Visit): number | null {
  if (!node.cutoff) return null;
  const kids = childrenOf(data, node.id);
  return kids.length > 0 ? kids[kids.length - 1]!.id : null;
}

/** The step that shows the first visit of a root move in the iteration for depth 2 (the "jump to root move" select). */
export const stepOfRootMove = (data: AbData, column: number): number => iterationTwoNode(data, column)?.id ?? 1;

/** The columns of the icicle tree after `step` visits (0 is idle). `minimax` draws every reply as searched and no cut. */
export function columnsAt(data: AbData, step: number, minimax: boolean): IcicleColumn[] {
  const active = step > 0 ? data.visits[step - 1] : undefined;
  const activeColumn = active ? columnOf(data, active) : -1;
  return data.rootMoves.map((move, column) => {
    const node = iterationTwoNode(data, column)!;
    const kids = childrenOf(data, node.id);
    const cut = cutStep(data, node);
    const cutVisible = !minimax && cut !== null && step >= cut;
    const cells: CellState[] = Array.from({ length: node.legal }, (_, k) => {
      if (minimax) return 'visited';
      const kid = kids[k];
      if (kid) return kid.id === step ? 'active' : kid.id < step ? 'visited' : 'todo';
      return cutVisible ? 'skipped' : 'todo';
    });
    return {
      glyph: move.kind === 'jump' ? '»' : '›',
      ...(!minimax && column === activeColumn ? { state: 'active' as const } : {}),
      cells,
      ...(cutVisible ? { cutAfter: kids.length } : {}),
    };
  });
}

export interface StepView {
  visit: Visit;
  /** Root move of the line, and the reply when the visit is a reply. */
  rootMove: { from: number; to: number };
  reply?: { from: number; to: number };
  /** Narration parts in order: a key of the catalog and its values. */
  narration: { key: 'enter' | 'leaf' | 'return' | 'cutoff'; values: Record<string, string | number> }[];
  /** The visit ends the search of a node whose window closed. */
  cut: boolean;
  /** `alpha`, `beta`, `score` as the text of the readout. */
  alpha: string;
  beta: string;
  score: string;
}

/**
 * What step `step` (1 to meta.nodes) shows. One step is one call of the search (one visit): a call with replies below it is narrated
 * when it starts; a call without replies is narrated with its score, and the last reply of a root move adds how that root move ended,
 * with a cut-off or with its value.
 */
export function stepView(data: AbData, step: number): StepView | null {
  const visit = data.visits[step - 1];
  if (!visit) return null;
  const inf = data.meta.infinity;
  const parent = visit.parent === 0 ? undefined : data.visits.find((v) => v.id === visit.parent);
  const rootNode = parent ?? visit;
  const rootMove = rootNode.move;
  const line = moveText(rootMove.from, rootMove.to);
  const narration: StepView['narration'] = [];
  let cut = false;
  if (visit.legal > 0) {
    narration.push({ key: 'enter', values: { move: line, alpha: bound(visit.alpha, inf), beta: bound(visit.beta, inf), left: visit.depthLeft } });
  } else {
    narration.push({ key: 'leaf', values: { move: moveText(visit.move.from, visit.move.to), score: bound(visit.score, inf) } });
    if (parent) {
      const kids = childrenOf(data, parent.id);
      if (kids[kids.length - 1]?.id === visit.id) {
        if (parent.cutoff) {
          cut = true;
          narration.push({ key: 'cutoff', values: { move: line, alpha: bound(parent.score, inf), beta: bound(parent.beta, inf), skipped: parent.skipped } });
        } else {
          // The mover's value of the root move is the negated score of the replier; the window of the root rises to it.
          const worth = -parent.score;
          const before = parent.beta >= inf ? -inf : -parent.beta;
          narration.push({ key: 'return', values: { move: line, score: bound(worth, inf), alpha: bound(Math.max(before, worth), inf) } });
        }
      }
    }
  }
  return {
    visit,
    rootMove,
    ...(parent ? { reply: visit.move } : {}),
    narration,
    cut,
    alpha: bound(visit.alpha, inf),
    beta: bound(visit.beta, inf),
    score: bound(visit.score, inf),
  };
}

/** The counter "visited of total": the visits so far of the search, or all nodes of the tree without pruning. */
export function counter(data: AbData, step: number, minimax: boolean): { visited: number; total: number } {
  return minimax ? { visited: data.meta.minimaxNodes, total: data.meta.minimaxNodes } : { visited: step, total: data.visits.length };
}
