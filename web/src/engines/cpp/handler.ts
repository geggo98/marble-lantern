// The request handler of the C++ engine worker, without any worker global: worker.ts only connects it to postMessage.
// It is separate so that tests and the node benchmark can drive the same code path as the page.
import type { LoadReport, Progress } from '../engine';
import { EngineFailure } from '../protocol';
import type { Request } from '../protocol';
import { loadTable, parseManifest, verifyTable } from '../tables';
import { CppCore } from './core';
import type { CppModuleFactory } from './core';

export interface HandlerOptions {
  /** Resolves the module factory from the URL of engine-cpp.js. Default: a dynamic import of that URL (ES module). */
  importFactory?: (url: string) => Promise<CppModuleFactory>;
  /** For tests. Default: the global fetch. */
  fetch?: typeof fetch;
  /** For tests. Default: crypto.subtle; null models an insecure context. */
  subtle?: SubtleCrypto | null;
}

const now = (): number => performance.now();

/** engine-cpp.js is an ES module (SH_WASM_ES6=ON) whose default export creates the module; it finds engine-cpp.wasm next to itself. */
export const importEngineModule = async (url: string): Promise<CppModuleFactory> => {
  const module = (await import(/* @vite-ignore */ url)) as { default?: CppModuleFactory };
  if (typeof module.default !== 'function') {
    throw new EngineFailure('internal', `${url} has no default export; was it built with SH_WASM_ES6=OFF?`);
  }
  return module.default;
};

export class CppRequestHandler {
  private core: CppCore | null = null;
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
      case 'bench':
        return this.ready().bench(request.plies, request.nodes);
    }
  }

  private ready(): CppCore {
    if (!this.core) throw new EngineFailure('internal', 'the engine is not initialised');
    return this.core;
  }

  private async init(request: Extract<Request, { type: 'init' }>, emit: (p: Progress) => void): Promise<LoadReport> {
    const t0 = now();
    const jsUrl = request.assets.engine.find((u) => u.endsWith('.js'));
    if (!jsUrl) throw new EngineFailure('internal', 'no engine-cpp.js among the engine assets');
    const doFetch = this.options.fetch ?? fetch;
    const subtle = this.options.subtle === undefined ? (globalThis.crypto?.subtle ?? null) : this.options.subtle;

    // Instantiating the module and getting the table do not depend on each other.
    const tInstantiate = now();
    emit({ phase: 'instantiate', loaded: 0, total: 1 });
    const importFactory = this.options.importFactory ?? importEngineModule;
    const corePromise = importFactory(jsUrl).then((factory) => CppCore.create(factory));
    const tablePromise = this.obtainTable(request, doFetch, subtle, emit);
    const [core, table] = await Promise.all([corePromise, tablePromise]);
    const instantiateMs = now() - tInstantiate;
    emit({ phase: 'instantiate', loaded: 1, total: 1 });

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
   * The inflated table and who checks it. With a subtle SHA-256 check against the manifest the engine skips its two
   * SHA-256 passes (about 100 ms); without one the engine checks (verify = 1) and the report says "engine".
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
