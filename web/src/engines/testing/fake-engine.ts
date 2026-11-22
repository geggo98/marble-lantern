// A fake engine for tests: random-ish legal moves from the tiny generator, behind the real worker protocol.
// Do not import it from production code.
import { CELLS, cellCentre } from '../../board/cells';
import { applyMove, goalCells, initialOwners, outcomeOf, playState } from '../../game/board-state';
import { DEFAULT_VARIANT, moveLimit } from '../../game/variant';
import type { Player } from '../../game/variant';
import type { Variant } from '../../game/variant';
import type { AnalysisResult, ChooseResult, EngineInfo, EngineOutcome, FeatureReport, LoadReport, NetForwardReport } from '../engine';
import { EngineFailure } from '../protocol';
import type { Request, WorkerOut } from '../protocol';
import type { WorkerLike } from '../client';
import { tinyLegalMoves } from './tiny-moves';

export const FAKE_INFO: EngineInfo = {
  id: 'cpp-wasm',
  labelKey: 'engine.cppWasm',
  runtime: 'wasm',
  language: 'C++',
  version: 'fake',
  binaryBytes: 0,
  binaryGzipBytes: 0,
};

/** Small deterministic generator (mulberry32). */
export function seeded(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** Replays the history and checks every move against the tiny generator of the rule, like a real engine (illegal-history). */
function replayChecked(history: readonly number[], variant: Variant): Int8Array {
  const owner = initialOwners(variant);
  history.forEach((move, ply) => {
    const side = playState(history.slice(0, ply), variant).sideToMove; // a seat that is out (full ranking) is skipped
    if (!tinyLegalMoves(owner, side, variant).includes(move)) throw new Error(`move ${move} at ply ${ply} is not legal for the ${variant.jump} rule`);
    applyMove(owner, move);
  });
  return owner;
}

export class FakeEngineCore {
  private nodes: readonly number[] = [1000, 1000, 1000];
  /** The variant of the last newGame. */
  private gameVariant: Variant = DEFAULT_VARIANT;
  private random: () => number;
  initCalls = 0;
  analyseCalls = 0;
  newGameCalls = 0;
  featuresCalls = 0;
  netForwardCalls = 0;
  tableReceived: number | null = null;

  constructor(seed = 1) {
    this.random = seeded(seed);
  }

  handle(request: Request): unknown {
    switch (request.type) {
      case 'init': {
        this.initCalls++;
        this.nodes = [1000, 1000, 1000]; this.gameVariant = DEFAULT_VARIANT; // a fresh worker has no game setup until newGame arrives
        this.tableReceived = request.table ? request.table.byteLength : null;
        const report: LoadReport = {
          fetchMs: 0, inflateMs: 0, verifyMs: 0, instantiateMs: 1, tableInstallMs: 1, totalMs: 2,
          tableVerifiedBy: 'engine',
        };
        return report;
      }
      case 'newGame':
        this.newGameCalls++;
        this.nodes = request.setup.nodes;
        this.gameVariant = request.setup.variant ?? DEFAULT_VARIANT;
        return null;
      case 'legalMoves':
        return this.legal(request.history, request.variant ?? DEFAULT_VARIANT);
      case 'outcome':
        return this.outcome(request.history, request.variant ?? DEFAULT_VARIANT);
      case 'choose':
        return this.choose(request.history, request.variant ?? this.gameVariant);
      case 'analyse':
        return this.analyse(request.history, request.moves, request.nodes, request.variant ?? DEFAULT_VARIANT);
      case 'evaluate3':
        if (request.variant.players !== 3) throw new EngineFailure('unsupported', 'evaluate3 is for three players');
        return [0, 0, 0];
      case 'features':
        return this.features(request.history, request.variant);
      case 'netForward':
        return this.netForward(request.history);
      case 'bench':
        throw new EngineFailure('internal', 'bench is not implemented by the fake engine');
    }
  }

  /** A report of the right shape with fixed numbers: 13 features that count the ply, score 0, zero distances. Tests that need real numbers pass their own core. */
  private features(history: readonly number[], variant: Variant): FeatureReport {
    this.featuresCalls++;
    // the gate of the real exports (WebCore.features, shw_features): two players, adjacent jumps, closed corners, 4 or 5 rows
    if (variant.players !== 2 || variant.jump !== 'adjacent' || (variant.camps ?? 'closed') !== 'closed') {
      throw new EngineFailure('unsupported', 'features is for the classic game on 4 or 5 rows');
    }
    this.legal(history, variant); // illegal-history
    return {
      features: Array.from({ length: 13 }, (_, i) => (i === 0 ? history.length : 0)),
      score: 0,
      sideToMove: (history.length % 2) as 0 | 1,
      distance: [Array.from({ length: CELLS.length }, () => 0), Array.from({ length: CELLS.length }, () => 0)],
    };
  }

  /** A report of the right shape: two planes of zeros, one layer, a uniform policy over the legal moves, values 0. */
  private netForward(history: readonly number[]): NetForwardReport {
    this.netForwardCalls++;
    const moves = this.legal(history, DEFAULT_VARIANT);
    if (moves.length < 2) throw new EngineFailure('unsupported', 'nothing to evaluate');
    const zeros = (): number[] => Array.from({ length: CELLS.length }, () => 0);
    return {
      modelId: 'fake',
      planes: [zeros(), zeros()],
      layers: [{ id: 'conv_in', norm: zeros() }],
      logitsNetOnly: { from: zeros(), to: zeros() },
      policy: moves.map((move) => ({ move, prior: 1 / moves.length, priorNetOnly: 1 / moves.length })),
      values: [0, 0],
      valuesNetOnly: [0, 0],
      canonicalRotated: history.length % 2 === 1,
    };
  }

  private legal(history: readonly number[], variant: Variant): number[] {
    let owner;
    try {
      owner = replayChecked(history, variant);
    } catch (e) {
      throw new EngineFailure('illegal-history', String(e));
    }
    if (outcomeOf(owner, history, variant)) return []; // a finished game has no moves
    return tinyLegalMoves(owner, playState(history, variant).sideToMove, variant);
  }

  /** Win, move limit (a draw) or no legal move (a draw), like the facade. */
  private outcome(history: readonly number[], variant: Variant): EngineOutcome {
    let owner;
    try {
      owner = replayChecked(history, variant);
    } catch (e) {
      throw new EngineFailure('illegal-history', String(e));
    }
    const ended = outcomeOf(owner, history, variant);
    if (ended?.kind === 'win') return { kind: 'win', winner: ended.winner };
    if (ended?.kind === 'ranking') return { kind: 'ranking', ranking: ended.ranking };
    if (ended) return { kind: 'draw' };
    const turn = playState(history, variant);
    if (variant.ranking === 'full' && history.length >= moveLimit(variant)) {
      // the move limit under full ranking: the seats that are out keep their ranks, the others follow in seat order (the real engine ranks them by distance)
      const rest = ([0, 1, 2] as const).filter((p) => !turn.finishers.includes(p));
      return { kind: 'ranking', ranking: [...turn.finishers, ...rest] as unknown as readonly [Player, Player, Player] };
    }
    if (tinyLegalMoves(owner, turn.sideToMove, variant).length === 0) return { kind: 'draw' };
    return turn.finishers.length > 0 ? { kind: 'ongoing', finished: turn.finishers } : { kind: 'ongoing' };
  }

  /** How far a move brings the piece to the goal: rows for two players, distance to the goal corner centre for three. */
  private gain(move: number, side: number, variant: Variant): number {
    if (variant.players === 2) {
      const dr = CELLS[move >>> 8]!.r - CELLS[move & 0xff]!.r;
      return side === 0 ? dr : -dr;
    }
    const goal = goalCells(variant)[side]!;
    const centre = { x: 0, y: 0 };
    for (const c of goal) {
      const p = cellCentre(c);
      centre.x += p.x / goal.length;
      centre.y += p.y / goal.length;
    }
    const dist = (id: number): number => Math.hypot(cellCentre(id).x - centre.x, cellCentre(id).y - centre.y);
    return Math.round((dist(move & 0xff) - dist(move >>> 8)) * 100);
  }

  /**
   * Scores for tests: 30 points for each row a move gains towards the goal row, from the point of view of the mover (30 is
   * the unit of the real evaluator's progress step). `nodes` is only echoed, per move.
   */
  private analyse(history: readonly number[], moves: readonly number[] | null, nodes: number, variant: Variant): AnalysisResult {
    this.analyseCalls++;
    const side = playState(history, variant).sideToMove;
    const legal = this.legal(history, variant);
    if (legal.length === 0) throw new EngineFailure('illegal-history', 'no legal move');
    const chosen = moves === null ? legal : [...moves];
    if (chosen.length === 0) throw new EngineFailure('illegal-move', 'no moves to score');
    for (const m of chosen) if (!legal.includes(m)) throw new EngineFailure('illegal-move', `move ${m} is not legal`);
    const scores = chosen.map((m) => 30 * this.gain(m, side, variant));
    return { scores, moves: chosen, position: Math.max(...scores), nodes: nodes * chosen.length, ms: 0 };
  }

  private choose(history: readonly number[], variant: Variant): ChooseResult {
    if (variant.players !== this.gameVariant.players || variant.jump !== this.gameVariant.jump) {
      throw new EngineFailure('internal', 'choose with another variant than newGame');
    }
    const side = playState(history, variant).sideToMove;
    const moves = this.legal(history, variant);
    if (moves.length === 0) throw new EngineFailure('illegal-history', 'no legal move');
    // Prefer the largest gain towards the goal row, random among equals.
    const gain = (m: number): number => this.gain(m, side, variant);
    const best = Math.max(...moves.map(gain));
    const pool = moves.filter((m) => gain(m) === best);
    const move = pool[Math.floor(this.random() * pool.length)]!;
    return {
      move, score: 0, depth: 1, nodes: this.nodes[side] ?? this.nodes[0] ?? 0, ms: 0, raceMove: false, rows1Requests: 0,
    };
  }
}

export interface FakeWorkerOptions {
  /** Delay before the reply to a "choose" or "analyse" request, in ms. */
  chooseDelayMs?: number;
  /** Fail the init request of the Nth worker (1-based) with this code. */
  initError?: EngineFailure;
}

/** An in-process WorkerLike. `instances` lists every worker the client created, to inspect restarts. */
export class FakeWorker implements WorkerLike {
  onmessage: ((event: { data: WorkerOut }) => void) | null = null;
  onerror: ((event: { message?: string }) => void) | null = null;
  terminated = false;
  readonly core: FakeEngineCore;
  private timers = new Set<ReturnType<typeof setTimeout>>();
  private readonly options: FakeWorkerOptions;

  constructor(core: FakeEngineCore, options: FakeWorkerOptions = {}) {
    this.core = core;
    this.options = options;
  }

  postMessage(original: Request, transfer?: Transferable[]): void {
    // Like a real worker: the message is cloned and the transferred buffers are detached in the sender.
    const message: Request = structuredClone(original, { transfer });
    const run = (): void => {
      if (this.terminated) return;
      let reply: WorkerOut;
      try {
        if (message.type === 'init' && this.options.initError) throw this.options.initError;
        reply = { id: message.id, ok: true, result: this.core.handle(message) };
      } catch (e) {
        const failure = e instanceof EngineFailure ? e : new EngineFailure('internal', String(e));
        reply = { id: message.id, ok: false, error: failure.toError() };
      }
      this.onmessage?.({ data: reply });
    };
    if ((message.type === 'choose' || message.type === 'analyse' || message.type === 'netForward') && this.options.chooseDelayMs) {
      const t = setTimeout(() => { this.timers.delete(t); run(); }, this.options.chooseDelayMs);
      this.timers.add(t);
    } else {
      queueMicrotask(run);
    }
  }

  terminate(): void {
    this.terminated = true;
    for (const t of this.timers) clearTimeout(t);
    this.timers.clear();
  }
}
