// The game controller: history, side to move, outcome, undo, new game, engine-versus-engine mode.
// Framework-free. The UI subscribes and renders the state.
import type { ChooseResult, Engine, GameSetup } from '../engines/engine';
import { applyMove, initialOwners, outcomeOf, replay, sideToMove } from './board-state';
import type { Outcome, Side } from './board-state';

export type GameMode = 'human-vs-engine' | 'engine-vs-engine';

export interface GameState {
  history: readonly number[];
  /** owner[cell]: 0, 1 or -1. A fresh copy on every change. */
  owner: Int8Array;
  sideToMove: Side;
  /** Legal moves of the side to move; empty when the game is over. */
  legal: readonly number[];
  outcome: Outcome | null;
  /** True while an engine search runs. */
  thinking: boolean;
  /** The result of the last engine move, for the figures card. */
  lastEngine: (ChooseResult & { side: Side }) | null;
  /** Set when an engine call failed; the game stays where it was. */
  error: Error | null;
}

export interface GameOptions {
  engine: Engine;
  mode: GameMode;
  /** Human-vs-engine only: the side the human plays. Default 1 (the bottom start corner). */
  humanSide?: Side;
  /** Node budget per move for side 0 and side 1. */
  nodes: [number, number];
}

export class IllegalMoveError extends Error {
  constructor(move: number) {
    super(`illegal move ${move}`);
    this.name = 'IllegalMoveError';
  }
}

export class GameController {
  private readonly engine: Engine;
  private mode: GameMode;
  private humanSide: Side;
  private setup: GameSetup;
  private current: GameState;
  private listeners = new Set<(state: GameState) => void>();
  private abort: AbortController | null = null;
  /** Bumped by newGame, undo and cancel; an engine reply from an older generation is dropped. */
  private generation = 0;
  private busy = false;
  private run: Promise<void> | null = null;

  constructor(options: GameOptions) {
    this.engine = options.engine;
    this.mode = options.mode;
    this.humanSide = options.humanSide ?? 1;
    this.setup = { nodes: options.nodes };
    this.current = this.stateFor([], [], null, null);
  }

  get state(): GameState {
    return this.current;
  }

  subscribe(listener: (state: GameState) => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  isEngineTurn(): boolean {
    const s = this.current;
    if (s.outcome) return false;
    return this.mode === 'engine-vs-engine' || s.sideToMove !== this.humanSide;
  }

  /** Starts a new game; in human-vs-engine mode with the engine on move, the engine plays its first move. */
  async newGame(options?: { mode?: GameMode; humanSide?: Side; nodes?: [number, number] }): Promise<void> {
    this.cancel();
    await this.idle();
    this.generation++;
    if (options?.mode) this.mode = options.mode;
    if (options?.humanSide !== undefined) this.humanSide = options.humanSide;
    if (options?.nodes) this.setup = { nodes: options.nodes };
    await this.engine.newGame(this.setup);
    await this.resetTo([], null);
    if (this.mode === 'human-vs-engine') await this.playEngineTurns();
  }

  /** The human plays a move; the engine answers. Rejects with IllegalMoveError if the move is not legal. */
  async play(move: number): Promise<void> {
    if (this.busy || this.current.thinking) throw new Error('the engine is thinking');
    if (this.mode !== 'human-vs-engine' || this.isEngineTurn() || this.current.outcome) {
      throw new Error('it is not the human\'s turn');
    }
    if (!this.current.legal.includes(move)) throw new IllegalMoveError(move);
    this.commit(move);
    await this.refreshLegal();
    await this.playEngineTurns();
  }

  /** Engine-versus-engine: plays until the game ends, an error occurs or cancel() is called. */
  async runEngineVsEngine(): Promise<void> {
    if (this.mode !== 'engine-vs-engine') throw new Error('not in engine-versus-engine mode');
    await this.playEngineTurns();
  }

  /** Plays exactly one engine move if the engine is on turn. */
  async stepEngine(): Promise<void> {
    await this.playEngineTurns(1);
  }

  /** Stops a running search. The engine restarts its worker; the game state stays. */
  cancel(): void {
    this.abort?.abort();
  }

  /**
   * Takes back the last move of the human and everything after it (human-vs-engine), or the last move
   * (engine-versus-engine). The engine drops its players (newGame) and replays the history on the next search.
   */
  async undo(): Promise<void> {
    this.cancel();
    await this.idle();
    this.generation++;
    const history = [...this.current.history];
    if (history.length === 0) return;
    if (this.mode === 'human-vs-engine') {
      // Remove moves until the human is to move again, and at least one.
      do {
        history.pop();
      } while (history.length > 0 && sideToMove(history.length) !== this.humanSide);
    } else {
      history.pop();
    }
    await this.engine.newGame(this.setup);
    await this.resetTo(history, null);
  }

  // ---- internals ----

  private async idle(): Promise<void> {
    while (this.run) await this.run;
  }

  private async playEngineTurns(maxMoves = Infinity): Promise<void> {
    if (this.busy) return;
    this.busy = true;
    const run = this.engineLoop(maxMoves);
    this.run = run;
    try {
      await run;
    } finally {
      if (this.run === run) this.run = null;
    }
  }

  private async engineLoop(maxMoves: number): Promise<void> {
    const generation = this.generation;
    try {
      for (let n = 0; n < maxMoves && this.isEngineTurn(); n++) {
        const controller = new AbortController();
        this.abort = controller;
        this.patch({ thinking: true, error: null });
        let result: ChooseResult;
        try {
          result = await this.engine.choose(this.current.history, controller.signal);
        } catch (e) {
          if (generation !== this.generation || controller.signal.aborted) {
            this.patch({ thinking: false });
            return;
          }
          this.patch({ thinking: false, error: e instanceof Error ? e : new Error(String(e)) });
          return;
        } finally {
          if (this.abort === controller) this.abort = null;
        }
        if (generation !== this.generation) {
          this.patch({ thinking: false });
          return;
        }
        if (!this.current.legal.includes(result.move)) {
          this.patch({ thinking: false, error: new IllegalMoveError(result.move) });
          return;
        }
        const side = this.current.sideToMove;
        this.commit(result.move);
        this.patch({ thinking: false, lastEngine: { ...result, side } });
        await this.refreshLegal();
      }
    } finally {
      this.busy = false;
    }
  }

  private commit(move: number): void {
    const history = [...this.current.history, move];
    const owner = applyMove(this.current.owner.slice(), move);
    const outcome = outcomeOf(owner, history);
    this.current = this.stateFor(history, [], outcome, owner, this.current);
    this.emit();
  }

  /** Asks the engine for the legal moves of the side to move; none means the game is over. */
  private async refreshLegal(): Promise<void> {
    const state = this.current;
    if (state.outcome) return;
    const generation = this.generation;
    try {
      const legal = await this.engine.legalMoves(state.history);
      if (generation !== this.generation) return;
      this.patch(legal.length === 0
        ? { legal, outcome: { kind: 'no-moves', side: state.sideToMove } }
        : { legal });
    } catch (e) {
      this.patch({ error: e instanceof Error ? e : new Error(String(e)) });
    }
  }

  private async resetTo(history: number[], outcome: Outcome | null): Promise<void> {
    const owner = replay(history);
    this.current = this.stateFor(history, [], outcome ?? outcomeOf(owner, history), owner);
    this.emit();
    await this.refreshLegal();
  }

  private stateFor(
    history: readonly number[],
    legal: readonly number[],
    outcome: Outcome | null,
    owner: Int8Array | null,
    previous?: GameState,
  ): GameState {
    return {
      history,
      owner: owner ?? initialOwners(),
      sideToMove: sideToMove(history.length),
      legal,
      outcome,
      thinking: false,
      lastEngine: previous?.lastEngine ?? null,
      error: null,
    };
  }

  private patch(change: Partial<GameState>): void {
    this.current = { ...this.current, ...change };
    this.emit();
  }

  private emit(): void {
    for (const l of this.listeners) l(this.current);
  }
}
