// The second opinion of the neural net for the move guides: one position at a time, abortable, cached by model, variant and
// history. Framework-free like GuideAnalyser (analyser.ts); useNetHints.ts subscribes to it. It never throws: every failure becomes a state.
//
// Flow per position (design 4.4, plan "Ansatz 1"): the classical result comes in; when it is no race or mate and there are two or more
// moves, the advisor engine (net-webgpu, `sims` simulations) searches the position and reports the root moves with visits and values.
// needsProbe (divergence.ts) names the moves that need a like-with-like value; each gets a short search of the position after it (the
// probe engine, `probeSims` simulations). findDivergences then decides trap and special move.
import { scoreBand } from '../engines/analysis';
import type { ChooseResult, Engine } from '../engines/engine';
import { bestModel } from '../engines/net/selection';
import type { EngineWarning, ReasonCode } from '../engines/warnings';
import { isDefault, sideAt, variantKey } from '../game/variant';
import type { Variant } from '../game/variant';
import { findDivergences, needsProbe } from './divergence';
import { getNetLoss, setNetLoss } from './net-loss';
import type { ClassicalResult, Divergence, NetResult } from './divergence';

export type NetAdvisorStatus = 'idle' | 'loading' | 'thinking' | 'marks' | 'none' | 'skipped' | 'failed';

export interface NetAdvisorState {
  status: NetAdvisorStatus;
  /** The position that the result or the running call belongs to (netKey); null when idle. */
  key: string | null;
  trap: Divergence | null;
  special: Divergence | null;
  /**
   * Set for the rest of the session once the net cannot run on this device: the code of the reason (a key suffix of net.reason.<code>,
   * from the engine's warning, else `webgpu-lost`). The advisor then calls no engine any more.
   */
  lost: string | null;
}

export interface NetAdvisorDeps {
  /** Makes an engine that is not yet initialised: the net-webgpu engine. Called once for the search and once, when a probe is needed, for the probes. */
  createEngine: () => Engine;
  /** The model that plays a variant; null for none. Default: the id of bestModel(variant). */
  modelOf?: (variant: Variant) => string | null;
  /** Simulations of the search of the position. */
  sims?: number;
  /** Simulations of a probe. */
  probeSims?: number;
  /** Results kept (least recently used out). */
  cacheLimit?: number;
}

export const ADVISOR_SIMS = 800;
export const PROBE_SIMS = 100;

/** Cache and state key of a position. `scope` identifies the classical engine whose scores went in. */
export function netKey(scope: string, modelId: string | null, variant: Variant, history: readonly number[]): string {
  return `${scope}|${modelId ?? 'none'}@${variantKey(variant)}:${history.join(',')}`;
}

/** The net engine could not be started (init failed): the slot has dropped the engine, and the device is treated as lost for the session. */
class InitFailed extends Error {}

const isAbort = (e: unknown): boolean => (e as { name?: unknown } | null)?.name === 'AbortError';

interface Cached { status: 'marks' | 'none'; trap: Divergence | null; special: Divergence | null }

interface Slot { engine: Engine | null; inited: boolean; ready: Promise<void> | null; setupKey: string | null }

/** Why a net result cannot be used (backend random or a backend warning), as a reason code; null when the GPU net answered. */
function unusableReason(backend: string | undefined, warnings: readonly EngineWarning[] | undefined): string | null {
  const w = warnings ?? [];
  const bad = w.find((x) => x.code === 'backend-unavailable');
  if (backend === 'webgpu' && !bad) return null;
  const withReason = bad?.reason ?? w.find((x) => x.reason !== undefined)?.reason;
  return (withReason as ReasonCode | undefined) ?? 'webgpu-lost';
}

export class NetAdvisor {
  private state: NetAdvisorState = { status: 'idle', key: null, trap: null, special: null, lost: getNetLoss() };
  private readonly cache = new Map<string, Cached>();
  private readonly listeners = new Set<() => void>();
  private running: { key: string; controller: AbortController } | null = null;
  private readonly slots: Record<'main' | 'probe', Slot> = {
    main: { engine: null, inited: false, ready: null, setupKey: null },
    probe: { engine: null, inited: false, ready: null, setupKey: null },
  };
  private disposed = false;

  private readonly sims: number;
  private readonly probeSims: number;
  private readonly cacheLimit: number;
  private readonly modelOf: (variant: Variant) => string | null;

  constructor(private readonly deps: NetAdvisorDeps) {
    this.sims = deps.sims ?? ADVISOR_SIMS;
    this.probeSims = deps.probeSims ?? PROBE_SIMS;
    this.cacheLimit = deps.cacheLimit ?? 32;
    this.modelOf = deps.modelOf ?? ((v) => bestModel(v)?.id ?? null);
  }

  getState = (): NetAdvisorState => this.state;

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  /** The key that `request` gives its state for these arguments. */
  keyFor(scope: string, history: readonly number[], variant: Variant): string {
    return netKey(scope, this.modelOf(variant), variant, history);
  }

  /** Wants the net's opinion of the position after `history`. `classical` holds the classical scores of the legal moves. */
  request(scope: string, history: readonly number[], variant: Variant, classical: ClassicalResult): void {
    if (this.disposed) return;
    const key = this.keyFor(scope, history, variant);
    if (this.running?.key === key) return;
    this.abortRunning();
    const lost = this.state.lost ?? getNetLoss();
    if (lost !== null) {
      this.set({ status: 'failed', key, trap: null, special: null, lost });
      return;
    }
    if (classical.moves.length < 2 || classical.moves.length !== classical.scores.length || scoreBand(Math.max(...classical.scores)) !== 'ordinary') {
      this.set({ status: 'skipped', key, trap: null, special: null, lost });
      return;
    }
    const cached = this.cache.get(key);
    if (cached) {
      this.cache.delete(key); // most recently used goes last
      this.cache.set(key, cached);
      this.set({ ...cached, key, lost });
      return;
    }
    const modelId = this.modelOf(variant);
    if (modelId === null) {
      this.fail(key); // no model for this variant: a failure of this position, not of the device
      return;
    }
    const controller = new AbortController();
    this.running = { key, controller };
    const first = this.slots.main.ready === null || this.slots.main.setupKey !== `${modelId}@${variantKey(variant)}`;
    this.set({ status: first ? 'loading' : 'thinking', key, trap: null, special: null, lost });
    void this.run(key, controller, history, variant, modelId, classical);
  }

  /** Aborts a running search, for example because the human moved. */
  cancel(): void {
    if (!this.abortRunning()) return;
    this.set({ status: 'idle', key: null, trap: null, special: null, lost: this.state.lost });
  }

  /** Aborts, frees both engines and drops the listeners. The advisor does nothing afterwards. */
  dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    this.abortRunning();
    for (const slot of Object.values(this.slots)) {
      slot.engine?.dispose();
      slot.engine = null;
      slot.ready = null;
    }
    this.listeners.clear();
    this.cache.clear();
  }

  // ---- internals ----

  private current(key: string, controller: AbortController): boolean {
    return !this.disposed && this.running?.key === key && this.running.controller === controller && !controller.signal.aborted;
  }

  private async run(
    key: string,
    controller: AbortController,
    history: readonly number[],
    variant: Variant,
    modelId: string,
    classical: ClassicalResult,
  ): Promise<void> {
    const signal = controller.signal;
    try {
      const main = await this.prepare('main', variant, modelId, this.sims);
      if (!this.current(key, controller)) return;
      const early = this.warningsLost(main);
      if (early !== null) return this.lose(key, early);
      if (this.state.status !== 'thinking') this.set({ status: 'thinking', key, trap: null, special: null, lost: null });
      const res = await main.choose(history, signal, variant);
      if (!this.current(key, controller)) return;
      const lostReason = unusableReason(res.net?.backend, res.warnings ?? main.warnings);
      if (lostReason !== null) return this.lose(key, lostReason);
      const root = res.net?.root;
      if (!root?.values) return this.fail(key);
      const net: NetResult = { moves: root.moves, visits: root.visits, values: root.values };

      const probes = new Map<number, number>();
      for (const move of needsProbe(classical, net)) {
        const probeEngine = await this.prepare('probe', variant, modelId, this.probeSims);
        if (!this.current(key, controller)) return;
        const value = await this.probe(probeEngine, history, move, variant, signal);
        if (!this.current(key, controller)) return;
        if (value.lost !== null) return this.lose(key, value.lost);
        if (value.value !== null) probes.set(move, value.value);
      }

      const found = findDivergences(classical, net, probes);
      const result: Cached = {
        status: found.trap || found.special ? 'marks' : 'none',
        trap: found.trap ?? null,
        special: found.special ?? null,
      };
      this.running = null;
      this.remember(key, result);
      this.set({ ...result, key, lost: null });
    } catch (e) {
      if (!this.current(key, controller) || isAbort(e)) return;
      if (e instanceof InitFailed) return this.lose(key, 'load-failed'); // no retry with every position
      this.fail(key);
    }
  }

  /**
   * The value of `move` for the mover, from a short search of the position after it: minus the score of the side to move there. A finished
   * game needs no search: +1 when the mover won, -1 when the mover lost, 0 for a draw.
   * Null: the net did not search the child, so there is no value (findDivergences reads a missing probe as no trap).
   */
  private async probe(
    engine: Engine,
    history: readonly number[],
    move: number,
    variant: Variant,
    signal: AbortSignal,
  ): Promise<{ value: number | null; lost: null } | { value: 0; lost: string }> {
    const child = [...history, move];
    const outcome = await engine.outcome(child, variant);
    if (outcome.kind === 'win') return { value: outcome.winner === sideAt(history.length, variant) ? 1 : -1, lost: null };
    if (outcome.kind !== 'ongoing') return { value: 0, lost: null };
    const r: ChooseResult = await engine.choose(child, signal, variant);
    const lost = unusableReason(r.net?.backend, r.warnings ?? engine.warnings);
    if (lost !== null) return { value: 0, lost };
    // The net did no search here (one legal move, a race move): `score` is the constant 0, not a value. No probe, so no trap is decided on it.
    if (r.raceMove || r.net?.sims === 0 || !r.net?.root) return { value: null, lost: null };
    return { value: -r.score, lost: null };
  }

  /** A warning right after newGame that says the net cannot play: no need to search. */
  private warningsLost(engine: Engine): string | null {
    const w = engine.warnings ?? [];
    return w.some((x) => x.code === 'backend-unavailable' || x.code === 'fallback-random') ? unusableReason(undefined, w) : null;
  }

  /** Loads and starts the engine of a slot for this model and variant; later calls with the same setup wait for the same work. */
  private async prepare(kind: 'main' | 'probe', variant: Variant, modelId: string, sims: number): Promise<Engine> {
    const slot = this.slots[kind];
    const setupKey = `${modelId}@${variantKey(variant)}`;
    if (!slot.ready || slot.setupKey !== setupKey) {
      const before = slot.ready;
      slot.setupKey = setupKey;
      const ready = (async () => {
        if (before) await before.catch(() => undefined);
        if (this.disposed) throw new Error('advisor disposed');
        slot.engine ??= this.deps.createEngine();
        if (!slot.inited) {
          const engine = slot.engine;
          try {
            await engine.init();
          } catch {
            engine.dispose(); // the worker of a failed init must not stay behind
            if (slot.engine === engine) slot.engine = null;
            throw new InitFailed('the net engine did not start');
          }
          slot.inited = true;
        }
        await slot.engine.newGame({ nodes: [1, 1], ...(isDefault(variant) ? {} : { variant }), model: modelId, netOptions: { sims } });
      })();
      slot.ready = ready;
      ready.catch(() => {
        if (slot.ready === ready) { slot.ready = null; slot.setupKey = null; }
      });
    }
    await slot.ready;
    return slot.engine!;
  }

  private lose(key: string, reason: string): void {
    this.running = null;
    setNetLoss(reason);
    this.set({ status: 'failed', key, trap: null, special: null, lost: reason });
  }

  private fail(key: string): void {
    this.running = null;
    this.set({ status: 'failed', key, trap: null, special: null, lost: null });
  }

  private abortRunning(): boolean {
    const running = this.running;
    if (!running) return false;
    this.running = null;
    running.controller.abort();
    return true;
  }

  private remember(key: string, value: Cached): void {
    this.cache.set(key, value);
    while (this.cache.size > this.cacheLimit) {
      const oldest = this.cache.keys().next().value;
      if (oldest === undefined) break;
      this.cache.delete(oldest);
    }
  }

  private set(next: NetAdvisorState): void {
    this.state = next;
    for (const l of this.listeners) l();
  }
}
