// Model of figure J2 (topic 6, "java", section plain-java): the same code written with objects and with primitives, in three views (a move,
// the undo data, the evaluation). Everything the figure counts or draws is derived here from data/java-plain.json; no React, no texts.
// The sizes in the data file were measured on OpenJDK 25.0.3 (Instrumentation.getObjectSize); the counts (allocations, bytes, cache lines)
// are computed from them. The two measured speeds of the figure (1.6 M nodes/s, +30 %) are not in this file: they are literals of the figure
// that cite docs/cpp-comparison.md and docs/search-and-evaluation.md.
import raw from '../data/java-plain.json';
import { cellName } from '../../ui/board-model';
import { DIRECTIONS, neighbor } from '../../board/cells';

export type PlainView = 'move' | 'undo' | 'eval';
export const VIEWS: readonly PlainView[] = ['move', 'undo', 'eval'];

interface SideState {
  dsum: number;
  /** [cell, distance to the goal], sorted by cell. */
  pieces: [number, number][];
}

export interface WalkStep {
  op: 'root' | 'make' | 'unmake';
  /** The packed move of the operation; 0 at the root. */
  move: number;
  /** The ply after the operation. */
  ply: number;
  /** The side to move after the operation (0 or 1). */
  stm: number;
  hash: string;
  s0: SideState;
  s1: SideState;
}

export interface PlainSizes {
  jvm: string;
  compressedOops: boolean;
  compressedClassPointers: boolean;
  compactObjectHeaders: boolean;
  alignment: number;
  moveObject: number;
  undoObject: number;
  intArray67: number;
  refArray67: number;
  arrayHeader: number;
  objectHeader: number;
  reference: number;
  cacheLine: number;
  cacheLineSource: string;
  frameInts: number;
}

export interface PlainData {
  position: { game: string; ply: number; sideToMove: number; treeFile: string };
  legalMoves: number[];
  maxMoves: number;
  dist: [number[], number[]];
  steps: WalkStep[];
  sizes: PlainSizes;
}

export const DATA = raw as unknown as PlainData;
export const SIZES = DATA.sizes;
/** Number of legal moves of the example position (67). */
export const N = DATA.legalMoves.length;
/** The walk has 7 states, 0 to 6. */
export const STEP_COUNT = DATA.steps.length;
export const LAST = STEP_COUNT - 1;
/** The ply of the root: the first column of the undo table. */
export const ROOT_PLY = DATA.steps[0]!.ply;
/** A word is 4 bytes; the memory map draws one mark per word and one row per cache line. */
export const WORD = 4;
export const WORDS_PER_LINE = SIZES.cacheLine / WORD;
/** The undo table, the object piles and the frames have three slots (the walk goes two plies deep). */
export const SLOTS = 3;
/** Pieces per side. */
export const PIECES = 10;
/** The incremental evaluation copies this many ints into a frame per make (IncrementalLinear.SAVED). */
export const FRAME_INTS = SIZES.frameInts;

export const moveFrom = (m: number): number => m & 0xff;
export const moveTo = (m: number): number => m >>> 8;
export const hex4 = (m: number): string => m.toString(16).padStart(4, '0');

const isStep = (from: number, to: number): boolean => Array.from({ length: DIRECTIONS }, (_, d) => neighbor(from, d)).includes(to);

/** A packed move in the notation of the app: `H5»N3` for a jump, `H5›I5` for a step. */
export function moveName(m: number): string {
  const from = moveFrom(m);
  const to = moveTo(m);
  return `${cellName(from)}${isStep(from, to) ? '›' : '»'}${cellName(to)}`;
}

/** The legal move number `k` (1 to N) in the order of Position.generate. */
export const moveAtPick = (k: number): number => DATA.legalMoves[Math.min(N, Math.max(1, k)) - 1]!;

// ---------- view "move": counts and memory maps ----------

export type WordKind = 'val' | 'hdr' | 'ref' | 'pad' | 'free';
export interface MapWord {
  t: WordKind;
  sel?: boolean;
}
export interface MemoryMapData {
  words: MapWord[];
  /** Runs of word indices [first, last] that get an outline. */
  boxes: [number, number][];
  /** From the word of the reference to the first word of its object (lane objects only). */
  hop?: [number, number];
  /** Three dots below the last row: the buffer goes on. */
  more: boolean;
}

export interface MoveCounts {
  allocations: number;
  bytes: number;
  lines: number;
}

const lines = (bytes: number): number => Math.ceil(bytes / SIZES.cacheLine);

/** Move[] with N references and N objects: N + 1 allocations; the array and the objects in bytes. */
export function objectCounts(): MoveCounts {
  const bytes = SIZES.refArray67 + N * SIZES.moveObject;
  return { allocations: N + 1, bytes, lines: lines(bytes) };
}

/** int[] buffer: the buffer exists already, so a move list allocates nothing; the bytes are those of the N moves in it. */
export function primitiveCounts(): MoveCounts {
  const bytes = SIZES.arrayHeader + N * WORD;
  return { allocations: 0, bytes, lines: lines(bytes) };
}

/**
 * The smallest possible layout of Move[N] followed by its N objects: array header (4 words), N references, one padding word, then N
 * objects of 6 words (3 header, from, to, padding), without gaps. The pick selects one reference and its object.
 */
export function objectsMap(pick: number): MemoryMapData {
  const words: MapWord[] = [];
  const headerWords = SIZES.arrayHeader / WORD;
  for (let i = 0; i < headerWords; i++) words.push({ t: 'hdr' });
  for (let i = 1; i <= N; i++) words.push({ t: 'ref', sel: i === pick });
  while ((words.length * WORD) % SIZES.alignment !== 0) words.push({ t: 'pad' });
  const objectWords = SIZES.moveObject / WORD;
  const objectStart = words.length + (pick - 1) * objectWords;
  for (let i = 1; i <= N; i++) {
    const sel = i === pick;
    words.push({ t: 'hdr', sel }, { t: 'hdr', sel }, { t: 'hdr', sel }, { t: 'val', sel }, { t: 'val', sel }, { t: 'pad', sel });
  }
  return { words, boxes: [[objectStart, objectStart + objectWords - 1]], hop: [headerWords + pick - 1, objectStart], more: false };
}

/** The start of the int[] buffer: array header, the N moves, then free buffer up to the end of the third cache line. */
export function primitivesMap(pick: number): MemoryMapData {
  const words: MapWord[] = [];
  const headerWords = SIZES.arrayHeader / WORD;
  for (let i = 0; i < headerWords; i++) words.push({ t: 'hdr' });
  for (let i = 1; i <= N; i++) words.push({ t: 'val', sel: i === pick });
  while (words.length % WORDS_PER_LINE !== 0) words.push({ t: 'free' });
  return { words, boxes: [[headerWords + pick - 1, headerWords + pick - 1]], more: true };
}

/** The rows of a map: one per cache line, plus one for the three dots. */
export const mapRows = (m: MemoryMapData): number => Math.ceil(m.words.length / WORDS_PER_LINE) + (m.more ? 1 : 0);

// ---------- the walk: undo data, objects, frames ----------

export type CellState = 'write' | 'read' | 'live' | 'stale' | 'empty';

export interface Slot {
  /** Last four hex digits of the hash that the make saved. */
  hash: string;
  /** The move as four hex digits. */
  move: string;
  /** The side that made the move (0 or 1). */
  mover: number;
  /** The saved mask; always 0 in this example. */
  mask: number;
  /** The distance sums before the make: what the incremental evaluation saves in its frame. */
  sums: [number, number];
  state: CellState;
}

export interface Obj {
  id: number;
  live: boolean;
  /** Created or dropped in this step. */
  fresh: boolean;
}

export interface Walk {
  step: number;
  /** Nesting depth of the walk: 0 at the root, 2 at a leaf. */
  depth: number;
  slots: (Slot | null)[];
  objects: Obj[];
  allocations: number;
  /** Evaluations done so far by the full way: one per leaf reached. */
  evaluations: number;
  /** Makes so far: the pieces that the incremental way updated. */
  updates: number;
  leaf: boolean;
}

/** The undo slot that an operation uses: a make writes the slot of the ply it starts from, an unmake reads the slot of the ply it returns to. */
export function slotPly(step: number): number {
  const s = DATA.steps[step]!;
  return s.op === 'make' ? s.ply - 1 : s.ply;
}

/** The state of the walk after `step` operations (0 to LAST). */
export function walkAt(step: number): Walk {
  const slots: (Slot | null)[] = Array.from({ length: SLOTS }, () => null);
  const objects: Obj[] = [];
  let depth = 0;
  let allocations = 0;
  let evaluations = 0;
  let updates = 0;
  for (let i = 1; i <= step; i++) {
    const s = DATA.steps[i]!;
    const prev = DATA.steps[i - 1]!;
    for (const x of slots) if (x && x.state !== 'stale') x.state = 'live';
    for (const o of objects) o.fresh = false;
    if (s.op === 'make') {
      slots[depth] = { hash: prev.hash.slice(-4), move: hex4(s.move), mover: prev.stm, mask: 0, sums: [prev.s0.dsum, prev.s1.dsum], state: 'write' };
      allocations++;
      objects.push({ id: allocations, live: true, fresh: true });
      depth++;
      updates++;
      if (depth === 2) evaluations++;
    } else {
      depth--;
      slots[depth]!.state = 'read';
      const top = [...objects].reverse().find((o) => o.live);
      if (top) {
        top.live = false;
        top.fresh = true;
      }
    }
  }
  slots.forEach((x, i) => {
    if (x && x.state === 'live' && i >= depth) x.state = 'stale';
  });
  return { step, depth, slots, objects, allocations, evaluations, updates, leaf: depth === 2 };
}

/** Bytes that the lane with objects has allocated for undo records up to this step. */
export const bytesAllocated = (w: Walk): number => w.allocations * SIZES.undoObject;

// ---------- view "eval": the pieces ----------

export interface Chip {
  cell: number;
  dist: number;
  /** The piece that the last operation moved or put back. */
  changed: boolean;
}

export interface PiecesAt {
  sides: [Chip[], Chip[]];
  /** The side whose piece the operation of this step touched; -1 at the root. */
  mover: number;
}

/** The pieces of both sides after `step` operations. They keep the order of the root position; a moved piece changes its number in place. */
export function piecesAt(step: number): PiecesAt {
  const root = DATA.steps[0]!;
  const sides: [Chip[], Chip[]] = [
    root.s0.pieces.map(([cell, dist]) => ({ cell, dist, changed: false })),
    root.s1.pieces.map(([cell, dist]) => ({ cell, dist, changed: false })),
  ];
  const undo: ({ chip: Chip; cell: number; dist: number } | null)[] = [];
  let last: Chip | null = null;
  let mover = -1;
  for (let i = 1; i <= step; i++) {
    const s = DATA.steps[i]!;
    if (s.op === 'make') {
      mover = DATA.steps[i - 1]!.stm;
      const chip = sides[mover]!.find((c) => c.cell === moveFrom(s.move)) ?? null;
      if (chip) {
        undo.push({ chip, cell: chip.cell, dist: chip.dist });
        chip.cell = moveTo(s.move);
        chip.dist = DATA.dist[mover as 0 | 1][chip.cell]!;
      } else undo.push(null);
      last = chip;
    } else {
      mover = s.stm;
      const u = undo.pop() ?? null;
      if (u) {
        u.chip.cell = u.cell;
        u.chip.dist = u.dist;
      }
      last = u?.chip ?? null;
    }
  }
  if (last && step > 0) last.changed = true;
  return { sides, mover };
}

export const sumsAt = (step: number): [number, number] => [DATA.steps[step]!.s0.dsum, DATA.steps[step]!.s1.dsum];

export interface FullEval {
  /** The full way computes at a leaf only (the step that made the move into depth 2). */
  computed: boolean;
  /** Pieces read so far: 20 per evaluation. */
  piecesRead: number;
}

export const fullEvalAt = (step: number): FullEval => {
  const w = walkAt(step);
  return { computed: w.leaf && DATA.steps[step]!.op === 'make', piecesRead: w.evaluations * 2 * PIECES };
};

export type UpdateLine =
  | { kind: 'idle' }
  /** `sum[A] += 0 - 6 -> 64` */
  | { kind: 'update'; side: 0 | 1; from: number; to: number; sum: number }
  | { kind: 'restore'; sums: [number, number] };

export function updateLineAt(step: number): UpdateLine {
  if (step === 0) return { kind: 'idle' };
  const s = DATA.steps[step]!;
  if (s.op === 'unmake') return { kind: 'restore', sums: sumsAt(step) };
  const side = DATA.steps[step - 1]!.stm as 0 | 1;
  const from = DATA.dist[side][moveFrom(s.move)]!;
  const to = DATA.dist[side][moveTo(s.move)]!;
  return { kind: 'update', side, from, to, sum: sumsAt(step)[side] };
}

// ---------- narration ----------

export type NarrationKind = 'root' | 'make' | 'leaf' | 'unmake' | 'reuse' | 'done';

/** The kind of narration of a step in the views `undo` and `eval` (section 6 of the design). */
export function narrationKind(view: 'undo' | 'eval', step: number): NarrationKind {
  if (step === 0) return 'root';
  if (step === LAST) return 'done';
  if (DATA.steps[step]!.op === 'unmake') return 'unmake';
  if (view === 'undo') return step === 4 ? 'reuse' : 'make';
  return walkAt(step).leaf ? 'leaf' : 'make';
}

export const narrationKey = (view: PlainView, step: number): string =>
  view === 'move' ? 'fig.javaplain.narr.move' : `fig.javaplain.narr.${view}.${narrationKind(view, step)}`;

/** The values for the placeholders of the narration of a state. */
export function narrationValues(view: PlainView, step: number, pick: number): Record<string, string | number> {
  if (view === 'move') return { k: pick, move: moveName(moveAtPick(pick)) };
  const s = DATA.steps[step]!;
  if (step === 0) return { ply: s.ply };
  const side = (s.op === 'make' ? DATA.steps[step - 1]!.stm : s.stm) as 0 | 1;
  return {
    move: moveName(s.move),
    ply: slotPly(step),
    df: DATA.dist[side][moveFrom(s.move)]!,
    dt: DATA.dist[side][moveTo(s.move)]!,
  };
}

/** The longest notation of a move among the legal moves and the walk: the narration ghosts reserve room for it. */
export const LONGEST_MOVE_NAME: string = [...DATA.legalMoves, ...DATA.steps.filter((s) => s.op !== 'root').map((s) => s.move)].map(moveName).reduce((a, b) => (b.length > a.length ? b : a));
