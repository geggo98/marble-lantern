// The model of V4 (source tree explorer): which rows of the tree are visible, which entries a filter dims, what a selected module
// depends on, and the layout of the module graph. Pure functions over `source-tree.json`.

export type Where = 'browser' | 'server' | 'training' | 'build';
export const WHERE: readonly Where[] = ['browser', 'server', 'training', 'build'];

export interface TreeEntry {
  id: string;
  path: string;
  kind: 'dir' | 'file';
  parent: string | null;
  tags: readonly ('ignored' | 'lfs')[];
  runsIn: readonly Where[];
  module?: string;
}

export interface TreeData {
  modules: readonly string[];
  deps: readonly { from: string; to: string }[];
  entries: readonly TreeEntry[];
}

export const hasChildren = (entries: readonly TreeEntry[], id: string): boolean => entries.some((e) => e.parent === id);

/** Depth of an entry in the tree (a top-level entry is level 1, as `aria-level` counts). */
export function levelOf(entries: readonly TreeEntry[], entry: TreeEntry): number {
  let level = 1;
  for (let p = entry.parent; p !== null; level++) p = entries.find((e) => e.id === p)?.parent ?? null;
  return level;
}

/** The rows that are on screen: top-level entries, and the children of every expanded entry, in file order. */
export function visibleEntries(entries: readonly TreeEntry[], expanded: ReadonlySet<string>): TreeEntry[] {
  const open = (e: TreeEntry): boolean => e.parent === null || (expanded.has(e.parent) && open(entries.find((p) => p.id === e.parent)!));
  return entries.filter(open);
}

/** True when the entry stays at full strength under the filter; `all` keeps everything. */
export const matchesFilter = (entry: TreeEntry, filter: Where | 'all'): boolean => filter === 'all' || entry.runsIn.includes(filter);

export function moduleDeps(data: Pick<TreeData, 'deps'>, module: string): { dependsOn: string[]; usedBy: string[] } {
  return {
    dependsOn: data.deps.filter((d) => d.from === module).map((d) => d.to),
    usedBy: data.deps.filter((d) => d.to === module).map((d) => d.from),
  };
}

/** Rank of a module: 0 when it depends on no other module, else one more than the highest rank among its dependencies. */
export function ranks(data: Pick<TreeData, 'modules' | 'deps'>): Map<string, number> {
  const result = new Map<string, number>();
  const rank = (m: string, seen: readonly string[]): number => {
    const known = result.get(m);
    if (known !== undefined) return known;
    if (seen.includes(m)) throw new Error(`dependency cycle at ${m}`);
    const r = data.deps.filter((d) => d.from === m).reduce((max, d) => Math.max(max, rank(d.to, [...seen, m]) + 1), 0);
    result.set(m, r);
    return r;
  };
  for (const m of data.modules) rank(m, []);
  return result;
}

export interface GraphBox {
  module: string;
  x: number;
  y: number;
  w: number;
  h: number;
}

export interface GraphLayout {
  width: number;
  height: number;
  boxes: GraphBox[];
  /** Line from the bottom of the dependent module to the top of the module it depends on. */
  edges: { from: string; to: string; x1: number; y1: number; x2: number; y2: number }[];
}

export const GRAPH = { width: 440, boxW: 100, boxH: 28, rowGap: 46, pad: 6 } as const;

/** The module with the highest rank on top; modules of one rank share a row, evenly spread. */
export function graphLayout(data: Pick<TreeData, 'modules' | 'deps'>): GraphLayout {
  const rank = ranks(data);
  const top = Math.max(...rank.values());
  const rows: string[][] = Array.from({ length: top + 1 }, () => []);
  for (const m of data.modules) rows[top - rank.get(m)!]!.push(m);
  const height = GRAPH.pad * 2 + rows.length * GRAPH.boxH + (rows.length - 1) * GRAPH.rowGap;
  const boxes: GraphBox[] = [];
  rows.forEach((row, r) => {
    const slot = GRAPH.width / row.length;
    row.forEach((module, i) =>
      boxes.push({ module, x: slot * (i + 0.5) - GRAPH.boxW / 2, y: GRAPH.pad + r * (GRAPH.boxH + GRAPH.rowGap), w: GRAPH.boxW, h: GRAPH.boxH }),
    );
  });
  const at = (m: string): GraphBox => boxes.find((b) => b.module === m)!;
  const edges = data.deps.map((d) => {
    const a = at(d.from);
    const b = at(d.to);
    return { from: d.from, to: d.to, x1: a.x + a.w / 2, y1: a.y + a.h, x2: b.x + b.w / 2, y2: b.y };
  });
  return { width: GRAPH.width, height, boxes, edges };
}
