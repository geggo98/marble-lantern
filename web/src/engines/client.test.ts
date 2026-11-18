import { describe, expect, it } from 'vitest';
import { WorkerEngine } from './client';
import type { WorkerLike } from './client';
import { FAKE_INFO, FakeEngineCore, FakeWorker } from './testing/fake-engine';
import { EngineFailure } from './protocol';
import type { Request, WorkerOut } from './protocol';
import type { LoadedTable } from './tables';

const assets = { engine: ['engine.wasm'], manifest: 'm.json', tableGz: 't.gz' };

function harness(options: { chooseDelayMs?: number; initError?: EngineFailure; withTable?: boolean } = {}) {
  const workers: FakeWorker[] = [];
  const core = new FakeEngineCore(7);
  let tableLoads = 0;
  const table: LoadedTable = {
    bytes: new Uint8Array([1, 2, 3, 4, 5]).buffer,
    manifest: { v: 1, alg: 'gzip', rawBytes: 5, sha256: '0'.repeat(64), gzBytes: 1, file: 'x' },
    verifiedBy: 'subtle-sha256', fetchMs: 3, inflateMs: 2, verifyMs: 1,
  };
  const engine = new WorkerEngine({
    info: FAKE_INFO,
    assets,
    createWorker: () => {
      const w = new FakeWorker(core, options);
      workers.push(w);
      return w;
    },
    loadTable: options.withTable ? async () => { tableLoads++; return table; } : undefined,
  });
  return { engine, workers, core, tableLoads: () => tableLoads, table };
}

/** A worker that records requests and lets the test answer in any order. */
class ManualWorker implements WorkerLike {
  onmessage: ((event: { data: WorkerOut }) => void) | null = null;
  onerror: ((event: { message?: string }) => void) | null = null;
  requests: Request[] = [];
  terminated = false;
  postMessage(m: Request): void { this.requests.push(m); }
  terminate(): void { this.terminated = true; }
  reply(data: WorkerOut): void { this.onmessage?.({ data }); }
}

describe('WorkerEngine', () => {
  it('matches replies to requests by id, also when they arrive out of order', async () => {
    const w = new ManualWorker();
    const engine = new WorkerEngine({ info: FAKE_INFO, assets, createWorker: () => w });
    const init = engine.init();
    w.reply({ id: w.requests[0]!.id, ok: true, result: { totalMs: 1, fetchMs: 0, inflateMs: 0, verifyMs: 0, instantiateMs: 0, tableInstallMs: 0, tableVerifiedBy: 'engine' } });
    await init;
    const a = engine.legalMoves([]);
    const b = engine.legalMoves([1]);
    await Promise.resolve(); // let both requests reach the worker
    const [ra, rb] = [w.requests[1]!, w.requests[2]!];
    expect(ra.id).not.toBe(rb.id);
    w.reply({ id: rb.id, ok: true, result: [22] });
    w.reply({ id: ra.id, ok: true, result: [11] });
    expect(await a).toEqual([11]);
    expect(await b).toEqual([22]);
  });

  it('maps an error reply to an EngineFailure with the protocol code', async () => {
    const { engine } = harness();
    await engine.init();
    await expect(engine.legalMoves([0])).rejects.toMatchObject({ name: 'EngineFailure', code: 'illegal-history' });
  });

  it('maps a failed init to its code', async () => {
    const { engine } = harness({ initError: new EngineFailure('table-checksum', 'bad') });
    await expect(engine.init()).rejects.toMatchObject({ code: 'table-checksum', message: 'bad' });
  });

  it('refuses calls before init', async () => {
    const { engine } = harness();
    await expect(engine.legalMoves([])).rejects.toMatchObject({ code: 'internal' });
  });

  it('routes progress events to the init callback by request id', async () => {
    const w = new ManualWorker();
    const engine = new WorkerEngine({ info: FAKE_INFO, assets, createWorker: () => w });
    const seen: number[] = [];
    const init = engine.init((p) => seen.push(p.loaded));
    const id = w.requests[0]!.id;
    w.reply({ id: id + 99, type: 'progress', progress: { phase: 'fetch', loaded: 1, total: 3 } }); // unknown id: ignored
    w.reply({ id, type: 'progress', progress: { phase: 'fetch', loaded: 2, total: 3 } });
    w.reply({ id, ok: true, result: { totalMs: 1, fetchMs: 0, inflateMs: 0, verifyMs: 0, instantiateMs: 0, tableInstallMs: 0, tableVerifiedBy: 'engine' } });
    await init;
    expect(seen).toEqual([2]);
  });

  it('loads the table in the main thread and passes a copy to the worker', async () => {
    const { engine, workers, core, table } = harness({ withTable: true });
    const report = await engine.init();
    expect(core.tableReceived).toBe(5);
    expect(workers).toHaveLength(1);
    expect(new Uint8Array(table.bytes)).toEqual(new Uint8Array([1, 2, 3, 4, 5])); // original kept
    expect(report.tableVerifiedBy).toBe('subtle-sha256');
    expect(report.fetchMs).toBe(3);
  });

  describe('abort', () => {
    it('rejects with AbortError, terminates the worker and starts a new one without a second download', async () => {
      const { engine, workers, core, tableLoads } = harness({ withTable: true, chooseDelayMs: 200 });
      await engine.init();
      await engine.newGame({ nodes: [500, 600] });
      const controller = new AbortController();
      const search = engine.choose([], controller.signal);
      await new Promise((r) => setTimeout(r, 0)); // choose reaches the worker after awaiting ready
      controller.abort();
      await expect(search).rejects.toMatchObject({ name: 'AbortError' });
      expect(workers).toHaveLength(2);
      expect(workers[0]!.terminated).toBe(true);
      expect(workers[1]!.terminated).toBe(false);
      expect(tableLoads()).toBe(1); // restart reuses the bytes the main thread kept
      // the new worker got the table again and the game setup
      const next = await engine.choose([]);
      expect(core.initCalls).toBe(2);
      expect(core.tableReceived).toBe(5);
      expect(core.newGameCalls).toBe(2);
      expect(next.nodes).toBe(500); // side 0 budget from the replayed setup
    });

    it('sends init, newGame, choose to the new worker in this order when choose follows an abort at once', async () => {
      const w1 = new ManualWorker();
      const w2 = new ManualWorker();
      const queue = [w1, w2];
      const engine = new WorkerEngine({ info: FAKE_INFO, assets, createWorker: () => queue.shift()! });
      const ok = { totalMs: 1, fetchMs: 0, inflateMs: 0, verifyMs: 0, instantiateMs: 0, tableInstallMs: 0, tableVerifiedBy: 'engine' };
      const init = engine.init();
      w1.reply({ id: w1.requests[0]!.id, ok: true, result: ok });
      await init;
      const game = engine.newGame({ nodes: [500, 600] });
      await Promise.resolve();
      w1.reply({ id: w1.requests[1]!.id, ok: true, result: null });
      await game;
      const controller = new AbortController();
      const search = engine.choose([], controller.signal);
     await new Promise((r) => setTimeout(r, 0)); // choose reaches the worker after awaiting ready
      await Promise.resolve();
      controller.abort();
      await expect(search).rejects.toMatchObject({ name: 'AbortError' });
      const next = engine.choose([]); // before the new worker has answered init
      await new Promise((r) => setTimeout(r, 0));
      expect(w2.requests.map((r) => r.type)).toEqual(['init']);
      w2.reply({ id: w2.requests[0]!.id, ok: true, result: ok });
      await new Promise((r) => setTimeout(r, 0));
      expect(w2.requests.map((r) => r.type)).toEqual(['init', 'newGame']);
      w2.reply({ id: w2.requests[1]!.id, ok: true, result: null });
      await new Promise((r) => setTimeout(r, 0));
      expect(w2.requests.map((r) => r.type)).toEqual(['init', 'newGame', 'choose']);
      w2.reply({ id: w2.requests[2]!.id, ok: true, result: { move: 1 } });
      expect(await next).toEqual({ move: 1 });
    });

    it('rejects choose before init with the code internal', async () => {
      const { engine } = harness();
      await expect(engine.choose([])).rejects.toMatchObject({ code: 'internal' });
    });

    it('rejects at once for a signal that is already aborted, without a restart', async () => {
      const { engine, workers } = harness();
      await engine.init();
      const controller = new AbortController();
      controller.abort();
      await expect(engine.choose([], controller.signal)).rejects.toMatchObject({ name: 'AbortError' });
      expect(workers).toHaveLength(1);
    });

    it('ignores a late reply of the terminated worker', async () => {
      const w1 = new ManualWorker();
      const w2 = new ManualWorker();
      const queue = [w1, w2];
      const engine = new WorkerEngine({ info: FAKE_INFO, assets, createWorker: () => queue.shift()! });
      const ok = { totalMs: 1, fetchMs: 0, inflateMs: 0, verifyMs: 0, instantiateMs: 0, tableInstallMs: 0, tableVerifiedBy: 'engine' };
      const init = engine.init();
      w1.reply({ id: w1.requests[0]!.id, ok: true, result: ok });
      await init;
      const controller = new AbortController();
      const search = engine.choose([], controller.signal);
      await new Promise((r) => setTimeout(r, 0)); // choose reaches the worker after awaiting ready
      const searchId = w1.requests[1]!.id;
      controller.abort();
      await expect(search).rejects.toMatchObject({ name: 'AbortError' });
      expect(w1.terminated).toBe(true);
      w1.reply({ id: searchId, ok: true, result: { move: 1 } }); // must not throw or resolve anything
      // the new worker is initialised, then answers
      expect(w2.requests[0]!.type).toBe('init');
      w2.reply({ id: w2.requests[0]!.id, ok: true, result: ok });
      await Promise.resolve();
      await Promise.resolve();
      const moves = engine.legalMoves([]);
      await new Promise((r) => setTimeout(r, 0));
      const req = w2.requests.at(-1)!;
      expect(req.type).toBe('legalMoves');
      w2.reply({ id: req.id, ok: true, result: [5] });
      expect(await moves).toEqual([5]);
    });

    it('fails later calls with the error when the restart fails', async () => {
      const w1 = new ManualWorker();
      const w2 = new ManualWorker();
      const queue = [w1, w2];
      const engine = new WorkerEngine({ info: FAKE_INFO, assets, createWorker: () => queue.shift()! });
      const ok = { totalMs: 1, fetchMs: 0, inflateMs: 0, verifyMs: 0, instantiateMs: 0, tableInstallMs: 0, tableVerifiedBy: 'engine' };
      const init = engine.init();
      w1.reply({ id: w1.requests[0]!.id, ok: true, result: ok });
      await init;
      const controller = new AbortController();
      const search = engine.choose([], controller.signal);
      await new Promise((r) => setTimeout(r, 0)); // choose reaches the worker after awaiting ready
      controller.abort();
      await expect(search).rejects.toMatchObject({ name: 'AbortError' });
      w2.reply({ id: w2.requests[0]!.id, ok: false, error: { code: 'table-checksum', message: 'bad' } });
      await expect(engine.legalMoves([])).rejects.toMatchObject({ code: 'table-checksum' });
    });
  });

  it('rejects pending requests when the worker reports an error', async () => {
    const w = new ManualWorker();
    const engine = new WorkerEngine({ info: FAKE_INFO, assets, createWorker: () => w });
    const init = engine.init();
    w.onerror?.({ message: 'boom' });
    await expect(init).rejects.toMatchObject({ code: 'internal', message: 'boom' });
  });

  it('terminates on dispose and rejects pending requests', async () => {
    const w = new ManualWorker();
    const engine = new WorkerEngine({ info: FAKE_INFO, assets, createWorker: () => w });
    const init = engine.init();
    engine.dispose();
    await expect(init).rejects.toMatchObject({ code: 'internal' });
    expect(w.terminated).toBe(true);
    await expect(engine.init()).rejects.toMatchObject({ code: 'internal' });
  });
});
