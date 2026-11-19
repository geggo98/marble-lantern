// Minimal safetensors reader: 8-byte little-endian header length, JSON header, raw tensor bytes.
// F32 is read as it is, F16 and BF16 are widened to F32 (the net runs in F32 either way).

export interface TensorInfo {
  name: string;
  dtype: string;
  shape: number[];
  data: Float32Array;
}

export interface Safetensors {
  metadata: Record<string, string>;
  tensors: Map<string, TensorInfo>;
}

function halfToFloat(h: number): number {
  const s = (h & 0x8000) !== 0 ? -1 : 1;
  const e = (h >> 10) & 0x1f;
  const f = h & 0x3ff;
  if (e === 0) return s * 2 ** -14 * (f / 1024);
  if (e === 0x1f) return f ? NaN : s * Infinity;
  return s * 2 ** (e - 15) * (1 + f / 1024);
}

export function parseSafetensors(bytes: Uint8Array): Safetensors {
  if (bytes.byteLength < 8) throw new Error('safetensors: file shorter than 8 bytes');
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const headerLength = Number(view.getBigUint64(0, true));
  if (!Number.isSafeInteger(headerLength) || 8 + headerLength > bytes.byteLength) {
    throw new Error(`safetensors: header length ${headerLength} exceeds the file (${bytes.byteLength} bytes)`);
  }
  const header = JSON.parse(new TextDecoder().decode(bytes.subarray(8, 8 + headerLength))) as Record<string, unknown>;
  const base = 8 + headerLength;
  const metadata = (header['__metadata__'] ?? {}) as Record<string, string>;
  const tensors = new Map<string, TensorInfo>();
  for (const [name, raw] of Object.entries(header)) {
    if (name === '__metadata__') continue;
    const t = raw as { dtype: string; shape: number[]; data_offsets: [number, number] };
    const [begin, end] = t.data_offsets;
    if (base + end > bytes.byteLength || begin > end) throw new Error(`safetensors: tensor ${name} lies outside the file`);
    const count = t.shape.reduce((a, b) => a * b, 1);
    const bpe = t.dtype === 'F32' ? 4 : t.dtype === 'F16' || t.dtype === 'BF16' ? 2 : 0;
    if (bpe === 0) throw new Error(`safetensors: tensor ${name} has the unsupported dtype ${t.dtype}`);
    if (end - begin !== count * bpe) throw new Error(`safetensors: tensor ${name} has ${end - begin} bytes, expected ${count * bpe}`);
    const data = new Float32Array(count);
    const dv = new DataView(bytes.buffer, bytes.byteOffset + base + begin, end - begin);
    const scratch = new Uint32Array(1);
    const asFloat = new Float32Array(scratch.buffer);
    for (let i = 0; i < count; i++) {
      if (t.dtype === 'F32') {
        data[i] = dv.getFloat32(i * 4, true);
      } else if (t.dtype === 'F16') {
        data[i] = halfToFloat(dv.getUint16(i * 2, true));
      } else {
        scratch[0] = dv.getUint16(i * 2, true) << 16;
        data[i] = asFloat[0]!;
      }
    }
    tensors.set(name, { name, dtype: t.dtype, shape: t.shape, data });
  }
  return { metadata, tensors };
}
