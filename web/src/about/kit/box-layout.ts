// Layout of a box diagram from the data files `arch-*.json` (design notes, section 6, "BoxDiagram", and section 7).
// Pure geometry, no React. One coordinate system for the HTML boxes and the SVG edge layer: view box `0 0 width height`.
//
// The data files hold boxes (id, label, runsIn) and edges (from, to, kind, label) but no positions, so the layout is generic:
//   - one region per distinct `runsIn`, stacked from top to bottom in the order of first appearance in the file;
//   - in a region the boxes fill rows from left to right in file order: wide 4 per row (144 x 60), compact 3 per row (70 x 50) for a
//     region of three or more boxes, otherwise one full-width box (224 x 46) per row;
//   - an edge is routed at right angles: straight between neighbours in a row, around the row (above it) for a box further along the row,
//     through the gap between two rows when the rows are adjacent, and through a lane at the margin (left when wide, right when compact)
//     when it spans more than one row, so that it never crosses a box. Edges that share a gap between rows get separate slots.
// The compact layout is 264 units wide: at 320 px the unit is 1.11 px, so a box of 46 units is 51 px and keeps the 44 px target.

export type LayoutMode = 'wide' | 'compact';
export type EdgeKind = 'data' | 'control';

export interface ArchBox {
  id: string;
  label: string;
  runsIn?: string;
}

export interface ArchEdge {
  from: string;
  to: string;
  kind: EdgeKind;
  label?: string;
}

export interface ArchData {
  boxes: readonly ArchBox[];
  edges: readonly ArchEdge[];
}

export interface Pt {
  x: number;
  y: number;
}

export interface Rect {
  x: number;
  y: number;
  w: number;
  h: number;
}

export interface LaidGroup extends Rect {
  id: string;
}

export interface LaidNode extends Rect {
  id: string;
  group: string;
  row: number;
  col: number;
}

export interface LaidEdge {
  index: number;
  from: string;
  to: string;
  kind: EdgeKind;
  /** Polyline from the source box to the target box, right angles only. */
  points: Pt[];
  /** Middle of the longest segment: the place of the edge label (wide) or the numbered marker (compact). */
  mid: Pt;
}

export interface BoxLayout {
  mode: LayoutMode;
  width: number;
  height: number;
  groups: LaidGroup[];
  nodes: LaidNode[];
  edges: LaidEdge[];
}

interface Metrics {
  width: number;
  regionX: number;
  regionW: number;
  pad: number;
  head: number;
  foot: number;
  rowGap: number;
  groupGap: number;
  /** Margin lanes for edges that span several rows: x of the first lane and the step. */
  laneX: number;
  laneStep: number;
  /** Box size when a region holds three or more boxes (rows of `per`) and when it holds fewer. */
  many: { w: number; h: number; hgap: number; per: number };
  few: { w: number; h: number; hgap: number; per: number };
}

export const METRICS: Readonly<Record<LayoutMode, Metrics>> = {
  wide: {
    width: 720,
    regionX: 12,
    regionW: 696,
    pad: 12,
    head: 26,
    foot: 12,
    rowGap: 40,
    groupGap: 44,
    laneX: 3,
    laneStep: 3,
    many: { w: 144, h: 60, hgap: 32, per: 4 },
    few: { w: 144, h: 60, hgap: 32, per: 4 },
  },
  compact: {
    width: 264,
    regionX: 0,
    regionW: 248,
    pad: 12,
    head: 26,
    foot: 12,
    rowGap: 28,
    groupGap: 28,
    laneX: 252,
    laneStep: 3,
    many: { w: 70, h: 50, hgap: 7, per: 3 },
    few: { w: 224, h: 46, hgap: 0, per: 1 },
  },
};

type Side = 'top' | 'bottom' | 'left' | 'right';
type Route = 'straight' | 'arc' | 'adjacent' | 'lane';

interface Plan {
  edge: ArchEdge;
  index: number;
  from: LaidNode;
  to: LaidNode;
  route: Route;
  srcSide: Side;
  tgtSide: Side;
  /** Gap indices (between row g and g+1; -1 above the first row) that carry a horizontal segment: [near the source, near the target]. */
  gapSrc: number;
  gapTgt: number;
  lane: number;
}

const cx = (n: Rect): number => n.x + n.w / 2;
const cy = (n: Rect): number => n.y + n.h / 2;

export function layoutBoxes(data: ArchData, mode: LayoutMode): BoxLayout {
  const m = METRICS[mode];
  // ---- regions and boxes ----
  const order: string[] = [];
  const byGroup = new Map<string, ArchBox[]>();
  for (const box of data.boxes) {
    const g = box.runsIn ?? '';
    if (!byGroup.has(g)) {
      byGroup.set(g, []);
      order.push(g);
    }
    byGroup.get(g)?.push(box);
  }

  const groups: LaidGroup[] = [];
  const nodes: LaidNode[] = [];
  const rows: { top: number; bottom: number }[] = [];
  let y = 0;
  for (const g of order) {
    const boxes = byGroup.get(g) ?? [];
    const spec = mode === 'wide' || boxes.length >= 3 ? m.many : m.few;
    const per = spec.per;
    const rowCount = Math.ceil(boxes.length / per);
    const top = y;
    const firstRow = top + m.head;
    boxes.forEach((box, i) => {
      const row = Math.floor(i / per);
      const col = i % per;
      const inRow = Math.min(per, boxes.length - row * per);
      const rowWidth = inRow * spec.w + (inRow - 1) * spec.hgap;
      const left = m.regionX + (m.regionW - rowWidth) / 2;
      nodes.push({ id: box.id, group: g, row: rows.length + row, col, x: left + col * (spec.w + spec.hgap), y: firstRow + row * (spec.h + m.rowGap), w: spec.w, h: spec.h });
    });
    for (let r = 0; r < rowCount; r++) rows.push({ top: firstRow + r * (spec.h + m.rowGap), bottom: firstRow + r * (spec.h + m.rowGap) + spec.h });
    const height = m.head + rowCount * spec.h + (rowCount - 1) * m.rowGap + m.foot;
    groups.push({ id: g, x: m.regionX, y: top, w: m.regionW, h: height });
    y = top + height + m.groupGap;
  }
  const height = y - m.groupGap;

  // ---- edge plans ----
  const node = new Map(nodes.map((n) => [n.id, n]));
  let lanes = 0;
  const plans: Plan[] = data.edges.map((edge, index) => {
    const from = node.get(edge.from);
    const to = node.get(edge.to);
    if (!from || !to) throw new Error(`edge ${index} (${edge.from} -> ${edge.to}) names a box that does not exist`);
    if (from === to) throw new Error(`edge ${index} runs from ${edge.from} to itself`);
    const base = { edge, index, from, to, gapSrc: -1, gapTgt: -1, lane: 0 };
    if (from.row === to.row) {
      if (Math.abs(from.col - to.col) === 1 && from.group === to.group) {
        const right = to.col > from.col;
        return { ...base, route: 'straight', srcSide: right ? 'right' : 'left', tgtSide: right ? 'left' : 'right' };
      }
      return { ...base, route: 'arc', srcSide: 'top', tgtSide: 'top', gapSrc: from.row - 1, gapTgt: from.row - 1 };
    }
    const down = to.row > from.row;
    const srcSide: Side = down ? 'bottom' : 'top';
    const tgtSide: Side = down ? 'top' : 'bottom';
    const gapSrc = down ? from.row : from.row - 1;
    const gapTgt = down ? to.row - 1 : to.row;
    if (Math.abs(to.row - from.row) === 1) return { ...base, route: 'adjacent', srcSide, tgtSide, gapSrc, gapTgt };
    return { ...base, route: 'lane', srcSide, tgtSide, gapSrc, gapTgt, lane: lanes++ };
  });

  const laneOf = (p: Plan): number => (mode === 'wide' ? m.laneX + (p.lane % 3) * m.laneStep : m.laneX + (p.lane % 4) * m.laneStep);

  // ---- ports: edges on one side of a box share it evenly, ordered by where the other end is ----
  const portUse = new Map<string, { index: number; role: 'src' | 'tgt'; key: number }[]>();
  const use = (n: LaidNode, side: Side, index: number, role: 'src' | 'tgt', key: number): void => {
    const k = `${n.id}/${side}`;
    const list = portUse.get(k) ?? [];
    list.push({ index, role, key });
    portUse.set(k, list);
  };
  for (const p of plans) {
    const horizontal = p.srcSide === 'left' || p.srcSide === 'right';
    use(p.from, p.srcSide, p.index, 'src', p.route === 'lane' ? laneOf(p) : horizontal ? cy(p.to) : cx(p.to));
    use(p.to, p.tgtSide, p.index, 'tgt', p.route === 'lane' ? laneOf(p) : horizontal ? cy(p.from) : cx(p.from));
  }
  const portPos = (n: LaidNode, side: Side, index: number, role: 'src' | 'tgt'): Pt => {
    const list = [...(portUse.get(`${n.id}/${side}`) ?? [])].sort((a, b) => a.key - b.key || a.index - b.index);
    const k = list.findIndex((e) => e.index === index && e.role === role);
    const t = (k + 1) / (list.length + 1);
    switch (side) {
      case 'top':
        return { x: n.x + n.w * t, y: n.y };
      case 'bottom':
        return { x: n.x + n.w * t, y: n.y + n.h };
      case 'left':
        return { x: n.x, y: n.y + n.h * t };
      case 'right':
        return { x: n.x + n.w, y: n.y + n.h * t };
    }
  };

  // ---- gap slots: edges with a horizontal segment in the same gap between rows get separate heights ----
  const gapUse = new Map<number, number[]>();
  for (const p of plans) {
    if (p.route === 'straight') continue;
    for (const g of new Set([p.gapSrc, p.gapTgt])) gapUse.set(g, [...(gapUse.get(g) ?? []), p.index]);
  }
  const gapY = (g: number, index: number): number => {
    const first = rows[0];
    const last = rows[rows.length - 1];
    const lo = g < 0 ? (first?.top ?? 0) - 24 : (rows[g]?.bottom ?? 0);
    const hi = g >= rows.length - 1 ? (last?.bottom ?? 0) + 24 : (rows[g + 1]?.top ?? 0);
    const users = gapUse.get(g) ?? [index];
    const slot = users.indexOf(index);
    return lo + ((hi - lo) * (slot + 1)) / (users.length + 1);
  };

  // ---- polylines ----
  const edges: LaidEdge[] = plans.map((p) => {
    const a = portPos(p.from, p.srcSide, p.index, 'src');
    const b = portPos(p.to, p.tgtSide, p.index, 'tgt');
    let pts: Pt[];
    if (p.route === 'straight') {
      pts = a.y === b.y ? [a, b] : [a, { x: (a.x + b.x) / 2, y: a.y }, { x: (a.x + b.x) / 2, y: b.y }, b];
    } else if (p.route === 'lane') {
      const lx = laneOf(p);
      pts = [a, { x: a.x, y: gapY(p.gapSrc, p.index) }, { x: lx, y: gapY(p.gapSrc, p.index) }, { x: lx, y: gapY(p.gapTgt, p.index) }, { x: b.x, y: gapY(p.gapTgt, p.index) }, b];
    } else {
      const gy = gapY(p.gapSrc, p.index);
      pts = [a, { x: a.x, y: gy }, { x: b.x, y: gy }, b];
    }
    pts = simplify(pts);
    return { index: p.index, from: p.edge.from, to: p.edge.to, kind: p.edge.kind, points: pts, mid: midOfLongest(pts) };
  });

  return { mode, width: m.width, height, groups, nodes, edges };
}

/** Removes repeated points and points in the middle of a straight run. */
export function simplify(points: readonly Pt[]): Pt[] {
  const out: Pt[] = [];
  for (const p of points) {
    const last = out[out.length - 1];
    if (last && last.x === p.x && last.y === p.y) continue;
    out.push(p);
    while (out.length >= 3) {
      const [a, b, c] = out.slice(-3) as [Pt, Pt, Pt];
      if ((a.x === b.x && b.x === c.x) || (a.y === b.y && b.y === c.y)) out.splice(out.length - 2, 1);
      else break;
    }
  }
  return out;
}

export function midOfLongest(points: readonly Pt[]): Pt {
  let best: [Pt, Pt] | null = null;
  let bestLen = -1;
  for (let i = 1; i < points.length; i++) {
    const a = points[i - 1] as Pt;
    const b = points[i] as Pt;
    const len = Math.abs(a.x - b.x) + Math.abs(a.y - b.y);
    if (len > bestLen) {
      bestLen = len;
      best = [a, b];
    }
  }
  return best ? { x: (best[0].x + best[1].x) / 2, y: (best[0].y + best[1].y) / 2 } : (points[0] ?? { x: 0, y: 0 });
}

/** The path `d` of a polyline. */
export const pathOf = (points: readonly Pt[]): string => points.map((p, i) => `${i === 0 ? 'M' : 'L'}${round(p.x)} ${round(p.y)}`).join('');

const round = (v: number): string => String(Math.round(v * 100) / 100);

/**
 * The head of an edge as its own path (design notes, section 7): a filled triangle for data, an open angle of two strokes for control.
 * The tip is the last point; `size` is the length and the width (9 units, 11 when active).
 */
export function headPath(points: readonly Pt[], kind: EdgeKind, size = 9): string {
  const tip = points[points.length - 1];
  const prev = points[points.length - 2];
  if (!tip || !prev) return '';
  const len = Math.hypot(tip.x - prev.x, tip.y - prev.y) || 1;
  const ux = (tip.x - prev.x) / len;
  const uy = (tip.y - prev.y) / len;
  const bx = tip.x - ux * size;
  const by = tip.y - uy * size;
  const l = `${round(bx - uy * (size / 2))} ${round(by + ux * (size / 2))}`;
  const r = `${round(bx + uy * (size / 2))} ${round(by - ux * (size / 2))}`;
  const t = `${round(tip.x)} ${round(tip.y)}`;
  return kind === 'data' ? `M${t}L${l}L${r}Z` : `M${l}L${t}L${r}`;
}

/** Percent of the wrapper for an element of the layout (the boxes are HTML, positioned in per cent of W and H). */
export const percent = (layout: BoxLayout, r: Rect): { left: string; top: string; width: string; height: string } => ({
  left: `${(r.x / layout.width) * 100}%`,
  top: `${(r.y / layout.height) * 100}%`,
  width: `${(r.w / layout.width) * 100}%`,
  height: `${(r.h / layout.height) * 100}%`,
});
