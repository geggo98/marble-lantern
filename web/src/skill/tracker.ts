// Measures the loss of every move of the human, in the background (docs/web-ui.md, section "Skill estimate"). Framework-free.
//
// Rules:
//  - one analysis at a time, never while the engine thinks or has the move (the engine answers first);
//  - an analysis in flight is aborted when the engine starts to think, or when the history no longer contains its position
//    (undo, new game, a loaded game), and its result is dropped;
//  - results are cached per position and move, so that undo, Back and an engine switch do not repeat finished work (analysis
//    scores are identical in all engines, docs/web-ui.md "Analysis API");
//  - the moves of a loaded game are analysed too, newest first, one at a time; `done` and `total` show the progress;
//  - an engine that rejects analyse with code 'unsupported' (the server engines) switches the feature off until reset().
import type { AnalysisResult, Engine } from '../engines/engine';
import { SKILL_NODES, scoreLoss } from '../engines/analysis';
import type { GameState } from '../game/game';
import type { Side } from '../game/board-state';

export interface TrackedGame {
  readonly state: Pick<GameState, 'history' | 'thinking' | 'outcome' | 'legal'>;
  subscribe(listener: (state: GameState) => void): () => void;
  isEngineTurn(): boolean;
}

export interface TrackerDeps {
  engine: Pick<Engine, 'analyse'>;
  game: TrackedGame;
  /** The side of the one human; null in a demo (nothing to measure). */
  humanSide: () => Side | null;
  /** False while an engine loads or has failed. */
  isReady: () => boolean;
  nodes?: number;
  /**
   * An analysis starts only when the game has been quiet for this long (default 800 ms). One engine worker does one thing at a
   * time, and an analysis takes about a second: started right after the engine's move, it would hold up the next legal-move
   * request or the engine's next search. A player who moves faster than this never waits for an analysis.
   */
  quietMs?: number;
}

export interface TrackerState {
  status: 'idle' | 'analysing' | 'unsupported' | 'error';
  /** Human moves of the current game that have a loss, and all human moves of it. */
  done: number;
  total: number;
  /** Raw (uncapped) losses of the analysed human moves of the current game, oldest first. */
  losses: readonly number[];
}

const EMPTY: TrackerState = { status: 'idle', done: 0, total: 0, losses: [] };

export class SkillTracker {
  private readonly deps: TrackerDeps;
  private readonly cache = new Map<string, number>();
  private running: { controller: AbortController; prefix: readonly number[] } | null = null;
  private failure: 'unsupported' | 'error' | null = null;
  private current: TrackerState = EMPTY;
  private listeners = new Set<() => void>();
  private lastChange = -Infinity;
  private timer: ReturnType<typeof setTimeout> | null = null;

  constructor(deps: TrackerDeps) {
    this.deps = deps;
  }

  getState = (): TrackerState => this.current;

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  /** Follows the game: call once; the returned function stops following. */
  attach(): () => void {
    return this.deps.game.subscribe(() => {
      this.lastChange = performance.now();
      const { history, thinking } = this.deps.game.state;
      const r = this.running;
      if (r && (thinking || r.prefix.length > history.length || r.prefix.some((m, i) => m !== history[i]))) this.abort();
      this.pump();
    });
  }

  /** Aborts the analysis in flight (its result is dropped). The cache stays. */
  abort(): void {
    this.cancelTimer();
    const r = this.running;
    if (!r) return;
    this.running = null;
    r.controller.abort();
    this.refresh();
  }

  /** Forgets a failure, so that a different engine can be tried. The cache stays. */
  reset(): void {
    this.abort();
    this.failure = null;
    this.refresh();
  }

  /** Starts the next analysis if the rules allow one. Cheap; call it whenever something may have changed. */
  pump(): void {
    this.refresh();
    const { game, engine } = this.deps;
    if (this.running || this.failure || !this.deps.isReady() || game.state.thinking) return;
    if (game.isEngineTurn()) return;
    // the legal moves of the position must be loaded, or the analysis would delay that request
    if (game.state.legal.length === 0 && !game.state.outcome) return;
    const next = this.missing().pop(); // newest first
    if (!next) return;
    const wait = (this.deps.quietMs ?? 800) - (performance.now() - this.lastChange);
    if (wait > 0) {
      this.cancelTimer();
      this.timer = setTimeout(() => {
        this.timer = null;
        this.pump();
      }, wait);
      return;
    }
    const prefix = game.state.history.slice(0, next.index);
    const controller = new AbortController();
    const mine = { controller, prefix: game.state.history.slice(0, next.index + 1) };
    this.running = mine;
    this.refresh();
    engine.analyse(prefix, null, this.deps.nodes ?? SKILL_NODES, controller.signal).then(
      (analysis) => {
        if (this.running !== mine) return; // aborted: drop the result
        this.running = null;
        const loss = lossOf(analysis, next.move);
        if (loss === null) this.failure = 'error';
        else this.cache.set(next.key, loss);
        this.pump();
      },
      (e: unknown) => {
        if (this.running !== mine) return;
        this.running = null;
        if (!controller.signal.aborted) this.failure = (e as { code?: unknown } | null)?.code === 'unsupported' ? 'unsupported' : 'error';
        this.pump();
      },
    );
  }

  private cancelTimer(): void {
    if (this.timer !== null) clearTimeout(this.timer);
    this.timer = null;
  }

  /** The human moves of the current history without a cached loss, oldest first. */
  private missing(): { index: number; move: number; key: string }[] {
    return this.humanMoves().filter((m) => !this.cache.has(m.key));
  }

  private humanMoves(): { index: number; move: number; key: string }[] {
    const side = this.deps.humanSide();
    const { history } = this.deps.game.state;
    if (side === null) return [];
    const out: { index: number; move: number; key: string }[] = [];
    for (let i = side; i < history.length; i += 2) out.push({ index: i, move: history[i]!, key: history.slice(0, i + 1).join(',') });
    return out;
  }

  private refresh(): void {
    const moves = this.humanMoves();
    const losses = moves.flatMap((m) => (this.cache.has(m.key) ? [this.cache.get(m.key)!] : []));
    const status = this.failure ?? (this.running ? 'analysing' : 'idle');
    const old = this.current;
    if (old.status === status && old.done === losses.length && old.total === moves.length && old.losses.length === losses.length && old.losses.every((l, i) => l === losses[i])) return;
    this.current = { status, done: losses.length, total: moves.length, losses };
    for (const l of this.listeners) l();
  }
}

/** The loss of `played` among the scored moves, or null if the analysis did not score it. */
function lossOf(analysis: AnalysisResult, played: number): number | null {
  const at = analysis.moves.indexOf(played);
  if (at < 0) return null;
  return scoreLoss(Math.max(...analysis.scores), analysis.scores[at]!);
}
