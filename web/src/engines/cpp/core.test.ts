// Tests of the C++ adapter against the real engine-cpp.js and rows2.bin.gz (built by `devenv tasks run wasm:build`
// and `web:tables`). Without those files the tests skip, unless REQUIRE_ENGINE_ASSETS=1 is set, as in CI: then they fail.
import { existsSync, readFileSync } from 'node:fs';
import { gunzipSync } from 'node:zlib';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { describe, expect, it } from 'vitest';
import { EngineFailure } from '../protocol';
import type { ErrorCode } from '../protocol';
import { CppCore } from './core';
import type { CppModuleFactory } from './core';
import { CppRequestHandler } from './handler';

const publicDir = fileURLToPath(new URL('../../../public/', import.meta.url));
const enginePath = publicDir + 'engines/cpp/engine-cpp.js';
const tableGzPath = publicDir + 'tables/rows2.bin.gz';
const manifestPath = publicDir + 'tables/rows2.manifest.json';
const assetsPresent = [enginePath, publicDir + 'engines/cpp/engine-cpp.wasm', tableGzPath, manifestPath].every(existsSync);
const required = process.env.REQUIRE_ENGINE_ASSETS === '1';

/** The benchmark game of plan section 8. */
const BENCH = { plies: 40, nodes: 20000, checksum: -1986550544382302149n, totalNodes: 800000 };
const checksumOf = (moves: readonly number[]): bigint => moves.reduce((c, m) => BigInt.asIntN(64, c * 31n + BigInt(m)), 0n);

const factory: CppModuleFactory = async () => ((await import(/* @vite-ignore */ pathToFileURL(enginePath).href)) as { default: CppModuleFactory }).default();
const table = (): Uint8Array => new Uint8Array(gunzipSync(readFileSync(tableGzPath)));
const errorCode = (fn: () => unknown): ErrorCode | 'no error' => {
  try {
    fn();
    return 'no error';
  } catch (e) {
    if (e instanceof EngineFailure) return e.code;
    throw e;
  }
};

it('has its engine assets (required when REQUIRE_ENGINE_ASSETS=1)', () => {
  if (required) expect(assetsPresent, 'run devenv tasks run wasm:build web:tables first').toBe(true);
});

describe.skipIf(!assetsPresent)('CppCore on the real engine', () => {
  it('plays the benchmark game with the fingerprint of the plan, checked by the engine and unchecked', async () => {
    const lines: string[] = [];
    for (const verify of [true, false]) {
      const core = await CppCore.create(factory);
      core.installTable(table(), verify);
      const r = core.bench(BENCH.plies, BENCH.nodes);
      const nodes = r.nodes.reduce((a, b) => a + b, 0);
      lines.push(`checksum=${checksumOf(r.moves)} nodes=${nodes} rows1Requests=${r.rows1Requests}`);
    }
    console.log(lines[0]);
    expect(lines[0]).toBe(`checksum=${BENCH.checksum} nodes=${BENCH.totalNodes} rows1Requests=0`);
    expect(lines[1]).toBe(lines[0]);
  }, 60_000);

  it('a second game with reused players gives the same moves as the first (the transposition table is cleared)', async () => {
    const core = await CppCore.create(factory);
    core.installTable(table(), false);
    const first = core.bench(8, 2000).moves;
    const second = core.bench(8, 2000).moves;
    expect(second).toEqual(first);
    // The pool must reuse: playing a different budget and coming back still matches.
    core.bench(2, 500);
    expect(core.bench(8, 2000).moves).toEqual(first);
  }, 60_000);

  it('maps damaged tables to the protocol codes: flipped byte, missing byte, extra byte, HTML page', async () => {
    const good = table();
    const flipped = good.slice();
    flipped[good.length - 1000]! ^= 0xff;
    const short = good.slice(0, good.length - 1);
    const long = new Uint8Array(good.length + 1);
    long.set(good);
    const html = new TextEncoder().encode('<!doctype html><title>404</title>');
    const codes: Record<string, ErrorCode | 'no error'> = {};
    for (const [name, bytes] of Object.entries({ flipped, short, long, html })) {
      const core = await CppCore.create(factory);
      codes[name] = errorCode(() => core.installTable(bytes, true));
    }
    expect(codes).toEqual({ flipped: 'table-checksum', short: 'table-truncated', long: 'table-trailing', html: 'table-magic' });
  }, 60_000);

  it('refuses to play before a table is installed', async () => {
    const core = await CppCore.create(factory);
    expect(errorCode(() => core.newGame({ nodes: [100, 100] }))).toBe('internal');
  });

  it('replays a history, rejects an illegal one, and reports the legal moves of the start position', async () => {
    const core = await CppCore.create(factory);
    core.installTable(table(), false);
    core.newGame({ nodes: [500, 500] });
    const start = core.legalMoves([]);
    expect(start.length).toBeGreaterThan(0);
    expect(new Set(start).size).toBe(start.length);
    const next = core.legalMoves([start[0]!]);
    expect(next.length).toBeGreaterThan(0);
    expect(errorCode(() => core.legalMoves([start[0]!, start[0]!]))).toBe('illegal-history');
    expect(errorCode(() => core.choose([9999]))).toBe('illegal-history');
    const r = core.choose([start[0]!]);
    expect(next).toContain(r.move);
    expect(r.rows1Requests).toBe(0);
  });
});

describe.skipIf(!assetsPresent)('CppRequestHandler (the worker logic)', () => {
  const assets = { engine: ['file://engine-cpp.js'], manifest: 'file://m', tableGz: 'file://t' };
  const handlerOptions = (subtle: SubtleCrypto | null) => ({
    importFactory: async () => factory,
    subtle,
    fetch: (async (url: string) =>
      url === 'file://m' ? new Response(readFileSync(manifestPath)) : new Response(readFileSync(tableGzPath))) as unknown as typeof fetch,
  });

  it('initialises from a fetched table, plays a game through the protocol and reports how the table was checked', async () => {
    const handler = new CppRequestHandler(handlerOptions(globalThis.crypto.subtle));
    const phases: string[] = [];
    const report = (await handler.handle({ id: 1, type: 'init', assets, table: null }, (p) => phases.push(p.phase))) as { tableVerifiedBy: string; totalMs: number };
    expect(report.tableVerifiedBy).toBe('subtle-sha256');
    expect(new Set(phases)).toContain('instantiate');
    expect(new Set(phases)).toContain('table');
    await handler.handle({ id: 2, type: 'newGame', setup: { nodes: [1000, 1000] } });
    const moves = (await handler.handle({ id: 3, type: 'legalMoves', history: [] })) as number[];
    expect(moves.length).toBeGreaterThan(0);
    const choice = (await handler.handle({ id: 4, type: 'choose', history: [] })) as { move: number; rows1Requests: number };
    expect(moves).toContain(choice.move);
    expect(choice.rows1Requests).toBe(0);
  }, 60_000);

  it('uses the engine check when crypto.subtle is missing', async () => {
    const handler = new CppRequestHandler(handlerOptions(null));
    const report = (await handler.handle({ id: 1, type: 'init', assets, table: null })) as { tableVerifiedBy: string };
    expect(report.tableVerifiedBy).toBe('engine');
  }, 60_000);

  it('takes a table handed over by the main thread and rejects a corrupt one as table-checksum', async () => {
    const good = table();
    const ok = new CppRequestHandler(handlerOptions(globalThis.crypto.subtle));
    await ok.handle({ id: 1, type: 'init', assets, table: good.slice().buffer });

    const bad = good.slice();
    bad[5000]! ^= 0x01;
    const handler = new CppRequestHandler(handlerOptions(globalThis.crypto.subtle));
    await expect(handler.handle({ id: 1, type: 'init', assets, table: bad.buffer })).rejects.toMatchObject({ code: 'table-checksum' });

    // The same corrupt table without subtle: the engine's own check finds it.
    const insecure = new CppRequestHandler(handlerOptions(null));
    await expect(insecure.handle({ id: 1, type: 'init', assets, table: bad.slice().buffer })).rejects.toMatchObject({ code: 'table-checksum' });
  }, 60_000);

  it('fails a request before init with "internal"', async () => {
    const handler = new CppRequestHandler(handlerOptions(null));
    await expect(handler.handle({ id: 1, type: 'choose', history: [] })).rejects.toMatchObject({ code: 'internal' });
  });

  it('plays the benchmark fingerprint through the bench request', async () => {
    const handler = new CppRequestHandler(handlerOptions(globalThis.crypto.subtle));
    await handler.handle({ id: 1, type: 'init', assets, table: null });
    const r = (await handler.handle({ id: 2, type: 'bench', plies: BENCH.plies, nodes: BENCH.nodes })) as { moves: number[]; nodes: number[]; rows1Requests: number };
    console.log(`checksum=${checksumOf(r.moves)} nodes=${r.nodes.reduce((a, b) => a + b, 0)} rows1Requests=${r.rows1Requests}`);
    expect(checksumOf(r.moves)).toBe(BENCH.checksum);
  }, 60_000);
});
