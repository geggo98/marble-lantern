// The registry definitions of the net engines: MCTS with a neural network, net-wasm (CPU, C++ WebAssembly forward pass) and
// net-webgpu (WebGPU forward pass). Importing this module registers both: `import './engines/net'`.
// Both run the search of the TeaVM facade (teavm-net) in a worker. They never lack a move: without a usable model they
// play random legal moves and report warnings (warnings.ts).
import { WorkerEngine } from '../client';
import type { WorkerLike } from '../client';
import { registerEngine } from '../registry';
import type { EngineContext, EngineDefinition } from '../registry';
import type { EngineId } from '../engine';

const workerAvailable = (): boolean => typeof WebAssembly === 'object' && typeof Worker === 'function';

function create(backend: 'wasm' | 'webgpu') {
  return (context: EngineContext) => {
    const url = (path: string): string => context.baseUrl + path;
    // The net engines need no race table: the manifest and table URLs of the protocol stay empty.
    const assets = { engine: context.files.map((f) => url(f.path)), manifest: '', tableGz: '', modelsBaseUrl: `${context.baseUrl}models/` };
    return new WorkerEngine({
      info: context.info,
      assets,
      backend,
      createWorker: () => new Worker(new URL('./worker.ts', import.meta.url), { type: 'module' }) as unknown as WorkerLike,
    });
  };
}

export const netWasmDefinition: EngineDefinition = {
  id: 'net-wasm' satisfies EngineId,
  labelKey: 'engine.netWasm',
  runtime: 'wasm',
  language: 'Java + C++',
  isAvailable: workerAvailable,
  create: create('wasm'),
};

/**
 * Available wherever net-wasm is: without WebGPU it plays with the CPU backend and warns. Whether WebGPU works is asked
 * asynchronously (isWebGpuAvailable, selection.ts), not here: isAvailable must be synchronous.
 */
export const netWebGpuDefinition: EngineDefinition = {
  id: 'net-webgpu' satisfies EngineId,
  labelKey: 'engine.netWebGpu',
  runtime: 'wasm',
  language: 'Java + C++',
  isAvailable: workerAvailable,
  create: create('webgpu'),
};

export function registerNetEngines(): void {
  registerEngine(netWasmDefinition);
  registerEngine(netWebGpuDefinition);
}

registerNetEngines();
