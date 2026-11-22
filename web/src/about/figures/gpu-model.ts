// Model of figure J4 (GPU anatomy for TornadoVM, Fig. 6.4): the machines of data/gpu-anatomy.json, the eight steps with the parts they
// light up, the numbers of the kernel grid (derived here from the repository's constants, never typed in), the facts of the detail panel
// and the rows of the tables. No React in here; the texts come from the catalog, only numbers and literals are made here.
import raw from '../data/gpu-anatomy.json';

export type HwId = 'm3' | 't4' | 'rtx';
export type Kind = 'discrete' | 'unified';
export const HW_IDS: readonly HwId[] = ['m3', 't4', 'rtx'];
/** The order of the picker and of the table columns. */
export const DEFAULT_HW: HwId = 't4';

/** A number with a unit. `approx` writes a leading "≈", `atMost` the words of `unit.atmost`. */
export interface Quantity {
  v: number;
  u: string;
  approx?: boolean;
  atMost?: boolean;
}

export interface Machine {
  short: string;
  name: string;
  kind: Kind;
  units: number;
  cudaCores: number | null;
  cudaPerUnit: number | null;
  schedulers: number | null;
  maxWarps: number | null;
  regs: { k: number; bits: number } | null;
  l1: Quantity | null;
  l1Split: number[][] | null;
  l2: Quantity | null;
  threadgroup: { mem: Quantity; maxThreads: number } | null;
  mem: Quantity;
  memMax: Quantity | null;
  memRate: Quantity;
  link: { name: string; rate: Quantity; thickness: number } | null;
  latency: { l1: number; l2: number; memLo: number; memHi: number } | null;
  backend: string | null;
  copyBelowMs: number | null;
}

export interface GridData {
  rows: number;
  channels: number;
  naiveMaxWork: number;
  simdgroup: { tile: number; groupSize: number };
  tiled: { tileN: number; tileM: number; groupX: number; groupY: number };
  sources: Record<string, { file: string; line: number; needle: string }>;
}

export const WARP_SIZE: number = raw.warpSize;
export const MACHINES: Readonly<Record<HwId, Machine>> = raw.machines as unknown as Record<HwId, Machine>;
export const GRID: GridData = raw.grid as GridData;
export const WORK_GROUP: { localMemKiB: number; maxItems: number } = raw.workGroup;
export const AKA: Readonly<Record<string, readonly string[]>> = raw.aka;
export const SPARK: { mem: Quantity; rate: Quantity } = raw.spark;

// ---- formatting of numbers and literals ----

type N = (value: number) => string;

/** "≈1,800 GB/s" / "64 GB"; `atMost` is the lookup of `unit.atmost` (the words come from the catalog). */
export function quantityText(q: Quantity, n: N, atMost: (value: string) => string): string {
  const body = `${q.approx ? '≈' : ''}${n(q.v)} ${q.u}`;
  return q.atMost ? atMost(body) : body;
}

/** "≈300–600" for a range, "≈30" for a single value (the latency ladder). */
export const cyclesText = (lo: number, hi: number | null, n: N): string => `≈${n(lo)}${hi === null ? '' : `–${n(hi)}`}`;

/** "64K × 32 bit". */
export const regsText = (regs: { k: number; bits: number }, n: N): string => `${n(regs.k)}K × ${regs.bits} bit`;

/** "64 + 32 KB / 32 + 64 KB": the two splits of the unified L1 and shared memory (shared memory + L1). */
export const splitText = (split: readonly (readonly number[])[], unit: string, n: N): string => split.map(([a, b]) => `${n(a!)} + ${n(b!)} ${unit}`).join(' / ');

/** The text of a count on the page: nearest 500 from 10,000, 100 from 500, 10 from 10, else whole. 124,928 gives 125,000; 976 gives 1,000; 22.96 gives 20. */
export function roundedCount(value: number): number {
  const step = value >= 10000 ? 500 : value >= 500 ? 100 : value >= 10 ? 10 : 1;
  return Math.round(value / step) * step;
}

// ---- the kernel grid of one block convolution (LARGE, 64 output channels) ----

export type Variant = 'NAIVE' | 'SIMDGROUP' | 'TILED';

export interface GridCounts {
  variant: Variant;
  rows: number;
  threads: number;
  /** Null when TornadoVM picks the work group (NAIVE). */
  groups: number | null;
  warps: number;
  /** warps / units: "if spread evenly". */
  perUnit: number;
}

const ceilDiv = (a: number, b: number): number => Math.ceil(a / b);

/** `KernelsHexConv.variantFor` for one convolution: NAIVE while batch * channels <= 128, else SIMDGROUP on Metal and TILED elsewhere. */
export function variantFor(hw: HwId, batch: number, grid: GridData = GRID): Variant {
  if (batch * grid.channels <= grid.naiveMaxWork) return 'NAIVE';
  return MACHINES[hw].backend === 'Metal' ? 'SIMDGROUP' : 'TILED';
}

/** `simdgroupGrid` and `tiledGrid` of KernelsHexConv, and `naive` (one thread per row and channel); see data `grid.sources`. */
export function gridCounts(hw: HwId, batch: number, grid: GridData = GRID, units: number = MACHINES[hw].units): GridCounts {
  const rows = batch * grid.rows;
  const variant = variantFor(hw, batch, grid);
  let threads: number;
  let groups: number | null = null;
  if (variant === 'NAIVE') {
    threads = rows * grid.channels;
  } else if (variant === 'SIMDGROUP') {
    groups = ceilDiv(grid.channels, grid.simdgroup.tile) * ceilDiv(rows, grid.simdgroup.tile);
    threads = groups * grid.simdgroup.groupSize;
  } else {
    groups = ceilDiv(grid.channels, grid.tiled.tileN) * ceilDiv(rows, grid.tiled.tileM);
    threads = groups * grid.tiled.groupX * grid.tiled.groupY;
  }
  const warps = threads / WARP_SIZE;
  return { variant, rows, threads, groups, warps, perUnit: warps / units };
}

/** Threads of one work group of the tiled kernels: 128. */
export const GROUP_THREADS: number = GRID.simdgroup.groupSize;
export const GROUP_WARPS: number = GROUP_THREADS / WARP_SIZE;

// ---- parts, slots, steps ----

/** A slot is a place in the anatomy that each kind of machine fills with its own part. */
export type Slot = 'cpu' | 'hmem' | 'link' | 'dmem' | 'gpu' | 'l2' | 'unit' | 'l1' | 'reg' | 'warp' | 'simt' | 'thread' | 'group';
export const PART_OF: Readonly<Record<Kind, Readonly<Record<Slot, string>>>> = {
  discrete: { cpu: 'cpu', hmem: 'hostmem', link: 'link', dmem: 'devmem', gpu: 'gpu', l2: 'l2', unit: 'sm', l1: 'l1', reg: 'reg', warp: 'warp', simt: 'simt', thread: 'thread', group: 'group' },
  unified: { cpu: 'cpu', hmem: 'umem', link: 'copy', dmem: 'umem', gpu: 'gpu', l2: 'cache', unit: 'core', l1: 'tgmem', reg: 'reg', warp: 'simd', simt: 'simt', thread: 'thread', group: 'group' },
};

/** The part ids a machine has. */
export const partsOf = (hw: HwId): ReadonlySet<string> => new Set(Object.values(PART_OF[MACHINES[hw].kind]));

export type StepId = 'idle' | 'host' | 'path' | 'gpu' | 'unit' | 'warp' | 'thread' | 'grid' | 'back';
export type CmpRow = 'units' | 'warp' | 'l1' | 'l2' | 'mem' | 'membw' | 'path' | 'backend';
export type Direction = 'in' | 'out';

export interface Step {
  id: StepId;
  /** The level that the step shows (1 to 5). */
  level: 1 | 2 | 3 | 4 | 5;
  /** Slots that light up; `ladder` and `chain` are the distance ladder and the chain picture, which are no parts. */
  slots: readonly (Slot | 'ladder' | 'chain')[];
  rows: readonly CmpRow[];
  /** The way the pipe points: to the GPU, to the host, both ways when idle. */
  dir?: Direction;
  /** The narration has one text per kind of machine. */
  byKind: boolean;
}

/** Step 0 is the idle state; steps 1 to 8 follow one call from the Java array to one GPU thread and back (spec, section 6). */
export const STEPS: readonly Step[] = [
  { id: 'idle', level: 1, slots: [], rows: [], byKind: false },
  { id: 'host', level: 1, slots: ['cpu', 'hmem'], rows: ['backend'], byKind: true },
  { id: 'path', level: 1, slots: ['hmem', 'link', 'dmem'], rows: ['path', 'membw'], dir: 'in', byKind: true },
  { id: 'gpu', level: 2, slots: ['dmem', 'l2', 'unit'], rows: ['units', 'l2', 'mem'], byKind: true },
  { id: 'unit', level: 3, slots: ['l1', 'reg', 'warp'], rows: ['l1'], byKind: true },
  { id: 'warp', level: 4, slots: ['simt'], rows: ['warp'], byKind: false },
  { id: 'thread', level: 4, slots: ['thread', 'ladder'], rows: [], byKind: true },
  { id: 'grid', level: 5, slots: ['group', 'chain'], rows: [], byKind: false },
  { id: 'back', level: 1, slots: ['dmem', 'link', 'hmem', 'cpu'], rows: ['path'], dir: 'out', byKind: true },
];
export const STEP_COUNT: number = STEPS.length - 1;
export const stepAt = (step: number): Step => STEPS[Math.max(0, Math.min(STEP_COUNT, step))] as Step;

/** The ids of the parts that are active at a step; empty at the idle step (everything is drawn in ink). */
export function activeParts(hw: HwId, step: number): ReadonlySet<string> {
  const kind = MACHINES[hw].kind;
  return new Set(stepAt(step).slots.flatMap((slot) => (slot === 'ladder' || slot === 'chain' ? [] : [PART_OF[kind][slot]])));
}

export type PartState = 'active' | 'dim' | undefined;
/** The state of a part: nothing at the idle step, else active or dim. */
export const partState = (hw: HwId, step: number, id: string): PartState => (step === 0 ? undefined : activeParts(hw, step).has(id) ? 'active' : 'dim');

/** The key of the narration of a step: `narr.host.discrete`, `narr.warp`. */
export const narrationKey = (hw: HwId, step: number): string => {
  const s = stepAt(step);
  return s.byKind ? `fig.gpu.narr.${s.id}.${MACHINES[hw].kind}` : `fig.gpu.narr.${s.id}`;
};

// ---- the distance ladder (level 4) ----

export interface LadderRow {
  /** The part whose label the row carries. */
  part: 'reg' | 'l1' | 'l2' | 'devmem' | 'cache' | 'umem';
  /** The bar as a share of the row: the order of the levels, not a scale. */
  bar: 22 | 44 | 70 | 100;
  /** Latency in cycles, or null for a dash. */
  cycles: { lo: number; hi: number | null } | null;
}

/** Four rows on every machine; the M3 Max has two known rungs and the two others are reserved (hidden) so that the list keeps its height. */
export function ladderRows(hw: HwId): { rows: LadderRow[]; reserved: number } {
  const m = MACHINES[hw];
  if (m.kind === 'unified') {
    return {
      rows: [
        { part: 'cache', bar: 44, cycles: null },
        { part: 'umem', bar: 100, cycles: null },
      ],
      reserved: 2,
    };
  }
  const lat = m.latency;
  return {
    rows: [
      { part: 'reg', bar: 22, cycles: null },
      { part: 'l1', bar: 44, cycles: lat ? { lo: lat.l1, hi: null } : null },
      { part: 'l2', bar: 70, cycles: lat ? { lo: lat.l2, hi: null } : null },
      { part: 'devmem', bar: 100, cycles: lat ? { lo: lat.memLo, hi: lat.memHi } : null },
    ],
    reserved: 0,
  };
}

// ---- the comparison table ----

export type CellValue = { kind: 'text'; text: string } | { kind: 'units' } | { kind: 'none' } | { kind: 'dash' };

/** The cell of the comparison table; `units` ("40 SMs", "40 cores") and `none` are words of the catalog that the component fills in. */
export function cmpCell(row: CmpRow, hw: HwId, n: N, atMost: (value: string) => string): CellValue {
  const m = MACHINES[hw];
  const q = (x: Quantity | null): CellValue => (x ? { kind: 'text', text: quantityText(x, n, atMost) } : { kind: 'dash' });
  switch (row) {
    case 'units':
      return { kind: 'units' };
    case 'warp':
      return { kind: 'text', text: n(WARP_SIZE) };
    case 'l1':
      return q(m.l1);
    case 'l2':
      return q(m.l2);
    case 'mem':
      return q(m.mem);
    case 'membw':
      return q(m.memRate);
    case 'path':
      return m.link ? q(m.link.rate) : { kind: 'none' };
    case 'backend':
      return m.backend ? { kind: 'text', text: m.backend } : { kind: 'dash' };
  }
}
export const CMP_ROWS: readonly CmpRow[] = ['units', 'warp', 'l1', 'l2', 'mem', 'membw', 'path', 'backend'];

// ---- facts of the detail panel ----

export interface Fact {
  /** Key suffix of `fig.gpu.fact.<key>`. */
  key: string;
  value: string;
}

/** The facts that the data holds for a part on a machine; empty when the maker publishes none (the panel then says so). */
export function partFacts(hw: HwId, id: string, n: N, atMost: (value: string) => string): Fact[] {
  const m = MACHINES[hw];
  const q = (x: Quantity): string => quantityText(x, n, atMost);
  const out: Fact[] = [];
  const add = (key: string, value: string | null | undefined): void => {
    if (value) out.push({ key, value });
  };
  switch (id) {
    case 'cpu':
      add('thread', n(1));
      break;
    case 'hostmem':
      add('type', 'FloatArray');
      break;
    case 'umem':
      add('size', q(m.mem));
      add('bw', q(m.memRate));
      add('max', m.memMax && q(m.memMax));
      break;
    case 'copy':
      add('copy', m.copyBelowMs !== null ? `< ${n(m.copyBelowMs)} ms` : null);
      break;
    case 'link':
      if (m.link) {
        add('link', m.link.name);
        add('bw', q(m.link.rate));
        add('membw', q(m.memRate));
      }
      break;
    case 'devmem':
      add('size', q(m.mem));
      add('bw', q(m.memRate));
      add('latency', m.latency && cyclesText(m.latency.memLo, m.latency.memHi, n));
      break;
    case 'gpu':
      add('units', n(m.units));
      add('cores', m.cudaCores !== null ? n(m.cudaCores) : null);
      add('backend', m.backend);
      break;
    case 'l2':
      add('size', m.l2 && q(m.l2));
      add('latency', m.latency && cyclesText(m.latency.l2, null, n));
      break;
    case 'sm':
      add('count', n(m.units));
      add('percores', m.cudaPerUnit !== null ? n(m.cudaPerUnit) : null);
      add('sched', m.schedulers !== null ? n(m.schedulers) : null);
      add('maxwarps', m.maxWarps !== null ? n(m.maxWarps) : null);
      break;
    case 'core':
      add('count', n(m.units));
      break;
    case 'l1':
      add('size', m.l1 && q(m.l1));
      add('latency', m.latency && cyclesText(m.latency.l1, null, n));
      add('split', m.l1Split && m.l1 ? splitText(m.l1Split, m.l1.u, n) : null);
      break;
    case 'tgmem':
      if (m.threadgroup) {
        add('size', q(m.threadgroup.mem));
        add('maxthreads', n(m.threadgroup.maxThreads));
      }
      break;
    case 'reg':
      add('size', m.regs && regsText(m.regs, n));
      break;
    case 'warp':
      add('threads', n(WARP_SIZE));
      add('maxwarps', m.maxWarps !== null ? n(m.maxWarps) : null);
      break;
    case 'simd':
    case 'simt':
      add('threads', n(WARP_SIZE));
      break;
    case 'group':
      add('threads', n(GROUP_THREADS));
      add('localmem', atMost(`${n(WORK_GROUP.localMemKiB)} KiB`));
      add('maxgroup', n(WORK_GROUP.maxItems));
      break;
    default:
      break;
  }
  return out;
}

/** "thread block · threadgroup · work group": the names of the same thing at NVIDIA, Apple and TornadoVM (the part has none: null). */
export const akaText = (id: string): string | null => AKA[id]?.join(' · ') ?? null;

// ---- values of the narration ----

/** The values that a narration key takes. Numbers are formatted with `n`; `atMost` words come from the catalog. */
export function narrationValues(hw: HwId, n: N, atMost: (value: string) => string): Record<string, string> {
  const m = MACHINES[hw];
  const b1 = gridCounts(hw, 1);
  const b256 = gridCounts(hw, 256);
  return {
    link: m.link?.name ?? '',
    linkRate: m.link ? quantityText(m.link.rate, n, atMost) : '',
    memRate: quantityText(m.memRate, n, atMost),
    copy: m.copyBelowMs !== null ? `${n(m.copyBelowMs)} ms` : '',
    mem: quantityText(m.mem, n, atMost),
    l2: m.l2 ? quantityText(m.l2, n, atMost) : '',
    units: n(m.units),
    l1: m.l1 ? quantityText(m.l1, n, atMost) : '',
    tg: m.threadgroup ? quantityText(m.threadgroup.mem, n, atMost) : '',
    threads1: n(roundedCount(b1.threads)),
    threads256: n(roundedCount(b256.threads)),
    group: n(GROUP_THREADS),
  };
}
