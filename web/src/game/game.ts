// The game controller: history, side to move, outcome, undo, new game, engine-versus-engine mode.
// Framework-free. The UI subscribes and renders the state.
//
// The game has one seat per player (two or three, see variant.ts). A seat is a human or an engine. The side to move after
// `ply` plies is seat `ply % players`: a human seat accepts input through play(), an engine seat gets choose(). Engine
// moves in a row (three players with two engines) play one after another with a short pause between them, so that the
// person can follow the board.
import type { ChooseResult, Engine } from '../engines/engine';
import { applyMove, initialOwners, outcomeOf, replay } from './board-state';
import type { Outcome, Side } from './board-state';
import { DEFAULT_VARIANT, moveLimit } from './variant';
import type { Player, Variant } from './variant';

/** Legacy two-player modes; `seats` expresses every mode. */
export type GameMode = 'human-vs-engine' | 'engine-vs-engine';

/** The seats the controller plays. (setup.ts also knows `network`, which no controller plays yet.) */
export type ControllerSeat = 'human' | 'engine';

/** Pause between two engine moves in a row of a three-player game, in ms. */
export const ENGINE_PAUSE_MS = 350;

export interface GameState {
  history: readonly number[];
  /** owner[cell]: the seat 0, 1 or 2, or -1. A fresh copy on every change. */
  owner: Int8Array;
  sideToMove: Player;
  /** Legal moves of the side to move; empty when the game is over. */
  legal: readonly number[];
  outcome: Outcome | null;
  /** True while an engine search runs. */
  thinking: boolean;
  /** The result of the last engine move, for the figures card. */
  lastEngine: (ChooseResult & { side: Player }) | null;
  /** Set when an engine call failed; the game stays where it was. */
  error: Error | null;
  /** The variant of this game. */
  variant: Variant;
  /** Who plays each seat: `seats[side]`. */
  seats: readonly ControllerSeat[];
  /** The swap button may be offered: at most one human seat. With two human seats (hot-seat) swapping makes no sense. */
  canSwap: boolean;
}

export interface GameOptions {
  engine: Engine;
  /** Two-player shortcut: 'human-vs-engine' (default) or 'engine-vs-engine'. Ignored when `seats` is given. */
  mode?: GameMode;
  /** Human-vs-engine only: the side the human plays. Default 1 (the bottom start corner). */
  humanSide?: Side;
  /** Node budget per move for each seat (one entry per seat; the last entry repeats). */
  nodes: readonly number[];
  /** Who plays each seat; its length must equal the player count of `variant`. */
  seats?: readonly ControllerSeat[];
  /** Default: adjacent jumps, two players. */
  variant?: Variant;
  /** Pause between engine moves in a row, ms. Default: ENGINE_PAUSE_MS for three players, 0 for two (the demo runs at engine speed). */
  enginePauseMs?: number;
}

export interface NewGameOptions {
  mode?: GameMode;
  humanSide?: Side;
  nodes?: readonly number[];
  seats?: readonly ControllerSeat[];
  variant?: Variant;
}

export class IllegalMoveError extends Error {
  constructor(move: number) {
    super(`illegal move ${move}`);
    this.name = 'IllegalMoveError';
  }
}

/** Seats of a two-player game: the human on `humanSide`, the engine on the other side. */
export const twoPlayerSeats = (humanSide: Side): ControllerSeat[] => (humanSide === 0 ? ['human', 'engine'] : ['engine', 'human']);

export class GameController {
  private readonly engine: Engine;
  private variant: Variant;
  private seats: ControllerSeat[];
  /** The side the human played last in a two-player game; where a swap from a demo goes back to. */
  private lastHumanSide: Side;
  private nodes: readonly number[];
  private readonly pauseOption: number | undefined;
  private current: GameState;
  private listeners = new Set<(state: GameState) => void>();
  private abort: AbortController | null = null;
  /** Bumped by newGame, undo and cancel; an engine reply from an older generation is dropped. */
  private generation = 0;
  private busy = false;
  private run: Promise<void> | null = null;

  constructor(options: GameOptions) {
    this.engine = options.engine;
    this.variant = options.variant ?? DEFAULT_VARIANT;
    this.lastHumanSide = options.humanSide ?? 1;
    this.seats = options.seats
      ? this.checkedSeats(options.seats, this.variant)
      : options.mode === 'engine-vs-engine'
        ? ['engine', 'engine']
        : twoPlayerSeats(this.lastHumanSide);
    this.nodes = options.nodes;
    this.pauseOption = options.enginePauseMs;
    this.current = this.stateFor([], [], null, null);
  }

  get state(): GameState {
    return this.current;
  }

  /** `engine-vs-engine` when no seat is human, else `human-vs-engine`. */
  get mode(): GameMode {
    return this.seats.includes('human') ? 'human-vs-engine' : 'engine-vs-engine';
  }

  subscribe(listener: (state: GameState) => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  /** The seat of `side` is an engine and the game is not over. */
  isEngineTurn(): boolean {
    const s = this.current;
    if (s.outcome) return false;
    return this.seats[s.sideToMove] === 'engine';
  }

  /** A person is to move: a human seat is on turn and the game is not over. */
  isHumanTurn(): boolean {
    const s = this.current;
    return !s.outcome && this.seats[s.sideToMove] === 'human';
  }

  /**
   * Starts a new game; with an engine on move, the engine plays its first move. `seats`, `variant` and `nodes` replace the
   * current ones when given; the two-player shortcuts `mode` and `humanSide` set the seats of a two-player game.
   */
  async newGame(options?: NewGameOptions): Promise<void> {
    this.cancel();
    await this.idle();
    this.generation++;
    this.configure(options);
    await this.engine.newGame(this.engineSetup());
    await this.resetTo([], null);
    if (this.mode === 'human-vs-engine') await this.playEngineTurns();
  }

  /** A person plays a move; the engines answer. Rejects with IllegalMoveError if the move is not legal. */
  async play(move: number): Promise<void> {
    if (this.busy || this.current.thinking) throw new Error('the engine is thinking');
    if (!this.isHumanTurn()) throw new Error('it is not the human\'s turn');
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

  /**
   * Changes who plays which seat without touching the board; a running search is dropped (the engine restarts its worker).
   *
   * - Two players: the person takes over `humanSide` (default: the other side), the engine plays the other one. From an
   *   engine-versus-engine demo the person takes `humanSide` (default: the side played last). Pressing it again and again lets
   *   the engine play both sides in turn.
   * - Three players with one human seat: the human seat rotates to `humanSide` (default: the next seat); the board stays.
   * - Two or more human seats (hot-seat), or a game with no human and three players: not offered, see `canSwap`; throws.
   */
  async swapSides(humanSide?: Player): Promise<void> {
    if (!this.current.canSwap) throw new Error('swapping is not offered with two human seats');
    this.cancel();
    await this.idle();
    this.generation++;
    const humans = this.seats.flatMap((s, i) => (s === 'human' ? [i as Player] : []));
    const players = this.variant.players;
    const current = humans[0];
    const target: Player = humanSide ?? (current === undefined ? this.lastHumanSide : (((current + 1) % players) as Player));
    if (target >= players) throw new RangeError(`seat ${target} does not exist in a ${players}-player game`);
    this.seats = this.seats.map((_, i) => (i === target ? 'human' : 'engine'));
    if (players === 2) this.lastHumanSide = target as Side;
    this.patch({ thinking: false, error: null, seats: this.seats, canSwap: this.canSwapNow() });
    await this.playEngineTurns();
  }

  /** Stops a running search. The engine restarts its worker; the game state stays. */
  cancel(): void {
    this.abort?.abort();
  }

  /**
   * Takes back to the previous turn of a human seat: the last move is taken back, then every move until a human seat is to
   * move (so the engine moves after the person's move go with it). Two players, one human: the human's last move and the
   * engine's answer. Hot-seat: one ply when the previous seat was human, else the engine move and the human move before it.
   * With no human seat (a demo) the last move. The engine drops its players (newGame) and replays the history on its next search.
   */
  async undo(): Promise<void> {
    this.cancel();
    await this.idle();
    this.generation++;
    const history = [...this.current.history];
    if (history.length === 0) return;
    const anyHuman = this.seats.includes('human');
    const players = this.variant.players;
    do {
      history.pop();
    } while (anyHuman && history.length > 0 && this.seats[history.length % players] !== 'human');
    await this.engine.newGame(this.engineSetup());
    await this.resetTo(history, null);
  }

  /**
   * Replaces the game by a position given as a move list (a game link, or the browser's Back button): cancels a running
   * search, asks the engine whether the history is legal and, if so, continues with the engines when one is on move.
   * Rejects, and leaves the game as it was, when the engine refuses the history (code illegal-history).
   * `seats` and `variant` replace the current ones when given; `humanSide` (two players) puts the human on that side. Without
   * any of them a demo turns into a game with the human on the side played last.
   */
  async loadGame(history: readonly number[], options?: { humanSide?: Side; seats?: readonly ControllerSeat[]; variant?: Variant }): Promise<void> {
    this.cancel();
    await this.idle();
    const variant = options?.variant ?? this.variant;
    await this.engine.legalMoves(history, variant); // throws for an illegal history, before anything changes
    replay(history, variant); // the same check for the board, in case an engine does not validate
    this.cancel();
    await this.idle();
    this.generation++;
    const next: NewGameOptions = { ...(options?.variant ? { variant: options.variant } : {}) };
    if (options?.seats) next.seats = options.seats;
    else if (options?.humanSide !== undefined) next.humanSide = options.humanSide;
    else if (!this.seats.includes('human') && variant.players === 2) next.humanSide = this.lastHumanSide;
    this.configure(next);
    await this.engine.newGame(this.engineSetup());
    await this.resetTo([...history], null);
    if (this.mode === 'human-vs-engine') await this.playEngineTurns();
  }

  // ---- internals ----

  private checkedSeats(seats: readonly ControllerSeat[], variant: Variant): ControllerSeat[] {
    if (seats.length !== variant.players) throw new RangeError(`${seats.length} seats for ${variant.players} players`);
    return [...seats];
  }

  /** Applies the options of newGame and loadGame to the seats, the variant and the node budgets. */
  private configure(options: NewGameOptions | undefined): void {
    if (options?.variant) this.variant = options.variant;
    if (options?.nodes) this.nodes = options.nodes;
    if (options?.seats) {
      this.seats = this.checkedSeats(options.seats, this.variant);
    } else if (options?.mode === 'engine-vs-engine') {
      this.seats = this.variant.players === 2 ? ['engine', 'engine'] : ['engine', 'engine', 'engine'];
    } else if (this.variant.players === 2 && (options?.mode === 'human-vs-engine' || options?.humanSide !== undefined)) {
      this.seats = twoPlayerSeats(options.humanSide ?? this.lastHumanSide);
    } else if (this.seats.length !== this.variant.players) {
      throw new RangeError(`the seats of the game do not fit ${this.variant.players} players; pass seats`);
    }
    const human = this.seats.indexOf('human');
    if (this.variant.players === 2 && human >= 0 && this.seats.filter((s) => s === 'human').length === 1) this.lastHumanSide = human as Side;
  }

  private engineSetup(): { nodes: readonly number[]; variant: Variant } {
    return { nodes: this.nodes, variant: this.variant };
  }

  private canSwapNow(): boolean {
    return this.seats.filter((s) => s === 'human').length <= 1;
  }

  private pauseMs(): number {
    return this.pauseOption ?? (this.variant.players === 3 ? ENGINE_PAUSE_MS : 0);
  }

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
          result = await this.engine.choose(this.current.history, controller.signal, this.variant);
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
        // Another engine is next: leave the move on the board for a moment before the next search starts.
        if (n + 1 < maxMoves && this.isEngineTurn() && this.pauseMs() > 0) {
          const pause = new AbortController();
          this.abort = pause;
          await sleep(this.pauseMs(), pause.signal);
          if (this.abort === pause) this.abort = null;
          if (generation !== this.generation || pause.signal.aborted) return;
        }
      }
    } finally {
      this.busy = false;
    }
  }

  private commit(move: number): void {
    const history = [...this.current.history, move];
    const owner = applyMove(this.current.owner.slice(), move);
    const outcome = outcomeOf(owner, history, this.variant);
    this.current = this.stateFor(history, [], outcome, owner, this.current);
    this.emit();
  }

  /**
   * Asks the engine how the game stands and for the legal moves of the side to move. A win is the engine's word; a draw is
   * the move limit, a side without legal moves ("no-moves"), or any other draw the rules know (a repeated position).
   */
  private async refreshLegal(): Promise<void> {
    const state = this.current;
    if (state.outcome) return;
    const generation = this.generation;
    try {
      const verdict = await this.engine.outcome(state.history, this.variant);
      if (generation !== this.generation) return;
      if (verdict.kind === 'win') {
        this.patch({ legal: [], outcome: { kind: 'win', winner: verdict.winner } });
        return;
      }
      // Full ranking is an engine-level field so far (the URL and the controller do not carry it): a finished ranking is
      // reported as the win of its first seat until the controller shows ranks.
      if (verdict.kind === 'ranking') {
        this.patch({ legal: [], outcome: { kind: 'win', winner: verdict.ranking[0] } });
        return;
      }
      const legal = await this.engine.legalMoves(state.history, this.variant);
      if (generation !== this.generation) return;
      if (legal.length === 0) this.patch({ legal, outcome: { kind: 'no-moves', side: state.sideToMove } });
      else if (verdict.kind === 'draw') {
        this.patch({ legal: [], outcome: state.history.length >= moveLimit(this.variant) ? { kind: 'move-limit' } : { kind: 'draw' } });
      } else this.patch({ legal });
    } catch (e) {
      this.patch({ error: e instanceof Error ? e : new Error(String(e)) });
    }
  }

  private async resetTo(history: number[], outcome: Outcome | null): Promise<void> {
    const owner = replay(history, this.variant);
    this.current = this.stateFor(history, [], outcome ?? outcomeOf(owner, history, this.variant), owner);
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
      owner: owner ?? initialOwners(this.variant),
      sideToMove: (history.length % this.variant.players) as Player,
      legal,
      outcome,
      thinking: false,
      lastEngine: previous?.lastEngine ?? null,
      error: null,
      variant: this.variant,
      seats: this.seats,
      canSwap: this.canSwapNow(),
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

/** Resolves after `ms`, or at once when `signal` aborts. */
function sleep(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    if (signal.aborted) return resolve();
    const done = (): void => {
      clearTimeout(timer);
      signal.removeEventListener('abort', done);
      resolve();
    };
    const timer = setTimeout(done, ms);
    signal.addEventListener('abort', done, { once: true });
  });
}
