// The core of the net engines (net-wasm and net-webgpu): the search loop of the TeaVM facade WebNet (teavm-net) around a
// NetBackend (IC1), and the fallback rules of IC4. It touches no worker global, so tests and node drive the code of the page.
//
// One move: begin(history) -> while n > 0 { inputs() -> backend.evaluate -> apply } -> best().
//
// The engine ALWAYS answers choose(). When no net can be used it plays a uniformly random legal move and reports the cause plus
// 'fallback-random' (warnings.ts):
//   no model selected                       -> no-model
//   the model may not play this variant     -> model-variant-mismatch
//   no backend runs, or it died             -> backend-unavailable
//   the model file or its weights failed    -> model-load-failed
// The one exception to "random": 'net-webgpu' with a usable model but no WebGPU (no adapter, device lost) plays with the CPU
// backend and reports backend-unavailable only. A working net is better than random moves.
// Warnings are recomputed on every newGame, so they clear when the cause is gone.
import { DEFAULT_VARIANT, campsOf, variantKey } from '../../game/variant';
import type { Variant } from '../../game/variant';
import type { AnalysisResult, ChooseResult, EngineOutcome, GameSetup, NetForwardReport } from '../engine';
import { EngineFailure } from '../protocol';
import { rowsArg, variantOf } from '../variant-args';
import { defaultBatch, DEFAULT_SIMS } from '../warnings';
import type { EngineWarning, NetOptions, ProbeResult, ReasonCode } from '../warnings';
import { BackendError } from './backend';
import type { NetBackend, NetOutputs } from './backend';
import { modelFits } from './models';
import type { ModelInfo } from './models';
import type { WebNetModule } from './webnet.d';
import type { Player } from '../../game/variant';
import { referenceForward, toAbsolute } from './reference-forward';
import { CELL_COUNT } from '../../board/cells';
import { moveFrom, moveTo } from '../engine';

export type BackendKind = 'wasm' | 'webgpu';

export interface NetCoreDeps {
  /** The TeaVM facade. One engine per module instance: use one core per module. */
  webnet: WebNetModule;
  /** The models of models.json. */
  models: readonly ModelInfo[];
  /** Creates a backend. `maxBatch` is the batch of the search; the wasm backend may allocate more. Throws a backend error. */
  createBackend(kind: BackendKind, maxBatch: number): Promise<NetBackend>;
  /** Whether WebGPU works. `fresh` asks the browser again (after a device loss). */
  probeWebGpu(fresh: boolean): Promise<ProbeResult>;
  /** The F32 safetensors bytes of a model (loadModel of models.ts). Throws on a missing, damaged or wrong file. */
  loadModel(info: ModelInfo): Promise<Uint8Array>;
  /** Uniform in [0, 1): picks the random fallback move. */
  random(): number;
  /** A 32-bit integer for the search seed of one game. */
  seed(): number;
  /** Lets the event loop run. Default: a macrotask. */
  yieldNow?(): Promise<void>;
  now?(): number;
}

/** Wasm batch buffers are allocated for this many positions; the search batch of the CPU engine is smaller. */
export const MAX_BATCH = 16;
/** The event loop gets a turn when a move has run this long without one. */
const YIELD_AFTER_MS = 40;

/** An "error <code> <message>" reply of the facade. */
class FacadeError extends Error {
  readonly code: string;
  constructor(code: string, message: string) {
    super(message);
    this.name = 'FacadeError';
    this.code = code;
  }
}

/** Splits an "ok ..." reply into fields; throws FacadeError for "error <code> <message>". */
function fields(reply: unknown): string[] {
  if (typeof reply !== 'string') throw new EngineFailure('internal', `the facade returned ${typeof reply}, not a string`);
  if (reply === 'ok') return [];
  if (reply.startsWith('ok ')) return reply.slice(3).split(' ');
  const m = /^error (\S+) ?(.*)$/s.exec(reply);
  if (m) throw new FacadeError(m[1]!, m[2]!);
  throw new EngineFailure('internal', `unexpected reply from the facade: ${reply.slice(0, 120)}`);
}

const numbers = (list: string | undefined): number[] => (list ? list.split(',').map(Number) : []);

/** Facade errors that are the caller's fault become protocol errors. */
function asFailure(e: unknown): unknown {
  if (!(e instanceof FacadeError)) return e;
  return new EngineFailure(e.code === 'illegal-history' ? 'illegal-history' : 'internal', `${e.code}: ${e.message}`);
}

/** Simulations per move: a finite integer of at least 1, else the default. Never throws. */
const sanitizeSims = (v: unknown): number => (typeof v === 'number' && Number.isInteger(v) && v >= 1 ? v : DEFAULT_SIMS);
/** Leaves per forward pass: a finite number (prepare() floors and clamps it to 1..16), else undefined for the default of the backend. */
const sanitizeBatch = (v: unknown): number | undefined => (typeof v === 'number' && Number.isFinite(v) ? v : undefined);

const abortError = (): Error => new DOMException('The search was aborted', 'AbortError');

/** Backend errors that say "this backend cannot work", as opposed to "this net is bad". */
const BACKEND_CODES: ReadonlySet<string> = new Set(['unavailable', 'device-lost', 'unsupported-model', 'memory', 'disposed']);
const codeOf = (e: unknown): string | undefined => (e as { code?: unknown } | null)?.code as string | undefined;
const isBackendError = (e: unknown): boolean => {
  const name = (e as { name?: unknown } | null)?.name;
  return (name === 'BackendError' || name === 'NetBackendError') && BACKEND_CODES.has(codeOf(e) ?? '');
};
const isAnyBackendError = (e: unknown): boolean => {
  const name = (e as { name?: unknown } | null)?.name;
  return name === 'BackendError' || name === 'NetBackendError';
};
const text = (e: unknown): string => (e instanceof Error ? e.message : String(e));

/**
 * The reason code and the English detail of an error: a lost device, a missing backend, a file that does not verify; anything else
 * gets `fallback`.
 */
function reasonOf(e: unknown, backend: BackendKind | null, fallback: ReasonCode): { reason: ReasonCode; detail: string } {
  const code = codeOf(e);
  const name = (e as { name?: unknown } | null)?.name;
  let reason = fallback;
  if (name === 'BackendError' || name === 'NetBackendError') {
    if (code === 'device-lost') reason = 'webgpu-lost';
    else if (code === 'unavailable') reason = backend === 'webgpu' ? 'webgpu-device-failed' : 'webassembly-unavailable';
    else if (code === 'unsupported-model' || code === 'invalid-weights') reason = 'load-failed';
  } else if (name === 'ModelError') {
    reason = code === 'model-checksum' || code === 'model-mismatch' ? 'verify-failed' : 'load-failed';
  }
  return { reason, detail: text(e) };
}

interface Plan {
  variantKey: string;
  mode: 'net' | 'random';
  /** The backend that plays; null in random mode. */
  kind: BackendKind | null;
  model: ModelInfo | null;
  warnings: EngineWarning[];
  sims: number;
  batch: number;
}

interface Held {
  kind: BackendKind;
  maxBatch: number;
  backend: NetBackend;
  /** Model id whose weights are loaded; null before load. */
  modelId: string | null;
}

/** The root of the search: the legal moves, their visits and the mean value of each (for the side that plays it), in one order. */
export interface NetRoot {
  moves: number[];
  visits: number[];
  /** Undefined when the engine file is older than the ninth field of best(). */
  values?: number[];
}

export interface NetMoveStats {
  backend: BackendKind | 'random';
  sims: number;
  batches: number;
  /** Absent for the random fallback and for a move without a search (one legal move). */
  root?: NetRoot;
}

export class NetCore {
  /** The backend this engine wants (net-wasm: 'wasm', net-webgpu: 'webgpu'). */
  readonly wanted: BackendKind;
  private readonly d: NetCoreDeps;
  private setup: { modelId: string | null; sims: number; batch: number | undefined; variant: Variant } | null = null;
  private plan: Plan | null = null;
  private held: Held | null = null;
  private bytes: { id: string; data: Uint8Array } | null = null;
  /** Set when the GPU device died during this game: the rest of the game uses the CPU. */
  private degraded: EngineWarning | null = null;
  private gpuNeedsFreshProbe = false;
  private seedOfGame = 0;

  constructor(wanted: BackendKind, deps: NetCoreDeps) {
    this.wanted = wanted;
    this.d = deps;
  }

  /** The warnings that hold now. */
  get warnings(): EngineWarning[] {
    return this.plan ? this.plan.warnings.map((w) => ({ ...w })) : [];
  }

  /** Starts a game: reads model and search settings of `setup`, loads what is needed. Never throws for a net problem: it becomes a warning. */
  async newGame(setup: GameSetup): Promise<EngineWarning[]> {
    const sims = sanitizeSims(setup.netOptions?.sims);
    this.setup = { modelId: setup.model ?? null, sims, batch: sanitizeBatch(setup.netOptions?.batch), variant: variantOf(setup) };
    this.degraded = null;
    this.seedOfGame = this.d.seed() | 0;
    this.plan = null;
    this.plan = await this.prepare(this.setup.variant);
    return this.warnings;
  }

  legalMoves(history: readonly number[], variant: Variant = DEFAULT_VARIANT): number[] {
    return this.legal(history, variant);
  }

  outcome(history: readonly number[], variant: Variant = DEFAULT_VARIANT): EngineOutcome {
    let f: string[];
    try {
      f = fields(this.d.webnet.outcome(history.join(','), variant.jump, String(variant.players), campsOf(variant), rowsArg(variant)));
    } catch (e) {
      throw asFailure(e);
    }
    if (f[0] === 'ongoing') return { kind: 'ongoing' };
    if (f[0] === 'draw') return { kind: 'draw' };
    const winner = Number(f[1]);
    if (f[0] === 'win' && Number.isInteger(winner) && winner >= 0 && winner < variant.players) return { kind: 'win', winner: winner as Player };
    throw new EngineFailure('internal', `unexpected outcome reply: ${f.join(' ')}`);
  }

  analyse(): AnalysisResult {
    throw new EngineFailure('unsupported', 'the net engines do not analyse positions');
  }

  async choose(history: readonly number[], signal?: AbortSignal, variant?: Variant): Promise<ChooseResult & { net: NetMoveStats; warnings: EngineWarning[] }> {
    if (!this.setup) throw new EngineFailure('internal', 'newGame was not called');
    const v = variant ?? this.setup.variant;
    if (signal?.aborted) throw abortError();
    const now = this.d.now ?? (() => performance.now());
    const t0 = now();
    let plan = await this.ensurePlan(v);
    let stats: NetMoveStats | undefined;
    let move = -1;
    let score = 0;
    let nodes = 0;
    // A net move can fail in a way that is a fallback case (dead GPU, rejected weights): then plan again, at most twice.
    for (let attempt = 0; attempt < 3 && stats === undefined; attempt++) {
      if (plan.mode === 'random') {
        move = this.randomMove(history, v);
        stats = { backend: 'random', sims: 0, batches: 0 };
        break;
      }
      try {
        const r = await this.netMove(plan, history, v, signal, now);
        move = r.move;
        score = r.score;
        nodes = r.nodes;
        stats = { backend: plan.kind as BackendKind, sims: r.sims, batches: r.batches, ...(r.root ? { root: r.root } : {}) };
      } catch (e) {
        if (isAbort(e) && signal?.aborted) throw e;
        if (e instanceof FacadeError && e.code === 'bad-weights-info') {
          plan = this.settle(plan, [
            { code: 'model-variant-mismatch', modelId: plan.model?.id, variant: v, reason: 'variant-not-supported', detail: e.message },
            { code: 'fallback-random', modelId: plan.model?.id, variant: v },
          ]);
        } else if (isAnyBackendError(e)) {
          plan = await this.degrade(plan, e, v);
        } else {
          throw asFailure(e);
        }
      }
    }
    if (!stats) throw new EngineFailure('internal', 'the net move could not be planned');
    return {
      move,
      score,
      depth: 0,
      nodes,
      ms: now() - t0,
      raceMove: false,
      rows1Requests: 0,
      net: stats,
      warnings: this.warnings,
    };
  }

  /**
   * The reference forward pass of the loaded expert model on the root position after `history`: the encoded planes, the norm of
   * every trunk layer, the logits and priors with and without the classical prior, the values. Two-player classic1 models only.
   * Rejects with unsupported when the position has one legal move or is over, or the model is not a two-player classic1 net; a model
   * that cannot be used (none selected, load failed) is an internal failure, never a random-move report.
   */
  async netForward(history: readonly number[], signal?: AbortSignal): Promise<NetForwardReport> {
    if (!this.setup) throw new EngineFailure('internal', 'newGame was not called');
    if (signal?.aborted) throw abortError();
    const v = this.setup.variant;
    const plan = await this.ensurePlan(v);
    const info = plan.model;
    if (plan.mode !== 'net' || !info) {
      throw new EngineFailure('internal', `failed: model: no usable model (${plan.warnings.map((w) => w.code).join(', ') || 'none selected'})`);
    }
    if (v.players !== 2 || info.players !== 2 || info.planeSet !== 'classic1') {
      throw new EngineFailure('unsupported', `the forward report covers two-player classic1 models, not ${info.id}`);
    }
    let bytes: Uint8Array;
    try {
      bytes = await this.modelBytes(info);
    } catch (e) {
      throw new EngineFailure('internal', `failed: model: ${text(e)}`);
    }
    let n: number;
    let input: Float32Array;
    try {
      n = Number(fields(this.d.webnet.begin(history.join(',')))[0]);
      if (n === 0) throw new EngineFailure('unsupported', 'the position has one legal move or is over: no search, no forward pass');
      // The first pending position of a search is the root (Mcts.java). The facade keeps the rest of its batch; the next begin drops it.
      input = this.d.webnet.inputs().slice();
    } catch (e) {
      throw asFailure(e);
    }
    const planes = input.length / (CELL_COUNT + 1);
    const out = referenceForward(bytes, input.subarray(0, (CELL_COUNT + 1) * planes));
    const mover: 0 | 1 = history.length % 2 === 1 ? 1 : 0;
    const absolute = (canonical: ArrayLike<number>, stride = 1, offset = 0): number[] => {
      const a = new Array<number>(CELL_COUNT);
      for (let c = 0; c < CELL_COUNT; c++) a[toAbsolute(c, mover)] = canonical[c * stride + offset] as number;
      return a;
    };
    const legal = this.legal(history, v);
    const priors = (logits: Float32Array): number[] => {
      const raw = legal.map((m) => (logits[toAbsolute(moveFrom(m), mover) * 2] as number) + (logits[toAbsolute(moveTo(m), mover) * 2 + 1] as number));
      const max = Math.max(...raw);
      const e = raw.map((x) => Math.exp(x - max));
      const sum = e.reduce((a, b) => a + b, 0);
      return e.map((x) => x / sum);
    };
    const withPrior = priors(out.policy);
    const netOnly = priors(out.policyNetOnly);
    const absValues = (rel: Float32Array): number[] => [0, 1].map((a) => rel[(a - mover + 2) % 2] as number);
    return {
      modelId: info.id,
      planes: Array.from({ length: planes }, (_, pl) => absolute(input, planes, pl)),
      layers: out.norms.map((norm, i) => ({ id: i === 0 ? 'conv_in' : `block${i - 1}`, norm: absolute(norm) })),
      logitsNetOnly: { from: absolute(out.policyNetOnly, 2, 0), to: absolute(out.policyNetOnly, 2, 1) },
      policy: legal.map((move, i) => ({ move, prior: withPrior[i] as number, priorNetOnly: netOnly[i] as number })),
      values: absValues(out.values),
      valuesNetOnly: absValues(out.valuesNetOnly),
      canonicalRotated: mover === 1,
    };
  }

  dispose(): void {
    this.held?.backend.dispose();
    this.held = null;
    this.bytes = null;
    this.plan = null;
  }

  // ---- planning ----

  private async ensurePlan(v: Variant): Promise<Plan> {
    if (this.plan && this.plan.variantKey === variantKey(v)) return this.plan;
    this.plan = await this.prepare(v);
    return this.plan;
  }

  private settle(plan: Plan, warnings: EngineWarning[]): Plan {
    // Keep what the plan already said (a missing GPU, say), then the degradation, then the new causes.
    const lead = this.degraded && !plan.warnings.includes(this.degraded) ? [this.degraded] : [];
    this.plan = { ...plan, mode: 'random', kind: null, warnings: [...plan.warnings, ...lead, ...warnings] };
    return this.plan;
  }

  /** The backend died in the middle of a game: warn, and go on with the CPU if the model allows it, else at random. */
  private async degrade(plan: Plan, e: unknown, v: Variant): Promise<Plan> {
    const dead = plan.kind ?? this.wanted;
    if (dead === 'webgpu') this.gpuNeedsFreshProbe = true;
    this.held?.backend.dispose();
    this.held = null;
    const fallback = dead === 'webgpu' ? 'wasm' : 'random';
    this.degraded = { code: 'backend-unavailable', backend: dead, modelId: plan.model?.id, variant: v, ...(dead === 'wasm' ? { reason: 'unknown' as const, detail: text(e) } : reasonOf(e, dead, 'unknown')), fallback };
    if (dead === 'wasm') {
      return this.settle(plan, [{ code: 'fallback-random', modelId: plan.model?.id, variant: v }]);
    }
    this.plan = await this.prepare(v);
    return this.plan;
  }

  private async prepare(v: Variant): Promise<Plan> {
    const s = this.setup as NonNullable<typeof this.setup>;
    const key = variantKey(v);
    const lead: EngineWarning[] = this.degraded ? [this.degraded] : [];
    /** A plan without a net: the causes, then fallback-random. */
    const random = (...causes: EngineWarning[]): Plan => ({
      variantKey: key,
      mode: 'random',
      kind: null,
      model: null,
      warnings: [...causes, { code: 'fallback-random', modelId: s.modelId ?? undefined, variant: v }],
      sims: s.sims,
      batch: 1,
    });
    if (s.modelId === null) return random(...lead, { code: 'no-model', variant: v });
    const info = this.d.models.find((m) => m.id === s.modelId);
    if (!info) return random(...lead, { code: 'model-load-failed', modelId: s.modelId, variant: v, reason: 'no-model-id', detail: `no model with the id ${s.modelId}` });
    if (!modelFits(info, v)) {
      return random(...lead, { code: 'model-variant-mismatch', modelId: info.id, variant: v, reason: 'variant-not-supported', detail: `the model ${info.id} does not play this variant` });
    }

    // The backends to try, best first. A dead GPU leaves the CPU.
    const tries: BackendKind[] = [];
    const warnings: EngineWarning[] = [...lead];
    if (this.wanted === 'webgpu' && !this.degraded) {
      const probe = await this.d.probeWebGpu(this.gpuNeedsFreshProbe);
      this.gpuNeedsFreshProbe = false;
      if (probe.available) tries.push('webgpu');
      else warnings.push({ code: 'backend-unavailable', backend: 'webgpu', fallback: 'wasm', modelId: info.id, variant: v, reason: probe.reason ?? 'webgpu-no-adapter', ...(probe.detail ? { detail: probe.detail } : {}) });
    }
    tries.push('wasm');

    let bytes: Uint8Array;
    try {
      bytes = await this.modelBytes(info);
    } catch (e) {
      return random(...warnings, { code: 'model-load-failed', modelId: info.id, variant: v, ...reasonOf(e, null, 'load-failed') });
    }

    for (const kind of tries) {
      const batch = Math.min(MAX_BATCH, Math.max(1, Math.floor(s.batch ?? defaultBatch(kind, s.sims))));
      let held: Held;
      try {
        held = await this.acquire(kind, batch, info, bytes);
      } catch (e) {
        if (isBackendError(e) || !isAnyBackendError(e)) {
          // The backend itself failed (no adapter, no flavour, out of memory) or something unforeseen: next backend.
          warnings.push({ code: 'backend-unavailable', backend: kind, fallback: kind === 'webgpu' ? 'wasm' : 'random', modelId: info.id, variant: v, ...reasonOf(e, kind, kind === 'webgpu' ? 'webgpu-device-failed' : 'webassembly-unavailable') });
          continue;
        }
        // The backend works but rejected this net.
        return random(...warnings, { code: 'model-load-failed', modelId: info.id, variant: v, ...reasonOf(e, kind, 'load-failed') });
      }
      try {
        const f = (r: unknown): string[] => fields(r);
        f(this.d.webnet.create(v.jump, String(v.players), s.sims, batch, this.seedOfGame, '', campsOf(v), rowsArg(v)));
        // setWeightsInfoRows names the board sizes of the network; a 4-row network keeps the two-argument call, which also serves an
        // engine-net file built before the export existed.
        if (info.rows !== '4' && this.d.webnet.setWeightsInfoRows) f(this.d.webnet.setWeightsInfoRows(info.planeSet, held.backend.valueWidth, info.rows));
        else f(this.d.webnet.setWeightsInfo(info.planeSet, held.backend.valueWidth));
      } catch (e) {
        if (e instanceof FacadeError && e.code === 'bad-weights-info') {
          return random(...warnings, { code: 'model-variant-mismatch', modelId: info.id, variant: v, reason: 'variant-not-supported', detail: e.message });
        }
        throw asFailure(e);
      }
      return { variantKey: key, mode: 'net', kind, model: info, warnings, sims: s.sims, batch };
    }
    // No backend at all.
    const last = warnings[warnings.length - 1];
    if (last?.code === 'backend-unavailable') last.fallback = 'random';
    return random(...warnings);
  }

  private async modelBytes(info: ModelInfo): Promise<Uint8Array> {
    if (this.bytes?.id === info.id) return this.bytes.data;
    this.bytes = null;
    const data = await this.d.loadModel(info);
    this.bytes = { id: info.id, data };
    return data;
  }

  /** A backend of `kind` with the weights of `info` loaded. Reuses the held one when it fits. */
  private async acquire(kind: BackendKind, batch: number, info: ModelInfo, bytes: Uint8Array): Promise<Held> {
    const maxBatch = kind === 'wasm' ? MAX_BATCH : batch;
    let held = this.held;
    if (held && (held.kind !== kind || held.maxBatch !== maxBatch)) {
      held.backend.dispose();
      held = this.held = null;
    }
    if (!held) {
      held = { kind, maxBatch, backend: await this.d.createBackend(kind, maxBatch), modelId: null };
      this.held = held;
    }
    if (held.modelId !== info.id) {
      held.modelId = null;
      try {
        await held.backend.load(bytes);
      } catch (e) {
        if (isBackendError(e)) {
          held.backend.dispose();
          this.held = null;
        }
        throw e;
      }
      held.modelId = info.id;
    }
    return held;
  }

  // ---- the move ----

  private legal(history: readonly number[], v: Variant): number[] {
    try {
      return numbers(fields(this.d.webnet.legalMoves(history.join(','), v.jump, String(v.players), campsOf(v), rowsArg(v)))[0]);
    } catch (e) {
      throw asFailure(e);
    }
  }

  private randomMove(history: readonly number[], v: Variant): number {
    const legal = this.legal(history, v);
    if (legal.length === 0) throw new EngineFailure('illegal-history', `illegal-history: the game is over after ${history.length} plies`); // the text of the net path (asFailure)
    const i = Math.min(legal.length - 1, Math.floor(this.d.random() * legal.length));
    return legal[i] as number;
  }

  private async netMove(
    plan: Plan,
    history: readonly number[],
    v: Variant,
    signal: AbortSignal | undefined,
    now: () => number,
  ): Promise<{ move: number; score: number; nodes: number; sims: number; batches: number; root?: NetRoot }> {
    const held = this.held;
    if (!held) throw new EngineFailure('internal', 'no backend is loaded');
    const web = this.d.webnet;
    let n = Number(fields(web.begin(history.join(',')))[0]);
    let batches = 0;
    let lastYield = now();
    while (n > 0) {
      if (signal?.aborted) throw abortError();
      const inputs = web.inputs();
      let out: NetOutputs;
      try {
        out = await held.backend.evaluate(inputs, n);
      } catch (e) {
        // A user abort wins. Any other error that is not a typed backend error (a wasm trap, a DOMException of a lost GPU that
        // arrived before the device.lost handler ran) means this backend cannot go on: report it as a backend failure so that
        // the fallback runs instead of the move rejecting.
        if (signal?.aborted) throw abortError();
        if (isAnyBackendError(e)) throw e;
        throw new BackendError(held.kind === 'webgpu' ? 'device-lost' : 'unavailable', `the ${held.kind} backend failed: ${text(e)}`, { cause: e });
      }
      batches++;
      n = Number(fields(web.apply(out.policyFrom, out.policyTo, out.values))[0]);
      if (n > 0 && now() - lastYield >= YIELD_AFTER_MS) {
        await (this.d.yieldNow ?? defaultYield)();
        lastYield = now();
      }
    }
    if (signal?.aborted) throw abortError();
    const b = fields(web.best());
    void v;
    // b: move rootValue sims nodes exactLeaves raceMove moves visits [values]. The lists are empty (and so absent from the split
    // when they are the last field) for a move without a search.
    const moves = numbers(b[6]);
    const root: NetRoot | undefined =
      moves.length > 0 ? { moves, visits: numbers(b[7]), ...(b[8] ? { values: numbers(b[8]) } : {}) } : undefined;
    return { move: Number(b[0]), score: Number(b[1]), sims: Number(b[2]), nodes: Number(b[3]), batches, ...(root ? { root } : {}) };
  }
}

const isAbort = (e: unknown): boolean => e instanceof DOMException && e.name === 'AbortError';
const defaultYield = (): Promise<void> => new Promise((resolve) => setTimeout(resolve, 0));

export type { NetOptions };
