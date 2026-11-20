// @vitest-environment jsdom
// The distance of the result in the whole app, through game links with the fake engine (docs/web-ui.md, "Distance of the result").
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import type { Root } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { PLAY_ON_WIN } from '../../e2e/fixtures';
import { WorkerEngine } from '../engines/client';
import { clearEngines } from '../engines/registry';
import type { EnginesManifest } from '../engines/registry';
import { wonGame2 } from '../engines/testing/won-game';
import { App } from './App';
import { registerFakeEngine } from './dev-fake';
import { FakeNavigation } from './fake-navigation';
import { encodeMoves } from './game-url';

vi.mock('./adapters', () => ({ loadManifest: vi.fn() }));

const file = { path: 'engines/cpp/engine-cpp.wasm', bytes: 1, gzipBytes: 1, sha256: '0'.repeat(64) };
const manifest: EnginesManifest = { v: 1, version: 'test', engines: { 'cpp-wasm': { files: [file], binaryBytes: 1, binaryGzipBytes: 1 } } };
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const TWO_WIN = `#g=v1.${encodeMoves(wonGame2().history)}&h=1&l=2`; // side 0 (an engine) has won; the person on side 1 lost
const THREE_WIN = `#g=v1.${PLAY_ON_WIN.moves}&l=0&p=3&s=hee`; // seat 2 has won

let host: HTMLElement;
let root: Root;
const q = <T extends Element = HTMLElement>(selector: string): T | null => document.querySelector<T>(selector);

async function until(condition: () => boolean, what: string, ms = 8000): Promise<void> {
  const end = Date.now() + ms;
  while (!condition()) {
    if (Date.now() > end) throw new Error(`timeout waiting for: ${what}`);
    await act(async () => {
      await new Promise((r) => setTimeout(r, 5));
    });
  }
}
async function mount(hash: string, wide: boolean): Promise<void> {
  window.matchMedia = ((query: string) => ({
    matches: query.includes('reduced') || (query.includes('max-width') ? !wide : query.includes('min-width') && wide),
    media: query, addEventListener: () => undefined, removeEventListener: () => undefined,
  })) as unknown as typeof window.matchMedia;
  clearEngines();
  registerFakeEngine(0);
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
  await act(async () => {
    root.render(<App manifestLoader={async () => manifest} navigation={new FakeNavigation(hash)} />);
  });
  if (hash.includes('&h=')) {
    // a two-player link asks which side the person continues as; the person is the side to move
    await until(() => q('[data-choice="link-side-to-move"]') !== null, 'the link dialog');
    await act(async () => {
      q('[data-choice="link-side-to-move"]')!.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
    });
  }
}
afterEach(async () => {
  await act(async () => root.unmount());
  host.remove();
  vi.restoreAllMocks();
});
const line = (): HTMLElement | null => q('[data-result-distance]');

describe('the distance of the result', () => {
  it('two players: an "estimating" line, then the number of plies of the losing side, labelled as an estimate', async () => {
    await mount(TWO_WIN, true);
    await until(() => q('.ml-result') !== null, 'the result card');
    await until(() => line()?.getAttribute('data-result-distance') === 'done', 'the estimate');
    expect(line()!.textContent).toMatch(/^The losing side would have needed (about|more than) \d+ more plies of its own to reach its goal \(estimate\)\.$/);
  });

  it('shows "estimating" first', async () => {
    await mount(TWO_WIN, true);
    await until(() => q('.ml-result') !== null, 'the result card');
    // the race yields to the event loop after each ply, so the first line is the waiting text
    expect(line()?.getAttribute('data-result-distance')).toBe('estimating');
    expect(line()!.textContent).toBe('Estimating the distance …');
    await until(() => line()?.getAttribute('data-result-distance') === 'done', 'the estimate');
  });

  it('three players: the engine plays the position out; the line names second place', async () => {
    const spy = vi.spyOn(WorkerEngine.prototype, 'analyse');
    await mount(THREE_WIN, true);
    await until(() => q('.ml-result') !== null, 'the result card');
    await until(() => line()?.getAttribute('data-result-distance') === 'done', 'the estimate');
    expect(line()!.textContent).toMatch(/^The other two sides would have needed (about|more than) \d+ more plies to decide second place \(estimate\)\.$/);
    expect(spy.mock.calls.length).toBeGreaterThan(0);
    expect(spy.mock.calls.every((c) => c[1] === null && c[4]?.players === 3 && c[4].ranking === 'full')).toBe(true);
  });

  it('aborts the playout when the result is closed on a small screen, and shows no line afterwards', async () => {
    const signals: AbortSignal[] = [];
    const real = WorkerEngine.prototype.analyse;
    vi.spyOn(WorkerEngine.prototype, 'analyse').mockImplementation(function (this: WorkerEngine, ...args: Parameters<WorkerEngine['analyse']>) {
      if (args[3]) signals.push(args[3]);
      return real.apply(this, args);
    });
    await mount(THREE_WIN, false);
    await until(() => q('.ml-result') !== null, 'the result card');
    await until(() => signals.length > 0, 'the first playout call');
    const close = q('[data-result-close]')!;
    await act(async () => {
      close.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
    });
    expect(q('.ml-result')).toBeNull();
    expect(signals.every((s) => s.aborted)).toBe(true);
    const count = signals.length;
    await act(async () => {
      await new Promise((r) => setTimeout(r, 50));
    });
    expect(signals).toHaveLength(count); // no further ply is asked for
  });

  it('three players: no line and no error when the engine cannot analyse', async () => {
    vi.spyOn(WorkerEngine.prototype, 'analyse').mockRejectedValue(Object.assign(new Error('no analysis'), { code: 'unsupported' }));
    await mount(THREE_WIN, true);
    await until(() => q('.ml-result') !== null, 'the result card');
    await until(() => line() === null, 'the waiting line goes away');
    expect(q('.ml-result')!.textContent).not.toMatch(/estimate|no analysis/i);
    expect(q('[role="alert"]')).toBeNull();
  });
});
