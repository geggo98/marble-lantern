// V10: the tree of mcts-mid.json as an icicle that grows with the simulations (Fig. 3.3, review M6). Pure: the figure only draws the blocks.
// The width of a block is proportional to the visits of its node, the root is the whole width, and the moves nobody has tried form one block.
import { links } from './puct';
import type { McstData, Substep } from './puct';

export type BlockState = 'visited' | 'path' | 'active' | 'todo';

export interface IcicleBlock {
  key: string;
  /** 1 for a move of the root, 2 and more for the replies below it. */
  depth: number;
  /** Position and width in view box units. */
  x: number;
  w: number;
  state: BlockState;
  visits: number;
  /** The root move of a depth-1 block; -1 for every other block (replies, and the one block of the moves that are not drawn one by one). */
  edge: number;
}

export interface IcicleLayout {
  /** Simulations folded into the tree. */
  visits: number;
  blocks: IcicleBlock[];
  /** Depth of the deepest path of the whole run: the picture keeps this many rows from the first state on, so the figure does not change height. */
  maxDepth: number;
}

/** Narrowest block of a root move, in units (a move with a single visit must stay visible). */
export const ICICLE_MIN_WIDTH = 4;
/** The block of the moves nobody has tried is this share of the visits wide, but at least one visit. */
export const ICICLE_REST_SHARE = 0.12;

/** The root edges by prior, the best first; a tie keeps the order of the moves. */
export const priorOrder = (data: McstData): number[] =>
  data.root.moves.map((_, i) => i).sort((a, b) => (data.root.priors[b] as number) - (data.root.priors[a] as number) || a - b);

/** Visits of every node below the root after the first `shown` simulations: a simulation counts once for every node on its path and for its new leaf. */
export function nodeVisits(data: McstData, shown: number): Map<number, number> {
  const out = new Map<number, number>();
  for (const s of data.sims.slice(0, Math.max(0, shown))) {
    for (const node of [...s.nodes.slice(1), s.leaf.node]) out.set(node, (out.get(node) ?? 0) + 1);
  }
  return out;
}

/**
 * Splits `total` over `weights` in proportion; a share below `min` is raised to `min` and the others give way, so the sum stays `total`.
 * A weight of 0 gets `min`.
 */
export function shareWidths(weights: readonly number[], total: number, min: number): number[] {
  const fixed = new Set<number>();
  const out = weights.map(() => min);
  for (let round = 0; round <= weights.length; round++) {
    const free = weights.map((_, i) => i).filter((i) => !fixed.has(i));
    const freeWeight = free.reduce((sum, i) => sum + (weights[i] as number), 0);
    const room = total - fixed.size * min;
    let again = false;
    for (const i of free) {
      out[i] = freeWeight > 0 ? ((weights[i] as number) / freeWeight) * room : room / free.length;
      if ((out[i] as number) < min) {
        fixed.add(i);
        out[i] = min;
        again = true;
      }
    }
    if (!again) break;
  }
  return out;
}

/**
 * The tree at (k, substep) as an icicle of `width` units. Below the root come the root moves that have been tried (at most `maxColumns`,
 * the most visited ones, in the order of their priors), then one block for all other moves. The nodes on the current path are `path`;
 * the new leaf is `active` from the expand sub-step on.
 */
export function icicleLayout(data: McstData, k: number, substep: Substep, width: number, maxColumns: number): IcicleLayout {
  const shown = Math.max(0, substep === 'select' ? k - 1 : k);
  const visits = nodeVisits(data, shown);
  const all = links(data, shown);
  const current = k >= 1 ? (data.sims[k - 1] as McstData['sims'][number]) : undefined;
  const pathNodes = new Set<number>(current ? current.nodes.slice(1) : []);
  if (current && substep !== 'select') pathNodes.add(current.leaf.node);
  const stateOf = (node: number): BlockState =>
    current && substep !== 'select' && node === current.leaf.node ? 'active' : pathNodes.has(node) ? 'path' : 'visited';

  const rootChild = new Map<number, number>();
  for (const l of all) if (l.node === 0) rootChild.set(l.edge, l.child);
  const visitsOf = (edge: number): number => visits.get(rootChild.get(edge) as number) ?? 0;
  const tried = priorOrder(data).filter((e) => rootChild.has(e));
  const mostVisited = [...tried].sort((a, b) => visitsOf(b) - visitsOf(a)).slice(0, Math.max(0, maxColumns));
  const columns = tried.filter((e) => mostVisited.includes(e));
  const restVisits = tried.filter((e) => !columns.includes(e)).reduce((sum, e) => sum + visitsOf(e), 0);
  const restWeight = restVisits + Math.max(1, Math.round(ICICLE_REST_SHARE * Math.max(1, shown)));
  const widths = shareWidths([...columns.map(visitsOf), restWeight], width, ICICLE_MIN_WIDTH);

  const blocks: IcicleBlock[] = [];
  const place = (node: number, x: number, w: number, depth: number): void => {
    const own = visits.get(node) ?? 0;
    let at = x;
    for (const l of all.filter((link) => link.node === node)) {
      const v = visits.get(l.child) ?? 0;
      const cw = own > 0 ? (w * v) / own : 0;
      blocks.push({ key: `n${l.child}`, depth, x: at, w: cw, state: stateOf(l.child), visits: v, edge: -1 });
      place(l.child, at, cw, depth + 1);
      at += cw;
    }
  };
  let x = 0;
  columns.forEach((edge, i) => {
    const child = rootChild.get(edge) as number;
    const w = widths[i] as number;
    blocks.push({ key: `n${child}`, depth: 1, x, w, state: stateOf(child), visits: visitsOf(edge), edge });
    place(child, x, w, 2);
    x += w;
  });
  blocks.push({ key: 'rest', depth: 1, x, w: widths[columns.length] as number, state: restVisits > 0 ? 'visited' : 'todo', visits: restVisits, edge: -1 });
  const maxDepth = data.sims.reduce((m, s) => Math.max(m, s.path.length), 1);
  return { visits: shown, blocks, maxDepth };
}
