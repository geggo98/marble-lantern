import { afterEach, describe, expect, it } from 'vitest';
import type { ChooseResult, Engine, EngineInfo, GameSetup, LoadReport } from '../engines/engine';
import { EngineFailure } from '../engines/protocol';
import { clearEngines, registerEngine } from '../engines/registry';
import type { EngineDefinition, EnginesManifest } from '../engines/registry';
import { EngineSession } from './session';
import { SwitchableEngine } from './switchable-engine';

const file = { path: 'engines/x.wasm', bytes: 10, gzipBytes: 5, sha256: '0'.repeat(64) };
const manifest: EnginesManifest = {
  v: 1, version: 't',
  engines: {
    'java-wasmgc': { files: [file], binaryBytes: 30, binaryGzipBytes: 12 },
    'cpp-wasm': { files: [file], binaryBytes: 10, binaryGzipBytes: 5 },
    'java-js': { files: [file], binaryBytes: 20, binaryGzipBytes: 9 },
  },
};

class StubEngine implements Engine {
  setups: GameSetup[] = [];
  disposed = false;
  constructor(readonly info: EngineInfo, private readonly failInit?: Error) {}
  async init(onProgress?: (p: { phase: 'fetch'; loaded: number; total: number }) => void): Promise<LoadReport> {
    onProgress?.({ phase: 'fetch', loaded: 1, total: 2 });
    if (this.failInit) throw this.failInit;
    return { fetchMs: 0, inflateMs: 0, verifyMs: 0, instantiateMs: 0, tableInstallMs: 0, totalMs: 0, tableVerifiedBy: 'engine' };
  }
  async newGame(setup: GameSetup): Promise<void> { this.setups.push(setup); }
  async legalMoves(): Promise<number[]> { return []; }
  async choose(): Promise<ChooseResult> { throw new Error('not used'); }
  dispose(): void { this.disposed = true; }
}

const made: StubEngine[] = [];
function def(id: 'cpp-wasm' | 'java-wasmgc' | 'java-js', failures: Error[] = [], available = true): EngineDefinition {
  return {
    id, labelKey: 'engine.cppWasm', runtime: id === 'cpp-wasm' ? 'wasm' : id === 'java-wasmgc' ? 'wasm-gc' : 'js',
    language: id === 'cpp-wasm' ? 'C++' : 'Java',
    isAvailable: () => available,
    create: (context) => {
      const e = new StubEngine(context.info, failures.shift());
      made.push(e);
      return e;
    },
  };
}

const deps = (engine: SwitchableEngine, loadManifest = async () => manifest) => ({ engine, baseUrl: '/', loadManifest, nodes: () => 5000 });

afterEach(() => { clearEngines(); made.length = 0; });

describe('EngineSession', () => {
  it('discovers the engines, loads the default one with progress and hands it to the switchable engine', async () => {
    registerEngine(def('cpp-wasm'));
    registerEngine(def('java-js'));
    const sw = new SwitchableEngine();
    const session = new EngineSession(deps(sw));
    const phases: string[] = [];
    session.subscribe(() => phases.push(session.getState().phase));
    expect(await session.start()).toBe(true);
    expect(phases).toContain('loading');
    expect(session.getState().phase).toBe('ready');
    expect(session.getState().current?.info.id).toBe('cpp-wasm');
    expect(sw.info.id).toBe('cpp-wasm');
    expect(made[0]!.setups).toEqual([{ nodes: [5000, 5000] }]);
  });

  it('keeps the error code of a failed load and recovers on restart', async () => {
    registerEngine(def('cpp-wasm', [new EngineFailure('table-checksum', 'bad')]));
    const session = new EngineSession(deps(new SwitchableEngine()));
    expect(await session.start()).toBe(false);
    expect(session.getState()).toMatchObject({ phase: 'failed', error: { code: 'table-checksum' } });
    expect(made[0]!.disposed).toBe(true);
    expect(await session.restart()).toBe(true);
    expect(session.getState()).toMatchObject({ phase: 'ready', error: null });
  });

  it('switches engine in the middle: the old engine is disposed after the new one is in use', async () => {
    registerEngine(def('cpp-wasm'));
    registerEngine(def('java-js'));
    const sw = new SwitchableEngine();
    const session = new EngineSession(deps(sw));
    await session.start();
    expect(await session.select('java-js')).toBe(true);
    expect(sw.info.id).toBe('java-js');
    expect(made[0]!.disposed).toBe(true);
    expect(made[1]!.disposed).toBe(false);
  });

  it('says no-engine when nothing is available, and failed when engines.json cannot be loaded', async () => {
    const none = new EngineSession(deps(new SwitchableEngine()));
    await none.start();
    expect(none.getState().error?.code).toBe('no-engine');
    registerEngine(def('cpp-wasm'));
    const broken = new EngineSession(deps(new SwitchableEngine(), async () => { throw new Error('engines.json: HTTP 404'); }));
    expect(await broken.start()).toBe(false);
    expect(broken.getState()).toMatchObject({ phase: 'failed', error: { code: 'internal', message: 'engines.json: HTTP 404' } });
  });

  it('starts with TeaVM Wasm GC when the browser has Wasm GC (ADR 0003)', async () => {
    registerEngine(def('cpp-wasm'));
    registerEngine(def('java-js'));
    registerEngine(def('java-wasmgc'));
    const session = new EngineSession(deps(new SwitchableEngine()));
    expect(await session.start()).toBe(true);
    expect(session.getState().current?.info.id).toBe('java-wasmgc');
  });

  it('falls back to C++ (faster), not to Java JS, in a browser without Wasm GC', async () => {
    registerEngine(def('java-js'));
    registerEngine(def('java-wasmgc', [], false)); // wasmGcSupported() is false there
    registerEngine(def('cpp-wasm'));
    const session = new EngineSession(deps(new SwitchableEngine()));
    expect(await session.start()).toBe(true);
    expect(session.getState().current?.info.id).toBe('cpp-wasm');
  });

  it('falls back to Java JS when only that engine runs', async () => {
    registerEngine(def('java-wasmgc', [], false));
    registerEngine(def('cpp-wasm', [], false));
    registerEngine(def('java-js'));
    const session = new EngineSession(deps(new SwitchableEngine()));
    expect(await session.start()).toBe(true);
    expect(session.getState().current?.info.id).toBe('java-js');
  });

  it('refuses to select an engine that is not available', async () => {
    registerEngine(def('cpp-wasm'));
    const session = new EngineSession(deps(new SwitchableEngine()));
    await session.start();
    expect(await session.select('java-js')).toBe(false);
    expect(session.getState().current?.info.id).toBe('cpp-wasm');
  });
});

describe('SwitchableEngine', () => {
  it('throws a protocol error before an engine is in use, and a strength override beats the game setup', async () => {
    const sw = new SwitchableEngine();
    await expect(sw.legalMoves([])).rejects.toMatchObject({ code: 'internal' });
    const e = new StubEngine({ id: 'cpp-wasm', labelKey: 'engine.cppWasm', runtime: 'wasm', language: 'C++', version: '', binaryBytes: 0, binaryGzipBytes: 0 });
    await sw.use(e, { nodes: [1, 1] });
    sw.setNodes(100000);
    await sw.newGame({ nodes: [20000, 20000] });
    expect(e.setups.at(-1)).toEqual({ nodes: [100000, 100000] });
  });
});
