// Development only (imported dynamically behind import.meta.env.DEV): the fake engine of the tests as "cpp-wasm",
// so that the UI can be tried without the engine binaries.
import { WorkerEngine } from '../engines/client';
import { FakeEngineCore, FakeWorker } from '../engines/testing/fake-engine';
import { registerEngine } from '../engines/registry';

export function registerFakeEngine(chooseDelayMs: number): void {
  registerEngine({
    id: 'cpp-wasm',
    labelKey: 'engine.cppWasm',
    runtime: 'wasm',
    language: 'C++',
    isAvailable: () => true,
    create: (context) =>
      new WorkerEngine({
        info: context.info,
        assets: { engine: [], manifest: '', tableGz: '' },
        createWorker: () => new FakeWorker(new FakeEngineCore(7), { chooseDelayMs }),
      }),
  });
}
