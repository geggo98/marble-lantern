// Figure "What the training cost" (Fig. 4.4, topic training, section energy): the pure part. Rows come from data/energy.json; this module
// decides which row is stronger than which at a given comparison basis, which rows are on the front, what the views draw and where, and how
// numbers are rounded. No React in here, so that node tests cover it (energy-model.test.ts). Design: scratchpad design spec of 2026-11-25,
// sections 2 and 6; layout note in web/design/about/about-notes.md, "V15".
import raw from '../data/energy.json';

export type View = 'energy' | 'time' | 'both';
export type Basis = 'sims' | 'time';
export type RowGroup = 'run' | 'steps';
export type NetName = 'SMALL' | 'LARGE';
export type FrontStatus = 'front' | 'unknown' | 'dominated';
export type Verdict = 'stronger' | 'weaker' | 'unresolved';
/** The costs a dominance check looks at. The figure uses both; a test uses one at a time to show that the view needs no third axis. */
export type Cost = 'energy' | 'time';

export interface Cite {
  doc: string;
  line: number;
  needle: string;
}
export interface PhaseCite extends Cite {
  /** The duration column of the cited phase row, "h:mm:ss". */
  duration: string;
}
export interface EnergyRow {
  id: string;
  net: NetName;
  group: RowGroup;
  trainer: 'gpu' | 'cpu';
  generation?: number;
  shipped?: boolean;
  hours: number;
  hoursFrom?: PhaseCite[];
  kwh: number;
  kwhRange?: [number, number];
  status: 'measured' | 'estimate';
  checked?: boolean;
  aboveIdleKwh?: number;
  steps?: number;
  /** The rating of a complete run on the scale 2p-adj-r5, per comparison basis. The steps rows have none. */
  strength?: Record<Basis, Rating>;
  source: Cite;
  hoursSource?: Cite;
  stepsSource?: Cite;
  aboveIdleSource?: Cite;
}
/** One entry of models/ratings/ratings.json (scale 2p-adj-r5), copied as that file prints it. */
export interface Rating {
  basis: Basis;
  /** The config id of the rating file, e.g. "net:r5-small-gen9@s1000". */
  config: string;
  /** Simulations per move of that config. */
  sims: number;
  elo: number;
  /** Half-width of the 95 % interval, disagreement between matches included. */
  hw95: number;
  /** The values the table prints (Elo rounded to 10, half-width up to 10). */
  displayElo: number;
  displayHw95: number;
  method: string;
  /** The entry rests on few matches (SMALL generation 0 at 1,000 simulations: two edges). */
  sparse?: boolean;
}
/** A pooled pairwise difference `a` minus `b` of the rating fit, for a pair without a head-to-head match. */
export interface PooledPair {
  a: string;
  b: string;
  basis: Basis;
  elo: number;
  hw95: number;
}
export interface EnergyMatch {
  /** The row that is compared (here LARGE generation 10) and the anchor it is compared with (SMALL generation 9). */
  a: string;
  b: string;
  basis: Basis;
  /** Elo of `a` against `b` and its 95 % interval. */
  elo: number;
  ci: number;
  simsA: number;
  simsB: number;
  source: Cite;
}
export interface NetInfo {
  channels: number;
  blocks: number;
  weights: number;
  packedBytes: number;
  cpuMsPerMove: number;
  source: Cite;
  packedSource: Cite;
  msSource: Cite;
}
export interface Factor {
  net: NetName;
  time: number;
  energy: number;
  timeSource: Cite;
  energySource: Cite;
}
export interface EnergyData {
  schema: number;
  source: { generator: string; date: string; how: string };
  machine: { name: string; idleW: number; trainW: [number, number]; source: Cite; trainWSource: Cite; trainWNote: string };
  nets: Record<NetName, NetInfo>;
  rows: EnergyRow[];
  matches: EnergyMatch[];
  pairs: PooledPair[];
  ratings: { file: string; scale: string; anchor: string; anchorElo: number; axis: { domain: [number, number]; rule: string }; compareRule: string };
  factors: Factor[];
}

export const ENERGY_DATA = raw as unknown as EnergyData;

export const VIEWS: readonly View[] = ['energy', 'time', 'both'];
export const BASES: readonly Basis[] = ['sims', 'time'];
export const DEFAULT_SELECTED = 'small-gen9';

export const rowById = (data: EnergyData, id: string): EnergyRow => {
  const row = data.rows.find((r) => r.id === id);
  if (!row) throw new Error(`no row ${id}`);
  return row;
};

/** The display code of a row: S0, S9, L0, L10 for the complete runs; S-GPU, S-CPU, L-GPU, L-CPU for the training steps. */
export function rowCode(row: Pick<EnergyRow, 'net' | 'group' | 'trainer' | 'generation'>): string {
  const initial = row.net === 'SMALL' ? 'S' : 'L';
  return row.group === 'run' ? `${initial}${row.generation ?? 0}` : `${initial}-${row.trainer.toUpperCase()}`;
}

/** The rows of a group in table order: complete runs by energy, training steps as the data file lists them. */
export function groupRows(data: EnergyData, group: RowGroup): EnergyRow[] {
  const rows = data.rows.filter((r) => r.group === group);
  return group === 'run' ? [...rows].sort((a, b) => a.kwh - b.kwh) : rows;
}

/** Mean power of a row in watts. */
export const powerW = (row: Pick<EnergyRow, 'kwh' | 'hours'>): number => (row.kwh / row.hours) * 1000;

// ---- strength and dominance ----

const matchFor = (a: EnergyRow, b: EnergyRow, basis: Basis, data: EnergyData): { elo: number; ci: number } | undefined => {
  for (const m of data.matches) {
    if (m.basis !== basis) continue;
    if (m.a === a.id && m.b === b.id) return { elo: m.elo, ci: m.ci };
    if (m.a === b.id && m.b === a.id) return { elo: -m.elo, ci: m.ci };
  }
  return undefined;
};

/** The pooled difference `a` minus `b` of the rating fit for the basis, if the data holds that pair (in either order). */
const pooledFor = (a: EnergyRow, b: EnergyRow, basis: Basis, data: EnergyData): { elo: number; hw: number } | undefined => {
  for (const p of data.pairs) {
    if (p.basis !== basis) continue;
    if (p.a === a.id && p.b === b.id) return { elo: p.elo, hw: p.hw95 };
    if (p.a === b.id && p.b === a.id) return { elo: -p.elo, hw: p.hw95 };
  }
  return undefined;
};

/** An interval for the difference decides the pair when it excludes 0. Touching 0 is not deciding. */
const decide = (elo: number, hw: number): Verdict => (elo > hw ? 'stronger' : -elo > hw ? 'weaker' : 'unresolved');

/**
 * Is `a` stronger than `b` at the comparison basis? Three rules, in this order (models/ratings/ratings.json, "energyTable.compareRule"):
 * 1. A head-to-head match of the two rows for the basis decides when its Elo is larger than its interval, else the pair is unresolved.
 * 2. Else the pooled pairwise difference of the rating fit decides when its interval excludes 0, else the pair is unresolved.
 * 3. Else (the fit lists no pooled difference for the pair) the difference of the two ratings decides when it is larger than the SUM of their
 *    half-widths. Origin: the interval of a difference is never wider than that sum, so this is the conservative reading. Limit: it can only
 *    leave a pair unresolved that the fit would resolve; it never resolves one that the fit leaves open.
 * Overlapping rating bars alone never decide the order, because the bars share the uncertain path to the anchor (rule 3 asks for more than overlap).
 * A row without a rating (the training-steps rows) is unresolved against every row. The result is antisymmetric.
 */
export function compareStrength(a: EnergyRow, b: EnergyRow, basis: Basis, data: EnergyData): Verdict {
  const m = matchFor(a, b, basis, data);
  if (m) return decide(m.elo, m.ci);
  const p = pooledFor(a, b, basis, data);
  if (p) return decide(p.elo, p.hw);
  const ra = a.strength?.[basis];
  const rb = b.strength?.[basis];
  if (!ra || !rb) return 'unresolved';
  return decide(ra.elo - rb.elo, ra.hw95 + rb.hw95);
}

/** `a` is not worse than `b` in every chosen cost. Equal cost counts (not strictly cheaper), as the model has no "equally strong". */
const notCostlier = (a: EnergyRow, b: EnergyRow, costs: readonly Cost[]): boolean =>
  (!costs.includes('energy') || a.kwh <= b.kwh) && (!costs.includes('time') || a.hours <= b.hours);

const BOTH_COSTS: readonly Cost[] = ['energy', 'time'];

/** `a` dominates `b` when it costs no more in every chosen cost and is resolved stronger. */
export function dominates(a: EnergyRow, b: EnergyRow, basis: Basis, data: EnergyData, costs: readonly Cost[] = BOTH_COSTS): boolean {
  return a.id !== b.id && notCostlier(a, b, costs) && compareStrength(a, b, basis, data) === 'stronger';
}

/**
 * Front status of a row of the group `run` at a basis: `dominated` when another run row dominates it, `unknown` when none does but a row that costs
 * no more is unresolved against it (never compared at that basis), else `front`.
 */
export function frontStatus(row: EnergyRow, basis: Basis, data: EnergyData, costs: readonly Cost[] = BOTH_COSTS): FrontStatus {
  const others = data.rows.filter((r) => r.group === 'run' && r.id !== row.id);
  if (others.some((o) => dominates(o, row, basis, data, costs))) return 'dominated';
  if (others.some((o) => notCostlier(o, row, costs) && compareStrength(o, row, basis, data) === 'unresolved')) return 'unknown';
  return 'front';
}

/** The match of a row against its anchor for the basis, if it has one. */
export function matchOf(row: EnergyRow, basis: Basis, data: EnergyData): (EnergyMatch & { anchor: EnergyRow }) | undefined {
  const m = data.matches.find((x) => x.a === row.id && x.basis === basis);
  return m ? { ...m, anchor: rowById(data, m.b) } : undefined;
}

/** The rating of a complete run for the basis. */
export function ratingOf(row: EnergyRow, basis: Basis): Rating {
  const r = row.strength?.[basis];
  if (!r) throw new Error(`row ${row.id} has no rating at ${basis}`);
  return r;
}

/** The y of the mark that decides a row: its Elo on the scale 2p-adj-r5 at the basis. */
export const strengthY = (row: EnergyRow, basis: Basis, _data?: EnergyData): number => ratingOf(row, basis).elo;

/** The y of the head-to-head triangle: the Elo of the anchor row plus the match. */
export function matchY(m: EnergyMatch & { anchor: EnergyRow }, basis: Basis): number {
  return ratingOf(m.anchor, basis).elo + m.elo;
}

// ---- front geometry ----

export interface FrontPoint {
  x: number;
  y: number;
  /** The way to this point is dashed (the row is `unknown`). */
  dashed?: boolean;
}
export interface PathVertex extends FrontPoint {
  dashed: boolean;
}
export interface FrontShape {
  /** Polyline vertices; `dashed` belongs to the segment that ends at the vertex. */
  line: PathVertex[];
  /** The closed polygon of the region below and right of the line (the last vertex is not a repeat of the first). */
  region: { x: number; y: number }[];
}

/**
 * The step line through the front points (sorted by x here): from each point horizontally to the next point's x, then vertically to its y; after the
 * last point horizontally to `xRight`. The region is everything below that line, from the first point to `xRight`, down to `yBottom`.
 */
export function frontPath(points: readonly FrontPoint[], xRight: number, yBottom: number): FrontShape {
  const sorted = [...points].sort((a, b) => a.x - b.x);
  const first = sorted[0];
  if (!first) return { line: [], region: [] };
  const line: PathVertex[] = [{ x: first.x, y: first.y, dashed: false }];
  let y = first.y;
  for (const p of sorted.slice(1)) {
    const dashed = p.dashed === true;
    line.push({ x: p.x, y, dashed }, { x: p.x, y: p.y, dashed });
    y = p.y;
  }
  line.push({ x: xRight, y, dashed: false });
  const region = [...line.map(({ x: px, y: py }) => ({ x: px, y: py })), { x: xRight, y: yBottom }, { x: first.x, y: yBottom }];
  return { line, region };
}

/** The x of a row in a strength view: energy in kWh or duration in hours. */
export const xOf = (row: EnergyRow, view: Exclude<View, 'both'>): number => (view === 'energy' ? row.kwh : row.hours);

/** The points of the front for a strength view and basis, with `unknown` rows drawn dashed. */
export function frontPoints(view: Exclude<View, 'both'>, basis: Basis, data: EnergyData): FrontPoint[] {
  return data.rows
    .filter((r) => r.group === 'run')
    .sort((a, b) => xOf(a, view) - xOf(b, view))
    .flatMap((r) => {
      const status = frontStatus(r, basis, data);
      if (status === 'dominated') return [];
      return [{ x: xOf(r, view), y: strengthY(r, basis, data), dashed: status === 'unknown' }];
    });
}

// ---- views and axes ----

export interface AxisSpec {
  scale: 'log' | 'linear';
  domain: [number, number];
  ticks: number[];
  /** The column of the data this axis shows. */
  key: 'kwh' | 'hours' | 'elo';
}
export interface ViewSpec {
  x: AxisSpec;
  y: AxisSpec;
}

/** The Elo axis: the interval ends of all ratings, rounded outward to 200 (the rule of the rating file; energy.test.ts checks it against the data). */
export const ELO_DOMAIN: [number, number] = [1000, 2400];
const ELO_TICKS = [1000, 1200, 1400, 1600, 1800, 2000, 2200, 2400];
const KWH_WIDE = [0.01, 0.02, 0.05, 0.1, 0.2, 0.5, 1, 2, 5, 10];
const KWH_COMPACT = [0.01, 0.1, 1, 10];
const HOURS_WIDE = [0.5, 1, 2, 5, 10, 20, 50, 100, 200];
const HOURS_COMPACT = [1, 10, 100];

/** Axes of a view in the wide and the compact layout (design spec section 2). The energy and the duration domain span three decades each. */
export function viewSpec(view: View, compact: boolean): ViewSpec {
  const elo: AxisSpec = { scale: 'linear', domain: ELO_DOMAIN, ticks: ELO_TICKS, key: 'elo' };
  switch (view) {
    case 'energy':
      return { x: { scale: 'log', domain: [0.01, 10], ticks: compact ? KWH_COMPACT : KWH_WIDE, key: 'kwh' }, y: elo };
    case 'time':
      return { x: { scale: 'log', domain: [0.25, 250], ticks: compact ? HOURS_COMPACT : HOURS_WIDE, key: 'hours' }, y: elo };
    case 'both':
      return {
        x: { scale: 'log', domain: [0.3, 300], ticks: compact ? HOURS_COMPACT : HOURS_WIDE, key: 'hours' },
        y: { scale: 'log', domain: [0.01, 10], ticks: KWH_COMPACT, key: 'kwh' },
      };
  }
}

export type MarkKind = 'point' | 'bound' | 'none';

/**
 * What a view draws for a row. The strength views draw the complete runs as points and the CPU training steps as vertical "at least this" lines
 * (their strength was not measured); a GPU training-steps row is a part of a run and is not drawn there. The view `both` draws every row as a point.
 */
export function markKind(row: EnergyRow, view: View): MarkKind {
  if (view === 'both') return 'point';
  if (row.group === 'run') return 'point';
  return row.trainer === 'cpu' ? 'bound' : 'none';
}

/** The run that a GPU training-steps row belongs to (same net, latest generation), for the line "not drawn in this view: part of the run X". */
export function parentRun(row: EnergyRow, data: EnergyData): EnergyRow | undefined {
  if (row.group !== 'steps' || row.trainer !== 'gpu') return undefined;
  return data.rows.filter((r) => r.group === 'run' && r.net === row.net).sort((a, b) => (b.generation ?? 0) - (a.generation ?? 0))[0];
}

// ---- hit targets ----

export interface Px {
  x: number;
  y: number;
}

/** Radius of the invisible hit circle of each point: half the distance to its nearest neighbour, at least `min` and at most `max` units. */
export function hitRadii(points: readonly Px[], min = 8, max = 22): number[] {
  return points.map((p, i) => {
    let nearest = Infinity;
    points.forEach((q, j) => {
      if (j !== i) nearest = Math.min(nearest, Math.hypot(p.x - q.x, p.y - q.y));
    });
    return Math.min(max, Math.max(min, nearest / 2));
  });
}

// ---- number formats (digits only; the component formats with Intl) ----

/** Decimals of a duration: two below 1 h, one from 1 h, none from 10 h. The boundary is decided after rounding (9.96 prints 10, not 10.0). */
export function formatHours(h: number): number {
  if (Math.round(h * 100) / 100 < 1) return 2;
  if (Math.round(h * 10) / 10 < 10) return 1;
  return 0;
}

/** Decimals of an energy: three for a measured row (the resolution of the reports), two significant digits for an estimate. */
export function formatKwh(row: Pick<EnergyRow, 'kwh' | 'status'>): number {
  if (row.status === 'measured') return 3;
  const rounded = Number(row.kwh.toPrecision(2));
  return Math.max(0, 1 - Math.floor(Math.log10(rounded)));
}

/** Days for a duration from 24 h on, else undefined. */
export const daysOf = (hours: number): number | undefined => (hours >= 24 ? hours / 24 : undefined);
