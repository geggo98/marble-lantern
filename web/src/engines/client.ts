// Wraps a Worker into an Engine: id counter, pending map, progress routing, terminate-and-restart on abort.
import type { AnalysisResult, ChooseResult, Engine, FeatureReport, NetForwardReport, EngineInfo, EngineOutcome, GameSetup, LoadReport, Progress } from './engine';
import { DEFAULT_VARIANT } from '../game/variant';
import type { Variant } from '../game/variant';
import { isEvent, EngineFailure } from './protocol';
import type { AssetUrls, Reply, Request, RequestBody, WorkerOut } from './protocol';
import type { LoadedTable } from './tables';
import type { EngineWarning } from './warnings';

/** The part of a Worker the client uses. A test passes an in-process fake. */
export interface WorkerLike {
  postMessage(message: Request, transfer?: Transferable[]): void;
  terminate(): void;
  onmessage: ((event: { data: WorkerOut }) => void) | null;
  onerror: ((event: { message?: string }) => void) | null;
}

export interface ClientOptions {
  info: EngineInfo;
  createWorker: () => WorkerLike;
  assets: AssetUrls;
  /**
   * Loads and checks the table in the main thread. The client keeps the bytes, so that a restart after an abort
   * does not download again. Without it the worker fetches the table itself (and a restart fetches again).
   */
  loadTable?: (onProgress?: (p: Progress) => void) => Promise<LoadedTable>;
  /** Net engines: the backend the worker should use; goes into the init request. Absent for the classical engines. */
  backend?: 'wasm' | 'webgpu';
}

interface Pending {
  resolve: (value: unknown) => void;
  reject: (reason: unknown) => void;
  onProgress?: (p: Progress) => void;
}

const abortError = (): Error => new DOMException('The search was aborted', 'AbortError');

export class WorkerEngine implements Engine {
  readonly info: EngineInfo;
  private readonly options: ClientOptions;
  private worker: WorkerLike | null = null;
  private nextId = 1;
  private pending = new Map<number, Pending>();
  private table: LoadedTable | null = null;
  private setup: GameSetup | null = null;
  private initialised = false;
  private disposed = false;
  /** Resolves when the current worker can take requests. A restart replaces it. */
  private ready: Promise<void> = Promise.resolve();

  constructor(options: ClientOptions) {
    this.options = options;
    this.info = options.info;
  }

  /** Number of workers created so far (a restart makes a new one). */
  workersStarted = 0;

  async init(onProgress?: (p: Progress) => void): Promise<LoadReport> {
    if (this.disposed) throw new EngineFailure('internal', 'engine is disposed');
    const t0 = performance.now();
    if (this.options.loadTable && !this.table) {
      this.table = await this.options.loadTable(onProgress);
    }
    const report = await this.startWorker(onProgress);
    this.initialised = true;
    const main = this.table;
    if (!main) return report;
    return {
      fetchMs: report.fetchMs + main.fetchMs,
      inflateMs: report.inflateMs + main.inflateMs,
      verifyMs: report.verifyMs + main.verifyMs,
      instantiateMs: report.instantiateMs,
      tableInstallMs: report.tableInstallMs,
      totalMs: performance.now() - t0,
      tableVerifiedBy: main.verifiedBy === 'subtle-sha256' ? 'subtle-sha256' : report.tableVerifiedBy,
    };
  }

  /** Net engines: the warnings of the last newGame or choose. Always empty for the classical engines. */
  warnings: readonly EngineWarning[] = [];
  private warningListeners = new Set<() => void>();

  subscribeWarnings(listener: () => void): () => void {
    this.warningListeners.add(listener);
    return () => this.warningListeners.delete(listener);
  }

  private setWarnings(next: readonly EngineWarning[]): void {
    if (JSON.stringify(next) === JSON.stringify(this.warnings)) return;
    this.warnings = next;
    for (const l of this.warningListeners) l();
  }

  /** A net worker answers newGame with { warnings }; every other worker with null. */
  private takeWarnings(result: unknown): void {
    const w = (result as { warnings?: unknown } | null)?.warnings;
    if (Array.isArray(w)) this.setWarnings(w as EngineWarning[]);
  }

  newGame(setup: GameSetup): Promise<void> {
    this.setup = setup;
    return this.call({ type: 'newGame', setup }).then((r) => this.takeWarnings(r));
  }

  legalMoves(history: readonly number[], variant: Variant = DEFAULT_VARIANT): Promise<number[]> {
    return this.call({ type: 'legalMoves', history: [...history], variant }) as Promise<number[]>;
  }

  async outcome(history: readonly number[], variant: Variant = DEFAULT_VARIANT): Promise<EngineOutcome> {
    return (await this.call({ type: 'outcome', history: [...history], variant })) as EngineOutcome;
  }

  async choose(history: readonly number[], signal?: AbortSignal, variant?: Variant): Promise<ChooseResult> {
    const v = variant ?? this.setup?.variant ?? DEFAULT_VARIANT;
    const result = (await this.abortable({ type: 'choose', history: [...history], variant: v }, signal)) as ChooseResult;
    if (result.warnings) this.setWarnings(result.warnings);
    return result;
  }

  async analyse(history: readonly number[], moves: readonly number[] | null, nodes: number, signal?: AbortSignal, variant: Variant = DEFAULT_VARIANT): Promise<AnalysisResult> {
    const request: RequestBody = { type: 'analyse', history: [...history], moves: moves === null ? null : [...moves], nodes, variant };
    return (await this.abortable(request, signal)) as AnalysisResult;
  }

  async evaluate3(history: readonly number[], variant: Variant = DEFAULT_VARIANT): Promise<number[]> {
    return (await this.call({ type: 'evaluate3', history: [...history], variant })) as number[];
  }

  async features(history: readonly number[], variant: Variant = DEFAULT_VARIANT): Promise<FeatureReport> {
    return (await this.call({ type: 'features', history: [...history], variant })) as FeatureReport;
  }

  async netForward(history: readonly number[], signal?: AbortSignal): Promise<NetForwardReport> {
    return (await this.abortable({ type: 'netForward', history: [...history] }, signal)) as NetForwardReport;
  }

  /** A long request that the caller may abort: cancel means terminate the worker and start a new one. */
  private async abortable(request: RequestBody, signal?: AbortSignal): Promise<unknown> {
    if (signal?.aborted) throw abortError();
    if (!this.initialised) throw new EngineFailure('internal', 'engine is not initialised');
    // After an abort the new worker needs its init and the replayed newGame first.
    await this.ready;
    if (signal?.aborted) throw abortError();
    const { id, promise } = this.send(request);
    if (!signal) return await promise;
    const onAbort = (): void => {
      this.restart();
      const p = this.pending.get(id);
      if (p) {
        this.pending.delete(id);
        p.reject(abortError());
      }
    };
    signal.addEventListener('abort', onAbort, { once: true });
    try {
      return await promise;
    } finally {
      signal.removeEventListener('abort', onAbort);
    }
  }

  dispose(): void {
    this.disposed = true;
    this.worker?.terminate();
    this.worker = null;
    this.failAll(new EngineFailure('internal', 'engine is disposed'));
  }

  // ---- internals ----

  private createWorker(): WorkerLike {
    const w = this.options.createWorker();
    this.workersStarted++;
    w.onmessage = (event) => this.onMessage(w, event.data);
    w.onerror = (event) => {
      if (this.worker === w) this.failAll(new EngineFailure('internal', event.message ?? 'worker error'));
    };
    return w;
  }

  private onMessage(from: WorkerLike, message: WorkerOut): void {
    if (from !== this.worker) return; // a terminated worker's late message
    const p = this.pending.get(message.id);
    if (!p) return;
    if (isEvent(message)) {
      p.onProgress?.(message.progress);
      return;
    }
    this.pending.delete(message.id);
    const reply: Reply = message;
    if (reply.ok) p.resolve(reply.result);
    else p.reject(new EngineFailure(reply.error.code, reply.error.message));
  }

  private failAll(reason: unknown): void {
    const all = [...this.pending.values()];
    this.pending.clear();
    for (const p of all) p.reject(reason);
  }

  /** Posts a request to the current worker. Not queued behind a restart: use call() for that. */
  private send(
    request: RequestBody,
    onProgress?: (p: Progress) => void,
    transfer?: Transferable[],
  ): { id: number; promise: Promise<unknown> } {
    const id = this.nextId++;
    const promise = new Promise<unknown>((resolve, reject) => {
      this.pending.set(id, { resolve, reject, onProgress });
    });
    const worker = this.worker;
    if (!worker) {
      this.pending.delete(id);
      return { id, promise: Promise.reject(new EngineFailure('internal', 'engine is not initialised')) };
    }
    worker.postMessage({ id, ...request } as Request, transfer);
    return { id, promise };
  }

  private async call(request: RequestBody): Promise<unknown> {
    if (!this.initialised) throw new EngineFailure('internal', 'engine is not initialised');
    await this.ready;
    return this.send(request).promise;
  }

  private startWorker(onProgress?: (p: Progress) => void): Promise<LoadReport> {
    // A second init (the retry after a failed one) must not leave the first worker running.
    this.worker?.terminate();
    this.worker = this.createWorker();
    // A copy is transferred; the main thread keeps the original for the next restart.
    const copy = this.table ? this.table.bytes.slice(0) : null;
    const { promise } = this.send(
      { type: 'init', assets: this.options.assets, table: copy, ...(this.options.backend ? { backend: this.options.backend } : {}) },
      onProgress,
      copy ? [copy] : undefined,
    );
    return promise as Promise<LoadReport>;
  }

  /** Cancel means terminate: kill the worker, start a new one, init it with the kept table, replay newGame. */
  private restart(): void {
    if (this.disposed) return;
    this.worker?.terminate();
    this.worker = null;
    this.failAllExcept();
    this.ready = (async () => {
      await this.startWorker();
      if (this.setup) this.takeWarnings(await this.send({ type: 'newGame', setup: this.setup }).promise);
    })();
    this.ready.catch(() => undefined); // each later call awaits it and sees the error there
  }

  /** Rejects every request of the terminated worker except the ones that are rejected by the caller. */
  private failAllExcept(): void {
    // The aborted choose is rejected by its own handler; nothing else runs in parallel in the UI,
    // but a leftover request must not hang forever.
    const rest = [...this.pending.entries()];
    for (const [id, p] of rest) {
      queueMicrotask(() => {
        if (this.pending.get(id) === p) {
          this.pending.delete(id);
          p.reject(abortError());
        }
      });
    }
  }
}
