// The engine the game controller talks to. The controller takes one Engine for its whole life; the UI lets the player
// change the engine and the strength in the middle of a game. This wrapper forwards to the current engine and can swap it.
// The move history is the whole game state, so the new engine needs no copy of the position: it replays the history
// on the next call (design-notes.md, section 12, point 4).
import { isNetEngine } from '../engines/engine';
import type { AnalysisResult, ChooseResult, Engine, EngineId, EngineInfo, EngineOutcome, GameSetup, LoadReport, Progress } from '../engines/engine';
import type { Variant } from '../game/variant';
import { EngineFailure } from '../engines/protocol';
import type { EngineWarning, NetOptions } from '../engines/warnings';

/**
 * Where the analysis of a net engine comes from (docs/web-ui.md, "Analysis for the net engines"): the net engines cannot analyse,
 * so a classical engine of this browser does it in its own worker. `id` names that engine; `create` makes it, not yet initialised.
 */
export interface AnalysisSource {
  id: EngineId;
  create: () => Engine;
}

/** The classical engine that analyses while a net engine plays; created on the first analyse or evaluate3 call. */
interface Helper {
  id: EngineId;
  ready: Promise<Engine>;
  engine: Engine | null;
}

const abortError = (): Error => new DOMException('The analysis was aborted', 'AbortError');

/** Waits for `ready`, but rejects with an AbortError as soon as `signal` fires; `ready` itself goes on. */
function untilReady<T>(ready: Promise<T>, signal?: AbortSignal): Promise<T> {
  if (!signal) return ready;
  if (signal.aborted) return Promise.reject(abortError());
  return new Promise<T>((resolve, reject) => {
    const onAbort = (): void => reject(abortError());
    signal.addEventListener('abort', onAbort, { once: true });
    ready.then(
      (v) => {
        signal.removeEventListener('abort', onAbort);
        resolve(v);
      },
      (e: unknown) => {
        signal.removeEventListener('abort', onAbort);
        reject(e);
      },
    );
  });
}

const PLACEHOLDER: EngineInfo = {
  id: 'cpp-wasm', labelKey: 'engine.cppWasm', runtime: 'wasm', language: 'C++', version: '', binaryBytes: 0, binaryGzipBytes: 0,
};

export class SwitchableEngine implements Engine {
  private inner: Engine | null = null;
  private nodes: number[] | null = null;
  private net: { model: string | null; netOptions: NetOptions } | null = null;
  private warningListeners = new Set<() => void>();
  private unsubscribeInner: (() => void) | null = null;
  /** Counts the calls of use(): a call whose newGame ends after a newer call began is stale. */
  private useGeneration = 0;
  private analysisSource: (() => AnalysisSource | null) | null = null;
  private helper: Helper | null = null;

  /** The info of the current engine; a placeholder until the first engine is in use. */
  get info(): EngineInfo {
    return this.inner?.info ?? PLACEHOLDER;
  }

  get hasEngine(): boolean {
    return this.inner !== null;
  }

  /** Net engines: the warnings of the engine in use (empty for the others). */
  get warnings(): readonly EngineWarning[] {
    return this.inner?.warnings ?? [];
  }

  /** Calls the listener when the warnings of the engine in use change, also when the engine is replaced. Returns the unsubscribe function. */
  subscribeWarnings(listener: () => void): () => void {
    this.warningListeners.add(listener);
    return () => this.warningListeners.delete(listener);
  }

  private notifyWarnings = (): void => {
    for (const l of this.warningListeners) l();
  };

  /**
   * Replaces the engine. The new engine must be initialised. The old one is disposed. Resolves true when the engine is in use.
   * A slow newGame (a net engine loads a model) can end after a newer use() began: then this engine is stale, it is disposed
   * and the newer choice stays; the result is false.
   */
  async use(engine: Engine, setup: GameSetup): Promise<boolean> {
    const mine = ++this.useGeneration;
    await engine.newGame(this.override(setup));
    if (mine !== this.useGeneration) {
      engine.dispose();
      return false;
    }
    const old = this.inner;
    this.unsubscribeInner?.();
    this.inner = engine;
    this.unsubscribeInner = engine.subscribeWarnings?.(this.notifyWarnings) ?? null;
    old?.dispose();
    // a classical engine analyses itself: the helper's worker and table are freed; a net engine after a net engine keeps it
    if (!isNetEngine(engine.info.id)) this.dropHelper();
    else if (this.helper?.engine === null) this.dropHelper(); // a failed or unfinished start is tried again for this engine
    this.notifyWarnings();
    return true;
  }

  /**
   * The source of the analysis engine for the net engines. Without it (or when it returns null: no classical engine in this
   * browser) a net engine answers analyse and evaluate3 itself, that is with the code unsupported.
   */
  setAnalysisSource(source: (() => AnalysisSource | null) | null): void {
    this.analysisSource = source;
    this.dropHelper();
  }

  /**
   * The engine whose scores analyse and evaluate3 return: the engine in use, or for a net engine the classical engine of the
   * analysis source. Null while no engine is in use. Caches of analysis results key on it.
   */
  get analysisId(): EngineId | null {
    const inner = this.inner;
    if (!inner) return null;
    if (!isNetEngine(inner.info.id)) return inner.info.id;
    return this.helper?.id ?? this.analysisSource?.()?.id ?? inner.info.id;
  }

  /** True while a classical analysis engine runs beside the net engine (for tests and the docs' cost figures). */
  get hasAnalysisEngine(): boolean {
    return this.helper?.engine != null;
  }

  /** The engine that answers analyse and evaluate3: the engine in use, or the helper for a net engine. */
  private analyser(signal?: AbortSignal): Promise<Engine> {
    const inner = this.engine;
    if (!isNetEngine(inner.info.id)) return Promise.resolve(inner);
    let helper = this.helper;
    if (!helper) {
      const source = this.analysisSource?.() ?? null;
      if (!source) return Promise.resolve(inner);
      helper = this.startHelper(source);
    }
    return untilReady(helper.ready, signal);
  }

  private startHelper(source: AnalysisSource): Helper {
    const start = async (helper: Helper): Promise<Engine> => {
      const engine = source.create();
      try {
        await engine.init();
      } catch (e) {
        engine.dispose();
        throw e; // kept in `ready` until the next use(): a failed start is not repeated for every position
      }
      if (this.helper !== helper) {
        engine.dispose(); // dropped while it started: a classical engine plays now, or the page closes
        throw abortError();
      }
      helper.engine = engine;
      return engine;
    };
    const helper = { id: source.id, engine: null } as Partial<Helper> & { id: EngineId; engine: Engine | null };
    this.helper = helper as Helper;
    helper.ready = start(helper as Helper);
    helper.ready.catch(() => undefined); // a failure reaches the caller that waits; nobody else must see it as unhandled
    return helper as Helper;
  }

  private dropHelper(): void {
    const helper = this.helper;
    this.helper = null;
    helper?.engine?.dispose();
  }

  /**
   * Model and search settings for the net engines: every later newGame carries them, so does the one of `use`. null removes them,
   * and the setup then has no model fields (the classical requests stay as they were).
   */
  setNet(net: { model: string | null; netOptions: NetOptions } | null): void {
    this.net = net;
  }

  /** From the next game on (and from the next engine move after `use`), both sides search with this budget. */
  setNodes(nodes: number): void {
    this.nodes = [nodes, nodes];
  }

  private override(setup: GameSetup): GameSetup {
    const withNodes = this.nodes ? { ...setup, nodes: this.nodes } : setup;
    return this.net ? { ...withNodes, model: this.net.model, netOptions: this.net.netOptions } : withNodes;
  }

  private get engine(): Engine {
    if (!this.inner) throw new EngineFailure('internal', 'no engine is loaded');
    return this.inner;
  }

  async init(onProgress?: (p: Progress) => void): Promise<LoadReport> {
    return await this.engine.init(onProgress);
  }

  async newGame(setup: GameSetup): Promise<void> {
    return await this.engine.newGame(this.override(setup));
  }

  async legalMoves(history: readonly number[], variant?: Variant): Promise<number[]> {
    return await this.engine.legalMoves(history, variant);
  }

  async choose(history: readonly number[], signal?: AbortSignal, variant?: Variant): Promise<ChooseResult> {
    return await this.engine.choose(history, signal, variant);
  }

  /** Scores of the engine in use; while a net engine plays, of the classical analysis engine (analysisId). */
  async analyse(history: readonly number[], moves: readonly number[] | null, nodes: number, signal?: AbortSignal, variant?: Variant): Promise<AnalysisResult> {
    const analyser = await this.analyser(signal);
    return await analyser.analyse(history, moves, nodes, signal, variant);
  }

  /** The static three-player vector, from the same engine as analyse; rejects with unsupported for an engine that has none. */
  async evaluate3(history: readonly number[], variant?: Variant): Promise<number[]> {
    const inner = await this.analyser();
    if (!inner.evaluate3) throw new EngineFailure('unsupported', 'this engine has no static evaluation vector');
    return await inner.evaluate3(history, variant);
  }

  async outcome(history: readonly number[], variant?: Variant): Promise<EngineOutcome> {
    return await this.engine.outcome(history, variant);
  }

  dispose(): void {
    this.dropHelper();
    this.unsubscribeInner?.();
    this.unsubscribeInner = null;
    this.inner?.dispose();
    this.inner = null;
  }
}
