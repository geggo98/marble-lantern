// The registry definition of the C++ WebAssembly engine, the default engine of the offline mode.
// Importing this module registers it: `import './engines/cpp'` (or call registerCppEngine()).
import { WorkerEngine } from '../client';
import type { WorkerLike } from '../client';
import { registerEngine } from '../registry';
import type { EngineContext, EngineDefinition } from '../registry';
import { loadTable } from '../tables';

/** WebAssembly without Wasm GC is enough, and every browser that can run a module worker has it. */
const isAvailable = (): boolean => typeof WebAssembly === 'object' && typeof Worker === 'function';

export const cppEngineDefinition: EngineDefinition = {
  id: 'cpp-wasm',
  labelKey: 'engine.cppWasm',
  runtime: 'wasm',
  language: 'C++',
  isAvailable,
  create(context: EngineContext) {
    const url = (path: string): string => context.baseUrl + path;
    const js = context.files.find((f) => f.path.endsWith('.js'));
    if (!js) throw new Error('engines.json lists no engine-cpp.js for cpp-wasm');
    // The worker imports engine-cpp.js by an absolute or root-relative URL; the wasm file is found next to it.
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
  },
};

export function registerCppEngine(): void {
  registerEngine(cppEngineDefinition);
}

registerCppEngine();
