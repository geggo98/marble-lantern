// Board geometry for the UI. The cell list comes from cells.generated.json (scripts/web/export-board.zsh,
// from sternhalma.engine.Geometry); the board renders before any engine has loaded.
import generated from './cells.generated.json';

export interface Cell {
  id: number;
  q: number;
  r: number;
}

export const CELLS: readonly Cell[] = generated;
export const CELL_COUNT = 121;

/** Direction vectors as in Geometry.java: directions d and d ^ 1 are opposite. */
const DQ = [1, -1, 0, 0, 1, -1] as const;
const DR = [0, 0, 1, -1, -1, 1] as const;
export const DIRECTIONS = 6;

const OFFSET = 8;
const SIDE = 2 * OFFSET + 1;
const idTable = new Int16Array(SIDE * SIDE).fill(-1);
for (const c of CELLS) idTable[(c.r + OFFSET) * SIDE + (c.q + OFFSET)] = c.id;

/** Cell id for axial coordinates, or -1 off the board. */
export function cellId(q: number, r: number): number {
  if (q < -OFFSET || q > OFFSET || r < -OFFSET || r > OFFSET) return -1;
  return idTable[(r + OFFSET) * SIDE + (q + OFFSET)] ?? -1;
}

export function cellAt(id: number): Cell {
  const c = CELLS[id];
  if (!c) throw new RangeError(`no such cell: ${id}`);
  return c;
}

/** Neighbour of a cell in direction 0 to 5, or -1 off the board. */
export function neighbor(id: number, direction: number): number {
  const c = cellAt(id);
  return cellId(c.q + (DQ[direction] ?? 0), c.r + (DR[direction] ?? 0));
}

export type CornerName = 'top' | 'bottom' | 'other';

/** The two corners the two-player game uses: top is r < -4, bottom is r > 4. Everything else is "other". */
export function cornerOf(id: number): CornerName {
  const c = cellAt(id);
  if (c.r < -4) return 'top';
  if (c.r > 4) return 'bottom';
  return 'other';
}

export const TOP_CORNER: readonly number[] = CELLS.filter((c) => c.r < -4).map((c) => c.id);
export const BOTTOM_CORNER: readonly number[] = CELLS.filter((c) => c.r > 4).map((c) => c.id);

/** Player 0 starts at the top, player 1 at the bottom; each goal is the other player's start corner. */
export const START_CELLS: readonly [readonly number[], readonly number[]] = [TOP_CORNER, BOTTOM_CORNER];
export const GOAL_CELLS: readonly [readonly number[], readonly number[]] = [BOTTOM_CORNER, TOP_CORNER];

/** Three players, clockwise (docs/rules.md, "Screen orientation and clockwise order"): player 0 starts at r < -4, player 1 at s < -4, player 2 at q < -4. Each goal is the opposite corner. */
const cornerWhere = (test: (q: number, r: number, s: number) => boolean): readonly number[] =>
  CELLS.filter((c) => test(c.q, c.r, -c.q - c.r)).map((c) => c.id);
export const START_CELLS_3: readonly [readonly number[], readonly number[], readonly number[]] = [
  cornerWhere((_q, r) => r < -4),
  cornerWhere((_q, _r, s) => s < -4),
  cornerWhere((q) => q < -4),
];
export const GOAL_CELLS_3: readonly [readonly number[], readonly number[], readonly number[]] = [
  cornerWhere((_q, r) => r > 4),
  cornerWhere((_q, _r, s) => s > 4),
  cornerWhere((q) => q > 4),
];

/** Screen position of a cell centre for a unit length u (design-notes.md, section 6): pointy-top layout. */
export function cellCentre(id: number, unit = 20): { x: number; y: number } {
  const c = cellAt(id);
  return { x: Math.sqrt(3) * unit * (c.q + c.r / 2), y: 1.5 * unit * c.r };
}
