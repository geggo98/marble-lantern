// The request handler of the net engine worker, without any worker global: worker.ts only connects it to postMessage.
// It is separate so that tests and node drive the same code path as the page.
// Files of the engine group (engines.json): the TeaVM facade engine-net.{wasm,wasm-runtime.js,js} (net-MCTS without the forward
// pass) and, for net-wasm, the three flavours of the C++ forward pass engine-net-nn-<flavour>.{js,wasm}.
import type { LoadReport, Progress } from '../engine';
import { EngineFailure } from '../protocol';
import type { Request } from '../protocol';
import { loadModuleInBrowser } from '../java/handler';
import type { JavaAssets } from '../java/handler';
import type { NetBackend } from './backend';
import { NetCore } from './core';
import type { BackendKind, NetCoreDeps } from './core';
import { listModels, loadModel } from './models';
import type { ModelInfo } from './models';
import { createWasmBackend, importFlavour, FLAVOUR_ORDER, NetBackendError } from './wasm-backend';
import type { Flavour } from './wasm-backend';
import { WebGpuBackend, forgetWebGpuProbe, isWebGpuAvailable } from './webgpu-backend';
import type { WebNetModule } from './webnet.d';

/** The engine files of one net engine, as URLs (or paths for a node loader). */
export interface NetAssets {
  facade: JavaAssets;
  /** The glue file of each flavour of the CPU backend that the build ships. */
  flavours: Partial<Record<Flavour, string>>;
  /** The facade without Wasm GC, when the build ships it; used if the browser cannot run the Wasm GC build. */
  facadeJs?: JavaAssets;
}

const base = (url: string): string => url.slice(url.lastIndexOf('/') + 1);

export function pickNetAssets(urls: readonly string[]): NetAssets {
  const named = (name: string): string | undefined => urls.find((u) => base(u) === name);
  const js = named('engine-net.js');
  const wasm = named('engine-net.wasm');
  const runtime = named('engine-net.wasm-runtime.js');
  const flavours: Partial<Record<Flavour, string>> = {};
  for (const f of FLAVOUR_ORDER) {
    const glue = named(`engine-net-nn-${f}.js`);
    if (glue) flavours[f] = glue;
  }
  const facadeJs: JavaAssets | undefined = js ? { target: 'js', main: js } : undefined;
  if (wasm && runtime) return { facade: { target: 'wasm-gc', main: wasm, runtime }, flavours, ...(facadeJs ? { facadeJs } : {}) };
  if (facadeJs) return { facade: facadeJs, flavours };
  throw new EngineFailure('internal', 'no engine-net.wasm (with its runtime) or engine-net.js among the net engine assets');
}

export interface NetHandlerOptions {
  /** Instantiates the facade. Default: the browser loader of the Java engines (the file names differ, the mechanism is the same). */
  loadModule?: (assets: JavaAssets) => Promise<WebNetModule>;
  /** Replaces the whole set of dependencies of the core (tests, node). `webnet` and `assets` are what init loaded. */
  makeDeps?: (webnet: WebNetModule, assets: NetAssets, modelsBaseUrl: string | undefined) => NetCoreDeps;
}

const randomUint32 = (): number => {
  const a = new Uint32Array(1);
  globalThis.crypto.getRandomValues(a);
  return a[0] as number;
};

/** Default dependencies of the page: real backends, the real model loader, entropy from crypto. */
export function browserDeps(webnet: WebNetModule, assets: NetAssets, modelsBaseUrl: string | undefined): NetCoreDeps {
  return {
    webnet,
    models: listModels(),
    createBackend: async (kind: BackendKind, maxBatch: number): Promise<NetBackend> => {
      if (kind === 'webgpu') return new WebGpuBackend({ maxBatch });
      return createWasmBackend({
        maxBatch,
        factory: async (flavour) => {
          const url = assets.flavours[flavour];
          if (!url) throw new NetBackendError('unavailable', `the build ships no ${flavour} flavour`);
          return importFlavour(url);
        },
      });
    },
    probeWebGpu: (fresh) => {
      if (fresh) forgetWebGpuProbe();
      return isWebGpuAvailable();
    },
    loadModel: (info: ModelInfo) => loadModel(info, fetch, modelsBaseUrl ? { baseUrl: modelsBaseUrl } : {}),
    random: () => randomUint32() / 4294967296,
    seed: () => randomUint32() | 0,
  };
}

const now = (): number => performance.now();

export class NetRequestHandler {
  private core: NetCore | null = null;
  private readonly options: NetHandlerOptions;

  constructor(options: NetHandlerOptions = {}) {
    this.options = options;
  }

  /** Runs one request. Throws EngineFailure; any other throw is mapped to "internal" by the caller (toEngineError). */
  async handle(request: Request, emit: (p: Progress) => void = () => undefined): Promise<unknown> {
    switch (request.type) {
      case 'init':
        return this.init(request, emit);
      case 'newGame':
        return { warnings: await this.ready().newGame(request.setup) };
      case 'legalMoves':
        return this.ready().legalMoves(request.history, request.variant);
      case 'choose':
        // The protocol has no abort message for a running choose (a Request carries no signal): the client ends a search by terminating
        // and restarting the worker. So no AbortSignal is passed here; NetCore.choose takes one for callers that have it (tests, node).
        return this.ready().choose(request.history, undefined, request.variant);
      case 'analyse':
        return this.ready().analyse();
      case 'evaluate3':
        throw new EngineFailure('unsupported', 'the net engines have no static evaluation vector');
      case 'features':
        throw new EngineFailure('unsupported', 'the net engines have no feature report');
      case 'netForward':
        throw new EngineFailure('unsupported', 'the forward report is not implemented yet'); // WP4 replaces the stub
      case 'outcome':
        return this.ready().outcome(request.history, request.variant);
      case 'bench':
        throw new EngineFailure('unsupported', 'the net engines have no benchmark game');
    }
  }

  private ready(): NetCore {
    if (!this.core) throw new EngineFailure('internal', 'the engine is not initialised');
    return this.core;
  }

  private async init(request: Extract<Request, { type: 'init' }>, emit: (p: Progress) => void): Promise<LoadReport> {
    const t0 = now();
    const assets = pickNetAssets(request.assets.engine);
    emit({ phase: 'instantiate', loaded: 0, total: 1 });
    const load = this.options.loadModule ?? ((a: JavaAssets) => loadModuleInBrowser(a) as unknown as Promise<WebNetModule>);
    let webnet: WebNetModule;
    try {
      // Wasm GC when the browser has it; the JavaScript build otherwise, or when the Wasm GC build fails to link.
      webnet = await load(assets.facade);
    } catch (e) {
      if (!assets.facadeJs || (e as { code?: unknown }).code !== 'wasm-gc-unsupported') throw e;
      webnet = await load(assets.facadeJs);
    }
    emit({ phase: 'instantiate', loaded: 1, total: 1 });
    const makeDeps = this.options.makeDeps ?? browserDeps;
    this.core = new NetCore(request.backend ?? 'wasm', makeDeps(webnet, assets, request.assets.modelsBaseUrl));
    const instantiateMs = now() - t0;
    return { fetchMs: 0, inflateMs: 0, verifyMs: 0, instantiateMs, tableInstallMs: 0, totalMs: instantiateMs, tableVerifiedBy: 'length-only' };
  }
}
