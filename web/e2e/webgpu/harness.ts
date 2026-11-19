// Code that runs in the browser for web/e2e/webgpu-backend.spec.ts, on the main thread (page.ts) and in a Worker (worker.ts):
// the production WebGpuBackend against the Java reference outputs, and its timings. No DOM access.
import { BackendError } from '../../src/engines/net/backend';
import { isWebGpuAvailable, WebGpuBackend } from '../../src/engines/net/webgpu-backend';
import { check, fetchBytes, loadGolden, measureFloor, measurePipelined, measureSequential, requestDevice } from '../../bench/webgpu/bench-core';

export interface HarnessCase {
  /** Name of the case in the report. */
  name: string;
  /** URL of the F32 safetensors weights. */
  weights: string;
  /** Golden file names below /golden/ (see the spec: the files of cpp/golden and the Java reference outputs). */
  inputs: string;
  ref: string;
}

export interface CheckRequest {
  base: string;
  testCase: HarnessCase;
  maxBatch: number;
  depth?: number;
}

export const harness = {
  probe: () => isWebGpuAvailable(),

  async probeTwiceSame(): Promise<boolean> {
    const a = isWebGpuAvailable();
    const b = isWebGpuAvailable();
    return a === b && (await a) === (await b);
  },

  /** Golden check through the production API: chunks of maxBatch, 16 single positions, one odd batch, and a negative control. */
  async check(req: CheckRequest): Promise<unknown> {
    const backend = new WebGpuBackend({ maxBatch: req.maxBatch, depth: req.depth });
    try {
      const t0 = performance.now();
      await backend.load(await fetchBytes(req.testCase.weights));
      const loadMs = performance.now() - t0;
      const golden = await loadGolden(req.base, req.testCase.inputs, req.testCase.ref);
      const result = await check(backend, golden, req.maxBatch);
      return { ...result, loadMs, planes: backend.planes, valueWidth: backend.valueWidth, goldenPlanes: golden.planes, goldenPlayers: golden.players };
    } finally {
      backend.dispose();
    }
  },

  /** Typed errors of a real device: batch above maxBatch, short input, dispose with a call in flight. */
  async errors(req: CheckRequest): Promise<Record<string, string>> {
    const backend = new WebGpuBackend({ maxBatch: 2 });
    const code = async (p: Promise<unknown>): Promise<string> => {
      try {
        await p;
        return 'resolved';
      } catch (e) {
        return e instanceof BackendError ? e.code : `other: ${String(e)}`;
      }
    };
    const out: Record<string, string> = {};
    out['beforeLoad'] = await code(backend.evaluate(new Float32Array(10), 1));
    await backend.load(await fetchBytes(req.testCase.weights));
    const size = 122 * backend.planes;
    out['batchTooBig'] = await code(backend.evaluate(new Float32Array(3 * size), 3));
    out['shortInput'] = await code(backend.evaluate(new Float32Array(size - 1), 1));
    const inFlight = code(backend.evaluate(new Float32Array(size), 1));
    backend.dispose();
    out['disposedInFlight'] = await inFlight;
    out['afterDispose'] = await code(backend.evaluate(new Float32Array(size), 1));
    out['garbage'] = await code(new WebGpuBackend().load(new Uint8Array(100)));
    return out;
  },

  /** Latency through the production API: sequential calls per batch size, the pipelined internal mode, and the floor. */
  async latency(req: CheckRequest & { batches: number[]; calls: number }): Promise<unknown> {
    const golden = await loadGolden(req.base, req.testCase.inputs, req.testCase.ref);
    const weights = await fetchBytes(req.testCase.weights);
    const sequential = [];
    for (const b of req.batches) {
      const backend = new WebGpuBackend({ maxBatch: Math.max(b, 1) });
      await backend.load(weights);
      sequential.push(await measureSequential(backend, golden, b, req.calls, 30));
      backend.dispose();
    }
    const pipelined = [];
    for (const b of [4, 8, 16]) {
      const backend = new WebGpuBackend({ maxBatch: b, depth: 3 });
      await backend.load(weights);
      pipelined.push(await measurePipelined(backend, golden, b, req.calls, 3, 20));
      backend.dispose();
    }
    const device = await requestDevice();
    const floor = await measureFloor(device, 300);
    device.destroy();
    return { adapter: (await isWebGpuAvailable()).adapterInfo, sequential, pipelined, floorEmptyRoundTripMs: floor };
  },
};

export type Harness = typeof harness;
export type HarnessCall = { fn: keyof Harness; args: unknown[] };
