// V9b: what the board shows at each layer step, computed from forward-<position>.json. Pure.
import start from '../data/forward-start.json';
import mid from '../data/forward-mid.json';
import race from '../data/forward-race.json';
import backends from '../data/backends.json';
import { heatLevel } from '../kit/board-heat';
import type { HeatCell } from '../kit/Boards';
import { parseDocNumber } from './numbers';

export type PositionId = 'start' | 'mid' | 'race';
export const POSITIONS: readonly PositionId[] = ['start', 'mid', 'race'];

export interface ForwardData {
  meta: { position: string; ply: number; sideToMove: number; legalMoves: number; planeNames: string[]; beta: number; k: number };
  cells: number[];
  planes: number[][];
  layers: { id: string; norm: number[] }[];
  policy: { from: number; to: number; prior: number; priorNetOnly: number }[];
  values: number[];
  valuesNetOnly: number[];
}

const DATA: Record<PositionId, ForwardData> = { start: start as ForwardData, mid: mid as ForwardData, race: race as ForwardData };
export const forwardData = (p: PositionId): ForwardData => DATA[p];

/** The eight steps of the stepper, input to value head (the trunk layers are the `layers` of the data). */
export const STEP_IDS = ['input', 'conv_in', 'block0', 'block1', 'block2', 'block3', 'policy', 'value'] as const;
export type StepId = (typeof STEP_IDS)[number];

/**
 * The heat cells of the input plane: a plane of 0 and 1 draws level 5 where it is 1 and nothing elsewhere; a scaled plane uses the five
 * levels between 0 and its largest value (zero stays empty).
 */
export function planeCells(plane: readonly number[]): (HeatCell | null)[] {
  const binary = plane.every((v) => v === 0 || v === 1);
  const max = Math.max(...plane);
  return plane.map((v) => {
    if (v <= 0) return null;
    if (binary) return { level: 5 };
    const level = heatLevel(v, 0, max);
    return level === null ? null : { level };
  });
}

/** The bounds of the activation strength of a position over all trunk layers, so that the layers can be compared. */
export function activationBounds(d: ForwardData): { min: number; max: number } {
  const all = d.layers.flatMap((l) => l.norm);
  return { min: Math.min(...all), max: Math.max(...all) };
}

export function activationCells(d: ForwardData, layer: string): (HeatCell | null)[] {
  const norm = d.layers.find((l) => l.id === layer)?.norm;
  if (!norm) throw new Error(`no layer ${layer}`);
  const { min, max } = activationBounds(d);
  return norm.map((v) => {
    const level = heatLevel(v, min, max);
    return level === null ? null : { level };
  });
}

/** The five highest priors; `withPriors` picks the final priors or the network's own. Ties keep the order of the data. */
export function topMoves(d: ForwardData, withPriors: boolean, count = 5): { from: number; to: number; prior: number }[] {
  const key = withPriors ? 'prior' : 'priorNetOnly';
  return d.policy
    .map((m, i) => ({ from: m.from, to: m.to, prior: m[key], i }))
    .sort((a, b) => b.prior - a.prior || a.i - b.i)
    .slice(0, count)
    .map(({ from, to, prior }) => ({ from, to, prior }));
}

/** Seat cells for a MiniBoard: seat `a` is player 0. */
export const piecesOf = (d: ForwardData): { a: number[]; b: number[] } => ({
  a: d.cells.flatMap((o, c) => (o === 0 ? [c] : [])),
  b: d.cells.flatMap((o, c) => (o === 1 ? [c] : [])),
});

/** Arrow width 2 to 6 board units by prior (relative to the highest of the five). */
export const arrowWidth = (prior: number, top: number): number => 2 + 4 * (top > 0 ? prior / top : 0);

// ---- the backend panel ----

export type BackendId = 'scalar' | 'vector' | 'tornado' | 'wasm' | 'webgpu' | 'webgpu16';
export const BACKEND_IDS: readonly BackendId[] = ['scalar', 'vector', 'tornado', 'wasm', 'webgpu', 'webgpu16'];

interface BackendRow {
  backend: string;
  preset: string;
  batch: string;
  rate?: { value: string; source: { doc: string; line: number } };
  callTime?: { value: string; unit: string; source: { doc: string; line: number } };
}
const ROWS = backends.rows as BackendRow[];
const find = (backend: string, batch: string): BackendRow => {
  const row = ROWS.find((r) => r.backend === backend && r.batch === batch && r.preset === 'SMALL');
  if (!row) throw new Error(`backends.json has no row ${backend} / batch ${batch}`);
  return row;
};
const rateOf = (row: BackendRow): { value: number; doc: string } => {
  if (row.rate) return { value: parseDocNumber(row.rate.value), doc: row.rate.source.doc };
  if (row.callTime) return { value: 1000 / parseDocNumber(row.callTime.value), doc: row.callTime.source.doc };
  throw new Error(`row ${row.backend} has neither a rate nor a call time`);
};

export interface BackendPoint {
  id: BackendId;
  /** Evaluations per second. */
  value: number;
  /** A second measurement of the same backend (the relaxed-SIMD build of the WebAssembly engine), for a whisker. */
  high?: number;
  batch: number;
  doc: string;
}

/**
 * One point per backend at its smallest batch (WebGPU also pipelined at batch 16), in the order of the chart. A row that gives only the time
 * of one call (WebGPU: 0.5 ms) becomes evaluations per second as 1000 / milliseconds.
 */
export function backendPoints(): BackendPoint[] {
  const point = (id: BackendId, row: BackendRow, high?: BackendRow): BackendPoint => {
    const r = rateOf(row);
    return { id, value: r.value, ...(high ? { high: rateOf(high).value } : {}), batch: Number(row.batch), doc: r.doc };
  };
  return [
    point('scalar', find('Java scalar reference (Network)', '1')),
    point('vector', find('Java Vector API (VectorNetwork)', '1')),
    point('tornado', find('TornadoVM on Metal', '1')),
    point('wasm', find('C++ WebAssembly, simd (net-wasm)', '1'), find('C++ WebAssembly, relaxed (net-wasm)', '1')),
    point('webgpu', find('WebGPU, batch 1', '1')),
    point('webgpu16', find('WebGPU, pipelined', '16')),
  ];
}
