import { describe, expect, it, vi } from 'vitest';
import { ServerEngineAdapter, ServerError, toServerError } from './client';
import type { ServerApi, ServerChoice, ServerEngineEntry } from './client';
import { registerServerEngines } from './load';
import { clearEngines, listDefinitions } from '../registry';

const entry: ServerEngineEntry = { id: 'browser', labelKey: 'engine.serverBrowser', backend: 'jvm' };
const choice: ServerChoice = { move: 5 | (9 << 8), score: 12, depth: 4, nodes: 99, ms: 3, raceMove: false, rows1Requests: 0 };

function api(over: Partial<ServerApi> = {}): ServerApi {
  return {
    engines: vi.fn(async () => [entry]),
    legalMoves: vi.fn(async () => [1, 2]),
    choose: vi.fn(async () => choice),
    ...over,
  };
}

class ConnectionFailureError extends Error {
  constructor() {
    super('no answer');
    this.name = 'ConnectionFailureError';
  }
}

describe('ServerEngineAdapter', () => {
  it('has no analysis yet and says so with the code "unsupported"', async () => {
    await expect(new ServerEngineAdapter(entry, api()).analyse([], null, 100)).rejects.toMatchObject({ code: 'unsupported' });
  });

  it('describes itself as a server engine with the server prefix', () => {
    const info = new ServerEngineAdapter(entry, api()).info;
    expect(info).toMatchObject({ id: 'server:browser', runtime: 'server', language: 'Java', labelKey: 'engine.serverBrowser', version: 'jvm' });
  });

  it('init succeeds when the server lists the engine and fails with internal when it does not', async () => {
    const ok = await new ServerEngineAdapter(entry, api()).init();
    expect(ok.tableVerifiedBy).toBe('engine');
    const missing = new ServerEngineAdapter({ ...entry, id: 'best' }, api());
    await expect(missing.init()).rejects.toMatchObject({ code: 'internal' });
  });

  it('init maps a dead server to server-unreachable', async () => {
    const dead = api({ engines: async () => { throw new ConnectionFailureError(); } });
    await expect(new ServerEngineAdapter(entry, dead).init()).rejects.toMatchObject({ code: 'server-unreachable' });
  });

  it('choose sends the node budget of the side to move: even history is side 0, odd is side 1', async () => {
    const a = api();
    const engine = new ServerEngineAdapter(entry, a);
    await engine.newGame({ nodes: [111, 222] });
    await engine.choose([]);
    await engine.choose([7]);
    expect(a.choose).toHaveBeenNthCalledWith(1, 'browser', [], 111, undefined);
    expect(a.choose).toHaveBeenNthCalledWith(2, 'browser', [7], 222, undefined);
  });

  it('choose returns the server result unchanged and hands the signal on', async () => {
    const a = api();
    const engine = new ServerEngineAdapter(entry, a);
    const signal = new AbortController().signal;
    expect(await engine.choose([], signal)).toEqual(choice);
    expect(a.choose).toHaveBeenCalledWith('browser', [], 20_000, { signal });
  });

  it('an abort stays an AbortError and is not turned into a server error', async () => {
    const abort = new DOMException('stopped', 'AbortError');
    const engine = new ServerEngineAdapter(entry, api({ choose: async () => { throw abort; } }));
    await expect(engine.choose([])).rejects.toBe(abort);
  });

  it('legalMoves passes a copy of the history and maps an illegal-history message to its code', async () => {
    const a = api();
    const engine = new ServerEngineAdapter(entry, a);
    const history = [3];
    expect(await engine.legalMoves(history)).toEqual([1, 2]);
    expect((a.legalMoves as ReturnType<typeof vi.fn>).mock.calls[0]?.[0]).not.toBe(history);
    const bad = api({ legalMoves: async () => { throw new Error('illegal-history: move 0 at ply 0 is not legal'); } });
    await expect(new ServerEngineAdapter(entry, bad).legalMoves([0])).rejects.toMatchObject({ code: 'illegal-history' });
  });

  it('an empty answer is an internal error', async () => {
    const engine = new ServerEngineAdapter(entry, api({ choose: async () => undefined }));
    await expect(engine.choose([])).rejects.toBeInstanceOf(ServerError);
  });
});

describe('toServerError', () => {
  it('maps a fetch TypeError to server-unreachable and anything else to internal', () => {
    expect(toServerError(new TypeError('Failed to fetch'))).toMatchObject({ code: 'server-unreachable' });
    expect(toServerError(new Error('boom'))).toMatchObject({ code: 'internal', message: 'boom' });
    expect(toServerError('text')).toMatchObject({ code: 'internal', message: 'text' });
  });
});

describe('registerServerEngines', () => {
  it('registers nothing in the offline build, where __ONLINE__ is false', async () => {
    clearEngines();
    await registerServerEngines();
    expect(listDefinitions()).toEqual([]);
  });
});
