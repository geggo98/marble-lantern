// The engine the game controller talks to. The controller takes one Engine for its whole life; the UI lets the player
// change the engine and the strength in the middle of a game. This wrapper forwards to the current engine and can swap it.
// The move history is the whole game state, so the new engine needs no copy of the position: it replays the history
// on the next call (design-notes.md, section 12, point 4).
import type { AnalysisResult, ChooseResult, Engine, EngineInfo, GameSetup, LoadReport, Progress } from '../engines/engine';
import { EngineFailure } from '../engines/protocol';

const PLACEHOLDER: EngineInfo = {
  id: 'cpp-wasm', labelKey: 'engine.cppWasm', runtime: 'wasm', language: 'C++', version: '', binaryBytes: 0, binaryGzipBytes: 0,
};

export class SwitchableEngine implements Engine {
  private inner: Engine | null = null;
  private nodes: [number, number] | null = null;

  /** The info of the current engine; a placeholder until the first engine is in use. */
  get info(): EngineInfo {
    return this.inner?.info ?? PLACEHOLDER;
  }

  get hasEngine(): boolean {
    return this.inner !== null;
  }

  /** Replaces the engine. The new engine must be initialised. The old one is disposed. */
  async use(engine: Engine, setup: GameSetup): Promise<void> {
    await engine.newGame(this.override(setup));
    const old = this.inner;
    this.inner = engine;
    old?.dispose();
  }

  /** From the next game on (and from the next engine move after `use`), both sides search with this budget. */
  setNodes(nodes: number): void {
    this.nodes = [nodes, nodes];
  }

  private override(setup: GameSetup): GameSetup {
    return this.nodes ? { nodes: this.nodes } : setup;
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

  async legalMoves(history: readonly number[]): Promise<number[]> {
    return await this.engine.legalMoves(history);
  }

  async choose(history: readonly number[], signal?: AbortSignal): Promise<ChooseResult> {
    return await this.engine.choose(history, signal);
  }

  async analyse(history: readonly number[], moves: readonly number[] | null, nodes: number, signal?: AbortSignal): Promise<AnalysisResult> {
    return await this.engine.analyse(history, moves, nodes, signal);
  }

  dispose(): void {
    this.inner?.dispose();
    this.inner = null;
  }
}
