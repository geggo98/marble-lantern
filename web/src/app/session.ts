// The engine session: finds the engines this build ships, loads one (with progress), swaps it in, and remembers what
// went wrong. Framework-free; App subscribes through useSyncExternalStore.
import type { Engine, EngineId, Progress } from '../engines/engine';
import { createEngine, describeEngines } from '../engines/registry';
import type { EngineChoice, EnginesManifest } from '../engines/registry';
import type { SwitchableEngine } from './switchable-engine';

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
}

export interface SessionDeps {
  engine: SwitchableEngine;
  baseUrl: string;
  /** Registers the adapters and fetches engines.json. */
  loadManifest: () => Promise<EnginesManifest>;
  /** The nodes per move of both sides, at the time an engine is swapped in. */
  nodes: () => number;
}

/** The first available engine in registry order: cpp-wasm, then the Java engines, then the server engines. */
export function defaultChoice(choices: readonly EngineChoice[]): EngineChoice | null {
  return choices.find((c) => c.available) ?? null;
}

export function toSessionError(e: unknown): SessionError {
  const code = typeof (e as { code?: unknown } | null)?.code === 'string' ? (e as { code: string }).code : 'internal';
  const message = e instanceof Error ? e.message : String(e);
  return { code, message };
}

export class EngineSession {
  private state: SessionState = { phase: 'discovering', choices: [], current: null, progress: null, error: null };
  private manifest: EnginesManifest | null = null;
  private listeners = new Set<() => void>();
  private generation = 0;
  private readonly deps: SessionDeps;

  constructor(deps: SessionDeps) {
    this.deps = deps;
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
    const choices = describeEngines(this.manifest);
    this.set({ choices });
    const first = defaultChoice(choices);
    if (!first) {
      this.set({ phase: 'failed', error: { code: 'no-engine', message: 'no engine is available in this browser' } });
      return false;
    }
    return this.load(first);
  }

  /** Switches to another engine; the game goes on with it. Resolves true when it is ready. */
  async select(id: EngineId): Promise<boolean> {
    const choice = this.state.choices.find((c) => c.info.id === id);
    if (!choice || !choice.available) return false;
    return this.load(choice);
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
      const nodes = this.deps.nodes();
      await this.deps.engine.use(engine, { nodes: [nodes, nodes] });
      this.set({ phase: 'ready', progress: null });
      return true;
    } catch (e) {
      engine?.dispose();
      if (mine === this.generation) this.set({ phase: 'failed', progress: null, error: toSessionError(e) });
      return false;
    }
  }
}
