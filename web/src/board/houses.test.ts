// The house sets of the board sizes (board/cells.ts). The expected sets are rebuilt here from cube coordinates and the rule of
// docs/rules.md, not from cells.ts: a house is a corner (the cells beyond distance 4 on one axis) plus, with 5 rows, the hexagon
// edge row in front of it.
import { describe, expect, it } from 'vitest';
import { BOTTOM_CORNER, CELLS, GOAL_CELLS, GOAL_CELLS_3, START_CELLS, START_CELLS_3, TOP_CORNER, goalHouses, startHouses } from './cells';
import type { BoardRows } from './cells';

type Axis = (q: number, r: number) => number;
const AXES: Record<string, Axis> = {
  rNeg: (_q, r) => r, rPos: (_q, r) => -r,
  sNeg: (q, r) => -q - r, sPos: (q, r) => q + r,
  qNeg: (q) => q, qPos: (q) => -q,
};

/** All cells with axis value < -4, and with 5 rows also the cells of the edge row (axis = -4) that lie in the hexagon. */
function expected(axis: keyof typeof AXES, rows: BoardRows): number[] {
  const out: number[] = [];
  for (let r = -8; r <= 8; r++) {
    for (let q = -8; q <= 8; q++) {
      const s = -q - r;
      const onBoard = Math.max(q, r, s) <= 4 || Math.min(q, r, s) >= -4;
      if (!onBoard) continue;
      const v = AXES[axis]!(q, r);
      const inHexagon = Math.max(Math.abs(q), Math.abs(r), Math.abs(s)) <= 4;
      if (v < -4 || (rows === 5 && v === -4 && inHexagon)) out.push(CELLS.find((c) => c.q === q && c.r === r)!.id);
    }
  }
  return out.sort((a, b) => a - b);
}
const sorted = (cells: readonly number[]): number[] => [...cells].sort((a, b) => a - b);

describe('house sets, 4 rows', () => {
  it('are the exports the app has always had', () => {
    expect(startHouses(2, 4)).toEqual(START_CELLS);
    expect(goalHouses(2, 4)).toEqual(GOAL_CELLS);
    expect(startHouses(3, 4)).toEqual(START_CELLS_3);
    expect(goalHouses(3, 4)).toEqual(GOAL_CELLS_3);
    expect(START_CELLS[0]).toEqual(TOP_CORNER);
    expect(GOAL_CELLS[0]).toEqual(BOTTOM_CORNER);
  });

  it('have 10 cells each', () => {
    for (const players of [2, 3] as const) {
      for (const h of [...startHouses(players, 4), ...goalHouses(players, 4)]) expect(h).toHaveLength(10);
    }
  });
});

describe('house sets, 5 rows', () => {
  it('have 15 cells each, 5 per side of the triangle', () => {
    for (const players of [2, 3] as const) {
      for (const h of [...startHouses(players, 5), ...goalHouses(players, 5)]) expect(h).toHaveLength(15);
    }
  });

  it('are the corner plus the hexagon edge row, in the order of the seats', () => {
    expect(sorted(startHouses(2, 5)[0]!)).toEqual(expected('rNeg', 5));
    expect(sorted(startHouses(2, 5)[1]!)).toEqual(expected('rPos', 5));
    expect(sorted(goalHouses(2, 5)[0]!)).toEqual(expected('rPos', 5));
    expect(sorted(goalHouses(2, 5)[1]!)).toEqual(expected('rNeg', 5));
    expect(startHouses(3, 5).map(sorted)).toEqual([expected('rNeg', 5), expected('sNeg', 5), expected('qNeg', 5)]);
    expect(goalHouses(3, 5).map(sorted)).toEqual([expected('rPos', 5), expected('sPos', 5), expected('qPos', 5)]);
  });

  it('contain the 4-row house, and the 5 extra cells are the edge row (r = -4, q 0 to 4 for the top house)', () => {
    const top = new Set(startHouses(2, 5)[0]);
    for (const c of startHouses(2, 4)[0]!) expect(top.has(c)).toBe(true);
    const extra = [...top].filter((c) => !startHouses(2, 4)[0]!.includes(c)).map((c) => [CELLS[c]!.q, CELLS[c]!.r]);
    expect(extra.sort((a, b) => a[0]! - b[0]!)).toEqual([[0, -4], [1, -4], [2, -4], [3, -4], [4, -4]]);
  });

  it('leave the cells of the neighbouring corner triangles out of the edge row', () => {
    // row r = -4 also holds q = 5..8 (corner of the s axis); they are in no top house
    const top = new Set(startHouses(2, 5)[0]);
    for (const c of CELLS) if (c.r === -4 && c.q > 4) expect(top.has(c.id)).toBe(false);
  });

  it('keep the two start houses of two players apart from each other', () => {
    const [a, b] = startHouses(2, 5);
    expect(a!.filter((c) => b!.includes(c))).toEqual([]);
  });

  it('overlap in three players exactly in the 6 vertex cells: each start house shares 2 with the goal houses of its neighbours', () => {
    const starts = startHouses(3, 5);
    const goals = goalHouses(3, 5);
    // start houses among themselves and goal houses among themselves do not overlap
    for (const set of [starts, goals]) {
      for (let i = 0; i < 3; i++) for (let j = i + 1; j < 3; j++) expect(set[i]!.filter((c) => set[j]!.includes(c))).toEqual([]);
    }
    const shared = new Set<number>();
    starts.forEach((s, i) => {
      const hits = goals.flatMap((g, j) => s.filter((c) => g.includes(c)).map((c) => ({ c, j })));
      expect(hits).toHaveLength(2); // two vertex cells per start house
      expect(hits.every((h) => h.j !== i)).toBe(true); // never the seat's own goal
      hits.forEach((h) => shared.add(h.c));
    });
    expect(shared.size).toBe(6);
    // the 6 vertex cells of the hexagon: the cells with max(|q|,|r|,|s|) = 4 and one coordinate 0
    const vertices = CELLS.filter((c) => Math.max(Math.abs(c.q), Math.abs(c.r), Math.abs(c.q + c.r)) === 4 && (c.q === 0 || c.r === 0 || c.q + c.r === 0)).map((c) => c.id);
    expect(sorted([...shared])).toEqual(sorted(vertices));
  });

  it('are point mirrors: the goal of every seat is the start house turned by 180 degrees (two players)', () => {
    const mirror = (c: number): number => CELLS.find((x) => x.q === -CELLS[c]!.q && x.r === -CELLS[c]!.r)!.id;
    const [s0, s1] = startHouses(2, 5);
    expect(sorted(goalHouses(2, 5)[0]!)).toEqual(sorted(s0!.map(mirror)));
    expect(sorted(goalHouses(2, 5)[0]!)).toEqual(sorted(s1!));
  });
});
