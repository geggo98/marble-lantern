// The engine the game controller talks to. The controller takes one Engine for its whole life; the UI lets the player
// change the engine and the strength in the middle of a game. This wrapper forwards to the current engine and can swap it.
// The move history is the whole game state, so the new engine needs no copy of the position: it replays the history
// on the next call (design-notes.md, section 12, point 4).
import type { AnalysisResult, ChooseResult, Engine, EngineInfo, EngineOutcome, GameSetup, LoadReport, Progress } from '../engines/engine';
import type { Variant } from '../game/variant';
import { EngineFailure } from '../engines/protocol';
import type { EngineWarning, NetOptions } from '../engines/warnings';

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
    this.notifyWarnings();
    return true;
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

  async analyse(history: readonly number[], moves: readonly number[] | null, nodes: number, signal?: AbortSignal, variant?: Variant): Promise<AnalysisResult> {
    return await this.engine.analyse(history, moves, nodes, signal, variant);
  }

  async outcome(history: readonly number[], variant?: Variant): Promise<EngineOutcome> {
    return await this.engine.outcome(history, variant);
  }

  dispose(): void {
    this.unsubscribeInner?.();
    this.unsubscribeInner = null;
    this.inner?.dispose();
    this.inner = null;
  }
}
