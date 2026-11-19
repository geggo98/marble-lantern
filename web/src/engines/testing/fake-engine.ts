// A fake engine for tests: random-ish legal moves from the tiny generator, behind the real worker protocol.
// Do not import it from production code.
import { CELLS, cellCentre } from '../../board/cells';
import { goalCells, outcomeOf, replay } from '../../game/board-state';
import { DEFAULT_VARIANT } from '../../game/variant';
import type { Variant } from '../../game/variant';
import type { AnalysisResult, ChooseResult, EngineInfo, EngineOutcome, LoadReport } from '../engine';
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

export class FakeEngineCore {
  private nodes: readonly number[] = [1000, 1000, 1000];
  /** The variant of the last newGame. */
  private gameVariant: Variant = DEFAULT_VARIANT;
  private random: () => number;
  initCalls = 0;
  analyseCalls = 0;
  newGameCalls = 0;
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
      case 'bench':
        throw new EngineFailure('internal', 'bench is not implemented by the fake engine');
    }
  }

  private legal(history: readonly number[], variant: Variant): number[] {
    let owner;
    try {
      owner = replay(history, variant);
    } catch (e) {
      throw new EngineFailure('illegal-history', String(e));
    }
    if (outcomeOf(owner, history, variant)) return []; // a finished game has no moves
    return tinyLegalMoves(owner, history.length % variant.players, variant);
  }

  /** Win, move limit (a draw) or no legal move (a draw), like the facade. */
  private outcome(history: readonly number[], variant: Variant): EngineOutcome {
    let owner;
    try {
      owner = replay(history, variant);
    } catch (e) {
      throw new EngineFailure('illegal-history', String(e));
    }
    const ended = outcomeOf(owner, history, variant);
    if (ended?.kind === 'win') return { kind: 'win', winner: ended.winner };
    if (ended) return { kind: 'draw' };
    return tinyLegalMoves(owner, history.length % variant.players, variant).length === 0 ? { kind: 'draw' } : { kind: 'ongoing' };
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
    if (variant.players === 3) throw new EngineFailure('unsupported', 'analysis is available for two players only');
    const side = history.length % 2;
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
    const side = history.length % variant.players;
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
    if ((message.type === 'choose' || message.type === 'analyse') && this.options.chooseDelayMs) {
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
