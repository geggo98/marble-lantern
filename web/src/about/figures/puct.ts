// V10: the search of mcts-mid.json replayed state by state. Pure; the figure only draws what these functions return.
// The formula is the one of `Mcts` (search/src/main/java/sternhalma/search/Mcts.java):
//   PUCT(a) = Q(a) + c * P(a) * sqrt(max(1, N_parent)) / (1 + N(a)), and an unvisited edge has Q = node mean - fpu
// with node mean = (value + sum of backed-up values) / (1 + visits).

export interface McstData {
  meta: { rootMover: number; cpuct: number; fpu: number; sims: number; choiceEdge: number };
  cells: number[];
  root: { moves: [number, number][]; priors: number[]; values: number[] };
  sims: {
    k: number;
    path: number[];
    nodes: number[];
    leaf: { node: number; kind: string; new: boolean };
    values: number[];
    expanded: { moves: [number, number][]; priors: number[]; value: number };
  }[];
  final: { move: [number, number]; P: number; N: number; Q: number | null }[];
}

export interface RootState {
  /** Simulations folded in. */
  k: number;
  visits: number[];
  /** Sum of the backed-up values of the root mover per root edge. */
  sums: number[];
  /** Mean value of the root node: (value + sum of all backed-up values) / (1 + k). */
  mean: number;
}

/** The root edges after the first `k` simulations (0 to the number of simulations). */
export function rootState(data: McstData, k: number): RootState {
  const n = data.root.moves.length;
  const visits = new Array<number>(n).fill(0);
  const sums = new Array<number>(n).fill(0);
  let total = 0;
  const mover = data.meta.rootMover;
  for (const s of data.sims.slice(0, k)) {
    const edge = s.path[0] as number;
    visits[edge] = (visits[edge] ?? 0) + 1;
    sums[edge] = (sums[edge] ?? 0) + (s.values[mover] as number);
    total += s.values[mover] as number;
  }
  return { k, visits, sums, mean: ((data.root.values[mover] as number) + total) / (1 + k) };
}

/** Q of a root edge: its mean backed-up value, or for an unvisited edge the node mean minus fpu. */
export const edgeQ = (data: McstData, state: RootState, edge: number): number => {
  const v = state.visits[edge] as number;
  return v === 0 ? state.mean - data.meta.fpu : (state.sums[edge] as number) / v;
};

/** The PUCT score of every root edge in `state` for the constant `c`. */
export function puctColumn(data: McstData, state: RootState, c: number): number[] {
  const explore = c * Math.sqrt(Math.max(1, state.k));
  return data.root.priors.map((p, edge) => edgeQ(data, state, edge) + (explore * p) / (1 + (state.visits[edge] as number)));
}

/** The edge that the next selection takes: the highest score, the first one on a tie (as `Mcts` scans the edges in order). */
export function argmax(scores: readonly number[]): number {
  let best = 0;
  scores.forEach((s, i) => {
    if (s > (scores[best] as number)) best = i;
  });
  return best;
}

/** Child node of (node, edge) as far as the first `k` simulations have created it; undefined while it does not exist. */
export function childOf(data: McstData, k: number, node: number, edge: number): number | undefined {
  for (const s of data.sims.slice(0, k)) {
    for (let i = 0; i < s.path.length; i++) {
      if (s.nodes[i] === node && s.path[i] === edge) return i + 1 < s.path.length ? (s.nodes[i + 1] as number) : s.leaf.node;
    }
  }
  return undefined;
}

/** All (node -> edge -> child) links created by the first `k` simulations, in creation order. */
export function links(data: McstData, k: number): { node: number; edge: number; child: number; sim: number }[] {
  const seen = new Set<string>();
  const out: { node: number; edge: number; child: number; sim: number }[] = [];
  for (const s of data.sims.slice(0, k)) {
    for (let i = 0; i < s.path.length; i++) {
      const node = s.nodes[i] as number;
      const edge = s.path[i] as number;
      const key = `${node}/${edge}`;
      if (seen.has(key)) continue;
      seen.add(key);
      out.push({ node, edge, child: i + 1 < s.path.length ? (s.nodes[i + 1] as number) : s.leaf.node, sim: s.k });
    }
  }
  return out;
}

/** The moves of a node: the root moves, or the moves expanded when the node was created. */
export function movesOf(data: McstData, node: number): [number, number][] {
  return node === 0 ? data.root.moves : (data.sims.find((s) => s.leaf.node === node && s.leaf.new)?.expanded.moves ?? []);
}

export type Substep = 'select' | 'expand' | 'evaluate' | 'backup';
export const SUBSTEPS: readonly Substep[] = ['select', 'expand', 'evaluate', 'backup'];

/** Simulations folded into the root table at (k, substep): the backup of simulation k is the last one to count. */
export const visibleSims = (k: number, substep: Substep): number => (k === 0 ? 0 : substep === 'backup' ? k : k - 1);

export type ColumnCell = { node: number; state: 'visited' | 'active' | 'path' };

/**
 * The cells under the move bar of root edge `edge` at (k, substep): the nodes of depth 2 and 3 below it that exist, in creation order.
 * The nodes of the current path are marked `path` and the new leaf `active` (from the expand sub-step on); at most `cap` cells, the
 * marked ones first.
 */
export function columnCells(data: McstData, k: number, substep: Substep, edge: number, cap: number): ColumnCell[] {
  const shown = substep === 'select' ? k - 1 : k;
  const all = links(data, Math.max(0, shown));
  const top = all.find((l) => l.node === 0 && l.edge === edge);
  if (!top) return [];
  const depth2 = all.filter((l) => l.node === top.child).map((l) => l.child);
  const depth3 = all.filter((l) => depth2.includes(l.node)).map((l) => l.child);
  const current = k >= 1 ? (data.sims[k - 1] as McstData['sims'][number]) : undefined;
  const pathNodes = new Set<number>(current ? current.nodes.slice(1) : []);
  if (current && substep !== 'select') pathNodes.add(current.leaf.node);
  const mark = (node: number): ColumnCell['state'] =>
    current && substep !== 'select' && node === current.leaf.node ? 'active' : current && current.path[0] === edge && pathNodes.has(node) ? 'path' : 'visited';
  const cells = [...depth2, ...depth3].map((node) => ({ node, state: mark(node) }));
  if (cells.length <= cap) return cells;
  const marked = cells.filter((c) => c.state !== 'visited');
  const rest = cells.filter((c) => c.state === 'visited').slice(0, Math.max(0, cap - marked.length));
  const keep = new Set([...marked, ...rest].map((c) => c.node));
  return cells.filter((c) => keep.has(c.node)).slice(0, cap);
}
