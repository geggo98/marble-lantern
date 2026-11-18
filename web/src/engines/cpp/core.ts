// Drives the Emscripten module of the C++ engine (cpp/src/wasm/wasm_api.cpp). Works in node and in a worker: it does not touch
// `self`, `postMessage` or the DOM. The glue for the worker is worker.ts.
//
// The move history is the game state: every call replays it from the start position in the module's one global game.
// One player per side lives for the whole game (so its transposition table carries over); newGame replaces them.
import type { AnalysisResult, BenchResult, ChooseResult, GameSetup } from '../engine';
import { sampleMemory } from '../../bench/memory';
import { EngineFailure } from '../protocol';
import type { ErrorCode } from '../protocol';

/** The exports of engine-cpp.js that this adapter calls (the `shw_*` C API plus the Emscripten runtime helpers). */
export interface CppModule {
  HEAPU8: Uint8Array;
  HEAP32: Int32Array;
  UTF8ToString(ptr: number): string;
  _malloc(size: number): number;
  _free(ptr: number): void;
  _shw_load_rows2(ptr: number, len: number, verify: number): number;
  _shw_error(): number;
  _shw_player_new(nodes: number): number;
  _shw_player_clear(id: number): void;
  _shw_game_new(): void;
  _shw_side_to_move(): number;
  _shw_legal_moves(out: number, cap: number): number;
  _shw_play(move: number): number;
  _shw_choose(id: number): number;
  _shw_last_score(id: number): number;
  _shw_last_depth(id: number): number;
  _shw_last_nodes(id: number): number;
  _shw_has_result(id: number): number;
  _shw_race_moves(id: number): number;
  _shw_analyse(nodes: number, movesIn: number, nIn: number, movesOut: number, scoresOut: number, cap: number): number;
  _shw_analysis_nodes(): number;
  _shw_rows1_requests(): number;
}

/** What `import('engine-cpp.js').default` is: it creates and instantiates the module. */
export type CppModuleFactory = () => Promise<CppModule>;

/** Return codes of shw_load_rows2 (LoadError kind + 1) that name a damaged table; everything else is "internal". */
const LOAD_ERRORS: Readonly<Record<number, ErrorCode>> = {
  4: 'table-magic',
  7: 'table-truncated',
  8: 'table-trailing',
  9: 'table-checksum',
  10: 'table-checksum',
};

/** Capacity of the legal-move buffer in ints; far above the largest move list of one position. */
const MOVE_CAPACITY = 1024;
const MAX_PLIES = 1000;

const now = (): number => performance.now();

export class CppCore {
  private readonly m: CppModule;
  /** Free players by node budget, so that newGame does not grow the module's player table (each holds a transposition table). */
  private readonly pool = new Map<number, number[]>();
  private players: [number, number] | null = null;
  /** Node budgets of the current players, to file them back under the right budget. */
  private budgets: [number, number] = [0, 0];
  private tableInstalled = false;
  private movesPtr = 0;
  /** Scratch for analyse: input moves, output moves, output scores (MOVE_CAPACITY ints each). */
  private analysisPtr = 0;

  private constructor(module: CppModule) {
    this.m = module;
  }

  static async create(factory: CppModuleFactory): Promise<CppCore> {
    return new CppCore(await factory());
  }

  /**
   * Copies the inflated table into the module and installs it. `verify` asks the engine to check both SHA-256 values
   * (about 100 ms); pass false only after the page has checked the manifest SHA-256. Returns the time in ms.
   * Throws EngineFailure with the protocol code on a damaged table.
   */
  installTable(bytes: ArrayBuffer | Uint8Array, verify: boolean): number {
    const t0 = now();
    const src = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
    const ptr = this.m._malloc(Math.max(src.length, 1));
    if (ptr === 0) throw new EngineFailure('internal', `out of memory for the ${src.length} byte table`);
    let rc: number;
    try {
      // HEAPU8 is read after the malloc: the heap may have grown, which replaces the view.
      this.m.HEAPU8.set(src, ptr);
      rc = this.m._shw_load_rows2(ptr, src.length, verify ? 1 : 0);
    } finally {
      this.m._free(ptr);
    }
    if (rc !== 0) {
      const text = this.m.UTF8ToString(this.m._shw_error());
      throw new EngineFailure(LOAD_ERRORS[rc] ?? 'internal', `shw_load_rows2 returned ${rc}: ${text}`);
    }
    this.tableInstalled = true;
    return now() - t0;
  }

  /** Starts a game: one player per side with its own node budget. Players of an earlier game are cleared and reused. */
  newGame(setup: GameSetup): void {
    this.requireTable();
    this.releasePlayers();
    const [n0, n1] = setup.nodes;
    // acquire() twice for equal budgets yields two different players: the first is taken off the free list.
    this.players = [this.acquire(n0), this.acquire(n1)];
    this.budgets = [n0, n1];
  }

  legalMoves(history: readonly number[]): number[] {
    this.requireTable();
    this.replay(history);
    return this.readLegalMoves();
  }

  choose(history: readonly number[]): ChooseResult {
    this.requireTable();
    if (!this.players) throw new EngineFailure('internal', 'newGame was not called');
    this.replay(history);
    const id = this.players[this.m._shw_side_to_move()]!;
    const raceBefore = this.m._shw_race_moves(id);
    const t0 = now();
    const move = this.m._shw_choose(id);
    const ms = now() - t0;
    if (move < 0) throw new EngineFailure('illegal-history', 'the position has no legal move');
    const searched = this.m._shw_has_result(id) !== 0;
    const rows1Requests = this.m._shw_rows1_requests();
    if (rows1Requests > 0) throw new EngineFailure('rows1-requested', `${rows1Requests} request(s) for rows1.bin (ADR 0001)`);
    return {
      move,
      score: searched ? this.m._shw_last_score(id) : 0,
      depth: searched ? this.m._shw_last_depth(id) : 0,
      nodes: searched ? this.m._shw_last_nodes(id) : 0,
      ms,
      raceMove: this.m._shw_race_moves(id) > raceBefore,
      rows1Requests,
    };
  }

  /**
   * Scores moves of the position after `history` for the side to move with the module's analysis player (shw_analyse): a
   * player of its own, so the game players, their tables and choose() results stay untouched. `moves` null scores all
   * legal moves. `nodes` is the budget for each scored move.
   */
  analyse(history: readonly number[], moves: readonly number[] | null, nodes: number): AnalysisResult {
    this.requireTable();
    if (!Number.isInteger(nodes) || nodes < 1) throw new EngineFailure('internal', `node budget must be a positive integer, got ${nodes}`);
    if (moves !== null && (moves.length === 0 || moves.length > MOVE_CAPACITY || !moves.every((m) => Number.isInteger(m) && m >= 0))) {
      throw new EngineFailure('illegal-move', 'the move list is empty, too long or not made of moves');
    }
    this.replay(history);
    if (this.analysisPtr === 0) {
      this.analysisPtr = this.m._malloc(3 * MOVE_CAPACITY * 4);
      if (this.analysisPtr === 0) throw new EngineFailure('internal', 'out of memory for the analysis buffers');
    }
    const inBase = this.analysisPtr;
    const outMoves = inBase + MOVE_CAPACITY * 4;
    const outScores = outMoves + MOVE_CAPACITY * 4;
    if (moves !== null) this.m.HEAP32.set(moves, inBase >>> 2);
    const t0 = now();
    const n = this.m._shw_analyse(nodes, moves === null ? 0 : inBase, moves === null ? 0 : moves.length, outMoves, outScores, MOVE_CAPACITY);
    const ms = now() - t0;
    if (n < 0) {
      const text = n === -4 ? this.m.UTF8ToString(this.m._shw_error()) : '';
      const [code, message]: [ErrorCode, string] =
        n === -1 ? ['illegal-move', 'a move to analyse is not legal'] :
        n === -2 ? ['illegal-history', 'the game is over'] :
        ['internal', `shw_analyse returned ${n} ${text}`.trim()];
      throw new EngineFailure(code, message);
    }
    const rows1Requests = this.m._shw_rows1_requests();
    if (rows1Requests > 0) throw new EngineFailure('rows1-requested', `${rows1Requests} request(s) for rows1.bin (ADR 0001)`);
    // HEAP32 is read after the call: a view taken earlier may be stale after memory growth.
    const heap = this.m.HEAP32;
    const scores = Array.from(heap.subarray(outScores >>> 2, (outScores >>> 2) + n));
    return {
      scores,
      moves: Array.from(heap.subarray(outMoves >>> 2, (outMoves >>> 2) + n)),
      position: Math.max(...scores),
      nodes: this.m._shw_analysis_nodes(),
      ms,
    };
  }

  /**
   * The benchmark game of plan section 8: `plies` plies from the start position, both sides with `nodes` per move,
   * one player per side for the whole game. Starts its own game; call newGame afterwards for normal play.
   */
  bench(plies: number, nodes: number): BenchResult {
    this.newGame({ nodes: [nodes, nodes] });
    const history: number[] = [];
    const result: BenchResult = { moves: [], nodes: [], ms: [], rows1Requests: 0 };
    for (let ply = 0; ply < plies; ply++) {
      const r = this.choose(history);
      history.push(r.move);
      result.moves.push(r.move);
      result.nodes.push(r.nodes);
      result.ms.push(r.ms);
      result.rows1Requests = r.rows1Requests;
    }
    result.memory = sampleMemory(this.m.HEAPU8.byteLength); // read after the game: the heap may have grown
    return result;
  }

  // ---- internals ----

  private requireTable(): void {
    if (!this.tableInstalled) throw new EngineFailure('internal', 'the race table is not installed');
  }

  private acquire(nodes: number): number {
    const free = this.pool.get(nodes)?.pop();
    if (free !== undefined) {
      this.m._shw_player_clear(free);
      return free;
    }
    const id = this.m._shw_player_new(nodes);
    if (id < 0) throw new EngineFailure('internal', `shw_player_new(${nodes}): ${this.m.UTF8ToString(this.m._shw_error())}`);
    return id;
  }

  private releasePlayers(): void {
    if (!this.players) return;
    this.players.forEach((id, side) => {
      const budget = this.budgets[side]!;
      const list = this.pool.get(budget) ?? [];
      list.push(id);
      this.pool.set(budget, list);
    });
    this.players = null;
  }

  /** Replays the history in the module's global game. A move the engine rejects is an illegal history. */
  private replay(history: readonly number[]): void {
    if (history.length > MAX_PLIES) throw new EngineFailure('illegal-history', `history has ${history.length} plies`);
    this.m._shw_game_new();
    history.forEach((move, ply) => {
      if (!Number.isInteger(move) || this.m._shw_play(move) !== 0) {
        throw new EngineFailure('illegal-history', `move ${move} at ply ${ply} is not legal`);
      }
    });
  }

  private readLegalMoves(): number[] {
    if (this.movesPtr === 0) {
      this.movesPtr = this.m._malloc(MOVE_CAPACITY * 4);
      if (this.movesPtr === 0) throw new EngineFailure('internal', 'out of memory for the move list');
    }
    const n = this.m._shw_legal_moves(this.movesPtr, MOVE_CAPACITY);
    if (n > MOVE_CAPACITY) throw new EngineFailure('internal', `${n} legal moves exceed the buffer of ${MOVE_CAPACITY}`);
    // HEAP32 is read after the call: a view taken earlier may be stale after memory growth.
    const base = this.movesPtr >>> 2;
    return Array.from(this.m.HEAP32.subarray(base, base + n));
  }
}
