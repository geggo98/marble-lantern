// Tests of the WebGPU backend that need no GPU: the probe, the typed errors, device loss and dispose.
// The GPU is a fake at the system boundary: it records nothing and computes nothing. The numbers of the kernels are checked
// against the Java reference in web/e2e/webgpu-backend.spec.ts.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { safetensorsFile, zeroNetFile } from '../testing/safetensors-file';
import { BackendError } from './backend';
import { forgetWebGpuProbe, isWebGpuAvailable, WebGpuBackend } from './webgpu-backend';

interface Fake {
  gpu: GPU;
  device: GPUDevice;
  loseDevice(reason: string): void;
  requests: { adapter: number; device: number };
  destroyed: () => number;
}

function fakeGpu(options: { adapter?: boolean; deviceError?: Error; limits?: Partial<Record<string, number>> } = {}): Fake {
  let lose: (i: { reason: string; message: string }) => void = () => undefined;
  const lost = new Promise<{ reason: string; message: string }>((r) => { lose = r; });
  let destroyed = 0;
  const buffer = {
    destroy: () => { destroyed++; },
    mapAsync: () => new Promise<void>(() => undefined), // a read that never finishes: only a loss or a dispose can end the call
    getMappedRange: () => new ArrayBuffer(0),
    unmap: () => undefined,
  };
  const limits = { maxComputeWorkgroupStorageSize: 32768, maxComputeInvocationsPerWorkgroup: 256, maxComputeWorkgroupSizeX: 256, ...options.limits };
  const device = {
    limits, lost,
    destroy: () => { destroyed++; },
    queue: { writeBuffer: () => undefined, submit: () => undefined },
    createBuffer: () => buffer,
    createShaderModule: () => ({ getCompilationInfo: async () => ({ messages: [] }) }),
    createComputePipelineAsync: async () => ({ getBindGroupLayout: () => ({}) }),
    createBindGroup: () => ({}),
    createCommandEncoder: () => ({
      beginComputePass: () => ({ setPipeline: () => undefined, setBindGroup: () => undefined, dispatchWorkgroups: () => undefined, end: () => undefined }),
      copyBufferToBuffer: () => undefined,
      finish: () => ({}),
    }),
  } as unknown as GPUDevice;
  const requests = { adapter: 0, device: 0 };
  const adapter = {
    limits,
    info: { vendor: 'v', architecture: 'a', device: 'd', description: 'fake', isFallbackAdapter: true },
    requestDevice: async () => { requests.device++; if (options.deviceError) throw options.deviceError; return device; },
  };
  const gpu = { requestAdapter: async () => { requests.adapter++; return options.adapter === false ? null : adapter; } } as unknown as GPU;
  return { gpu, device, loseDevice: (reason) => lose({ reason, message: 'fake loss' }), requests, destroyed: () => destroyed };
}

function setNavigatorGpu(gpu: GPU | undefined): void {
  vi.stubGlobal('navigator', gpu ? { gpu } : {});
}

beforeEach(() => {
  forgetWebGpuProbe();
  vi.stubGlobal('GPUBufferUsage', { STORAGE: 1, COPY_DST: 2, UNIFORM: 4, COPY_SRC: 8, MAP_READ: 16 });
  vi.stubGlobal('GPUMapMode', { READ: 1 });
});
afterEach(() => {
  vi.unstubAllGlobals();
  forgetWebGpuProbe();
});

const net = (): Uint8Array => zeroNetFile(4, 7, 1, 4, 2);

describe('isWebGpuAvailable', () => {
  it('reports unavailable with a reason when there is no navigator.gpu', async () => {
    setNavigatorGpu(undefined);
    const p = await isWebGpuAvailable();
    expect(p.available).toBe(false);
    expect(p.reason).toMatch(/navigator\.gpu is undefined/);
    expect(p.adapterInfo).toBeUndefined();
  });

  it('reports unavailable when requestAdapter returns null', async () => {
    setNavigatorGpu(fakeGpu({ adapter: false }).gpu);
    const p = await isWebGpuAvailable();
    expect(p).toEqual({ available: false, reason: expect.stringMatching(/requestAdapter\(\) returned null/) });
  });

  it('reports unavailable, with the cause, when requestDevice throws', async () => {
    setNavigatorGpu(fakeGpu({ deviceError: new Error('no device for you') }).gpu);
    const p = await isWebGpuAvailable();
    expect(p.available).toBe(false);
    expect(p.reason).toMatch(/no device for you/);
  });

  it('does a real requestAdapter and requestDevice, returns the adapter info and destroys the probe device', async () => {
    const fake = fakeGpu();
    setNavigatorGpu(fake.gpu);
    const p = await isWebGpuAvailable();
    expect(p.available).toBe(true);
    expect(p.adapterInfo).toEqual({ vendor: 'v', architecture: 'a', device: 'd', description: 'fake', isFallbackAdapter: true });
    expect(fake.requests).toEqual({ adapter: 1, device: 1 });
    expect(fake.destroyed()).toBe(1);
  });

  it('caches the result, positive or negative, until forgetWebGpuProbe', async () => {
    const fake = fakeGpu();
    setNavigatorGpu(fake.gpu);
    const [a, b] = await Promise.all([isWebGpuAvailable(), isWebGpuAvailable()]);
    await isWebGpuAvailable();
    expect(a).toBe(b);
    expect(fake.requests.adapter).toBe(1);
    forgetWebGpuProbe();
    await isWebGpuAvailable();
    expect(fake.requests.adapter).toBe(2);
  });
});

describe('WebGpuBackend', () => {
  it('rejects maxBatch and depth outside their range', () => {
    expect(() => new WebGpuBackend({ maxBatch: 0 })).toThrow(RangeError);
    expect(() => new WebGpuBackend({ maxBatch: 17 })).toThrow(RangeError);
    expect(() => new WebGpuBackend({ maxBatch: 1.5 })).toThrow(RangeError);
    expect(() => new WebGpuBackend({ depth: 0 })).toThrow(RangeError);
    expect(() => new WebGpuBackend({ maxBatch: 16, depth: 4 })).not.toThrow();
  });

  it('has kind webgpu and zero planes and value width before load', () => {
    const b = new WebGpuBackend();
    expect(b.kind).toBe('webgpu');
    expect(b.planes).toBe(0);
    expect(b.valueWidth).toBe(0);
  });

  it('evaluate before load rejects with not-loaded', async () => {
    await expect(new WebGpuBackend({ gpu: fakeGpu().gpu }).evaluate(new Float32Array(1), 1)).rejects.toMatchObject({ code: 'not-loaded' });
  });

  it('load rejects a file that is not a net with invalid-weights, and does not ask for a device', async () => {
    const fake = fakeGpu();
    const b = new WebGpuBackend({ gpu: fake.gpu });
    await expect(b.load(new Uint8Array(3))).rejects.toMatchObject({ code: 'invalid-weights' });
    await expect(b.load(safetensorsFile([]))).rejects.toMatchObject({ code: 'invalid-weights', message: expect.stringMatching(/conv_in.weight is missing/) });
    expect(fake.requests.adapter).toBe(0);
  });

  it('load without WebGPU rejects with unavailable', async () => {
    setNavigatorGpu(undefined);
    await expect(new WebGpuBackend().load(net())).rejects.toMatchObject({ code: 'unavailable' });
  });

  it('load without an adapter or when requestDevice fails rejects with unavailable', async () => {
    await expect(new WebGpuBackend({ gpu: fakeGpu({ adapter: false }).gpu }).load(net())).rejects.toMatchObject({ code: 'unavailable' });
    await expect(new WebGpuBackend({ gpu: fakeGpu({ deviceError: new Error('x') }).gpu }).load(net())).rejects.toMatchObject({ code: 'unavailable', message: expect.stringMatching(/x/) });
  });

  it('load rejects with unsupported-model when the adapter lacks the workgroup memory the net needs', async () => {
    // 64 channels need 18,464 bytes (webgpu-shaders.test.ts); this adapter offers 16,384
    const b = new WebGpuBackend({ gpu: fakeGpu({ limits: { maxComputeWorkgroupStorageSize: 16384 } }).gpu });
    await expect(b.load(zeroNetFile(64, 15, 1, 8, 2))).rejects.toMatchObject({ code: 'unsupported-model', message: expect.stringMatching(/18464 bytes/) });
  });

  it('exposes planes and value width of the loaded net', async () => {
    const b = new WebGpuBackend({ gpu: fakeGpu().gpu });
    await b.load(zeroNetFile(4, 7, 1, 4, 3));
    expect(b.planes).toBe(7);
    expect(b.valueWidth).toBe(3);
  });

  it('checks batch and input length with invalid-input', async () => {
    const b = new WebGpuBackend({ maxBatch: 4, gpu: fakeGpu().gpu });
    await b.load(net());
    const inputs = new Float32Array(5 * 122 * 7); // long enough for batch 5, so that only the batch bound can reject it
    await expect(b.evaluate(inputs, 0)).rejects.toMatchObject({ code: 'invalid-input' });
    await expect(b.evaluate(inputs, 5)).rejects.toMatchObject({ code: 'invalid-input' });
    await expect(b.evaluate(inputs, 1.5)).rejects.toMatchObject({ code: 'invalid-input' });
    await expect(b.evaluate(new Float32Array(2 * 122 * 7), 3)).rejects.toMatchObject({ code: 'invalid-input', message: expect.stringMatching(/need 2562/) }); // 3 * 122 * 7
  });

  it('device loss rejects the pending evaluate and every later call with device-lost, and marks WebGPU unavailable', async () => {
    const fake = fakeGpu();
    setNavigatorGpu(fake.gpu);
    expect((await isWebGpuAvailable()).available).toBe(true);
    const b = new WebGpuBackend({ gpu: fake.gpu });
    await b.load(net());
    const pending = b.evaluate(new Float32Array(122 * 7), 1);
    const settled = pending.then(() => 'resolved', (e: unknown) => e);
    fake.loseDevice('unknown');
    const err = await settled;
    expect(err).toBeInstanceOf(BackendError);
    expect(err).toMatchObject({ code: 'device-lost', message: expect.stringMatching(/unknown/) });
    await expect(b.evaluate(new Float32Array(122 * 7), 1)).rejects.toMatchObject({ code: 'device-lost' });
    await expect(b.load(net())).rejects.toMatchObject({ code: 'device-lost' });
    const probe = await isWebGpuAvailable();
    expect(probe.available).toBe(false);
    expect(probe.reason).toMatch(/lost/);
  });

  it('a loss reported as "destroyed" (our own destroy) is not an error', async () => {
    const fake = fakeGpu();
    const b = new WebGpuBackend({ gpu: fake.gpu });
    await b.load(net());
    fake.loseDevice('destroyed');
    await Promise.resolve();
    await Promise.resolve();
    // not failed: the call is accepted (and stays pending on the fake's endless read)
    const p = b.evaluate(new Float32Array(122 * 7), 1);
    b.dispose();
    await expect(p).rejects.toMatchObject({ code: 'disposed' });
  });

  it('dispose rejects pending and later calls with disposed, destroys the buffers and the device, and is repeatable', async () => {
    const fake = fakeGpu();
    const b = new WebGpuBackend({ gpu: fake.gpu });
    await b.load(net());
    const before = fake.destroyed();
    const pending = b.evaluate(new Float32Array(122 * 7), 1);
    b.dispose();
    await expect(pending).rejects.toMatchObject({ code: 'disposed' });
    expect(fake.destroyed()).toBeGreaterThan(before + 1); // the buffers and the device
    await expect(b.evaluate(new Float32Array(122 * 7), 1)).rejects.toMatchObject({ code: 'disposed' });
    await expect(b.load(net())).rejects.toMatchObject({ code: 'disposed' });
    expect(() => b.dispose()).not.toThrow();
    expect(b.planes).toBe(0);
  });

  it('a second load replaces the net and keeps the device', async () => {
    const fake = fakeGpu();
    const b = new WebGpuBackend({ gpu: fake.gpu });
    await b.load(zeroNetFile(4, 7, 1, 4, 2));
    await b.load(zeroNetFile(4, 13, 1, 4, 3));
    expect(b.planes).toBe(13);
    expect(b.valueWidth).toBe(3);
    expect(fake.requests.device).toBe(1);
  });
});
