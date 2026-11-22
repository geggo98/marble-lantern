// Model of figure J3 (SIMD with the Vector API, topic 6 "java"): one pass of the inner loop of the hex convolution as a grid of products, 4 board
// cells by the 32 output channels of SMALL. The lane count (1, 4, 8, 16) only moves the gaps between the registers; the steps walk the loop body
// (2 loads, 4 broadcasts, 8 FMAs, then the stores). All numbers come from data/java-simd.json or are computed here. No React in here.
import raw from '../data/java-simd.json';

interface Entry {
  id: string;
  value: number;
  /** The text that the cited line of `source.doc` must contain. */
  needle: string;
  source: { doc: string; line: number };
}

export const SIMD_ENTRIES: readonly Entry[] = raw.entries as Entry[];

const num = (id: string): number => {
  const e = SIMD_ENTRIES.find((x) => x.id === id);
  if (!e) throw new Error(`java-simd.json has no entry ${id}`);
  return e.value;
};

export const LANE_STOPS = [1, 4, 8, 16] as const;
export type Lanes = (typeof LANE_STOPS)[number];
export const DEFAULT_LANES: Lanes = 4;

/** Output channels of the SMALL preset. */
export const CHANNELS = num('channels-small');
/** Cells that one pass of the loop handles (`CELL_BLOCK`). */
export const CELLS = num('cell-block');
/** Registers per tile (`TILE = 2 * LANES`). */
export const TILE_REGISTERS = num('tile-registers');
/** Steps of the stepper: 0 is idle, 1 to LAST_STEP walk the loop body and the stores. */
export const LAST_STEP = 2 + CELLS * TILE_REGISTERS + 1;
export const FMA_FIRST_STEP = 3;
/** Products of the grid: cells times channels. */
export const GRID_PRODUCTS = CELLS * CHANNELS;
/** Repeats of the loop body for one block of cells: gather entries (1 + 6) times input channels (32 in SMALL). */
export const LOOP_REPEATS = num('stencil') * CHANNELS;
export const STORES = num('stores');
/** FMAs in the loop body: one per cell and register. */
export const BODY_FMAS = CELLS * TILE_REGISTERS;

export const laneIndex = (lanes: Lanes): number => LANE_STOPS.indexOf(lanes);
export const lanesAt = (index: number): Lanes => LANE_STOPS[Math.min(LANE_STOPS.length - 1, Math.max(0, Math.round(index)))]!;

/** Tiles in one row of the grid at this lane count: 32 / (2 * lanes). */
export const tileCount = (lanes: number): number => CHANNELS / (TILE_REGISTERS * lanes);
/** FMAs for the whole row of the grid: 128 / lanes. */
export const fmasForRow = (lanes: number): number => GRID_PRODUCTS / lanes;
export const registerBits = (lanes: number): number => 32 * lanes;

export type StepKind = 'idle' | 'load' | 'bcast' | 'fma' | 'done';
export const STEP_KINDS: readonly StepKind[] = ['idle', 'load', 'bcast', 'fma', 'done'];

export const stepKind = (step: number): StepKind => (step <= 0 ? 'idle' : step === 1 ? 'load' : step === 2 ? 'bcast' : step >= LAST_STEP ? 'done' : 'fma');

/** FMA number 0 to 7 of a step 3 to 10; -1 for every other step. */
export const fmaIndex = (step: number): number => (stepKind(step) === 'fma' ? step - FMA_FIRST_STEP : -1);
/** Accumulator name of FMA i: the first digit is the cell, the second the weight register. */
export const accName = (i: number): string => `a${i >> 1}${i & 1}`;
export const weightName = (i: number): string => `w${i & 1}`;
export const inputName = (i: number): string => `v${i >> 1}`;

export type BoxState = 'none' | 'pending' | 'current' | 'done';
/** State of the product box of accumulator `i` (0 to 7) of the first tile at this step. */
export function accState(step: number, i: number): Exclude<BoxState, 'none'> {
  if (stepKind(step) === 'done') return 'done';
  const f = fmaIndex(step);
  if (f < 0) return 'pending';
  return i < f ? 'done' : i === f ? 'current' : 'pending';
}

export type ValueState = 'none' | 'empty' | 'loaded' | 'current';
/** State of the weight register j (0 or 1) of the first tile: its box and its name. */
export function weightState(step: number, j: number): ValueState {
  const k = stepKind(step);
  if (k === 'idle') return 'empty';
  return k === 'load' || (k === 'fma' && (fmaIndex(step) & 1) === j) ? 'current' : 'loaded';
}
/** State of the input register v_c (name box and the dots in its row): `none` before the broadcast. */
export function inputState(step: number, c: number): ValueState {
  const k = stepKind(step);
  if (k === 'idle' || k === 'load') return 'none';
  return k === 'bcast' || (k === 'fma' && fmaIndex(step) >> 1 === c) ? 'current' : 'loaded';
}

/** FMAs of the loop body that are done at this step, the current one included: 0 before the first, 8 at the end. */
export const fmasDone = (step: number): number => (stepKind(step) === 'fma' ? step - FMA_FIRST_STEP + 1 : stepKind(step) === 'done' ? BODY_FMAS : 0);

// ---- the code strip: lines of nn/src/main/java/sternhalma/nn/VectorNetwork.java ----

export const CODE_FILE = 'nn/src/main/java/sternhalma/nn/VectorNetwork.java';

export interface CodeLine {
  /** Line number in `CODE_FILE`; null for a spacer line that only holds the height. */
  n: number | null;
  /** The source line, without indentation. */
  text: string;
  /** Identifiers that the grid highlights at this step. */
  hl: readonly string[];
}

const SRC: Readonly<Record<number, string>> = {
  305: 'for (int ch = 0; ch < inChannels; ch++, w += TILE) {',
  306: 'FloatVector w0 = FloatVector.fromArray(SPECIES, weight, w);',
  307: 'FloatVector w1 = FloatVector.fromArray(SPECIES, weight, w + LANES);',
  308: 'FloatVector v0 = FloatVector.broadcast(SPECIES, in[r0 + ch]);',
  311: 'FloatVector v3 = FloatVector.broadcast(SPECIES, in[r3 + ch]);',
  312: 'a00 = w0.fma(v0, a00);',
  313: 'a01 = w1.fma(v0, a01);',
  314: 'a10 = w0.fma(v1, a10);',
  315: 'a11 = w1.fma(v1, a11);',
  316: 'a20 = w0.fma(v2, a20);',
  317: 'a21 = w1.fma(v2, a21);',
  318: 'a30 = w0.fma(v3, a30);',
  319: 'a31 = w1.fma(v3, a31);',
  322: 'store(out, residual, cell * stride + o, a00, a01);',
  325: 'store(out, residual, (cell + 3) * stride + o, a30, a31);',
};
/** Every line the strip can show, by number (the test compares them with the source file). */
export const CODE_SOURCE: Readonly<Record<number, string>> = SRC;

const line = (n: number, hl: readonly string[] = []): CodeLine => ({ n, text: SRC[n]!, hl });

/** The source lines of a step, at most two; the FMA steps show one line and a spacer so that every step keeps the two-line height. */
export function codeLines(step: number): CodeLine[] {
  switch (stepKind(step)) {
    case 'idle':
      return [line(305)];
    case 'load':
      return [line(306, ['w0']), line(307, ['w1'])];
    case 'bcast':
      return [line(308, ['v0']), line(311, ['v3'])];
    case 'fma': {
      const i = fmaIndex(step);
      return [line(312 + i, [accName(i), weightName(i), inputName(i)]), { n: null, text: '', hl: [] }];
    }
    case 'done':
      return [line(322), line(325)];
  }
}

// ---- narration ----

export interface Narration {
  /** Catalog key. */
  key: string;
  values: Record<string, string | number>;
}

export function narration(step: number, lanes: number): Narration {
  const kind = stepKind(step);
  const key = `fig.simd.narr.${kind}`;
  switch (kind) {
    case 'idle':
      return { key, values: { cells: CELLS, channels: CHANNELS } };
    case 'load':
    case 'bcast':
      return { key, values: { lanes } };
    case 'fma': {
      const i = fmaIndex(step);
      return { key, values: { acc: accName(i), w: weightName(i), v: inputName(i), lanes } };
    }
    case 'done':
      return { key, values: { fmas: BODY_FMAS, products: BODY_FMAS * lanes, repeats: LOOP_REPEATS, stores: STORES } };
  }
}

/**
 * One narration per kind with the values that give the longest text (16 lanes, accumulator a31): the ghost lines of the status area. The
 * area keeps the height of the tallest of them, so that the figure does not jump from step to step or when the slider moves.
 */
export function longestNarration(): Narration[] {
  const top = LANE_STOPS[LANE_STOPS.length - 1]!;
  return [0, 1, 2, LAST_STEP - 1, LAST_STEP].map((s) => narration(s, top));
}

// ---- the number line ----

export const MEAS = {
  dot: num('measured-dot'),
  lo: num('measured-lo'),
  hi: num('measured-hi'),
  /** Lane count of the machine that was measured. */
  lanes: num('lanes-neon') as Lanes,
  /** The text rounds the range to whole numbers ("15 to 17"). */
  textLo: num('measured-text-lo'),
  textHi: num('measured-text-hi'),
} as const;
export const AXIS_MAX = 18;

export type MachineNote = 'scalar' | 'neon' | 'avx2' | 'avx512';
export const machineNote = (lanes: Lanes): MachineNote => (lanes === 1 ? 'scalar' : lanes === 4 ? 'neon' : lanes === 8 ? 'avx2' : 'avx512');
export const MACHINE_NOTES: readonly MachineNote[] = ['scalar', 'neon', 'avx2', 'avx512'];

// ---- geometry ----

export type SimdLayout = 'compact' | 'wide';
/** Figure width from which the wide layout applies (40 rem). */
export const WIDE_FROM_PX = 640;

interface Params {
  W: number;
  /** Gutter for the names v0 to v3. */
  GL: number;
  /** Height of the weight row and of a product row; gap between product rows. */
  hh: number;
  rh: number;
  rg: number;
  /** Gap between the two registers of a tile (gv) and between tiles (gt), at 1 lane and otherwise. */
  gv1: number;
  gt1: number;
  gv: number;
  gt: number;
}
const PARAMS: Record<SimdLayout, Params> = {
  compact: { W: 260, GL: 26, hh: 12, rh: 14, rg: 4, gv1: 1, gt1: 2, gv: 2, gt: 5 },
  wide: { W: 640, GL: 36, hh: 14, rh: 18, rg: 5, gv1: 2, gt1: 5, gv: 3, gt: 10 },
};

export interface GridGeometry {
  W: number;
  H: number;
  GL: number;
  hh: number;
  rh: number;
  /** Width of one column and of the lane box in it. */
  col: number;
  box: number;
  /** Gap between the two registers of a tile and between tiles. */
  gv: number;
  gt: number;
  tiles: number;
  yHead: number;
  yRows: number;
  bottom: number;
  /** Left edge of the box of output channel `ch`. */
  x: (ch: number) => number;
  /** Top of the product row of cell `c`. */
  y: (c: number) => number;
}

/** Geometry of the product grid. The view box depends on the layout only, never on the lane count or the step. */
export function gridGeometry(layout: SimdLayout, lanes: number): GridGeometry {
  const P = PARAMS[layout];
  const gv = lanes === 1 ? P.gv1 : P.gv;
  const gt = lanes === 1 ? P.gt1 : P.gt;
  const tiles = tileCount(lanes);
  const gaps = tiles * gv + (tiles - 1) * gt;
  const col = (P.W - P.GL - 2 - gaps) / CHANNELS;
  const tileW = TILE_REGISTERS * lanes;
  const x = (ch: number): number => P.GL + ch * col + Math.floor(ch / tileW) * (gt + gv) + Math.floor((ch % tileW) / lanes) * gv;
  const yHead = 26;
  const yRows = yHead + P.hh + 8;
  const y = (c: number): number => yRows + c * (P.rh + P.rg);
  const bottom = y(CELLS - 1) + P.rh;
  return { W: P.W, H: bottom + 12, GL: P.GL, hh: P.hh, rh: P.rh, col, box: col - 1, gv, gt, tiles, yHead, yRows, bottom, x, y };
}

/** Number line: x of a value on the axis 0 to AXIS_MAX. */
export const MEAS_VIEW = { compact: { W: 260, H: 72 }, wide: { W: 320, H: 72 } } as const;
export const measX = (layout: SimdLayout, v: number): number => 8 + (v / AXIS_MAX) * (MEAS_VIEW[layout].W - 8 - 12);
