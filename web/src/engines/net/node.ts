// Loads the net engine in node, without a worker: for the tests. Not imported by the page.
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { loadJavaModuleInNode } from '../java/node';
import type { NetCoreDeps } from './core';
import type { NetBackend } from './backend';
import { listModels, loadModel } from './models';
import type { ModelInfo } from './models';
import { createWasmBackend, importFlavour } from './wasm-backend';
import type { Flavour } from './wasm-backend';
import type { WebNetModule } from './webnet.d';

export type FacadeTarget = 'wasm-gc' | 'js';

/** The facade from `<publicDir>/engines/net/`. Wasm GC needs node 22 or newer. */
export async function loadWebNetInNode(publicDir: string, target: FacadeTarget = 'js'): Promise<WebNetModule> {
  const dir = join(publicDir, 'engines/net');
  const module =
    target === 'js'
      ? await loadJavaModuleInNode({ target: 'js', main: join(dir, 'engine-net.js') })
      : await loadJavaModuleInNode({ target: 'wasm-gc', main: join(dir, 'engine-net.wasm'), runtime: join(dir, 'engine-net.wasm-runtime.js') });
  return module as unknown as WebNetModule;
}

/** Reads a packed model from `modelsDir` instead of fetching it. */
export const loadModelFromDisk =
  (modelsDir: string) =>
  (info: ModelInfo): Promise<Uint8Array> =>
    loadModel(
      info,
      (async (url: string) => new Response(readFileSync(join(modelsDir, url.slice(url.lastIndexOf('/') + 1))))) as unknown as typeof fetch,
      { baseUrl: 'file:///models/' },
    );

export interface NodeDepsOptions {
  publicDir: string;
  modelsDir: string;
  seed?: number;
  /** Uniform [0,1) source of the random fallback. Default: a fixed-seed generator, so that a test is repeatable. */
  random?: () => number;
  createBackend?: NetCoreDeps['createBackend'];
  probeWebGpu?: NetCoreDeps['probeWebGpu'];
}

/** A small deterministic generator (mulberry32). */
export function mulberry32(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** Real facade, real wasm backend, real model files. WebGPU is reported as missing, as in node. */
export function nodeDeps(webnet: WebNetModule, o: NodeDepsOptions): NetCoreDeps {
  const netDir = join(o.publicDir, 'engines/net');
  return {
    webnet,
    models: listModels(),
    createBackend:
      o.createBackend ??
      (async (kind, maxBatch): Promise<NetBackend> => {
        if (kind !== 'wasm') throw new Error('no WebGPU in node');
        return createWasmBackend({ maxBatch, factory: (f: Flavour) => importFlavour(pathToFileURL(join(netDir, `engine-net-nn-${f}.js`)).href) });
      }),
    probeWebGpu: o.probeWebGpu ?? (async () => ({ available: false, reason: 'node has no WebGPU' })),
    loadModel: loadModelFromDisk(o.modelsDir),
    random: o.random ?? mulberry32(12345),
    seed: () => o.seed ?? 1,
  };
}
