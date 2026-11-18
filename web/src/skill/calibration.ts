// The calibration: which statistic (stats.ts) an engine of a given node budget reaches, measured by scripts/web/calibrate-skill.mts
// and stored in calibration.json, and the mapping back from a player's statistic to a node budget. Pure.
import { MIN_MOVES, bootstrapInterval, lossStat } from './stats';

export interface LadderRung {
  /** Node budget of the engine that played the measured moves. */
  nodes: number;
  /** Number of analysed moves, and of games they come from. */
  moves: number;
  games: number;
  /** Measured statistic: mean of the capped losses. */
  stat: number;
  median: number;
  /** Standard error of `stat`, from the spread of the losses (the moves of one game are not independent, so it is optimistic). */
  se: number;
  /** `stat` after isotonic smoothing (never increases with the budget). The mapping uses this. */
  smoothed: number;
}

export interface Calibration {
  v: 1;
  statistic: { name: string; cap: number };
  /** The node budget of the analysis that produced every loss (SKILL_NODES). */
  analysisNodes: number;
  engine: { id: string; version: string };
  date: string;
  /** Random legal plies at the start of each game, to vary the games. */
  openingPlies: number;
  seeds: number[];
  /** Ascending node budgets. */
  ladder: LadderRung[];
  /** Rungs above this budget are not told apart from it (see resolvedTop); the mapping uses only rungs up to it. */
  resolvedUpTo: number;
  /** Free text: what was measured, noise, violations of monotonicity. */
  notes?: string;
}

/**
 * Pool-adjacent-violators for a NON-INCREASING fit: the closest series (weighted least squares) in which no value is larger
 * than its predecessor. A larger budget must not have a larger loss, so noise that breaks that is averaged out.
 */
export function isotonicNonIncreasing(values: readonly number[], weights?: readonly number[]): number[] {
  const blocks: { sum: number; weight: number; count: number }[] = [];
  values.forEach((v, i) => {
    const w = weights?.[i] ?? 1;
    blocks.push({ sum: v * w, weight: w, count: 1 });
    while (blocks.length > 1) {
      const b = blocks[blocks.length - 1]!;
      const a = blocks[blocks.length - 2]!;
      if (b.sum / b.weight <= a.sum / a.weight) break; // still non-increasing
      blocks.splice(blocks.length - 2, 2, { sum: a.sum + b.sum, weight: a.weight + b.weight, count: a.count + b.count });
    }
  });
  return blocks.flatMap((b) => Array.from({ length: b.count }, () => b.sum / b.weight));
}

/** How many neighbours of a series (ordered by budget) break "never increases". */
export function ladderViolations(stats: readonly number[]): number {
  let n = 0;
  for (let i = 1; i < stats.length; i++) if (stats[i]! > stats[i - 1]!) n++;
  return n;
}

/**
 * The smallest budget from which the smoothed ladder is flat within one standard error: above it the statistic no longer
 * separates strength. The analysis that produces the losses has a budget itself (SKILL_NODES); engines stronger than its
 * judgement cannot be told apart by agreement with it.
 */
export function resolvedTop(nodes: readonly number[], smoothed: readonly number[], se: readonly number[]): number {
  const final = smoothed[smoothed.length - 1]!;
  const i = smoothed.findIndex((s, k) => s - final <= se[k]!);
  return nodes[i]!;
}

export interface LadderPoint {
  nodes: number;
  stat: number;
}

export interface NodesForStat {
  nodes: number;
  /** 'weak': the statistic is worse than the weakest rung; 'strong': better than the strongest. Null inside the ladder. */
  clamp: 'weak' | 'strong' | null;
}

/**
 * The node budget whose statistic equals `stat`: piecewise linear in log(nodes) between the points of a non-increasing ladder,
 * clamped at both ends. On a plateau (equal statistics) every budget fits, so it returns the middle of the plateau in log(nodes).
 * It never decreases when `stat` decreases.
 */
export function nodesForStat(stat: number, ladder: readonly LadderPoint[]): NodesForStat {
  const first = ladder[0]!;
  const last = ladder[ladder.length - 1]!;
  if (stat > first.stat) return { nodes: first.nodes, clamp: 'weak' };
  if (stat < last.stat) return { nodes: last.nodes, clamp: 'strong' };
  let lo = Infinity;
  let hi = -Infinity;
  const take = (x: number): void => {
    lo = Math.min(lo, x);
    hi = Math.max(hi, x);
  };
  if (ladder.length === 1) take(Math.log(first.nodes));
  for (let i = 0; i + 1 < ladder.length; i++) {
    const a = ladder[i]!;
    const b = ladder[i + 1]!;
    if (stat > a.stat || stat < b.stat) continue;
    if (a.stat === b.stat) {
      take(Math.log(a.nodes));
      take(Math.log(b.nodes));
    } else {
      const t = (a.stat - stat) / (a.stat - b.stat);
      take(Math.log(a.nodes) + t * (Math.log(b.nodes) - Math.log(a.nodes)));
    }
  }
  // exp(log(x)) is off by a rounding error; keep the result inside the ladder
  return { nodes: Math.min(last.nodes, Math.max(first.nodes, Math.exp((lo + hi) / 2))), clamp: null };
}

/** Where a node budget lies relative to the preset levels: the level index, fractional in between (log scale); below 0 or above the last index outside. */
export function levelPosition(nodes: number, levels: readonly number[]): number {
  const logs = levels.map(Math.log);
  const x = Math.log(nodes);
  const n = levels.length;
  if (x <= logs[0]!) return (x - logs[0]!) / (logs[1]! - logs[0]!);
  if (x >= logs[n - 1]!) return n - 1 + (x - logs[n - 1]!) / (logs[n - 1]! - logs[n - 2]!);
  let i = 0;
  while (logs[i + 1]! < x) i++;
  return i + (x - logs[i]!) / (logs[i + 1]! - logs[i]!);
}

export type SkillEstimate =
  | { enough: false; n: number; needed: number }
  | {
      enough: true;
      /** Number of analysed moves it is based on. */
      n: number;
      stat: number;
      median: number;
      /** Node budget of the estimate and the ends of the 80 % interval (sampling noise of the moves only). */
      nodes: number;
      lowNodes: number;
      highNodes: number;
      /** The estimate lies beyond the measured ladder. */
      clamp: 'weak' | 'strong' | null;
    };

/** The estimate from the raw (uncapped) losses of a player's moves. */
export function estimateSkill(losses: readonly number[], calibration: Calibration): SkillEstimate {
  const s = lossStat(losses);
  if (!s || s.n < MIN_MOVES) return { enough: false, n: losses.length, needed: MIN_MOVES };
  const ladder = calibration.ladder
    .filter((r) => r.nodes <= calibration.resolvedUpTo)
    .map((r) => ({ nodes: r.nodes, stat: r.smoothed }));
  const point = nodesForStat(s.mean, ladder);
  const { low, high } = bootstrapInterval(losses);
  return {
    enough: true,
    n: s.n,
    stat: s.mean,
    median: s.median,
    nodes: point.nodes,
    // a larger loss means a weaker player: the high end of the loss is the low end of the nodes
    lowNodes: Math.min(point.nodes, nodesForStat(high, ladder).nodes),
    highNodes: Math.max(point.nodes, nodesForStat(low, ladder).nodes),
    clamp: point.clamp,
  };
}
