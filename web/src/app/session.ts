// The engine session: finds the engines this build ships, loads one (with progress), swaps it in, and remembers what
// went wrong. Framework-free; App subscribes through useSyncExternalStore.
import { isNetEngine } from '../engines/engine';
import type { Engine, EngineId, Progress } from '../engines/engine';
import type { EngineWarning } from '../engines/warnings';
import { createEngine, describeEngines } from '../engines/registry';
import type { EngineChoice, EnginesManifest } from '../engines/registry';
import type { AnalysisSource, NetModel, SwitchableEngine } from './switchable-engine';
import { DEFAULT_VARIANT, isDefault } from '../game/variant';
import type { Variant } from '../game/variant';

export type SessionPhase = 'discovering' | 'loading' | 'ready' | 'failed';

export interface SessionError {
  /** A protocol code (table-checksum, wasm-gc-unsupported, internal, ...), "server-unreachable", or "no-engine". */
  code: string;
  message: string;
}

export interface SessionState {
  phase: SessionPhase;
  choices: readonly EngineChoice[];
  current: EngineChoice | null;
  progress: Progress | null;
  error: SessionError | null;
  /** Net engines: what the engine in use reports (no model, fallback to random moves, ...). Empty for the other engines. */
  warnings: readonly EngineWarning[];
}

export interface SessionDeps {
  engine: SwitchableEngine;
  baseUrl: string;
  /** Registers the adapters and fetches engines.json. */
  loadManifest: () => Promise<EnginesManifest>;
  /** The nodes per move of both sides, at the time an engine is swapped in. */
  nodes: () => number;
  /** The game variant of the game that is on (default: adjacent jumps, two players). An engine that cannot play it is not offered. */
  variant?: () => Variant;
  /** Model and search settings for the net engines, read when a net engine is loaded and by applyNet. Without it a net engine gets no model. */
  net?: () => { model: NetModel; sims: number; batch?: number };
}

/**
 * The first available engine in registry order: java-wasmgc (ADR 0003), then cpp-wasm (the fallback without Wasm GC,
 * because it is faster than java-js), then java-js, then the server engines.
 */
export function defaultChoice(choices: readonly EngineChoice[]): EngineChoice | null {
  // The net engines are never the default: without a model they play random moves.
  return choices.find((c) => c.available && !isNetEngine(c.info.id)) ?? null;
}

/**
 * The engine that analyses while a net engine plays: the first available classical engine of this browser, in the order of
 * defaultChoice (java-wasmgc, cpp-wasm, java-js). The server engines cannot analyse, so they never stand in.
 */
export function analysisChoice(choices: readonly EngineChoice[]): EngineChoice | null {
  return choices.find((c) => c.available && !isNetEngine(c.info.id) && c.info.runtime !== 'server') ?? null;
}

export function toSessionError(e: unknown): SessionError {
  const code = typeof (e as { code?: unknown } | null)?.code === 'string' ? (e as { code: string }).code : 'internal';
  const message = e instanceof Error ? e.message : String(e);
  return { code, message };
}

export class EngineSession {
  private state: SessionState = { phase: 'discovering', choices: [], current: null, progress: null, error: null, warnings: [] };
  private manifest: EnginesManifest | null = null;
  private listeners = new Set<() => void>();
  private generation = 0;
  private readonly deps: SessionDeps;

  constructor(deps: SessionDeps) {
    this.deps = deps;
    // The warnings of the net engine in use follow its newGame and choose calls.
    deps.engine.subscribeWarnings(() => this.set({ warnings: deps.engine.warnings }));
    deps.engine.setAnalysisSource(() => this.analysisSource());
  }

  /** The classical engine that analyses for a net engine (docs/web-ui.md, "Analysis for the net engines"); null before start() or without one. */
  analysisSource(): AnalysisSource | null {
    const manifest = this.manifest;
    const choice = analysisChoice(this.state.choices);
    if (!manifest || !choice) return null;
    return { id: choice.info.id, create: () => createEngine(choice, manifest, this.deps.baseUrl) };
  }

  /**
   * The net engine of the Info page, for My-game mode: always `net-wasm` (CPU), never `net-webgpu`. The GPU forward pass rounds
   * differently, so visit counts could differ from the goldens, and a lost GPU process would add a failure mode the figures do not
   * need. Null before start(), when this browser cannot run it or the build does not ship it. The choice is looked up for the default
   * variant: the net engine of a snapshot does not depend on the game variant that is on now. `create` makes the engine, not yet initialised.
   */
  netSource(): AnalysisSource | null {
    const manifest = this.manifest;
    if (!manifest) return null;
    const choice = describeEngines(manifest, DEFAULT_VARIANT).find((c) => c.info.id === 'net-wasm');
    if (!choice?.available) return null;
    return { id: choice.info.id, create: () => createEngine(choice, manifest, this.deps.baseUrl) };
  }

  /**
   * What the net-hints advisor needs to make its own net-webgpu engine (netEngineFactory in guides/useNetHints.ts): the manifest and the
   * site root. Null before start(). The advisor owns the engine it makes: it is never the engine that plays and never shared with it.
   */
  advisorSource(): { manifest: EnginesManifest; baseUrl: string } | null {
    return this.manifest ? { manifest: this.manifest, baseUrl: this.deps.baseUrl } : null;
  }

  getState = (): SessionState => this.state;

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  private set(change: Partial<SessionState>): void {
    this.state = { ...this.state, ...change };
    for (const l of this.listeners) l();
  }

  /** Finds the engines and loads the default one. Resolves true when an engine is ready. */
  async start(): Promise<boolean> {
    try {
      this.manifest = await this.deps.loadManifest();
    } catch (e) {
      this.set({ phase: 'failed', error: toSessionError(e) });
      return false;
    }
    const choices = describeEngines(this.manifest, this.variant());
    this.set({ choices });
    const first = defaultChoice(choices);
    if (!first) {
      this.set({ phase: 'failed', error: { code: 'no-engine', message: 'no engine is available in this browser' } });
      return false;
    }
    return this.load(first);
  }

  private variant(): Variant {
    return this.deps.variant?.() ?? DEFAULT_VARIANT;
  }

  /**
   * The game variant changed: lists the engines again for it. If the engine in use cannot play the variant (the server engines play
   * the standard game only), the first engine that can is loaded; the promise then resolves true when that engine is ready. Returns
   * null when nothing has to be loaded.
   */
  setVariant(): Promise<boolean> | null {
    if (!this.manifest) return null; // start() has not run yet; it reads the variant itself
    const choices = describeEngines(this.manifest, this.variant());
    this.set({ choices });
    const current = this.state.current;
    if (current && choices.find((c) => c.info.id === current.info.id)?.available) return null;
    const next = defaultChoice(choices);
    if (!next) {
      this.set({ phase: 'failed', error: { code: 'no-engine', message: 'no engine can play this game variant' } });
      return Promise.resolve(false);
    }
    return this.load(next);
  }

  /** Switches to another engine; the game goes on with it. Resolves true when it is ready. */
  async select(id: EngineId): Promise<boolean> {
    const choice = this.state.choices.find((c) => c.info.id === id);
    if (!choice || !choice.available) return false;
    return this.load(choice);
  }

  /**
   * The model or the simulations changed: the net engine in use starts over with the new settings (a new newGame, no reload of the
   * engine). The warnings follow. Resolves true when the engine is ready; false when the engine in use is no net engine.
   */
  async applyNet(): Promise<boolean> {
    const current = this.state.current;
    if (!current || !isNetEngine(current.info.id) || this.state.phase !== 'ready') return false;
    const mine = ++this.generation;
    this.set({ phase: 'loading', progress: null, error: null });
    try {
      this.useNetSettings(current);
      const nodes = this.deps.nodes();
      const variant = this.variant();
      await this.deps.engine.newGame({ nodes: [nodes, nodes], ...(isDefault(variant) ? {} : { variant }) });
      if (mine !== this.generation) return false;
      this.set({ phase: 'ready' });
      return true;
    } catch (e) {
      if (mine === this.generation) this.set({ phase: 'failed', error: toSessionError(e) });
      return false;
    }
  }

  private useNetSettings(choice: EngineChoice): void {
    const net = isNetEngine(choice.info.id) ? this.deps.net?.() : undefined;
    this.deps.engine.setNet(net ? { model: net.model, netOptions: { sims: net.sims, ...(net.batch === undefined ? {} : { batch: net.batch }) } } : null);
  }

  /** Loads the current engine again (after a crash or a damaged table). */
  async restart(): Promise<boolean> {
    const choice = this.state.current ?? defaultChoice(this.state.choices);
    return choice ? this.load(choice) : this.start();
  }

  private async load(choice: EngineChoice): Promise<boolean> {
    const manifest = this.manifest;
    if (!manifest) return false;
    const mine = ++this.generation;
    this.set({ phase: 'loading', current: choice, progress: null, error: null });
    let engine: Engine | null = null;
    try {
      engine = createEngine(choice, manifest, this.deps.baseUrl);
      await engine.init((p) => {
        if (mine === this.generation) this.set({ progress: p });
      });
      if (mine !== this.generation) {
        engine.dispose(); // a newer load took over
        return false;
      }
      this.useNetSettings(choice);
      const nodes = this.deps.nodes();
      const variant = this.variant();
      const used = await this.deps.engine.use(engine, { nodes: [nodes, nodes], ...(isDefault(variant) ? {} : { variant }) });
      if (!used || mine !== this.generation) return false; // a newer load took over; use() disposed the stale engine
      this.set({ phase: 'ready', progress: null });
      return true;
    } catch (e) {
      engine?.dispose();
      if (mine === this.generation) {
        this.deps.engine.setNet(null); // the net settings of this failed load must not leak into the next newGame
        this.set({ phase: 'failed', progress: null, error: toSessionError(e) });
      }
      return false;
    }
  }
}
