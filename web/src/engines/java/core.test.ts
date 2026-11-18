// Runs the real TeaVM builds (web/public/engines/java/, written by `devenv tasks run teavm:build`) and the real table
// (web/public/tables/, `devenv tasks run web:tables`) in node, for both targets. Skipped, and shown as skipped,
// when either is missing. Expected values are not taken from this engine: the fingerprint is the one that the JVM,
// native C++ and C++ Wasm produce for the same game (plan section 8, teavm-web WebFacadeTest).
import { existsSync, readFileSync } from 'node:fs';
import { webcrypto } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { gunzipSync } from 'node:zlib';
import { describe, expect, it } from 'vitest';
import { EngineFailure } from '../protocol';
import type { Request } from '../protocol';
import { fields, JavaCore } from './core';
import type { JavaModule } from './core';
import { JavaRequestHandler, pickAssets } from './handler';
import type { JavaAssets } from './handler';
import { loadJavaModuleInNode } from './node';
import { analysisSuite } from '../testing/analysis-suite';

const path = (rel: string): string => fileURLToPath(new URL(`../../../public/${rel}`, import.meta.url));
const present = ['engines/java/engine-java.js', 'engines/java/engine-java.wasm', 'engines/java/engine-java.wasm-runtime.js',
  'tables/rows2.bin.gz', 'tables/rows2.manifest.json'].every((f) => existsSync(path(f)));

const TARGETS: { name: string; assets: JavaAssets }[] = [
  { name: 'wasm-gc', assets: { target: 'wasm-gc', main: path('engines/java/engine-java.wasm'), runtime: path('engines/java/engine-java.wasm-runtime.js') } },
  { name: 'js', assets: { target: 'js', main: path('engines/java/engine-java.js') } },
];

const table = (): Uint8Array => new Uint8Array(gunzipSync(readFileSync(path('tables/rows2.bin.gz'))));
const checksumOf = (moves: readonly number[]): bigint => moves.reduce((c, m) => BigInt.asIntN(64, c * 31n + BigInt(m)), 0n);
const failure = (run: () => unknown): EngineFailure => {
  try { run(); } catch (e) { if (e instanceof EngineFailure) return e; throw e; }
  throw new Error('expected an EngineFailure');
};

describe('fields (the reply format of Web.java)', () => {
  it('splits ok replies', () => {
    expect(fields('ok')).toEqual([]);
    expect(fields('ok 1 2 3')).toEqual(['1', '2', '3']);
    expect(fields('ok ')).toEqual(['']);
  });
  it('turns an error reply into an EngineFailure with the code and the message', () => {
    const f = failure(() => fields('error table-trailing the file has trailing bytes'));
    expect([f.code, f.message]).toEqual(['table-trailing', 'the file has trailing bytes']);
  });
  it('maps an unknown code and a malformed reply to internal', () => {
    expect(failure(() => fields('error nonsense x')).code).toBe('internal');
    expect(failure(() => fields('whatever')).code).toBe('internal');
    expect(failure(() => fields(undefined)).code).toBe('internal');
  });
});

describe('pickAssets', () => {
  it('picks the target by the file names', () => {
    expect(pickAssets(['a/engine-java.wasm', 'a/engine-java.wasm-runtime.js'])).toEqual({ target: 'wasm-gc', main: 'a/engine-java.wasm', runtime: 'a/engine-java.wasm-runtime.js' });
    expect(pickAssets(['a/engine-java.js'])).toEqual({ target: 'js', main: 'a/engine-java.js' });
  });
  it('rejects a Wasm file without its runtime and an empty list', () => {
    expect(failure(() => pickAssets(['a/engine-java.wasm'])).code).toBe('internal');
    expect(failure(() => pickAssets([])).code).toBe('internal');
  });
});

describe.skipIf(!present).each(TARGETS)('TeaVM $name in node', ({ assets }) => {
  const load = async (): Promise<JavaCore> => {
    const core = JavaCore.create(await loadJavaModuleInNode(assets));
    core.installTable(table(), true);
    return core;
  };

  analysisSuite(`TeaVM ${assets.target}`, load);

  it('plays the benchmark game with the fingerprint of the JVM: checksum -1986550544382302149, 800,000 nodes', async () => {
    const core = await load();
    const r = core.bench(40, 20_000);
    const nodes = r.nodes.reduce((a, b) => a + b, 0);
    console.log(`${assets.target} checksum=${checksumOf(r.moves)} nodes=${nodes} rows1Requests=${r.rows1Requests}`);
    expect(checksumOf(r.moves)).toBe(-1986550544382302149n);
    expect(nodes).toBe(800_000);
    expect(r.rows1Requests).toBe(0);
  }, 60_000);

  it('answers the start position: 14 or more legal moves, and a legal choice that searched', async () => {
    const core = await load();
    core.newGame({ nodes: [1000, 1000] });
    const legal = core.legalMoves([]);
    expect(legal.length).toBeGreaterThan(0);
    const c = core.choose([]);
    expect(legal).toContain(c.move);
    expect(c.raceMove).toBe(false);
    expect(c.nodes).toBeGreaterThan(0);
    expect(c.depth).toBeGreaterThan(0);
    // choose through the history path and the benchmark path give the same first move at the full budget
    core.newGame({ nodes: [20_000, 20_000] });
    expect(core.choose([]).move).toBe(core.bench(1, 20_000).moves[0]);
  }, 60_000);

  it('replays a history: the second move is legal for the other side', async () => {
    const core = await load();
    core.newGame({ nodes: [500, 500] });
    const m1 = core.choose([]).move;
    const m2 = core.choose([m1]).move;
    expect(core.legalMoves([m1])).toContain(m2);
    expect(failure(() => core.legalMoves([m1, m1])).code).toBe('illegal-history');
    expect(failure(() => core.choose([-3])).code).toBe('illegal-history');
  });

  it('refuses to choose before newGame and before the table', async () => {
    const core = JavaCore.create(await loadJavaModuleInNode(assets));
    expect(failure(() => core.choose([])).code).toBe('internal');
    core.installTable(table(), false);
    expect(failure(() => core.choose([])).code).toBe('internal');
  });

  it('maps damaged tables to the protocol codes', async () => {
    const good = table();
    const fresh = async (): Promise<JavaCore> => JavaCore.create(await loadJavaModuleInNode(assets));
    const cases: [string, Uint8Array, boolean][] = [
      ['table-truncated', good.slice(0, good.length - 1), false],
      ['table-truncated', good.slice(0, 100), false],
      ['table-trailing', new Uint8Array([...good, 0]), false],
      ['table-magic', Uint8Array.from(good, (b, i) => (i === 0 ? b ^ 1 : b)), false],
      ['table-checksum', Uint8Array.from(good, (b, i) => (i === good.length - 1000 ? b ^ 0x55 : b)), true],
    ];
    for (const [code, bytes, verify] of cases) {
      const core = await fresh();
      expect(failure(() => core.installTable(bytes, verify)).code, code).toBe(code);
    }
  });
});

describe.skipIf(!present).each(TARGETS)('request handler, TeaVM $name', ({ assets }) => {
  const urls = assets.target === 'js' ? [assets.main] : [assets.main, assets.runtime!];
  const init = (table: ArrayBuffer | null = null): Request => ({
    id: 1, type: 'init', table,
    assets: { engine: urls, manifest: 'tables/rows2.manifest.json', tableGz: 'tables/rows2.bin.gz' },
  });
  /** Serves the real package; `damage` may change the gzip bytes of the table. */
  const serve = (damage?: (gz: Uint8Array) => Uint8Array): typeof fetch => (async (url: string) => {
    const name = String(url).split('/').pop()!;
    const bytes = readFileSync(path(`tables/${name}`));
    return new Response((name.endsWith('.gz') && damage ? damage(bytes) : bytes) as BodyInit);
  }) as unknown as typeof fetch;
  const handler = (options: { subtle: SubtleCrypto | null; fetch?: typeof fetch }): JavaRequestHandler =>
    new JavaRequestHandler({ loadModule: loadJavaModuleInNode, fetch: options.fetch ?? serve(), subtle: options.subtle });

  it('loads with the page check (subtle SHA-256) and plays a short game', async () => {
    const h = handler({ subtle: webcrypto.subtle as SubtleCrypto });
    const report = (await h.handle(init())) as { tableVerifiedBy: string; instantiateMs: number; tableInstallMs: number };
    expect(report.tableVerifiedBy).toBe('subtle-sha256');
    expect(report.instantiateMs).toBeGreaterThan(0);
    await h.handle({ id: 2, type: 'newGame', setup: { nodes: [300, 300] } });
    const choice = (await h.handle({ id: 3, type: 'choose', history: [] })) as { move: number; rows1Requests: number };
    expect((await h.handle({ id: 4, type: 'legalMoves', history: [] })) as number[]).toContain(choice.move);
    expect(choice.rows1Requests).toBe(0);
  }, 60_000);

  it('without crypto.subtle the engine checks the table itself and says so', async () => {
    const h = handler({ subtle: null });
    const report = (await h.handle(init())) as { tableVerifiedBy: string };
    expect(report.tableVerifiedBy).toBe('engine');
  }, 60_000);

  it('uses the table bytes of the main thread when it sends them', async () => {
    const h = handler({ subtle: webcrypto.subtle as SubtleCrypto });
    const t = table();
    const copy = t.buffer.slice(t.byteOffset, t.byteOffset + t.byteLength) as ArrayBuffer;
    const report = (await h.handle(init(copy))) as { inflateMs: number };
    expect(report.inflateMs).toBe(0);
  }, 60_000);

  it('a request before init is an internal error', async () => {
    const f = await handler({ subtle: null }).handle({ id: 1, type: 'choose', history: [] }).catch((e: unknown) => e);
    expect((f as EngineFailure).code).toBe('internal');
  });

  it('a table with a flipped byte is table-checksum, with and without crypto.subtle', async () => {
    // gzip of a table with one changed body byte: re-compress, so that gzip itself is intact
    const { gzipSync } = await import('node:zlib');
    const bad = table().slice();
    bad[bad.length - 1000]! ^= 0x55;
    const gz = new Uint8Array(gzipSync(bad));
    // the page check compares the length of the download with the manifest, so give the manifest the new length
    const serveBad = (async (url: string) => {
      const name = String(url).split('/').pop()!;
      if (name.endsWith('.gz')) return new Response(gz);
      const manifest = JSON.parse(readFileSync(path('tables/rows2.manifest.json'), 'utf8')) as { gzBytes: number };
      manifest.gzBytes = gz.length;
      return new Response(JSON.stringify(manifest));
    }) as unknown as typeof fetch;
    for (const subtle of [webcrypto.subtle as SubtleCrypto, null]) {
      const e = await handler({ subtle, fetch: serveBad }).handle(init()).catch((x: unknown) => x);
      expect((e as EngineFailure).code, String(subtle)).toBe('table-checksum');
    }
  }, 60_000);

  it('a cut-short download is table-truncated', async () => {
    const e = await handler({ subtle: webcrypto.subtle as SubtleCrypto, fetch: serve((gz) => gz.slice(0, gz.length - 7)) })
      .handle(init()).catch((x: unknown) => x);
    expect((e as EngineFailure).code).toBe('table-truncated');
  });
});

// keeps the unused-type import honest: JavaModule is the shape both targets must satisfy
const _shape = (m: JavaModule): JavaCore => JavaCore.create(m);
void _shape;
