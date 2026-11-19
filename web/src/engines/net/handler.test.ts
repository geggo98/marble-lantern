import { describe, expect, it } from 'vitest';
import { EngineFailure } from '../protocol';
import type { Request } from '../protocol';
import { NetRequestHandler, pickNetAssets } from './handler';
import type { NetCoreDeps } from './core';
import type { ModelInfo } from './models';
import type { WebNetModule } from './webnet.d';

const urls = (...names: string[]): string[] => names.map((n) => `/app/engines/net/${n}`);
const FLAVOURS = ['scalar', 'simd', 'relaxed'].flatMap((f) => [`engine-net-nn-${f}.js`, `engine-net-nn-${f}.wasm`]);

describe('pickNetAssets', () => {
  it('finds the facade by exact file name, not the backend glue files that end in .js and .wasm too', () => {
    const a = pickNetAssets(urls(...FLAVOURS, 'engine-net.js', 'engine-net.wasm', 'engine-net.wasm-runtime.js'));
    expect(a.facade).toEqual({ target: 'wasm-gc', main: '/app/engines/net/engine-net.wasm', runtime: '/app/engines/net/engine-net.wasm-runtime.js' });
    expect(a.facadeJs).toEqual({ target: 'js', main: '/app/engines/net/engine-net.js' });
    expect(Object.keys(a.flavours).sort()).toEqual(['relaxed', 'scalar', 'simd']);
    expect(a.flavours.simd).toBe('/app/engines/net/engine-net-nn-simd.js');
  });

  it('uses the JavaScript facade when the Wasm GC files are not shipped', () => {
    const a = pickNetAssets(urls('engine-net.js'));
    expect(a.facade).toEqual({ target: 'js', main: '/app/engines/net/engine-net.js' });
    expect(a.facadeJs).toBeUndefined();
  });

  it('refuses a set without the facade (the backend files alone are no engine)', () => {
    expect(() => pickNetAssets(urls(...FLAVOURS))).toThrowError(expect.objectContaining({ code: 'internal' }));
  });
});

function handlerWith(load: (target: string) => Promise<WebNetModule>, coreDeps: Partial<NetCoreDeps> = {}) {
  const loaded: string[] = [];
  const web = {
    legalMoves: () => 'ok 5,6',
    outcome: () => 'ok ongoing',
  } as unknown as WebNetModule;
  const handler = new NetRequestHandler({
    loadModule: async (a) => { loaded.push(a.target); return load(a.target); },
    makeDeps: (webnet) => ({ webnet, models: [], createBackend: async () => { throw new Error('unused'); }, probeWebGpu: async () => ({ available: false }), loadModel: async () => new Uint8Array(), random: () => 0.5, seed: () => 1, ...coreDeps }),
  });
  return { handler, loaded, web };
}

const init = (engine: string[], backend?: 'wasm' | 'webgpu'): Request => ({ id: 1, type: 'init', assets: { engine, manifest: '', tableGz: '' }, table: null, ...(backend ? { backend } : {}) });

describe('NetRequestHandler', () => {
  it('init loads the facade and reports a load report; newGame without a model answers with the warnings', async () => {
    const { handler, web } = handlerWith(async () => web2());
    function web2(): WebNetModule { return web; }
    const report = await handler.handle(init(urls('engine-net.js')));
    expect(report).toMatchObject({ tableVerifiedBy: 'length-only', fetchMs: 0 });
    const reply = await handler.handle({ id: 2, type: 'newGame', setup: { nodes: [1, 1] } });
    expect((reply as { warnings: Array<{ code: string }> }).warnings.map((w) => w.code)).toEqual(['no-model', 'fallback-random']);
    expect(await handler.handle({ id: 3, type: 'legalMoves', history: [] })).toEqual([5, 6]);
    expect(await handler.handle({ id: 4, type: 'outcome', history: [] })).toEqual({ kind: 'ongoing' });
    const move = (await handler.handle({ id: 5, type: 'choose', history: [] })) as { move: number; net: { backend: string } };
    expect([5, 6]).toContain(move.move);
    expect(move.net.backend).toBe('random');
  });

  it('falls back to the JavaScript facade when the Wasm GC build cannot be loaded', async () => {
    const { handler, loaded, web } = handlerWith(async (target) => {
      if (target === 'wasm-gc') throw new EngineFailure('wasm-gc-unsupported', 'no GC');
      return web2();
    });
    function web2(): WebNetModule { return web; }
    await handler.handle(init(urls('engine-net.wasm', 'engine-net.wasm-runtime.js', 'engine-net.js')));
    expect(loaded).toEqual(['wasm-gc', 'js']);
  });

  it('does not hide other load errors behind the JavaScript fallback', async () => {
    const { handler, loaded } = handlerWith(async () => { throw new Error('boom'); });
    await expect(handler.handle(init(urls('engine-net.wasm', 'engine-net.wasm-runtime.js', 'engine-net.js')))).rejects.toThrow('boom');
    expect(loaded).toEqual(['wasm-gc']);
  });

  it('refuses a request before init, analyse with unsupported and the benchmark game with unsupported', async () => {
    const { handler, web } = handlerWith(async () => web2());
    function web2(): WebNetModule { return web; }
    await expect(handler.handle({ id: 1, type: 'choose', history: [] })).rejects.toMatchObject({ code: 'internal' });
    await handler.handle(init(urls('engine-net.js')));
    await expect(handler.handle({ id: 2, type: 'analyse', history: [], moves: null, nodes: 10 })).rejects.toMatchObject({ code: 'unsupported' });
    await expect(handler.handle({ id: 3, type: 'bench', plies: 1, nodes: 1 })).rejects.toMatchObject({ code: 'unsupported' });
  });

  it('wants the CPU backend unless init names WebGPU', async () => {
    for (const [backend, expected] of [[undefined, 'wasm'], ['wasm', 'wasm'], ['webgpu', 'webgpu']] as const) {
      const created: string[] = [];
      const web = {
        create: () => 'ok', setWeightsInfo: () => 'ok 1 2', legalMoves: () => 'ok 5',
      } as unknown as WebNetModule;
      const model = { id: 'm', variants: [{ jump: 'adjacent', players: 2 }], planeSet: 'classic1' } as unknown as ModelInfo;
      const handler = new NetRequestHandler({
        loadModule: async () => web,
        makeDeps: (webnet) => ({
          webnet, models: [model], random: () => 0, seed: () => 1,
          createBackend: async (kind) => { created.push(kind); return { kind, planes: 13, valueWidth: 2, load: async () => undefined, evaluate: async () => { throw new Error('unused'); }, dispose: () => undefined }; },
          probeWebGpu: async () => ({ available: true }),
          loadModel: async () => new Uint8Array([1]),
        }),
      });
      await handler.handle(init(urls('engine-net.js'), backend));
      const reply = (await handler.handle({ id: 2, type: 'newGame', setup: { nodes: [1, 1], model: 'm' } })) as { warnings: unknown[] };
      expect(reply.warnings).toEqual([]);
      expect(created).toEqual([expected]);
    }
  });
});
