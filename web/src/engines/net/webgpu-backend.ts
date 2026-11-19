// WebGPU backend of the net engine: the forward pass of the hex-convolution network (docs/network.md) in F32 WGSL kernels.
//
//   const probe = await isWebGpuAvailable();             // real requestAdapter + requestDevice, cached
//   const backend = new WebGpuBackend({ maxBatch: 8 });  // maxBatch 1..16, default 8
//   await backend.load(weightsBytes);                    // F32 safetensors bytes, see loadModel in models.ts
//   const { policyFrom, policyTo, values } = await backend.evaluate(inputs, batch);
//   backend.dispose();
//
// Works on the main thread and in a dedicated Web Worker (the code touches only globalThis.navigator.gpu and the GPU objects,
// never the DOM). Errors are BackendError values with a code (backend.ts). If the device is lost, every pending and later
// evaluate rejects with code 'device-lost' and isWebGpuAvailable() reports false until forgetWebGpuProbe() is called.
//
// The kernels are the tiled variant of web/bench/webgpu (webgpu-shaders.ts). The naive variant of the benchmark is not part
// of production. The optional WebGPU features shader-f16 and subgroups are not used: see docs/web-ui.md.
import { BackendError, type NetBackend, type NetOutputs } from './backend';
import { parseSafetensors } from './safetensors';
import {
  CELLS, ROWS, cellsPerWorkgroup, convTiled, deviceNeeds, neighbourTable, policyShader, readNetConfig, requireTensor,
  transposeWeights, valueShader, type NetConfig,
} from './webgpu-shaders';

export interface WebGpuProbe {
  available: boolean;
  /** Why WebGPU is not usable. Set when `available` is false. */
  reason?: string;
  /** What the adapter reports (empty strings where the browser hides a field). Set when `available` is true. */
  adapterInfo?: { vendor: string; architecture: string; device: string; description: string; isFallbackAdapter: boolean };
}

const PROBE_TIMEOUT_MS = 10_000;
let probeCache: Promise<WebGpuProbe> | undefined;

function gpuOf(): GPU | undefined {
  return (globalThis as { navigator?: { gpu?: GPU } }).navigator?.gpu;
}

function withTimeout<T>(p: Promise<T>, what: string): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`${what} did not answer within ${PROBE_TIMEOUT_MS / 1000} s`)), PROBE_TIMEOUT_MS);
    p.then((v) => { clearTimeout(timer); resolve(v); }, (e) => { clearTimeout(timer); reject(e); });
  });
}

async function probeOnce(): Promise<WebGpuProbe> {
  const gpu = gpuOf();
  if (!gpu) return { available: false, reason: 'navigator.gpu is undefined (no WebGPU in this browser, or not a secure context)' };
  try {
    const adapter = await withTimeout(gpu.requestAdapter(), 'requestAdapter()');
    if (!adapter) return { available: false, reason: 'requestAdapter() returned null (no usable GPU or WebGPU blocked)' };
    const device = await withTimeout(adapter.requestDevice(), 'requestDevice()');
    device.destroy();
    const info = adapter.info;
    return {
      available: true,
      adapterInfo: {
        vendor: info?.vendor ?? '', architecture: info?.architecture ?? '', device: info?.device ?? '', description: info?.description ?? '',
        isFallbackAdapter: (info as { isFallbackAdapter?: boolean } | undefined)?.isFallbackAdapter ?? false,
      },
    };
  } catch (e) {
    return { available: false, reason: `requesting a WebGPU device failed: ${e instanceof Error ? e.message : String(e)}` };
  }
}

/**
 * Whether WebGPU works here: a real requestAdapter and requestDevice, once; the result is cached, also a negative one.
 * It never rejects. The probe device is destroyed at once.
 */
export function isWebGpuAvailable(): Promise<WebGpuProbe> {
  probeCache ??= probeOnce();
  return probeCache;
}

/** Drops the cached probe result so that the next isWebGpuAvailable() asks the browser again (for example after a retry button). */
export function forgetWebGpuProbe(): void {
  probeCache = undefined;
}

export interface WebGpuBackendOptions {
  /** Largest batch of one evaluate() call, 1..16. Default 8: leaf batches above 8 cost search strength. */
  maxBatch?: number;
  /**
   * Internal. Buffer sets for overlapping evaluate() calls, 1..4, default 1. MCTS batches depend on the previous results,
   * so only 1 makes sense there; more serves benchmarks that submit the next batch before reading the previous one.
   * The inputs array must not change until the promise of its call has settled.
   */
  depth?: number;
  /** Internal. Cells per thread of the convolution kernels, 1 or 2. Default 2 (measured faster). */
  cellsPerThread?: 1 | 2;
  /** The GPU object to use instead of navigator.gpu (a boundary for tests with a fake device). */
  gpu?: GPU;
}

interface Slot {
  uniform: GPUBuffer;
  out: GPUBuffer;
  input: GPUBuffer;
  staging: GPUBuffer;
  groups: GPUBindGroup[];
  tail: Promise<unknown>;
}

interface Loaded {
  cfg: NetConfig;
  buffers: GPUBuffer[];
  slots: Slot[];
  next: number;
  pIn: GPUComputePipeline;
  pConv: GPUComputePipeline;
  pConvRes: GPUComputePipeline;
  pPol: GPUComputePipeline;
  pVal: GPUComputePipeline;
}

export class WebGpuBackend implements NetBackend {
  readonly kind = 'webgpu';
  readonly maxBatch: number;
  private readonly depth: number;
  private readonly cpt: 1 | 2;
  private readonly gpu: GPU | undefined;
  private device: GPUDevice | undefined;
  private loaded: Loaded | undefined;
  /** Set when the backend can no longer work; every later call and every pending call fails with it. */
  private failure: BackendError | undefined;
  private readonly pending = new Set<(e: BackendError) => void>();
  private _planes = 0;
  private _valueWidth = 0;

  constructor(options: WebGpuBackendOptions = {}) {
    const maxBatch = options.maxBatch ?? 8;
    const depth = options.depth ?? 1;
    if (!Number.isInteger(maxBatch) || maxBatch < 1 || maxBatch > 16) throw new RangeError(`maxBatch ${maxBatch} not in 1..16`);
    if (!Number.isInteger(depth) || depth < 1 || depth > 4) throw new RangeError(`depth ${depth} not in 1..4`);
    this.maxBatch = maxBatch;
    this.depth = depth;
    this.cpt = options.cellsPerThread ?? 2;
    this.gpu = options.gpu;
  }

  get planes(): number {
    return this._planes;
  }

  get valueWidth(): number {
    return this._valueWidth;
  }

  private fail(e: BackendError): void {
    if (this.failure) return;
    this.failure = e;
    for (const reject of [...this.pending]) reject(e);
    this.pending.clear();
  }

  private async acquireDevice(needs: { workgroupStorageBytes: number; valueWorkgroupSize: number }): Promise<GPUDevice> {
    const fits = (l: GPUSupportedLimits): boolean =>
      l.maxComputeWorkgroupStorageSize >= needs.workgroupStorageBytes
      && l.maxComputeInvocationsPerWorkgroup >= needs.valueWorkgroupSize
      && l.maxComputeWorkgroupSizeX >= needs.valueWorkgroupSize;
    if (this.device && fits(this.device.limits)) return this.device;
    this.device?.destroy();
    this.device = undefined;
    const gpu = this.gpu ?? gpuOf();
    if (!gpu) throw new BackendError('unavailable', 'navigator.gpu is undefined (no WebGPU in this browser, or not a secure context)');
    let adapter: GPUAdapter | null;
    try {
      adapter = await gpu.requestAdapter();
    } catch (e) {
      throw new BackendError('unavailable', `requestAdapter() failed: ${String(e)}`, { cause: e });
    }
    if (!adapter) throw new BackendError('unavailable', 'requestAdapter() returned null (no usable GPU or WebGPU blocked)');
    if (!fits(adapter.limits)) {
      throw new BackendError('unsupported-model',
        `the net needs ${needs.workgroupStorageBytes} bytes of workgroup memory and ${needs.valueWorkgroupSize} invocations per workgroup; `
        + `the adapter offers ${adapter.limits.maxComputeWorkgroupStorageSize} and ${adapter.limits.maxComputeInvocationsPerWorkgroup}`);
    }
    // The default limit is 16384 bytes of workgroup memory; the larger nets (64 channels) need more, so ask for what is needed.
    const required: Record<string, number> = {};
    if (needs.workgroupStorageBytes > 16384) required['maxComputeWorkgroupStorageSize'] = needs.workgroupStorageBytes;
    let device: GPUDevice;
    try {
      device = await adapter.requestDevice({ requiredLimits: required });
    } catch (e) {
      throw new BackendError('unavailable', `requestDevice() failed: ${String(e)}`, { cause: e });
    }
    void device.lost.then((info) => {
      if (info.reason === 'destroyed') return;
      const error = new BackendError('device-lost', `the GPU device was lost (${info.reason}): ${info.message}`);
      probeCache = Promise.resolve({ available: false, reason: error.message });
      if (this.device === device) this.fail(error);
    });
    this.device = device;
    return device;
  }

  private releaseNet(): void {
    for (const b of this.loaded?.buffers ?? []) b.destroy();
    this.loaded = undefined;
    this._planes = 0;
    this._valueWidth = 0;
  }

  async load(weights: Uint8Array): Promise<void> {
    if (this.failure) throw this.failure;
    let cfg: NetConfig;
    let tensors: ReturnType<typeof parseSafetensors>['tensors'];
    try {
      tensors = parseSafetensors(weights).tensors;
      cfg = readNetConfig(tensors);
    } catch (e) {
      throw new BackendError('invalid-weights', e instanceof Error ? e.message : String(e), { cause: e });
    }
    const needs = deviceNeeds(cfg, this.cpt);
    const device = await this.acquireDevice(needs);
    if (this.failure) throw this.failure;
    this.releaseNet();
    try {
      this.loaded = await this.build(device, tensors, cfg);
    } catch (e) {
      this.releaseNet();
      if (this.failure) throw this.failure;
      throw e instanceof BackendError ? e : new BackendError('unsupported-model', e instanceof Error ? e.message : String(e), { cause: e });
    }
    if (this.failure) throw this.failure;
    this._planes = cfg.planes;
    this._valueWidth = cfg.players;
  }

  private async build(device: GPUDevice, tensors: ReturnType<typeof parseSafetensors>['tensors'], cfg: NetConfig): Promise<Loaded> {
    const C = cfg.channels;
    const I = cfg.planes;
    const I4 = Math.ceil(I / 4) * 4;
    const maxBatch = this.maxBatch;
    const cpt = this.cpt;
    const buffers: GPUBuffer[] = [];
    const makeBuffer = (size: number, usage: number): GPUBuffer => {
      const b = device.createBuffer({ size: Math.max(16, Math.ceil(size / 4) * 4), usage });
      buffers.push(b);
      return b;
    };
    const constant = (data: Float32Array | Uint32Array): GPUBuffer => {
      const b = makeBuffer(data.byteLength, GPUBufferUsage.STORAGE | GPUBufferUsage.COPY_DST);
      device.queue.writeBuffer(b, 0, data as Uint32Array<ArrayBuffer>);
      return b;
    };
    const convW = (name: string): GPUBuffer => constant(transposeWeights(requireTensor(tensors, `${name}.weight`, C * 7 * C), C, C, C));
    try {
      const nbr = constant(neighbourTable());
      const gIn = constant(transposeWeights(requireTensor(tensors, 'conv_in.weight', C * 7 * I), C, I, I4));
      const gBIn = constant(requireTensor(tensors, 'conv_in.bias', C));
      const blocks = Array.from({ length: cfg.blocks }, (_, i) => ({
        w1: convW(`block${i}.conv1`), b1: constant(requireTensor(tensors, `block${i}.conv1.bias`, C)),
        w2: convW(`block${i}.conv2`), b2: constant(requireTensor(tensors, `block${i}.conv2.bias`, C)),
      }));
      const pw = constant(requireTensor(tensors, 'head_policy.weight', 2 * C));
      const pb = constant(requireTensor(tensors, 'head_policy.bias', 2));
      const vw1 = constant(requireTensor(tensors, 'head_value.dense1.weight', cfg.hidden * C));
      const vb1 = constant(requireTensor(tensors, 'head_value.dense1.bias', cfg.hidden));
      const vw2 = constant(requireTensor(tensors, 'head_value.dense2.weight', cfg.players * cfg.hidden));
      const vb2 = constant(requireTensor(tensors, 'head_value.dense2.bias', cfg.players));

      const pipe = async (code: string, label: string): Promise<GPUComputePipeline> => {
        const module = device.createShaderModule({ code, label });
        const info = await module.getCompilationInfo();
        const errors = info.messages.filter((m) => m.type === 'error');
        if (errors.length) throw new BackendError('unsupported-model', `WGSL ${label}: ${errors.map((m) => `${m.lineNum}: ${m.message}`).join('; ')}`);
        return device.createComputePipelineAsync({ layout: 'auto', compute: { module, entryPoint: 'main' }, label });
      };
      const [pIn, pConv, pConvRes, pPol, pVal] = await Promise.all([
        pipe(convTiled(C, I4, I, true, false, cpt), 'conv_in'),
        pipe(convTiled(C, C, C, false, false, cpt), 'conv1'),
        pipe(convTiled(C, C, C, false, true, cpt), 'conv2'),
        pipe(policyShader(cfg), 'policy'),
        pipe(valueShader(cfg, maxBatch), 'value'),
      ]);

      const actBytes = maxBatch * ROWS * C * 4;
      const outFloats = maxBatch * CELLS * 2 + maxBatch * cfg.players;
      const entries = (...bufs: Array<GPUBuffer | null>): GPUBindGroupEntry[] =>
        bufs.flatMap((buffer, binding) => (buffer ? [{ binding, resource: { buffer } }] : []));

      const slots: Slot[] = [];
      for (let s = 0; s < this.depth; s++) {
        const uniform = makeBuffer(16, GPUBufferUsage.UNIFORM | GPUBufferUsage.COPY_DST);
        const input = makeBuffer(maxBatch * ROWS * I * 4, GPUBufferUsage.STORAGE | GPUBufferUsage.COPY_DST);
        const A = makeBuffer(actBytes, GPUBufferUsage.STORAGE);
        const B = makeBuffer(actBytes, GPUBufferUsage.STORAGE);
        const T = makeBuffer(actBytes, GPUBufferUsage.STORAGE);
        const out = makeBuffer(outFloats * 4, GPUBufferUsage.STORAGE | GPUBufferUsage.COPY_SRC);
        const staging = makeBuffer(outFloats * 4, GPUBufferUsage.MAP_READ | GPUBufferUsage.COPY_DST);
        const groups: GPUBindGroup[] = [];
        groups.push(device.createBindGroup({ layout: pIn.getBindGroupLayout(0), entries: entries(uniform, input, gIn, gBIn, nbr, null, A) }));
        let cur = A;
        let alt = B;
        for (const blk of blocks) {
          groups.push(device.createBindGroup({ layout: pConv.getBindGroupLayout(0), entries: entries(uniform, cur, blk.w1, blk.b1, nbr, null, T) }));
          groups.push(device.createBindGroup({ layout: pConvRes.getBindGroupLayout(0), entries: entries(uniform, T, blk.w2, blk.b2, nbr, cur, alt) }));
          [cur, alt] = [alt, cur];
        }
        groups.push(device.createBindGroup({ layout: pPol.getBindGroupLayout(0), entries: entries(uniform, cur, pw, pb, cfg.priors ? input : null, out) }));
        groups.push(device.createBindGroup({ layout: pVal.getBindGroupLayout(0), entries: entries(uniform, cur, vw1, vb1, vw2, vb2, cfg.priors ? input : null, out) }));
        slots.push({ uniform, input, out, staging, groups, tail: Promise.resolve() });
      }
      return { cfg, buffers, slots, next: 0, pIn, pConv, pConvRes, pPol, pVal };
    } catch (e) {
      for (const b of buffers) b.destroy();
      throw e;
    }
  }

  evaluate(inputs: Float32Array, batch: number): Promise<NetOutputs> {
    if (this.failure) return Promise.reject(this.failure);
    const net = this.loaded;
    const device = this.device;
    if (!net || !device) return Promise.reject(new BackendError('not-loaded', 'evaluate() before load() has resolved'));
    if (!Number.isInteger(batch) || batch < 1 || batch > this.maxBatch) {
      return Promise.reject(new BackendError('invalid-input', `batch ${batch} not in 1..${this.maxBatch}`));
    }
    const need = batch * ROWS * net.cfg.planes;
    if (inputs.length < need) return Promise.reject(new BackendError('invalid-input', `inputs has ${inputs.length} floats, need ${need}`));
    const slot = net.slots[net.next]!;
    net.next = (net.next + 1) % net.slots.length;
    const run = slot.tail.then(() => this.run(device, net, slot, inputs, batch));
    slot.tail = run.catch(() => undefined);
    return new Promise<NetOutputs>((resolve, reject) => {
      this.pending.add(reject);
      run.then(resolve, (e: unknown) => reject(this.failure ?? e)).finally(() => this.pending.delete(reject));
    });
  }

  private async run(device: GPUDevice, net: Loaded, slot: Slot, inputs: Float32Array, batch: number): Promise<NetOutputs> {
    if (this.failure) throw this.failure;
    const cfg = net.cfg;
    const cells = cellsPerWorkgroup(cfg.channels, this.cpt);
    device.queue.writeBuffer(slot.uniform, 0, new Uint32Array([batch, 0, 0, 0]));
    device.queue.writeBuffer(slot.input, 0, inputs as Float32Array<ArrayBuffer>, 0, batch * ROWS * cfg.planes);
    const enc = device.createCommandEncoder();
    const pass = enc.beginComputePass();
    const dispatch = (p: GPUComputePipeline, g: GPUBindGroup, n: number): void => {
      pass.setPipeline(p);
      pass.setBindGroup(0, g);
      pass.dispatchWorkgroups(n);
    };
    let gi = 0;
    const convCount = Math.ceil((batch * CELLS) / cells);
    dispatch(net.pIn, slot.groups[gi++]!, convCount);
    for (let i = 0; i < cfg.blocks; i++) {
      dispatch(net.pConv, slot.groups[gi++]!, convCount);
      dispatch(net.pConvRes, slot.groups[gi++]!, convCount);
    }
    dispatch(net.pPol, slot.groups[gi++]!, Math.ceil((batch * CELLS) / 64));
    dispatch(net.pVal, slot.groups[gi++]!, batch);
    pass.end();
    const polBytes = batch * CELLS * 2 * 4;
    const valBytes = batch * cfg.players * 4;
    const valOffset = this.maxBatch * CELLS * 2 * 4;
    enc.copyBufferToBuffer(slot.out, 0, slot.staging, 0, polBytes);
    enc.copyBufferToBuffer(slot.out, valOffset, slot.staging, valOffset, valBytes);
    device.queue.submit([enc.finish()]);
    try {
      await slot.staging.mapAsync(GPUMapMode.READ);
    } catch (e) {
      // mapAsync rejects with an AbortError or OperationError when the device dies, often before device.lost has fired.
      throw this.failure ?? new BackendError('device-lost', `mapAsync failed: ${e instanceof Error ? e.message : String(e)}`, { cause: e });
    }
    const all = new Float32Array(slot.staging.getMappedRange());
    const policyFrom = new Float32Array(batch * CELLS);
    const policyTo = new Float32Array(batch * CELLS);
    for (let i = 0; i < batch * CELLS; i++) {
      policyFrom[i] = all[2 * i]!;
      policyTo[i] = all[2 * i + 1]!;
    }
    const values = all.slice(valOffset / 4, valOffset / 4 + batch * cfg.players);
    slot.staging.unmap();
    return { policyFrom, policyTo, values };
  }

  dispose(): void {
    this.fail(new BackendError('disposed', 'the backend was disposed'));
    this.releaseNet();
    this.device?.destroy();
    this.device = undefined;
  }
}
