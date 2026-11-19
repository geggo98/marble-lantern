// Drives the Emscripten module of the C++ engine (cpp/src/wasm/wasm_api.cpp). Works in node and in a worker: it does not touch
// `self`, `postMessage` or the DOM. The glue for the worker is worker.ts.
//
// The move history is the game state: every call replays it from the start position in the module's one global game.
// One player per side lives for the whole game (so its transposition table carries over); newGame replaces them.
import type { AnalysisResult, BenchResult, ChooseResult, EngineOutcome, GameSetup } from '../engine';
import { DEFAULT_VARIANT, playsOn, rankingOf } from '../../game/variant';
import type { Player, Variant } from '../../game/variant';
import { needsTable, requireAnalysable, seatNodes, variantOf } from '../variant-args';
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
  /** jump: 0 adjacent, 1 long. */
  _shw_player_new(nodes: number, jump: number): number;
  /** The three-player player. */
  _shw_player_new3(nodes: number, jump: number): number;
  _shw_player_clear(id: number): void;
  /** jump: 0 adjacent, 1 long; players: 2 or 3. Returns 0, or -1 for other values. */
  _shw_game_new2(jump: number, players: number): number;
  /** As _shw_game_new2 with a ranking rule: 0 first winner only, 1 full ranking; -1 for other values. */
  _shw_game_new3(jump: number, players: number, ranking: number): number;
  /** The absolute seat of rank i (0 = first place), or -1 if that rank is not decided. */
  _shw_rank(i: number): number;
  /** The seats that are out (full ranking) as bits: bit p for seat p. */
  _shw_finished_mask(): number;
  /** -1 running, 0 draw, 1 + winner. */
  _shw_outcome(): number;
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
  private readonly pool = new Map<string, number[]>();
  /** The engine players of the current game, one per seat. */
  private players: number[] | null = null;
  /** Pool keys of the current players, to file them back where they came from. */
  private keys: string[] = [];
  /** The variant of the current game; null before newGame. */
  private gameVariant: Variant | null = null;
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
    const variant = variantOf(setup);
    this.requireTable(variant);
    this.releasePlayers();
    // acquire() twice for equal budgets yields two different players: the first is taken off the free list.
    const budgets = seatNodes(setup);
    this.players = [];
    this.keys = [];
    for (const nodes of budgets) {
      const key = `${variant.players}:${variant.jump}:${nodes}`;
      this.keys.push(key);
      this.players.push(this.acquire(key, nodes, variant));
    }
    this.gameVariant = variant;
  }

  legalMoves(history: readonly number[], variant: Variant = DEFAULT_VARIANT): number[] {
    this.requireTable(variant);
    this.replay(history, variant);
    return this.readLegalMoves();
  }

  choose(history: readonly number[], variant?: Variant): ChooseResult {
    if (!this.players || !this.gameVariant) throw new EngineFailure('internal', 'newGame was not called');
    variant ??= this.gameVariant;
    this.requireTable(variant);
    this.replay(history, variant);
    const id = this.players[this.m._shw_side_to_move()]!;
    const raceBefore = this.m._shw_race_moves(id);
    const t0 = now();
    const move = this.m._shw_choose(id);
    const ms = now() - t0;
    if (move === -2) throw new EngineFailure('internal', `shw_choose: ${this.m.UTF8ToString(this.m._shw_error())} (the game variant differs from the one of newGame)`);
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
   * `ongoing` (with the seats that are out, in full ranking), a winner (seat 0, 1 or 2), `draw` (move limit, repetition, no
   * legal move) or, in a full-ranking game of three players, the final `ranking`.
   */
  outcome(history: readonly number[], variant: Variant = DEFAULT_VARIANT): EngineOutcome {
    this.requireTable(variant);
    this.replay(history, variant);
    const code = this.m._shw_outcome();
    if (playsOn(variant)) return this.fullOutcome(code);
    if (code === -1) return { kind: 'ongoing' };
    if (code === 0) return { kind: 'draw' };
    if (code >= 1 && code <= variant.players) return { kind: 'win', winner: (code - 1) as Player };
    throw new EngineFailure('internal', `shw_outcome returned ${code}`);
  }

  /**
   * Scores moves of the position after `history` for the side to move with the module's analysis player (shw_analyse): a
   * player of its own, so the game players, their tables and choose() results stay untouched. `moves` null scores all
   * legal moves. `nodes` is the budget for each scored move.
   */
  analyse(history: readonly number[], moves: readonly number[] | null, nodes: number, variant: Variant = DEFAULT_VARIANT): AnalysisResult {
    requireAnalysable(variant);
    this.requireTable(variant);
    if (!Number.isInteger(nodes) || nodes < 1) throw new EngineFailure('internal', `node budget must be a positive integer, got ${nodes}`);
    if (moves !== null && (moves.length === 0 || moves.length > MOVE_CAPACITY || !moves.every((m) => Number.isInteger(m) && m >= 0))) {
      throw new EngineFailure('illegal-move', 'the move list is empty, too long or not made of moves');
    }
    this.replay(history, variant);
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
  bench(plies: number, nodes: number, variant: Variant = DEFAULT_VARIANT): BenchResult {
    this.newGame({ nodes: [nodes, nodes, nodes], variant });
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

  /** The outcome of a full-ranking game of three players: no draw exists, an ended game always has all three ranks. */
  private fullOutcome(code: number): EngineOutcome {
    const mask = this.m._shw_finished_mask();
    if (code === -1) {
      const finished = ([0, 1, 2] as const).filter((p) => (mask >> p & 1) === 1);
      return finished.length === 0 ? { kind: 'ongoing' } : { kind: 'ongoing', finished };
    }
    const order = [0, 1, 2].map((i) => this.m._shw_rank(i));
    const seats = order.filter((p): p is Player => p === 0 || p === 1 || p === 2);
    if (seats.length !== 3 || new Set(seats).size !== 3) throw new EngineFailure('internal', `shw_rank gave ${order.join(' ')} for outcome ${code}`);
    return { kind: 'ranking', ranking: [seats[0]!, seats[1]!, seats[2]!] };
  }

  /** Only the adjacent two-player game needs the race table (see needsTable). */
  private requireTable(variant: Variant = DEFAULT_VARIANT): void {
    if (needsTable(variant) && !this.tableInstalled) throw new EngineFailure('internal', 'the race table is not installed');
  }

  private acquire(key: string, nodes: number, variant: Variant): number {
    const free = this.pool.get(key)?.pop();
    if (free !== undefined) {
      this.m._shw_player_clear(free);
      return free;
    }
    const jump = variant.jump === 'long' ? 1 : 0;
    const id = variant.players === 3 ? this.m._shw_player_new3(nodes, jump) : this.m._shw_player_new(nodes, jump);
    if (id < 0) throw new EngineFailure('internal', `shw_player_new(${nodes}, ${jump}): ${this.m.UTF8ToString(this.m._shw_error())}`);
    return id;
  }

  private releasePlayers(): void {
    if (!this.players) return;
    this.players.forEach((id, side) => {
      const key = this.keys[side]!;
      const list = this.pool.get(key) ?? [];
      list.push(id);
      this.pool.set(key, list);
    });
    this.players = null;
    this.keys = [];
  }

  /** Replays the history in the module's global game. A move the engine rejects is an illegal history. */
  private replay(history: readonly number[], variant: Variant): void {
    if (history.length > MAX_PLIES) throw new EngineFailure('illegal-history', `history has ${history.length} plies`);
    if (this.m._shw_game_new3(variant.jump === 'long' ? 1 : 0, variant.players, rankingOf(variant) === 'full' ? 1 : 0) !== 0) {
      throw new EngineFailure('internal', `shw_game_new3: ${this.m.UTF8ToString(this.m._shw_error())}`);
    }
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
