// @vitest-environment jsdom
// The app with the installable-app port: the service worker registers after the first engine load, and the update notice appears in a
// running game without touching it; only "Reload" asks for the update.
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import type { Root } from 'react-dom/client';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { clearEngines } from '../engines/registry';
import type { EnginesManifest } from '../engines/registry';
import type { PwaPort, PwaState } from '../pwa/pwa';
import { App } from './App';
import { registerFakeEngine } from './dev-fake';

vi.mock('./adapters', () => ({ loadManifest: vi.fn() })); // no adapter modules, no fetch

const file = { path: 'engines/cpp/engine-cpp.wasm', bytes: 284000, gzipBytes: 108000, sha256: '0'.repeat(64) };
const manifest: EnginesManifest = { v: 1, version: 'test', engines: { 'cpp-wasm': { files: [file], binaryBytes: 284000, binaryGzipBytes: 108000 } } };

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

let host: HTMLElement;
let root: Root;
const q = (s: string): HTMLElement | null => host.querySelector<HTMLElement>(s);

async function until(condition: () => boolean, what: string, ms = 3000): Promise<void> {
  const end = Date.now() + ms;
  while (!condition()) {
    if (Date.now() > end) throw new Error(`timeout waiting for: ${what}`);
    await act(async () => {
      await new Promise((r) => setTimeout(r, 5));
    });
  }
}

function port(): PwaPort & { push(patch: Partial<PwaState>): void } {
  let state: PwaState = { update: false, install: null };
  const listeners = new Set<() => void>();
  return {
    getState: () => state,
    subscribe: (l) => {
      listeners.add(l);
      return () => listeners.delete(l);
    },
    start: vi.fn(async () => undefined),
    applyUpdate: vi.fn(),
    dismissUpdate: vi.fn(),
    promptInstall: vi.fn(async () => undefined),
    push(patch) {
      state = { ...state, ...patch };
      listeners.forEach((l) => l());
    },
  };
}

beforeEach(() => {
  window.matchMedia = ((query: string) => ({ matches: false, media: query, addEventListener: () => undefined, removeEventListener: () => undefined })) as unknown as typeof window.matchMedia;
  clearEngines();
  registerFakeEngine(0);
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
});
afterEach(async () => {
  await act(async () => root.unmount());
  host.remove();
  history.replaceState(null, '', location.pathname);
});

it('registers once the engine is ready, and shows the update notice over a running game until the person clicks', async () => {
  const pwa = port();
  await act(async () => root.render(<App manifestLoader={async () => manifest} pwa={pwa} />));
  await act(async () => document.querySelector<HTMLElement>('[data-choice="begin-no"]')!.click());
  const ply = (): string => q('.ml-status__ply')?.textContent ?? '';
  await until(() => /^\D*1\s*\//.test(ply()), 'the engine makes its first move');
  expect(pwa.start).toHaveBeenCalledTimes(1);

  await act(async () => pwa.push({ update: true }));
  expect(q('[data-pwa="update"]')?.textContent).toContain('A new version is available.');
  expect(pwa.applyUpdate).not.toHaveBeenCalled();
  expect(/^\D*1\s*\//.test(ply())).toBe(true); // the game stays as it is
  await act(async () => q('[data-pwa="reload"]')!.click());
  expect(pwa.applyUpdate).toHaveBeenCalledTimes(1);
});
