// Tests of the safetensors reader. Expected values are written out by hand from the formats (IEEE half, bfloat16).
import { describe, expect, it } from 'vitest';
import { safetensorsFile } from '../testing/safetensors-file';
import { parseSafetensors } from './safetensors';

const u16 = (...v: number[]): Uint8Array => new Uint8Array(new Uint16Array(v).buffer);

describe('parseSafetensors', () => {
  it('reads F32, F16 and BF16 tensors and the metadata', () => {
    const f32 = new Uint8Array(new Float32Array([1.5, -2.25, 0]).buffer);
    const f16 = u16(0x3c00, 0xc000, 0x3555); // 1, -2, 0.333251953125
    const bf16 = u16(0x3f80, 0xc000, 0x3eaa); // 1, -2, 0.33203125
    const r = parseSafetensors(safetensorsFile([
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

  it('widens the special F16 values: subnormal, signed zero, infinity, NaN, the largest finite value', () => {
    const r = parseSafetensors(safetensorsFile([
      // 0x0001 = 2^-24, 0x8000 = -0, 0x7c00 = +inf, 0xfc00 = -inf, 0x7e00 = NaN, 0x7bff = 65504, 0x0400 = 2^-14
      { name: 'h', dtype: 'F16', shape: [7], bytes: u16(0x0001, 0x8000, 0x7c00, 0xfc00, 0x7e00, 0x7bff, 0x0400) },
    ]));
    const d = r.tensors.get('h')!.data;
    expect(d[0]).toBe(2 ** -24);
    expect(Object.is(d[1], -0)).toBe(true);
    expect(d[2]).toBe(Infinity);
    expect(d[3]).toBe(-Infinity);
    expect(d[4]).toBeNaN();
    expect(d[5]).toBe(65504);
    expect(d[6]).toBe(2 ** -14);
  });

  it('honours the byte offset of a view into a larger buffer', () => {
    const file = safetensorsFile([{ name: 'a', dtype: 'F32', shape: [2], bytes: new Uint8Array(new Float32Array([7, 8]).buffer) }]);
    const big = new Uint8Array(file.length + 5);
    big.set(file, 5);
    expect([...parseSafetensors(big.subarray(5)).tensors.get('a')!.data]).toEqual([7, 8]);
  });

  it('rejects a truncated file instead of returning partial data', () => {
    const ok = safetensorsFile([{ name: 'a', dtype: 'F32', shape: [3], bytes: new Uint8Array(12) }]);
    expect(() => parseSafetensors(ok.subarray(0, ok.length - 4))).toThrow(/outside the file/);
  });

  it('rejects an unsupported dtype, a file shorter than 8 bytes and a header longer than the file', () => {
    expect(() => parseSafetensors(safetensorsFile([{ name: 'a', dtype: 'I64', shape: [1], bytes: new Uint8Array(8) }]))).toThrow(/unsupported dtype/);
    expect(() => parseSafetensors(new Uint8Array(4))).toThrow(/shorter than 8/);
    const lie = new Uint8Array(16);
    new DataView(lie.buffer).setBigUint64(0, 100n, true);
    expect(() => parseSafetensors(lie)).toThrow(/header length 100 exceeds/);
  });

  it('rejects a tensor whose byte size does not match its shape', () => {
    const bad = safetensorsFile([{ name: 'a', dtype: 'F32', shape: [3], bytes: new Uint8Array(8) }]);
    expect(() => parseSafetensors(bad)).toThrow(/has 8 bytes, expected 12/);
  });

  it('rejects offsets that run backwards', () => {
    const h = new TextEncoder().encode(JSON.stringify({ a: { dtype: 'F32', shape: [0], data_offsets: [4, 0] } }));
    const f = new Uint8Array(8 + h.length + 4);
    new DataView(f.buffer).setBigUint64(0, BigInt(h.length), true);
    f.set(h, 8);
    expect(() => parseSafetensors(f)).toThrow(/outside the file/);
  });
});
