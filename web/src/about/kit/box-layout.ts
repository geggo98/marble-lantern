// Layout of a box diagram from the data files `arch-*.json` (design notes, section 6, "BoxDiagram", and section 7).
// Pure geometry, no React. One coordinate system for the HTML boxes and the SVG edge layer: view box `0 0 width height`.
//
// The data files hold boxes (id, label, runsIn) and edges (from, to, kind, label) but no positions, so the layout is generic:
//   - one region per distinct `runsIn`. A region is a sunken panel with a label in its top left corner (a header band of 16 units that
//     no edge crosses) and rows of boxes in file order;
//   - wide (720 units, boxes 144 x 60): regions are grouped into bands (`options.bands`; default one region per band). The regions of a
//     band stand side by side, up to four boxes per row, and the bands are stacked. Edge labels sit on the edges;
//   - compact (264 units): every region is a band of its own, three boxes (70 x 50) per row for a region of three or more boxes, otherwise
//     one full-width box (224 x 46) per row. Edges carry numbered markers; the list under the diagram names them;
//   - edges are routed at right angles by a grid search (box-route.ts) that keeps clear of boxes and region labels and lets parallel edges
//     take their own lanes.

import { routeEdges } from './box-route';

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
  /** Row over the whole diagram (the regions of one band share the rows) and column within the row of its region. */
  row: number;
  col: number;
}

/** The label of an edge in the wide layout: centre, size and the lines it is wrapped into. */
export interface LaidLabel {
  x: number;
  y: number;
  w: number;
  h: number;
  lines: string[];
}

export interface LaidEdge {
  index: number;
  from: string;
  to: string;
  kind: EdgeKind;
  /** Polyline from the source box to the target box, right angles only. */
  points: Pt[];
  /** Place of the numbered marker (when `numbered`) or the centre of the label. */
  mid: Pt;
  /** Wide layout: the label, when the edge has one and a free place was found for it. */
  label?: LaidLabel;
  /** The edge carries a numbered marker and a row in the visible list: always in the compact layout, in the wide layout when its label found no free place. */
  numbered: boolean;
}

export interface BoxLayout {
  mode: LayoutMode;
  width: number;
  height: number;
  groups: LaidGroup[];
  nodes: LaidNode[];
  edges: LaidEdge[];
  /** The text of each region label (obstacle for edges), by group id. */
  groupLabels: Record<string, Rect>;
}

export interface LayoutOptions {
  /** Wide layout: the regions (group ids) of each band; a band is a strip of regions side by side. Regions left out get a band each. */
  bands?: readonly (readonly string[])[];
  /** Text of the edge labels by edge index (default: `label` of the edge); only its length matters. */
  edgeText?: Readonly<Record<number, string>>;
  /** Text of the region labels by group id (default: the id). */
  groupText?: Readonly<Record<string, string>>;
}

interface Spec {
  w: number;
  h: number;
  hgap: number;
  per: number;
}

interface Metrics {
  width: number;
  /** Where the regions may stand: left edge and right edge. */
  left: number;
  right: number;
  pad: number;
  head: number;
  foot: number;
  rowGap: number;
  bandGap: number;
  top: number;
  bottom: number;
  /** Space between two regions of one band. */
  gutter: number;
  many: Spec;
  few: Spec;
}

export const METRICS: Readonly<Record<LayoutMode, Metrics>> = {
  wide: {
    width: 720,
    left: 12,
    right: 708,
    pad: 12,
    head: 34,
    foot: 14,
    rowGap: 68,
    bandGap: 60,
    top: 12,
    bottom: 20,
    gutter: 40,
    many: { w: 144, h: 60, hgap: 32, per: 4 },
    few: { w: 144, h: 60, hgap: 32, per: 4 },
  },
  compact: {
    width: 264,
    left: 14,
    right: 250,
    pad: 6,
    head: 34,
    foot: 12,
    rowGap: 48,
    bandGap: 48,
    top: 4,
    bottom: 20,
    gutter: 0,
    many: { w: 70, h: 50, hgap: 7, per: 3 },
    few: { w: 224, h: 46, hgap: 0, per: 1 },
  },
};

/** Estimated size of text in units: a region label (upper case, 12 px with letter spacing) and an edge label (12 px monospace). */
const GROUP_CHAR = 9.2;
const GROUP_LABEL_H = 14;
const EDGE_CHAR = 7.6;
const EDGE_LINE = 15;

const labelRect = (g: Rect, chars: number): Rect => ({ x: g.x + 10, y: g.y + 4, w: chars * GROUP_CHAR + 6, h: GROUP_LABEL_H });

interface Region {
  id: string;
  boxes: ArchBox[];
  per: number;
}

const natural = (spec: Spec, per: number, pad: number): number => per * spec.w + (per - 1) * spec.hgap + 2 * pad;

export function layoutBoxes(data: ArchData, mode: LayoutMode, options: LayoutOptions = {}): BoxLayout {
  const m = METRICS[mode];
  // ---- regions ----
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
  const regionOf = (id: string): Region => {
    const boxes = byGroup.get(id) ?? [];
    return { id, boxes, per: mode === 'wide' ? Math.min(boxes.length, m.many.per) : boxes.length >= 3 ? m.many.per : m.few.per };
  };
  const spec = (r: Region): Spec => (mode === 'wide' || r.boxes.length >= 3 ? m.many : m.few);

  // ---- bands ----
  const bands: Region[][] = [];
  const placed = new Set<string>();
  if (mode === 'wide') {
    for (const band of options.bands ?? []) {
      const regions = band.filter((id) => byGroup.has(id) && !placed.has(id));
      regions.forEach((id) => placed.add(id));
      if (regions.length > 0) bands.push(regions.map(regionOf));
    }
  }
  for (const id of order) if (!placed.has(id)) bands.push([regionOf(id)]);

  const groups: LaidGroup[] = [];
  const nodes: LaidNode[] = [];
  let y = m.top;
  let rowBase = 0;
  for (const band of bands) {
    const inner = m.right - m.left;
    // boxes per row: shrink the widest region until the band fits
    const fits = (): boolean => band.reduce((s, r) => s + natural(spec(r), r.per, m.pad), 0) + (band.length - 1) * m.gutter <= inner;
    while (mode === 'wide' && !fits()) {
      const widest = band.filter((r) => r.per > 1).sort((a, b) => natural(spec(b), b.per, m.pad) - natural(spec(a), a.per, m.pad))[0];
      if (!widest) throw new Error(`regions ${band.map((r) => r.id).join(', ')} do not fit into one band`);
      widest.per--;
    }
    const used = band.reduce((s, r) => s + natural(spec(r), r.per, m.pad), 0);
    const spare = Math.max(0, inner - used - (band.length - 1) * m.gutter);
    const gutter = m.gutter + (band.length > 1 ? Math.min(spare * 0.5 / (band.length - 1), 56) : 0);
    const extra = (inner - used - (band.length - 1) * gutter) / band.length;
    const rowCount = Math.max(...band.map((r) => Math.ceil(r.boxes.length / r.per)));
    const boxH = Math.max(...band.map((r) => spec(r).h));
    const height = m.head + rowCount * boxH + (rowCount - 1) * m.rowGap + m.foot;
    let x = m.left;
    for (const r of band) {
      const s = spec(r);
      const width = mode === 'wide' ? natural(s, r.per, m.pad) + extra : inner;
      groups.push({ id: r.id, x, y, w: width, h: height });
      r.boxes.forEach((box, i) => {
        const row = Math.floor(i / r.per);
        const col = i % r.per;
        const inRow = Math.min(r.per, r.boxes.length - row * r.per);
        const rowWidth = inRow * s.w + (inRow - 1) * s.hgap;
        nodes.push({
          id: box.id,
          group: r.id,
          row: rowBase + row,
          col,
          x: x + (width - rowWidth) / 2 + col * (s.w + s.hgap),
          y: y + m.head + row * (s.h + m.rowGap),
          w: s.w,
          h: s.h,
        });
      });
      x += width + gutter;
    }
    rowBase += rowCount;
    y += height + m.bandGap;
  }
  const height = y - m.bandGap + m.bottom;

  // ---- edges ----
  const node = new Map(nodes.map((n) => [n.id, n]));
  data.edges.forEach((edge, index) => {
    if (!node.has(edge.from) || !node.has(edge.to)) throw new Error(`edge ${index} (${edge.from} -> ${edge.to}) names a box that does not exist`);
    if (edge.from === edge.to) throw new Error(`edge ${index} runs from ${edge.from} to itself`);
  });
  const groupLabels: Record<string, Rect> = {};
  for (const g of groups) groupLabels[g.id] = labelRect(g, (options.groupText?.[g.id] ?? g.id).length);
  const routes = routeEdges({
    width: m.width,
    height,
    boxes: nodes,
    keepOut: Object.values(groupLabels),
    costly: groups.map((g) => ({ x: g.x, y: g.y, w: g.w, h: m.head })),
    noHorizontal: mode === 'wide' ? narrowGaps(nodes) : [],
    edges: data.edges.map((e) => ({ from: node.get(e.from) as Rect, to: node.get(e.to) as Rect })),
  });
  const polylines = routes.map(simplify);
  const edges: LaidEdge[] = data.edges.map((e, index) => ({ index, from: e.from, to: e.to, kind: e.kind, points: polylines[index] as Pt[], mid: midOfLongest(polylines[index] as Pt[]), numbered: mode === 'compact' }));
  const regionLabels = Object.values(groupLabels);
  let taken: Rect[] = [];
  if (mode === 'wide') taken = placeLabels(edges, nodes, regionLabels, (i) => options.edgeText?.[i] ?? data.edges[i]?.label ?? '', m.width, height);
  placeMarkers(edges.filter((e) => e.numbered), nodes, [...regionLabels, ...taken], m.width);

  return { mode, width: m.width, height, groups, nodes, edges, groupLabels };
}

/** The strips between two boxes that stand side by side and are less than 56 units apart: too narrow for a label, so edges cross them only vertically. */
function narrowGaps(nodes: readonly LaidNode[]): Rect[] {
  const out: Rect[] = [];
  for (const a of nodes) {
    for (const b of nodes) {
      if (a.row !== b.row || b.x < a.x + a.w) continue;
      const gap = b.x - (a.x + a.w);
      if (gap >= 56 || nodes.some((n) => n !== a && n !== b && n.row === a.row && n.x >= a.x + a.w && n.x + n.w <= b.x)) continue;
      out.push({ x: a.x + a.w, y: a.y, w: gap, h: a.h });
    }
  }
  return out;
}

// ---------------------------------------------------------------------------------------------------------------- geometry helpers

const segmentsOf = (points: readonly Pt[]): [Pt, Pt][] => points.slice(1).map((p, i) => [points[i] as Pt, p]);
const rectDistance = (p: Pt, r: Rect): number => Math.hypot(Math.max(r.x - p.x, 0, p.x - (r.x + r.w)), Math.max(r.y - p.y, 0, p.y - (r.y + r.h)));
const grow = (r: Rect, by: number): Rect => ({ x: r.x - by, y: r.y - by, w: r.w + 2 * by, h: r.h + 2 * by });
const rectsMeet = (a: Rect, b: Rect): boolean => a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h;
const segmentMeets = (p: Pt, q: Pt, r: Rect): boolean => Math.max(p.x, q.x) > r.x && Math.min(p.x, q.x) < r.x + r.w && Math.max(p.y, q.y) > r.y && Math.min(p.y, q.y) < r.y + r.h;

/** Numbered markers of the compact layout: on a straight run, clear of boxes and region labels, at least 24 units from each other. */
function placeMarkers(edges: LaidEdge[], nodes: readonly Rect[], labels: readonly Rect[], width: number): void {
  const placed: Pt[] = [];
  // the edges with the fewest places first
  for (const e of [...edges].sort((a, b) => length(a.points) - length(b.points) || a.index - b.index)) {
    let best: { p: Pt; score: number } | undefined;
    const tiers: { box: number; apart: number }[] = [
      { box: 14, apart: 24 },
      { box: 12, apart: 20 },
      { box: 9, apart: 14 },
      { box: 0, apart: 0 },
    ];
    for (const tier of tiers) {
      for (const [a, b] of segmentsOf(e.points)) {
        const len = Math.abs(a.x - b.x) + Math.abs(a.y - b.y);
        for (let t = 8; t <= len - 8 || (t === 8 && len > 0); t += 4) {
          const f = Math.min(t, len) / len;
          const p = { x: a.x + (b.x - a.x) * f, y: a.y + (b.y - a.y) * f };
          if (p.x < 11 || p.x > width - 11 || nodes.some((n) => rectDistance(p, n) < tier.box) || labels.some((l) => rectDistance(p, l) < 12)) continue;
          if (placed.some((q) => Math.hypot(q.x - p.x, q.y - p.y) < tier.apart)) continue;
          const score = len - Math.abs(Math.min(t, len) - len / 2) * 0.5 + Math.min(...nodes.map((n) => rectDistance(p, n)), 40) * 0.2;
          if (!best || score > best.score) best = { p, score };
        }
      }
      if (best) break;
    }
    e.mid = best?.p ?? e.mid;
    placed.push(e.mid);
  }
}

/** Splits `text` into at most `lines` lines of about equal length, breaking at spaces. */
export function wrapLabel(text: string, lines: number): string[] {
  const words = text.split(/\s+/).filter(Boolean);
  if (lines <= 1 || words.length <= 1) return [words.join(' ')];
  for (let max = Math.ceil(text.length / lines); max <= text.length; max++) {
    const out: string[] = [];
    let line = '';
    for (const w of words) {
      if (line && line.length + 1 + w.length > max) {
        out.push(line);
        line = w;
      } else line = line ? `${line} ${w}` : w;
    }
    out.push(line);
    if (out.length <= lines) return out;
  }
  return [words.join(' ')];
}

/** Edge labels of the wide layout: on a run of the edge, clear of boxes, region labels and other labels, over as few other edges as possible. */
function placeLabels(edges: LaidEdge[], nodes: readonly Rect[], labels: readonly Rect[], text: (index: number) => string, width: number, height: number): Rect[] {
  const taken: Rect[] = [];
  const solid = [...nodes.map((n) => grow(n, 3)), ...labels.map((l) => grow(l, 2))];
  const sorted = [...edges].sort((a, b) => length(b.points) - length(a.points) || a.index - b.index);
  for (const e of sorted) {
    const s = text(e.index);
    if (!s) continue; // an edge without text has nothing to say: no label, no marker
    const variants = [1, 2, 3].map((n) => wrapLabel(s, n)).filter((v, i, all) => i === 0 || v.length > (all[i - 1] as string[]).length);
    const ends = [e.points[0] as Pt, e.points[e.points.length - 1] as Pt].map((p) => grow({ x: p.x, y: p.y, w: 0, h: 0 }, 13));
    let best: { rect: Rect; lines: string[]; cost: number } | undefined;
    for (const lines of variants) {
      const w = Math.max(...lines.map((l) => l.length)) * EDGE_CHAR + 10;
      const h = lines.length * EDGE_LINE + 4;
      for (const [a, b] of segmentsOf(e.points)) {
        const len = Math.abs(a.x - b.x) + Math.abs(a.y - b.y);
        const vertical = a.x === b.x;
        for (let t = 0; t <= len; t += 4) {
          const c = { x: a.x + ((b.x - a.x) * t) / (len || 1), y: a.y + ((b.y - a.y) * t) / (len || 1) };
          const rect: Rect = { x: c.x - w / 2, y: c.y - h / 2, w, h };
          if (rect.x < 0 || rect.y < 0 || rect.x + rect.w > width || rect.y + rect.h > height) continue;
          if (solid.some((r) => rectsMeet(rect, r)) || taken.some((r) => rectsMeet(rect, grow(r, 2))) || ends.some((r) => rectsMeet(rect, r))) continue;
          let cost = Math.abs(t - len / 2) * 0.1 + (vertical ? 6 : 0) + (lines.length - 1) * 8;
          for (const other of edges) {
            for (const [p, q] of segmentsOf(other.points)) if (segmentMeets(p, q, rect) && !(other === e && p === a)) cost += other === e ? 10 : 40;
          }
          if (!best || cost < best.cost) best = { rect, lines, cost };
        }
      }
    }
    if (best && best.cost < 85) {
      taken.push(best.rect);
      e.label = { x: best.rect.x + best.rect.w / 2, y: best.rect.y + best.rect.h / 2, w: best.rect.w, h: best.rect.h, lines: best.lines };
    } else {
      e.numbered = true;
      continue;
    }
    e.mid = { x: e.label.x, y: e.label.y };
  }
  return taken;
}

const length = (points: readonly Pt[]): number => segmentsOf(points).reduce((s, [a, b]) => s + Math.abs(a.x - b.x) + Math.abs(a.y - b.y), 0);

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
