// A BenchTarget on top of a real engine worker, for the browser page. It speaks the worker protocol directly
// (init, then bench) instead of going through WorkerEngine, which plays single moves for the UI and has no bench call.
import type { BenchResult, LoadReport, Progress } from '../engines/engine';
import { isEvent } from '../engines/protocol';
import type { AssetUrls, Request, RequestBody, WorkerOut } from '../engines/protocol';
import type { EngineFile } from '../engines/registry';
import type { BenchTarget } from './core';

export interface WorkerTargetOptions {
  id: string;
  info: BenchTarget['info'];
  worker: Worker;
  files: EngineFile[];
  baseUrl: string;
}

export class WorkerTarget implements BenchTarget {
  readonly id: string;
  readonly info: BenchTarget['info'];
  private readonly worker: Worker;
  private readonly assets: AssetUrls;
  private nextId = 1;
  private readonly pending = new Map<number, { resolve(v: unknown): void; reject(e: Error): void; onProgress?: (p: Progress) => void }>();

  constructor(o: WorkerTargetOptions) {
    this.id = o.id;
    this.info = o.info;
    this.worker = o.worker;
    const url = (path: string): string => o.baseUrl + path;
    this.assets = { engine: o.files.map((f) => url(f.path)), manifest: url('tables/rows2.manifest.json'), tableGz: url('tables/rows2.bin.gz') };
    this.worker.onmessage = (event: MessageEvent<WorkerOut>) => {
      const message = event.data;
      const p = this.pending.get(message.id);
      if (!p) return;
      if (isEvent(message)) return p.onProgress?.(message.progress);
      this.pending.delete(message.id);
      if (message.ok) p.resolve(message.result);
      else p.reject(new Error(`${message.error.code}: ${message.error.message}`));
    };
    this.worker.onerror = (event) => {
      const error = new Error(event.message || 'worker error');
      for (const p of this.pending.values()) p.reject(error);
      this.pending.clear();
    };
  }

  private call(body: RequestBody, onProgress?: (p: Progress) => void): Promise<unknown> {
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject, onProgress });
      this.worker.postMessage({ id, ...body } as Request);
    });
  }

  /** The worker fetches, inflates and checks the table itself (table: null) and reports the load times. */
  init(onProgress?: (p: Progress) => void): Promise<LoadReport> {
    return this.call({ type: 'init', assets: this.assets, table: null }, onProgress) as Promise<LoadReport>;
  }

  bench(plies: number, nodes: number): Promise<BenchResult> {
    return this.call({ type: 'bench', plies, nodes }) as Promise<BenchResult>;
  }

  dispose(): void {
    this.worker.terminate();
  }
}
