// The model of V5 (alpha-beta search at depth 2, one step per root move): the data files `ab-tree-mid.json` and `ab-tree-start.json`
// (schema 2, written by ExplainerDataTest) turned into the steps, the readouts, the narration and the geometry of the overview and of the
// gauge. Pure functions; every number comes from the data file. Design: web/design (v5-mid-spec.md, sections 4 to 6).
// Every score in the file is a score for the side to move at the root ("mover"). Alpha is the best score the mover is already sure of.
import { CELLS } from '../../board/cells';
import { cellName } from '../../ui/board-model';

export const NEG_INF = '-infinity';
export type Alpha = number | typeof NEG_INF;

/** A searched reply: from, to, kind (0 step, 1 jump), score for the mover. */
export type Reply = readonly [from: number, to: number, kind: 0 | 1, score: number];

export interface RootMove {
  from: number;
  to: number;
  /** One shortest chain of cells of the piece, origin first; two entries for a step. */
  path: readonly number[];
  kind: 'jump' | 'step';
  /** The ordering key (distance gain); "pv" for the best move of the depth-1 pass, which is tried first. */
  gain: number | 'pv';
  /** Score of the root move in the depth-1 pass. */
  d1: number;
  visit1: number;
  visit2: number;
  /** Number of legal replies. */
  replies: number;
  alphaBefore: Alpha;
  alphaAfter: Alpha;
  /** The value if `bound` is "exact", else the highest value the move can still have. */
  value: number;
  bound: 'exact' | 'upper';
  cutoff: boolean;
  skipped: number;
  newBest: boolean;
  /** The chain of cells of the deciding reply, written only when that reply is a jump. */
  replyPath?: readonly number[];
  searched: readonly Reply[];
}

export interface AbData {
  schema: 2;
  meta: {
    spec: string;
    depth: number;
    perspective: 'mover';
    ply: number;
    sideToMove: number;
    game: string;
    history: readonly (readonly [number, number])[];
    nodes: number;
    iterationNodes: readonly [number, number];
    minimaxNodes: number;
    bestMove: { from: number; to: number; text: string };
    score: number;
    rootMoves: number;
    rootJumps: number;
    infinity: number;
  };
  cells: readonly number[];
  rootMoves: readonly RootMove[];
}

export type StepKind = 'idle' | 'first' | 'cut' | 'best' | 'result';
export type Verdict = 'first' | 'best' | 'cut' | 'result';

/** `(q,r)->(q,r)` in the axial coordinates of the engine, as in the data file (`meta.bestMove.text`). */
export function moveText(from: number, to: number): string {
  const a = CELLS[from]!;
  const b = CELLS[to]!;
  return `(${a.q},${a.r})->(${b.q},${b.r})`;
}

/** A score as text with the minus sign of typography; `-infinity` is "−∞". */
export function scoreText(value: Alpha | '+infinity'): string {
  if (value === NEG_INF) return '−∞';
  if (value === '+infinity') return '+∞';
  return value < 0 ? `−${-value}` : String(value);
}

/** A move in the notation of the app: `H5»L6` for a jump, `H5›I5` for a step. */
export const moveName = (from: number, to: number, jump: boolean): string => `${cellName(from)}${jump ? '»' : '›'}${cellName(to)}`;

export const rootName = (m: RootMove): string => moveName(m.from, m.to, m.kind === 'jump');
export const replyName = (r: Reply): string => moveName(r[0], r[1], r[2] === 1);

/** Steps: 0 (idle), 1 to N (one per root move), N + 1 (the result). */
export const stepCount = (data: AbData): number => data.rootMoves.length + 1;

export function stepKind(data: AbData, step: number): StepKind {
  const n = data.rootMoves.length;
  if (step <= 0) return 'idle';
  if (step > n) return 'result';
  if (step === 1) return 'first';
  return data.rootMoves[step - 1]!.cutoff ? 'cut' : 'best';
}

/** The root move of step 1 to N. */
export const moveAt = (data: AbData, step: number): RootMove | null => (step >= 1 && step <= data.rootMoves.length ? data.rootMoves[step - 1]! : null);

/** The root move that the search chose (the entry of the result step). */
export function bestRoot(data: AbData): RootMove {
  const { from, to } = data.meta.bestMove;
  return data.rootMoves.find((m) => m.from === from && m.to === to)!;
}

/** Alpha after the root move of `step`; at step 0 minus infinity, at the result step the final alpha. */
export function alphaAt(data: AbData, step: number): Alpha {
  if (step <= 0) return NEG_INF;
  return data.rootMoves[Math.min(step, data.rootMoves.length) - 1]!.alphaAfter;
}

export const verdictAt = (data: AbData, step: number): Verdict | null => {
  const kind = stepKind(data, step);
  return kind === 'idle' ? null : kind;
};

/** Calls of depth 2 so far: every root move that was searched counts once, plus its searched replies. */
export function visitedAt(data: AbData, step: number): number {
  let sum = 0;
  for (let i = 0; i < Math.min(step, data.rootMoves.length); i++) sum += 1 + data.rootMoves[i]!.searched.length;
  return sum;
}

/** The index of the reply that decides: the first one with the lowest score. A cut-off reply is always that one. */
export function decidingIndex(move: RootMove): number {
  let best = 0;
  move.searched.forEach((r, i) => {
    if (r[3] < move.searched[best]![3]) best = i;
  });
  return best;
}

export const decidingReply = (move: RootMove): Reply => move.searched[decidingIndex(move)]!;

/** The chain of cells that the deciding reply draws: the written `replyPath` for a jump, else the two cells of the step. */
export const replyChain = (move: RootMove): readonly number[] => {
  const r = decidingReply(move);
  return move.replyPath ?? [r[0], r[1]];
};

/** Notable steps (spec, section 6): step 0, 1, every new best move, the step after it, the last root move, the result. */
export function notableSteps(data: AbData): ReadonlySet<number> {
  const n = data.rootMoves.length;
  const set = new Set<number>([0, 1, n, n + 1]);
  data.rootMoves.forEach((m, i) => {
    if (m.newBest) {
      set.add(i + 1);
      if (i + 1 < n) set.add(i + 2);
    }
  });
  return set;
}

/** Page Up and Page Down: the previous or the next notable step; the ends when there is none. */
export function neighbourNotable(data: AbData, step: number, direction: -1 | 1): number {
  const notable = notableSteps(data);
  const last = stepCount(data);
  let s = step + direction;
  while (s > 0 && s < last && !notable.has(s)) s += direction;
  return Math.max(0, Math.min(last, s));
}

export const NOTABLE_MS = 1200;
export const QUICK_MS = 250;

/** Time that the playback stays on `step`: 1.2 s on a notable step, 0.25 s on any other one. */
export const stepDelayMs = (data: AbData, step: number): number => (notableSteps(data).has(step) ? NOTABLE_MS : QUICK_MS);

export interface Narration {
  key: StepKind;
  values: Record<string, string | number>;
}

/** The narration of a step: the template key (`fig.ab.narr.<key>`) and its values. `fmt` formats counts for the language. */
export function narration(data: AbData, step: number, fmt: (value: number) => string = String): Narration {
  const key = stepKind(data, step);
  const n = data.rootMoves.length;
  if (key === 'idle') return { key, values: { n: fmt(n), d1: fmt(data.meta.iterationNodes[0]) } };
  if (key === 'result') {
    const best = bestRoot(data);
    return {
      key,
      values: { move: rootName(best), score: scoreText(data.meta.score), visited: fmt(data.meta.iterationNodes[1]), total: fmt(data.meta.minimaxNodes) },
    };
  }
  const m = data.rootMoves[step - 1]!;
  const move = rootName(m);
  if (key === 'first') return { key, values: { move, replies: fmt(m.replies), value: scoreText(m.value) } };
  if (key === 'cut') {
    return { key, values: { move, reply: replyName(decidingReply(m)), bound: scoreText(m.value), alpha: scoreText(m.alphaBefore), skipped: fmt(m.skipped) } };
  }
  return { key, values: { move, replies: fmt(m.replies), value: scoreText(m.value), alphaOld: scoreText(m.alphaBefore), alphaNew: scoreText(m.alphaAfter) } };
}

/**
 * One narration per template with the longest values that the data holds for it, so that a figure can reserve the height of the longest
 * text of every template (spec, section 10). "Longest" is the longest sum of the value strings; the template text is the same for every
 * step of one template, so this is the longest rendered text in both languages.
 */
export function longestNarration(data: AbData, fmt: (value: number) => string = String): Narration[] {
  const best = new Map<StepKind, { narration: Narration; length: number }>();
  for (let step = 0; step < stepCount(data) + 1; step++) {
    const nar = narration(data, step, fmt);
    const length = Object.values(nar.values).reduce<number>((sum, v) => sum + String(v).length, 0);
    const held = best.get(nar.key);
    if (!held || length > held.length) best.set(nar.key, { narration: nar, length });
  }
  return [...best.values()].map((b) => b.narration);
}

// ---- the readout ----

/** The cell "this move": "= −10" for an exact value, "≤ −40" for a bound, a dash when there is no root move. */
export function valueCell(data: AbData, step: number): string {
  const m = moveAt(data, step);
  return m ? `${m.cutoff ? '≤' : '='} ${scoreText(m.value)}` : '–';
}

// ---- the overview: value band and work band ----

export interface OverviewLayout {
  compact: boolean;
  width: number;
  height: number;
  gutter: number;
  /** Pitch and drawn width of one column. */
  pitch: number;
  barW: number;
  top: number;
  valueH: number;
  /** One reply, and the smallest searched block. */
  unit: number;
  minBlock: number;
  workTop: number;
  workH: number;
  maxReplies: number;
  vMin: number;
  vMax: number;
  x: (index: number) => number;
  y: (value: number) => number;
}

/** The geometry of the overview for the whole example, so that its view box does not depend on the step (spec, section 10). */
export function overviewLayout(data: AbData, compact: boolean): OverviewLayout {
  const moves = data.rootMoves;
  const n = moves.length;
  const width = compact ? 260 : 640;
  const gutter = 30;
  const pitch = (width - gutter - 2) / n;
  const barW = Math.max(pitch * 0.72, 1.6);
  const top = 8;
  const valueH = compact ? 52 : 60;
  const unit = compact ? 1.5 : 1.6;
  const minBlock = compact ? 2.5 : 3;
  const workTop = top + valueH + 12;
  const maxReplies = Math.max(...moves.map((m) => m.replies));
  const workH = maxReplies * unit;
  const values = moves.map((m) => m.value);
  const vMax = Math.max(...values);
  const vMin = Math.min(...values);
  const span = Math.max(10, vMax - vMin);
  return {
    compact,
    width,
    height: workTop + workH + 14,
    gutter,
    pitch,
    barW,
    top,
    valueH,
    unit,
    minBlock,
    workTop,
    workH,
    maxReplies,
    vMin,
    vMax,
    x: (i) => gutter + pitch * (i + 0.5),
    y: (v) => top + 8 + ((vMax - v) / span) * (valueH - 16),
  };
}

/** The alpha staircase after `done` root moves: a step up where a root move became the new best; `ahead` is the dotted line to the right. */
export function alphaPaths(data: AbData, done: number, L: OverviewLayout): { solid: string; ahead: string | null; label: { x: number; y: number; value: Alpha } } | null {
  const n = data.rootMoves.length;
  if (done <= 0) return null;
  let d = '';
  let a: Alpha | null = null;
  for (let i = 0; i < done; i++) {
    const an = data.rootMoves[i]!.alphaAfter;
    // The first root move always sets a number: alpha before it is minus infinity, after it the value of the first move.
    if (typeof an !== 'number') continue;
    if (a === null) d += `M${L.x(i)} ${L.y(an)}`;
    else if (an !== a) d += `H${L.x(i)}V${L.y(an)}`;
    a = an;
  }
  if (a === null) return null;
  d += `H${L.x(done - 1) + (done === n ? L.pitch / 2 : 0)}`;
  const ahead = done < n ? `M${L.x(done - 1)} ${L.y(a)}H${L.width - 1}` : null;
  const above = L.y(a) - L.top > 16;
  return { solid: d, ahead, label: { x: L.width - 4, y: above ? L.y(a) - 5 : L.y(a) + 14, value: a } };
}

// ---- the gauge of the close view ----

export interface GaugeLayout {
  width: number;
  height: number;
  pad: number;
  axisY: number;
  lo: number;
  hi: number;
  x: (value: number) => number;
}

/** The number line of the reply scores; the range is that of all replies of the example, so that the gauge does not rescale per step. */
export function gaugeLayout(data: AbData, compact: boolean): GaugeLayout {
  const scores = data.rootMoves.flatMap((m) => m.searched.map((r) => r[3]));
  const lo = Math.min(...scores);
  const hi = Math.max(...scores);
  const width = compact ? 236 : 400;
  const pad = 18;
  const span = Math.max(10, hi - lo);
  return { width, height: 60, pad, axisY: 40, lo, hi, x: (v) => pad + ((v - lo) / span) * (width - 2 * pad) };
}

/** The ticks on the axis of the gauge: the lowest, zero when it lies inside, the highest. */
export const gaugeTicks = (G: GaugeLayout): number[] => [...new Set([G.lo, 0 >= G.lo && 0 <= G.hi ? 0 : G.lo, G.hi])];

/** Alpha that the gauge shows for a step: the alpha before the move at steps 1 to N, the final alpha at the result, none at step 0. */
export function gaugeAlpha(data: AbData, step: number): Alpha {
  const m = moveAt(data, step);
  if (m) return m.alphaBefore;
  return step <= 0 ? NEG_INF : data.rootMoves[data.rootMoves.length - 1]!.alphaAfter;
}

/** The reply strip: one entry per legal reply in search order, reserved up to the largest count of the example. */
export function replyStrip(data: AbData, step: number): ('done' | 'min' | 'skip' | 'none')[] {
  const max = Math.max(...data.rootMoves.map((m) => m.replies));
  const m = moveAt(data, step);
  const cells: ('done' | 'min' | 'skip' | 'none')[] = [];
  for (let i = 0; i < max; i++) {
    if (!m || i >= m.replies) cells.push('none');
    else if (i === decidingIndex(m)) cells.push('min');
    else cells.push(i < m.searched.length ? 'done' : 'skip');
  }
  return cells;
}
