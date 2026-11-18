// The request handler of the Java (TeaVM) engine worker, without any worker global: worker.ts only connects it to
// postMessage. Separate so that tests and the node benchmark drive the same code path as the page.
// One handler serves both targets: it picks Wasm GC when the engine assets contain a .wasm file, else JavaScript.
import type { LoadReport, Progress } from '../engine';
import { EngineFailure } from '../protocol';
import type { Request } from '../protocol';
import { wasmGcSupported } from '../registry';
import { loadTable, parseManifest, verifyTable } from '../tables';
import { JavaCore } from './core';
import type { JavaModule } from './core';

/** The engine files of one target, as URLs (or paths, for a node loader). Wasm GC: wasm and runtime; JS: js. */
export interface JavaAssets {
  target: 'wasm-gc' | 'js';
  /** engine-java.wasm, or engine-java.js. */
  main: string;
  /** engine-java.wasm-runtime.js (Wasm GC only). */
  runtime?: string;
}

export interface HandlerOptions {
  /** Instantiates the facade module. Default: the browser loader below. */
  loadModule?: (assets: JavaAssets) => Promise<JavaModule>;
  /** For tests. Default: the global fetch. */
  fetch?: typeof fetch;
  /** For tests. Default: crypto.subtle; null models an insecure context. */
  subtle?: SubtleCrypto | null;
}

const now = (): number => performance.now();

export function pickAssets(urls: readonly string[]): JavaAssets {
  const wasm = urls.find((u) => u.endsWith('.wasm'));
  const runtime = urls.find((u) => u.endsWith('-runtime.js'));
  if (wasm) {
    if (!runtime) throw new EngineFailure('internal', 'no engine-java.wasm-runtime.js among the engine assets');
    return { target: 'wasm-gc', main: wasm, runtime };
  }
  const js = urls.find((u) => u.endsWith('.js'));
  if (!js) throw new EngineFailure('internal', 'no engine-java.wasm or engine-java.js among the engine assets');
  return { target: 'js', main: js };
}

/**
 * Browser loader. JS target: the file is an ES module whose exports are the facade. Wasm GC target: the runtime is a
 * classic script that defines `TeaVM.wasmGC`; a module worker has no importScripts, so it is imported as a module (it only sets a global).
 */
export const loadModuleInBrowser = async (assets: JavaAssets): Promise<JavaModule> => {
  if (assets.target === 'js') {
    return (await import(/* @vite-ignore */ assets.main)) as JavaModule;
  }
  if (!wasmGcSupported()) throw new EngineFailure('wasm-gc-unsupported', 'this browser has no WebAssembly GC');
  type TeaVMGlobal = { TeaVM?: { wasmGC?: { load(url: string): Promise<{ exports: JavaModule }> } } };
  const g = globalThis as unknown as TeaVMGlobal;
  if (!g.TeaVM?.wasmGC) {
    // A self-contained script that defines globalThis.TeaVM; importing it as a module runs it (no eval, no CSP issue).
    await import(/* @vite-ignore */ assets.runtime!);
  }
  try {
    return (await g.TeaVM!.wasmGC!.load(assets.main)).exports;
  } catch (e) {
    // A WebAssembly.CompileError or LinkError: the browser knows no final exception handling or no GC types.
    if (e instanceof WebAssembly.CompileError || e instanceof WebAssembly.LinkError) {
      throw new EngineFailure('wasm-gc-unsupported', `the browser cannot run the Wasm GC build: ${e.message}`);
    }
    throw e;
  }
};

export class JavaRequestHandler {
  private core: JavaCore | null = null;
  private readonly options: HandlerOptions;

  constructor(options: HandlerOptions = {}) {
    this.options = options;
  }

  /** Runs one request. Throws EngineFailure; any other throw is mapped to "internal" by the caller (toEngineError). */
  async handle(request: Request, emit: (p: Progress) => void = () => undefined): Promise<unknown> {
    switch (request.type) {
      case 'init':
        return this.init(request, emit);
      case 'newGame':
        this.ready().newGame(request.setup);
        return null;
      case 'legalMoves':
        return this.ready().legalMoves(request.history);
      case 'choose':
        return this.ready().choose(request.history);
      case 'analyse':
        return this.ready().analyse(request.history, request.moves, request.nodes);
      case 'bench':
        return this.ready().bench(request.plies, request.nodes);
    }
  }

  private ready(): JavaCore {
    if (!this.core) throw new EngineFailure('internal', 'the engine is not initialised');
    return this.core;
  }

  private async init(request: Extract<Request, { type: 'init' }>, emit: (p: Progress) => void): Promise<LoadReport> {
    const t0 = now();
    const assets = pickAssets(request.assets.engine);
    const doFetch = this.options.fetch ?? fetch;
    const subtle = this.options.subtle === undefined ? (globalThis.crypto?.subtle ?? null) : this.options.subtle;

    // Instantiating the module and getting the table do not depend on each other.
    const tInstantiate = now();
    emit({ phase: 'instantiate', loaded: 0, total: 1 });
    const modulePromise = (this.options.loadModule ?? loadModuleInBrowser)(assets);
    const tablePromise = this.obtainTable(request, doFetch, subtle, emit);
    const [module, table] = await Promise.all([modulePromise, tablePromise]);
    const instantiateMs = now() - tInstantiate;
    emit({ phase: 'instantiate', loaded: 1, total: 1 });

    const core = JavaCore.create(module);
    emit({ phase: 'table', loaded: 0, total: table.bytes.byteLength });
    const tableInstallMs = core.installTable(table.bytes, table.engineVerifies);
    emit({ phase: 'table', loaded: table.bytes.byteLength, total: table.bytes.byteLength });
    this.core = core;
    return {
      fetchMs: table.fetchMs,
      inflateMs: table.inflateMs,
      verifyMs: table.verifyMs,
      instantiateMs,
      tableInstallMs,
      totalMs: now() - t0,
      tableVerifiedBy: table.verifiedBy,
    };
  }

  /**
   * The inflated table and who checks it. With a subtle SHA-256 check against the manifest the engine skips its own
   * check; without one the engine checks the body against the checksum in the table header and the report says "engine".
   * (TeaVM has no MessageDigest, so Sha256.java does it in plain Java.)
   */
  private async obtainTable(
    request: Extract<Request, { type: 'init' }>,
    doFetch: typeof fetch,
    subtle: SubtleCrypto | null,
    emit: (p: Progress) => void,
  ): Promise<{ bytes: ArrayBuffer; fetchMs: number; inflateMs: number; verifyMs: number; verifiedBy: LoadReport['tableVerifiedBy']; engineVerifies: boolean }> {
    if (!request.table) {
      const loaded = await loadTable({
        manifestUrl: request.assets.manifest,
        tableUrl: request.assets.tableGz,
        engineChecksum: true,
        onProgress: emit,
        fetch: doFetch,
        subtle,
      });
      return { ...loaded, engineVerifies: loaded.verifiedBy !== 'subtle-sha256' };
    }
    // The main thread handed over bytes. It checked them, but the request does not say how, so check again cheaply.
    const t0 = now();
    const response = await doFetch(request.assets.manifest);
    if (!response.ok) throw new EngineFailure('internal', `manifest: HTTP ${response.status}`);
    const manifest = parseManifest(await response.text());
    const fetchMs = now() - t0;
    const t1 = now();
    await verifyTable(new Uint8Array(request.table), manifest, subtle);
    const verifyMs = now() - t1;
    return {
      bytes: request.table,
      fetchMs,
      inflateMs: 0,
      verifyMs,
      verifiedBy: subtle ? 'subtle-sha256' : 'engine',
      engineVerifies: !subtle,
    };
  }
}
