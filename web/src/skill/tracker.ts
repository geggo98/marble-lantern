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
import { DEFAULT_VARIANT, campsOf, rowsOf } from '../game/variant';
import type { Variant } from '../game/variant';
import { skillCalibrated, skillEnabled } from './estimate';
import { EXTRA_LISTS, LEDGER_MAX, hashKey, listOf } from './ledger';
import type { LedgerStore } from './ledger';

export interface TrackedGame {
  readonly state: Pick<GameState, 'history' | 'thinking' | 'outcome' | 'legal'> & { variant?: Variant };
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
  /** Where the measured losses and the pause flag are kept over many games (default: nothing is kept). */
  store?: LedgerStore;
}

export interface TrackerState {
  status: 'idle' | 'analysing' | 'unsupported' | 'error';
  /** Human moves of the current game that have a loss, and all human moves of it. */
  done: number;
  total: number;
  /** Raw (uncapped) losses of the analysed human moves of the current game, oldest first. */
  losses: readonly number[];
  /** Raw losses of ALL measured human moves of the jump rule of the current variant that are kept in the ledger, over all games on this device, oldest first. Empty when `enabled` is false. */
  ledger: readonly number[];
  /** Measuring is paused: nothing is analysed or recorded. */
  paused: boolean;
  /** The variant of the game that is on. The ledger and the estimate belong to its jump rule. */
  variant: Variant;
  /** Moves are measured in this variant: two players. Three players have no analysis, so no skill estimate. */
  enabled: boolean;
  /** The calibration was measured for this variant (adjacent jumps, two players). False for the long hop: the estimate is a rough guide. */
  calibrated: boolean;
}

type ListName = 'entries' | (typeof EXTRA_LISTS)[number];
const LIST_NAMES: readonly ListName[] = ['entries', ...EXTRA_LISTS];

const EMPTY: TrackerState = {
  status: 'idle', done: 0, total: 0, losses: [], ledger: [], paused: false, variant: DEFAULT_VARIANT, enabled: true, calibrated: true,
};

export class SkillTracker {
  private readonly deps: TrackerDeps;
  /** Measured losses by position hash, one map per (jump rule, house size): a loss belongs to the rule and the board it was measured under. Three players have none. */
  private readonly caches: Record<ListName, Map<string, number>> = { entries: new Map(), long: new Map(), five: new Map(), fiveLong: new Map() };
  private running: { controller: AbortController; prefix: readonly number[] } | null = null;
  private failure: 'unsupported' | 'error' | null = null;
  private current: TrackerState = EMPTY;
  private listeners = new Set<() => void>();
  private lastChange = -Infinity;
  private timer: ReturnType<typeof setTimeout> | null = null;
  /** Plies before this index were not played by the person at the board (the sides were swapped later). */
  private floor = 0;

  private paused = false;

  constructor(deps: TrackerDeps) {
    this.deps = deps;
    const stored = deps.store?.load();
    if (stored) {
      this.paused = stored.paused;
      for (const name of LIST_NAMES) for (const [k, loss] of (name === 'entries' ? stored.entries : stored[name]) ?? []) this.caches[name].set(k, loss);
      this.current = { ...EMPTY, ledger: [...this.cache.values()], paused: this.paused };
    }
  }

  /** The variant of the game that is on. */
  private get variant(): Variant {
    return this.deps.game.state.variant ?? DEFAULT_VARIANT;
  }

  /** The ledger of the jump rule and house size of the current variant. */
  private get cache(): Map<string, number> {
    return this.caches[listOf(this.variant.jump, rowsOf(this.variant))];
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

  /** Counts only the moves from ply `index` on: the sides were swapped there, so earlier moves are not the person's. */
  startAt(index: number): void {
    this.abort();
    this.floor = index;
    this.refresh();
  }

  /** Forgets a failure, so that a different engine can be tried. The cache stays. */
  reset(): void {
    this.abort();
    this.failure = null;
    this.refresh();
  }

  /** Pauses or resumes the measurement. While paused nothing is analysed or recorded; moves played meanwhile never count. */
  setPaused(paused: boolean): void {
    if (paused === this.paused) return;
    this.paused = paused;
    this.abort();
    if (!paused) this.floor = this.deps.game.state.history.length; // what was played during the pause is not measured later
    this.persist();
    this.refresh();
    this.pump();
  }

  /** Deletes the whole ledger and starts counting at the current position (moves played so far stay out). */
  clear(): void {
    this.abort();
    for (const cache of Object.values(this.caches)) cache.clear();
    this.floor = this.deps.game.state.history.length;
    this.persist();
    this.refresh();
  }

  private persist(): void {
    this.deps.store?.save({
      paused: this.paused,
      entries: [...this.caches.entries.entries()],
      ...Object.fromEntries(EXTRA_LISTS.flatMap((name) => (this.caches[name].size > 0 ? [[name, [...this.caches[name].entries()]]] : []))),
    });
  }

  private remember(variant: Variant, key: string, loss: number): void {
    const cache = this.caches[listOf(variant.jump, rowsOf(variant))];
    cache.set(key, loss);
    while (cache.size > LEDGER_MAX) cache.delete(cache.keys().next().value as string);
    this.persist();
  }

  /** Starts the next analysis if the rules allow one. Cheap; call it whenever something may have changed. */
  pump(): void {
    this.refresh();
    const { game, engine } = this.deps;
    if (this.paused || this.running || this.failure || !this.deps.isReady() || game.state.thinking) return;
    const variant = this.variant;
    if (!skillEnabled(variant)) return; // three players: no analysis, nothing to measure
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
    engine.analyse(prefix, null, this.deps.nodes ?? SKILL_NODES, controller.signal, variant).then(
      (analysis) => {
        if (this.running !== mine) return; // aborted: drop the result
        this.running = null;
        const loss = lossOf(analysis, next.move);
        if (loss === null) this.failure = 'error';
        else this.remember(variant, next.key, loss);
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
    if (side === null || !skillEnabled(this.variant)) return [];
    const out: { index: number; move: number; key: string }[] = [];
    const from = Math.min(this.floor, history.length);
    for (let i = from + ((((side - from) % 2) + 2) % 2); i < history.length; i += 2) out.push({ index: i, move: history[i]!, key: hashKey(history.slice(0, i + 1).join(',')) });
    return out;
  }

  private refresh(): void {
    const variant = this.variant;
    const moves = this.paused ? [] : this.humanMoves();
    const losses = moves.flatMap((m) => (this.cache.has(m.key) ? [this.cache.get(m.key)!] : []));
    const status = this.failure ?? (this.running ? 'analysing' : 'idle');
    const old = this.current;
    const enabled = skillEnabled(variant);
    const ledger = enabled ? [...this.cache.values()] : [];
    const same = old.variant.jump === variant.jump && old.variant.players === variant.players && campsOf(old.variant) === campsOf(variant) && rowsOf(old.variant) === rowsOf(variant);
    if (same && old.paused === this.paused && old.ledger.length === ledger.length && old.ledger.every((l, i) => l === ledger[i]) && old.status === status && old.done === losses.length && old.total === moves.length && old.losses.length === losses.length && old.losses.every((l, i) => l === losses[i])) return;
    this.current = {
      status, done: losses.length, total: moves.length, losses, ledger, paused: this.paused, variant, enabled, calibrated: skillCalibrated(variant),
    };
    for (const l of this.listeners) l();
  }
}

/** The loss of `played` among the scored moves, or null if the analysis did not score it. */
function lossOf(analysis: AnalysisResult, played: number): number | null {
  const at = analysis.moves.indexOf(played);
  if (at < 0) return null;
  return scoreLoss(Math.max(...analysis.scores), analysis.scores[at]!);
}
