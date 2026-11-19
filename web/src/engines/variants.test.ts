// Game variants at the seams of the engine layer, without a real engine: the worker client, the server adapter and the registry.
import { afterEach, describe, expect, it, vi } from 'vitest';
import { WorkerEngine } from './client';
import type { Engine } from './engine';
import { FAKE_INFO, FakeEngineCore, FakeWorker } from './testing/fake-engine';
import { ServerEngineAdapter } from './server/client';
import type { ServerApi, ServerEngineEntry } from './server/client';
import { registerServerEngines } from './server/load';
import { clearEngines, describeEngines, registerEngine } from './registry';
import type { EngineDefinition, EnginesManifest } from './registry';
import { makeMove } from './engine';
import { cellId } from '../board/cells';
import { DEFAULT_VARIANT } from '../game/variant';
import type { Variant } from '../game/variant';

const THREE: Variant = { jump: 'adjacent', players: 3 };
const LONG: Variant = { jump: 'long', players: 2 };
const assets = { engine: [], manifest: '', tableGz: '' };

async function workerEngine(): Promise<{ engine: WorkerEngine; workers: FakeWorker[] }> {
  const core = new FakeEngineCore(9);
  const workers: FakeWorker[] = [];
  const engine = new WorkerEngine({
    info: FAKE_INFO, assets,
    createWorker: () => { const w = new FakeWorker(core, { chooseDelayMs: 5 }); workers.push(w); return w; },
  });
  await engine.init();
  return { engine, workers };
}

describe('WorkerEngine with variants', () => {
  it('sends the variant of newGame with choose, and the variant argument with the stateless calls', async () => {
    const { engine } = await workerEngine();
    await engine.newGame({ nodes: [10, 10, 10], variant: THREE });
    const first = await engine.choose([]);
    expect((await engine.legalMoves([], THREE))).toContain(first.move);
    expect(await engine.outcome([], THREE)).toEqual({ kind: 'ongoing' });
    // the two-player start position has other legal moves than the three-player one
    expect(await engine.legalMoves([first.move])).not.toEqual(await engine.legalMoves([first.move], THREE));
    // choose with a variant other than the game's is an error from the engine
    await expect(engine.choose([], undefined, DEFAULT_VARIANT)).rejects.toMatchObject({ code: 'internal' });
  });

  it('replays the variant of the game after an abort restarts the worker', async () => {
    const { engine, workers } = await workerEngine();
    await engine.newGame({ nodes: [10, 10, 10], variant: THREE });
    const controller = new AbortController();
    const search = engine.choose([], controller.signal);
    await new Promise((resolve) => setTimeout(resolve, 1)); // the request is in flight
    controller.abort();
    await expect(search).rejects.toMatchObject({ name: 'AbortError' });
    const again = await engine.choose([], undefined, THREE); // would fail with "another variant than newGame" if the restart forgot the variant
    expect(workers.length).toBe(2);
    expect(again.move).toBeGreaterThan(0);
  });

  it('analyse of a long-hop game passes the variant; for three players it rejects with unsupported', async () => {
    const { engine } = await workerEngine();
    await engine.newGame({ nodes: [10, 10], variant: LONG });
    const long = await engine.analyse([], null, 10, undefined, LONG);
    expect(long.scores.length).toBeGreaterThan(0);
    await expect(engine.analyse([], null, 10, undefined, THREE)).rejects.toMatchObject({ code: 'unsupported' });
  });
});

const entry: ServerEngineEntry = { id: 'browser', labelKey: 'engine.serverBrowser', backend: 'jvm' };
const serverApi = (): ServerApi => ({
  engines: vi.fn(async () => [entry]),
  legalMoves: vi.fn(async () => [makeMove(cellId(1, -5), cellId(0, -4))]),
  choose: vi.fn(async () => ({ move: 1, score: 0, depth: 1, nodes: 1, ms: 0, raceMove: false, rows1Requests: 0 })),
});

describe('server engines play the default variant only', () => {
  it('reject every other variant with the code unsupported, without calling the server', async () => {
    const api = serverApi();
    const server = new ServerEngineAdapter(entry, api);
    for (const variant of [LONG, THREE, { jump: 'long', players: 3 } as Variant]) {
      await expect(server.newGame({ nodes: [1, 1], variant })).rejects.toMatchObject({ code: 'unsupported' });
      await expect(server.legalMoves([], variant)).rejects.toMatchObject({ code: 'unsupported' });
      await expect(server.choose([], undefined, variant)).rejects.toMatchObject({ code: 'unsupported' });
      await expect(server.analyse([], null, 1, undefined, variant)).rejects.toMatchObject({ code: 'unsupported' });
      await expect(server.outcome([], variant)).rejects.toMatchObject({ code: 'unsupported' });
    }
    expect(api.legalMoves).not.toHaveBeenCalled();
    expect(api.choose).not.toHaveBeenCalled();
  });

  it('accept the default variant, spelled out or not', async () => {
    const server = new ServerEngineAdapter(entry, serverApi());
    await server.newGame({ nodes: [1, 1], variant: DEFAULT_VARIANT });
    await server.newGame({ nodes: [1, 1] });
    expect(await server.legalMoves([], DEFAULT_VARIANT)).toHaveLength(1);
    expect((await server.choose([], undefined, DEFAULT_VARIANT)).move).toBe(1);
  });

  it('outcome of the default variant: ongoing, draw without legal moves, draw at the move limit, illegal history', async () => {
    const api = serverApi();
    const server = new ServerEngineAdapter(entry, api);
    expect(await server.outcome([])).toEqual({ kind: 'ongoing' });
    api.legalMoves = vi.fn(async () => []);
    expect(await server.outcome([])).toEqual({ kind: 'draw' });
    await expect(server.outcome([makeMove(cellId(0, 0), cellId(1, 0))])).rejects.toMatchObject({ code: 'illegal-history' });
  });
});

describe('registry and variants', () => {
  afterEach(() => clearEngines());
  const file = { path: 'x', bytes: 1, gzipBytes: 1, sha256: '0'.repeat(64) };
  const manifest: EnginesManifest = { v: 1, version: 'v', engines: { 'cpp-wasm': { files: [file], binaryBytes: 1, binaryGzipBytes: 1 } } };
  const stub = {} as Engine;
  const browserDef: EngineDefinition = { id: 'cpp-wasm', labelKey: 'engine.cppWasm', runtime: 'wasm', language: 'C++', isAvailable: () => true, create: () => stub };
  const serverDef: EngineDefinition = {
    id: 'server:browser', labelKey: 'engine.serverBrowser', runtime: 'server', language: 'Java', isAvailable: () => true,
    supportsVariant: (v) => v.jump === 'adjacent' && v.players === 2, create: () => stub,
  };

  it('marks a server engine unavailable for any variant but the default, with the reason "variant"', () => {
    registerEngine(browserDef);
    registerEngine(serverDef);
    const byId = (v?: Variant) => Object.fromEntries(describeEngines(manifest, v).map((c) => [c.info.id, c]));
    expect(byId()['server:browser']).toMatchObject({ available: true });
    expect(byId(DEFAULT_VARIANT)['server:browser']).toMatchObject({ available: true });
    for (const v of [LONG, THREE]) {
      expect(byId(v)['server:browser']).toMatchObject({ available: false, reason: 'variant' });
      expect(byId(v)['cpp-wasm']).toMatchObject({ available: true });
    }
  });

  it('registerServerEngines gives the server definitions the variant check (offline builds register none)', async () => {
    // __ONLINE__ is false in the tests: nothing is registered, and the call must not throw
    await registerServerEngines();
    expect(describeEngines(manifest, THREE).every((c) => c.definition.runtime !== 'server')).toBe(true);
  });
});
