// @vitest-environment jsdom
// The live run state machine with fake engines: progress, done, stop, unmount, the cache per snapshot id, failures.
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import type { Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { WorkerEngine } from '../../engines/client';
import { FAKE_INFO, FakeEngineCore, FakeWorker } from '../../engines/testing/fake-engine';
import { DEFAULT_VARIANT } from '../../game/variant';
import { LiveProvider } from './context';
import { LiveError } from './engines';
import { useLiveRun } from './useLiveRun';
import type { LiveRunFn, UseLiveRun } from './useLiveRun';
import type { LiveInput } from './types';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

let host: HTMLElement;
let root: Root;
const workers: FakeWorker[] = [];

/** Sources whose classical engine is a real WorkerEngine on a fake worker; `workers` lists every worker that was started. */
const sources = () => ({
  classical: {
    id: 'cpp-wasm' as const,
    create: () =>
      new WorkerEngine({
        info: FAKE_INFO,
        assets: { engine: [], manifest: '', tableGz: '' },
        createWorker: () => {
          const w = new FakeWorker(new FakeEngineCore());
          workers.push(w);
          return w;
        },
      }),
  },
  net: null,
});

const input = (id: number): LiveInput => ({ snapshot: { id, history: [], variant: DEFAULT_VARIANT, ply: 0, over: false }, sources });

let hook: UseLiveRun<string, string>;
function Probe({ run }: { run: LiveRunFn<string, string> }): null {
  hook = useLiveRun<string, string>('fig', run);
  return null;
}

const mount = async (run: LiveRunFn<string, string>, id = 1): Promise<void> => {
  await act(async () => root.render(<LiveProvider input={input(id)}><Probe run={run} /></LiveProvider>));
};
const settle = async (): Promise<void> => {
  await act(async () => {
    await new Promise((r) => setTimeout(r, 0));
  });
};

beforeEach(() => {
  workers.length = 0;
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
});
afterEach(() => {
  act(() => root.unmount());
  host.remove();
});

/** A promise that the test settles by hand. */
function gate<T>(): { promise: Promise<T>; resolve: (v: T) => void; reject: (e: unknown) => void } {
  let resolve!: (v: T) => void;
  let reject!: (e: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

describe('useLiveRun', () => {
  it('goes idle -> starting(progress) -> running(done, total, partial) -> done(value)', async () => {
    const g = gate<string>();
    await mount(async (_engines, _signal, report) => {
      report.progress({ phase: 'fetch', loaded: 1, total: 4 });
      report.step(1, 3, 'one');
      return g.promise;
    });
    expect(hook.state).toEqual({ kind: 'idle' });
    await act(async () => hook.start());
    await settle();
    expect(hook.state).toEqual({ kind: 'running', done: 1, total: 3, partial: 'one' });
    await act(async () => g.resolve('result'));
    expect(hook.state).toEqual({ kind: 'done', value: 'result' });
  });

  it('shows starting with the progress of the engine load before the first step', async () => {
    const g = gate<string>();
    await mount(async (_engines, _signal, report) => {
      report.progress({ phase: 'inflate', loaded: 2, total: 5 });
      return g.promise;
    });
    await act(async () => hook.start());
    await settle();
    expect(hook.state).toEqual({ kind: 'starting', progress: { phase: 'inflate', loaded: 2, total: 5 } });
  });

  it('stop fires the signal and ends in stopped; a result that arrives later is ignored', async () => {
    const g = gate<string>();
    let signal!: AbortSignal;
    await mount(async (_engines, s) => {
      signal = s;
      return g.promise;
    });
    await act(async () => hook.start());
    await settle();
    expect(signal.aborted).toBe(false);
    await act(async () => hook.stop());
    expect(signal.aborted).toBe(true);
    expect(hook.state).toEqual({ kind: 'stopped' });
    await act(async () => g.resolve('late'));
    expect(hook.state).toEqual({ kind: 'stopped' });
    // the next press starts again
    await act(async () => hook.start());
    await settle();
    expect(hook.state.kind).not.toBe('stopped');
  });

  it('an AbortError that follows a stop does not turn into a failure', async () => {
    const g = gate<string>();
    await mount(async () => g.promise);
    await act(async () => hook.start());
    await settle();
    await act(async () => hook.stop());
    await act(async () => g.reject(new DOMException('aborted', 'AbortError')));
    expect(hook.state).toEqual({ kind: 'stopped' });
  });

  it('unmount aborts the run and disposes the engine worker that the run started', async () => {
    const g = gate<string>();
    let signal!: AbortSignal;
    await mount(async (engines, s) => {
      signal = s;
      await engines.classical();
      return g.promise;
    });
    await act(async () => hook.start());
    await settle();
    expect(workers).toHaveLength(1);
    expect(workers[0]!.terminated).toBe(false);
    await act(async () => root.render(<div />));
    expect(signal.aborted).toBe(true);
    expect(workers[0]!.terminated).toBe(true);
  });

  it('keeps the result per figure and snapshot id: the same id does not run again, a new id does', async () => {
    let runs = 0;
    const run: LiveRunFn<string, string> = async () => `run ${++runs}`;
    await mount(run, 7);
    await act(async () => hook.start());
    await settle();
    expect(hook.state).toEqual({ kind: 'done', value: 'run 1' });
    await act(async () => hook.reset()); // the reader switches back to the example
    expect(hook.state).toEqual({ kind: 'idle' });
    await act(async () => hook.start()); // ... and to My game again
    expect(hook.state).toEqual({ kind: 'done', value: 'run 1' });
    expect(runs).toBe(1);
    await mount(run, 8); // a new press of "Explain this position"
    expect(hook.state).toEqual({ kind: 'idle' });
    await act(async () => hook.start());
    await settle();
    expect(hook.state).toEqual({ kind: 'done', value: 'run 2' });
    expect(runs).toBe(2);
  });

  it('maps a LiveError to its code and anything else to internal', async () => {
    await mount(async () => {
      throw new LiveError('model', 'no model');
    });
    await act(async () => hook.start());
    await settle();
    expect(hook.state).toEqual({ kind: 'failed', code: 'model' });
    await mount(async () => {
      throw new Error('boom');
    });
    await act(async () => hook.start());
    await settle();
    expect(hook.state).toEqual({ kind: 'failed', code: 'internal' });
  });

  it('does nothing without a snapshot', async () => {
    let runs = 0;
    await act(async () => root.render(<LiveProvider input={null}><Probe run={async () => `run ${++runs}`} /></LiveProvider>));
    await act(async () => hook.start());
    await settle();
    expect(runs).toBe(0);
    expect(hook.state).toEqual({ kind: 'idle' });
  });
});
