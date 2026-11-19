// Tests of the parts of the WebGPU net that need no GPU: gather table, net shape, device needs, weight layout and WGSL text.
// The WGSL itself is compiled and checked against the Java reference in web/e2e/webgpu-backend.spec.ts.
import { describe, expect, it } from 'vitest';
import type { TensorInfo } from './safetensors';
import {
  cellsPerWorkgroup, convTiled, deviceNeeds, neighbourTable, policyShader, readNetConfig, transposeWeights, valueShader, type NetConfig,
} from './webgpu-shaders';

describe('neighbourTable', () => {
  const t = neighbourTable();

  it('has the counts of docs/network.md: 121 self entries, 624 neighbours, 102 padding entries', () => {
    expect(t.length).toBe(121 * 7);
    let pad = 0;
    for (let c = 0; c < 121; c++) {
      expect(t[c * 7]).toBe(c);
      for (let k = 1; k < 7; k++) if (t[c * 7 + k] === 121) pad++;
    }
    expect(pad).toBe(102);
    const real = Array.from({ length: 121 }, (_, c) => [1, 2, 3, 4, 5, 6].filter((k) => t[c * 7 + k] !== 121).length);
    expect(real.filter((n) => n === 6).length).toBe(73);
    expect(real.filter((n) => n === 5).length).toBe(6);
    expect(real.filter((n) => n === 4).length).toBe(36);
    expect(real.filter((n) => n === 2).length).toBe(6);
    expect(real.reduce((a, b) => a + b, 0)).toBe(624);
  });

  it('is symmetric: the neighbour in direction d has the cell as neighbour in direction d ^ 1', () => {
    for (let c = 0; c < 121; c++) {
      for (let d = 0; d < 6; d++) {
        const n = t[c * 7 + 1 + d]!;
        if (n !== 121) expect(t[n * 7 + 1 + (d ^ 1)]).toBe(c);
      }
    }
  });

  it('puts cell 0 at a tip of the star: only 2 of its 6 neighbours exist', () => {
    const real = Array.from({ length: 6 }, (_, d) => t[1 + d]!).filter((n) => n !== 121);
    expect(real.length).toBe(2);
  });
});

function tensorInfo(name: string, shape: number[], fill = 0): TensorInfo {
  const n = shape.reduce((a, b) => a * b, 1);
  return { name, dtype: 'F32', shape, data: new Float32Array(n).fill(fill) };
}

/** The tensors of a net with the given sizes, as parseSafetensors returns them. */
function netTensors(c: number, planes: number, blocks: number, hidden: number, players: number, priors: boolean): Map<string, TensorInfo> {
  const list = [tensorInfo('conv_in.weight', [c, 7 * planes]), tensorInfo('conv_in.bias', [c])];
  for (let b = 0; b < blocks; b++) list.push(tensorInfo(`block${b}.conv1.weight`, [c, 7 * c]));
  list.push(tensorInfo('head_value.dense1.bias', [hidden]), tensorInfo('head_value.dense2.bias', [players]));
  if (priors) list.push({ ...tensorInfo('prior.beta', [1]), data: new Float32Array([0.5]) }, { ...tensorInfo('prior.k', [1]), data: new Float32Array([0.25]) });
  return new Map(list.map((t) => [t.name, t]));
}

describe('readNetConfig', () => {
  it('reads channels, planes, blocks, hidden width, players and the prior scalars', () => {
    const cfg = readNetConfig(netTensors(32, 13, 4, 64, 2, true));
    expect(cfg).toEqual({ channels: 32, planes: 13, blocks: 4, hidden: 64, players: 2, priors: true, beta: 0.5, k: 0.25 });
  });

  it('reads a plain net without priors', () => {
    const cfg = readNetConfig(netTensors(8, 7, 1, 16, 3, false));
    expect(cfg.priors).toBe(false);
    expect(cfg.planes).toBe(7);
    expect(cfg.players).toBe(3);
    expect(cfg.beta).toBe(0);
  });

  it('rejects what the kernels cannot run, with a message that names the problem', () => {
    expect(() => readNetConfig(new Map())).toThrow(/conv_in.weight is missing/);
    expect(() => readNetConfig(netTensors(30, 13, 1, 8, 2, false))).toThrow(/unsupported shape \(channels 30/);
    expect(() => readNetConfig(netTensors(12, 13, 1, 8, 2, false))).toThrow(/unsupported shape \(channels 12/); // 64 % 3 != 0
    expect(() => readNetConfig(netTensors(32, 13, 0, 8, 2, false))).toThrow(/blocks 0/);
    expect(() => readNetConfig(netTensors(32, 7, 1, 8, 2, true))).toThrow(/priors need the classical planes/);
  });

  it('rejects a prior scalar with the wrong number of values', () => {
    const t = netTensors(32, 13, 1, 8, 2, true);
    t.set('prior.k', tensorInfo('prior.k', [2]));
    expect(() => readNetConfig(t)).toThrow(/prior.k has 2 values, expected 1/);
  });
});

describe('deviceNeeds', () => {
  const cfg = (channels: number, planes: number, hidden: number): NetConfig =>
    ({ channels, planes, blocks: 4, hidden, players: 2, priors: true, beta: 0, k: 0 });

  it('SMALL (32 channels, 13 planes) fits the default 16 KiB of workgroup memory', () => {
    // 16 cells per workgroup (8 cell groups x 2); conv_in: CIN 16: 16*17*4 + 16*8*16 = 1088 + 2048; trunk: CIN 32: 16*33*4 + 32*8*16 = 2112 + 4096
    expect(deviceNeeds(cfg(32, 13, 64), 2)).toEqual({ workgroupStorageBytes: 6208, valueWorkgroupSize: 128 });
  });

  it('LARGE (64 channels) needs more than the default limit, so the backend must ask for it', () => {
    // 8 cells per workgroup; trunk: 8*65*4 + 64*16*16 = 2080 + 16384
    expect(deviceNeeds(cfg(64, 15, 64), 2)).toEqual({ workgroupStorageBytes: 18464, valueWorkgroupSize: 256 });
  });

  it('one cell per thread halves the tile', () => {
    expect(cellsPerWorkgroup(32, 1)).toBe(8);
    expect(cellsPerWorkgroup(32, 2)).toBe(16);
    expect(deviceNeeds(cfg(32, 13, 64), 1).workgroupStorageBytes).toBe(8 * 33 * 4 + 4096);
  });
});

describe('transposeWeights', () => {
  it('moves [C_out][7*cin] to [7*cinPad][C_out] with zero padding', () => {
    // 4 output channels, cin 2, cinPad 4: w[o][k*2+ch] = 100*o + 10*k + ch
    const w = new Float32Array(4 * 7 * 2);
    for (let o = 0; o < 4; o++) for (let k = 0; k < 7; k++) for (let ch = 0; ch < 2; ch++) w[o * 14 + k * 2 + ch] = 100 * o + 10 * k + ch;
    const t = transposeWeights(w, 4, 2, 4);
    expect(t.length).toBe(7 * 4 * 4);
    // entry (k, ch, o) at (k * 4 + ch) * 4 + o
    expect(t[(3 * 4 + 1) * 4 + 2]).toBe(100 * 2 + 10 * 3 + 1);
    expect(t[(6 * 4 + 0) * 4 + 3]).toBe(300 + 60);
    // padded channels stay 0
    expect(t[(2 * 4 + 2) * 4 + 1]).toBe(0);
    expect(t[(2 * 4 + 3) * 4 + 1]).toBe(0);
  });
});

describe('WGSL source', () => {
  const cfg: NetConfig = { channels: 32, planes: 13, blocks: 4, hidden: 64, players: 2, priors: true, beta: 0.5, k: 0.25 };

  it('conv kernel: tile constants follow the channel count and cells per thread', () => {
    const w = convTiled(32, 32, 32, false, false, 2);
    expect(w).toContain('const OG = 8u;');
    expect(w).toContain('const CIN4 = 8u;');
    expect(w).toContain('const XST = 33u;');
    expect(w).toContain('const CELLSWG = 16u;'); // 8 cell groups x 2
    expect(w).toContain('array<f32, 528>'); // 16 * 33
    expect(w).toContain('array<vec4<f32>, 256>'); // 32 * 8
    expect(w).toContain('@workgroup_size(64)');
    expect(w).toContain('acc1');
    expect(convTiled(32, 32, 32, false, false, 1)).not.toContain('acc1');
    expect(convTiled(64, 64, 64, false, false, 2)).toContain('const CELLSWG = 8u;');
  });

  it('conv kernel: the residual input is bound only in the second convolution of a block', () => {
    expect(convTiled(32, 32, 32, false, true, 2)).toContain('var<storage, read> res:');
    expect(convTiled(32, 32, 32, false, false, 2)).not.toContain('res');
  });

  it('conv_in kernel: reads raw planes, pads to CIN and zero-fills the padding', () => {
    const w = convTiled(32, 16, 13, true, false, 2);
    expect(w).toContain('array<f32>;'); // raw f32 input
    expect(w).toContain('ch < 13u');
    expect(w).toContain('const CIN = 16u;');
  });

  it('policy kernel: the distance prior is added to "from" and subtracted from "to" only with priors', () => {
    const w = policyShader(cfg);
    expect(w).toContain('let term = 8.0 * raw[(b * 122u + c) * 13u + 7u]; ft += term; tt -= term;'); // beta 0.5 * 16
    expect(w).toContain('j < 8u'); // 32 / 4
    expect(policyShader({ ...cfg, priors: false })).not.toContain('term');
  });

  it('value kernel: the prior uses plane 12, the sign is + for player 0 and - for player 1, output offset follows maxBatch', () => {
    const w = valueShader(cfg, 8);
    expect(w).toContain('0.25 * sign * raw[b * 122u * 13u + 12u]');
    expect(w).toContain('outp[1936u + b * 2u + li]'); // 8 * 121 * 2
    expect(w).toContain('@workgroup_size(128)'); // 4 parts x 32 channels
    expect(valueShader({ ...cfg, priors: false }, 8)).not.toContain('sign');
    expect(valueShader(cfg, 16)).toContain('outp[3872u');
  });

  it('formats scalar constants as WGSL floats (no bare integers, no exponent loss)', () => {
    expect(policyShader({ ...cfg, beta: 0.0625 })).toContain('let term = 1.0 *');
    expect(policyShader({ ...cfg, beta: 1e-9 })).toMatch(/let term = [0-9.]+e-[0-9]+ \*/);
  });
});
