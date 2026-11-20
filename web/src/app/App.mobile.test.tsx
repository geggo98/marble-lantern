// @vitest-environment jsdom
// The play screen on a small screen (matchMedia says: narrower than 60rem): a bottom bar with the primary actions, and
// the HUD menu with everything that is a setting or secondary. The content is the same as in the side column of a wide screen.
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import type { Root } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { clearEngines } from '../engines/registry';
import type { EnginesManifest } from '../engines/registry';
import { App } from './App';
import { registerFakeEngine } from './dev-fake';
import { FakeNavigation } from './fake-navigation';

vi.mock('./adapters', () => ({ loadManifest: vi.fn() }));

const file = { path: 'engines/cpp/engine-cpp.wasm', bytes: 1, gzipBytes: 1, sha256: '0'.repeat(64) };
const manifest: EnginesManifest = {
  v: 1, version: 'test', engines: { 'cpp-wasm': { files: [file], binaryBytes: 1, binaryGzipBytes: 1 } },
};

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

let host: HTMLElement;
let root: Root;
const q = <T extends Element = HTMLElement>(selector: string, scope: ParentNode = document): T | null => scope.querySelector<T>(selector);
const all = <T extends Element = HTMLElement>(selector: string, scope: ParentNode = document): T[] => [...scope.querySelectorAll<T>(selector)];

async function until(condition: () => boolean, what: string, ms = 5000): Promise<void> {
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
const press = async (element: Element, key: string): Promise<void> => {
  await act(async () => {
    element.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }));
  });
};

async function start(wide: boolean): Promise<void> {
  window.matchMedia = ((query: string) => ({
    matches: query.includes('reduced') || (query.includes('max-width') && !wide),
    media: query, addEventListener: () => undefined, removeEventListener: () => undefined,
  })) as unknown as typeof window.matchMedia;
  clearEngines();
  registerFakeEngine(0);
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
  await act(async () => {
    root.render(<App manifestLoader={async () => manifest} navigation={new FakeNavigation('')} />);
  });
  await until(() => !q('.ml-loading') && q('[data-choice="begin-yes"]') !== null, 'the dialog');
  await click(q('[data-choice="begin-yes"]')!);
  await until(() => all('[data-cell][aria-label*="your piece"]').length > 0, 'the game');
}

afterEach(async () => {
  await act(async () => root.unmount());
  host.remove();
});

const menuButton = (): HTMLButtonElement => q<HTMLButtonElement>('.ml-play__bar [aria-controls]')!;

describe('play screen on a small screen', () => {
  it('has a bottom bar with undo, new game and the menu button, and keeps the status outside the menu', async () => {
    await start(false);
    const bar = q('.ml-play__bar')!;
    expect(all('button', bar)).toHaveLength(3);
    expect(menuButton().getAttribute('aria-expanded')).toBe('false');
    expect(q('.ml-status')).not.toBeNull();
    expect(q('.ml-play__side')).toBeNull();
    expect(q('.ml-engine')).toBeNull(); // everything else waits in the menu
  });

  it('opens the menu with the settings and the secondary actions', async () => {
    await start(false);
    await click(menuButton());
    const menu = q('[role="dialog"]')!;
    expect(menuButton().getAttribute('aria-expanded')).toBe('true');
    expect(menuButton().getAttribute('aria-controls')).toBe(menu.id);
    expect(q('.ml-engine', menu)).not.toBeNull();
    expect(q('[data-guides-levels]', menu)).not.toBeNull();
    expect(q('.ml-skill', menu)).not.toBeNull();
    expect(q('.ml-copylink', menu)).not.toBeNull();
    expect(q('.ml-moves', menu)).not.toBeNull();
    expect(q('.ml-seg [aria-pressed]', menu)).not.toBeNull(); // theme
    const labels = all('button', menu).map((b) => b.textContent);
    expect(labels).toEqual(expect.arrayContaining(['Swap sides', 'Engine demo']));
  });

  it('the engine sheet opens above the menu; Escape closes the sheet first, then the menu, and focus returns step by step', async () => {
    await start(false);
    await click(menuButton());
    const change = q<HTMLButtonElement>('.ml-engine button[aria-haspopup="dialog"]')!;
    change.focus();
    await click(change);
    expect(all('[role="dialog"]')).toHaveLength(2);
    const sheet = all('[role="dialog"]').find((d) => d.className.includes('ml-sheet') && !d.className.includes('ml-menu'))!;
    await press(sheet, 'Escape');
    expect(all('[role="dialog"]')).toHaveLength(1);
    expect(document.activeElement).toBe(change);
    await press(change, 'Escape');
    expect(q('[role="dialog"]')).toBeNull();
    expect(document.activeElement).toBe(menuButton());
  });

  it('a primary action of the bar works: new game starts again from ply 0', async () => {
    await start(false);
    const plies = (): number => Number(/(\d+)\//.exec(q('.ml-status__ply')?.textContent ?? '')?.[1] ?? -1);
    for (const piece of all('[data-cell][aria-label*="your piece"]')) {
      await click(piece);
      const target = all('[data-cell][aria-label*="target"]')[0];
      if (target) {
        await click(target);
        break;
      }
    }
    await until(() => plies() >= 2 && !q('.ml-status[data-state="thinking"]'), 'the engine answered');
    const bar = q('.ml-play__bar')!;
    await click(all<HTMLButtonElement>('button', bar).find((b) => b.textContent === 'New game')!);
    await until(() => q('[data-choice="begin-yes"]') !== null, 'the start question'); // the side is asked again
    await click(q('[data-choice="begin-yes"]')!);
    await until(() => plies() === 0, 'a new game');
  });
});

describe('play screen on a wide screen', () => {
  it('keeps the side column and has no menu button', async () => {
    await start(true);
    expect(q('.ml-play__side .ml-engine')).not.toBeNull();
    expect(q('.ml-play__bar')).toBeNull();
    expect(q('[aria-controls]')).toBeNull();
  });
});
