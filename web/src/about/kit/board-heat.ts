// Levels and arrow geometry of the figure boards (design notes, section 3 "diverging ramp" and section 6, "MiniBoard and HeatBoard").
// Pure; the cell positions come from ui/board-model.ts, the board geometry of the game itself.
import { cellPoint } from '../../ui/board-model';
import type { Point } from '../../ui/board-model';

export type HeatLevel = 1 | 2 | 3 | 4 | 5;
export type SignedLevel = 'neg-2' | 'neg-1' | 'zero' | 'pos-1' | 'pos-2';

/** Disc radius per level: five discs in rising size, so that the legend works without colour. */
export const HEAT_RADIUS: Readonly<Record<HeatLevel, number>> = { 1: 4.5, 2: 6.5, 3: 8.5, 4: 10.5, 5: 12.5 };

/**
 * The sequential level of `value` on [min, max] in five equal bins. The bounds are fixed per position over all layers by the caller, so
 * that layers can be compared. A value below `min` is level 1, above `max` level 5; NaN or infinity gives null (no disc).
 */
export function heatLevel(value: number, min: number, max: number): HeatLevel | null {
  if (!Number.isFinite(value) || !(max > min)) return null;
  const t = (value - min) / (max - min);
  return Math.min(5, Math.max(1, 1 + Math.floor(t * 5))) as HeatLevel;
}

/** The diverging level of `value` against the symmetric bound `bound` (> 0): plum is negative, teal positive, the middle fifth is zero. */
export function signedLevel(value: number, bound: number): SignedLevel | null {
  if (!Number.isFinite(value) || !(bound > 0)) return null;
  const t = value / bound;
  if (t < -0.6) return 'neg-2';
  if (t < -0.2) return 'neg-1';
  if (t <= 0.2) return 'zero';
  if (t <= 0.6) return 'pos-1';
  return 'pos-2';
}

export interface Arrow {
  from: number;
  to: number;
  /** A reply is drawn with a dashed line (brief V5). */
  dashed?: boolean;
  /** Line width in board units, 2 to 6 (default 5). */
  width?: number;
}

/** The arrow ends 26 units before the target centre, so that the head stands beside the marble, not on it. */
export const ARROW_GAP = 26;

export function arrowGeometry(arrow: Arrow, rotated = false): { from: Point; end: Point; head: string } {
  const a = cellPoint(arrow.from, rotated);
  const b = cellPoint(arrow.to, rotated);
  const dx = b.x - a.x;
  const dy = b.y - a.y;
  const len = Math.hypot(dx, dy) || 1;
  const ux = dx / len;
  const uy = dy / len;
  const end = { x: b.x - ux * ARROW_GAP, y: b.y - uy * ARROW_GAP };
  // Triangle of 12 units length and 12 units width, tip at `end`.
  const base = { x: end.x - ux * 12, y: end.y - uy * 12 };
  const head = `M${end.x.toFixed(2)} ${end.y.toFixed(2)}L${(base.x - uy * 6).toFixed(2)} ${(base.y + ux * 6).toFixed(2)}L${(base.x + uy * 6).toFixed(2)} ${(base.y - ux * 6).toFixed(2)}Z`;
  return { from: a, end, head };
}
