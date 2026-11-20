// @vitest-environment jsdom
// The fading trail: storage of the setting and the timer logic of useTrailPhase (fake timers).
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import type { Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  TRAIL_DEFAULT_SECONDS,
  TRAIL_FADE_MS,
  TRAIL_KEY,
  loadTrailSeconds,
  saveTrailSeconds,
  useTrailPhase,
} from './trail-fade';
import type { TrailPhase } from './trail-fade';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const memory = (initial: Record<string, string> = {}) => {
  const data = { ...initial };
  return { data, getItem: (k: string) => data[k] ?? null, setItem: (k: string, v: string) => { data[k] = v; } };
};
const blocked = {
  getItem: () => { throw new Error('blocked'); },
  setItem: () => { throw new Error('blocked'); },
};

describe('trail setting storage', () => {
  it('is 3 s by default, also without or with blocked storage', () => {
    expect(TRAIL_DEFAULT_SECONDS).toBe(3);
    expect(loadTrailSeconds(memory())).toBe(3);
    expect(loadTrailSeconds(null)).toBe(3);
    expect(loadTrailSeconds(blocked)).toBe(3);
  });
  it('round-trips 0 (always visible) and 10', () => {
    const store = memory();
    saveTrailSeconds(0, store);
    expect(store.data[TRAIL_KEY]).toBe('0');
    expect(loadTrailSeconds(store)).toBe(0);
    saveTrailSeconds(10, store);
    expect(loadTrailSeconds(store)).toBe(10);
  });
  it('clamps on save and falls back to the default for a stored value it does not know', () => {
    const store = memory();
    saveTrailSeconds(99, store);
    expect(store.data[TRAIL_KEY]).toBe('10');
    saveTrailSeconds(-4, store);
    expect(store.data[TRAIL_KEY]).toBe('0');
    for (const bad of ['11', '-1', '2.5', 'abc', '', ' 3']) expect(loadTrailSeconds(memory({ [TRAIL_KEY]: bad }))).toBe(3);
  });
  it('survives blocked storage on write', () => {
    expect(() => saveTrailSeconds(5, blocked)).not.toThrow();
  });
});

let host: HTMLElement;
let root: Root;
let phase: TrailPhase;
const normal = (): boolean => false;
const reduced = (): boolean => true;

function Probe(p: { plies: number; seconds: number; reducedMotion: () => boolean }): null {
  phase = useTrailPhase(p.plies, p.seconds, p.reducedMotion);
  return null;
}
async function draw(plies: number, seconds: number, reducedMotion: () => boolean = normal): Promise<void> {
  await act(async () => {
    root.render(<Probe plies={plies} seconds={seconds} reducedMotion={reducedMotion} />);
  });
}
const advance = (ms: number) =>
  act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
const remount = (): void => {
  act(() => root.unmount());
  root = createRoot(host);
};

beforeEach(() => {
  vi.useFakeTimers();
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
});
afterEach(() => {
  act(() => root.unmount());
  host.remove();
  vi.useRealTimers();
});

describe('useTrailPhase', () => {
  it('is shown for the set time, then fades for TRAIL_FADE_MS, then is gone', async () => {
    await draw(1, 3);
    expect(phase).toBe('shown');
    await advance(2999);
    expect(phase).toBe('shown');
    await advance(1);
    expect(phase).toBe('fading');
    await advance(TRAIL_FADE_MS - 1);
    expect(phase).toBe('fading');
    await advance(1);
    expect(phase).toBe('gone');
  });

  it('stays shown forever with 0 seconds', async () => {
    await draw(1, 0);
    await advance(60_000);
    expect(phase).toBe('shown');
  });

  it('a new move restarts the timer and cancels the old one', async () => {
    await draw(1, 3);
    await advance(2500);
    await draw(2, 3);
    await advance(2500); // 5 s since the first move, 2.5 s since the second
    expect(phase).toBe('shown');
    await advance(500);
    expect(phase).toBe('fading');
  });

  it('a new move while fading shows the trail again', async () => {
    await draw(1, 1);
    await advance(1100);
    expect(phase).toBe('fading');
    await draw(2, 1);
    expect(phase).toBe('shown');
    await advance(TRAIL_FADE_MS + 100); // the old fade timer must not hide the new trail early
    expect(phase).toBe('shown');
    await advance(1000);
    expect(phase).toBe('gone');
  });

  it('undo, new game and navigation (plies does not grow by one) clear it at once', async () => {
    for (const next of [4, 0, 9]) {
      await draw(5, 3);
      expect(phase).toBe('shown');
      await draw(next, 3);
      expect(phase).toBe('gone');
      await advance(10_000);
      expect(phase).toBe('gone');
      remount();
    }
  });

  it('after a clear the next move shows it again', async () => {
    await draw(5, 3);
    await draw(4, 3);
    expect(phase).toBe('gone');
    await draw(5, 3);
    expect(phase).toBe('shown');
  });

  it('with reduced motion there is no fading phase: it is gone after the time', async () => {
    await draw(1, 2, reduced);
    const seen: TrailPhase[] = [];
    for (let i = 0; i < 25; i++) {
      await advance(100);
      seen.push(phase);
    }
    expect(seen).not.toContain('fading');
    expect(seen[18]).toBe('shown'); // 1.9 s
    expect(seen[19]).toBe('gone'); // 2.0 s
  });

  it('a change of the setting restarts a visible trail and leaves a hidden one hidden', async () => {
    await draw(1, 5);
    await advance(2000);
    await draw(1, 1);
    expect(phase).toBe('shown');
    await advance(1000);
    expect(phase).toBe('fading');
    await advance(TRAIL_FADE_MS);
    expect(phase).toBe('gone');
    await draw(1, 3);
    expect(phase).toBe('gone');
    await draw(1, 0);
    expect(phase).toBe('shown');
  });

  it('leaves no timer behind after unmount', async () => {
    await draw(1, 3);
    expect(vi.getTimerCount()).toBe(1);
    act(() => root.unmount());
    expect(vi.getTimerCount()).toBe(0);
    root = createRoot(host);
  });

  it('keeps at most one timer and none once gone', async () => {
    await draw(1, 1);
    await advance(1000);
    expect(vi.getTimerCount()).toBe(1); // the fade timer
    await advance(TRAIL_FADE_MS);
    expect(vi.getTimerCount()).toBe(0);
  });
});
