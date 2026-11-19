// Worker message protocol: one request, one reply, matched by id. Progress events carry the id of the running request.
import type { BenchResult, EngineOutcome, GameSetup, Progress } from './engine';
import type { Variant } from '../game/variant';

export interface AssetUrls {
  /** URLs of the engine files, in the order the worker glue expects. */
  engine: string[];
  manifest: string;
  tableGz: string;
  /** Net engines only: directory of the packed model files with a trailing slash. Default: `${BASE_URL}models/` of the worker. */
  modelsBaseUrl?: string;
}

export type Request =
  /** table: bytes the main thread already holds (transferred), or null to fetch them in the worker. */
  /** backend: net engines only, the backend the engine wants ('wasm' CPU or 'webgpu'); absent for the classical engines. The model and the search settings come with newGame (GameSetup.model, GameSetup.netOptions). */
  | { id: number; type: 'init'; assets: AssetUrls; table: ArrayBuffer | null; backend?: 'wasm' | 'webgpu' }
  | { id: number; type: 'newGame'; setup: GameSetup }
  | { id: number; type: 'legalMoves'; history: number[]; variant?: Variant }
  | { id: number; type: 'choose'; history: number[]; variant?: Variant }
  /** moves: null scores all legal moves. */
  | { id: number; type: 'analyse'; history: number[]; moves: number[] | null; nodes: number; variant?: Variant }
  /** Ongoing, won or drawn after the history. */
  | { id: number; type: 'outcome'; history: number[]; variant?: Variant }
  /** Runs the benchmark game inside the worker. */
  | { id: number; type: 'bench'; plies: number; nodes: number; variant?: Variant };

export type ErrorCode =
  | 'table-checksum'
  | 'table-truncated'
  | 'table-trailing'
  | 'table-magic'
  | 'wasm-gc-unsupported'
  | 'illegal-history'
  | 'illegal-move'
  | 'rows1-requested'
  | 'unsupported'
  | 'aborted'
  | 'internal';

export interface EngineError {
  code: ErrorCode;
  /** English, for logs. The UI maps the code to an i18n text. */
  message: string;
}

/** result: LoadReport, number[], ChooseResult, AnalysisResult, EngineOutcome, BenchResult, or null. */
export type Reply =
  | { id: number; ok: true; result: unknown }
  | { id: number; ok: false; error: EngineError };

export type WorkerEvent = { id: number; type: 'progress'; progress: Progress };

export type WorkerOut = Reply | WorkerEvent;

export function isEvent(message: WorkerOut): message is WorkerEvent {
  return 'type' in message && message.type === 'progress';
}

export type { BenchResult };

/** An error with a protocol code. Adapters throw it; the worker glue turns it into a failed Reply. */
export class EngineFailure extends Error {
  readonly code: ErrorCode;
  constructor(code: ErrorCode, message: string) {
    super(message);
    this.name = 'EngineFailure';
    this.code = code;
  }
  toError(): EngineError {
    return { code: this.code, message: this.message };
  }
}

/** Maps any thrown value to a protocol error; unknown values become "internal". */
export function toEngineError(e: unknown): EngineError {
  if (e instanceof EngineFailure) return e.toError();
  const message = e instanceof Error ? e.message : String(e);
  return { code: 'internal', message };
}

type DistributiveOmit<T, K extends PropertyKey> = T extends unknown ? Omit<T, K> : never;

/** A request without its id; the client adds the id. */
export type RequestBody = DistributiveOmit<Request, 'id'>;
