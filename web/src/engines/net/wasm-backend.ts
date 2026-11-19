// The CPU network backend: the C++ forward pass (cpp/src/wasm/net_api.cpp) as WebAssembly, behind the NetBackend contract (IC1).
// Works in a Web Worker, on the main thread and in Node: it touches no DOM and no worker global.
//
// Three builds of the same C++ source exist (flavours). The loader picks the best one this runtime can run:
//   relaxed  simd128 plus relaxed-simd (fused multiply-add)   probe: a module with f32x4.relaxed_madd
//   simd     simd128                                          probe: a module with i8x16.popcnt
//   scalar   no SIMD instructions                             always available
// A flavour whose probe fails, or whose module fails to load or instantiate, is skipped and the next one is tried. The
// order can be forced for tests and for A/B measurements (options.flavours).
//
// Weights: load() takes the bytes of an F32 safetensors file (plain, classic1, classic1-planes or classic1v). A model stored as F16
// must be widened to F32 by the caller first (loadModel in models.ts does this); the C++ side reads no F16.
//
// The distance prior of the policy and the value prior (classic1, classic1v) are added inside the C++ forward pass, exactly like
// the Java Network (docs/network.md, "Classical planes and residual priors"). The values come out after the tanh.

/** The contract IC1. The same shape as `NetBackend` of backend.ts (WebGPU task); kept here so that this file has no dependency on it. */
export interface NetBackend {
  readonly kind: 'wasm' | 'webgpu';
  /** @param weights the bytes of an F32 safetensors file */
  load(weights: Uint8Array): Promise<void>;
  /**
   * @param inputs batch * 122 * planes floats, canonical orientation, as produced by the Java Encoder
   * @returns policyFrom and policyTo: batch * 121 raw logits each (with the distance prior); values: batch * valueWidth after the tanh
   */
  evaluate(
    inputs: Float32Array,
    batch: number,
  ): Promise<{ policyFrom: Float32Array; policyTo: Float32Array; values: Float32Array }>;
  /** Input planes of the loaded net; 0 before load. */
  readonly planes: number;
  /** Width of the value head (2, or 3 for a classic1v net); 0 before load. */
  readonly valueWidth: number;
  dispose(): void;
}

export type Flavour = 'relaxed' | 'simd' | 'scalar';

/** Best first. */
export const FLAVOUR_ORDER: readonly Flavour[] = ['relaxed', 'simd', 'scalar'];

/** Rows of one encoded position (121 cells and the all-zero padding row). */
export const ROWS = 122;
export const CELLS = 121;
/** Default capacity of the batch buffers in the module. */
export const DEFAULT_MAX_BATCH = 16;

export type NetBackendErrorCode =
  | 'file' // not a readable safetensors file (truncated, damaged header or layout)
  | 'format' // the metadata does not name the format "sternhalma-nn" (bad magic)
  | 'config' // metadata or tensors do not describe a supported net
  | 'memory'
  | 'no-net' // evaluate before load
  | 'batch' // batch < 1 or batch > maxBatch
  | 'forward' // the forward pass refused the input
  | 'argument'
  | 'bad-input' // the input array does not have batch * 122 * planes floats
  | 'unavailable' // no flavour could be loaded
  | 'disposed'
  | 'internal';

export class NetBackendError extends Error {
  readonly code: NetBackendErrorCode;
  constructor(code: NetBackendErrorCode, message: string) {
    super(message);
    this.name = 'NetBackendError';
    this.code = code;
  }
}

/** Return codes of net_load, net_input_ptr and net_evaluate (cpp/src/wasm/net_api.cpp, header comment). */
const C_CODES: Readonly<Record<number, NetBackendErrorCode>> = {
  1: 'file',
  2: 'format',
  3: 'config',
  4: 'memory',
  5: 'no-net',
  6: 'batch',
  7: 'forward',
  8: 'argument',
};

/** The exports of engine-net-nn-<flavour>.js that this adapter calls. */
export interface NetModule {
  HEAPU8: Uint8Array;
  HEAPF32: Float32Array;
  UTF8ToString(ptr: number): string;
  _malloc(size: number): number;
  _free(ptr: number): void;
  _net_error(): number;
  /** 0 scalar, 1 simd128, 2 relaxed-simd. */
  _net_flavour(): number;
  _net_load(ptr: number, len: number): number;
  _net_planes(): number;
  _net_value_width(): number;
  /** Allocates the buffers; returns the byte address of the input buffer, 0 on error. */
  _net_input_ptr(maxBatch: number): number;
  _net_policy_from_ptr(): number;
  _net_policy_to_ptr(): number;
  _net_values_ptr(): number;
  _net_max_batch(): number;
  _net_evaluate(batch: number): number;
  _net_free(): void;
}

/** What `import('engine-net-nn-<flavour>.js').default` is: it creates and instantiates the module. */
export type NetModuleFactory = () => Promise<NetModule>;

// Probe modules: a function () -> v128 whose body uses one instruction of the feature. WebAssembly.validate says whether the
// engine knows it. (The bytes were checked with WebAssembly.validate in Node 24 and are covered by the tests.)
const HEADER = [0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00, 0x01, 0x05, 0x01, 0x60, 0x00, 0x01, 0x7b, 0x03, 0x02, 0x01, 0x00];
/** i32.const 0; i8x16.splat; i8x16.popcnt */
export const SIMD_PROBE = new Uint8Array([...HEADER, 0x0a, 0x0a, 0x01, 0x08, 0x00, 0x41, 0x00, 0xfd, 0x0f, 0xfd, 0x62, 0x0b]);
/** three times (i32.const 1; i8x16.splat), then f32x4.relaxed_madd (0xfd 0x85 0x02) */
export const RELAXED_PROBE = new Uint8Array([
  ...HEADER, 0x0a, 0x13, 0x01, 0x11, 0x00, 0x41, 0x01, 0xfd, 0x0f, 0x41, 0x01, 0xfd, 0x0f, 0x41, 0x01, 0xfd, 0x0f, 0xfd, 0x85, 0x02, 0x0b,
]);

/** True if this runtime can run the flavour. Never throws. */
export function supportsFlavour(flavour: Flavour): boolean {
  try {
    if (typeof WebAssembly !== 'object') return false;
    if (flavour === 'scalar') return true;
    return WebAssembly.validate(flavour === 'simd' ? SIMD_PROBE : RELAXED_PROBE);
  } catch {
    return false;
  }
}

/** The best flavour of this runtime. */
export function bestFlavour(): Flavour {
  return FLAVOUR_ORDER.find(supportsFlavour) ?? 'scalar';
}

/** The default site-relative directory of the module files (scripts/web/build-wasm.zsh publishes them there). */
export const NET_ASSET_DIR = 'engines/net/';

/** URL of the glue file of a flavour below `baseUrl` (the site root with a trailing slash). The wasm file is found next to it. */
export function flavourUrl(baseUrl: string, flavour: Flavour): string {
  return `${baseUrl}${NET_ASSET_DIR}engine-net-nn-${flavour}.js`;
}

/** Default factory source: a dynamic import of the glue file, an ES module whose default export creates the module. */
export async function importFlavour(url: string): Promise<NetModuleFactory> {
  const module = (await import(/* @vite-ignore */ url)) as { default?: NetModuleFactory };
  if (typeof module.default !== 'function') throw new NetBackendError('unavailable', `${url} has no default export`);
  return module.default;
}

export interface WasmBackendOptions {
  /** Site root with a trailing slash (import.meta.env.BASE_URL), as the other engines get it. Default: ''. Ignored if `factory` is set. */
  baseUrl?: string;
  /** Flavours to try, in this order. Default: FLAVOUR_ORDER. A flavour that this runtime cannot run is skipped. */
  flavours?: readonly Flavour[];
  /** Replaces the runtime check (tests). Default: supportsFlavour. */
  supports?: (flavour: Flavour) => boolean;
  /** Replaces the loading of a flavour (tests, Node with file URLs). Default: importFlavour(flavourUrl(baseUrl, flavour)). */
  factory?: (flavour: Flavour) => Promise<NetModuleFactory>;
  /** Capacity of the batch buffers; evaluate rejects larger batches. Default 16, up to 4096. */
  maxBatch?: number;
}

export class WasmNetBackend implements NetBackend {
  readonly kind = 'wasm';
  /** The flavour that was loaded. */
  readonly flavour: Flavour;
  readonly maxBatch: number;
  private readonly m: NetModule;
  private loaded = false;
  private disposed = false;
  private planeCount = 0;
  private width = 0;
  private inPtr = 0;

  private constructor(module: NetModule, flavour: Flavour, maxBatch: number) {
    this.m = module;
    this.flavour = flavour;
    this.maxBatch = maxBatch;
  }

  /** Picks and instantiates the best flavour. Throws NetBackendError('unavailable') if none works. */
  static async create(options: WasmBackendOptions = {}): Promise<WasmNetBackend> {
    const maxBatch = options.maxBatch ?? DEFAULT_MAX_BATCH;
    if (!Number.isInteger(maxBatch) || maxBatch < 1 || maxBatch > 4096) {
      throw new NetBackendError('argument', `maxBatch must be an integer in [1, 4096], got ${maxBatch}`);
    }
    const supports = options.supports ?? supportsFlavour;
    const baseUrl = options.baseUrl ?? '';
    const source = options.factory ?? ((f: Flavour) => importFlavour(flavourUrl(baseUrl, f)));
    const failures: string[] = [];
    for (const flavour of options.flavours ?? FLAVOUR_ORDER) {
      if (!supports(flavour)) {
        failures.push(`${flavour}: not supported by this runtime`);
        continue;
      }
      try {
        const module = await (await source(flavour))();
        return new WasmNetBackend(module, flavour, maxBatch);
      } catch (e) {
        failures.push(`${flavour}: ${e instanceof Error ? e.message : String(e)}`);
      }
    }
    throw new NetBackendError('unavailable', `no WebAssembly flavour of the net backend could be loaded (${failures.join('; ')})`);
  }

  get planes(): number {
    return this.planeCount;
  }

  get valueWidth(): number {
    return this.width;
  }

  private alive(): void {
    if (this.disposed) throw new NetBackendError('disposed', 'the backend was disposed');
  }

  private fail(code: number, what: string): never {
    const text = this.m.UTF8ToString(this.m._net_error());
    throw new NetBackendError(C_CODES[code] ?? 'internal', `${what} returned ${code}: ${text}`);
  }

  async load(weights: Uint8Array): Promise<void> {
    this.alive();
    this.loaded = false;
    this.planeCount = 0;
    this.width = 0;
    const ptr = this.m._malloc(Math.max(weights.length, 1));
    if (ptr === 0) throw new NetBackendError('memory', `out of memory for the ${weights.length} byte net`);
    let rc: number;
    try {
      // HEAPU8 is read after the malloc: the heap may have grown, which replaces the view.
      this.m.HEAPU8.set(weights, ptr);
      rc = this.m._net_load(ptr, weights.length);
    } finally {
      this.m._free(ptr);
    }
    if (rc !== 0) this.fail(rc, 'net_load');
    // Addresses in linear memory do not move when the heap grows (only the JavaScript views are replaced), so the pointer is kept.
    this.inPtr = this.m._net_input_ptr(this.maxBatch);
    if (this.inPtr === 0) this.fail(4, 'net_input_ptr');
    this.planeCount = this.m._net_planes();
    this.width = this.m._net_value_width();
    this.loaded = true;
  }

  async evaluate(
    inputs: Float32Array,
    batch: number,
  ): Promise<{ policyFrom: Float32Array; policyTo: Float32Array; values: Float32Array }> {
    this.alive();
    if (!this.loaded) throw new NetBackendError('no-net', 'evaluate before load');
    if (!Number.isInteger(batch) || batch < 1 || batch > this.maxBatch) {
      throw new NetBackendError('batch', `batch must be an integer in [1, ${this.maxBatch}], got ${batch}`);
    }
    const size = ROWS * this.planeCount;
    if (inputs.length !== batch * size) {
      throw new NetBackendError(
        'bad-input',
        `expected ${batch} x ${ROWS} x ${this.planeCount} = ${batch * size} floats for this net (${this.planeCount} planes), got ${inputs.length}`,
      );
    }
    // The module owns the buffers; the JavaScript views are taken fresh on every call because the heap may have grown.
    this.m.HEAPF32.set(inputs, this.inPtr >> 2);
    const rc = this.m._net_evaluate(batch);
    if (rc !== 0) this.fail(rc, 'net_evaluate');
    const from = this.m._net_policy_from_ptr() >> 2;
    const to = this.m._net_policy_to_ptr() >> 2;
    const values = this.m._net_values_ptr() >> 2;
    const heap = this.m.HEAPF32;
    return {
      policyFrom: heap.slice(from, from + batch * CELLS),
      policyTo: heap.slice(to, to + batch * CELLS),
      values: heap.slice(values, values + batch * this.width),
    };
  }

  dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    this.loaded = false;
    this.m._net_free();
  }
}

/** Shorthand: the best flavour, ready to load(). */
export function createWasmBackend(options: WasmBackendOptions = {}): Promise<WasmNetBackend> {
  return WasmNetBackend.create(options);
}
