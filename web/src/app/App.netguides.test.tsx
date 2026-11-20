// @vitest-environment jsdom
// Move guides while a net engine plays (docs/web-ui.md, "Analysis for the net engines"). The fake net engine rejects analyse and
// evaluate3 with code unsupported, as the real ones do; the guides, the advantage bar and the skill tracker then get their scores
// from a second, classical engine, and the net engine is never asked.
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import type { Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { WorkerEngine } from '../engines/client';
import { EngineFailure } from '../engines/protocol';
import { clearEngines, registerEngine } from '../engines/registry';
import type { EnginesManifest } from '../engines/registry';
import { FakeEngineCore, FakeWorker } from '../engines/testing/fake-engine';
import en from '../i18n/en.json';
import { App } from './App';
import { NetSelectionStore } from './net-selection';

vi.mock('./adapters', () => ({ loadManifest: vi.fn() }));

const file = { path: 'engines/x.wasm', bytes: 1, gzipBytes: 1, sha256: '0'.repeat(64) };
const entry = { files: [file], binaryBytes: 1, binaryGzipBytes: 1 };
const manifest: EnginesManifest = { v: 1, version: 'test', engines: { 'cpp-wasm': entry, 'net-wasm': entry } };

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

let host: HTMLElement;
let root: Root;
const q = <T extends Element = HTMLElement>(selector: string): T | null => document.querySelector<T>(selector);
const all = (selector: string): Element[] => [...host.querySelectorAll(selector)];

async function until(condition: () => boolean, what: string, ms = 4000): Promise<void> {
  const end = Date.now() + ms;
  while (!condition()) {
    if (Date.now() > end) throw new Error(`timeout waiting for: ${what}`);
    await act(async () => {
      await new Promise((r) => setTimeout(r, 5));
    });
  }
}
const click = async (element: Element | null): Promise<void> => {
  if (!element) throw new Error('no element to click');
  await act(async () => {
    element.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
  });
};
const status = (): string => q('.ml-status__who span')?.textContent ?? '';

/** What the fakes saw. */
const seen = { classicalMade: 0, classicalDisposed: 0, classicalAnalyse: 0, netAnalyse: 0 };

function register(): void {
  clearEngines();
  registerEngine({
    id: 'cpp-wasm', labelKey: 'engine.cppWasm', runtime: 'wasm', language: 'C++', isAvailable: () => true,
    create: (context) => {
      seen.classicalMade++;
      const engine = new WorkerEngine({ info: context.info, assets: { engine: [], manifest: '', tableGz: '' }, createWorker: () => new FakeWorker(new FakeEngineCore(7), {}) });
      const analyse = engine.analyse.bind(engine);
      engine.analyse = (...args) => {
        seen.classicalAnalyse++;
        return analyse(...args);
      };
      const dispose = engine.dispose.bind(engine);
      engine.dispose = () => {
        seen.classicalDisposed++;
        dispose();
      };
      return engine;
    },
  });
  registerEngine({
    id: 'net-wasm', labelKey: 'engine.netWasm', runtime: 'wasm', language: 'Java + C++', isAvailable: () => true,
    create: (context) => {
      const engine = new WorkerEngine({ info: context.info, assets: { engine: [], manifest: '', tableGz: '' }, createWorker: () => new FakeWorker(new FakeEngineCore(3), {}) });
      engine.analyse = async () => {
        seen.netAnalyse++;
        throw new EngineFailure('unsupported', 'the net engines cannot analyse');
      };
      engine.evaluate3 = async () => {
        throw new EngineFailure('unsupported', 'the net engines have no static evaluation vector');
      };
      return engine;
    },
  });
}

beforeEach(() => {
  window.matchMedia = ((query: string) => ({
    matches: query.includes('reduced'), media: query, addEventListener: () => undefined, removeEventListener: () => undefined,
  })) as unknown as typeof window.matchMedia;
  localStorage.clear();
  for (const k of Object.keys(seen) as (keyof typeof seen)[]) seen[k] = 0;
  register();
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
});
afterEach(async () => {
  await act(async () => root.unmount());
  host.remove();
  localStorage.clear();
  history.replaceState(null, '', location.pathname);
});

/** Opens the engine sheet and applies `id`. */
async function chooseEngine(id: string): Promise<void> {
  const change = [...host.querySelectorAll('button')].find((b) => b.textContent?.includes(en.engine_change));
  await click(change ?? null);
  await click(q(`[data-engine="${id}"]`));
  await click(q('.ml-sheet__actions .ml-btn--primary'));
}

describe('move guides with a net engine', () => {
  it('show marks, the advantage bar and a note naming the classical engine; the net engine is never asked to analyse', async () => {
    const store = new NetSelectionStore({ initial: { engine: 'cpp-wasm', modelId: null, sims: 200 }, storage: null, wasm: true });
    await act(async () => {
      root.render(<App manifestLoader={async () => manifest} netSelection={store} />);
    });
    await click(q('[data-choice="begin-no"]'));
    await until(() => status() === 'Your move', 'the classical engine moves first');
    await chooseEngine('net-wasm');
    await until(() => store.selection.engine === 'net-wasm' && status() === 'Your move', 'the net engine plays');
    expect(seen.classicalDisposed, 'the classical engine that played is freed').toBe(1);

    await click(q('[data-guides-level="eval"]'));
    await until(() => all('[data-guide="piece"]').length > 0, 'piece badges from the analysis engine');
    await until(() => q('.ml-advantage') !== null, 'the advantage bar');
    expect(q('[data-guides-status]')!.getAttribute('data-guides-status')).not.toBe('unavailable');
    expect(q('[data-guides-note-net]')?.textContent).toBe(en.guides_note_net.replace('{engine}', en['engine.cppWasm']));
    expect(seen.netAnalyse).toBe(0);
    expect(seen.classicalAnalyse).toBeGreaterThan(0);
    // The position was already analysed while the classical engine played (the strip runs at every level), and the cache is keyed by the
    // analysis engine, so the net engine's first turn needed no second engine. The next position does.
    await click(all('[data-cell][aria-label*="best move"]')[0]!);
    await click(all('[data-cell][aria-label*="target"]')[0]!);
    await until(() => seen.classicalMade === 2, 'one engine played first, a second one analyses for the net engine');
    await until(() => status() === 'Your move' && all('[data-guide="piece"]').length > 0, 'badges for the next position');
    expect(seen.netAnalyse).toBe(0);

    // back to a classical engine: the analysis engine is freed, the guides go on with the engine that plays
    const disposedBefore = seen.classicalDisposed;
    await chooseEngine('cpp-wasm');
    await until(() => store.selection.engine === 'cpp-wasm' && status() === 'Your move', 'the classical engine plays again');
    expect(seen.classicalDisposed, 'the analysis engine is freed').toBe(disposedBefore + 1);
    expect(q('[data-guides-note-net]')).toBeNull();
    await until(() => all('[data-guide="piece"]').length > 0, 'piece badges from the engine in use');
    expect(seen.classicalMade).toBe(3);
  });
});
