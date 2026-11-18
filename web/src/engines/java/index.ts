// The registry definitions of the two Java engines: TeaVM Wasm GC and TeaVM JavaScript (the fallback when the
// browser has no Wasm GC). Importing this module registers both: `import './engines/java'` (or registerJavaEngines()).
import { WorkerEngine } from '../client';
import type { WorkerLike } from '../client';
import { registerEngine, wasmGcSupported } from '../registry';
import type { EngineContext, EngineDefinition } from '../registry';
import { loadTable } from '../tables';

const workerAvailable = (): boolean => typeof Worker === 'function';

function create(context: EngineContext) {
  const url = (path: string): string => context.baseUrl + path;
  // Wasm GC: engine-java.wasm and its runtime; JS: engine-java.js. The handler picks the target from the file names.
  const assets = {
    engine: context.files.map((f) => url(f.path)),
    manifest: url('tables/rows2.manifest.json'),
    tableGz: url('tables/rows2.bin.gz'),
  };
  return new WorkerEngine({
    info: context.info,
    assets,
    createWorker: () => new Worker(new URL('./worker.ts', import.meta.url), { type: 'module' }) as unknown as WorkerLike,
    // The main thread keeps the inflated table, so that "cancel means terminate" does not download again.
    loadTable: (onProgress) =>
      loadTable({ manifestUrl: assets.manifest, tableUrl: assets.tableGz, engineChecksum: true, onProgress }),
  });
}

export const javaWasmGcDefinition: EngineDefinition = {
  id: 'java-wasmgc',
  labelKey: 'engine.javaWasmGc',
  runtime: 'wasm-gc',
  language: 'Java',
  isAvailable: () => workerAvailable() && wasmGcSupported(),
  create,
};

export const javaJsDefinition: EngineDefinition = {
  id: 'java-js',
  labelKey: 'engine.javaJs',
  runtime: 'js',
  language: 'Java',
  isAvailable: workerAvailable,
  create,
};

export function registerJavaEngines(): void {
  registerEngine(javaWasmGcDefinition);
  registerEngine(javaJsDefinition);
}

registerJavaEngines();
