import { levelPosition } from './calibration';

export type LevelDescription =
  | { kind: 'at'; level: number }
  | { kind: 'between'; low: number; high: number }
  | { kind: 'below' }
  | { kind: 'above' };

/** Within this part of a step between two levels (on the log scale) a budget counts as "at" the level. */
const NEAR = 0.15;

/** A node budget in words of the preset levels; level numbers count from 1 like the labels of the engine card. */
export function describeLevel(nodes: number, levels: readonly number[]): LevelDescription {
  const p = levelPosition(nodes, levels);
  const nearest = Math.round(p);
  if (Math.abs(p - nearest) <= NEAR && nearest >= 0 && nearest < levels.length) return { kind: 'at', level: nearest + 1 };
  if (p < 0) return { kind: 'below' };
  if (p > levels.length - 1) return { kind: 'above' };
  return { kind: 'between', low: Math.floor(p) + 1, high: Math.floor(p) + 2 };
}
