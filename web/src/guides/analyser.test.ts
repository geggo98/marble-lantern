import { describe, expect, it } from 'vitest';
import type { AnalysisResult } from '../engines/engine';
import { makeMove } from '../engines/engine';
import { GUIDE_NODES } from '../engines/analysis';
import { GuideAnalyser } from './analyser';
import type { AnalyseFn } from './analyser';

const move = makeMove(1, 2);
const okResult: AnalysisResult = { moves: [move], scores: [40], position: 40, nodes: 1, ms: 0 };
const abortError = (): Error => new DOMException('aborted', 'AbortError');

/** An analyse function whose calls the test settles by hand. */
function manual() {
  const calls: Array<{
    history: readonly number[];
    moves: readonly number[] | null;
    nodes: number;
    signal: AbortSignal | undefined;
    resolve: (r: AnalysisResult) => void;
    reject: (e: unknown) => void;
  }> = [];
  const fn: AnalyseFn = (history, moves, nodes, signal) =>
    new Promise<AnalysisResult>((resolve, reject) => {
      calls.push({ history, moves, nodes, signal, resolve, reject });
      signal?.addEventListener('abort', () => reject(abortError()));
    });
  return { fn, calls };
}
const tick = (): Promise<void> => new Promise((r) => setTimeout(r, 0));

describe('GuideAnalyser', () => {
  it('analyses all moves with the guide budget, and publishes the view', async () => {
    const m = manual();
    const a = new GuideAnalyser(m.fn);
    a.request('cpp', [5]);
    expect(a.getState().status).toBe('analysing');
    expect(m.calls).toHaveLength(1);
    expect(m.calls[0]!.moves).toBeNull();
    expect(m.calls[0]!.nodes).toBe(GUIDE_NODES);
    m.calls[0]!.resolve(okResult);
    await tick();
    expect(a.getState().status).toBe('ready');
    expect(a.getState().view?.position).toBe(40);
  });

  it('answers from the cache for the same engine and history, and asks again for another engine', async () => {
    const m = manual();
    const a = new GuideAnalyser(m.fn);
    a.request('cpp', [5]);
    m.calls[0]!.resolve(okResult);
    await tick();
    a.request('cpp', [5, 6]);
    a.cancel();
    a.request('cpp', [5]);
    expect(a.getState().status).toBe('ready');
    expect(m.calls).toHaveLength(2); // [5] was not asked again
    a.request('java', [5]);
    expect(m.calls).toHaveLength(3);
  });

  it('does not start a second call for the key that is already running', () => {
    const m = manual();
    const a = new GuideAnalyser(m.fn);
    a.request('cpp', [5]);
    a.request('cpp', [5]);
    expect(m.calls).toHaveLength(1);
  });

  it('aborts the running call when another position is wanted, and ignores its late result', async () => {
    const m = manual();
    const a = new GuideAnalyser(m.fn);
    a.request('cpp', [1]);
    a.request('cpp', [1, 2]);
    expect(m.calls[0]!.signal?.aborted).toBe(true);
    m.calls[0]!.resolve({ ...okResult, position: 999 }); // too late: the abort already rejected it
    m.calls[1]!.resolve(okResult);
    await tick();
    expect(a.getState().view?.position).toBe(40);
    expect(a.getState().key).toBe('cpp:1,2');
  });

  it('cancel() aborts and returns to idle, with no error state', async () => {
    const m = manual();
    const a = new GuideAnalyser(m.fn);
    a.request('cpp', [1]);
    a.cancel();
    await tick();
    expect(m.calls[0]!.signal?.aborted).toBe(true);
    expect(a.getState().status).toBe('idle');
  });

  it('cancel() without a running call does nothing', () => {
    const a = new GuideAnalyser(manual().fn);
    expect(() => a.cancel()).not.toThrow();
    expect(a.getState().status).toBe('idle');
  });

  it('reports "unavailable" for a server engine (code unsupported) and does not ask that engine again', async () => {
    const m = manual();
    const a = new GuideAnalyser(m.fn);
    a.request('server:best', [1]);
    m.calls[0]!.reject(Object.assign(new Error('no analysis'), { code: 'unsupported' }));
    await tick();
    expect(a.getState().status).toBe('unavailable');
    a.request('server:best', [1, 2]);
    expect(a.getState().status).toBe('unavailable');
    expect(m.calls).toHaveLength(1);
    a.request('cpp', [1]);
    expect(a.getState().status).toBe('analysing');
  });

  it('reports "failed" for any other error, once per position, and never throws', async () => {
    const m = manual();
    const a = new GuideAnalyser(m.fn);
    a.request('cpp', [1]);
    m.calls[0]!.reject(new Error('boom'));
    await tick();
    expect(a.getState().status).toBe('failed');
    a.request('cpp', [1]);
    expect(m.calls).toHaveLength(1); // no retry loop
    a.request('cpp', [1, 2]);
    expect(m.calls).toHaveLength(2);
  });

  it('survives a synchronous throw of the engine', () => {
    const a = new GuideAnalyser(() => {
      throw new Error('sync');
    });
    expect(() => a.request('cpp', [1])).not.toThrow();
    expect(a.getState().status).toBe('failed');
  });

  it('notifies subscribers and stops after unsubscribe', async () => {
    const m = manual();
    const a = new GuideAnalyser(m.fn);
    let n = 0;
    const off = a.subscribe(() => n++);
    a.request('cpp', [1]);
    expect(n).toBeGreaterThan(0);
    off();
    const before = n;
    m.calls[0]!.resolve(okResult);
    await tick();
    expect(n).toBe(before);
  });

  it('peek returns a cached view without starting a call', async () => {
    const m = manual();
    const a = new GuideAnalyser(m.fn);
    expect(a.peek('cpp', [1])).toBeNull();
    a.request('cpp', [1]);
    m.calls[0]!.resolve(okResult);
    await tick();
    expect(a.peek('cpp', [1])?.position).toBe(40);
    expect(m.calls).toHaveLength(1);
  });

  it('keeps the cache within its limit, dropping the oldest entry', async () => {
    const m = manual();
    const a = new GuideAnalyser(m.fn, GUIDE_NODES, 2);
    for (const h of [[1], [2], [3]]) {
      a.request('cpp', h);
      m.calls[m.calls.length - 1]!.resolve(okResult);
      await tick();
    }
    expect(a.peek('cpp', [1])).toBeNull();
    expect(a.peek('cpp', [3])).not.toBeNull();
  });
});
