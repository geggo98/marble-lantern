// @vitest-environment jsdom
// The move guides in the whole app, against the fake engine (its analyse scores 30 points for each row a move gains).
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import type { Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { WorkerEngine } from '../engines/client';
import { GUIDE_NODES } from '../engines/analysis';
import { clearEngines, registerEngine } from '../engines/registry';
import type { EnginesManifest } from '../engines/registry';
import { FakeEngineCore, FakeWorker } from '../engines/testing/fake-engine';
import en from '../i18n/en.json';
import { GUIDES_KEY } from '../guides/preference';
import { App } from './App';

vi.mock('./adapters', () => ({ loadManifest: vi.fn() }));

const file = { path: 'engines/cpp/engine-cpp.wasm', bytes: 1, gzipBytes: 1, sha256: '0'.repeat(64) };
const manifest: EnginesManifest = { v: 1, version: 'test', engines: { 'cpp-wasm': { files: [file], binaryBytes: 1, binaryGzipBytes: 1 } } };

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const MOVE_TEXT = new RegExp(`target, (${['much_better', 'better', 'equal', 'worse', 'much_worse'].map((b) => en[`guide_move_${b}` as keyof typeof en]).join('|')})$`);

let host: HTMLElement;
let root: Root;
const q = <T extends Element = HTMLElement>(selector: string): T | null => host.querySelector<T>(selector);
const all = <T extends Element = HTMLElement>(selector: string): T[] => [...host.querySelectorAll<T>(selector)];

async function until(condition: () => boolean, what: string, ms = 3000): Promise<void> {
  const end = Date.now() + ms;
  while (!condition()) {
    if (Date.now() > end) throw new Error(`timeout waiting for: ${what}`);
    await act(async () => {
      await new Promise((r) => setTimeout(r, 5));
    });
  }
}
const click = async (element: Element): Promise<void> => {
  await act(async () => {
    element.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
  });
};
const status = (): string => q('.ml-status__who span')?.textContent ?? '';
const plies = (): number => Number(/(\d+)\//.exec(q('.ml-status__ply')?.textContent ?? '')?.[1] ?? -1);
const level = (l: string): HTMLInputElement => q<HTMLInputElement>(`[data-guides-level="${l}"]`)!;

function registerFake(analyseFails: boolean): void {
  clearEngines();
  registerEngine({
    id: 'cpp-wasm',
    labelKey: 'engine.cppWasm',
    runtime: 'wasm',
    language: 'C++',
    isAvailable: () => true,
    create: (context) => {
      const engine = new WorkerEngine({
        info: context.info,
        assets: { engine: [], manifest: '', tableGz: '' },
        createWorker: () => new FakeWorker(new FakeEngineCore(7), {}),
      });
      if (analyseFails) {
        engine.analyse = async () => {
          throw Object.assign(new Error('the server engines have no analysis endpoint'), { code: 'unsupported' });
        };
      }
      return engine;
    },
  });
}

async function start(analyseFails = false): Promise<void> {
  registerFake(analyseFails);
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
  await act(async () => {
    root.render(<App manifestLoader={async () => manifest} />);
  });
  await click(document.querySelector('[data-choice="begin-no"]')!); // the engine begins
  await until(() => status() === 'Your move', 'the engine loads and moves first');
}

beforeEach(() => {
  window.matchMedia = ((query: string) => ({
    matches: query.includes('reduced'), media: query, addEventListener: () => undefined, removeEventListener: () => undefined,
  })) as unknown as typeof window.matchMedia;
  localStorage.clear();
});
afterEach(async () => {
  await act(async () => root.unmount());
  host.remove();
  localStorage.clear();
  history.replaceState(null, '', location.pathname);
});

describe('move guides in the app', () => {
  it('default is the level "targets": no marks, no bar, no analysis', async () => {
    await start();
    expect(level('targets').checked).toBe(true);
    expect(all('svg.ml-board__svg [data-band]')).toHaveLength(0);
    expect(q('.ml-advantage')).toBeNull();
  });

  it('show piece badges and an advantage text after the switch, and remember the choice', async () => {
    await start();
    await click(level('eval'));
    expect(localStorage.getItem(GUIDES_KEY)).toBe('eval');
    await until(() => all('[data-guide="piece"]').length > 0, 'piece badges');
    expect(all('[data-guide="piece"]')).toHaveLength(all('[data-cell][aria-label*="best move"]').length);
    await until(() => q('.ml-advantage') !== null, 'advantage bar');
    expect(q('.ml-advantage__text')?.textContent).toMatch(/Advantage: (Even|You: |Engine: )/);
    expect(q('.ml-advantage__track')?.getAttribute('role')).toBe('meter');
  });

  it('tints the targets of the selected piece by band, with the band in the accessible name', async () => {
    await start();
    await click(level('eval'));
    await until(() => all('[data-guide="piece"]').length > 0, 'piece badges');
    const seen = new Set<string>();
    for (const piece of all('[data-cell][aria-label*="best move"]')) {
      await click(piece);
      const marks = all('[data-guide="target"]');
      expect(marks.length).toBeGreaterThan(0);
      expect(marks).toHaveLength(all('.ml-target__ring').length);
      for (const target of all('[data-cell][aria-label*="target"]')) {
        expect(target.getAttribute('aria-label')).toMatch(MOVE_TEXT);
      }
      for (const m of marks) seen.add(m.getAttribute('data-band')!);
    }
    // the opening of the fake engine has only forward moves of equal gain: every target ties with the best move, so it is top
    expect([...seen]).toEqual(['much-better']);
  });

  it('updates the advantage after the human move (from the cache) and again after the engine reply', async () => {
    await start();
    await click(level('eval'));
    await until(() => q('.ml-advantage') !== null, 'advantage bar');
    const piece = all('[data-cell][aria-label*="best move"]')[0]!;
    await click(piece);
    const target = all('[data-cell][aria-label*="target"]')[0]!;
    await click(target);
    await until(() => plies() === 3 && status() === 'Your move', 'the engine answers');
    await until(() => all('[data-guide="piece"]').length > 0 && !q('.ml-advantage.is-stale'), 'a fresh analysis of the new position');
    expect(q('.ml-advantage')).not.toBeNull();
  });

  it('hides the marks while the engine is on move and when the switch goes off', async () => {
    await start();
    await click(level('eval'));
    await until(() => all('[data-guide="piece"]').length > 0, 'piece badges');
    await click(level('targets'));
    expect(localStorage.getItem(GUIDES_KEY)).toBe('targets');
    expect(all('[data-band]').filter((e) => e.closest('svg.ml-board__svg'))).toHaveLength(0);
    expect(q('.ml-advantage')).toBeNull();
  });

  it('starts switched on when the stored choice says so', async () => {
    localStorage.setItem(GUIDES_KEY, 'on');
    await start();
    expect(level('eval').checked).toBe(true);
    expect(localStorage.getItem(GUIDES_KEY)).toBe('eval'); // the old value "on" is migrated
    await until(() => all('[data-guide="piece"]').length > 0, 'piece badges');
  });

  it('shows a short notice, no marks and no crash when the engine cannot analyse', async () => {
    await start(true);
    await click(level('eval'));
    await until(() => (q('.ml-guides-card__status')?.textContent ?? '') !== '', 'the notice');
    expect(q('.ml-guides-card__status')?.textContent).toContain('engine that runs in this browser');
    expect(all('[data-band]').filter((e) => e.closest('svg.ml-board__svg'))).toHaveLength(0);
    expect(status()).toBe('Your move'); // the game is not affected
    await click(all('[data-cell][aria-label*="your piece"]')[0]!);
    expect(all('.ml-target__dot').length).toBeGreaterThan(0); // plain targets, as without guides
  });

  it('lists all five bands with a mark each in the legend', async () => {
    await start();
    await click(level('eval'));
    const items = all('[data-legend-band]');
    expect(items).toHaveLength(5);
    expect(new Set(items.map((i) => i.querySelector('path')?.getAttribute('d'))).size).toBe(5);
  });
  it('level "targets": a selected piece shows plain targets, and nothing is analysed or rated', async () => {
    const spy = vi.spyOn(WorkerEngine.prototype, 'analyse');
    try {
      await start();
      await click(all('[data-cell][aria-label*="your piece"]')[0]!);
      expect(all('.ml-target__dot').length).toBeGreaterThan(0);
      expect(all('[data-band]')).toHaveLength(0);
      expect(q('.ml-advantage')).toBeNull();
      expect(q('[data-guides-note-net]')).toBeNull();
      expect(q('[data-legend-band]')).toBeNull(); // the legend explains the evaluation marks only
      await new Promise((r) => setTimeout(r, 100));
      // the skill tracker may analyse the human's moves with its own budget (SKILL_NODES, 5-row games included); the guides must not
      expect(spy.mock.calls.filter((c) => c[2] === GUIDE_NODES)).toEqual([]);
    } finally {
      spy.mockRestore();
    }
  });

  it('level "none": a selected piece keeps its highlight but no target is drawn, and the move can still be played', async () => {
    await start();
    await click(level('none'));
    expect(localStorage.getItem(GUIDES_KEY)).toBe('none');
    expect(level('none').checked).toBe(true);
    await click(all('[data-cell][aria-label*="your piece"]')[0]!);
    expect(all('[data-cell][aria-label$="selected"]')).toHaveLength(1);
    expect(all('.ml-target__ring')).toHaveLength(0);
    expect(all('.ml-target__dot')).toHaveLength(0);
    expect(all('[data-band]')).toHaveLength(0);
    expect(q('.ml-advantage')).toBeNull();
    // the targets are still legal to tap (and are named for a screen reader): level "none" hides marks, it does not change the rules
    const target = all('[data-cell][aria-label*="target"]')[0]!;
    await click(target);
    await until(() => plies() === 3 && status() === 'Your move', 'the move is played and the engine answers');
  });

  it('the old stored switch "off" shows as "targets" and "on" as the evaluation', async () => {
    localStorage.setItem(GUIDES_KEY, 'off');
    await start();
    expect(level('targets').checked).toBe(true);
    expect(localStorage.getItem(GUIDES_KEY)).toBe('targets');
  });

  it('the three levels form one radio group: choosing one unchecks the others, the group has a name', async () => {
    await start();
    const group = q('[role="radiogroup"][data-guides-levels]')!;
    expect(group.getAttribute('aria-label')).toBe(en.guides_heading);
    expect(group.getAttribute('aria-describedby')).toBeTruthy();
    const radios = all<HTMLInputElement>('[data-guides-level]');
    expect(radios.map((r) => r.value)).toEqual(['none', 'targets', 'eval']);
    expect(new Set(radios.map((r) => r.name)).size).toBe(1);
    await click(level('none'));
    expect(radios.map((r) => r.checked)).toEqual([true, false, false]);
  });
});
