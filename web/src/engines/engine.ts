// The one interface every engine adapter implements (C++ Wasm, TeaVM Wasm GC, TeaVM JS, server).
// Moves use the engine's own encoding: from | (to << 8), cell ids 0 to 120 (sternhalma.engine.Move).

export type EngineId = 'cpp-wasm' | 'java-wasmgc' | 'java-js' | `server:${string}`;

export interface EngineInfo {
  id: EngineId;
  /** i18n key, e.g. "engine.cppWasm". */
  labelKey: string;
  runtime: 'wasm' | 'wasm-gc' | 'js' | 'server';
  language: 'C++' | 'Java';
  /** git describe of the build, from engines.json. */
  version: string;
  /** Sum of the shipped files, raw; from engines.json. */
  binaryBytes: number;
  binaryGzipBytes: number;
}

/** All values in milliseconds, measured inside the worker. */
export interface LoadReport {
  fetchMs: number;
  inflateMs: number;
  verifyMs: number;
  instantiateMs: number;
  tableInstallMs: number;
  totalMs: number;
  tableVerifiedBy: 'subtle-sha256' | 'engine' | 'length-only';
}

export interface GameSetup {
  /** Node budget per move for side 0 and side 1. */
  nodes: [number, number];
}

export interface ChooseResult {
  move: number;
  score: number;
  depth: number;
  /** Nodes searched for this move. */
  nodes: number;
  /** Wall time of the search call inside the worker. */
  ms: number;
  /** The move came from race play, not from search. */
  raceMove: boolean;
  /** ADR 0001 guard; must stay 0. */
  rows1Requests: number;
}

export interface Progress {
  phase: 'fetch' | 'inflate' | 'verify' | 'instantiate' | 'table';
  loaded: number;
  total: number;
}

/** Result of the benchmark game that runs inside the worker (request type "bench"). */
export interface BenchResult {
  /** The moves played, in order. */
  moves: number[];
  /** Nodes per ply. */
  nodes: number[];
  /** Wall time of each choose call in ms. */
  ms: number[];
  rows1Requests: number;
  /** Memory sizes the adapter can read cheaply: Wasm linear memory (C++) and the JS heap (Chrome). Null where unavailable. */
  memory?: { wasmBytes: number | null; jsHeapBytes: number | null };
}

export interface Engine {
  readonly info: EngineInfo;
  init(onProgress?: (p: Progress) => void): Promise<LoadReport>;
  newGame(setup: GameSetup): Promise<void>;
  /** The history is the whole game state: moves from the start position, oldest first. */
  legalMoves(history: readonly number[]): Promise<number[]>;
  /** Rejects with an AbortError when the signal fires; the adapter then restarts its worker. */
  choose(history: readonly number[], signal?: AbortSignal): Promise<ChooseResult>;
  dispose(): void;
}

export const moveFrom = (move: number): number => move & 0xff;
export const moveTo = (move: number): number => move >>> 8;
export const makeMove = (from: number, to: number): number => from | (to << 8);
