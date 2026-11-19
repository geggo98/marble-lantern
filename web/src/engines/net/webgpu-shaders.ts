// WGSL source and weight layout of the tiled WebGPU forward pass. Pure functions with no GPU access, so that node tests can
// check them. The kernels compute the network of docs/network.md in F32.
//
// Tiled gather-convolution: a workgroup computes a tile of cells x all output channels. For each of the 7 neighbour slots the
// gathered activations and the transposed weight slice go to workgroup memory; weights and activations are read as vec4;
// each thread keeps `cellsPerThread` x 4 accumulators. The value head splits the mean over 4 partial sums.
import type { TensorInfo } from './safetensors';

export const CELLS = 121;
export const ROWS = 122;
/** Plane that holds the classical distance of the player's own pieces; 16 * plane is the integer distance (docs/network.md). */
export const DIST_OWN = 7;
/** Plane that holds the classical evaluation (docs/network.md). */
export const EVAL = 12;
export const DIST_SCALE = 16;
/** Value head: partial sums of the mean over the cells. */
export const VALUE_PARTS = 4;
export const WORKGROUP = 64;

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

export interface NetConfig {
  channels: number;
  blocks: number;
  /** Width of the value head. */
  players: number;
  hidden: number;
  /** Input planes per cell. */
  planes: number;
  priors: boolean;
  beta: number;
  k: number;
}

/** The F32 values of a tensor, checked against the expected number of values. */
export function requireTensor(t: Map<string, TensorInfo>, name: string, size: number): Float32Array {
  const x = t.get(name);
  if (!x) throw new Error(`weights: tensor ${name} is missing`);
  if (x.data.length !== size) throw new Error(`weights: tensor ${name} has ${x.data.length} values, expected ${size}`);
  return x.data;
}

/** Reads the shape of the net from the tensors. Throws a plain Error with a message that names the problem. */
export function readNetConfig(t: Map<string, TensorInfo>): NetConfig {
  const convIn = t.get('conv_in.weight');
  if (!convIn) throw new Error('weights: conv_in.weight is missing');
  const channels = convIn.shape[0]!;
  const planes = convIn.shape[1]! / 7;
  let blocks = 0;
  while (t.has(`block${blocks}.conv1.weight`)) blocks++;
  const hidden = t.get('head_value.dense1.bias')?.data.length ?? 0;
  const players = t.get('head_value.dense2.bias')?.data.length ?? 0;
  const priors = t.has('prior.beta') && t.has('prior.k');
  if (!Number.isInteger(planes) || !Number.isInteger(channels) || channels < 4 || channels % 4 !== 0 || 64 % (channels / 4) !== 0
      || blocks < 1 || !hidden || !players) {
    throw new Error(`weights: unsupported shape (channels ${channels}, planes ${planes}, blocks ${blocks})`);
  }
  if (priors && planes <= EVAL) throw new Error('weights: priors need the classical planes (13 or 15)');
  return {
    channels, blocks, players, hidden, planes, priors,
    beta: priors ? requireTensor(t, 'prior.beta', 1)[0]! : 0,
    k: priors ? requireTensor(t, 'prior.k', 1)[0]! : 0,
  };
}

/** Cells per workgroup of the convolution kernels: (64 / (C/4)) cell groups times cpt cells per thread. */
export function cellsPerWorkgroup(channels: number, cpt: number): number {
  return (WORKGROUP / (channels / 4)) * cpt;
}

/** What the kernels need from a device, for the limit check before any buffer exists. */
export function deviceNeeds(cfg: NetConfig, cpt: number): { workgroupStorageBytes: number; valueWorkgroupSize: number } {
  const C = cfg.channels;
  const OG = C / 4;
  const cellsWg = cellsPerWorkgroup(C, cpt);
  const planesPad = Math.ceil(cfg.planes / 4) * 4;
  // xs: cellsWg rows of (CIN + 1) floats; ws: CIN * OG vec4 (16 bytes); for conv_in CIN = planesPad, for the others C
  const bytes = (cin: number): number => cellsWg * (cin + 1) * 4 + cin * OG * 16;
  return {
    workgroupStorageBytes: Math.max(bytes(planesPad), bytes(C)),
    valueWorkgroupSize: Math.max(32, VALUE_PARTS * C, cfg.hidden, cfg.players),
  };
}

const f = (x: number): string => {
  const s = String(Math.fround(x));
  return /[.eE]|Infinity|NaN/.test(s) ? s : `${s}.0`;
};

const HEADER = `struct Params { b: u32, a: u32, c: u32, d: u32 }\n@group(0) @binding(0) var<uniform> prm: Params;\n`;

/**
 * Tiled gather-convolution. Workgroup of 64 threads = (C/4 output groups of 4) x (64/(C/4) cell groups) x cpt cells.
 * wt is the weight transposed to [7*CIN][C] so that a vec4 holds 4 output channels. For conv_in (raw) the input has
 * `planes` channels per row and CIN is the planes rounded up to a multiple of 4, the surplus reads as 0.
 */
export function convTiled(C: number, CIN: number, planes: number, raw: boolean, res: boolean, cpt: number): string {
  const OG = C / 4;
  const CG = WORKGROUP / OG;
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

/** Policy head: 1x1 convolution C -> 2 per cell plus the distance prior. */
export function policyShader(cfg: NetConfig): string {
  const C = cfg.channels;
  return `${HEADER}
@group(0) @binding(1) var<storage, read> act: array<vec4<f32>>;
@group(0) @binding(2) var<storage, read> pw: array<vec4<f32>>;
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
  for (var j = 0u; j < ${C / 4}u; j++) {
    let a = act[(b * 122u + c) * ${C / 4}u + j];
    ft += dot(a, pw[j]);
    tt += dot(a, pw[${C / 4}u + j]);
  }
  ${cfg.priors ? `let term = ${f(cfg.beta * DIST_SCALE)} * raw[(b * 122u + c) * ${cfg.planes}u + ${DIST_OWN}u]; ft += term; tt -= term;` : ''}
  outp[idx * 2u] = ft;
  outp[idx * 2u + 1u] = tt;
}`;
}

/** Value head: mean over the cells (split in VALUE_PARTS partial sums), dense + ReLU, dense, prior, tanh. One workgroup per position. */
export function valueShader(cfg: NetConfig, maxBatch: number): string {
  const C = cfg.channels;
  const H = cfg.hidden;
  const P = cfg.players;
  const parts = VALUE_PARTS;
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

/** [C_out][7*cin] (cin = what the file has) -> [7*cinPadded][C_out], zero padded, so that a vec4 holds 4 output channels. */
export function transposeWeights(w: Float32Array, channels: number, cin: number, cinPad: number): Float32Array {
  const t = new Float32Array(7 * cinPad * channels);
  for (let o = 0; o < channels; o++) {
    for (let k = 0; k < 7; k++) {
      for (let ch = 0; ch < cin; ch++) t[(k * cinPad + ch) * channels + o] = w[o * 7 * cin + k * cin + ch]!;
    }
  }
  return t;
}
