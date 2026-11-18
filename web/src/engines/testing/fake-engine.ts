// A fake engine for tests: random-ish legal moves from the tiny generator, behind the real worker protocol.
// Do not import it from production code.
import { CELLS } from '../../board/cells';
import { replay, sideToMove } from '../../game/board-state';
import type { ChooseResult, EngineInfo, LoadReport } from '../engine';
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
  private nodes: [number, number] = [1000, 1000];
  private random: () => number;
  initCalls = 0;
  newGameCalls = 0;
  tableReceived: number | null = null;

  constructor(seed = 1) {
    this.random = seeded(seed);
  }

  handle(request: Request): unknown {
    switch (request.type) {
      case 'init': {
        this.initCalls++;
        this.nodes = [1000, 1000]; // a fresh worker has no game setup until newGame arrives
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
        return null;
      case 'legalMoves':
        return this.legal(request.history);
      case 'choose':
        return this.choose(request.history);
      case 'bench':
        throw new EngineFailure('internal', 'bench is not implemented by the fake engine');
    }
  }

  private legal(history: readonly number[]): number[] {
    let owner;
    try {
      owner = replay(history);
    } catch (e) {
      throw new EngineFailure('illegal-history', String(e));
    }
    return tinyLegalMoves(owner, sideToMove(history.length));
  }

  private choose(history: readonly number[]): ChooseResult {
    const side = sideToMove(history.length);
    const moves = this.legal(history);
    if (moves.length === 0) throw new EngineFailure('illegal-history', 'no legal move');
    // Prefer the largest gain towards the goal row, random among equals.
    const gain = (m: number): number => {
      const dr = CELLS[m >>> 8]!.r - CELLS[m & 0xff]!.r;
      return side === 0 ? dr : -dr;
    };
    const best = Math.max(...moves.map(gain));
    const pool = moves.filter((m) => gain(m) === best);
    const move = pool[Math.floor(this.random() * pool.length)]!;
    return {
      move, score: 0, depth: 1, nodes: this.nodes[side], ms: 0, raceMove: false, rows1Requests: 0,
    };
  }
}

export interface FakeWorkerOptions {
  /** Delay before the reply to a "choose" request, in ms. */
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
    if (message.type === 'choose' && this.options.chooseDelayMs) {
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
