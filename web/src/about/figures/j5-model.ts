// Model of figure J5 (GPU overhead and batching, topic java): the rows of data/j5-overhead.json, the derived split of a call into work and
// rest, the verdict, the split that the C++ twin measured, and the way the page prints durations, shares and factors. No React in here.
// Definitions (design spec, section 1): work = batch x full-load cost (the time per position at the largest measured batch); rest = call time
// minus work. Rest is 0 at the largest batch by definition, so it is a lower bound of the real fixed cost.
import raw from '../data/j5-overhead.json';

export type Machine = 'mac' | 't4';
export type Net = 'SMALL' | 'LARGE';
export type Verdict = 'dominates' | 'mixed' | 'amortized';
export type Fill = 'solid' | 'hatch' | 'dots' | 'none';

export const MACHINES: readonly Machine[] = ['mac', 't4'];
export const NETS: readonly Net[] = ['SMALL', 'LARGE'];
/** The five stops of the slider: the batch sizes that both machines measured. */
export const BATCHES: readonly number[] = [1, 16, 64, 256, 1024];
/** The state at the start: Mac, SMALL, batch 1. */
export const DEFAULT_STATE: { machine: Machine; net: Net; batch: number } = { machine: 'mac', net: 'SMALL', batch: 1 };

/** The y axis of the plot: log, in ms, from 5 µs to 3 ms. */
export const PLOT_DOMAIN: readonly [number, number] = [0.005, 3];
export const PLOT_TICKS: readonly number[] = [0.01, 0.1, 1];

/** Rest share from which a call is "dominated" by its own cost, and from which it is "mixed". */
export const DOMINATES_FROM = 0.5;
export const MIXED_FROM = 0.1;

export interface SeriesRow {
  batch: number;
  evals: number;
  /** The printed time of the same table line (Mac only), kept as a cross-check. */
  ms?: number;
  needle: string;
  source: { doc: string; line: number };
}
export interface CpuRow {
  evals: number;
  range?: readonly [number, number];
  needle: string;
  source: { doc: string; line: number };
}
export interface MacSplitRow {
  batch: number;
  wall: number;
  gpu: number;
  write: number;
  read: number;
  source: { doc: string; line: number; line2: number };
}
export interface T4SplitRow {
  batch: number;
  h2d: number;
  kernel: number;
  d2h: number;
  source: { doc: string; line: number };
}
export interface MachineData {
  status: string;
  statusNote: string;
  kernels: Record<Net, number>;
  series: Record<Net, readonly SeriesRow[]>;
  cpu: Record<Net, CpuRow>;
  split: { program: string; kind: 'gpu-outside' | 'copy-kernel-copy' } & Partial<Record<Net, readonly (MacSplitRow | T4SplitRow)[]>>;
}
export interface J5Data {
  machines: Record<Machine, MachineData>;
  where: {
    pool: { games: number; carriers: number; cohorts: number; batch: number; quietMicros: number; maxWaitMs: number; sources: readonly string[] };
    tree: { batchBelow800: number; batchAt800: number; lossBatch16Sims200: readonly [number, number]; sources: readonly string[] };
    one: { batch: number; sources: readonly string[] };
  };
}

export const J5_DATA = raw as unknown as J5Data;

// ---- series and the call ----

export interface CallRow {
  batch: number;
  evals: number;
  /** Wall time of one call, ms: batch x 1000 / evals. */
  ms: number;
}

export const seriesOf = (machine: Machine, net: Net, data: J5Data = J5_DATA): CallRow[] =>
  data.machines[machine].series[net].map((r) => ({ batch: r.batch, evals: r.evals, ms: (r.batch * 1000) / r.evals }));

/** The measured batch that is nearest to `batch` on the log scale; a tie goes to the smaller one. */
export function nearestBatch(batches: readonly number[], batch: number): number {
  const sorted = [...batches].sort((a, b) => a - b);
  const distance = (b: number): number => Math.abs(Math.log2(b) - Math.log2(batch));
  return sorted.reduce((best, b) => (distance(b) < distance(best) ? b : best));
}

export interface CallPoint {
  batch: number;
  evals: number;
  /** Call time, ms. */
  ms: number;
  /** Time per position, ms. */
  perPos: number;
  /** Time per position at the largest measured batch (the GPU is full), ms. */
  full: number;
  work: number;
  rest: number;
  /** rest / ms, from 0 to 1. */
  share: number;
  /** How many times cheaper a position is than at batch 1. */
  factor: number;
  verdict: Verdict;
}

export const verdictOf = (share: number): Verdict => (share >= DOMINATES_FROM ? 'dominates' : share >= MIXED_FROM ? 'mixed' : 'amortized');

/** The point of one series at the measured batch nearest to `batch`. */
export function callPoint(machine: Machine, net: Net, batch: number, data: J5Data = J5_DATA): CallPoint {
  const rows = seriesOf(machine, net, data);
  const at = nearestBatch(
    rows.map((r) => r.batch),
    batch,
  );
  const row = rows.find((r) => r.batch === at)!;
  const first = rows[0]!;
  const last = rows[rows.length - 1]!;
  const full = last.ms / last.batch;
  const perPos = row.ms / row.batch;
  // the largest batch has no rest by definition; the division and product would leave a rounding residue of 1e-16
  const work = row.batch === last.batch ? row.ms : Math.min(row.ms, row.batch * full);
  const rest = Math.max(0, row.ms - work);
  const share = rest / row.ms;
  return { batch: row.batch, evals: row.evals, ms: row.ms, perPos, full, work, rest, share, factor: first.ms / first.batch / perPos, verdict: verdictOf(share) };
}

/** Time per position of one CPU thread (Vector API) at batch 1, ms. */
export const cpuPerPosition = (machine: Machine, net: Net, data: J5Data = J5_DATA): number => 1000 / data.machines[machine].cpu[net].evals;

export const kernelsOf = (machine: Machine, net: Net, data: J5Data = J5_DATA): number => data.machines[machine].kernels[net];

// ---- the split that the C++ twin measured ----

export interface SplitSeg {
  id: 'gpu' | 'outside' | 'h2d' | 'kernel' | 'd2h';
  fill: Fill;
  ms: number;
}
export interface SplitView {
  /** The total that the segments add up to: the wall time of the twin (Mac) or the device path (T4), ms. */
  total: number;
  segs: SplitSeg[];
  /** A number without a segment: both copies on the Mac. */
  copies?: number;
}

/** The measured split for this machine, network and batch, or null (the empty state). */
export function splitFor(machine: Machine, net: Net, batch: number, data: J5Data = J5_DATA): SplitView | null {
  const s = data.machines[machine].split;
  const row = (s[net] ?? []).find((r) => r.batch === batch);
  if (!row) return null;
  if (s.kind === 'gpu-outside') {
    const r = row as MacSplitRow;
    return {
      total: r.wall,
      segs: [
        { id: 'gpu', fill: 'solid', ms: r.gpu },
        { id: 'outside', fill: 'hatch', ms: r.wall - r.gpu },
      ],
      copies: r.write + r.read,
    };
  }
  const r = row as T4SplitRow;
  return {
    total: r.h2d + r.kernel + r.d2h,
    segs: [
      { id: 'h2d', fill: 'dots', ms: r.h2d },
      { id: 'kernel', fill: 'solid', ms: r.kernel },
      { id: 'd2h', fill: 'dots', ms: r.d2h },
    ],
  };
}

// ---- the cards of block D ----

export type UseId = 'pool' | 'tree' | 'one';
/** The pool card is marked from this batch on; the one-search card at batch 1; the tree card never (the browser uses WebGPU, not TornadoVM). */
export const POOL_ACTIVE_FROM = 64;
export const isUseActive = (use: UseId, batch: number, data: J5Data = J5_DATA): boolean =>
  use === 'pool' ? batch >= POOL_ACTIVE_FROM : use === 'one' ? batch === data.where.one.batch : false;

/** The preset that is pressed for this batch, or null. `one` is batch 1, `pool` the pool's batch (256). */
export const presetFor = (batch: number, data: J5Data = J5_DATA): 'one' | 'pool' | null =>
  batch === data.where.one.batch ? 'one' : batch === data.where.pool.batch ? 'pool' : null;

// ---- the numbers as the page prints them ----

/** Two significant digits: 510.4 -> 510, 8.43 -> 8.4, 1968 -> 2000, 0.0084 -> 0.0084. */
export function sig2(value: number): number {
  if (value === 0) return 0;
  const magnitude = 10 ** (Math.floor(Math.log10(Math.abs(value))) - 1);
  return Math.round(value / magnitude) * magnitude;
}

/** Floating point noise off the product above: 8.4 stays 8.4, not 8.400000000000001. */
const clean = (value: number): number => Number(value.toPrecision(12));

export interface DurationParts {
  unit: 'us' | 'ms';
  value: number;
}

/** A duration given in ms: below 1 ms in µs, from 1 ms in ms, two significant digits ("510 µs", "8.4 µs", "2.3 ms", "48 ms"). */
export function durationParts(ms: number): DurationParts {
  const us = clean(sig2(ms * 1000));
  return us < 1000 ? { unit: 'us', value: us } : { unit: 'ms', value: clean(sig2(ms)) };
}

/** Rate (positions per second), two significant digits. */
export const rateValue = (evals: number): number => clean(sig2(evals));

/** "98 %", "< 1 %", "0 %": a share from 0 to 1. A share below 1 % that is not 0 reads "< 1 %". The spaces are no-break spaces. */
export function percentText(share: number): string {
  if (share === 0) return '0 %';
  if (share < 0.01) return '< 1 %';
  return `${Math.round(share * 100)} %`;
}

/** The factor against batch 1 as the readout shows it: 1 below 1.05, else two significant digits (57). */
export const factorValue = (factor: number): number => (factor < 1.05 ? 1 : clean(sig2(factor)));

/** Width of a segment in percent of the bar (0 to 100). */
export const widthPercent = (part: number, total: number): number => (total > 0 ? Math.min(100, Math.max(0, (part / total) * 100)) : 0);
