// Drives the TeaVM facade sternhalma.web.Web (teavm-web/). Works in node and in a worker: it does not touch `self`,
// `postMessage` or the DOM. The glue for the worker is worker.ts, the loading of the module is in handler.ts and node.ts.
//
// The facade exports string-only methods that never throw (see Web.java): the reply is "ok <fields>" or
// "error <code> <message>". The move history is the game state and crosses the boundary as "1,2,3".
import type { AnalysisResult, BenchResult, ChooseResult, EngineOutcome, GameSetup } from '../engine';
import { DEFAULT_VARIANT, rankingOf } from '../../game/variant';
import type { Player, Variant } from '../../game/variant';
import { needsTable, requireThreePlayers, seatNodes, variantOf } from '../variant-args';
import { sampleMemory } from '../../bench/memory';
import { EngineFailure } from '../protocol';
import type { ErrorCode } from '../protocol';

/** The exports of engine-java.js (JS target) and of `TeaVM.wasmGC.load(...).exports` (Wasm GC target). */
export interface JavaModule {
  /** `file` is the inflated table; `verify` checks the SHA-256 of the body against the one in the file header. */
  setTable(file: Int8Array, verify: boolean): string;
  /**
   * `jump` is "adjacent" or "long", `players` "2" or "3", `ranking` "first" or "full". Every argument is always passed:
   * the JS target fails on a missing one. `nodes2` is 0 for two players.
   */
  newGame(nodes0: number, nodes1: number, jump: string, players: string, nodes2: number, ranking: string): string;
  legalMoves(history: string, jump: string, players: string, ranking: string): string;
  choose(history: string, jump: string, players: string, ranking: string): string;
  bench(plies: number, nodes: number, jump: string, players: string, ranking: string): string;
  /** `moves` is a comma separated list, or empty for all legal moves. */
  analyse(history: string, moves: string, nodes: number, jump: string, players: string, ranking: string): string;
  /**
   * `ok ongoing`, `ok win <side>` or `ok draw`; with ranking "full" and three players `ok ongoing`,
   * `ok ongoing finished <mask>` (bit p set: seat p is out) or `ok ranking <a> <b> <c>`.
   */
  outcome(history: string, jump: string, players: string, ranking: string): string;
  /**
   * `ok <e0> <e1> <e2>`: the static evaluation of a three-player position, one integer per absolute player. With ranking
   * "full" a seat that is out holds a rank constant (1000000 first, 500000 second, -1000000 last).
   */
  evaluate3(history: string, jump: string, ranking: string): string;
}

const CODES: ReadonlySet<string> = new Set<ErrorCode>([
  'table-checksum', 'table-truncated', 'table-trailing', 'table-magic', 'wasm-gc-unsupported',
  'illegal-history', 'illegal-move', 'rows1-requested', 'unsupported', 'aborted', 'internal',
]);

const now = (): number => performance.now();

/** Splits an "ok ..." reply into its fields; throws EngineFailure for an "error <code> <message>" reply. */
export function fields(reply: unknown): string[] {
  if (typeof reply !== 'string') throw new EngineFailure('internal', `the facade returned ${typeof reply}, not a string`);
  if (reply === 'ok') return [];
  if (reply.startsWith('ok ')) return reply.slice(3).split(' ');
  const m = /^error (\S+) ?(.*)$/s.exec(reply);
  if (m) throw new EngineFailure(CODES.has(m[1]!) ? (m[1] as ErrorCode) : 'internal', m[2]!);
  throw new EngineFailure('internal', `unexpected reply from the facade: ${reply.slice(0, 120)}`);
}

/** The seats of a finished-set mask (bit p for seat p), seat 0 first; the facade names at most one seat while a game runs. */
export function ongoingOf(mask: number, reply: readonly string[]): EngineOutcome {
  if (reply.length === 1) return { kind: 'ongoing' };
  if (reply.length !== 3 || reply[1] !== 'finished' || !Number.isInteger(mask) || mask < 1 || mask > 7) {
    throw new EngineFailure('internal', `unexpected outcome reply: ${reply.join(' ')}`);
  }
  const seats = ([0, 1, 2] as const).filter((p) => (mask >> p & 1) === 1);
  return { kind: 'ongoing', finished: seats };
}

/** The ranking of an `ok ranking a b c` reply: three different seats. */
export function rankingOutcome(order: readonly number[], reply: readonly string[]): EngineOutcome {
  const seats = order.filter((p): p is Player => p === 0 || p === 1 || p === 2);
  if (seats.length !== 3 || order.length !== 3 || new Set(seats).size !== 3) {
    throw new EngineFailure('internal', `unexpected outcome reply: ranking ${reply.slice(1).join(' ')}`);
  }
  return { kind: 'ranking', ranking: [seats[0]!, seats[1]!, seats[2]!] };
}

const numbers = (list: string | undefined): number[] => (list ? list.split(',').map(Number) : []);

const MAX_PLIES = 1000;

export class JavaCore {
  private readonly m: JavaModule;
  private tableInstalled = false;
  /** The variant of the last newGame; null before it. */
  private gameVariant: Variant | null = null;

  private constructor(module: JavaModule) {
    this.m = module;
  }

  static create(module: JavaModule): JavaCore {
    return new JavaCore(module);
  }

  /**
   * Installs the inflated table. `verify` asks the engine to check the body against the checksum in the table header
   * (a few hundred ms in Java; pass false after the page has checked the manifest SHA-256). Returns the time in ms.
   * Throws EngineFailure with the protocol code on a damaged table.
   */
  installTable(bytes: ArrayBuffer | Uint8Array, verify: boolean): number {
    const t0 = now();
    const view = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
    // The facade takes byte[] by reference: an Int8Array over the same memory, no copy.
    const states = fields(this.m.setTable(new Int8Array(view.buffer, view.byteOffset, view.byteLength), verify));
    if (!(Number(states[0]) > 0)) throw new EngineFailure('internal', 'setTable returned no state count');
    this.tableInstalled = true;
    this.gameVariant = null;
    return now() - t0;
  }

  /** Starts a game: one player per side with its own node budget; the transposition tables start empty. */
  newGame(setup: GameSetup): void {
    const variant = variantOf(setup);
    this.requireTable(variant);
    const [n0, n1, n2] = seatNodes(setup) as [number, number, number | undefined];
    fields(this.m.newGame(n0, n1, variant.jump, String(variant.players), n2 ?? 0, rankingOf(variant)));
    this.gameVariant = variant;
  }

  legalMoves(history: readonly number[], variant: Variant = DEFAULT_VARIANT): number[] {
    this.requireTable(variant);
    return numbers(fields(this.m.legalMoves(this.encode(history), variant.jump, String(variant.players), rankingOf(variant)))[0]);
  }

  /** Plays for the side to move with the variant of the last newGame (`variant`, if given, must equal it). */
  choose(history: readonly number[], variant?: Variant): ChooseResult {
    // Without newGame the facade would play with its default budget; the page always calls newGame first.
    if (!this.gameVariant) throw new EngineFailure('internal', 'newGame was not called');
    variant ??= this.gameVariant;
    this.requireTable(variant);
    const f = fields(this.m.choose(this.encode(history), variant.jump, String(variant.players), rankingOf(variant)));
    return this.parseChoice(f);
  }

  /**
   * `ongoing` (with the seats that are out, in full ranking), a winner (seat 0, 1 or 2), `draw` (move limit, repetition, no
   * legal move) or, in a full-ranking game of three players, the final `ranking`.
   */
  outcome(history: readonly number[], variant: Variant = DEFAULT_VARIANT): EngineOutcome {
    this.requireTable(variant);
    const f = fields(this.m.outcome(this.encode(history), variant.jump, String(variant.players), rankingOf(variant)));
    if (f[0] === 'ongoing') return ongoingOf(f.length === 3 && f[1] === 'finished' ? Number(f[2]) : 0, f);
    if (f[0] === 'ranking') return rankingOutcome(f.slice(1).map(Number), f);
    if (f[0] === 'draw') return { kind: 'draw' };
    const winner = Number(f[1]);
    if (f[0] === 'win' && Number.isInteger(winner) && winner >= 0 && winner < variant.players) return { kind: 'win', winner: winner as Player };
    throw new EngineFailure('internal', `unexpected outcome reply: ${f.join(' ')}`);
  }

  /**
   * Scores moves of the position after `history` for the side to move with the facade's analysis player: a player of its
   * own, so the game players, their tables and choose() results stay untouched. `moves` null scores all legal moves.
   * `nodes` is the budget for each scored move.
   */
  analyse(history: readonly number[], moves: readonly number[] | null, nodes: number, variant: Variant = DEFAULT_VARIANT): AnalysisResult {
    this.requireTable(variant);
    if (!Number.isInteger(nodes) || nodes < 1) throw new EngineFailure('internal', `node budget must be a positive integer, got ${nodes}`);
    if (moves !== null && (moves.length === 0 || !moves.every((m) => Number.isInteger(m) && m >= 0))) {
      throw new EngineFailure('illegal-move', 'the move list is empty or not made of moves');
    }
    const f = fields(this.m.analyse(this.encode(history), moves === null ? '' : moves.join(','), nodes, variant.jump, String(variant.players), rankingOf(variant)));
    const visited = Number(f[0]);
    const ms = Number(f[1]);
    const rows1 = Number(f[2]);
    const scored = numbers(f[3]);
    const scores = numbers(f[4]);
    if (f.length !== 5 || ![visited, ms, rows1].every(Number.isFinite) || scored.length === 0 || scored.length !== scores.length) {
      throw new EngineFailure('internal', `unexpected analyse reply: ${f.join(' ').slice(0, 120)}`);
    }
    if (rows1 > 0) throw new EngineFailure('rows1-requested', `${rows1} request(s) for rows1.bin (ADR 0001)`);
    return { scores, moves: scored, position: Math.max(...scores), nodes: visited, ms };
  }

  /**
   * The static evaluation of the three-player position after `history`: three integers, one per absolute player (seat 0, 1,
   * 2), each the leaf score of the three-player search for that seat (see analysis.ts). No search. Rejects with code
   * unsupported for two players.
   */
  evaluate3(history: readonly number[], variant: Variant): number[] {
    requireThreePlayers(variant);
    const f = fields(this.m.evaluate3(this.encode(history), variant.jump, rankingOf(variant)));
    const values = f.map(Number);
    if (values.length !== 3 || !values.every(Number.isInteger)) {
      throw new EngineFailure('internal', `unexpected evaluate3 reply: ${f.join(' ').slice(0, 120)}`);
    }
    return values;
  }

  /**
   * The benchmark game of plan section 8: `plies` plies from the start position, both sides with `nodes` per move,
   * one player per side for the whole game. Does not touch the players of the current game.
   */
  bench(plies: number, nodes: number, variant: Variant = DEFAULT_VARIANT): BenchResult {
    this.requireTable(variant);
    const f = fields(this.m.bench(plies, nodes, variant.jump, String(variant.players), rankingOf(variant)));
    const result: BenchResult = { moves: numbers(f[0]), nodes: numbers(f[1]), ms: numbers(f[2]), rows1Requests: Number(f[3]) };
    if (result.rows1Requests > 0) throw new EngineFailure('rows1-requested', `${result.rows1Requests} request(s) for rows1.bin (ADR 0001)`);
    result.memory = sampleMemory(null); // no linear memory to read: the JS target uses the JS heap, Wasm GC its own GC heap
    return result;
  }

  // ---- internals ----

  private parseChoice(f: string[]): ChooseResult {
    const [move, score, depth, nodes, ms, race, rows1] = f.map(Number) as [number, number, number, number, number, number, number];
    if (f.length !== 7 || [move, score, depth, nodes, ms, race, rows1].some((v) => !Number.isFinite(v))) {
      throw new EngineFailure('internal', `unexpected choose reply: ${f.join(' ')}`);
    }
    if (rows1 > 0) throw new EngineFailure('rows1-requested', `${rows1} request(s) for rows1.bin (ADR 0001)`);
    return { move, score, depth, nodes, ms, raceMove: race === 1, rows1Requests: rows1 };
  }

  private encode(history: readonly number[]): string {
    if (history.length > MAX_PLIES) throw new EngineFailure('illegal-history', `history has ${history.length} plies`);
    for (const [ply, move] of history.entries()) {
      if (!Number.isInteger(move) || move < 0) throw new EngineFailure('illegal-history', `move ${move} at ply ${ply} is not a move`);
    }
    return history.join(',');
  }

  /** Only the adjacent two-player game needs the race table (see needsTable). */
  private requireTable(variant: Variant = DEFAULT_VARIANT): void {
    if (needsTable(variant) && !this.tableInstalled) throw new EngineFailure('internal', 'the race table is not installed');
  }
}
