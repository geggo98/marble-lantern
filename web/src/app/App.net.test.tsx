// @vitest-environment jsdom
// The persisted net selection and the engine that really plays (UX review H1): a stored net engine must not warn in the New game
// dialog while a classical engine plays, and after the start the store names the engine that was loaded; model and sims stay.
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import type { Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { clearEngines, registerEngine } from '../engines/registry';
import type { EnginesManifest } from '../engines/registry';
import { listModels } from '../engines/net/model-manifest';
import { App } from './App';
import { registerFakeEngine } from './dev-fake';
import { NetSelectionStore } from './net-selection';

vi.mock('./adapters', () => ({ loadManifest: vi.fn() }));

const file = { path: 'engines/cpp/engine-cpp.wasm', bytes: 284000, gzipBytes: 108000, sha256: '0'.repeat(64) };
const manifest: EnginesManifest = { v: 1, version: 'test', engines: { 'cpp-wasm': { files: [file], binaryBytes: 284000, binaryGzipBytes: 108000 } } };
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

let host: HTMLElement;
let root: Root;

beforeEach(() => {
  window.matchMedia = ((query: string) => ({ matches: query.includes('reduced'), media: query, addEventListener: () => undefined, removeEventListener: () => undefined })) as unknown as typeof window.matchMedia;
  clearEngines();
  registerFakeEngine(0);
  registerEngine({ id: 'java-wasmgc', labelKey: 'engine.javaWasmGc', runtime: 'wasm-gc', language: 'Java', isAvailable: () => false, create: () => { throw new Error('unavailable'); } });
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
});
afterEach(async () => {
  await act(async () => root.unmount());
  host.remove();
  history.replaceState(null, '', location.pathname);
});

const settle = async (done: () => boolean): Promise<void> => {
  for (let i = 0; i < 100 && !done(); i++) await act(async () => { await new Promise((r) => setTimeout(r, 10)); });
};

describe('a stored net engine at the next visit', () => {
  it('shows no warning in the New game dialog, and the store names the classical engine once it is loaded; model and sims stay', async () => {
    const modelId = listModels()[0]!.id;
    const store = new NetSelectionStore({ initial: { engine: 'net-wasm', modelId, sims: 50 }, storage: null, wasm: true });
    await act(async () => {
      root.render(<App manifestLoader={async () => manifest} netSelection={store} />);
    });
    const dialog = document.querySelector('[data-dialog="begin"]');
    expect(dialog, 'the start dialog is open').not.toBeNull();
    expect(dialog!.querySelector('[data-net-warning]'), 'no warning for a net engine that does not play').toBeNull();
    await settle(() => store.selection.engine !== 'net-wasm');
    expect(store.selection).toEqual({ engine: 'cpp-wasm', modelId, sims: 50 });
  });

  it('a stored net engine without a model: still no warning while a classical engine plays', async () => {
    const store = new NetSelectionStore({ initial: { engine: 'net-wasm', modelId: null, sims: 200 }, storage: null, wasm: true });
    await act(async () => {
      root.render(<App manifestLoader={async () => manifest} netSelection={store} />);
    });
    expect(document.querySelector('[data-dialog="begin"] [data-net-warning]'), 'no warning before the engine is loaded').toBeNull();
    await settle(() => store.selection.engine !== 'net-wasm');
    await act(async () => {
      (document.querySelector('[data-choice="begin-no"]') as HTMLElement).click();
    });
    expect(store.selection.engine).toBe('cpp-wasm');
    expect(document.querySelector('[data-net-warning]')).toBeNull();
  });
});
