// Figure J7 (topic java-web): one call of choose() from the page to a browser engine and back, across two boundaries. This file is the whole
// model of the figure: the seven steps, the counters, the code literals of the payload card and the rows of the comparison table. No text
// for people lives here (it is in about/i18n/{en,de}/java-web.json under `fig.boundary.*`); the code literals are quoted from the repository
// and are not translated. Where each number comes from: design spec j7-teavm, section 6; the C++ call count is pinned against
// web/src/engines/cpp/core.ts by boundary-model.test.ts.

export const VARIANTS = ['js', 'wasmgc', 'cpp'] as const;
export type Variant = (typeof VARIANTS)[number];
export const DEFAULT_VARIANT: Variant = 'wasmgc';

export const LANES = ['page', 'glue', 'engine'] as const;
export type Lane = (typeof LANES)[number];

/** Wall B style per variant: a dotted line (same heap), a double line (values are converted), a thick line (numbers only). */
export type WallKind = 'none' | 'convert' | 'slot';
export const WALL_KIND: Record<Variant, WallKind> = { js: 'none', wasmgc: 'convert', cpp: 'slot' };

export type Direction = 'in' | 'out';
export type ThreadState = 'idle' | 'free' | 'busy' | 'incall';
export const THREAD_STATES: readonly ThreadState[] = ['idle', 'free', 'busy', 'incall'];

export type How = 'none' | 'clone' | 'direct' | 'convert' | 'numbers';
export const HOWS: readonly How[] = ['none', 'clone', 'direct', 'convert', 'numbers'];
export const HOW_MARK: Record<How, string> = { none: '–', clone: '⧉', direct: '→', convert: '⇄', numbers: '#' };

export type RowId = 'call' | 'types' | 'bulk' | 'memory' | 'thread';

export const STEP_IDS = ['idle', 'post', 'queue', 'call', 'run', 'return', 'reply'] as const;
export type StepId = (typeof STEP_IDS)[number];
/** The stepper counts 1 to 6; step 0 is the idle state before the call. */
export const STEP_COUNT = STEP_IDS.length - 1;

export interface StepDef {
  readonly id: StepId;
  /** Lanes drawn as active; the others are dim (nothing is active at step 0, where no lane has a state). */
  readonly active: readonly Lane[];
  readonly wallA: Direction | null;
  readonly wallB: Direction | null;
  /** Thread state of the lanes page, glue, engine. */
  readonly states: readonly [ThreadState, ThreadState, ThreadState];
  /** Messages between page and worker so far, of 2. */
  readonly msgs: 0 | 1 | 2;
  /** 0: no call yet; 1: the call into the engine has started; 2: all calls of one choose() are done. */
  readonly callStage: 0 | 1 | 2;
  readonly worker: 'idle' | 'busy';
  /** The row of the comparison table that the step is about. */
  readonly row?: RowId;
}

export const STEPS: readonly StepDef[] = [
  { id: 'idle', active: [], wallA: null, wallB: null, states: ['idle', 'idle', 'idle'], msgs: 0, callStage: 0, worker: 'idle' },
  { id: 'post', active: ['page'], wallA: 'in', wallB: null, states: ['free', 'idle', 'idle'], msgs: 1, callStage: 0, worker: 'idle' },
  { id: 'queue', active: ['glue'], wallA: null, wallB: null, states: ['free', 'busy', 'idle'], msgs: 1, callStage: 0, worker: 'busy' },
  { id: 'call', active: ['glue', 'engine'], wallA: null, wallB: 'in', states: ['free', 'busy', 'busy'], msgs: 1, callStage: 1, worker: 'busy', row: 'call' },
  { id: 'run', active: ['engine'], wallA: null, wallB: null, states: ['free', 'incall', 'busy'], msgs: 1, callStage: 1, worker: 'busy', row: 'thread' },
  { id: 'return', active: ['glue', 'engine'], wallA: null, wallB: 'out', states: ['free', 'busy', 'idle'], msgs: 1, callStage: 2, worker: 'busy', row: 'types' },
  { id: 'reply', active: ['page'], wallA: 'out', wallB: null, states: ['idle', 'idle', 'idle'], msgs: 2, callStage: 2, worker: 'idle' },
];

/**
 * Calls into the engine module so far, as the readout writes them. TeaVM: one call of `choose` with six strings. C++: `_shw_game_new5` once,
 * `_shw_play` once per move (N), then the search call: N + 4 at the start of `_shw_choose`, N + 10 when `choose()` has returned (it holds when
 * the engine searched; without a result it is N + 7). Counted in the source, not measured.
 */
export const CALL_COUNT: Record<Variant, readonly [string, string, string]> = {
  js: ['0', '1', '1'],
  wasmgc: ['0', '1', '1'],
  cpp: ['0', 'N + 4', 'N + 10'],
};

export const callsText = (variant: Variant, step: number): string => CALL_COUNT[variant][STEPS[step]!.callStage];

/** The calls of the C++ `choose()` and `replay()` that the figure counts (see boundary-model.test.ts, which reads them from the source). */
export const CPP_CALL_SITES = { outsideLoop: 10, insideLoop: 1 } as const;

/** The catalog key of the narration of a step (the keys of the catalog differ for steps 3 and 5). */
export function narrationKey(step: number, variant: Variant): string {
  const id = STEPS[step]!.id;
  if (id === 'call') return `fig.boundary.narr.call.${variant}`;
  if (id === 'return') return `fig.boundary.narr.return.${variant === 'cpp' ? 'cpp' : 'teavm'}`;
  return `fig.boundary.narr.${id}`;
}

// ---- lane files ---------------------------------------------------------------------------------------------------------------------

export const PAGE_FILE = 'engines/client.ts';
export const GLUE_FILE: Record<Variant, string> = { js: 'java/core.ts', wasmgc: 'java/core.ts', cpp: 'cpp/core.ts' };
export const ENGINE_FILE: Record<Variant, string> = { js: 'engine-java.js', wasmgc: 'engine-java.wasm', cpp: 'engine-cpp' };

// ---- payload card -------------------------------------------------------------------------------------------------------------------

/** A line of the card: a code literal, or a text that is a catalog key (a comment, printed after `// `; the placeholder of step 0 is no comment). */
export type PayLine = { readonly code: string } | { readonly note: string; readonly comment: boolean };

export interface Payload {
  readonly how: How;
  readonly lines: readonly PayLine[];
}

const code = (text: string): PayLine => ({ code: text });
const note = (key: string, comment = true): PayLine => ({ note: `fig.boundary.pay.note.${key}`, comment });

/** What crosses a wall at this step, with the literal from the repository and the way it crosses. Sources: spec section 6, "Code literals". */
export function payload(step: number, variant: Variant): Payload {
  const teavmHow: How = variant === 'js' ? 'direct' : 'convert';
  switch (STEPS[step]!.id) {
    case 'idle':
      return { how: 'none', lines: [note('idle', false)] };
    case 'post':
      return { how: 'clone', lines: [code("worker.postMessage({ id, type: 'choose',\n  history: [m1, …, mN], variant })")] };
    case 'queue':
      return { how: 'none', lines: [code('handler.handle(request)\n  → core.choose(request.history, request.variant)')] };
    case 'call':
      return variant === 'cpp'
        ? {
            how: 'numbers',
            lines: [code('_shw_game_new5(jump, players, ranking, camps, rows)\n_shw_play(m1) … _shw_play(mN)\n_shw_side_to_move()  _shw_race_moves(id)\n_shw_choose(id)')],
          }
        : { how: teavmHow, lines: [code('m.choose("m1,…,mN", jump, players,\n  ranking, camps, rows)'), note('args')] };
    case 'run':
      return variant === 'cpp'
        ? { how: 'none', lines: [note('cpp'), code('int shw_choose(int player_id)')] }
        : { how: 'none', lines: [note('java'), code('WebCore.Choice c = CORE.choose(parse(history), …);')] };
    case 'return':
      return variant === 'cpp'
        ? {
            how: 'numbers',
            lines: [code('move = _shw_choose(id)\n_shw_has_result(id)  _shw_rows1_requests()\n_shw_last_score(id)  _shw_last_depth(id)\n_shw_last_nodes(id)  _shw_race_moves(id)')],
          }
        : { how: teavmHow, lines: [code('"ok <move> <score> <depth> <nodes> <ms>\n    <raceMove> <rows1Requests>"'), note('reply')] };
    case 'reply':
      return { how: 'clone', lines: [code('postMessage({ id, ok: true, result: { move, score,\n  depth, nodes, ms, raceMove, rows1Requests } })')] };
  }
}

// ---- comparison table ---------------------------------------------------------------------------------------------------------------

/** The code literal of a cell of the table; `memory` has one for C++ only. The text of each cell is the catalog key `fig.boundary.cell.<row>.<variant>`. */
export const TABLE_ROWS: readonly { readonly id: Exclude<RowId, 'thread'>; readonly code: Record<Variant, string> }[] = [
  { id: 'call', code: { js: 'import(url) → m.choose(…)', wasmgc: 'TeaVM.wasmGC.load(url)\n  .exports.choose(…)', cpp: 'createEngineCpp()\n  → m._shw_choose(id)' } },
  { id: 'types', code: { js: 'number, BigInt, string, typed array', wasmgc: 'number, BigInt, string, typed array', cpp: 'number (int, double, pointer)' } },
  { id: 'bulk', code: { js: '@JSByRef byte[] ← Int8Array', wasmgc: '@JSByRef(optional = true)', cpp: '_malloc → HEAPU8.set → fn(ptr, len) → _free' } },
  { id: 'memory', code: { js: '', wasmgc: '', cpp: 'HEAPU8.buffer' } },
];

/** The row of the table that a step marks, if any. */
export const rowOfStep = (step: number): RowId | undefined => STEPS[step]!.row;

export const ALSO = ['transfer', 'cancel', 'batch'] as const;
