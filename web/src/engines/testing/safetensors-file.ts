// Test helper: builds safetensors files in memory.

export interface TestTensor {
  name: string;
  dtype: string;
  shape: number[];
  bytes: Uint8Array;
}

/** Builds a safetensors file: 8-byte little-endian header length, JSON header, tensor bytes in the given order. */
export function safetensorsFile(entries: TestTensor[], metadata: Record<string, string> = { format: 'test' }): Uint8Array {
  const header: Record<string, unknown> = { __metadata__: metadata };
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
  for (const e of entries) {
    out.set(e.bytes, p);
    p += e.bytes.length;
  }
  return out;
}

/** The file of a net with the given sizes and all-zero F32 weights (a net that outputs the prior only). */
export function zeroNetFile(c: number, planes: number, blocks: number, hidden: number, players: number): Uint8Array {
  const t = (name: string, shape: number[]): TestTensor => ({
    name, dtype: 'F32', shape, bytes: new Uint8Array(shape.reduce((a, b) => a * b, 1) * 4),
  });
  const list = [t('conv_in.weight', [c, 7 * planes]), t('conv_in.bias', [c])];
  for (let b = 0; b < blocks; b++) {
    list.push(t(`block${b}.conv1.weight`, [c, 7 * c]), t(`block${b}.conv1.bias`, [c]));
    list.push(t(`block${b}.conv2.weight`, [c, 7 * c]), t(`block${b}.conv2.bias`, [c]));
  }
  list.push(t('head_policy.weight', [2, c]), t('head_policy.bias', [2]));
  list.push(t('head_value.dense1.weight', [hidden, c]), t('head_value.dense1.bias', [hidden]));
  list.push(t('head_value.dense2.weight', [players, hidden]), t('head_value.dense2.bias', [players]));
  return safetensorsFile(list);
}
