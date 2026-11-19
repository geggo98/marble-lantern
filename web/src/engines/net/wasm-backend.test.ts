// Tests of the WebAssembly net backend (wasm-backend.ts, contract IC1).
//
// Needs the built modules (scripts/web/build-wasm.zsh writes web/public/engines/net/, or set STERNHALMA_NET_WASM_DIR) and, for the
// golden tests, the golden directory of scripts/web/net-wasm-golden.zsh (STERNHALMA_GOLDEN_DIR, default <repo>/cpp/golden). The real-net
// tests also need real-ref-v2/-v3 in that directory. What is missing is skipped, and the suite says so in its test names.
import { existsSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { describe, expect, it } from 'vitest';
import { parseSafetensors } from './safetensors.js';
import {
  CELLS,
  FLAVOUR_ORDER,
  NetBackendError,
  RELAXED_PROBE,
  ROWS,
  SIMD_PROBE,
  WasmNetBackend,
  bestFlavour,
  flavourUrl,
  supportsFlavour,
} from './wasm-backend.js';
import type { Flavour, NetModuleFactory } from './wasm-backend.js';

const repo = resolve(import.meta.dirname, '../../../..');
const wasmDir = process.env['STERNHALMA_NET_WASM_DIR'] ?? join(repo, 'web/public/engines/net');
const goldenDir = process.env['STERNHALMA_GOLDEN_DIR'] ?? join(repo, 'cpp/golden');
const haveModules = FLAVOUR_ORDER.every((f) => existsSync(join(wasmDir, `engine-net-nn-${f}.wasm`)));
const golden = (name: string): string => join(goldenDir, name);
const haveGolden = (...names: string[]): boolean => names.every((n) => existsSync(golden(n)));
const read = (path: string): Uint8Array => new Uint8Array(readFileSync(path));

/** Factory of a flavour from the files on disk (Node: the glue file finds its .wasm next to itself). */
const diskFactory = (flavour: Flavour): Promise<NetModuleFactory> =>
  import(/* @vite-ignore */ pathToFileURL(join(wasmDir, `engine-net-nn-${flavour}.js`)).href).then(
    (m: { default: NetModuleFactory }) => m.default,
  );

async function backend(flavour: Flavour, maxBatch?: number): Promise<WasmNetBackend> {
  return WasmNetBackend.create({ flavours: [flavour], factory: diskFactory, ...(maxBatch === undefined ? {} : { maxBatch }) });
}

/** |fast - ref| <= 1e-4 + 1e-3 |ref| (docs/network.md); returns the worst fraction of the bound. */
function worstFraction(fast: ArrayLike<number>, ref: ArrayLike<number>): number {
  let worst = 0;
  for (let i = 0; i < ref.length; i++) {
    const r = ref[i]!;
    worst = Math.max(worst, Math.abs(fast[i]! - r) / (1e-4 + 1e-3 * Math.abs(r)));
  }
  return worst;
}

interface GoldenCase {
  name: string;
  weights: string;
  inputs: { file: string; tensor: string };
  ref: string;
  planes: number;
  width: number;
  /** Positions to check (all if omitted). */
  limit?: number;
}

const CASES: GoldenCase[] = [
  { name: 'plain 7 planes (SMALL, random weights)', weights: 'nn-weights-small-plain.safetensors', inputs: { file: 'nn-ref-small-plain.safetensors', tensor: 'inputs' }, ref: 'nn-ref-small-plain.safetensors', planes: 7, width: 2 },
  { name: 'classic1 13 planes (SMALL, random weights)', weights: 'nn-weights-small-u5.safetensors', inputs: { file: 'nn-inputs.safetensors', tensor: 'inputs' }, ref: 'nn-ref-small-u5.safetensors', planes: 13, width: 2 },
  { name: 'classic1v 15 planes (SMALL, random weights)', weights: 'nn-weights-small-v3.safetensors', inputs: { file: 'nn-inputs-v3.safetensors', tensor: 'inputs' }, ref: 'nn-ref-small-v3.safetensors', planes: 15, width: 3 },
];

/** Real nets are in the data directory of the main checkout, not in the repository: STERNHALMA_DATA_DIR (default <repo>/data). */
const dataDir = process.env['STERNHALMA_DATA_DIR'] ?? join(repo, 'data');
const REAL: Array<GoldenCase & { net: string }> = [
  { name: 'real net loop3 gen-0013 (classic1, 2 players)', net: join(dataDir, 'loop3/gen-0013/candidate.safetensors'), weights: '', inputs: { file: 'nn-inputs.safetensors', tensor: 'inputs' }, ref: 'real-ref-v2.safetensors', planes: 13, width: 2, limit: 1024 },
  { name: 'real net gen0-shared (classic1v, 3 value outputs)', net: join(dataDir, 'shared/gen0-shared/gen-0000/candidate.safetensors'), weights: '', inputs: { file: 'nn-inputs-v3.safetensors', tensor: 'inputs' }, ref: 'real-ref-v3.safetensors', planes: 15, width: 3 },
];

async function checkAgainstReference(flavour: Flavour, c: GoldenCase, weights: Uint8Array): Promise<number> {
  const inputs = parseSafetensors(read(golden(c.inputs.file))).tensors.get(c.inputs.tensor)!;
  const ref = parseSafetensors(read(golden(c.ref))).tensors;
  const logits = ref.get('logits')!.data;
  const values = ref.get('values')!.data;
  const total = Math.min(c.limit ?? Infinity, logits.length / 242);
  const size = ROWS * c.planes;
  expect(inputs.data.length).toBeGreaterThanOrEqual(total * size);
  const net = await backend(flavour);
  try {
    await net.load(weights);
    expect(net.planes).toBe(c.planes);
    expect(net.valueWidth).toBe(c.width);
    let worst = 0;
    let checked = 0;
    for (let start = 0; start < total; start += net.maxBatch) {
      const n = Math.min(net.maxBatch, total - start);
      const out = await net.evaluate(inputs.data.subarray(start * size, (start + n) * size), n);
      expect(out.policyFrom.length).toBe(n * CELLS);
      expect(out.policyTo.length).toBe(n * CELLS);
      expect(out.values.length).toBe(n * c.width);
      // The reference has the interleaved layout cell * 2 + channel.
      const from = new Float32Array(n * CELLS);
      const to = new Float32Array(n * CELLS);
      for (let i = 0; i < n * CELLS; i++) {
        from[i] = logits[start * 242 + Math.floor(i / CELLS) * 242 + 2 * (i % CELLS)]!;
        to[i] = logits[start * 242 + Math.floor(i / CELLS) * 242 + 2 * (i % CELLS) + 1]!;
      }
      worst = Math.max(worst, worstFraction(out.policyFrom, from), worstFraction(out.policyTo, to), worstFraction(out.values, values.subarray(start * c.width, (start + n) * c.width)));
      checked += n;
    }
    expect(checked).toBe(total);
    return worst;
  } finally {
    net.dispose();
  }
}

describe('probes', () => {
  it('the SIMD probe modules are valid in this runtime exactly when the flavours are supported', () => {
    expect(supportsFlavour('scalar')).toBe(true);
    expect(supportsFlavour('simd')).toBe(WebAssembly.validate(SIMD_PROBE));
    expect(supportsFlavour('relaxed')).toBe(WebAssembly.validate(RELAXED_PROBE));
  });

  it('rejects a probe with an unknown opcode, so that validate is not vacuously true', () => {
    const broken = RELAXED_PROBE.slice();
    broken[broken.length - 3] = 0xff; // the opcode byte of relaxed_madd
    expect(WebAssembly.validate(broken)).toBe(false);
  });

  it('bestFlavour is the first supported one of the order', () => {
    expect(bestFlavour()).toBe(FLAVOUR_ORDER.find(supportsFlavour));
  });

  it('flavourUrl joins the site root and the asset directory', () => {
    expect(flavourUrl('/base/', 'simd')).toBe('/base/engines/net/engine-net-nn-simd.js');
  });
});

describe('loader fallback order', () => {
  const stub: NetModuleFactory = () => Promise.resolve({ _net_free: () => undefined } as never);
  const trace = (log: Flavour[], failing: Flavour[] = []) => (f: Flavour): Promise<NetModuleFactory> => {
    log.push(f);
    return failing.includes(f) ? Promise.reject(new Error(`cannot load ${f}`)) : Promise.resolve(stub);
  };

  it('takes the first flavour that is supported: relaxed, then simd, then scalar', async () => {
    for (const [supported, want] of [[['relaxed', 'simd', 'scalar'], 'relaxed'], [['simd', 'scalar'], 'simd'], [['scalar'], 'scalar']] as Array<[Flavour[], Flavour]>) {
      const log: Flavour[] = [];
      const b = await WasmNetBackend.create({ supports: (f) => supported.includes(f), factory: trace(log) });
      expect(b.flavour).toBe(want);
      expect(log).toEqual([want]);
    }
  });

  it('falls through to the next flavour when a module cannot be loaded', async () => {
    const log: Flavour[] = [];
    const b = await WasmNetBackend.create({ supports: () => true, factory: trace(log, ['relaxed', 'simd']) });
    expect(b.flavour).toBe('scalar');
    expect(log).toEqual(['relaxed', 'simd', 'scalar']);
  });

  it('a forced flavour list is honoured in its own order', async () => {
    const log: Flavour[] = [];
    const b = await WasmNetBackend.create({ supports: () => true, factory: trace(log), flavours: ['scalar', 'relaxed'] });
    expect(b.flavour).toBe('scalar');
    expect(log).toEqual(['scalar']);
  });

  it('throws unavailable, naming every flavour, when none works', async () => {
    const log: Flavour[] = [];
    const error = await WasmNetBackend.create({ supports: (f) => f !== 'simd', factory: trace(log, ['relaxed', 'scalar']) }).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(NetBackendError);
    expect((error as NetBackendError).code).toBe('unavailable');
    expect((error as Error).message).toMatch(/relaxed: cannot load relaxed; simd: not supported by this runtime; scalar: cannot load scalar/);
    expect(log).toEqual(['relaxed', 'scalar']);
  });

  it('rejects an invalid maxBatch before loading anything', async () => {
    for (const maxBatch of [0, -1, 4097, 1.5]) {
      await expect(WasmNetBackend.create({ maxBatch, factory: trace([]) })).rejects.toMatchObject({ code: 'argument' });
    }
  });
});

describe.skipIf(!haveModules)('modules on disk', () => {
  it.each(FLAVOUR_ORDER.filter(supportsFlavour))('the %s module reports its own flavour', async (flavour) => {
    const b = await backend(flavour);
    const m = (b as unknown as { m: { _net_flavour(): number } }).m;
    expect(m._net_flavour()).toBe({ scalar: 0, simd: 1, relaxed: 2 }[flavour]);
    b.dispose();
  });

  it('the default loader picks the best flavour of this runtime', async () => {
    const b = await WasmNetBackend.create({ factory: diskFactory });
    expect(b.flavour).toBe(bestFlavour());
    b.dispose();
  });
});

describe.skipIf(!haveModules)('error codes', () => {
  const netFile = golden('nn-weights-small-u5.safetensors');
  const run = haveGolden('nn-weights-small-u5.safetensors') ? it : it.skip;
  const replaceText = (bytes: Uint8Array, from: string, to: string): Uint8Array => {
    expect(to.length).toBe(from.length);
    const text = Buffer.from(bytes).toString('latin1');
    const at = text.indexOf(from);
    expect(at).toBeGreaterThan(0);
    const out = bytes.slice();
    out.set(Buffer.from(to, 'latin1'), at);
    return out;
  };

  run('a truncated file is "file", also for a file cut inside the header and for an empty file', async () => {
    const bytes = read(netFile);
    for (const cut of [bytes.length - 1, bytes.length / 2, 100, 7, 0]) {
      const b = await backend('scalar');
      await expect(b.load(bytes.slice(0, cut))).rejects.toMatchObject({ code: 'file' });
      b.dispose();
    }
  });

  run('a wrong format name (bad magic) is "format"', async () => {
    const b = await backend('scalar');
    await expect(b.load(replaceText(read(netFile), 'sternhalma-nn', 'sternhalma-xx'))).rejects.toMatchObject({ code: 'format' });
    b.dispose();
  });

  run('a wrong plane count in the metadata is "config" and leaves the backend without a net', async () => {
    const b = await backend('scalar');
    await expect(b.load(replaceText(read(netFile), '"input_planes":"13"', '"input_planes":"14"'))).rejects.toMatchObject({ code: 'config' });
    await expect(b.evaluate(new Float32Array(ROWS * 13), 1)).rejects.toMatchObject({ code: 'no-net' });
    b.dispose();
  });

  run('an unknown plane set is "config"', async () => {
    const b = await backend('scalar');
    await expect(b.load(replaceText(read(netFile), '"planes":"classic1"', '"planes":"classic9"'))).rejects.toMatchObject({ code: 'config' });
    b.dispose();
  });

  run('input of the wrong size is "bad-input", and a bad position (dirty padding row) is "forward"', async () => {
    const b = await backend('scalar', 4);
    await b.load(read(netFile));
    await expect(b.evaluate(new Float32Array(ROWS * 14), 1)).rejects.toMatchObject({ code: 'bad-input' });
    await expect(b.evaluate(new Float32Array(ROWS * 13 * 2 - 1), 2)).rejects.toMatchObject({ code: 'bad-input' });
    const dirty = new Float32Array(ROWS * 13);
    dirty[(ROWS - 1) * 13] = 1; // the padding row must be zero
    await expect(b.evaluate(dirty, 1)).rejects.toMatchObject({ code: 'forward' });
    b.dispose();
  });

  run('batch above maxBatch and batch below 1 are rejected, the largest allowed batch works', async () => {
    const b = await backend('scalar', 4);
    await b.load(read(netFile));
    const size = ROWS * 13;
    await expect(b.evaluate(new Float32Array(5 * size), 5)).rejects.toMatchObject({ code: 'batch' });
    await expect(b.evaluate(new Float32Array(0), 0)).rejects.toMatchObject({ code: 'batch' });
    await expect(b.evaluate(new Float32Array(size), 1.5)).rejects.toMatchObject({ code: 'batch' });
    const ok = await b.evaluate(new Float32Array(4 * size), 4);
    expect(ok.values.length).toBe(4 * 2);
    b.dispose();
  });

  it('evaluate before load, and any call after dispose, fail with their own codes', async () => {
    const b = await backend('scalar');
    await expect(b.evaluate(new Float32Array(10), 1)).rejects.toMatchObject({ code: 'no-net' });
    b.dispose();
    await expect(b.load(new Uint8Array(0))).rejects.toMatchObject({ code: 'disposed' });
    await expect(b.evaluate(new Float32Array(10), 1)).rejects.toMatchObject({ code: 'disposed' });
    expect(() => b.dispose()).not.toThrow();
  });

  it('a null-length buffer is "file", not a crash', async () => {
    const b = await backend('scalar');
    await expect(b.load(new Uint8Array(0))).rejects.toMatchObject({ code: 'file' });
    b.dispose();
  });
});

describe.skipIf(!haveModules)('golden positions against the Java reference, |fast - ref| <= 1e-4 + 1e-3 |ref|', () => {
  for (const flavour of FLAVOUR_ORDER) {
    const supported = supportsFlavour(flavour);
    for (const c of CASES) {
      const ok = supported && haveGolden(c.weights, c.inputs.file, c.ref);
      it.skipIf(!ok)(`${flavour}: ${c.name}`, async () => {
        const worst = await checkAgainstReference(flavour, c, read(golden(c.weights)));
        expect(worst).toBeLessThanOrEqual(1);
      });
    }
    for (const c of REAL) {
      const ok = supported && existsSync(c.net) && haveGolden(c.inputs.file, c.ref);
      it.skipIf(!ok)(`${flavour}: ${c.name}`, async () => {
        const worst = await checkAgainstReference(flavour, c, read(c.net));
        expect(worst).toBeLessThanOrEqual(1);
      });
    }
  }

  it.skipIf(!haveGolden('nn-weights-small-u5.safetensors', 'nn-inputs.safetensors', 'nn-ref-small-u5.safetensors'))(
    'the results do not depend on the batch size (1, 3 and 16 give the same numbers, also after a smaller batch)',
    async () => {
      const inputs = parseSafetensors(read(golden('nn-inputs.safetensors'))).tensors.get('inputs')!.data;
      const size = ROWS * 13;
      const net = await backend('scalar', 16);
      await net.load(read(golden('nn-weights-small-u5.safetensors')));
      const all = await net.evaluate(inputs.subarray(0, 16 * size), 16);
      const one = await net.evaluate(inputs.subarray(5 * size, 6 * size), 1);
      const three = await net.evaluate(inputs.subarray(0, 3 * size), 3);
      expect([...one.policyFrom]).toEqual([...all.policyFrom.subarray(5 * CELLS, 6 * CELLS)]);
      expect([...three.policyTo]).toEqual([...all.policyTo.subarray(0, 3 * CELLS)]);
      expect([...three.values]).toEqual([...all.values.subarray(0, 6)]);
      // the returned arrays are copies: the next call must not change them
      await net.evaluate(inputs.subarray(8 * size, 9 * size), 1);
      expect([...one.policyFrom]).toEqual([...all.policyFrom.subarray(5 * CELLS, 6 * CELLS)]);
      net.dispose();
    },
  );

  it.skipIf(!haveGolden('nn-weights-small-u5.safetensors', 'nn-inputs.safetensors', 'nn-weights-small-v3.safetensors', 'nn-inputs-v3.safetensors'))(
    'a second load replaces the net (13 planes, then 15 planes)',
    async () => {
      const net = await backend('scalar');
      await net.load(read(golden('nn-weights-small-u5.safetensors')));
      expect([net.planes, net.valueWidth]).toEqual([13, 2]);
      await net.load(read(golden('nn-weights-small-v3.safetensors')));
      expect([net.planes, net.valueWidth]).toEqual([15, 3]);
      const inputs = parseSafetensors(read(golden('nn-inputs-v3.safetensors'))).tensors.get('inputs')!.data;
      const out = await net.evaluate(inputs.subarray(0, ROWS * 15), 1);
      expect(out.values.length).toBe(3);
      await expect(net.evaluate(new Float32Array(ROWS * 13), 1)).rejects.toMatchObject({ code: 'bad-input' });
      net.dispose();
    },
  );
});
