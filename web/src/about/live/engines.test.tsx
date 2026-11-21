// @vitest-environment jsdom
// The Info engine holder: nothing starts before the first call (not at page load, not when the provider mounts), one engine per kind,
// dispose on unmount, a failed start is not cached.
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Engine } from '../../engines/engine';
import { WorkerEngine } from '../../engines/client';
import { EngineFailure } from '../../engines/protocol';
import { FAKE_INFO, FakeEngineCore, FakeWorker } from '../../engines/testing/fake-engine';
import { LiveProvider } from './context';
import { LiveEngines, LiveError } from './engines';
import type { LiveInput, LiveSources } from './types';
import { DEFAULT_VARIANT } from '../../game/variant';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

/** Sources that count creations; `worker` lists the fake worker of every engine that was created and initialised. */
function counting(options: { failInit?: EngineFailure } = {}) {
  const made = { classical: 0, net: 0 };
  const workers: Record<'classical' | 'net', FakeWorker[]> = { classical: [], net: [] };
  const make = (kind: 'classical' | 'net', id: 'cpp-wasm' | 'net-wasm') => ({
    id,
    create: (): Engine => {
      made[kind]++;
      return new WorkerEngine({
        info: FAKE_INFO,
        assets: { engine: [], manifest: '', tableGz: '' },
        createWorker: () => {
          const w = new FakeWorker(new FakeEngineCore(), options.failInit ? { initError: options.failInit } : {});
          workers[kind].push(w);
          return w;
        },
      });
    },
  });
  const sources = (): LiveSources => ({ classical: make('classical', 'cpp-wasm'), net: make('net', 'net-wasm') });
  return { made, workers, sources };
}

afterEach(() => vi.unstubAllGlobals());

describe('LiveEngines', () => {
  it('creates no engine before the first classical() or net() call, and only the kind that was asked for', async () => {
    const c = counting();
    const holder = new LiveEngines(c.sources);
    expect(c.made).toEqual({ classical: 0, net: 0 });
    expect(holder.started('classical')).toBe(false);
    await holder.classical();
    expect(c.made).toEqual({ classical: 1, net: 0 });
    expect(holder.started('net')).toBe(false);
    await holder.net();
    expect(c.made).toEqual({ classical: 1, net: 1 });
  });

  it('shares one engine between callers, also while it still loads', async () => {
    const c = counting();
    const holder = new LiveEngines(c.sources);
    const [a, b] = await Promise.all([holder.classical(), holder.classical()]);
    expect(a).toBe(b);
    expect(await holder.classical()).toBe(a);
    expect(c.made.classical).toBe(1);
  });

  it('dispose terminates the workers; the holder then makes a new engine on demand', async () => {
    const c = counting();
    const holder = new LiveEngines(c.sources);
    const first = await holder.classical();
    holder.dispose();
    await Promise.resolve();
    expect(c.workers.classical[0]!.terminated).toBe(true);
    expect(holder.started('classical')).toBe(false);
    expect(await holder.classical()).not.toBe(first);
    expect(c.made.classical).toBe(2);
  });

  it('an engine that finishes loading after dispose is disposed at once', async () => {
    const c = counting();
    const holder = new LiveEngines(c.sources);
    const loading = holder.classical();
    holder.dispose();
    await expect(loading).rejects.toMatchObject({ code: 'internal' });
    expect(c.workers.classical[0]!.terminated).toBe(true);
  });

  it('a failed start rejects with a LiveError, disposes the engine and is not cached', async () => {
    const c = counting({ failInit: new EngineFailure('internal', 'no network') });
    const holder = new LiveEngines(c.sources);
    await expect(holder.net()).rejects.toMatchObject({ name: 'LiveError', code: 'load' });
    expect(c.workers.net[0]!.terminated).toBe(true);
    expect(holder.started('net')).toBe(false);
    await expect(holder.net()).rejects.toBeInstanceOf(LiveError);
    expect(c.made.net).toBe(2); // the second press tries again
  });

  it('says offline when the start fails without a network', async () => {
    vi.stubGlobal('navigator', { onLine: false });
    const holder = new LiveEngines(counting({ failInit: new EngineFailure('internal', 'x') }).sources);
    await expect(holder.net()).rejects.toMatchObject({ code: 'offline' });
  });

  it('maps an unsupported engine to the code unsupported', async () => {
    const holder = new LiveEngines(counting({ failInit: new EngineFailure('unsupported', 'x') }).sources);
    await expect(holder.classical()).rejects.toMatchObject({ code: 'unsupported' });
  });

  it('rejects when the source is missing', async () => {
    const holder = new LiveEngines(() => ({ classical: null, net: null }));
    await expect(holder.net()).rejects.toBeInstanceOf(LiveError);
  });
});

describe('LiveProvider', () => {
  it('starts nothing when it mounts, and disposes the engines when the topic page unmounts', async () => {
    const c = counting();
    const input: LiveInput = { snapshot: { id: 1, history: [], variant: DEFAULT_VARIANT, ply: 0, over: false }, sources: c.sources };
    const host = document.createElement('div');
    document.body.appendChild(host);
    const root = createRoot(host);
    let holder: LiveEngines | null = null;
    const { useLive } = await import('./context');
    function Grab(): null {
      holder = useLive().engines;
      return null;
    }
    await act(async () => root.render(<LiveProvider input={input}><Grab /></LiveProvider>));
    expect(c.made).toEqual({ classical: 0, net: 0 });
    await holder!.classical();
    expect(c.workers.classical[0]!.terminated).toBe(false);
    await act(async () => root.unmount());
    expect(c.workers.classical[0]!.terminated).toBe(true);
    host.remove();
  });
});
