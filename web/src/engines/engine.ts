// The one interface every engine adapter implements (C++ Wasm, TeaVM Wasm GC, TeaVM JS, server).
// Moves use the engine's own encoding: from | (to << 8), cell ids 0 to 120 (sternhalma.engine.Move).

import type { Player, Variant } from '../game/variant';

import type { EngineWarning, NetOptions } from './warnings';

export type EngineId = 'cpp-wasm' | 'java-wasmgc' | 'java-js' | 'net-wasm' | 'net-webgpu' | `server:${string}`;

/** The net engines: MCTS with a neural network, no node budget (see GameSetup.netOptions). */
export const isNetEngine = (id: EngineId): id is 'net-wasm' | 'net-webgpu' => id === 'net-wasm' || id === 'net-webgpu';

export interface EngineInfo {
  id: EngineId;
  /** i18n key, e.g. "engine.cppWasm". */
  labelKey: string;
  runtime: 'wasm' | 'wasm-gc' | 'js' | 'server';
  language: 'C++' | 'Java' | 'Java + C++';
  /** git describe of the build, from engines.json. */
  version: string;
  /** Sum of the shipped files, raw; from engines.json. */
  binaryBytes: number;
  binaryGzipBytes: number;
  /** Net engines: gzip bytes of a typical load (facade and one CPU flavour), from engines.json. The sum above counts every flavour. */
  downloadGzipBytes?: number;
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
  /** Node budget per move for each seat: two entries for two players, three for three (a missing third entry is the second). */
  nodes: readonly number[];
  /** The game variant; missing means the default (adjacent jumps, two players). */
  variant?: Variant;
  /**
   * Net engines only (other engines ignore both fields; they are absent in every classical request): the id of the model
   * (web/models/models.json), null or missing for none, and the search settings. `nodes` is not used by net engines.
   */
  model?: string | null;
  netOptions?: NetOptions;
}

/** What Engine.outcome reports: the game goes on, a seat has won, or it ended without a winner (move limit, repetition, no legal move). */
export type EngineOutcome = { kind: 'ongoing' } | { kind: 'win'; winner: Player } | { kind: 'draw' };

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
  /** Net engines only: what played the move. 'random' is the fallback (the warnings say why). `score` is the mean root value in [-1, 1]; `nodes` the tree size. */
  net?: { backend: 'wasm' | 'webgpu' | 'random'; sims: number; batches: number };
  /** Net engines only: the warnings that hold after this move (a device loss during the move shows up here). */
  warnings?: EngineWarning[];
}

/** Result of Engine.analyse: a score for each analysed move. */
export interface AnalysisResult {
  /** One score per move, in the order of `moves`: the same unit as ChooseResult.score, from the point of view of the side that makes the move (higher is better for the mover). See analysis.ts. */
  scores: number[];
  /** The moves that were scored: the argument of analyse, or all legal moves in the order of legalMoves. */
  moves: number[];
  /** Score of the position for the side to move: the best of `scores`. */
  position: number;
  /** Nodes searched, all moves together (each move gets the node budget of the call). */
  nodes: number;
  /** Wall time of the analysis inside the worker. */
  ms: number;
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
  /** Net engines: the warnings that hold now (set by newGame and choose). Absent or empty for the others. */
  readonly warnings?: readonly EngineWarning[];
  /** Net engines: calls the listener when `warnings` changed. Returns the unsubscribe function. */
  subscribeWarnings?(listener: () => void): () => void;
  init(onProgress?: (p: Progress) => void): Promise<LoadReport>;
  newGame(setup: GameSetup): Promise<void>;
  /**
   * The history is the whole game state: moves from the start position, oldest first. `variant` (default: adjacent jumps, two
   * players) is passed with every call that replays a history, so the call is stateless; `choose` without a variant uses the
   * variant of the last `newGame`. Engines that cannot play a variant reject with code unsupported.
   */
  legalMoves(history: readonly number[], variant?: Variant): Promise<number[]>;
  /** Rejects with an AbortError when the signal fires; the adapter then restarts its worker. */
  choose(history: readonly number[], signal?: AbortSignal, variant?: Variant): Promise<ChooseResult>;
  /**
   * Scores moves of the position after `history` for the side to move, with a dedicated analysis player (never the game
   * players: their tables, budgets and choose() results stay as they are). `moves` null means all legal moves. `nodes` is
   * the node budget for each scored move. Rejects with an AbortError when the signal fires; the adapter then restarts its worker.
   * Rejects with code illegal-move if a move is not legal, illegal-history if the game is over.
   * Rejects with code unsupported for three players (analysis is two-player only).
   */
  analyse(history: readonly number[], moves: readonly number[] | null, nodes: number, signal?: AbortSignal, variant?: Variant): Promise<AnalysisResult>;
  /** Whether the game after `history` goes on, is won (by which seat) or drawn. Rejects with illegal-history for an illegal history. */
  outcome(history: readonly number[], variant?: Variant): Promise<EngineOutcome>;
  dispose(): void;
}

export const moveFrom = (move: number): number => move & 0xff;
export const moveTo = (move: number): number => move >>> 8;
export const makeMove = (from: number, to: number): number => from | (to << 8);
