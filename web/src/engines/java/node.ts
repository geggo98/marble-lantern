// Loads the TeaVM engine in node, without a worker: for the tests and the node benchmark. Not imported by the page.
import { pathToFileURL } from 'node:url';
import type { JavaAssets } from './handler';
import type { JavaModule } from './core';

type TeaVMGlobal = { TeaVM?: { wasmGC?: { load(path: string): Promise<{ exports: JavaModule }> } } };

/** `assets.main` and `assets.runtime` are file paths. Wasm GC needs node 22 or newer. */
export async function loadJavaModuleInNode(assets: JavaAssets): Promise<JavaModule> {
  if (assets.target === 'js') {
    return (await import(/* @vite-ignore */ pathToFileURL(assets.main).href)) as JavaModule;
  }
  const g = globalThis as unknown as TeaVMGlobal;
  // The runtime is a self-contained script that defines globalThis.TeaVM; importing it as a module runs it.
  if (!g.TeaVM?.wasmGC) await import(/* @vite-ignore */ pathToFileURL(assets.runtime!).href);
  return (await g.TeaVM!.wasmGC!.load(assets.main)).exports;
}
