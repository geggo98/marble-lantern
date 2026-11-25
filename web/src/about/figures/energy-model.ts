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
  strength?: { elo: number; ci: number; source: Cite };
  source: Cite;
  hoursSource?: Cite;
  stepsSource?: Cite;
  aboveIdleSource?: Cite;
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

/** Rule 2: the ladder decides when the two 95 % intervals are disjoint. Touching at one value is not disjoint. */
function ladder(a: EnergyRow, b: EnergyRow): Verdict {
  if (!a.strength || !b.strength) return 'unresolved';
  const aLo = a.strength.elo - a.strength.ci;
  const aHi = a.strength.elo + a.strength.ci;
  const bLo = b.strength.elo - b.strength.ci;
  const bHi = b.strength.elo + b.strength.ci;
  if (aLo > bHi) return 'stronger';
  if (bLo > aHi) return 'weaker';
  return 'unresolved';
}

/**
 * Is `a` stronger than `b` at the comparison basis? Three rules, in this order:
 * 1. A direct match of the two rows for the basis decides when its Elo is larger than its interval, else the pair is unresolved.
 * 2. Else, at equal simulations or for two nets of one size (equal simulations are then equal time), the ladder decides when the intervals are disjoint.
 * 3. Else (equal time, nets of different size, no match): a SMALL row that the ladder ranks higher stays higher, because equal time gives SMALL more
 *    simulations than the ladder gave it; a LARGE row that the ladder ranks higher is unresolved. Origin: LARGE needs 5.6 times the CPU time per move
 *    (docs/training.md, "Strength per cost") and more simulations buy strength there. Limit: it assumes that more simulations never make a net weaker.
 * The result is antisymmetric: swapping the rows swaps `stronger` and `weaker`.
 */
export function compareStrength(a: EnergyRow, b: EnergyRow, basis: Basis, data: EnergyData): Verdict {
  const m = matchFor(a, b, basis, data);
  if (m) return m.elo > m.ci ? 'stronger' : -m.elo > m.ci ? 'weaker' : 'unresolved';
  const byLadder = ladder(a, b);
  if (basis === 'sims' || a.net === b.net) return byLadder;
  if (byLadder === 'unresolved') return 'unresolved';
  const smallWins = (byLadder === 'stronger' ? a : b).net === 'SMALL';
  return smallWins ? byLadder : 'unresolved';
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

/** The y of the mark that decides a row: its ladder Elo, or the Elo of its anchor plus the match for a row that has one. */
export function strengthY(row: EnergyRow, basis: Basis, data: EnergyData): number {
  const m = matchOf(row, basis, data);
  if (m) {
    if (!m.anchor.strength) throw new Error(`anchor ${m.anchor.id} has no strength`);
    return m.anchor.strength.elo + m.elo;
  }
  if (!row.strength) throw new Error(`row ${row.id} has no strength`);
  return row.strength.elo;
}

// ---- front geometry ----

export interface FrontPoint {
  x: number;
  y: number;
  /** The way to this point is dashed (the row is `unknown` or its y comes from a match). */
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

/** The points of the front for a strength view and basis, with `unknown` rows and rows decided by a match drawn dashed. */
export function frontPoints(view: Exclude<View, 'both'>, basis: Basis, data: EnergyData): FrontPoint[] {
  return data.rows
    .filter((r) => r.group === 'run')
    .sort((a, b) => xOf(a, view) - xOf(b, view))
    .flatMap((r) => {
      const status = frontStatus(r, basis, data);
      if (status === 'dominated') return [];
      return [{ x: xOf(r, view), y: strengthY(r, basis, data), dashed: status === 'unknown' || matchOf(r, basis, data) !== undefined }];
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

const ELO_TICKS = [0, 200, 400, 600, 800, 1000, 1200];
const KWH_WIDE = [0.01, 0.02, 0.05, 0.1, 0.2, 0.5, 1, 2, 5, 10];
const KWH_COMPACT = [0.01, 0.1, 1, 10];
const HOURS_WIDE = [0.5, 1, 2, 5, 10, 20, 50, 100, 200];
const HOURS_COMPACT = [1, 10, 100];

/** Axes of a view in the wide and the compact layout (design spec section 2). The energy and the duration domain span three decades each. */
export function viewSpec(view: View, compact: boolean): ViewSpec {
  const elo: AxisSpec = { scale: 'linear', domain: [0, 1200], ticks: ELO_TICKS, key: 'elo' };
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
