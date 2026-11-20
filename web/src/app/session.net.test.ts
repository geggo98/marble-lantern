// The session with a net engine: model and simulations reach newGame, the warnings reach the state, the default engine is never a net
// engine, and a classical newGame stays free of net fields. Also the selection store the UI binds to.
import { afterEach, describe, expect, it } from 'vitest';
import type { AnalysisResult, ChooseResult, Engine, EngineInfo, EngineOutcome, GameSetup, LoadReport } from '../engines/engine';
import { clearEngines, describeEngines, registerEngine } from '../engines/registry';
import type { EngineDefinition, EnginesManifest } from '../engines/registry';
import type { EngineWarning } from '../engines/warnings';
import { SELECTION_KEY } from '../engines/net/selection';
import { EngineSession, analysisChoice, defaultChoice } from './session';
import { NetSelectionStore } from './net-selection';
import { SwitchableEngine } from './switchable-engine';

const file = { path: 'engines/net/x.wasm', bytes: 10, gzipBytes: 5, sha256: '0'.repeat(64) };
const entry = { files: [file], binaryBytes: 10, binaryGzipBytes: 5 };
const manifest: EnginesManifest = { v: 1, version: 't', engines: { 'cpp-wasm': entry, 'net-wasm': entry, 'net-webgpu': entry } };

class StubEngine implements Engine {
  setups: GameSetup[] = [];
  warnings: readonly EngineWarning[] = [];
  disposed = false;
  /** A newGame waits for this promise (a slow model load), when set. */
  hold: Promise<void> | null = null;
  failNewGame: Error | null = null;
  private listeners = new Set<() => void>();
  constructor(readonly info: EngineInfo, private readonly onNewGame: (setup: GameSetup) => EngineWarning[] = () => []) {}
  subscribeWarnings(l: () => void): () => void { this.listeners.add(l); return () => this.listeners.delete(l); }
  async init(): Promise<LoadReport> { return { fetchMs: 0, inflateMs: 0, verifyMs: 0, instantiateMs: 0, tableInstallMs: 0, totalMs: 0, tableVerifiedBy: 'engine' }; }
  async newGame(setup: GameSetup): Promise<void> {
    this.setups.push(setup);
    await this.hold;
    if (this.failNewGame) throw this.failNewGame;
    this.warnings = this.onNewGame(setup);
    for (const l of this.listeners) l();
  }
  async legalMoves(): Promise<number[]> { return []; }
  async choose(): Promise<ChooseResult> { throw new Error('not used'); }
  async analyse(): Promise<AnalysisResult> { throw new Error('not used'); }
  async outcome(): Promise<EngineOutcome> { return { kind: 'ongoing' }; }
  dispose(): void { this.disposed = true; }
}

const made: Record<string, StubEngine> = {};
const holds: Record<string, Promise<void>> = {};
const failures: Record<string, Error> = {};
const rule = (setup: GameSetup): EngineWarning[] => (setup.model ? [] : [{ code: 'no-model' }, { code: 'fallback-random' }]);
function def(id: 'cpp-wasm' | 'net-wasm' | 'net-webgpu'): EngineDefinition {
  return {
    id, labelKey: 'engine.cppWasm', runtime: 'wasm', language: id === 'cpp-wasm' ? 'C++' : 'Java + C++',
    isAvailable: () => true,
    create: (context) => {
      const e = (made[id] = new StubEngine(context.info, id === 'cpp-wasm' ? undefined : rule));
      e.hold = holds[id] ?? null;
      e.failNewGame = failures[id] ?? null;
      return e;
    },
  };
}

afterEach(() => { clearEngines(); for (const o of [made, holds, failures]) for (const k of Object.keys(o)) delete o[k]; });

function setup(selection: { model: string | null; sims: number } = { model: null, sims: 200 }) {
  registerEngine(def('cpp-wasm'));
  registerEngine(def('net-wasm'));
  registerEngine(def('net-webgpu'));
  const sw = new SwitchableEngine();
  let net = selection;
  const session = new EngineSession({ engine: sw, baseUrl: '/', loadManifest: async () => manifest, nodes: () => 5000, net: () => net });
  return { sw, session, setNet: (n: typeof net) => { net = n; } };
}

describe('EngineSession with net engines', () => {
  it('starts with the classical engine, never a net engine, and the net engines are listed and available', async () => {
    const { session } = setup();
    await session.start();
    expect(session.getState().current?.info.id).toBe('cpp-wasm');
    expect(session.getState().warnings).toEqual([]);
    const ids = describeEngines(manifest).filter((c) => c.available).map((c) => c.info.id);
    expect(ids).toEqual(expect.arrayContaining(['net-wasm', 'net-webgpu']));
    expect(defaultChoice(describeEngines({ ...manifest, engines: { 'net-wasm': entry } }))).toBeNull();
  });

  it('a net engine gets model and options with its first newGame; the warnings show in the state', async () => {
    const { session } = setup({ model: 'm', sims: 800 });
    await session.start();
    expect(await session.select('net-wasm')).toBe(true);
    expect(made['net-wasm']!.setups[0]).toMatchObject({ nodes: [5000, 5000], model: 'm', netOptions: { sims: 800 } });
    expect(session.getState().warnings).toEqual([]);
  });

  it('without a model the state carries the warnings; they clear when the model is applied', async () => {
    const { session, setNet } = setup();
    await session.start();
    await session.select('net-wasm');
    expect(session.getState().warnings.map((w) => w.code)).toEqual(['no-model', 'fallback-random']);
    setNet({ model: 'm', sims: 50 });
    expect(await session.applyNet()).toBe(true);
    expect(made['net-wasm']!.setups.at(-1)).toMatchObject({ model: 'm', netOptions: { sims: 50 } });
    expect(session.getState().warnings).toEqual([]);
    expect(session.getState().phase).toBe('ready');
  });

  it('listeners of the session hear about the warnings', async () => {
    const { session } = setup();
    await session.start();
    const seen: number[] = [];
    session.subscribe(() => seen.push(session.getState().warnings.length));
    await session.select('net-wasm');
    expect(seen).toContain(2);
  });

  it('switching back to a classical engine clears the warnings and sends no net fields', async () => {
    const { session, sw } = setup();
    await session.start();
    await session.select('net-wasm');
    expect(session.getState().warnings).toHaveLength(2);
    await session.select('cpp-wasm');
    expect(session.getState().warnings).toEqual([]);
    const second = made['cpp-wasm']!;
    expect(JSON.stringify(second.setups[0])).toBe('{"nodes":[5000,5000]}');
    await sw.newGame({ nodes: [1, 1] });
    expect('model' in second.setups.at(-1)!).toBe(false);
  });

  it('applyNet does nothing for a classical engine', async () => {
    const { session } = setup();
    await session.start();
    expect(await session.applyNet()).toBe(false);
  });

  it('later newGame calls of the app (a new game, a level change) carry the model too', async () => {
    const { session, sw } = setup({ model: 'm', sims: 200 });
    await session.start();
    await session.select('net-webgpu');
    await sw.newGame({ nodes: [100, 100] });
    expect(made['net-webgpu']!.setups.at(-1)).toMatchObject({ model: 'm', netOptions: { sims: 200 } });
  });
});

describe('engine switch races (F8)', () => {
  it('a slow net newGame that ends after a newer classical load does not replace the classical engine', async () => {
    let release: () => void = () => undefined;
    holds['net-wasm'] = new Promise<void>((r) => { release = r; });
    const { session, sw } = setup({ model: 'm', sims: 200 });
    await session.start();
    const classical1 = made['cpp-wasm']!;
    const slowNet = session.select('net-wasm'); // waits in newGame
    await new Promise((r) => setTimeout(r, 0));
    expect(made['net-wasm']).toBeDefined();
    const back = session.select('cpp-wasm'); // quick, newer
    expect(await back).toBe(true);
    const classical2 = made['cpp-wasm']!;
    expect(sw.info.id).toBe('cpp-wasm');
    release();
    expect(await slowNet).toBe(false);
    expect(made['net-wasm']!.disposed).toBe(true); // the stale engine is disposed
    expect(classical2.disposed).toBe(false);
    expect(classical1.disposed).toBe(true);
    expect(session.getState().current?.info.id).toBe('cpp-wasm');
    expect(session.getState().phase).toBe('ready');
    // and the game goes on with the classical engine, without net fields
    await sw.newGame({ nodes: [1, 1] });
    expect('model' in classical2.setups.at(-1)!).toBe(false);
  });

  it('SwitchableEngine.use reports false for a stale engine and true for the current one', async () => {
    const sw = new SwitchableEngine();
    let release: () => void = () => undefined;
    const slow = new StubEngine({ id: 'net-wasm' } as EngineInfo);
    slow.hold = new Promise<void>((r) => { release = r; });
    const quick = new StubEngine({ id: 'cpp-wasm' } as EngineInfo);
    const first = sw.use(slow, { nodes: [1, 1] });
    expect(await sw.use(quick, { nodes: [1, 1] })).toBe(true);
    release();
    expect(await first).toBe(false);
    expect(slow.disposed).toBe(true);
    expect(sw.info.id).toBe('cpp-wasm');
  });

  it('a failing net load resets the net settings, so the next classical newGame carries no model', async () => {
    failures['net-wasm'] = new Error('model failed');
    const { session, sw } = setup({ model: 'm', sims: 200 });
    await session.start();
    expect(await session.select('net-wasm')).toBe(false);
    expect(session.getState().phase).toBe('failed');
    const classical = made['cpp-wasm']!;
    await sw.newGame({ nodes: [1, 1] });
    expect('model' in classical.setups.at(-1)!).toBe(false);
    expect('netOptions' in classical.setups.at(-1)!).toBe(false);
  });
});

describe('NetSelectionStore', () => {
  const mem = () => {
    const data = new Map<string, string>();
    return { data, storage: { getItem: (k: string) => data.get(k) ?? null, setItem: (k: string, v: string) => void data.set(k, v) } };
  };

  it('starts from the default selection, merges changes, saves them and notifies', () => {
    const m = mem();
    const store = new NetSelectionStore({ storage: m.storage, probe: async () => ({ available: true }), wasm: true });
    expect(store.selection).toEqual({ engine: 'java-wasmgc', modelId: null, sims: 200 });
    let n = 0;
    store.subscribe(() => n++);
    store.setSelection({ modelId: 'small-shared-all' });
    store.setSelection({ sims: 800 });
    expect(store.selection).toEqual({ engine: 'java-wasmgc', modelId: 'small-shared-all', sims: 800 });
    expect(n).toBe(2);
    expect(JSON.parse(m.data.get(SELECTION_KEY) as string)).toEqual(store.selection);
    expect(new NetSelectionStore({ storage: m.storage, wasm: true }).selection).toEqual(store.selection);
  });

  it('probes WebGPU once, when net-webgpu is selected, and exposes the answer and the evaluation', async () => {
    let probes = 0;
    const store = new NetSelectionStore({ storage: mem().storage, wasm: true, probe: async () => { probes++; return { available: false, reason: 'webgpu-no-adapter' as const, detail: 'no adapter' }; } });
    expect(store.getState().availability.webgpu).toBeNull();
    store.setSelection({ engine: 'net-wasm', modelId: 'small-2p-adjacent-expert' });
    expect(probes).toBe(0);
    store.setSelection({ engine: 'net-webgpu' });
    store.setSelection({ sims: 50 });
    await store.probeWebGpu();
    expect(probes).toBe(1);
    expect(store.getState().availability).toEqual({ wasm: true, webgpu: false, webgpuReason: 'webgpu-no-adapter', webgpuDetail: 'no adapter' });
    const e = store.evaluate({ jump: 'adjacent', players: 2 });
    expect(e.warnings.map((w) => w.code)).toEqual(['backend-unavailable']);
    expect(e.effectiveBackend).toBe('wasm');
  });

  it('a probe that throws counts as not available', async () => {
    const store = new NetSelectionStore({ storage: mem().storage, wasm: true, probe: async () => { throw new Error('probe broke'); } });
    await store.probeWebGpu();
    expect(store.getState().availability).toMatchObject({ webgpu: false, webgpuReason: 'unknown', webgpuDetail: 'probe broke' });
  });
});

describe('the analysis engine of the net engines', () => {
  it('names the first classical browser engine and makes a fresh engine of it, apart from the one that plays', async () => {
    const { sw, session } = setup();
    expect(session.analysisSource(), 'nothing before start()').toBeNull();
    await session.start();
    await session.select('net-wasm');
    const playing = made['net-wasm'];
    const source = session.analysisSource();
    expect(source?.id).toBe('cpp-wasm');
    expect(sw.analysisId).toBe('cpp-wasm');
    const before = made['cpp-wasm'];
    const helper = source!.create();
    expect(helper.info.id).toBe('cpp-wasm');
    expect(helper).not.toBe(before);
    expect(helper).not.toBe(playing);
  });

  it('skips net and server engines and unavailable ones; null when none is left', () => {
    registerEngine(def('net-wasm'));
    registerEngine({ id: 'server:browser', labelKey: 'engine.serverBrowser', runtime: 'server', language: 'Java', isAvailable: () => true, create: () => { throw new Error('unused'); } });
    expect(analysisChoice(describeEngines(manifest))).toBeNull();
    registerEngine({ ...def('cpp-wasm'), isAvailable: () => false });
    expect(analysisChoice(describeEngines(manifest))).toBeNull();
    registerEngine(def('cpp-wasm'));
    expect(analysisChoice(describeEngines(manifest))?.info.id).toBe('cpp-wasm');
  });
});
