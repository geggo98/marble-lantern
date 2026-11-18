// The strength of the engine as the app stores it: ONE number, the `level`.
//   0 to 4        a preset level: an index into NODE_LEVELS (the five levels of the engine card);
//   10 to 5e6     a custom level: the node budget itself ("custom, N nodes"), set by "Match engine to my strength".
// The game URL carries the same number in `&l=` (game-url.ts), so links with a preset level (`l=2`) keep their meaning and
// a custom level is `l=12345`. The two ranges cannot meet: a node budget below 10 is not offered.
import { NODE_LEVELS } from '../ui/Panels';

export const CUSTOM_MIN_NODES = 10;
export const CUSTOM_MAX_NODES = 5_000_000;

export const isPresetLevel = (level: number): boolean => Number.isInteger(level) && level >= 0 && level < NODE_LEVELS.length;
export const isCustomLevel = (level: number): boolean => Number.isInteger(level) && level >= CUSTOM_MIN_NODES && level <= CUSTOM_MAX_NODES;
export const isValidLevel = (level: number): boolean => isPresetLevel(level) || isCustomLevel(level);

/** The node budget of a level; an invalid level gives the fallback level's budget. */
export function nodesOfLevel(level: number, fallback = 2): number {
  if (isPresetLevel(level)) return NODE_LEVELS[level]!;
  if (isCustomLevel(level)) return level;
  return NODE_LEVELS[fallback]!;
}

/** The level for a node budget: a preset when the budget is exactly one of theirs, else a custom level (rounded, kept inside the limits). */
export function levelForNodes(nodes: number): number {
  const preset = NODE_LEVELS.findIndex((n) => n === Math.round(nodes));
  if (preset >= 0) return preset;
  return Math.min(CUSTOM_MAX_NODES, Math.max(CUSTOM_MIN_NODES, Math.round(nodes)));
}
