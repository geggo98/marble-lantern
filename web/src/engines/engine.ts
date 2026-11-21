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

/**
 * What Engine.outcome reports.
 * - `ongoing`: the game goes on. `finished` is present only with full ranking, after a seat has filled its goal: the seats
 *   that are out, in finishing order (the others play for the lower ranks).
 * - `win`: a seat has won; the game is over (first-winner rules, and every two-player game).
 * - `draw`: the game ended without a winner (move limit, repetition, no legal move); first-winner rules only.
 * - `ranking`: a full-ranking game of three players is over. The seats by rank, first place first. A move limit, a
 *   repetition and a seat without a legal move end such a game with a ranking too: the seats that are not out are ranked by
 *   their distance to the goal.
 */
export type EngineOutcome =
  | { kind: 'ongoing'; finished?: readonly Player[] }
  | { kind: 'win'; winner: Player }
  | { kind: 'draw' }
  | { kind: 'ranking'; ranking: readonly [Player, Player, Player] };

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
  net?: {
    backend: 'wasm' | 'webgpu' | 'random';
    sims: number;
    batches: number;
    /** The root moves with the visits and the mean value of each (for the side that plays it), in the same order. `values` is absent when the engine file is older than this field. */
    root?: { moves: number[]; visits: number[]; values?: number[] };
  };
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

/** Result of Engine.features: the linear-r3 evaluation of a classic position, broken down (classical engines only). */
export interface FeatureReport {
  /** The 13 features of linear-r3 in the order of LinearEvaluator.GAIN_NAMES, side to move minus opponent. */
  features: number[];
  /** Evaluators.byName("linear-r3").evaluate: the rounded score for the side to move. */
  score: number;
  sideToMove: 0 | 1;
  /** distance[p][cell]: single-agent distance of a piece of player p on that cell to p's goal corner (Layout.distance). */
  distance: [number[], number[]];
}

/** Result of Engine.netForward: the reference forward pass of the network on the root position (net engines only). */
export interface NetForwardReport {
  modelId: string;
  /** planes[plane][cell], absolute cells. */
  planes: number[][];
  /** One entry per trunk layer: conv_in, block0 ... blockN; norm[cell] is the L2 norm over the channels after the ReLU. */
  layers: { id: string; norm: number[] }[];
  /** Raw policy logits without the classical prior, absolute cells. */
  logitsNetOnly: { from: number[]; to: number[] };
  /** The legal moves with their prior with and without the classical prior (softmax over the legal moves). */
  policy: { move: number; prior: number; priorNetOnly: number }[];
  /** Expected result of each absolute player in [-1, 1], with and without the value prior. */
  values: number[];
  valuesNetOnly: number[];
  canonicalRotated: boolean;
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
   * Works for two and three players on the classical engines. With three players the scores are paranoid scores from the
   * mover's point of view (see analysis.ts). The net engines and the server engines reject with code unsupported.
   */
  analyse(history: readonly number[], moves: readonly number[] | null, nodes: number, signal?: AbortSignal, variant?: Variant): Promise<AnalysisResult>;
  /**
   * The static evaluation of the three-player position after `history`: three integers, one per absolute player (seat 0, 1,
   * 2), higher is better for that seat, no search (see analysis.ts). Offered by the classical engines (C++ and Java); absent on
   * the others. Rejects with code unsupported for two players, illegal-history for an illegal history.
   */
  evaluate3?(history: readonly number[], variant?: Variant): Promise<number[]>;
  /**
   * The linear-r3 features of a classic position and the per-cell distances (Info page, live mode). Offered by the classical engines;
   * absent on the others. Rejects with code unsupported for a variant that is not classic, illegal-history for an illegal history.
   */
  features?(history: readonly number[], variant?: Variant): Promise<FeatureReport>;
  /**
   * Net engines: encodes the position after `history`, runs the reference forward pass on the loaded model and reports planes,
   * layers and heads. Rejects with code unsupported when there is nothing to evaluate (game over, one legal move). Aborting
   * restarts the worker, like choose.
   */
  netForward?(history: readonly number[], signal?: AbortSignal): Promise<NetForwardReport>;
  /** Whether the game after `history` goes on, is won (by which seat) or drawn. Rejects with illegal-history for an illegal history. */
  outcome(history: readonly number[], variant?: Variant): Promise<EngineOutcome>;
  dispose(): void;
}

export const moveFrom = (move: number): number => move & 0xff;
export const moveTo = (move: number): number => move >>> 8;
export const makeMove = (from: number, to: number): number => from | (to << 8);
