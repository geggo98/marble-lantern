import { createHash, webcrypto } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { gzipSync } from 'node:zlib';
import { describe, expect, it } from 'vitest';
import type { Variant } from '../../game/variant';
import {
  halfToFloat, listModels, loadModel, modelsFor, parseModelManifest, widenSafetensors,
} from './models';
import type { ModelErrorCode, ModelInfo } from './models';

const sha = (b: Uint8Array | string): string => createHash('sha256').update(b).digest('hex');
const subtle = webcrypto.subtle as SubtleCrypto;
const modelsDir = new URL('../../../models/', import.meta.url);
const realManifestText = readFileSync(new URL('models.json', modelsDir), 'utf8');

async function codeOf(p: Promise<unknown> | (() => unknown)): Promise<ModelErrorCode | 'no error'> {
  try {
    await (typeof p === 'function' ? p() : p);
    return 'no error';
  } catch (e) {
    return (e as { code?: ModelErrorCode }).code ?? 'no error';
  }
}

// ---- manifest ----

describe('parseModelManifest', () => {
  const real = JSON.parse(realManifestText) as { v: number; models: Array<Record<string, unknown>> };
  const clone = () => JSON.parse(realManifestText) as typeof real;

  it('reads the real manifest', () => {
    const models = parseModelManifest(realManifestText);
    expect(models.map((m) => m.id)).toEqual(['small-2p-adjacent-expert', 'small-shared-all']);
    expect(models[0]).toMatchObject({ planes: 13, planeSet: 'classic1', formatVersion: 2, players: 2, params: 61734 });
    expect(models[1]).toMatchObject({ planes: 15, planeSet: 'classic1v', formatVersion: 3, players: 3 });
  });

  it('puts lengths and hashes before the descriptive fields', () => {
    const keys = Object.keys(real.models[0] as object);
    expect(keys.slice(0, 5)).toEqual(['id', 'rawBytesF16', 'packedBytes', 'sha256Raw', 'sha256Packed']);
  });

  it('a cut-short manifest is manifest-truncated', async () => {
    expect(await codeOf(() => parseModelManifest(realManifestText.slice(0, realManifestText.length >> 1)))).toBe('manifest-truncated');
    expect(await codeOf(() => parseModelManifest(''))).toBe('manifest-truncated');
  });

  const cases: Array<[string, (m: ReturnType<typeof clone>) => void]> = [
    ['wrong version', (m) => { m.v = 2; }],
    ['extra top-level key', (m) => { (m as Record<string, unknown>).extra = 1; }],
    ['models is not an array', (m) => { (m as Record<string, unknown>).models = {}; }],
    ['entry is not an object', (m) => { m.models[0] = 'x' as unknown as Record<string, unknown>; }],
    ['missing key', (m) => { delete (m.models[0] as Record<string, unknown>).sha256Raw; }],
    ['unknown key', (m) => { (m.models[0] as Record<string, unknown>).surprise = 1; }],
    ['bad id', (m) => { m.models[0]!.id = 'Small_2p'; }],
    ['file does not match id', (m) => { m.models[0]!.file = 'other.safetensors.gz'; }],
    ['short sha', (m) => { m.models[0]!.sha256Raw = 'abc'; }],
    ['upper-case sha', (m) => { m.models[0]!.sha256Packed = String(m.models[0]!.sha256Packed).toUpperCase(); }],
    ['unknown plane set', (m) => { m.models[0]!.planeSet = 'classic9'; }],
    ['planes do not fit the plane set', (m) => { m.models[0]!.planes = 15; }],
    ['negative size', (m) => { m.models[0]!.packedBytes = -1; }],
    ['fractional size', (m) => { m.models[0]!.rawBytesF16 = 1.5; }],
    ['value width 4', (m) => { m.models[0]!.players = 4; }],
    ['no variants', (m) => { m.models[0]!.variants = []; }],
    ['unknown jump rule', (m) => { m.models[0]!.variants = [{ jump: 'far', players: 2 }]; }],
    ['variant with 4 players', (m) => { m.models[1]!.variants = [{ jump: 'long', players: 4 }]; }],
    ['variant with extra key', (m) => { m.models[1]!.variants = [{ jump: 'long', players: 2, x: 1 }]; }],
    ['duplicate variant', (m) => { m.models[1]!.variants = [{ jump: 'long', players: 2 }, { jump: 'long', players: 2 }]; }],
    ['3p variant on a 2-output model', (m) => { m.models[0]!.variants = [{ jump: 'adjacent', players: 3 }]; }],
    ['duplicate id', (m) => { m.models[1] = { ...m.models[0]! }; }],
    ['strength is not an object', (m) => { m.models[0]!.strength = 5; }],
    ['empty label key', (m) => { m.models[0]!.labelKey = ''; }],
  ];
  for (const [name, mutate] of cases) {
    it(`rejects: ${name}`, async () => {
      const m = clone();
      mutate(m);
      expect(await codeOf(() => parseModelManifest(JSON.stringify(m)))).toBe('manifest-invalid');
    });
  }
});

describe('modelsFor', () => {
  const v = (jump: Variant['jump'], players: Variant['players']): Variant => ({ jump, players });
  const ids = (variant: Variant) => modelsFor(variant).map((m) => m.id);

  it('the classic1 expert plays only adjacent jumps with two players', () => {
    expect(ids(v('adjacent', 2))).toContain('small-2p-adjacent-expert');
    for (const other of [v('long', 2), v('adjacent', 3), v('long', 3)]) {
      expect(ids(other)).not.toContain('small-2p-adjacent-expert');
    }
  });

  it('the shared classic1v net lists all four variants', () => {
    for (const variant of [v('adjacent', 2), v('long', 2), v('adjacent', 3), v('long', 3)]) {
      expect(ids(variant)).toContain('small-shared-all');
    }
    expect(listModels().find((m) => m.id === 'small-shared-all')?.variants).toHaveLength(4);
  });

  it('the variants list is authoritative, not the plane set', () => {
    const models = parseModelManifest({
      v: 1,
      models: [{ ...(JSON.parse(realManifestText).models[1] as object), variants: [{ jump: 'long', players: 3 }] }],
    });
    expect(modelsFor(v('long', 3), models)).toHaveLength(1);
    expect(modelsFor(v('adjacent', 2), models)).toHaveLength(0);
  });
});

// ---- widening ----

describe('halfToFloat', () => {
  // Reference values from IEEE 754-2008 binary16 (Table of example values), not from the code.
  it.each([
    [0x0000, 0], [0x3c00, 1], [0xbc00, -1], [0xc000, -2], [0x3555, 0.333251953125], [0x7bff, 65504],
    [0x0400, 2 ** -14], [0x0001, 2 ** -24], [0x03ff, 1023 * 2 ** -24], [0x7c00, Infinity], [0xfc00, -Infinity],
    [0x3800, 0.5], [0xb800, -0.5],
  ])('0x%s', (bits, value) => {
    expect(halfToFloat(bits)).toBe(value);
  });
  it('negative zero and NaN', () => {
    expect(Object.is(halfToFloat(0x8000), -0)).toBe(true);
    expect(halfToFloat(0x7e00)).toBeNaN();
  });
  it('every half agrees with the arithmetic definition', () => {
    for (let h = 0; h < 0x10000; h++) {
      const exp = (h >> 10) & 31;
      const frac = h & 1023;
      const mag = exp === 0 ? frac * 2 ** -24 : exp === 31 ? (frac ? NaN : Infinity) : (1 + frac / 1024) * 2 ** (exp - 15);
      const want = h & 0x8000 ? -mag : mag;
      const got = halfToFloat(h);
      if (Number.isNaN(want)) expect(got).toBeNaN();
      else expect(Object.is(got, want)).toBe(true);
    }
  });
});

// ---- loading, synthetic net ----

// Two tensors, values given as half bit patterns: 1.0, -2.0, 0.5, 65504, smallest subnormal, 0.333251953125.
const bitsA = [0x3c00, 0xc000, 0x3800];
const bitsB = [0x7bff, 0x0001, 0x3555, 0x0000];
const expectedValues = { a: [1, -2, 0.5], b: [65504, 2 ** -24, 0.333251953125, 0] };

function f16File(meta: Record<string, string>): Uint8Array {
  const header = JSON.stringify({
    __metadata__: meta,
    a: { dtype: 'F16', shape: [3], data_offsets: [0, 6] },
    b: { dtype: 'F16', shape: [2, 2], data_offsets: [6, 14] },
  });
  const head = Buffer.from(header + ' '.repeat((8 - (header.length % 8)) % 8));
  const out = Buffer.alloc(8 + head.length + 14);
  out.writeBigUInt64LE(BigInt(head.length), 0);
  head.copy(out, 8);
  [...bitsA, ...bitsB].forEach((b, i) => out.writeUInt16LE(b, 8 + head.length + 2 * i));
  return new Uint8Array(out);
}

const goodMeta = {
  format: 'sternhalma-nn', format_version: '3', planes: 'classic1v', input_planes: '15', players: '3',
  weights_dtype: 'F16', source_sha256: 'f'.repeat(64),
};

function entryFor(raw: Uint8Array, gz: Uint8Array): ModelInfo {
  return {
    id: 'test-net', labelKey: 'l', descriptionKey: 'd', variants: [{ jump: 'adjacent', players: 2 }], planes: 15,
    planeSet: 'classic1v', formatVersion: 3, players: 3, params: 7, rawBytes: raw.length, packedBytes: gz.length,
    sha256Raw: sha(raw), sha256Packed: sha(gz), file: 'test-net.safetensors.gz', sourceSha256: 'f'.repeat(64), createdFrom: 'test',
  };
}

const rawGood = f16File(goodMeta);
const gzGood = new Uint8Array(gzipSync(rawGood, { level: 9 }));
const info = entryFor(rawGood, gzGood);
const serve = (body: Uint8Array | number): typeof fetch =>
  (async () => (typeof body === 'number' ? new Response('', { status: body }) : new Response(body as BodyInit))) as typeof fetch;
const load = (body: Uint8Array | number, i: ModelInfo = info, s: SubtleCrypto | null = subtle) =>
  loadModel(i, serve(body), { baseUrl: 'models/', subtle: s });

function floatsOf(file: Uint8Array): { meta: Record<string, string>; a: number[]; b: number[]; header: Record<string, { dtype: string; data_offsets: number[] }> } {
  const n = Number(new DataView(file.buffer, file.byteOffset).getBigUint64(0, true));
  const header = JSON.parse(Buffer.from(file.subarray(8, 8 + n)).toString('utf8'));
  const data = new Float32Array(file.slice(8 + n).buffer);
  const meta = header.__metadata__;
  delete header.__metadata__;
  return { meta, header, a: [...data.subarray(0, 3)], b: [...data.subarray(3, 7)] };
}

describe('loadModel (synthetic net)', () => {
  it('returns the F32 file with the values of the half bit patterns', async () => {
    const out = floatsOf(await load(gzGood));
    expect(out.a).toEqual(expectedValues.a);
    expect(out.b).toEqual(expectedValues.b);
    expect(out.header.a).toMatchObject({ dtype: 'F32', data_offsets: [0, 12] });
    expect(out.header.b).toMatchObject({ dtype: 'F32', data_offsets: [12, 28] });
    expect(out.meta.weights_dtype).toBeUndefined();
    expect(out.meta.source_sha256).toBeUndefined();
    expect(out.meta.planes).toBe('classic1v');
  });

  it('works without crypto.subtle (pure-JS sha256) and with a body the server already inflated', async () => {
    expect(floatsOf(await load(gzGood, info, null)).a).toEqual(expectedValues.a);
    expect(floatsOf(await load(rawGood)).b).toEqual(expectedValues.b);
  });

  it('a failed fetch is model-http', async () => {
    expect(await codeOf(load(404))).toBe('model-http');
  });

  it('a cut-short packed file is model-truncated, extra bytes are model-trailing', async () => {
    expect(await codeOf(load(gzGood.subarray(0, gzGood.length - 3)))).toBe('model-truncated');
    expect(await codeOf(load(new Uint8Array([...gzGood, 0])))).toBe('model-trailing');
  });

  it('a flipped byte in the packed file is model-checksum or model-corrupt, never accepted', async () => {
    const bad = gzGood.slice();
    bad[bad.length >> 1] = (bad[bad.length >> 1] ?? 0) ^ 0x55;
    expect(await codeOf(load(bad))).toBe('model-checksum');
    // Without crypto.subtle the pure-JS sha256 still checks the packed file (before F7 only gzip's CRC32 stopped it: model-corrupt).
    expect(await codeOf(load(bad, info, null))).toBe('model-checksum');
  });

  it('without crypto.subtle a wrong sha256 of the packed or the inflated file is model-checksum, a right one passes', async () => {
    expect(await codeOf(load(gzGood, { ...info, sha256Packed: '0'.repeat(64) }, null))).toBe('model-checksum');
    expect(await codeOf(load(gzGood, { ...info, sha256Raw: '0'.repeat(64) }, null))).toBe('model-checksum');
    // a body the server already inflated: only the raw check applies
    expect(await codeOf(load(rawGood, { ...info, sha256Raw: '0'.repeat(64) }, null))).toBe('model-checksum');
    expect(floatsOf(await load(gzGood, info, null)).a).toEqual(expectedValues.a);
  });

  it('wrong sha256Raw in the manifest is model-checksum', async () => {
    expect(await codeOf(load(gzGood, { ...info, sha256Raw: '0'.repeat(64) }))).toBe('model-checksum');
  });

  it('wrong rawBytes in the manifest is model-truncated or model-trailing', async () => {
    expect(await codeOf(load(gzGood, { ...info, rawBytes: info.rawBytes + 1 }))).toBe('model-truncated');
    expect(await codeOf(load(gzGood, { ...info, rawBytes: info.rawBytes - 1 }))).toBe('model-trailing');
  });

  it('a damaged header is model-header', async () => {
    const raw = rawGood.slice();
    raw[10] = 0x7b; // inside the JSON text
    raw[12] = 0x00;
    const gz = new Uint8Array(gzipSync(raw));
    expect(await codeOf(load(gz, entryFor(raw, gz)))).toBe('model-header');
  });

  const mismatches: Array<[string, Record<string, string>]> = [
    ['format version', { format_version: '2' }],
    ['plane count', { input_planes: '13' }],
    ['plane set', { planes: 'classic1' }],
    ['value width', { players: '2' }],
    ['dtype', { weights_dtype: 'F32' }],
    ['source hash', { source_sha256: 'e'.repeat(64) }],
    ['format', { format: 'other' }],
  ];
  for (const [name, over] of mismatches) {
    it(`header ${name} contradicting the manifest is model-mismatch`, async () => {
      const raw = f16File({ ...goodMeta, ...over });
      const gz = new Uint8Array(gzipSync(raw));
      expect(await codeOf(load(gz, entryFor(raw, gz)))).toBe('model-mismatch');
    });
  }

  it('a weight count that differs from the manifest is model-mismatch', async () => {
    expect(await codeOf(load(gzGood, { ...info, params: 8 }))).toBe('model-mismatch');
  });

  it('widenSafetensors rejects an F32 input', () => {
    const f32 = widenSafetensors(rawGood);
    expect(() => widenSafetensors(f32)).toThrow(/F16/);
  });
});

// ---- loading, the real files ----

// sha256 of the F32 file that scripts/models/pack-model.py widen writes for each packed model. Computed by the Python
// tool, an independent implementation of the same widening.
const pythonWidened: Record<string, string> = {
  'small-2p-adjacent-expert': '415d45f53c97e4b12d2431888734be5d0346efe584379e6a9edb2aeab940edf6',
  'small-shared-all': '1579ae068858ebe4b725c19cb43908e331b50f0564bc46fcbf25a378fc84d265',
};

describe('loadModel (real files)', () => {
  const fromDisk = (async (url: string) =>
    new Response(readFileSync(new URL(url.split('/').pop() as string, modelsDir)))) as typeof fetch;

  for (const m of listModels()) {
    it(`${m.id}: verifies and widens to the same bytes as the Python tool`, async () => {
      const out = await loadModel(m, fromDisk, { subtle });
      expect(sha(out)).toBe(pythonWidened[m.id]);
      expect(out.length).toBe(8 + Number(new DataView(out.buffer, out.byteOffset).getBigUint64(0, true)) + 4 * m.params);
    });

    it(`${m.id}: the packed file matches the manifest sizes and hash`, () => {
      const gz = readFileSync(new URL(m.file, modelsDir));
      expect(gz.length).toBe(m.packedBytes);
      expect(sha(gz)).toBe(m.sha256Packed);
    });
  }
});
