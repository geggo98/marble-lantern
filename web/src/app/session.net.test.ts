// The session with a net engine: model and simulations reach newGame, the warnings reach the state, the default engine is never a net
// engine, and a classical newGame stays free of net fields. Also the selection store the UI binds to.
import { afterEach, describe, expect, it } from 'vitest';
import type { AnalysisResult, ChooseResult, Engine, EngineInfo, EngineOutcome, GameSetup, LoadReport } from '../engines/engine';
import { clearEngines, describeEngines, registerEngine } from '../engines/registry';
import type { EngineDefinition, EnginesManifest } from '../engines/registry';
import type { EngineWarning } from '../engines/warnings';
import { SELECTION_KEY } from '../engines/net/selection';
import { EngineSession, defaultChoice } from './session';
import { NetSelectionStore } from './net-selection';
import { SwitchableEngine } from './switchable-engine';

const file = { path: 'engines/net/x.wasm', bytes: 10, gzipBytes: 5, sha256: '0'.repeat(64) };
const entry = { files: [file], binaryBytes: 10, binaryGzipBytes: 5 };
const manifest: EnginesManifest = { v: 1, version: 't', engines: { 'cpp-wasm': entry, 'net-wasm': entry, 'net-webgpu': entry } };

class StubEngine implements Engine {
  setups: GameSetup[] = [];
  warnings: readonly EngineWarning[] = [];
  disposed = false;
  private listeners = new Set<() => void>();
  constructor(readonly info: EngineInfo, private readonly onNewGame: (setup: GameSetup) => EngineWarning[] = () => []) {}
  subscribeWarnings(l: () => void): () => void { this.listeners.add(l); return () => this.listeners.delete(l); }
  async init(): Promise<LoadReport> { return { fetchMs: 0, inflateMs: 0, verifyMs: 0, instantiateMs: 0, tableInstallMs: 0, totalMs: 0, tableVerifiedBy: 'engine' }; }
  async newGame(setup: GameSetup): Promise<void> {
    this.setups.push(setup);
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
const rule = (setup: GameSetup): EngineWarning[] => (setup.model ? [] : [{ code: 'no-model' }, { code: 'fallback-random' }]);
function def(id: 'cpp-wasm' | 'net-wasm' | 'net-webgpu'): EngineDefinition {
  return {
    id, labelKey: 'engine.cppWasm', runtime: 'wasm', language: id === 'cpp-wasm' ? 'C++' : 'Java + C++',
    isAvailable: () => true,
    create: (context) => (made[id] = new StubEngine(context.info, id === 'cpp-wasm' ? undefined : rule)),
  };
}

afterEach(() => { clearEngines(); for (const k of Object.keys(made)) delete made[k]; });

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
    const store = new NetSelectionStore({ storage: mem().storage, wasm: true, probe: async () => { probes++; return { available: false, reason: 'no adapter' }; } });
    expect(store.getState().availability.webgpu).toBeNull();
    store.setSelection({ engine: 'net-wasm', modelId: 'small-2p-adjacent-expert' });
    expect(probes).toBe(0);
    store.setSelection({ engine: 'net-webgpu' });
    store.setSelection({ sims: 50 });
    await store.probeWebGpu();
    expect(probes).toBe(1);
    expect(store.getState().availability).toEqual({ wasm: true, webgpu: false, webgpuReason: 'no adapter' });
    const e = store.evaluate({ jump: 'adjacent', players: 2 });
    expect(e.warnings.map((w) => w.code)).toEqual(['backend-unavailable']);
    expect(e.effectiveBackend).toBe('wasm');
  });

  it('a probe that throws counts as not available', async () => {
    const store = new NetSelectionStore({ storage: mem().storage, wasm: true, probe: async () => { throw new Error('probe broke'); } });
    await store.probeWebGpu();
    expect(store.getState().availability).toMatchObject({ webgpu: false, webgpuReason: 'probe broke' });
  });
});
