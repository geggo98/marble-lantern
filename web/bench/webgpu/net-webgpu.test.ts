// Tests of the parts of the WebGPU net that need no GPU: the gather table and the safetensors reader.
import { describe, expect, it } from 'vitest';
import { neighbourTable } from './net-webgpu.js';
import { parseSafetensors } from './safetensors.js';

describe('neighbourTable', () => {
  const t = neighbourTable();

  it('has the counts of docs/network.md: 121 self entries, 624 neighbours, 102 padding entries', () => {
    expect(t.length).toBe(121 * 7);
    let self = 0;
    let pad = 0;
    for (let c = 0; c < 121; c++) {
      expect(t[c * 7]).toBe(c);
      self++;
      for (let k = 1; k < 7; k++) if (t[c * 7 + k] === 121) pad++;
    }
    expect(self).toBe(121);
    expect(pad).toBe(102);
    const sixNeighbours = Array.from({ length: 121 }, (_, c) => [1, 2, 3, 4, 5, 6].filter((k) => t[c * 7 + k] !== 121).length);
    expect(sixNeighbours.filter((n) => n === 6).length).toBe(73);
    expect(sixNeighbours.filter((n) => n === 5).length).toBe(6);
    expect(sixNeighbours.filter((n) => n === 4).length).toBe(36);
    expect(sixNeighbours.filter((n) => n === 2).length).toBe(6);
  });

  it('is symmetric: the neighbour in direction d has the cell as neighbour in the opposite direction d ^ 1', () => {
    for (let c = 0; c < 121; c++) {
      for (let d = 0; d < 6; d++) {
        const n = t[c * 7 + 1 + d]!;
        if (n !== 121) expect(t[n * 7 + 1 + (d ^ 1)]).toBe(c);
      }
    }
  });

  it('puts cell 0 at the tip (4, -8): its only neighbours are directions 1 and 5 (-1,0) and (-1,+1) or their opposites', () => {
    const real = Array.from({ length: 6 }, (_, d) => t[1 + d]!).filter((n) => n !== 121);
    expect(real.length).toBe(2);
  });
});

function file(entries: Array<{ name: string; dtype: string; shape: number[]; bytes: Uint8Array }>): Uint8Array {
  const header: Record<string, unknown> = { __metadata__: { format: 'test' } };
  let off = 0;
  for (const e of entries) {
    header[e.name] = { dtype: e.dtype, shape: e.shape, data_offsets: [off, off + e.bytes.length] };
    off += e.bytes.length;
  }
  const h = new TextEncoder().encode(JSON.stringify(header));
  const out = new Uint8Array(8 + h.length + off);
  new DataView(out.buffer).setBigUint64(0, BigInt(h.length), true);
  out.set(h, 8);
  let p = 8 + h.length;
  for (const e of entries) { out.set(e.bytes, p); p += e.bytes.length; }
  return out;
}

describe('parseSafetensors', () => {
  it('reads F32, F16 and BF16 tensors and the metadata', () => {
    const f32 = new Uint8Array(new Float32Array([1.5, -2.25, 0]).buffer);
    const f16 = new Uint8Array(new Uint16Array([0x3c00, 0xc000, 0x3555]).buffer); // 1, -2, 0.333251953125
    const bf16 = new Uint8Array(new Uint16Array([0x3f80, 0xc000, 0x3eaa]).buffer); // 1, -2, 0.33203125
    const r = parseSafetensors(file([
      { name: 'a', dtype: 'F32', shape: [3], bytes: f32 },
      { name: 'b', dtype: 'F16', shape: [3], bytes: f16 },
      { name: 'c', dtype: 'BF16', shape: [1, 3], bytes: bf16 },
    ]));
    expect(r.metadata['format']).toBe('test');
    expect([...r.tensors.get('a')!.data]).toEqual([1.5, -2.25, 0]);
    expect([...r.tensors.get('b')!.data]).toEqual([1, -2, 0.333251953125]);
    expect([...r.tensors.get('c')!.data]).toEqual([1, -2, 0.33203125]);
    expect(r.tensors.get('c')!.shape).toEqual([1, 3]);
  });

  it('rejects a truncated file and an unsupported dtype instead of returning partial data', () => {
    const ok = file([{ name: 'a', dtype: 'F32', shape: [3], bytes: new Uint8Array(12) }]);
    expect(() => parseSafetensors(ok.subarray(0, ok.length - 4))).toThrow(/outside the file/);
    expect(() => parseSafetensors(file([{ name: 'a', dtype: 'I64', shape: [1], bytes: new Uint8Array(8) }]))).toThrow(/unsupported dtype/);
    expect(() => parseSafetensors(new Uint8Array(4))).toThrow(/shorter than 8/);
  });
});
