import { afterEach, describe, expect, it } from 'vitest';
import type { Engine, EngineId } from './engine';
import { clearEngines, createEngine, describeEngines, listDefinitions, loadEnginesManifest, registerEngine, wasmGcSupported } from './registry';
import type { EngineDefinition, EnginesManifest } from './registry';

const stubEngine = {} as Engine;
const def = (id: EngineId, runtime: EngineDefinition['runtime'], available = true): EngineDefinition => ({
  id, labelKey: 'engine.cppWasm', runtime, language: id.startsWith('java') ? 'Java' : 'C++',
  isAvailable: () => available, create: () => stubEngine,
});
const file = { path: 'engines/x.wasm', bytes: 10, gzipBytes: 5, sha256: '0'.repeat(64) };
const manifest: EnginesManifest = {
  v: 1, version: 'v0-1-gabc',
  engines: {
    'cpp-wasm': { files: [file], binaryBytes: 267000, binaryGzipBytes: 101000 },
    'java-wasmgc': { files: [file], binaryBytes: 506000, binaryGzipBytes: 200000 },
  },
};

afterEach(() => clearEngines());

describe('wasmGcSupported', () => {
  it('is true where this runtime has Wasm GC (node 24) and the probe is a valid struct-type module', () => {
    expect(wasmGcSupported()).toBe(true);
  });

  it('uses a probe that a validator can reject: a broken type form is not valid', () => {
    const broken = new Uint8Array([0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00, 0x01, 0x03, 0x01, 0x5e, 0x00]);
    expect(WebAssembly.validate(broken)).toBe(false);
  });
});

describe('registry', () => {
  it('lists the C++ engine first, then Java Wasm GC, then Java JS, whatever the registration order', () => {
    registerEngine(def('java-js', 'js'));
    registerEngine(def('java-wasmgc', 'wasm-gc'));
    registerEngine(def('cpp-wasm', 'wasm'));
    expect(listDefinitions().map((d) => d.id)).toEqual(['cpp-wasm', 'java-wasmgc', 'java-js']);
  });

  it('marks an engine unavailable when the browser lacks the feature, and one the build did not ship', () => {
    registerEngine(def('cpp-wasm', 'wasm'));
    registerEngine(def('java-wasmgc', 'wasm-gc', false));
    registerEngine(def('java-js', 'js'));
    const byId = Object.fromEntries(describeEngines(manifest).map((c) => [c.info.id, c]));
    expect(byId['cpp-wasm']).toMatchObject({ available: true });
    expect(byId['java-wasmgc']).toMatchObject({ available: false, reason: 'unsupported' });
    expect(byId['java-js']).toMatchObject({ available: false, reason: 'not-shipped' });
  });

  it('fills the info from the manifest', () => {
    registerEngine(def('cpp-wasm', 'wasm'));
    const [choice] = describeEngines(manifest);
    expect(choice!.info).toMatchObject({ version: 'v0-1-gabc', binaryBytes: 267000, binaryGzipBytes: 101000, language: 'C++' });
  });

  it('offers server engines without shipped files', () => {
    registerEngine(def('server:browser', 'server'));
    expect(describeEngines(manifest)[0]).toMatchObject({ available: true });
  });

  it('refuses to create an unavailable engine', () => {
    registerEngine(def('java-js', 'js'));
    const [choice] = describeEngines(manifest);
    expect(() => createEngine(choice!, manifest, '/')).toThrow(/not available \(not-shipped\)/);
  });

  it('creates an available engine with the files of the manifest', () => {
    let seen: unknown;
    registerEngine({ ...def('cpp-wasm', 'wasm'), create: (ctx) => { seen = ctx; return stubEngine; } });
    createEngine(describeEngines(manifest)[0]!, manifest, '/marble-lantern/');
    expect(seen).toMatchObject({ baseUrl: '/marble-lantern/', files: [file] });
  });
});

describe('loadEnginesManifest', () => {
  const respond = (body: unknown, status = 200): typeof fetch => (async () => new Response(JSON.stringify(body), { status })) as typeof fetch;

  it('reads a valid file', async () => {
    expect(await loadEnginesManifest('engines/engines.json', respond(manifest))).toEqual(manifest);
  });

  it('rejects HTTP errors and wrong shapes', async () => {
    await expect(loadEnginesManifest('x', respond({}, 404))).rejects.toThrow(/HTTP 404/);
    await expect(loadEnginesManifest('x', respond({ v: 2, version: 'a', engines: {} }))).rejects.toThrow(/unexpected shape/);
    await expect(loadEnginesManifest('x', respond({ v: 1, engines: {} }))).rejects.toThrow(/unexpected shape/);
  });
});
