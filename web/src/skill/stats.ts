// The statistic of the skill estimate. Pure: no DOM, no engine. See docs/web-ui.md, section "Skill estimate".
//
// One human move has a loss: the best score of any legal move minus the score of the move played, both from
// engine.analyse at SKILL_NODES (scoreLoss in engines/analysis.ts). The statistic of a set of moves is the MEAN OF THE LOSSES,
// EACH CAPPED AT LOSS_CAP; the median is shown next to it.
//
// Why capped mean and not the median alone: a good player plays the best move most of the time, so the median is 0 for a whole
// range of strengths and cannot tell them apart. The plain mean is wrong the other way: a race or mate score is worth up to a
// million points, so one blunder into a lost position would be the whole statistic. The cap bounds one move's weight to
// LOSS_CAP; 200 points is one move of race arithmetic and about a 92 % swing in win probability (scoreScale in analysis.ts).

/** Largest loss, in points, that one move contributes. */
export const LOSS_CAP = 200;

/** Fewest analysed moves for which an estimate is shown. Below this the interval is wider than the whole ladder. */
export const MIN_MOVES = 8;

export const cappedLoss = (loss: number): number => Math.min(LOSS_CAP, Math.max(0, loss));

export function median(values: readonly number[]): number {
  const sorted = [...values].sort((a, b) => a - b);
  const mid = sorted.length >> 1;
  return sorted.length % 2 === 1 ? sorted[mid]! : (sorted[mid - 1]! + sorted[mid]!) / 2;
}

export interface LossStat {
  /** Number of moves the statistic is based on. */
  n: number;
  /** Mean of the capped losses: the number that is mapped to a node budget. */
  mean: number;
  /** Median of the capped losses (informative only). */
  median: number;
}

/** The statistic of raw (uncapped) losses; null without moves. */
export function lossStat(losses: readonly number[]): LossStat | null {
  if (losses.length === 0) return null;
  const capped = losses.map(cappedLoss);
  return { n: capped.length, mean: capped.reduce((s, x) => s + x, 0) / capped.length, median: median(capped) };
}

/** Small deterministic generator (mulberry32), so that the interval of the same moves is always the same. */
export function seededRandom(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/**
 * Percentile bootstrap interval of the mean of the capped losses: resample the moves with replacement, take the mean each time,
 * and report the central `level` share (default 80 %) of the means. It shows the sampling noise of the moves, nothing else.
 */
export function bootstrapInterval(
  losses: readonly number[],
  options: { resamples?: number; level?: number; seed?: number } = {},
): { low: number; high: number } {
  const { resamples = 400, level = 0.8, seed = 1 } = options;
  const capped = losses.map(cappedLoss);
  const n = capped.length;
  const random = seededRandom(seed);
  const means: number[] = [];
  for (let r = 0; r < resamples; r++) {
    let sum = 0;
    for (let i = 0; i < n; i++) sum += capped[Math.floor(random() * n)]!;
    means.push(sum / n);
  }
  means.sort((a, b) => a - b);
  const tail = (1 - level) / 2;
  return {
    low: means[Math.floor(tail * (resamples - 1))]!,
    high: means[Math.ceil((1 - tail) * (resamples - 1))]!,
  };
}
