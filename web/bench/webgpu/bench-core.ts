// Measurement and correctness code shared by the bench page and the bench worker (no DOM access).
import { createNet, type Net, type NetOptions, ROWS, CELLS } from './net-webgpu.js';
import { parseSafetensors } from '../../src/engines/net/safetensors.js';

/* eslint-disable @typescript-eslint/no-explicit-any */
type Gpu = any;

export interface Stats {
  n: number;
  min: number;
  p50: number;
  p95: number;
  p99: number;
  max: number;
  mean: number;
}

export function stats(samples: number[]): Stats {
  const s = [...samples].sort((a, b) => a - b);
  const at = (q: number): number => s[Math.min(s.length - 1, Math.floor(q * s.length))]!;
  return { n: s.length, min: s[0]!, p50: at(0.5), p95: at(0.95), p99: at(0.99), max: s[s.length - 1]!, mean: s.reduce((a, b) => a + b, 0) / s.length };
}

export async function fetchBytes(url: string): Promise<Uint8Array> {
  const r = await fetch(url);
  if (!r.ok) throw new Error(`${url}: HTTP ${r.status}`);
  return new Uint8Array(await r.arrayBuffer());
}

export async function describeGpu(): Promise<Record<string, unknown>> {
  const gpu = (navigator as any).gpu;
  if (!gpu) return { available: false, reason: 'navigator.gpu is undefined' };
  const adapter = await gpu.requestAdapter();
  if (!adapter) return { available: false, reason: 'requestAdapter() returned null' };
  const info = adapter.info ?? (await adapter.requestAdapterInfo?.());
  const limits: Record<string, number> = {};
  for (const k in adapter.limits) limits[k] = adapter.limits[k];
  return {
    available: true,
    isFallbackAdapter: adapter.isFallbackAdapter ?? info?.isFallbackAdapter,
    info: info ? { vendor: info.vendor, architecture: info.architecture, device: info.device, description: info.description, subgroupMinSize: info.subgroupMinSize, subgroupMaxSize: info.subgroupMaxSize } : null,
    features: [...adapter.features].sort(),
    limits,
  };
}

export async function requestDevice(): Promise<Gpu> {
  const adapter = await (navigator as any).gpu.requestAdapter();
  const device = await adapter.requestDevice();
  device.lost.then((i: any) => console.error('device lost', i.reason, i.message));
  return device;
}

/** Fixed CPU loop, in ms. Compared at start and end of a run: a ratio far from 1.00 says the machine was busy. */
export function cpuProbe(): number {
  const t = performance.now();
  let x = 1;
  for (let i = 0; i < 30_000_000; i++) x = (x * 1664525 + 1013904223) >>> 0;
  if (x === 42) console.log(x);
  return performance.now() - t;
}

export interface Golden {
  inputs: Float32Array;
  planes: number;
  positions: number;
  refLogits: Float32Array;
  refValues: Float32Array;
  players: number;
}

export async function loadGolden(base: string, inputsName: string, refName: string): Promise<Golden> {
  const inp = parseSafetensors(await fetchBytes(`${base}/golden/${inputsName}`)).tensors.get('inputs')!;
  const ref = parseSafetensors(await fetchBytes(`${base}/golden/${refName}`)).tensors;
  const logits = ref.get('logits')!;
  const values = ref.get('values')!;
  return { inputs: inp.data, planes: inp.shape[2]!, positions: inp.shape[0]!, refLogits: logits.data, refValues: values.data, players: values.shape[1]! };
}

/** What check() and the timing functions need: both the benchmark Net and the production backend fit. */
export interface Evaluator {
  evaluate(inputs: Float32Array, batch: number): Promise<{ policyFrom: Float32Array; policyTo: Float32Array; values: Float32Array }>;
}

export interface CheckResult {
  positions: number;
  batches: string;
  maxAbsPolicy: number;
  maxAbsValue: number;
  /** max over all outputs of |fast-ref| / (1e-4 + 1e-3 |ref|); the tolerance holds iff <= 1 */
  maxFrac: number;
  violations: number;
  checked: number;
  /** violations when position 0 is compared with the reference of position 1: must be > 0, shows that the check can fail */
  controlViolations: number;
  pass: boolean;
}

/** Compares the net with the Java reference of `g`: chunks of `chunk`, then single positions, then an odd batch of 37. */
export async function check(net: Evaluator, g: Golden, chunk = 64): Promise<CheckResult> {
  const size = ROWS * g.planes;
  let maxAbsPolicy = 0;
  let maxAbsValue = 0;
  let maxFrac = 0;
  let violations = 0;
  let checked = 0;
  const compare = (res: { policyFrom: Float32Array; policyTo: Float32Array; values: Float32Array }, start: number, n: number): void => {
    const one = (fast: number, ref: number, policy: boolean): void => {
      const d = Math.abs(fast - ref);
      const frac = Number.isNaN(fast) ? Infinity : d / (1e-4 + 1e-3 * Math.abs(ref));
      if (policy) maxAbsPolicy = Math.max(maxAbsPolicy, d); else maxAbsValue = Math.max(maxAbsValue, d);
      maxFrac = Math.max(maxFrac, frac);
      if (!(frac <= 1)) violations++;
      checked++;
    };
    for (let b = 0; b < n; b++) {
      for (let c = 0; c < CELLS; c++) {
        one(res.policyFrom[b * CELLS + c]!, g.refLogits[(start + b) * 242 + 2 * c]!, true);
        one(res.policyTo[b * CELLS + c]!, g.refLogits[(start + b) * 242 + 2 * c + 1]!, true);
      }
      for (let p = 0; p < g.players; p++) one(res.values[b * g.players + p]!, g.refValues[(start + b) * g.players + p]!, false);
    }
  };
  const run = async (start: number, n: number): Promise<void> => {
    compare(await net.evaluate(g.inputs.subarray(start * size, (start + n) * size), n), start, n);
  };
  for (let s = 0; s < g.positions; s += chunk) await run(s, Math.min(chunk, g.positions - s));
  for (let s = 0; s < Math.min(16, g.positions); s++) await run(s, 1);
  const odd = Math.min(37, g.positions - 5, chunk);
  await run(5, odd);
  // negative control: position 0 against the reference of position 1 must fail, and must not touch the figures
  const saved = { maxAbsPolicy, maxAbsValue, maxFrac, violations, checked };
  const control = await net.evaluate(g.inputs.subarray(0, size), 1);
  compare(control, 1, 1);
  const controlViolations = violations - saved.violations;
  ({ maxAbsPolicy, maxAbsValue, maxFrac, violations, checked } = saved);
  return {
    positions: g.positions, batches: `chunks of ${chunk}, 16 x B=1, one B=${odd}`,
    maxAbsPolicy, maxAbsValue, maxFrac, violations, checked, controlViolations, pass: violations === 0 && controlViolations > 0,
  };
}

/** A pool of input arrays for batch b, cycling through the golden positions, so that the timing loop allocates nothing. */
export function inputPool(g: Golden, b: number, count = 8): Float32Array[] {
  const size = ROWS * g.planes;
  const pool: Float32Array[] = [];
  for (let c = 0; c < count; c++) {
    const a = new Float32Array(b * size);
    for (let i = 0; i < b; i++) {
      const pos = (c * b + i) % g.positions;
      a.set(g.inputs.subarray(pos * size, (pos + 1) * size), i * size);
    }
    pool.push(a);
  }
  return pool;
}

export interface SeqResult { batch: number; calls: number; ms: Stats; evalsPerSecMedian: number }

export async function measureSequential(net: Evaluator, g: Golden, b: number, calls: number, warm: number): Promise<SeqResult> {
  const pool = inputPool(g, b);
  for (let i = 0; i < warm; i++) await net.evaluate(pool[i % pool.length]!, b);
  const t: number[] = [];
  for (let i = 0; i < calls; i++) {
    const t0 = performance.now();
    await net.evaluate(pool[i % pool.length]!, b);
    t.push(performance.now() - t0);
  }
  const s = stats(t);
  return { batch: b, calls, ms: s, evalsPerSecMedian: (b / s.p50) * 1000 };
}

export interface PipeResult { batch: number; inflight: number; calls: number; totalMs: number; msPerCall: number; evalsPerSec: number }

/** Keeps `inflight` calls submitted: the next batch is submitted before the oldest one is awaited. */
export async function measurePipelined(net: Evaluator, g: Golden, b: number, calls: number, inflight: number, warm: number): Promise<PipeResult> {
  const pool = inputPool(g, b);
  const drive = async (n: number): Promise<void> => {
    const q: Array<Promise<unknown>> = [];
    for (let i = 0; i < n; i++) {
      q.push(net.evaluate(pool[i % pool.length]!, b));
      if (q.length >= inflight) await q.shift();
    }
    await Promise.all(q);
  };
  await drive(warm);
  const t0 = performance.now();
  await drive(calls);
  const total = performance.now() - t0;
  return { batch: b, inflight, calls, totalMs: total, msPerCall: total / calls, evalsPerSec: (b * calls) / (total / 1000) };
}

/** Round trip of an empty submit plus a 16-byte mapAsync: the floor under every figure above. */
export async function measureFloor(device: Gpu, runs = 1000): Promise<Stats> {
  const U = (globalThis as any).GPUBufferUsage;
  const M = (globalThis as any).GPUMapMode;
  const src = device.createBuffer({ size: 16, usage: U.COPY_SRC | U.COPY_DST });
  const dst = device.createBuffer({ size: 16, usage: U.MAP_READ | U.COPY_DST });
  const t: number[] = [];
  for (let i = 0; i < runs + 50; i++) {
    const t0 = performance.now();
    const enc = device.createCommandEncoder();
    enc.copyBufferToBuffer(src, 0, dst, 0, 16);
    device.queue.submit([enc.finish()]);
    await dst.mapAsync(M.READ);
    dst.getMappedRange();
    dst.unmap();
    if (i >= 50) t.push(performance.now() - t0);
  }
  src.destroy();
  dst.destroy();
  return stats(t);
}

export interface SuiteOptions {
  base: string;
  variants: Array<'naive' | 'tiled'>;
  batches: number[];
  netNames: string[];
  /** sequential calls per batch size (scaled down for big batches) */
  calls: number;
  latencyRuns: number;
  cellsPerThread?: 1 | 2;
  skipBench?: boolean;
}

const NETS: Record<string, { weights: string; inputs: string; ref: string }> = {
  'real-v2': { weights: 'net-real-v2', inputs: 'nn-inputs', ref: 'real-ref-v2' },
  'real-v3': { weights: 'net-real-v3', inputs: 'nn-inputs-v3', ref: 'real-ref-v3' },
  'u5-v2': { weights: 'nn-weights-small-u5', inputs: 'nn-inputs', ref: 'nn-ref-small-u5' },
  'u5-v3': { weights: 'nn-weights-small-v3', inputs: 'nn-inputs-v3', ref: 'nn-ref-small-v3' },
};

/** Correctness for every net in opts.netNames and every variant, then timings on real-v2 (the SMALL net of loop 3). */
export async function runSuite(device: Gpu, opts: SuiteOptions): Promise<Record<string, unknown>> {
  const out: Record<string, unknown> = {};
  const probe0 = cpuProbe();
  const correctness: Record<string, CheckResult & { compile: string }> = {};
  for (const name of opts.netNames) {
    const spec = NETS[name]!;
    const bytes = await fetchBytes(`${opts.base}/data/${spec.weights}`);
    const g = await loadGolden(opts.base, spec.inputs, spec.ref);
    for (const variant of opts.variants) {
      const o: NetOptions = { variant, maxBatch: 128, depth: 2, cellsPerThread: opts.cellsPerThread };
      const t0 = performance.now();
      const net = await createNet(device, bytes, o);
      const compile = `${(performance.now() - t0).toFixed(0)} ms`;
      correctness[`${name}/${variant}`] = { ...(await check(net, g)), compile };
      net.destroy();
    }
  }
  out['correctness'] = correctness;
  if (opts.skipBench) return out;

  const bytes = await fetchBytes(`${opts.base}/data/${NETS['real-v2']!.weights}`);
  const g = await loadGolden(opts.base, NETS['real-v2']!.inputs, NETS['real-v2']!.ref);
  out['floorEmptyRoundTripMs'] = await measureFloor(device);
  const results: Record<string, unknown> = {};
  for (const variant of opts.variants) {
    const net = await createNet(device, bytes, { variant, maxBatch: 128, depth: 3, cellsPerThread: opts.cellsPerThread });
    const seq: SeqResult[] = [];
    const pipe: PipeResult[] = [];
    for (const b of opts.batches) {
      const calls = Math.max(40, Math.round(opts.calls / Math.max(1, Math.sqrt(b))));
      seq.push(await measureSequential(net, g, b, calls, 30));
      for (const inflight of [2, 3]) pipe.push(await measurePipelined(net, g, b, calls, inflight, 20));
    }
    const lat = await measureSequential(net, g, 1, opts.latencyRuns, 100);
    results[variant] = { sequential: seq, pipelined: pipe, latencyB1: lat };
    net.destroy();
  }
  out['bench'] = results;
  out['cpuProbeMs'] = { start: probe0, end: cpuProbe() };
  return out;
}
