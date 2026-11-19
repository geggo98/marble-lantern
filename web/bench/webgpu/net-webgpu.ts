// WebGPU forward pass of the Sternhalma hex-convolution network (docs/network.md), F32, written in WGSL.
//
//   const net = await createNet(device, safetensorsBytes, { variant: 'tiled', maxBatch: 128 });
//   const { policyFrom, policyTo, values } = await net.evaluate(inputs, batch);
//
// inputs: Float32Array [batch][122][planes], exactly what the Java Encoder writes (row 121 is the padding row).
// policyFrom, policyTo: Float32Array [batch][121]; the policy logit of a move is policyFrom[from'] + policyTo[to'],
// including the classical distance prior. values: Float32Array [batch][P] after the tanh, as the Java Network.
//
// Two variants, same numbers up to float rounding:
//  - 'naive': one thread per output element, weights straight from global memory.
//  - 'tiled': a workgroup computes a tile of cells x all output channels. For each of the 7 neighbour slots the gathered
//    activations and the transposed weight slice go to workgroup memory; weights and activations are read as vec4;
//    each thread keeps `cellsPerThread` x 4 accumulators. The value head splits the mean over 4 partial sums.
//
// Several evaluate() calls may be in flight: the net owns `depth` buffer sets (slots, default 2), a call takes the
// next slot and queues behind the earlier call of that slot, so the next batch is submitted before the previous one is
// read back (double buffering). The inputs array must not change before the promise of its call has settled.
//
// The module has no dependency on React or on the DOM. The GPU types are `any` here because the project has no
// @webgpu/types dependency; they match the WebGPU API of Chrome 145.
import { parseSafetensors, type TensorInfo } from './safetensors.js';

/* eslint-disable @typescript-eslint/no-explicit-any */
type Gpu = any;

export type Variant = 'naive' | 'tiled';

export interface NetOptions {
  variant?: Variant;
  /** Largest batch of one evaluate() call (buffers are sized for it). Default 128. */
  maxBatch?: number;
  /** Number of buffer sets for overlapping calls. Default 2. */
  depth?: number;
  /** Tiled variant: cells per thread, 1 or 2. Default 2. */
  cellsPerThread?: 1 | 2;
}

export interface NetResult {
  policyFrom: Float32Array;
  policyTo: Float32Array;
  values: Float32Array;
}

export interface Net {
  readonly channels: number;
  readonly blocks: number;
  readonly players: number;
  readonly inputPlanes: number;
  readonly variant: Variant;
  readonly maxBatch: number;
  evaluate(inputs: Float32Array, batch: number): Promise<NetResult>;
  destroy(): void;
}

export const CELLS = 121;
export const ROWS = 122;
const DIST_OWN = 7;
const EVAL = 12;
const DIST_SCALE = 16;

/** The gather table [121][7]: the cell itself, then the six neighbours in Geometry order; 121 = off the board. */
export function neighbourTable(): Uint32Array {
  const ids = new Map<string, number>();
  const coords: Array<[number, number]> = [];
  for (let r = -8; r <= 8; r++) {
    for (let q = -8; q <= 8; q++) {
      const s = -q - r;
      if (Math.max(q, r, s) <= 4 || Math.min(q, r, s) >= -4) {
        ids.set(`${q},${r}`, coords.length);
        coords.push([q, r]);
      }
    }
  }
  if (coords.length !== CELLS) throw new Error(`board has ${coords.length} cells, expected ${CELLS}`);
  const dirs: Array<[number, number]> = [[1, 0], [-1, 0], [0, 1], [0, -1], [1, -1], [-1, 1]];
  const table = new Uint32Array(CELLS * 7);
  coords.forEach(([q, r], c) => {
    table[c * 7] = c;
    dirs.forEach(([dq, dr], d) => {
      table[c * 7 + 1 + d] = ids.get(`${q + dq},${r + dr}`) ?? CELLS;
    });
  });
  return table;
}

interface Config {
  channels: number;
  blocks: number;
  players: number;
  hidden: number;
  planes: number;
  priors: boolean;
  beta: number;
  k: number;
}

function tensor(t: Map<string, TensorInfo>, name: string, size: number): Float32Array {
  const x = t.get(name);
  if (!x) throw new Error(`weights: tensor ${name} is missing`);
  if (x.data.length !== size) throw new Error(`weights: tensor ${name} has ${x.data.length} values, expected ${size}`);
  return x.data;
}

function readConfig(t: Map<string, TensorInfo>): Config {
  const convIn = t.get('conv_in.weight');
  if (!convIn) throw new Error('weights: conv_in.weight is missing');
  const channels = convIn.shape[0]!;
  const planes = convIn.shape[1]! / 7;
  let blocks = 0;
  while (t.has(`block${blocks}.conv1.weight`)) blocks++;
  const hidden = t.get('head_value.dense1.bias')?.data.length ?? 0;
  const players = t.get('head_value.dense2.bias')?.data.length ?? 0;
  const priors = t.has('prior.beta') && t.has('prior.k');
  if (!Number.isInteger(planes) || channels % 4 !== 0 || blocks < 1 || !hidden || !players) {
    throw new Error(`weights: unsupported shape (channels ${channels}, planes ${planes}, blocks ${blocks})`);
  }
  if (priors && planes <= EVAL) throw new Error('weights: priors need the classical planes (13 or 15)');
  return {
    channels, blocks, players, hidden, planes, priors,
    beta: priors ? tensor(t, 'prior.beta', 1)[0]! : 0,
    k: priors ? tensor(t, 'prior.k', 1)[0]! : 0,
  };
}

const f = (x: number): string => {
  const s = String(Math.fround(x));
  return /[.eE]|Infinity|NaN/.test(s) ? s : `${s}.0`;
};

// ---------------------------------------------------------------- WGSL

const HEADER = `struct Params { b: u32, a: u32, c: u32, d: u32 }\n@group(0) @binding(0) var<uniform> prm: Params;\n`;

/** One output element per thread. CIN = input channels per cell (the planes for conv_in). */
function convNaive(C: number, CIN: number, res: boolean): string {
  return `${HEADER}
@group(0) @binding(1) var<storage, read> inp: array<f32>;
@group(0) @binding(2) var<storage, read> wgt: array<f32>;
@group(0) @binding(3) var<storage, read> bias: array<f32>;
@group(0) @binding(4) var<storage, read> nbr: array<u32>;
${res ? '@group(0) @binding(5) var<storage, read> res: array<f32>;' : ''}
@group(0) @binding(6) var<storage, read_write> outp: array<f32>;
const C = ${C}u;
const CIN = ${CIN}u;
@compute @workgroup_size(64)
fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
  let idx = gid.x;
  if (idx >= prm.b * 121u * C) { return; }
  let g = idx / C;
  let o = idx % C;
  let b = g / 121u;
  let c = g % 121u;
  var acc = bias[o];
  for (var k = 0u; k < 7u; k++) {
    let nb = nbr[c * 7u + k];
    if (nb == 121u) { continue; }
    let rowbase = (b * 122u + nb) * CIN;
    let wbase = o * 7u * CIN + k * CIN;
    for (var ch = 0u; ch < CIN; ch++) {
      acc += wgt[wbase + ch] * inp[rowbase + ch];
    }
  }
  ${res ? 'acc += res[(b * 122u + c) * C + o];' : ''}
  outp[(b * 122u + c) * C + o] = max(acc, 0.0);
}`;
}

/**
 * Tiled gather-convolution. Workgroup of 64 threads = (C/4 output groups of 4) x (64/(C/4) cell groups) x cpt cells.
 * wt is the weight transposed to [7*CIN][C] so that a vec4 holds 4 output channels. For conv_in (raw) the input has
 * `planes` channels per row and CIN is the planes rounded up to a multiple of 4, the surplus reads as 0.
 */
function convTiled(C: number, CIN: number, planes: number, raw: boolean, res: boolean, cpt: number): string {
  const OG = C / 4;
  const CG = 64 / OG;
  const CELLSWG = CG * cpt;
  const XST = CIN + 1;
  const accDecl = Array.from({ length: cpt }, (_, j) => `  var acc${j} = vec4<f32>(0.0);`).join('\n');
  const accUse = Array.from({ length: cpt }, (_, j) => `      acc${j} += xs[(cg * ${cpt}u + ${j}u) * XST + ch] * w;`).join('\n');
  const epilogue = Array.from({ length: cpt }, (_, j) => `
  {
    let g = base + cg * ${cpt}u + ${j}u;
    if (g < total) {
      let b = g / 121u;
      let c = g % 121u;
      let row = (b * 122u + c) * OG + og;
      var v = acc${j} + bias[og];
      ${res ? 'v += res[row];' : ''}
      outp[row] = max(v, vec4<f32>(0.0));
    }
  }`).join('');
  const gather = raw
    ? `    for (var i = li; i < CELLSWG * CIN; i += 64u) {
      let r = i / CIN;
      let ch = i % CIN;
      let g = base + r;
      var v = 0.0;
      if (g < total && ch < ${planes}u) {
        let nb = nbr[(g % 121u) * 7u + k];
        if (nb != 121u) { v = inp[((g / 121u) * 122u + nb) * ${planes}u + ch]; }
      }
      xs[r * XST + ch] = v;
    }`
    : `    for (var i = li; i < CELLSWG * CIN4; i += 64u) {
      let r = i / CIN4;
      let c4 = i % CIN4;
      let g = base + r;
      var v = vec4<f32>(0.0);
      if (g < total) {
        let nb = nbr[(g % 121u) * 7u + k];
        if (nb != 121u) { v = inp[((g / 121u) * 122u + nb) * CIN4 + c4]; }
      }
      let o = r * XST + c4 * 4u;
      xs[o] = v.x; xs[o + 1u] = v.y; xs[o + 2u] = v.z; xs[o + 3u] = v.w;
    }`;
  return `${HEADER}
@group(0) @binding(1) var<storage, read> inp: array<${raw ? 'f32' : 'vec4<f32>'}>;
@group(0) @binding(2) var<storage, read> wt: array<vec4<f32>>;
@group(0) @binding(3) var<storage, read> bias: array<vec4<f32>>;
@group(0) @binding(4) var<storage, read> nbr: array<u32>;
${res ? '@group(0) @binding(5) var<storage, read> res: array<vec4<f32>>;' : ''}
@group(0) @binding(6) var<storage, read_write> outp: array<vec4<f32>>;
const OG = ${OG}u;
const CIN = ${CIN}u;
const CIN4 = ${CIN / 4}u;
const XST = ${XST}u;
const CELLSWG = ${CELLSWG}u;
var<workgroup> xs: array<f32, ${CELLSWG * XST}>;
var<workgroup> ws: array<vec4<f32>, ${CIN * OG}>;
@compute @workgroup_size(64)
fn main(@builtin(workgroup_id) wid: vec3<u32>, @builtin(local_invocation_index) li: u32) {
  let total = prm.b * 121u;
  let base = wid.x * CELLSWG;
  let og = li % OG;
  let cg = li / OG;
${accDecl}
  for (var k = 0u; k < 7u; k++) {
${gather}
    for (var i = li; i < CIN * OG; i += 64u) { ws[i] = wt[k * CIN * OG + i]; }
    workgroupBarrier();
    for (var ch = 0u; ch < CIN; ch++) {
      let w = ws[ch * OG + og];
${accUse}
    }
    workgroupBarrier();
  }
${epilogue}
}`;
}

/** Policy head: 1x1 convolution C -> 2 per cell plus the distance prior. act is read as vec4 when vec4 is set. */
function policy(cfg: Config, maxBatch: number, vec4: boolean): string {
  const C = cfg.channels;
  const dot = vec4
    ? `  for (var j = 0u; j < ${C / 4}u; j++) {
    let a = act[(b * 122u + c) * ${C / 4}u + j];
    ft += dot(a, pw[j]);
    tt += dot(a, pw[${C / 4}u + j]);
  }`
    : `  for (var ch = 0u; ch < ${C}u; ch++) {
    let a = act[(b * 122u + c) * ${C}u + ch];
    ft += pw[ch] * a;
    tt += pw[${C}u + ch] * a;
  }`;
  return `${HEADER}
@group(0) @binding(1) var<storage, read> act: array<${vec4 ? 'vec4<f32>' : 'f32'}>;
@group(0) @binding(2) var<storage, read> pw: array<${vec4 ? 'vec4<f32>' : 'f32'}>;
@group(0) @binding(3) var<storage, read> pb: array<f32>;
${cfg.priors ? '@group(0) @binding(4) var<storage, read> raw: array<f32>;' : ''}
@group(0) @binding(5) var<storage, read_write> outp: array<f32>;
@compute @workgroup_size(64)
fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
  let idx = gid.x;
  if (idx >= prm.b * 121u) { return; }
  let b = idx / 121u;
  let c = idx % 121u;
  var ft = pb[0];
  var tt = pb[1];
${dot}
  ${cfg.priors ? `let term = ${f(cfg.beta * DIST_SCALE)} * raw[(b * 122u + c) * ${cfg.planes}u + ${DIST_OWN}u]; ft += term; tt -= term;` : ''}
  outp[idx * 2u] = ft;
  outp[idx * 2u + 1u] = tt;
}
// maxBatch ${maxBatch}`;
}

/** Value head: mean over the cells (split in `parts` partial sums), dense + ReLU, dense, prior, tanh. One workgroup per position. */
function value(cfg: Config, maxBatch: number, parts: number): string {
  const C = cfg.channels;
  const H = cfg.hidden;
  const P = cfg.players;
  const NT = Math.max(32, parts * C, H, P);
  return `${HEADER}
@group(0) @binding(1) var<storage, read> act: array<f32>;
@group(0) @binding(2) var<storage, read> w1: array<f32>;
@group(0) @binding(3) var<storage, read> b1: array<f32>;
@group(0) @binding(4) var<storage, read> w2: array<f32>;
@group(0) @binding(5) var<storage, read> b2: array<f32>;
${cfg.priors ? '@group(0) @binding(6) var<storage, read> raw: array<f32>;' : ''}
@group(0) @binding(7) var<storage, read_write> outp: array<f32>;
var<workgroup> partial: array<f32, ${parts * C}>;
var<workgroup> pooled: array<f32, ${C}>;
var<workgroup> hid: array<f32, ${H}>;
@compute @workgroup_size(${NT})
fn main(@builtin(workgroup_id) wid: vec3<u32>, @builtin(local_invocation_index) li: u32) {
  let b = wid.x;
  if (b >= prm.b) { return; }
  if (li < ${parts * C}u) {
    let ch = li % ${C}u;
    let part = li / ${C}u;
    var s = 0.0;
    for (var c = part; c < 121u; c += ${parts}u) { s += act[(b * 122u + c) * ${C}u + ch]; }
    partial[li] = s;
  }
  workgroupBarrier();
  if (li < ${C}u) {
    var s = 0.0;
    for (var p = 0u; p < ${parts}u; p++) { s += partial[p * ${C}u + li]; }
    pooled[li] = s / 121.0;
  }
  workgroupBarrier();
  if (li < ${H}u) {
    var s = b1[li];
    for (var ch = 0u; ch < ${C}u; ch++) { s += w1[li * ${C}u + ch] * pooled[ch]; }
    hid[li] = max(s, 0.0);
  }
  workgroupBarrier();
  if (li < ${P}u) {
    var s = b2[li];
    for (var j = 0u; j < ${H}u; j++) { s += w2[li * ${H}u + j] * hid[j]; }
    ${cfg.priors ? `var sign = 0.0; if (li == 0u) { sign = 1.0; } else if (li == 1u) { sign = -1.0; }
    s += ${f(cfg.k)} * sign * raw[b * 122u * ${cfg.planes}u + ${EVAL}u];` : ''}
    outp[${maxBatch * CELLS * 2}u + b * ${P}u + li] = tanh(clamp(s, -20.0, 20.0));
  }
}`;
}

// ---------------------------------------------------------------- host side

interface Slot {
  uniform: Gpu;
  input: Gpu;
  out: Gpu;
  staging: Gpu;
  groups: Gpu[];
  tail: Promise<unknown>;
}

export async function createNet(device: Gpu, safetensorsBytes: Uint8Array, options: NetOptions = {}): Promise<Net> {
  const variant: Variant = options.variant ?? 'tiled';
  const maxBatch = options.maxBatch ?? 128;
  const depth = options.depth ?? 2;
  const cpt = options.cellsPerThread ?? 2;
  const { tensors } = parseSafetensors(safetensorsBytes);
  const cfg = readConfig(tensors);
  const C = cfg.channels;
  const I = cfg.planes;
  const I4 = Math.ceil(I / 4) * 4;
  const U = (globalThis as any).GPUBufferUsage;
  const M = (globalThis as any).GPUMapMode;
  const tiled = variant === 'tiled';
  const buffers: Gpu[] = [];

  const makeBuffer = (size: number, usage: number): Gpu => {
    const b = device.createBuffer({ size: Math.max(16, Math.ceil(size / 4) * 4), usage });
    buffers.push(b);
    return b;
  };
  const constant = (data: Float32Array | Uint32Array): Gpu => {
    const b = makeBuffer(data.byteLength, U.STORAGE | U.COPY_DST);
    device.queue.writeBuffer(b, 0, data);
    return b;
  };
  /** [C_out][7*cin] (cin = what the file has) -> [7*cinPadded][C_out], zero padded. */
  const transpose = (w: Float32Array, cin: number, cinPad: number): Float32Array => {
    const t = new Float32Array(7 * cinPad * C);
    for (let o = 0; o < C; o++) {
      for (let k = 0; k < 7; k++) {
        for (let ch = 0; ch < cin; ch++) t[(k * cinPad + ch) * C + o] = w[o * 7 * cin + k * cin + ch]!;
      }
    }
    return t;
  };

  const nbr = constant(neighbourTable());
  const wIn = tensor(tensors, 'conv_in.weight', C * 7 * I);
  const bIn = tensor(tensors, 'conv_in.bias', C);
  const convW = (name: string): Gpu => constant(tiled ? transpose(tensor(tensors, `${name}.weight`, C * 7 * C), C, C) : tensor(tensors, `${name}.weight`, C * 7 * C));
  const gIn = constant(tiled ? transpose(wIn, I, I4) : wIn);
  const gBIn = constant(bIn);
  const blocks = Array.from({ length: cfg.blocks }, (_, i) => ({
    w1: convW(`block${i}.conv1`), b1: constant(tensor(tensors, `block${i}.conv1.bias`, C)),
    w2: convW(`block${i}.conv2`), b2: constant(tensor(tensors, `block${i}.conv2.bias`, C)),
  }));
  const pw = constant(tensor(tensors, 'head_policy.weight', 2 * C));
  const pb = constant(tensor(tensors, 'head_policy.bias', 2));
  const vw1 = constant(tensor(tensors, 'head_value.dense1.weight', cfg.hidden * C));
  const vb1 = constant(tensor(tensors, 'head_value.dense1.bias', cfg.hidden));
  const vw2 = constant(tensor(tensors, 'head_value.dense2.weight', cfg.players * cfg.hidden));
  const vb2 = constant(tensor(tensors, 'head_value.dense2.bias', cfg.players));

  const pipe = async (code: string, label: string): Promise<Gpu> => {
    const module = device.createShaderModule({ code, label });
    const info = await module.getCompilationInfo();
    const errors = info.messages.filter((m: Gpu) => m.type === 'error');
    if (errors.length) throw new Error(`WGSL ${label}: ${errors.map((m: Gpu) => `${m.lineNum}: ${m.message}`).join('; ')}`);
    return device.createComputePipelineAsync({ layout: 'auto', compute: { module, entryPoint: 'main' }, label });
  };
  const cells = tiled ? (64 / (C / 4)) * cpt : 0;
  const [pIn, pConv, pConvRes, pPol, pVal] = await Promise.all([
    pipe(tiled ? convTiled(C, I4, I, true, false, cpt) : convNaive(C, I, false), 'conv_in'),
    pipe(tiled ? convTiled(C, C, C, false, false, cpt) : convNaive(C, C, false), 'conv1'),
    pipe(tiled ? convTiled(C, C, C, false, true, cpt) : convNaive(C, C, true), 'conv2'),
    pipe(policy(cfg, maxBatch, tiled), 'policy'),
    pipe(value(cfg, maxBatch, tiled ? 4 : 1), 'value'),
  ]);

  const actBytes = maxBatch * ROWS * C * 4;
  const outFloats = maxBatch * CELLS * 2 + maxBatch * cfg.players;
  const entries = (...bufs: Gpu[]): Gpu[] => bufs.map((buffer, binding) => (buffer ? { binding, resource: { buffer } } : null)).filter(Boolean);

  const slots: Slot[] = [];
  for (let s = 0; s < depth; s++) {
    const uniform = makeBuffer(16, U.UNIFORM | U.COPY_DST);
    const input = makeBuffer(maxBatch * ROWS * I * 4, U.STORAGE | U.COPY_DST);
    const A = makeBuffer(actBytes, U.STORAGE);
    const B = makeBuffer(actBytes, U.STORAGE);
    const T = makeBuffer(actBytes, U.STORAGE);
    const out = makeBuffer(outFloats * 4, U.STORAGE | U.COPY_SRC);
    const staging = makeBuffer(outFloats * 4, U.MAP_READ | U.COPY_DST);
    const groups: Gpu[] = [];
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
  let next = 0;
  const run = async (slot: Slot, inputs: Float32Array, batch: number): Promise<NetResult> => {
    device.queue.writeBuffer(slot.uniform, 0, new Uint32Array([batch, 0, 0, 0]));
    device.queue.writeBuffer(slot.input, 0, inputs, 0, batch * ROWS * I);
    const enc = device.createCommandEncoder();
    const pass = enc.beginComputePass();
    const dispatch = (p: Gpu, g: Gpu, n: number): void => {
      pass.setPipeline(p);
      pass.setBindGroup(0, g);
      pass.dispatchWorkgroups(n);
    };
    let gi = 0;
    const convCount = tiled ? Math.ceil((batch * CELLS) / cells) : Math.ceil((batch * CELLS * C) / 64);
    dispatch(pIn, slot.groups[gi++], convCount);
    for (let i = 0; i < cfg.blocks; i++) {
      dispatch(pConv, slot.groups[gi++], convCount);
      dispatch(pConvRes, slot.groups[gi++], convCount);
    }
    dispatch(pPol, slot.groups[gi++], Math.ceil((batch * CELLS) / 64));
    dispatch(pVal, slot.groups[gi++], batch);
    pass.end();
    const polBytes = batch * CELLS * 2 * 4;
    const valBytes = batch * cfg.players * 4;
    const valOffset = maxBatch * CELLS * 2 * 4;
    enc.copyBufferToBuffer(slot.out, 0, slot.staging, 0, polBytes);
    enc.copyBufferToBuffer(slot.out, valOffset, slot.staging, valOffset, valBytes);
    device.queue.submit([enc.finish()]);
    await slot.staging.mapAsync(M.READ);
    const all = new Float32Array(slot.staging.getMappedRange());
    const pol = all.slice(0, batch * CELLS * 2);
    const values = all.slice(valOffset / 4, valOffset / 4 + batch * cfg.players);
    slot.staging.unmap();
    const policyFrom = new Float32Array(batch * CELLS);
    const policyTo = new Float32Array(batch * CELLS);
    for (let i = 0; i < batch * CELLS; i++) {
      policyFrom[i] = pol[2 * i]!;
      policyTo[i] = pol[2 * i + 1]!;
    }
    return { policyFrom, policyTo, values };
  };

  return {
    channels: C, blocks: cfg.blocks, players: cfg.players, inputPlanes: I, variant, maxBatch,
    evaluate(inputs, batch) {
      if (!Number.isInteger(batch) || batch < 1 || batch > maxBatch) return Promise.reject(new RangeError(`batch ${batch} not in 1..${maxBatch}`));
      if (inputs.length < batch * ROWS * I) return Promise.reject(new RangeError(`inputs has ${inputs.length} floats, need ${batch * ROWS * I}`));
      const slot = slots[next]!;
      next = (next + 1) % slots.length;
      const p = slot.tail.then(() => run(slot, inputs, batch));
      slot.tail = p.catch(() => undefined);
      return p;
    },
    destroy() {
      for (const b of buffers) b.destroy();
    },
  };
}
