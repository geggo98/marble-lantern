// @vitest-environment jsdom
// The update notice and the install entry: drawn only when there is something to offer, in both languages, and the buttons call the port.
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import type { Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { i18n } from '../i18n/i18n';
import type { PwaPort, PwaState } from '../pwa/pwa';
import { InstallEntry, UpdateNotice } from './PwaUi';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

let host: HTMLElement;
let root: Root;
const q = (s: string): HTMLElement | null => host.querySelector<HTMLElement>(s);
const render = async (node: React.JSX.Element): Promise<void> => {
  await act(async () => root.render(node));
};
const click = async (el: Element | null): Promise<void> => {
  if (!el) throw new Error('nothing to click');
  await act(async () => {
    el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
  });
};

/** A port whose state the test sets; the methods are spies. */
function port(initial: Partial<PwaState> = {}): PwaPort & { push(patch: Partial<PwaState>): void } {
  let state: PwaState = { update: false, install: null, ...initial };
  const listeners = new Set<() => void>();
  return {
    getState: () => state,
    subscribe: (l) => {
      listeners.add(l);
      return () => listeners.delete(l);
    },
    start: vi.fn(async () => undefined),
    applyUpdate: vi.fn(),
    dismissUpdate: vi.fn(() => {
      state = { ...state, update: false };
      listeners.forEach((l) => l());
    }),
    promptInstall: vi.fn(async () => undefined),
    push(patch) {
      state = { ...state, ...patch };
      listeners.forEach((l) => l());
    },
  };
}

beforeEach(() => {
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
  act(() => i18n.setLanguage('en'));
});
afterEach(async () => {
  await act(async () => root.unmount());
  host.remove();
  act(() => i18n.setLanguage('en'));
});

describe('UpdateNotice', () => {
  it('draws nothing without a port or without a waiting update, and appears when one arrives', async () => {
    await render(<UpdateNotice />);
    expect(host.innerHTML).toBe('');
    const p = port();
    await render(<UpdateNotice pwa={p} />);
    expect(q('[data-pwa="update"]')).toBeNull();
    await act(async () => p.push({ update: true }));
    expect(q('[data-pwa="update"]')?.getAttribute('role')).toBe('status');
    expect(q('[data-pwa="update"]')?.textContent).toContain('A new version is available.');
  });

  it('"Reload" asks the port to apply the update; "Not now" hides the notice', async () => {
    const p = port({ update: true });
    await render(<UpdateNotice pwa={p} />);
    await click(q('[data-pwa="reload"]'));
    expect(p.applyUpdate).toHaveBeenCalledTimes(1);
    expect(q('[data-pwa="later"]')?.getAttribute('aria-label')).toBe('Not now');
    await click(q('[data-pwa="later"]'));
    expect(p.dismissUpdate).toHaveBeenCalledTimes(1);
    expect(q('[data-pwa="update"]')).toBeNull();
  });

  it('speaks German', async () => {
    act(() => i18n.setLanguage('de'));
    await render(<UpdateNotice pwa={port({ update: true })} />);
    expect(q('[data-pwa="update"]')?.textContent).toContain('Eine neue Version ist da.');
    expect(q('[data-pwa="reload"]')?.textContent).toBe('Neu laden');
  });
});

describe('InstallEntry', () => {
  it('draws nothing when the browser offers no install', async () => {
    await render(<InstallEntry pwa={port()} />);
    expect(host.innerHTML).toBe('');
    await render(<InstallEntry />);
    expect(host.innerHTML).toBe('');
  });

  it('offers "Install app" after beforeinstallprompt and starts the prompt on click', async () => {
    const p = port({ install: 'prompt' });
    await render(<InstallEntry pwa={p} />);
    expect(q('[data-pwa="install"]')?.textContent).toBe('Install app');
    await click(q('[data-pwa="install"]'));
    expect(p.promptInstall).toHaveBeenCalledTimes(1);
  });

  it('gives the Share hint on Safari for iOS, in both languages', async () => {
    await render(<InstallEntry pwa={port({ install: 'ios' })} />);
    expect(q('[data-pwa="ios-hint"]')?.textContent).toMatch(/Share.*Add to Home Screen/);
    act(() => i18n.setLanguage('de'));
    expect(q('[data-pwa="ios-hint"]')?.textContent).toMatch(/Teilen.*Zum Home-Bildschirm/);
    expect(q('[data-pwa="install"]')).toBeNull();
  });
});
