// Drives the TeaVM facade sternhalma.web.Web (teavm-web/). Works in node and in a worker: it does not touch `self`,
// `postMessage` or the DOM. The glue for the worker is worker.ts, the loading of the module is in handler.ts and node.ts.
//
// The facade exports string-only methods that never throw (see Web.java): the reply is "ok <fields>" or
// "error <code> <message>". The move history is the game state and crosses the boundary as "1,2,3".
import type { BenchResult, ChooseResult, GameSetup } from '../engine';
import { EngineFailure } from '../protocol';
import type { ErrorCode } from '../protocol';

/** The exports of engine-java.js (JS target) and of `TeaVM.wasmGC.load(...).exports` (Wasm GC target). */
export interface JavaModule {
  /** `file` is the inflated table; `verify` checks the SHA-256 of the body against the one in the file header. */
  setTable(file: Int8Array, verify: boolean): string;
  newGame(nodes0: number, nodes1: number): string;
  legalMoves(history: string): string;
  choose(history: string): string;
  bench(plies: number, nodes: number): string;
}

const CODES: ReadonlySet<string> = new Set<ErrorCode>([
  'table-checksum', 'table-truncated', 'table-trailing', 'table-magic', 'wasm-gc-unsupported',
  'illegal-history', 'rows1-requested', 'aborted', 'internal',
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

const numbers = (list: string | undefined): number[] => (list ? list.split(',').map(Number) : []);

const MAX_PLIES = 1000;

export class JavaCore {
  private readonly m: JavaModule;
  private tableInstalled = false;
  private gameStarted = false;

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
    this.gameStarted = false;
    return now() - t0;
  }

  /** Starts a game: one player per side with its own node budget; the transposition tables start empty. */
  newGame(setup: GameSetup): void {
    this.requireTable();
    fields(this.m.newGame(setup.nodes[0], setup.nodes[1]));
    this.gameStarted = true;
  }

  legalMoves(history: readonly number[]): number[] {
    this.requireTable();
    return numbers(fields(this.m.legalMoves(this.encode(history)))[0]);
  }

  choose(history: readonly number[]): ChooseResult {
    this.requireTable();
    // Without newGame the facade would play with its default budget; the page always calls newGame first.
    if (!this.gameStarted) throw new EngineFailure('internal', 'newGame was not called');
    const f = fields(this.m.choose(this.encode(history)));
    return this.parseChoice(f);
  }

  /**
   * The benchmark game of plan section 8: `plies` plies from the start position, both sides with `nodes` per move,
   * one player per side for the whole game. Does not touch the players of the current game.
   */
  bench(plies: number, nodes: number): BenchResult {
    this.requireTable();
    const f = fields(this.m.bench(plies, nodes));
    const result: BenchResult = { moves: numbers(f[0]), nodes: numbers(f[1]), ms: numbers(f[2]), rows1Requests: Number(f[3]) };
    if (result.rows1Requests > 0) throw new EngineFailure('rows1-requested', `${result.rows1Requests} request(s) for rows1.bin (ADR 0001)`);
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

  private requireTable(): void {
    if (!this.tableInstalled) throw new EngineFailure('internal', 'the race table is not installed');
  }
}
