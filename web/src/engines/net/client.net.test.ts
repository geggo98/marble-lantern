// The worker client with a net worker: the init request names the backend, newGame carries model and options, warnings flow from
// the replies, and the classical requests stay byte-for-byte what they were.
import { describe, expect, it } from 'vitest';
import { WorkerEngine } from '../client';
import type { WorkerLike } from '../client';
import { FAKE_INFO } from '../testing/fake-engine';
import type { Request, WorkerOut } from '../protocol';
import type { EngineWarning } from '../warnings';

const assets = { engine: ['engine-net.js'], manifest: '', tableGz: '' };
const REPORT = { totalMs: 1, fetchMs: 0, inflateMs: 0, verifyMs: 0, instantiateMs: 0, tableInstallMs: 0, tableVerifiedBy: 'length-only' };

class ManualWorker implements WorkerLike {
  onmessage: ((event: { data: WorkerOut }) => void) | null = null;
  onerror: ((event: { message?: string }) => void) | null = null;
  requests: Request[] = [];
  terminated = false;
  postMessage(m: Request): void { this.requests.push(m); }
  terminate(): void { this.terminated = true; }
  reply(id: number, result: unknown): void { this.onmessage?.({ data: { id, ok: true, result } }); }
  last(): Request { return this.requests[this.requests.length - 1] as Request; }
}

const flush = (): Promise<void> => new Promise((resolve) => setTimeout(resolve, 0));

const NO_MODEL: EngineWarning[] = [{ code: 'no-model' }, { code: 'fallback-random' }];

async function ready(backend?: 'wasm' | 'webgpu') {
  const w = new ManualWorker();
  const engine = new WorkerEngine({ info: FAKE_INFO, assets, createWorker: () => w, ...(backend ? { backend } : {}) });
  const init = engine.init();
  w.reply(w.last().id, REPORT);
  await init;
  return { w, engine };
}

describe('WorkerEngine with a net worker', () => {
  it('puts the backend into the init request, and leaves the field out for the classical engines', async () => {
    const net = await ready('webgpu');
    expect(net.w.requests[0]).toMatchObject({ type: 'init', backend: 'webgpu' });
    const classical = await ready();
    expect('backend' in classical.w.requests[0]!).toBe(false);
  });

  it('sends model and net options with newGame, and the setup is untouched when there are none', async () => {
    const { w, engine } = await ready('wasm');
    const p = engine.newGame({ nodes: [1, 1], model: 'm', netOptions: { sims: 50, batch: 1 } });
    await flush();
    expect(w.last()).toMatchObject({ type: 'newGame', setup: { nodes: [1, 1], model: 'm', netOptions: { sims: 50, batch: 1 } } });
    w.reply(w.last().id, { warnings: [] });
    await p;
    const q = engine.newGame({ nodes: [5000, 5000] });
    await flush();
    expect(JSON.stringify((w.last() as { setup: unknown }).setup)).toBe('{"nodes":[5000,5000]}');
    w.reply(w.last().id, null);
    await q;
  });

  it('takes the warnings of newGame and tells the subscribers, once per change', async () => {
    const { w, engine } = await ready('wasm');
    let calls = 0;
    const off = engine.subscribeWarnings(() => calls++);
    expect(engine.warnings).toEqual([]);
    const p = engine.newGame({ nodes: [1, 1] });
    await flush();
    w.reply(w.last().id, { warnings: NO_MODEL });
    await p;
    expect(engine.warnings).toEqual(NO_MODEL);
    expect(calls).toBe(1);
    const q = engine.newGame({ nodes: [1, 1] });
    await flush();
    w.reply(w.last().id, { warnings: NO_MODEL });
    await q;
    expect(calls).toBe(1); // same warnings: no notification
    const r = engine.newGame({ nodes: [1, 1], model: 'm' });
    await flush();
    w.reply(w.last().id, { warnings: [] });
    await r;
    expect(engine.warnings).toEqual([]);
    expect(calls).toBe(2);
    off();
    const s = engine.newGame({ nodes: [1, 1] });
    await flush();
    w.reply(w.last().id, { warnings: NO_MODEL });
    await s;
    expect(calls).toBe(2);
  });

  it('takes the warnings of a choose result (a device loss during the move)', async () => {
    const { w, engine } = await ready('webgpu');
    const g = engine.newGame({ nodes: [1, 1] });
    await flush();
    w.reply(w.last().id, { warnings: [] });
    await g;
    const c = engine.choose([1]);
    await flush();
    const lost: EngineWarning[] = [{ code: 'backend-unavailable', backend: 'webgpu', fallback: 'wasm' }];
    w.reply(w.last().id, { move: 3, score: 0, depth: 0, nodes: 1, ms: 1, raceMove: false, rows1Requests: 0, warnings: lost });
    await c;
    expect(engine.warnings).toEqual(lost);
  });

  it('a classical reply (null for newGame, no warnings in choose) leaves the warnings empty', async () => {
    const { w, engine } = await ready();
    const g = engine.newGame({ nodes: [1, 1] });
    await flush();
    w.reply(w.last().id, null);
    await g;
    expect(engine.warnings).toEqual([]);
  });
});
