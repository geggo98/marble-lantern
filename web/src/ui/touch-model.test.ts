// Pure logic of touch input on the board: screen to board mapping, snapping a drop to a target, the swipe that closes
// the menu, the size of the invisible hit circles. Expected values are worked out by hand in the comments.
import { describe, expect, it } from 'vitest';
import { CELLS } from '../board/cells';
import { cellPoint, VIEW_BOX } from './board-model';
import {
  clientToBoard, DRAG_THRESHOLD_PX, exceedsDragThreshold, hitRadiusUnits, markBoost, REACH_PX, shouldDismissSwipe, snapTarget,
} from './touch-model';

const centre = CELLS.find((c) => c.q === 0 && c.r === 0)!.id;
// the six neighbours of the centre cell, 34.64 units away (sqrt(3) * 20)
const neighbours = CELLS.filter(
  (c) => Math.abs(cellPoint(c.id, false).x) < 40 && Math.abs(cellPoint(c.id, false).y) < 40 && c.id !== centre,
).map((c) => c.id);

describe('clientToBoard', () => {
  it('maps a box with the aspect ratio of the board one to one', () => {
    // 468 x 532 units shown at 234 x 266 px: 0.5 px per unit, the box origin is the view box origin
    const r = clientToBoard({ left: 10, top: 20, width: 234, height: 266 }, { x: 10 + 117, y: 20 + 133 });
    expect(r.pxPerUnit).toBeCloseTo(0.5);
    expect(r.point.x).toBeCloseTo(0); // 117 px from the left = 234 units = the board centre
    expect(r.point.y).toBeCloseTo(0);
  });

  it('accounts for the bars of a box that is wider than the board (meet)', () => {
    // box 600 x 266: the board is 234 px wide and centred, so it starts 183 px from the left
    const r = clientToBoard({ left: 0, top: 0, width: 600, height: 266 }, { x: 183, y: 0 });
    expect(r.pxPerUnit).toBeCloseTo(0.5);
    expect(r.point.x).toBeCloseTo(VIEW_BOX.x);
    expect(r.point.y).toBeCloseTo(VIEW_BOX.y);
  });

  it('accounts for the bars of a box that is taller than the board', () => {
    // box 234 x 600: the board is 266 px tall and centred, so it starts 167 px from the top
    const r = clientToBoard({ left: 0, top: 0, width: 234, height: 600 }, { x: 0, y: 167 });
    expect(r.pxPerUnit).toBeCloseTo(0.5);
    expect(r.point.y).toBeCloseTo(VIEW_BOX.y);
  });
});

describe('snapTarget', () => {
  const at = cellPoint(neighbours[0]!, false);
  it('picks the target whose centre is nearest, within the tolerance', () => {
    expect(snapTarget({ x: at.x + 5, y: at.y - 5 }, [neighbours[0]!, neighbours[1]!], false, 20)).toBe(neighbours[0]);
  });
  it('picks the nearer of two targets in reach', () => {
    const b = cellPoint(neighbours[1]!, false);
    const p = { x: (at.x * 3 + b.x) / 4, y: (at.y * 3 + b.y) / 4 };
    expect(snapTarget(p, [neighbours[1]!, neighbours[0]!], false, 40)).toBe(neighbours[0]);
  });
  it('returns null when no target is within the tolerance', () => {
    expect(snapTarget({ x: at.x + 21, y: at.y }, [neighbours[0]!], false, 20)).toBeNull();
  });
  it('accepts a point exactly at the tolerance', () => {
    expect(snapTarget({ x: at.x + 20, y: at.y }, [neighbours[0]!], false, 20)).toBe(neighbours[0]);
  });
  it('returns null without targets', () => {
    expect(snapTarget(at, [], false, 20)).toBeNull();
  });
  it('follows a rotated board', () => {
    const rotated = cellPoint(neighbours[0]!, true);
    expect(snapTarget(rotated, [neighbours[0]!], true, 5)).toBe(neighbours[0]);
    expect(snapTarget(at, [neighbours[0]!], true, 5)).toBeNull();
  });
});

describe('exceedsDragThreshold', () => {
  it('is false up to the threshold and true beyond it', () => {
    expect(exceedsDragThreshold(0, 0)).toBe(false);
    expect(exceedsDragThreshold(DRAG_THRESHOLD_PX, 0)).toBe(false);
    expect(exceedsDragThreshold(3, 4)).toBe(false); // 5 px
    expect(exceedsDragThreshold(0, DRAG_THRESHOLD_PX + 0.5)).toBe(true);
    expect(exceedsDragThreshold(-5, -5)).toBe(true); // 7.07 px
  });
});

describe('shouldDismissSwipe', () => {
  it('closes after a long pull', () => {
    expect(shouldDismissSwipe(96, 800)).toBe(true);
    expect(shouldDismissSwipe(95, 800)).toBe(false);
  });
  it('closes after a short, fast flick', () => {
    expect(shouldDismissSwipe(40, 50)).toBe(true); // 0.8 px/ms
    expect(shouldDismissSwipe(40, 200)).toBe(false); // 0.2 px/ms
  });
  it('ignores upward and tiny moves; a zero duration counts as fast', () => {
    expect(shouldDismissSwipe(-200, 50)).toBe(false);
    expect(shouldDismissSwipe(10, 1)).toBe(false);
    expect(shouldDismissSwipe(40, 0)).toBe(true);
  });
});

describe('hit circles', () => {
  it('are 44 px wide on a small board and keep the drawn size on a large one', () => {
    expect(hitRadiusUnits(0.684)).toBeCloseTo(REACH_PX / 0.684); // 22 px reach on a 320 px board
    expect(hitRadiusUnits(2)).toBe(15); // drawn size wins: 15 units = 30 px
  });
  it('mark boost grows with a smaller board and stops at 1.5', () => {
    expect(markBoost(1)).toBe(1);
    expect(markBoost(0.684)).toBeCloseTo(0.9 / 0.684);
    expect(markBoost(0.3)).toBe(1.5);
  });
});
