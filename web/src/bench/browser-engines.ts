// The engines the browser benchmark can start, shared by the benchmark page (web/bench/index.html) and the benchmark screen
// of the app. Literal `new Worker(new URL(...))` calls: Vite only bundles workers it can see in this form.
import { wasmGcSupported } from '../engines/registry';

export const makeWorker: Record<string, () => Worker> = {
  'cpp-wasm': () => new Worker(new URL('../engines/cpp/worker.ts', import.meta.url), { type: 'module' }),
  'java-wasmgc': () => new Worker(new URL('../engines/java/worker.ts', import.meta.url), { type: 'module' }),
  'java-js': () => new Worker(new URL('../engines/java/worker.ts', import.meta.url), { type: 'module' }),
};

export const META: Record<string, { runtime: string; language: string; available: () => boolean }> = {
  'cpp-wasm': { runtime: 'wasm', language: 'C++', available: () => typeof WebAssembly === 'object' },
  'java-wasmgc': { runtime: 'wasm-gc', language: 'Java', available: wasmGcSupported },
  'java-js': { runtime: 'js', language: 'Java', available: () => true },
};
