// A plain TypeScript forward pass of the hex-convolution network (docs/network.md), for the Info figures only: it exposes the
// hidden layers that the fast backends do not return. The game never calls it; it plays with engines/net/wasm-backend.ts or
// webgpu-backend.ts.
//
// The arithmetic follows the scalar C++ reference (cpp/src/nn/reference_net.cpp, gather.cpp, priors.cpp) and the Java
// ReferenceOps: every dot product accumulates in double (a JavaScript number) in ascending index order and is rounded to float
// (Math.fround) once per output element; ReLU keeps NaN and turns -0 into +0; the residual priors are added in float in the
// order of Java. The tanh is Math.tanh, so a value may differ from Java or C++ in the last bit.
//
// Layouts, as in the Java Encoder: an activation is [122 rows][channels], row 121 is the all-zero padding row; a convolution
// weight is [out][7 stencil entries][in], the policy logits are [121 cells][2 channels] with channel 0 "from" and 1 "to". All
// cells are canonical: for two players the board is rotated by 180 degrees when player 1 moves (nn/.../Orientation.java).
import { CELL_COUNT, cellAt, cellId, neighbor } from '../../board/cells';
import { parseSafetensors } from './safetensors';

const CELLS = CELL_COUNT;
const ROWS = CELLS + 1;
const PADDING = CELLS;
const STENCIL = 7;
const POLICY_CHANNELS = 2;
const FROM = 0;
const TO = 1;
/** Encoder.DIST_OWN, Encoder.EVAL and ClassicalPlanes.DIST_SCALE of the classic1 plane set. */
const DIST_OWN = 7;
const EVAL = 12;
const DIST_SCALE = 16;

export interface ReferenceOutputs {
  /** One entry per trunk layer (conv_in, block0 ... blockN-1): the L2 norm over the channels after the ReLU, 121 canonical cells. */
  norms: Float32Array[];
  /** 242 logits [cell][from, to], canonical cells, with the classical distance prior (what NetBackend.evaluate returns, interleaved). */
  policy: Float32Array;
  /** The same logits without the prior (the raw policy head). */
  policyNetOnly: Float32Array;
  /** One value per relative player (mover first), after the tanh, with the value prior. */
  values: Float32Array;
  /** The values without the value prior. */
  valuesNetOnly: Float32Array;
}

interface Net {
  channels: number;
  blocks: number;
  players: number;
  hidden: number;
  planes: number;
  priors: boolean;
  tensor: (name: string, length: number) => Float32Array;
}

/** gather[cell * 7 + k]: k = 0 the cell itself, k = 1..6 the neighbour in direction k - 1, or the padding row off the board. */
const GATHER: Int32Array = (() => {
  const t = new Int32Array(CELLS * STENCIL);
  for (let c = 0; c < CELLS; c++) {
    t[c * STENCIL] = c;
    for (let d = 0; d < 6; d++) {
      const n = neighbor(c, d);
      t[c * STENCIL + 1 + d] = n < 0 ? PADDING : n;
    }
  }
  return t;
})();

const nets = new WeakMap<Uint8Array, Net>();

function intMeta(meta: Record<string, string>, key: string): number {
  const v = Number(meta[key]);
  if (!Number.isInteger(v) || v < 1) throw new Error(`reference forward: metadata ${key} is ${String(meta[key])}, expected a positive integer`);
  return v;
}

function netOf(weightsF32: Uint8Array): Net {
  const cached = nets.get(weightsF32);
  if (cached) return cached;
  const st = parseSafetensors(weightsF32);
  const m = st.metadata;
  const planeSet = m['planes'] ?? 'plain';
  const net: Net = {
    channels: intMeta(m, 'channels'),
    blocks: intMeta(m, 'blocks'),
    players: intMeta(m, 'players'),
    hidden: intMeta(m, 'value_hidden'),
    planes: intMeta(m, 'input_planes'),
    priors: planeSet === 'classic1' || planeSet === 'classic1v',
    tensor: (name, length) => {
      const t = st.tensors.get(name);
      if (!t) throw new Error(`reference forward: the net has no tensor ${name}`);
      if (t.data.length !== length) throw new Error(`reference forward: tensor ${name} has ${t.data.length} floats, expected ${length}`);
      return t.data;
    },
  };
  // A classic1v net has three value outputs for two and three players (cpp/src/nn/priors.hpp, absolute_values).
  if (planeSet === 'classic1v') net.players = 3;
  nets.set(weightsF32, net);
  return net;
}

const relu = (a: Float32Array): void => {
  for (let i = 0; i < a.length; i++) {
    const v = a[i]!;
    a[i] = v !== v ? v : Math.max(v, 0); // keeps NaN, turns -0 into +0
  }
};

/** HexGrid convolution: out[c][o] = bias[o] + sum over k, ch of weight[o][k][ch] * in[gather(c, k)][ch]. Row 121 of out is 0. */
function hexConv(input: Float32Array, cin: number, weight: Float32Array, bias: Float32Array, cout: number, out: Float32Array): void {
  for (let ch = 0; ch < cin; ch++) {
    const pad = input[PADDING * cin + ch]!;
    if (pad !== 0) throw new Error(`reference forward: padding row of the input is not zero in channel ${ch}: ${pad}`);
  }
  const width = STENCIL * cin;
  for (let c = 0; c < CELLS; c++) {
    for (let o = 0; o < cout; o++) {
      let sum = bias[o]!;
      for (let k = 0; k < STENCIL; k++) {
        const x = GATHER[c * STENCIL + k]! * cin;
        const w = o * width + k * cin;
        for (let ch = 0; ch < cin; ch++) sum += weight[w + ch]! * input[x + ch]!;
      }
      out[c * cout + o] = sum; // the Float32Array store rounds to float once
    }
  }
  out.fill(0, PADDING * cout, ROWS * cout);
}

/** out[row][o] = bias[o] + sum over i of weight[o][i] * in[row][i], for `rows` rows. */
function pointwise(input: Float32Array, rows: number, cin: number, weight: Float32Array, bias: Float32Array, cout: number, out: Float32Array): void {
  for (let row = 0; row < rows; row++) {
    for (let o = 0; o < cout; o++) {
      let sum = bias[o]!;
      for (let i = 0; i < cin; i++) sum += weight[o * cin + i]! * input[row * cin + i]!;
      out[row * cout + o] = sum;
    }
  }
}

/** L2 norm over the channels of each of the 121 cell rows. */
function norm(x: Float32Array, channels: number): Float32Array {
  const out = new Float32Array(CELLS);
  for (let row = 0; row < CELLS; row++) {
    let sum = 0;
    for (let ch = 0; ch < channels; ch++) {
      const v = x[row * channels + ch]!;
      sum += v * v;
    }
    out[row] = Math.sqrt(sum);
  }
  return out;
}

/** +1 for relative player 0, -1 for player 1, 0 for player 2 (ResidualPriors.valueBase). */
const valueSign = (relative: number): number => (relative === 0 ? 1 : relative === 1 ? -1 : 0);

/**
 * Runs the network on ONE encoded position. `weightsF32` is an F32 safetensors file (what models.ts loadModel returns, or
 * widenSafetensors of a shipped model); the parsed net is cached per array. `input` is [122][planes] floats in canonical
 * orientation, as the net facade's inputs() returns them.
 */
export function referenceForward(weightsF32: Uint8Array, input: Float32Array): ReferenceOutputs {
  const net = netOf(weightsF32);
  const { channels: c, blocks, players, hidden: h, planes } = net;
  if (input.length !== ROWS * planes) {
    throw new Error(`reference forward: expected ${ROWS} x ${planes} = ${ROWS * planes} input floats, got ${input.length}`);
  }
  const t = net.tensor;
  const conv = (name: string, cin: number): [Float32Array, Float32Array] => [t(`${name}.weight`, c * STENCIL * cin), t(`${name}.bias`, c)];

  const size = ROWS * c;
  let x = new Float32Array(size);
  const tmp = new Float32Array(size);
  let u = new Float32Array(size);
  const norms: Float32Array[] = [];

  // conv_in, bias, ReLU.
  hexConv(input, planes, ...conv('conv_in', planes), c, x);
  relu(x);
  norms.push(norm(x, c));
  // Residual blocks: x = ReLU(conv2(ReLU(conv1(x))) + x).
  for (let i = 0; i < blocks; i++) {
    hexConv(x, c, ...conv(`block${i}.conv1`, c), c, tmp);
    relu(tmp);
    hexConv(tmp, c, ...conv(`block${i}.conv2`, c), c, u);
    for (let j = 0; j < size; j++) u[j] = u[j]! + x[j]!;
    relu(u);
    [x, u] = [u, x];
    norms.push(norm(x, c));
  }

  // Policy head: 1x1 convolution on the 121 cell rows, then the distance prior.
  const policyNetOnly = new Float32Array(CELLS * POLICY_CHANNELS);
  pointwise(x, CELLS, c, t('head_policy.weight', POLICY_CHANNELS * c), t('head_policy.bias', POLICY_CHANNELS), POLICY_CHANNELS, policyNetOnly);
  const policy = policyNetOnly.slice();
  if (net.priors) {
    const scale = Math.fround(t('prior.beta', 1)[0]! * DIST_SCALE);
    for (let cell = 0; cell < CELLS; cell++) {
      const term = Math.fround(scale * input[cell * planes + DIST_OWN]!);
      policy[cell * POLICY_CHANNELS + FROM] = policy[cell * POLICY_CHANNELS + FROM]! + term;
      policy[cell * POLICY_CHANNELS + TO] = policy[cell * POLICY_CHANNELS + TO]! - term;
    }
  }

  // Value head: mean over the cells, dense + ReLU, dense, (+ prior), tanh.
  const pooled = new Float32Array(c);
  for (let ch = 0; ch < c; ch++) {
    let sum = 0;
    for (let cell = 0; cell < CELLS; cell++) sum += x[cell * c + ch]!;
    pooled[ch] = sum / CELLS;
  }
  const hiddenOut = new Float32Array(h);
  pointwise(pooled, 1, c, t('head_value.dense1.weight', h * c), t('head_value.dense1.bias', h), h, hiddenOut);
  relu(hiddenOut);
  const raw = new Float32Array(players);
  pointwise(hiddenOut, 1, h, t('head_value.dense2.weight', players * h), t('head_value.dense2.bias', players), players, raw);
  const valuesNetOnly = raw.map((v) => Math.tanh(v));
  const values = raw.slice();
  if (net.priors) {
    const k = t('prior.k', 1)[0]!;
    for (let i = 0; i < players; i++) {
      const base = Math.fround(valueSign(i) * input[EVAL]!);
      values[i] = values[i]! + Math.fround(k * base);
    }
  }
  for (let i = 0; i < players; i++) values[i] = Math.tanh(values[i]!);
  return { norms, policy, policyNetOnly, values, valuesNetOnly };
}

/**
 * The board cell of a canonical cell for a two-player position: the identity for mover 0, the rotation by 180 degrees
 * ((q, r) to (-q, -r)) for mover 1. The rotation is its own inverse, so the same function maps board cells to canonical cells.
 */
export function toAbsolute(cellCanonical: number, mover: 0 | 1): number {
  if (mover === 0) return cellCanonical;
  const { q, r } = cellAt(cellCanonical);
  return cellId(-q, -r);
}
