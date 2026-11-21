// Orthogonal edge routing for the box diagrams (box-layout.ts). A grid search on a 4 unit grid: boxes and the text of the regions are
// obstacles, an edge leaves a box with a straight leg of at least LEG units (room for the arrow head), turns cost, running along or beside
// another edge costs a lot and crossing one costs a little, so parallel edges spread to their own lanes and crossings stay rare.
// Pure geometry, no React. Used for the wide and the compact layout alike.
import type { Pt, Rect } from './box-layout';

export const GRID = 4;
/** Clearance of an edge from a box that is not one of its ends. */
export const CLEARANCE = 5;
/** Length of the straight leg at both ends of an edge (the head is 9 to 11 units long). */
export const LEG = 10;

const BEND = 6;
const SHARE = 40;
const BESIDE = 5;
const CROSS = 3;
const NEAR_BOX = 1.5;

type Dir = 0 | 1 | 2 | 3; // 0 right, 1 down, 2 left, 3 up
const DX = [1, 0, -1, 0];
const DY = [0, 1, 0, -1];

export interface RouteRequest {
  from: Rect;
  to: Rect;
}

export interface RouteInput {
  width: number;
  height: number;
  boxes: readonly Rect[];
  /** Further obstacles: the text of the regions. */
  keepOut: readonly Rect[];
  /** Strips between two boxes of a row where an edge may cross vertically but not run sideways (so its label has room in the corridors). */
  noHorizontal?: readonly Rect[];
  /** Strips an edge should avoid (the header band of a region: no room for a label there). */
  costly?: readonly Rect[];
  edges: readonly RouteRequest[];
}

interface Port {
  i: number;
  j: number;
  /** Direction of travel when leaving the box (source) or entering it (target). */
  dir: Dir;
  boundary: Pt;
}

const inflated = (r: Rect, by: number): Rect => ({ x: r.x - by, y: r.y - by, w: r.w + 2 * by, h: r.h + 2 * by });
const strictlyInside = (x: number, y: number, r: Rect): boolean => x > r.x && x < r.x + r.w && y > r.y && y < r.y + r.h;
const up = (v: number): number => Math.ceil(v / GRID) * GRID;
const down = (v: number): number => Math.floor(v / GRID) * GRID;

/** The grid points a leg can reach on each side of a box. */
function portsOf(box: Rect, out: boolean): Port[] {
  const ports: Port[] = [];
  const margin = 8;
  const right = box.x + box.w;
  const bottom = box.y + box.h;
  for (let x = up(box.x + margin); x <= right - margin; x += GRID) {
    ports.push({ i: x / GRID, j: up(bottom + LEG) / GRID, dir: out ? 1 : 3, boundary: { x, y: bottom } });
    ports.push({ i: x / GRID, j: down(box.y - LEG) / GRID, dir: out ? 3 : 1, boundary: { x, y: box.y } });
  }
  for (let y = up(box.y + margin); y <= bottom - margin; y += GRID) {
    ports.push({ i: up(right + LEG) / GRID, j: y / GRID, dir: out ? 0 : 2, boundary: { x: right, y } });
    ports.push({ i: down(box.x - LEG) / GRID, j: y / GRID, dir: out ? 2 : 0, boundary: { x: box.x, y } });
  }
  return ports;
}

class MinHeap {
  private keys: number[] = [];
  private vals: number[] = [];
  get size(): number {
    return this.keys.length;
  }
  push(key: number, val: number): void {
    const k = this.keys;
    const v = this.vals;
    let i = k.length;
    k.push(key);
    v.push(val);
    while (i > 0) {
      const p = (i - 1) >> 1;
      if (k[p]! <= key) break;
      k[i] = k[p]!;
      v[i] = v[p]!;
      i = p;
    }
    k[i] = key;
    v[i] = val;
  }
  pop(): number {
    const k = this.keys;
    const v = this.vals;
    const top = v[0]!;
    const lastK = k.pop()!;
    const lastV = v.pop()!;
    const n = k.length;
    if (n > 0) {
      let i = 0;
      for (;;) {
        let c = 2 * i + 1;
        if (c >= n) break;
        if (c + 1 < n && k[c + 1]! < k[c]!) c++;
        if (k[c]! >= lastK) break;
        k[i] = k[c]!;
        v[i] = v[c]!;
        i = c;
      }
      k[i] = lastK;
      v[i] = lastV;
    }
    return top;
  }
}

const distToRect = (x: number, y: number, r: Rect): number => Math.hypot(Math.max(r.x - x, 0, x - (r.x + r.w)), Math.max(r.y - y, 0, y - (r.y + r.h)));

/** Routes every edge; returns one polyline per request (boundary of the source to boundary of the target), or throws for an edge without a way. */
export function routeEdges(input: RouteInput): Pt[][] {
  const nx = Math.floor(input.width / GRID) + 1;
  const ny = Math.floor(input.height / GRID) + 1;
  const at = (i: number, j: number): number => j * nx + i;
  const blocked = new Uint8Array(nx * ny);
  const near = new Uint8Array(nx * ny);
  const solid = [...input.boxes.map((b) => inflated(b, CLEARANCE)), ...input.keepOut.map((k) => inflated(k, 2))];
  const halo = input.boxes.map((b) => inflated(b, 9));
  for (let j = 0; j < ny; j++) {
    for (let i = 0; i < nx; i++) {
      const x = i * GRID;
      const y = j * GRID;
      if (solid.some((r) => strictlyInside(x, y, r))) blocked[at(i, j)] = 1;
      else if (halo.some((r) => strictlyInside(x, y, r))) near[at(i, j)] = 1;
    }
  }
  const noH = new Uint8Array(nx * ny);
  for (const r of input.noHorizontal ?? []) {
    for (let j = 0; j < ny; j++) for (let i = 0; i < nx; i++) if (i * GRID > r.x && i * GRID < r.x + r.w && j * GRID >= r.y && j * GRID <= r.y + r.h) noH[at(i, j)] = 1;
  }
  for (const r of input.costly ?? []) {
    for (let j = 0; j < ny; j++) for (let i = 0; i < nx; i++) if (i * GRID > r.x && i * GRID < r.x + r.w && j * GRID >= r.y && j * GRID <= r.y + r.h) near[at(i, j)] = 2;
  }
  const hUse = new Uint8Array(nx * ny);
  const vUse = new Uint8Array(nx * ny);
  const free = (p: Port): boolean => p.i >= 0 && p.j >= 0 && p.i < nx && p.j < ny && !blocked[at(p.i, p.j)] && !((p.dir === 0 || p.dir === 2) && noH[at(p.i, p.j)]);

  const order = input.edges
    .map((e, index) => ({ index, d: Math.abs(e.from.x + e.from.w / 2 - (e.to.x + e.to.w / 2)) + Math.abs(e.from.y + e.from.h / 2 - (e.to.y + e.to.h / 2)) }))
    .sort((a, b) => a.d - b.d || a.index - b.index);

  const result: Pt[][] = new Array<Pt[]>(input.edges.length);
  const gScore = new Float64Array(nx * ny * 4);
  const came = new Int32Array(nx * ny * 4);
  const closed = new Uint8Array(nx * ny * 4);
  for (const { index } of order) {
    const req = input.edges[index]!;
    const sources = portsOf(req.from, true).filter(free);
    const targets = new Map<number, Port>();
    for (const p of portsOf(req.to, false)) if (free(p)) targets.set(at(p.i, p.j) * 4 + p.dir, p);
    if (sources.length === 0 || targets.size === 0) throw new Error(`edge ${index} has no free port`);
    const h = (i: number, j: number): number => Math.max(0, distToRect(i * GRID, j * GRID, req.to) - LEG - GRID) / GRID;

    gScore.fill(Infinity);
    came.fill(-2);
    closed.fill(0);
    const heap = new MinHeap();
    for (const s of sources) {
      const state = at(s.i, s.j) * 4 + s.dir;
      gScore[state] = 0;
      came[state] = -1;
      heap.push(h(s.i, s.j), state);
    }
    let goal = -1;
    let goalPort: Port | undefined;
    while (heap.size > 0) {
      const state = heap.pop();
      if (closed[state]) continue;
      closed[state] = 1;
      const target = targets.get(state);
      if (target) {
        goal = state;
        goalPort = target;
        break;
      }
      const cell = state >> 2;
      const dir = state & 3;
      const i = cell % nx;
      const j = (cell - i) / nx;
      for (let nd = 0; nd < 4; nd++) {
        if (nd === ((dir + 2) & 3)) continue; // no U-turn
        const ni = i + DX[nd]!;
        const nj = j + DY[nd]!;
        if (ni < 0 || nj < 0 || ni >= nx || nj >= ny) continue;
        const nc = at(ni, nj);
        if (blocked[nc]) continue;
        const horizontal = nd === 0 || nd === 2;
        if (horizontal && (noH[nc] || noH[cell])) continue;
        let cost = 1 + (nd === dir ? 0 : BEND) + (near[nc] === 2 ? 4 : near[nc] ? NEAR_BOX : 0);
        const same = horizontal ? hUse : vUse;
        const other = horizontal ? vUse : hUse;
        if (same[nc]) cost += SHARE;
        else if (horizontal ? (nj > 0 && hUse[nc - nx]) || (nj < ny - 1 && hUse[nc + nx]) : (ni > 0 && vUse[nc - 1]) || (ni < nx - 1 && vUse[nc + 1])) cost += BESIDE;
        if (other[nc]) cost += CROSS;
        const ns = nc * 4 + nd;
        const g = gScore[state]! + cost;
        if (g < gScore[ns]!) {
          gScore[ns] = g;
          came[ns] = state;
          heap.push(g + h(ni, nj), ns);
        }
      }
    }
    if (goal < 0 || !goalPort) throw new Error(`edge ${index} cannot be routed`);

    const cells: Pt[] = [];
    let state = goal;
    let first = goal;
    while (state >= 0) {
      const cell = state >> 2;
      const i = cell % nx;
      cells.push({ x: i * GRID, y: ((cell - i) / nx) * GRID });
      first = state;
      state = came[state]!;
    }
    cells.reverse();
    const startPort = sources.find((s) => at(s.i, s.j) * 4 + s.dir === first);
    if (!startPort) throw new Error(`edge ${index}: lost the start`);
    const pts: Pt[] = [startPort.boundary, ...cells, goalPort.boundary];
    result[index] = pts;
    for (let k = 1; k < pts.length; k++) {
      const a = pts[k - 1]!;
      const b = pts[k]!;
      const horizontal = a.y === b.y;
      const steps = Math.max(1, Math.round(Math.max(Math.abs(a.x - b.x), Math.abs(a.y - b.y)) / GRID));
      for (let s = 0; s <= steps; s++) {
        const x = Math.round((a.x + ((b.x - a.x) * s) / steps) / GRID);
        const y = Math.round((a.y + ((b.y - a.y) * s) / steps) / GRID);
        if (x < 0 || y < 0 || x >= nx || y >= ny) continue;
        (horizontal ? hUse : vUse)[at(x, y)] = 1;
      }
    }
  }
  return result;
}
