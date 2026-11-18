// Pure logic of touch input on the board: where a finger is in board units, which target a drop snaps to, when a drag
// begins, when a swipe closes the menu, how large the invisible hit circles are.
import { cellPoint, VIEW_BOX } from './board-model';
import type { Point } from './board-model';

/** Reach of a cell for a finger: a circle of 44 CSS px (the smallest touch target of WCAG 2.5.5). */
export const REACH_PX = 22;
/** A pointer must move this far before a press becomes a drag; a tap with a trembling finger stays a tap. */
export const DRAG_THRESHOLD_PX = 6;

export interface Box {
  left: number;
  top: number;
  width: number;
  height: number;
}

/**
 * The position of a pointer in board units, and the scale in CSS px per unit. The SVG is drawn with
 * `preserveAspectRatio="xMidYMid meet"`: in a box with another aspect ratio the board is centred and bars remain.
 */
export function clientToBoard(box: Box, client: { x: number; y: number }): { point: Point; pxPerUnit: number } {
  const pxPerUnit = Math.min(box.width / VIEW_BOX.width, box.height / VIEW_BOX.height);
  const originX = box.left + (box.width - VIEW_BOX.width * pxPerUnit) / 2;
  const originY = box.top + (box.height - VIEW_BOX.height * pxPerUnit) / 2;
  return {
    point: { x: VIEW_BOX.x + (client.x - originX) / pxPerUnit, y: VIEW_BOX.y + (client.y - originY) / pxPerUnit },
    pxPerUnit,
  };
}

/** The candidate whose centre is nearest to the point and not farther than `tolerance` board units; ties go to the earlier one. */
export function nearestCell(point: Point, cells: readonly number[], rotated: boolean, tolerance: number): number | null {
  let best: number | null = null;
  let bestDistance = tolerance;
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

/** Where a dragged piece lands: the nearest legal target in reach, or null (the drop is cancelled). */
export const snapTarget = (point: Point, targets: readonly number[], rotated: boolean, tolerance: number): number | null =>
  nearestCell(point, targets, rotated, tolerance);

export const exceedsDragThreshold = (dx: number, dy: number): boolean => Math.hypot(dx, dy) > DRAG_THRESHOLD_PX;

/** A swipe down closes the menu: a pull of 96 px or more, or a flick of at least 24 px at 0.5 px/ms. */
export function shouldDismissSwipe(dy: number, ms: number): boolean {
  if (dy >= 96) return true;
  if (dy < 24) return false;
  return ms <= 0 || dy / ms >= 0.5;
}

/** Radius of the transparent hit circle of a cell in board units: at least 22 CSS px, never below the drawn 15 units. */
export const hitRadiusUnits = (pxPerUnit: number): number => Math.max(15, REACH_PX / pxPerUnit);

/** Marks (rings, dots, badges) grow on a small board so that they stay readable; 1 from 0.9 px per unit up. */
export const markBoost = (pxPerUnit: number): number => Math.min(1.5, Math.max(1, 0.9 / pxPerUnit));
