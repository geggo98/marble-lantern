// The numbers of figure V14 (topic 5): the Elo arithmetic of search/.../Elo.java, and the rows of the staircase, the sweeps, the futility
// grid and sweep 1, read from the data files. No React in here. Every number that a figure shows comes through this file.
import ladderJson from '../data/tuning-ladder.json';
import sweepsJson from '../data/sweeps.json';

// ---- Elo arithmetic (search/src/main/java/sternhalma/search/Elo.java) ----

/** `Elo.fromScore` (Elo.java:9-13): 400 log10(s / (1 - s)), the score clamped to [0.001, 0.999]. */
export function eloFromScore(score: number): number {
  const s = Math.min(0.999, Math.max(0.001, score));
  return 400 * Math.log10(s / (1 - s));
}

/** `Elo.confidence95` (Elo.java:42-47): half width of the 95 % interval in Elo from the standard error of the score. */
export function confidence95(score: number, scoreStandardError: number): number {
  const lo = eloFromScore(score - 1.96 * scoreStandardError);
  const hi = eloFromScore(score + 1.96 * scoreStandardError);
  return (hi - lo) / 2;
}

/** Highest score on the Elo panel: the clamp of `fromScore`. */
export const SCORE_MAX = 0.999;
export const SCORE_MIN = 0.5;

// ---- data shapes ----

interface Num {
  value: string;
}
interface Sourced {
  source: { doc: string; line: number };
}
interface LadderLevel {
  id: string;
  measured: boolean;
  elo?: Sourced & Num & { ci95?: string };
  score?: Sourced & Num & { se: string };
}
interface FutilityRow extends Sourced {
  fut: string;
  futk: string;
  fut2?: string;
  seed31?: { elo: string; ci95: string };
  seed32?: { elo: string; ci95: string };
}
interface Ladder {
  levels: LadderLevel[];
  saturation: { scores: { values: string[] } };
  futility: { nodes: Num; chosen: { fut: string; futk: string; fut2: string }; rows: FutilityRow[] };
}
interface SweepPoint {
  elo: Num & { se: string };
}
interface Sweeps {
  sweep1: {
    variants: { id: string; score: Num & { se: string } }[];
    confirmation: { id: string; pairs: Num; score: Num & { se: string } }[];
  };
  mctsSweep: {
    cpuctBatch1: (SweepPoint & { c: string })[];
    batchAtC05: (SweepPoint & { batch: string })[];
    priorTemperature: (SweepPoint & { temperature: string })[];
  };
}

const ladder = ladderJson as unknown as Ladder;
const sweeps = sweepsJson as unknown as Sweeps;

const num = (text: string): number => Number(text.replace(/,/g, ''));

// ---- the staircase ----

export const LEVEL_IDS = ['decisions', 'hand-tuning', 'regression', 'grids', 'sweeps', 'recipe-sweep', 'pre-registered'] as const;
export type LevelId = (typeof LEVEL_IDS)[number];

export interface Level {
  id: LevelId;
  /** 0 to 6: the level number of the topic text. */
  k: number;
  measured: boolean;
  /** The Elo (signed text as the docs print it, and as a number) and the half width of the 95 % interval. */
  elo?: number;
  eloText?: string;
  ci?: number;
  ciText?: string;
}

/** The seven levels. A level with a score and no Elo interval gets its half width from `confidence95` (rounded, as the docs print Elo). */
export function levels(): Level[] {
  return ladder.levels.map((row, i) => {
    const level: Level = { id: row.id as LevelId, k: i, measured: row.measured };
    if (!row.measured || !row.elo) return level;
    level.elo = num(row.elo.value);
    level.eloText = row.elo.value;
    const ci = row.elo.ci95 !== undefined ? num(row.elo.ci95) : row.score ? Math.round(confidence95(num(row.score.value), num(row.score.se))) : undefined;
    if (ci !== undefined) {
      level.ci = ci;
      level.ciText = String(ci);
    }
    return level;
  });
}

/** The shared Elo axis of the staircase: from 0 (or lower) to the next 100 above the highest end of an interval. */
export function staircaseDomain(rows: readonly Level[] = levels()): [number, number] {
  let lo = 0;
  let hi = 0;
  for (const r of rows) {
    if (r.elo === undefined) continue;
    lo = Math.min(lo, r.elo - (r.ci ?? 0));
    hi = Math.max(hi, r.elo + (r.ci ?? 0));
  }
  return [Math.floor(lo / 100) * 100, Math.ceil(hi / 100) * 100];
}

// ---- the Elo curve ----

/**
 * The standard deviation of one pair's score, taken from the confirmation of variant F in sweep 1 (400 pairs, standard error 0.0136:
 * sd = se x sqrt(pairs) = 0.272). The other measured match of the docs with pairs and se (LARGE: 150 pairs, 0.021, sd 0.257) is close.
 * The panel assumes this one sd for every score, so its band shows how the steepness of the curve alone widens the interval.
 */
export function pairScoreSd(): number {
  const f = sweeps.sweep1.confirmation.find((c) => c.id === 'F');
  if (!f) throw new Error('sweeps.json has no confirmation of F');
  return num(f.score.se) * Math.sqrt(num(f.pairs.value));
}

export const scoreStandardError = (pairs: number): number => pairScoreSd() / Math.sqrt(pairs);

export interface EloPoint {
  score: number;
  elo: number;
  lo: number;
  hi: number;
  /** Half width of the 95 % interval, `confidence95`. */
  ci: number;
}

export function eloPoint(score: number, pairs: number): EloPoint {
  const se = scoreStandardError(pairs);
  return {
    score,
    elo: eloFromScore(score),
    lo: eloFromScore(score - 1.96 * se),
    hi: eloFromScore(score + 1.96 * se),
    ci: confidence95(score, se),
  };
}

/** The curve and its band for the plot: `count` scores from 0.5 to the clamp. */
export function eloCurve(pairs: number, count = 120): EloPoint[] {
  return Array.from({ length: count }, (_, i) => eloPoint(SCORE_MIN + ((SCORE_MAX - SCORE_MIN) * i) / (count - 1), pairs));
}

/** The two scores at which the classical ladder saturated in loop run 4, as numbers. */
export const saturationScores = (): [number, number] => {
  const [a, b] = ladder.saturation.scores.values.map(num);
  return [a ?? NaN, b ?? NaN];
};

// ---- sweeps ----

export interface SweepDot {
  /** The setting as the docs print it, and as a number. */
  label: string;
  x: number;
  elo: number;
  /** Half width of the 95 % interval. The docs table is headed "Elo (± 95 %)"; sweeps.json calls the number `se`. */
  ci: number;
}

const dots = (rows: { label: string; elo: SweepPoint['elo'] }[]): SweepDot[] =>
  rows.map((r) => ({ label: r.label, x: num(r.label), elo: num(r.elo.value), ci: num(r.elo.se) }));

export function mctsSweeps(): { c: SweepDot[]; batch: SweepDot[]; ptemp: SweepDot[] } {
  const s = sweeps.mctsSweep;
  return {
    c: dots(s.cpuctBatch1.map((r) => ({ label: r.c, elo: r.elo }))),
    batch: dots(s.batchAtC05.map((r) => ({ label: r.batch, elo: r.elo }))),
    ptemp: dots(s.priorTemperature.map((r) => ({ label: r.temperature, elo: r.elo }))),
  };
}

/** The shared y domain of the three small plots, round to 100. */
export function sweepsDomain(all = mctsSweeps()): [number, number] {
  const rows = [...all.c, ...all.batch, ...all.ptemp];
  const lo = Math.min(...rows.map((r) => r.elo - r.ci));
  const hi = Math.max(...rows.map((r) => r.elo + r.ci));
  return [Math.floor(lo / 100) * 100, Math.ceil(hi / 100) * 100];
}

// ---- futility grid ----

export interface FutilityCell {
  elo: number;
  eloText: string;
  ci: number;
}
export interface FutilityLine {
  fut: string;
  futk: string;
  /** Undefined: the second-depth margin is off. */
  fut2?: string;
  seed31?: FutilityCell;
  seed32?: FutilityCell;
  chosen: boolean;
}

const cell = (c?: { elo: string; ci95: string }): FutilityCell | undefined =>
  c === undefined ? undefined : { elo: num(c.elo), eloText: c.elo, ci: num(c.ci95) };

export function futilityRows(): FutilityLine[] {
  const chosen = ladder.futility.chosen;
  return ladder.futility.rows.map((r) => {
    const line: FutilityLine = {
      fut: r.fut,
      futk: r.futk,
      chosen: r.fut === chosen.fut && r.futk === chosen.futk && r.fut2 === chosen.fut2,
    };
    if (r.fut2 !== undefined) line.fut2 = r.fut2;
    const a = cell(r.seed31);
    const b = cell(r.seed32);
    if (a) line.seed31 = a;
    if (b) line.seed32 = b;
    return line;
  });
}

/** Lowest and highest Elo over all played cells: the bounds of the heat discs. */
export function futilityRange(rows: readonly FutilityLine[] = futilityRows()): [number, number] {
  const all = rows.flatMap((r) => [r.seed31?.elo, r.seed32?.elo]).filter((v): v is number => v !== undefined);
  return [Math.min(...all), Math.max(...all)];
}

// ---- sweep 1 ----

export interface RecipePoint {
  id: string;
  score: number;
  /** Half width of the 95 % interval of the score: 1.96 x the standard error. */
  half: number;
}

const recipe = (id: string, s: Num & { se: string }): RecipePoint => ({ id, score: num(s.value), half: 1.96 * num(s.se) });

export function sweep1(): { variants: RecipePoint[]; confirmation: RecipePoint[]; winner: string } {
  const variants = sweeps.sweep1.variants.map((v) => recipe(v.id, v.score));
  const winner = variants.reduce((a, b) => (b.score > a.score ? b : a)).id;
  return { variants, confirmation: sweeps.sweep1.confirmation.map((c) => recipe(c.id, c.score)), winner };
}

/** The y domain of sweep 1 in steps of 0.02, so that every whisker fits. */
export function sweep1Domain(data = sweep1()): [number, number] {
  const rows = [...data.variants, ...data.confirmation];
  const lo = Math.min(...rows.map((r) => r.score - r.half));
  const hi = Math.max(...rows.map((r) => r.score + r.half));
  return [Number((Math.floor(lo / 0.02 + 1e-9) * 0.02).toFixed(2)), Number((Math.ceil(hi / 0.02 - 1e-9) * 0.02).toFixed(2))];
}
