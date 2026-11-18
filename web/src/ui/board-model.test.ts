import { describe, expect, it } from 'vitest';
import { CELLS, TOP_CORNER, BOTTOM_CORNER } from '../board/cells';
import { makeMove } from '../engines/engine';
import { initialOwners } from '../game/board-state';
import { tinyLegalMoves } from '../engines/testing/tiny-moves';
import {
  approximateDistance, cellName, isStep, cellPoint, cycle, describeHistory, movableCells, navigate, notation, reading,
  resolveTap, targetsOf, VIEW_BOX,
} from './board-model';

const legalForBottom = tinyLegalMoves(initialOwners(), 1);

describe('cell names and positions', () => {
  it('names the 121 cells uniquely, tips A1 and Q1, row E has 13 cells', () => {
    const names = CELLS.map((c) => cellName(c.id));
    expect(new Set(names).size).toBe(121);
    expect(cellName(CELLS.find((c) => c.r === -8)!.id)).toBe('A1');
    expect(cellName(CELLS.find((c) => c.r === 8)!.id)).toBe('Q1');
    expect(names.filter((n) => n.startsWith('E'))).toHaveLength(13);
  });

  it('keeps every cell inside the view box, rotated or not, and rotation negates', () => {
    for (const c of CELLS) {
      for (const rotated of [false, true]) {
        const p = cellPoint(c.id, rotated);
        expect(p.x).toBeGreaterThan(VIEW_BOX.x + 11);
        expect(p.x).toBeLessThan(VIEW_BOX.x + VIEW_BOX.width - 11);
        expect(p.y).toBeGreaterThan(VIEW_BOX.y + 11);
        expect(p.y).toBeLessThan(VIEW_BOX.y + VIEW_BOX.height - 11);
      }
      const a = cellPoint(c.id, false);
      const b = cellPoint(c.id, true);
      expect(b.x).toBeCloseTo(-a.x);
      expect(b.y).toBeCloseTo(-a.y);
    }
  });
});

describe('resolveTap', () => {
  const piece = movableCells(legalForBottom)[0]!;
  const target = targetsOf(legalForBottom, piece)[0]!;

  it('selects a movable piece, moves to a target, ignores an empty cell without a selection', () => {
    expect(resolveTap({ selected: null, legal: legalForBottom, rotated: false, hitCell: piece })).toEqual({ type: 'select', cell: piece });
    expect(resolveTap({ selected: piece, legal: legalForBottom, rotated: false, hitCell: target })).toEqual({
      type: 'move', move: makeMove(piece, target),
    });
    expect(resolveTap({ selected: null, legal: legalForBottom, rotated: false, hitCell: target })).toEqual({ type: 'none' });
  });

  it('clears on the selected piece, marks a cell that is no target as illegal', () => {
    expect(resolveTap({ selected: piece, legal: legalForBottom, rotated: false, hitCell: piece })).toEqual({ type: 'clear' });
    const far = TOP_CORNER[0]!;
    expect(resolveTap({ selected: piece, legal: legalForBottom, rotated: false, hitCell: far })).toEqual({ type: 'illegal', cell: far });
  });

  it('resolves a tap by position to the nearest candidate within 16 px, and outside every cell it clears', () => {
    const p = cellPoint(piece, false);
    expect(resolveTap({ selected: null, legal: legalForBottom, rotated: false, hitCell: null, point: { x: p.x + 6, y: p.y - 5 } }))
      .toEqual({ type: 'select', cell: piece });
    expect(resolveTap({ selected: piece, legal: legalForBottom, rotated: false, hitCell: null, point: { x: 9999, y: 9999 } }))
      .toEqual({ type: 'clear' });
  });

  it('uses the rotated positions when the board is rotated', () => {
    const p = cellPoint(piece, true);
    expect(resolveTap({ selected: null, legal: legalForBottom, rotated: true, hitCell: null, point: p })).toEqual({ type: 'select', cell: piece });
    const wrong = cellPoint(piece, false);
    expect(resolveTap({ selected: null, legal: legalForBottom, rotated: true, hitCell: null, point: wrong }).type).not.toBe('select');
  });
});

describe('keyboard navigation', () => {
  it('Right then Left returns; Up then Down returns to the same cell (remembered x)', () => {
    const start = CELLS.find((c) => c.q === 0 && c.r === 0)!.id;
    const right = navigate(start, 'ArrowRight', false, null).cell;
    expect(navigate(right, 'ArrowLeft', false, null).cell).toBe(start);
    const up = navigate(start, 'ArrowUp', false, null);
    expect(CELLS[up.cell]!.r).toBe(-1);
    expect(navigate(up.cell, 'ArrowDown', false, up.x).cell).toBe(start);
  });

  it('stops at the edges and Home and End reach the ends of the row', () => {
    const tip = CELLS.find((c) => c.r === -8)!.id;
    expect(navigate(tip, 'ArrowUp', false, null).cell).toBe(tip);
    const row = CELLS.filter((c) => c.r === 0);
    const left = navigate(row[3]!.id, 'Home', false, null).cell;
    const rightEnd = navigate(row[3]!.id, 'End', false, null).cell;
    expect(CELLS[left]!.q).toBe(Math.min(...row.map((c) => c.q)));
    expect(CELLS[rightEnd]!.q).toBe(Math.max(...row.map((c) => c.q)));
  });

  it('on a rotated board Up goes up on the screen, which is larger r', () => {
    const start = CELLS.find((c) => c.q === 0 && c.r === 0)!.id;
    expect(CELLS[navigate(start, 'ArrowUp', true, null).cell]!.r).toBe(1);
  });

  it('cycles through a list and wraps', () => {
    expect(cycle([1, 2, 3], null, 1)).toBe(1);
    expect(cycle([1, 2, 3], null, -1)).toBe(3);
    expect(cycle([1, 2, 3], 3, 1)).toBe(1);
    expect(cycle([], null, 1)).toBeNull();
    expect(reading(BOTTOM_CORNER, false)[0]).toBe(BOTTOM_CORNER.find((c) => CELLS[c]!.r === 5)! );
  });
});

describe('notation', () => {
  it('writes a step with the single arrow and a jump with the double arrow, and finds the jump chain', () => {
    const owner = initialOwners();
    const stepMove = legalForBottom.find((m) => isStep(m & 0xff, m >>> 8))!;
    const jumpMove = legalForBottom.find((m) => !isStep(m & 0xff, m >>> 8))!;
    const [step, jump] = describeHistory([stepMove], owner).concat(describeHistory([jumpMove], owner));
    expect(step!.jump).toBe(false);
    expect(notation(step!)).toMatch(/^[A-Q]\d+›[A-Q]\d+$/);
    expect(jump!.jump).toBe(true);
    expect(notation(jump!)).toMatch(/^[A-Q]\d+»[A-Q]\d+$/);
    expect(jump!.path.length).toBeGreaterThanOrEqual(2);
    expect(jump!.path[0]).toBe(jumpMove & 0xff);
    expect(jump!.path.at(-1)).toBe(jumpMove >>> 8);
    expect(jump!.side).toBe(1);
  });

  it("follows a two-jump chain over the whole path", () => {
    const owner = new Int8Array(initialOwners().length).fill(-1);
    for (const c of [60, 61, 63]) owner[c] = 0;
    const [d] = describeHistory([makeMove(60, 64)], owner);
    expect(d!.path).toEqual([60, 62, 64]);
  });
});

describe('approximateDistance', () => {
  it('is larger at the start than for the same side one step closer, and equal for both sides at the start', () => {
    const owner = initialOwners();
    expect(approximateDistance(owner, 0)).toBe(approximateDistance(owner, 1));
    const m = legalForBottom.filter((x) => CELLS[x >>> 8]!.r < CELLS[x & 0xff]!.r)[0]!;
    const next = owner.slice();
    next[m >>> 8] = 1;
    next[m & 0xff] = -1;
    expect(approximateDistance(next, 1)).toBeLessThan(approximateDistance(owner, 1));
  });
});
