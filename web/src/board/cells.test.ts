import { describe, expect, it } from 'vitest';
import { BOTTOM_CORNER, CELLS, CELL_COUNT, DIRECTIONS, TOP_CORNER, cellAt, cellCentre, cellId, neighbor } from './cells';

describe('board geometry', () => {
  it('has 121 cells with the ids 0 to 120 in order', () => {
    expect(CELLS).toHaveLength(121);
    expect(CELL_COUNT).toBe(121);
    expect(CELLS.map((c) => c.id)).toEqual(Array.from({ length: 121 }, (_, i) => i));
  });

  it('contains exactly the cells of the two-triangle rule of docs/rules.md', () => {
    // Independent of cells.ts: recompute the board from max(q,r,s) <= 4 or min(q,r,s) >= -4.
    const expected: string[] = [];
    for (let r = -8; r <= 8; r++) {
      for (let q = -8; q <= 8; q++) {
        const s = -q - r;
        if (Math.max(q, r, s) <= 4 || Math.min(q, r, s) >= -4) expected.push(`${q},${r}`);
      }
    }
    expect(CELLS.map((c) => `${c.q},${c.r}`)).toEqual(expected);
  });

  it('puts 10 cells into each of the two corners the two-player game uses', () => {
    expect(TOP_CORNER).toHaveLength(10);
    expect(BOTTOM_CORNER).toHaveLength(10);
    expect(TOP_CORNER.every((id) => cellAt(id).r < -4)).toBe(true);
    expect(BOTTOM_CORNER.every((id) => cellAt(id).r > 4)).toBe(true);
  });

  it('maps coordinates back to ids and rejects cells off the board', () => {
    for (const c of CELLS) expect(cellId(c.q, c.r)).toBe(c.id);
    expect(cellId(0, -8)).toBe(-1); // r = -8 has only q = 4
    expect(cellId(9, 0)).toBe(-1);
    expect(cellId(-9, 0)).toBe(-1);
  });

  it('has symmetric neighbours: the opposite direction leads back', () => {
    for (const c of CELLS) {
      for (let d = 0; d < DIRECTIONS; d++) {
        const n = neighbor(c.id, d);
        if (n >= 0) expect(neighbor(n, d ^ 1)).toBe(c.id);
      }
    }
  });

  it('gives the centre cell six neighbours and a tip cell fewer', () => {
    const centre = cellId(0, 0);
    expect(Array.from({ length: 6 }, (_, d) => neighbor(centre, d)).every((n) => n >= 0)).toBe(true);
    const tip = cellId(4, -8);
    expect(Array.from({ length: 6 }, (_, d) => neighbor(tip, d)).filter((n) => n >= 0)).toHaveLength(2);
  });

  it('places cell centres in the pointy-top layout of the design (unit 20)', () => {
    const { x, y } = cellCentre(cellId(2, -4), 20);
    expect(x).toBeCloseTo(Math.sqrt(3) * 20 * (2 - 2), 9);
    expect(y).toBeCloseTo(-120, 9);
    expect(cellCentre(cellId(1, 0), 20).x).toBeCloseTo(34.641016, 5);
  });

  it('throws for an unknown cell id', () => {
    expect(() => cellAt(121)).toThrow(RangeError);
  });
});
