// @vitest-environment jsdom
// A component test: the whole app plays a game against the fake engine through clicks and key presses.
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import type { Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { clearEngines, registerEngine } from '../engines/registry';
import type { EnginesManifest } from '../engines/registry';
import { App } from './App';
import { registerFakeEngine } from './dev-fake';

vi.mock('./adapters', () => ({ loadManifest: vi.fn() })); // no adapter modules, no fetch

const file = { path: 'engines/cpp/engine-cpp.wasm', bytes: 284000, gzipBytes: 108000, sha256: '0'.repeat(64) };
const manifest: EnginesManifest = {
  v: 1,
  version: 'test',
  engines: { 'cpp-wasm': { files: [file], binaryBytes: 284000, binaryGzipBytes: 108000 } , 'java-js': { files: [file], binaryBytes: 321000, binaryGzipBytes: 118000 } },
};

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

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

async function click(element: Element): Promise<void> {
  await act(async () => {
    element.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
  });
}

async function key(element: Element, name: string): Promise<void> {
  await act(async () => {
    element.dispatchEvent(new KeyboardEvent('keydown', { key: name, bubbles: true, cancelable: true }));
  });
}

const status = (): string => q('.ml-status__who span')?.textContent ?? '';
const plies = (): number => Number(/(\d+)\//.exec(q('.ml-status__ply')?.textContent ?? '')?.[1] ?? -1);
const ownPieces = (): HTMLElement[] => all('[data-cell][aria-label*="your piece"]');
const targetCells = (): HTMLElement[] => all('[data-cell][aria-label*="target"]');

/** Selects the first own piece that has a target; returns its cell id. */
async function selectMovablePiece(): Promise<void> {
  for (const piece of ownPieces()) {
    await click(piece);
    if (targetCells().length > 0) return;
  }
  throw new Error('no own piece has a legal move');
}

beforeEach(async () => {
  window.matchMedia = ((query: string) => ({
    matches: query.includes('reduced'),
    media: query,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
  })) as unknown as typeof window.matchMedia;
  clearEngines();
  registerFakeEngine(0);
  // Java engines this browser cannot run: wasm-gc is not in the manifest (not shipped), java-js is shipped but unsupported here.
  registerEngine({ id: 'java-wasmgc', labelKey: 'engine.javaWasmGc', runtime: 'wasm-gc', language: 'Java', isAvailable: () => false, create: () => { throw new Error('unavailable'); } });
  registerEngine({ id: 'java-js', labelKey: 'engine.javaJs', runtime: 'js', language: 'Java', isAvailable: () => false, create: () => { throw new Error('unavailable'); } });
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
  await act(async () => {
    root.render(<App manifestLoader={async () => manifest} />);
  });
  // the start dialog asks first; "No" lets the engine begin, as these tests expect
  await click(document.querySelector('[data-choice="begin-no"]')!);
  await until(() => status() === 'Your move', 'the engine loads and makes its first move');
});

afterEach(async () => {
  await act(async () => root.unmount());
  host.remove();
  document.documentElement.removeAttribute('data-theme');
  history.replaceState(null, '', location.pathname); // the games of these tests leave their address behind
});

describe('the play screen', () => {
  it('shows the board of 121 cells and 20 pieces, the engine card and the first engine move', () => {
    expect(all('[data-cell]')).toHaveLength(121);
    expect(all('.ml-pieces use')).toHaveLength(20);
    expect(q('.ml-engine__head')?.textContent).toContain('C++');
    expect(plies()).toBe(1); // the engine (side 0) moved first
    expect(q('.ml-telemetry')).not.toBeNull();
    expect(ownPieces()).toHaveLength(10);
  });

  it('plays three moves through clicks: select, targets appear, move, the engine answers, pieces are conserved', async () => {
    for (let k = 1; k <= 3; k++) {
      await selectMovablePiece();
      expect(all('.ml-target__ring').length).toBeGreaterThan(0);
      expect(all('.ml-select')).toHaveLength(1);
      await click(targetCells()[0]!);
      await until(() => plies() === 1 + 2 * k && status() === 'Your move', `ply ${1 + 2 * k}`);
      expect(all('.ml-pieces use')).toHaveLength(20);
      expect(all('.ml-select')).toHaveLength(0);
    }
    expect(all('.ml-moves__list li')).toHaveLength(4); // 7 plies in rows of two
    expect(q('[aria-current="true"]')).not.toBeNull();
  });

  it('marks a click on a cell that is no target as illegal, with a text hint, and keeps the selection', async () => {
    await selectMovablePiece();
    const far = all('[data-cell]').find((c) => /empty$/.test(c.getAttribute('aria-label') ?? ''))!;
    await click(far);
    expect(all('.ml-illegal')).toHaveLength(1);
    expect(q('.ml-board .ml-hint')?.textContent).toContain('marked cells');
    expect(all('.ml-select')).toHaveLength(1);
  });

  it('plays from the keyboard: Enter selects the piece in the tab stop, Page Down moves to a target, Enter moves', async () => {
    const stop = q('[data-cell][tabindex="0"]')!;
    expect(stop.getAttribute('aria-label')).toContain('your piece');
    await act(async () => stop.focus());
    await key(stop, 'Enter');
    expect(all('.ml-select')).toHaveLength(1);
    await key(stop, 'PageDown');
    const focused = document.activeElement as HTMLElement;
    expect(focused.getAttribute('aria-label')).toContain('target');
    await key(focused, 'Enter');
    await until(() => plies() === 3, 'the engine answers');
  });

  it('keeps one tab stop on the board (roving tabindex)', () => {
    expect(all('[data-cell][tabindex="0"]')).toHaveLength(1);
  });

  it('undo takes back the human move, new game starts again, swap sides keeps the board and lets the engine take the other side', async () => {
    await selectMovablePiece();
    await click(targetCells()[0]!);
    await until(() => plies() === 3, 'ply 3');
    await click(all('button').find((b) => b.textContent === 'Undo')!);
    await until(() => plies() === 1, 'undo back to ply 1');
    await click(all('button').find((b) => b.textContent === 'Swap sides')!);
    // the board stays; the engine (now side 1) answers at once, then the human is on move again as side 0
    await until(() => status() === 'Your move' && plies() === 2, 'the engine played the other side');
    // The human now plays side 0 (the top corner), so the board is rotated: the human's marbles are amber and at the bottom.
    const own = ownPieces()[0]!;
    const y = Number(own.getAttribute('cy'));
    expect(y).toBeGreaterThan(0);
    await click(all('button').find((b) => b.textContent === 'New game')!);
    // the side is asked again: Escape keeps the game that runs
    const yes = (): HTMLElement | null => document.querySelector('[data-choice="begin-yes"]');
    await until(() => yes() !== null, 'the start question');
    const before = plies();
    await act(async () => { document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true })); });
    await until(() => yes() === null, 'the dialog closed');
    expect(plies()).toBe(before);
    await click(all('button').find((b) => b.textContent === 'New game')!);
    await until(() => yes() !== null, 'the start question again');
    await click(yes()!);
    await until(() => plies() === 0 || plies() === 1, 'a new game');
    await until(() => status() === 'Your move', 'new game');
  });
});

describe('engine and strength', () => {
  it('opens a modal sheet, applies a new strength, and returns focus to the opening button', async () => {
    const opener = all('button').find((b) => b.textContent?.includes('Engine and strength'))!;
    await act(async () => opener.focus());
    await click(opener);
    const dialog = document.querySelector('[role="dialog"]')!;
    expect(dialog.getAttribute('aria-modal')).toBe('true');
    const slider = dialog.querySelector<HTMLInputElement>('input[type="range"]')!;
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!;
    await act(async () => {
      setter.call(slider, '0');
      slider.dispatchEvent(new Event('input', { bubbles: true }));
    });
    expect(dialog.textContent).toContain('Beginner');
    const apply = [...dialog.querySelectorAll('button')].find((b) => b.textContent === 'Apply')!;
    await click(apply);
    await until(() => document.querySelector('[role="dialog"]') === null, 'the sheet closes');
    expect(q('.ml-engine__level')?.textContent).toContain('Beginner');
    expect(q('.ml-engine__level')?.textContent).toContain('1,000');
    expect(document.activeElement?.textContent).toContain('Engine and strength');
  });

  it('closes on Escape without changes', async () => {
    const opener = all('button').find((b) => b.textContent?.includes('Engine and strength'))!;
    await click(opener);
    const dialog = document.querySelector('[role="dialog"]')!;
    await key(dialog, 'Escape');
    expect(document.querySelector('[role="dialog"]')).toBeNull();
    expect(q('.ml-engine__level')?.textContent).toContain('Standard');
  });

  it('lists the engines that were not shipped as unavailable, with a reason as text', async () => {
    const opener = all('button').find((b) => b.textContent?.includes('Engine and strength'))!;
    await click(opener);
    const dialog = document.querySelector('[role="dialog"]')!;
    const radios = [...dialog.querySelectorAll('[role="radio"]')];
    expect(radios).toHaveLength(3);
    const disabled = radios.filter((r) => r.getAttribute('aria-disabled') === 'true');
    expect(disabled).toHaveLength(2);
    expect(disabled.map((d) => d.textContent).join('|')).toContain('does not include this engine');
    expect(disabled.map((d) => d.textContent).join('|')).toContain('cannot run this engine');
  });
});

describe('chrome', () => {
  it('shows the offline badge, switches the theme and remembers it', async () => {
    expect(q('.ml-badge')?.textContent).toContain('offline');
    const dark = all('.ml-footer .ml-seg__opt').find((b) => b.getAttribute('aria-label') === 'Dark')!;
    await click(dark);
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
    expect(dark.getAttribute('aria-pressed')).toBe('true');
    const auto = all('.ml-footer .ml-seg__opt').find((b) => b.getAttribute('aria-label') === 'Follow system')!;
    await click(auto);
    expect(document.documentElement.hasAttribute('data-theme')).toBe(false);
  });

  it('marks the current screen and switches screens by hash', async () => {
    expect(q('.ml-nav__link[aria-current="page"]')?.textContent).toBe('Play');
    await act(async () => {
      location.hash = '#/about';
      window.dispatchEvent(new HashChangeEvent('hashchange'));
    });
    expect(q('.ml-page__title')?.textContent).toBe('About');
    expect(q('.ml-nav__link[aria-current="page"]')?.textContent).toBe('About');
    await act(async () => {
      location.hash = '#/bench';
      window.dispatchEvent(new HashChangeEvent('hashchange'));
    });
    expect(q('.ml-page__title')?.textContent).toBe('Benchmark');
    expect(q('.ml-bench__run .ml-hint')?.textContent).toContain('not part of this build');
    await act(async () => {
      location.hash = '';
      window.dispatchEvent(new HashChangeEvent('hashchange'));
    });
  });
});
