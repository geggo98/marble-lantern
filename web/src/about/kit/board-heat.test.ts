// Levels and arrows of the figure boards.
import { describe, expect, it } from 'vitest';
import { cellPoint } from '../../ui/board-model';
import { ARROW_GAP, arrowGeometry, heatLevel, signedLevel } from './board-heat';

describe('heatLevel', () => {
  it('cuts [0, 10] into five equal bins', () => {
    expect([0, 1.99, 2, 3.99, 4, 5.99, 6, 7.99, 8, 9.99, 10].map((v) => heatLevel(v, 0, 10))).toEqual([1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 5]);
  });

  it('clamps outside the bounds and gives no disc for a value that is not a number', () => {
    expect([heatLevel(-5, 0, 10), heatLevel(99, 0, 10)]).toEqual([1, 5]);
    expect([heatLevel(Number.NaN, 0, 10), heatLevel(Infinity, 0, 10), heatLevel(3, 5, 5)]).toEqual([null, null, null]);
  });
});

describe('signedLevel', () => {
  it('splits [-1, 1] at -0.6, -0.2, 0.2 and 0.6', () => {
    expect([-1, -0.61, -0.6, -0.21, -0.2, 0, 0.2, 0.21, 0.6, 0.61, 1].map((v) => signedLevel(v, 1))).toEqual([
      'neg-2', 'neg-2', 'neg-1', 'neg-1', 'zero', 'zero', 'zero', 'pos-1', 'pos-1', 'pos-2', 'pos-2',
    ]);
  });

  it('scales by the bound and refuses a bound that is not positive', () => {
    expect(signedLevel(5, 10)).toBe('pos-1');
    expect(signedLevel(5, 0)).toBeNull();
  });
});

describe('arrowGeometry', () => {
  it('starts at the origin cell and ends 26 units before the target centre', () => {
    const g = arrowGeometry({ from: 0, to: 1 });
    const a = cellPoint(0, false);
    const b = cellPoint(1, false);
    expect(g.from).toEqual(a);
    expect(Math.hypot(b.x - g.end.x, b.y - g.end.y)).toBeCloseTo(ARROW_GAP, 9);
    expect(Math.hypot(g.end.x - a.x, g.end.y - a.y)).toBeCloseTo(Math.hypot(b.x - a.x, b.y - a.y) - ARROW_GAP, 9);
  });
});
