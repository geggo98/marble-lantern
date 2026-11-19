// Pure logic of the board screen: cell names, screen positions, tap resolution, keyboard moves, notation.
// No React in here, so that node tests cover it (design/design-notes.md, sections 6 and 7).
import { CELLS, CELL_COUNT, DIRECTIONS, GOAL_CELLS, neighbor } from '../board/cells';
import { jumpPath } from '../board/path';
import { makeMove, moveFrom, moveTo } from '../engines/engine';
import type { Side } from '../game/board-state';

/** `viewBox="-234 -266 468 532"`; a unit of 20 (design-notes.md, section 6). */
export const VIEW_BOX = { x: -234, y: -266, width: 468, height: 532 } as const;
const UNIT = 20;

export interface Point {
  x: number;
  y: number;
}

const BASE: readonly Point[] = CELLS.map((c) => ({
  x: Math.sqrt(3) * UNIT * (c.q + c.r / 2),
  y: 1.5 * UNIT * c.r,
}));

/** Screen position of a cell centre. A rotated board negates both coordinates (human plays the top start corner). */
export function cellPoint(id: number, rotated: boolean): Point {
  const p = BASE[id];
  if (!p) throw new RangeError(`no such cell: ${id}`);
  return rotated ? { x: -p.x, y: -p.y } : p;
}

// ---- names: rows A to Q from the top of the unrotated board, numbered from 1 in rising q ----

const NAMES: string[] = new Array<string>(CELL_COUNT);
for (let row = 0; row < 17; row++) {
  const inRow = CELLS.filter((c) => c.r + 8 === row).sort((a, b) => a.q - b.q);
  inRow.forEach((c, i) => {
    NAMES[c.id] = `${String.fromCharCode(65 + row)}${i + 1}`;
  });
}

/** "A1" is the top tip, "Q1" the bottom tip of the unrotated board. The name does not change when the board rotates. */
export function cellName(id: number): string {
  const name = NAMES[id];
  if (!name) throw new RangeError(`no such cell: ${id}`);
  return name;
}

// ---- legal moves ----

/** Cells with an own piece that has at least one legal move. */
export function movableCells(legal: readonly number[]): number[] {
  return [...new Set(legal.map(moveFrom))];
}

/** The cells the piece on `from` can reach, in the order of the move list. */
export function targetsOf(legal: readonly number[], from: number): number[] {
  return legal.filter((m) => moveFrom(m) === from).map(moveTo);
}

// ---- tap resolution (design-notes.md, section 7, "Pointer and touch") ----

export type TapAction =
  | { type: 'select'; cell: number }
  | { type: 'move'; move: number }
  | { type: 'clear' }
  | { type: 'illegal'; cell: number }
  | { type: 'none' };

export interface TapInput {
  selected: number | null;
  legal: readonly number[];
  rotated: boolean;
  /** The cell element that was hit (keyboard, or the element under the finger). null: outside every cell. */
  hitCell: number | null;
  /** Position of the tap in board units. Without it only `hitCell` counts. */
  point?: Point;
  /** Reach of a candidate in board units; 16 CSS px (a 32 px target). */
  radius?: number;
}

function nearest(point: Point, cells: readonly number[], rotated: boolean, radius: number): number | null {
  let best: number | null = null;
  let bestDistance = radius;
  for (const c of cells) {
    const p = cellPoint(c, rotated);
    const d = Math.hypot(p.x - point.x, p.y - point.y);
    if (d <= bestDistance) {
      best = c;
      bestDistance = d;
    }
  }
  return best;
}

/**
 * Rules: candidates without a selection are the own movable pieces; with a selection they are the targets of the
 * selected piece and the other movable pieces. The nearest candidate within the radius wins. A tap with a selection
 * and no candidate in reach is illegal if it lands on a cell, and clears the selection if it lands outside.
 */
export function resolveTap(input: TapInput): TapAction {
  const { selected, legal, rotated, point } = input;
  const radius = input.radius ?? 16;
  const movable = movableCells(legal);
  const targets = selected === null ? [] : targetsOf(legal, selected);
  const candidates = selected === null ? movable : [...new Set([...targets, ...movable])];

  let picked: number | null;
  let hit = input.hitCell;
  if (point) {
    picked = nearest(point, candidates, rotated, radius);
    hit = nearest(point, CELLS.map((c) => c.id), rotated, radius);
  } else {
    picked = hit !== null && candidates.includes(hit) ? hit : null;
  }

  if (picked !== null) {
    if (selected !== null && targets.includes(picked)) return { type: 'move', move: makeMove(selected, picked) };
    if (picked === selected) return { type: 'clear' };
    return { type: 'select', cell: picked };
  }
  if (selected === null) return { type: 'none' };
  return hit === null ? { type: 'clear' } : { type: 'illegal', cell: hit };
}

// ---- keyboard ----

export type ArrowKey = 'ArrowLeft' | 'ArrowRight' | 'ArrowUp' | 'ArrowDown' | 'Home' | 'End';

interface Rows {
  rows: number[][]; // top to bottom on screen, each row left to right
  rowOf: number[];
}

const rowCache = new Map<boolean, Rows>();

function rowsFor(rotated: boolean): Rows {
  const cached = rowCache.get(rotated);
  if (cached) return cached;
  const byY = new Map<number, number[]>();
  for (const c of CELLS) {
    const y = Math.round(cellPoint(c.id, rotated).y);
    const row = byY.get(y) ?? [];
    row.push(c.id);
    byY.set(y, row);
  }
  const rows = [...byY.entries()]
    .sort((a, b) => a[0] - b[0])
    .map(([, ids]) => ids.sort((a, b) => cellPoint(a, rotated).x - cellPoint(b, rotated).x));
  const rowOf: number[] = new Array<number>(CELL_COUNT).fill(-1);
  rows.forEach((ids, i) => ids.forEach((id) => (rowOf[id] = i)));
  const built = { rows, rowOf };
  rowCache.set(rotated, built);
  return built;
}

/**
 * Where an arrow key moves the focus. `x` is the x position where vertical movement started, so that
 * Up and then Down returns to the same cell. Returns the same cell at an edge.
 */
export function navigate(from: number, key: ArrowKey, rotated: boolean, x: number | null): { cell: number; x: number } {
  const { rows, rowOf } = rowsFor(rotated);
  const rowIndex = rowOf[from] ?? 0;
  const row = rows[rowIndex] ?? [];
  const here = cellPoint(from, rotated);
  const keep = (cell: number): { cell: number; x: number } => ({ cell, x: cellPoint(cell, rotated).x });
  const col = row.indexOf(from);
  switch (key) {
    case 'ArrowLeft':
      return keep(row[Math.max(0, col - 1)] ?? from);
    case 'ArrowRight':
      return keep(row[Math.min(row.length - 1, col + 1)] ?? from);
    case 'Home':
      return keep(row[0] ?? from);
    case 'End':
      return keep(row[row.length - 1] ?? from);
    case 'ArrowUp':
    case 'ArrowDown': {
      const target = rows[rowIndex + (key === 'ArrowUp' ? -1 : 1)];
      const wanted = x ?? here.x;
      if (!target) return { cell: from, x: wanted };
      let best = target[0] ?? from;
      for (const c of target) {
        if (Math.abs(cellPoint(c, rotated).x - wanted) < Math.abs(cellPoint(best, rotated).x - wanted)) best = c;
      }
      return { cell: best, x: wanted };
    }
  }
}

/** Order for Page Up and Page Down: by row, then by x. */
export function reading(cells: readonly number[], rotated: boolean): number[] {
  return [...cells].sort((a, b) => {
    const pa = cellPoint(a, rotated);
    const pb = cellPoint(b, rotated);
    return pa.y - pb.y || pa.x - pb.x;
  });
}

/** The element after (or before) `current` in a list, wrapping. Without `current`, the first (or last). */
export function cycle<T>(list: readonly T[], current: T | null, step: 1 | -1): T | null {
  if (list.length === 0) return null;
  const i = current === null ? -1 : list.indexOf(current);
  if (i < 0) return step === 1 ? list[0] ?? null : list[list.length - 1] ?? null;
  return list[(i + step + list.length) % list.length] ?? null;
}

// ---- notation and paths ----

export interface MoveDescription {
  from: number;
  to: number;
  side: Side;
  /** Cells the piece visits, origin first; a fallback [from, to] when no chain is found. */
  path: number[];
  /** True for a jump or a chain of jumps. */
  jump: boolean;
}

export const isStep = (from: number, to: number): boolean => {
  for (let d = 0; d < DIRECTIONS; d++) if (neighbor(from, d) === to) return true;
  return false;
};

/** Walks the history and describes every move. */
export function describeHistory(history: readonly number[], initial: Int8Array): MoveDescription[] {
  const owner = initial.slice();
  const out: MoveDescription[] = [];
  for (const m of history) {
    const from = moveFrom(m);
    const to = moveTo(m);
    const side = (owner[from] ?? 0) as Side;
    const step = isStep(from, to);
    const path = step ? [from, to] : (jumpPath(from, to, Array.from(owner, (o) => o >= 0)) ?? [from, to]);
    out.push({ from, to, side, path, jump: !step });
    owner[to] = owner[from] ?? -1;
    owner[from] = -1;
  }
  return out;
}

/** "E6›F6" for a step, "C3»E7" for a jump or a chain of jumps. */
export const notation = (d: Pick<MoveDescription, 'from' | 'to' | 'jump'>): string =>
  `${cellName(d.from)}${d.jump ? '»' : '›'}${cellName(d.to)}`;

// ---- score at the move limit ----

/** Axial hex distance between two cells. */
export function hexDistance(a: number, b: number): number {
  const ca = CELLS[a];
  const cb = CELLS[b];
  if (!ca || !cb) throw new RangeError('no such cell');
  const dq = ca.q - cb.q;
  const dr = ca.r - cb.r;
  return (Math.abs(dq) + Math.abs(dr) + Math.abs(dq + dr)) / 2;
}

/**
 * An approximate remaining distance of a side: the sum, over its pieces, of the hex distance to the tip of its goal
 * corner. The engine's own table (single-agent distance) is not exposed to the browser, so this figure only orders
 * the two sides; it is not the figure the engine scores with.
 */
export function approximateDistance(owner: Int8Array, side: number, goals: readonly (readonly number[])[] = GOAL_CELLS): number {
  const goal = goals[side]!;
  const tip = goal.reduce((best, c) => (Math.abs(CELLS[c]!.r) > Math.abs(CELLS[best]!.r) ? c : best), goal[0]!);
  let sum = 0;
  for (let c = 0; c < CELL_COUNT; c++) if (owner[c] === side) sum += hexDistance(c, tip);
  return sum;
}
