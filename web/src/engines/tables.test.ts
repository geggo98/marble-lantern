import { createHash, webcrypto } from 'node:crypto';
import { gzipSync } from 'node:zlib';
import { describe, expect, it } from 'vitest';
import { EngineFailure } from './protocol';
import { loadTable, parseManifest } from './tables';
import type { TableManifest } from './tables';

// A synthetic table: the magic "SHRT" and a recognisable body. Expected hashes come from node:crypto, not from tables.ts.
const body = new Uint8Array(5000);
body.set([0x53, 0x48, 0x52, 0x54]);
for (let i = 4; i < body.length; i++) body[i] = (i * 31) & 0xff;
const sha = (b: Uint8Array): string => createHash('sha256').update(b).digest('hex');
const gz = new Uint8Array(gzipSync(body, { level: 9 }));

const manifest = (over: Partial<TableManifest> = {}): TableManifest => ({
  v: 1, alg: 'gzip', rawBytes: body.length, sha256: sha(body), gzBytes: gz.length, file: 'rows2.bin.gz', ...over,
});

function fakeFetch(files: { manifest: string | number; table?: Uint8Array | number }): typeof fetch {
  return (async (url: string) => {
    if (url.endsWith('manifest.json')) {
      return typeof files.manifest === 'number'
        ? new Response('', { status: files.manifest })
        : new Response(files.manifest);
    }
    if (typeof files.table === 'number') return new Response('', { status: files.table });
    return new Response(files.table as BodyInit, { headers: { 'content-length': String((files.table as Uint8Array).length) } });
  }) as typeof fetch;
}

const load = (
  table: Uint8Array | number,
  m: string | number = JSON.stringify(manifest()),
  extra: { subtle?: SubtleCrypto | null; engineChecksum?: boolean } = {},
) =>
  loadTable({
    manifestUrl: 'tables/rows2.manifest.json',
    tableUrl: 'tables/rows2.bin.gz',
    engineChecksum: extra.engineChecksum ?? true,
    fetch: fakeFetch({ manifest: m, table }),
    subtle: extra.subtle === undefined ? (webcrypto.subtle as SubtleCrypto) : extra.subtle,
  });

const codeOf = async (p: Promise<unknown>): Promise<string> => {
  try {
    await p;
  } catch (e) {
    expect(e).toBeInstanceOf(EngineFailure);
    return (e as EngineFailure).code;
  }
  return 'no error';
};

describe('loadTable', () => {
  it('inflates a gzip body and checks the SHA-256 with crypto.subtle', async () => {
    const t = await load(gz);
    expect(new Uint8Array(t.bytes)).toEqual(body);
    expect(t.verifiedBy).toBe('subtle-sha256');
    expect(t.manifest.rawBytes).toBe(5000);
  });

  it('accepts a body the server already inflated (Content-Encoding: gzip on a .gz file)', async () => {
    const t = await load(body);
    expect(new Uint8Array(t.bytes)).toEqual(body);
    expect(t.verifiedBy).toBe('subtle-sha256');
  });

  it('rejects a wrong SHA-256 of the same length', async () => {
    const flipped = new Uint8Array(body);
    flipped[100] = flipped[100]! ^ 1;
    expect(await codeOf(load(flipped))).toBe('table-checksum');
    // the same flip inside a valid gzip stream: gzip is intact, only the manifest hash catches it
    expect(await codeOf(load(new Uint8Array(gzipSync(flipped)), JSON.stringify(manifest({ gzBytes: gzipSync(flipped).length }))))).toBe('table-checksum');
  });

  it('rejects a table that is too short (source truncated before compression)', async () => {
    const short = body.slice(0, 4000);
    expect(await codeOf(load(short))).toBe('table-truncated');
    const gzShort = new Uint8Array(gzipSync(short));
    expect(await codeOf(load(gzShort, JSON.stringify(manifest({ gzBytes: gzShort.length }))))).toBe('table-truncated');
  });

  it('rejects a table with bytes after the end', async () => {
    const long = new Uint8Array(body.length + 7);
    long.set(body);
    expect(await codeOf(load(long))).toBe('table-trailing');
    const one = new Uint8Array(body.length + 1); // boundary: one byte more
    one.set(body);
    expect(await codeOf(load(one))).toBe('table-trailing');
  });

  it('rejects a gzip download that is shorter than the manifest says', async () => {
    const p = load(gz.slice(0, gz.length - 20));
    expect(await codeOf(p)).toBe('table-truncated');
    await expect(load(gz.slice(0, gz.length - 20))).rejects.toThrow(new RegExp(`download has ${gz.length - 20} of ${gz.length} bytes`));
  });

  it('rejects a damaged gzip stream even when its length matches the manifest', async () => {
    const cut = gz.slice(0, gz.length - 20);
    expect(await codeOf(load(cut, JSON.stringify(manifest({ gzBytes: cut.length }))))).toBe('table-truncated');
  });

  it('rejects a table without the magic', async () => {
    const wrong = new Uint8Array(body);
    wrong[0] = 0x58;
    const m = JSON.stringify(manifest({ sha256: sha(wrong) }));
    expect(await codeOf(load(wrong, m))).toBe('table-magic');
  });

  it('reports "engine" without crypto.subtle when the engine checks the hash itself (C++)', async () => {
    const t = await load(gz, undefined, { subtle: null, engineChecksum: true });
    expect(t.verifiedBy).toBe('engine');
  });

  it('reports "length-only" without crypto.subtle when the engine cannot hash (TeaVM)', async () => {
    const t = await load(gz, undefined, { subtle: null, engineChecksum: false });
    expect(t.verifiedBy).toBe('length-only');
  });

  it('documents the limit of length-only: a same-length corruption passes', async () => {
    const flipped = new Uint8Array(body);
    flipped[100] = flipped[100]! ^ 1;
    const t = await load(flipped, undefined, { subtle: null, engineChecksum: false });
    expect(t.verifiedBy).toBe('length-only');
  });

  it('still catches length errors without crypto.subtle', async () => {
    expect(await codeOf(load(body.slice(0, 4999), undefined, { subtle: null }))).toBe('table-truncated');
  });

  it('maps HTTP failures to "internal"', async () => {
    expect(await codeOf(load(gz, 404))).toBe('internal');
    expect(await codeOf(load(404))).toBe('internal');
  });

  it('reports progress while fetching', async () => {
    const seen: string[] = [];
    await loadTable({
      manifestUrl: 'tables/rows2.manifest.json', tableUrl: 'tables/rows2.bin.gz', engineChecksum: true,
      fetch: fakeFetch({ manifest: JSON.stringify(manifest()), table: gz }),
      subtle: webcrypto.subtle as SubtleCrypto,
      onProgress: (p) => seen.push(p.phase),
    });
    expect(seen).toContain('fetch');
    expect(seen).toContain('inflate');
    expect(seen).toContain('verify');
  });
});

describe('parseManifest', () => {
  it('reads the manifest that scripts/offline/pack-tables.py writes', () => {
    const text = '{"v":1,"alg":"gzip","rawBytes":13123286,"sha256":"d62edf38bbccb229ca055da3054a11e6daffae817d2293dbfa523ad8bb3678e3","gzBytes":2453293,"file":"rows2.bin.gz"}';
    expect(parseManifest(text).rawBytes).toBe(13123286);
  });

  it('calls a cut-short manifest "table-truncated"', () => {
    const text = JSON.stringify(manifest());
    expect(() => parseManifest(text.slice(0, 40))).toThrowError(expect.objectContaining({ code: 'table-truncated' }));
  });

  it('rejects a manifest with a bad hash or version', () => {
    expect(() => parseManifest(JSON.stringify({ ...manifest(), sha256: 'abc' }))).toThrowError(expect.objectContaining({ code: 'internal' }));
    expect(() => parseManifest(JSON.stringify({ ...manifest(), v: 2 }))).toThrowError(expect.objectContaining({ code: 'internal' }));
    expect(() => parseManifest('null')).toThrowError(expect.objectContaining({ code: 'internal' }));
  });
});
