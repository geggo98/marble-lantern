import { describe, expect, it } from 'vitest';
import { DEFAULT_VARIANT } from '../../game/variant';
import type { Variant } from '../../game/variant';
import type { EngineId } from '../engine';
import { listModels } from './models';
import { DEFAULT_SELECTION, SELECTION_KEY, evaluateSelection, loadSelection, parseSelection, saveSelection } from './selection';
import type { NetAvailability, NetSelection } from './selection';

const ADJ2 = DEFAULT_VARIANT as Variant;
const ADJ3: Variant = { jump: 'adjacent', players: 3 };
const EXPERT = 'small-2p-adjacent-expert';
const SHARED = 'small-shared-all';
const all: NetAvailability = { wasm: true, webgpu: true };
const sel = (engine: EngineId, modelId: string | null, sims: NetSelection['sims'] = 200): NetSelection => ({ engine, modelId, sims });
const codes = (w: ReadonlyArray<{ code: string }>): string[] => w.map((x) => x.code);

describe('evaluateSelection', () => {
  it('a classical engine is usable with no warnings, whatever the model says', () => {
    const e = evaluateSelection(sel('cpp-wasm', 'ghost'), ADJ3, all);
    expect(e).toMatchObject({ usable: true, warnings: [], effectiveBackend: 'classical' });
  });

  it('a net engine without a model: no-model and fallback-random, random moves', () => {
    const e = evaluateSelection(sel('net-wasm', null), ADJ2, all);
    expect(e.usable).toBe(false);
    expect(e.effectiveBackend).toBe('random');
    expect(codes(e.warnings)).toEqual(['no-model', 'fallback-random']);
    expect(e.warnings[0]).toMatchObject({ variant: ADJ2 });
  });

  it('the expert model is fine for adjacent two-player and a mismatch for the others', () => {
    expect(evaluateSelection(sel('net-wasm', EXPERT), ADJ2, all)).toMatchObject({ usable: true, warnings: [], effectiveBackend: 'wasm' });
    const bad = evaluateSelection(sel('net-wasm', EXPERT), ADJ3, all);
    expect(codes(bad.warnings)).toEqual(['model-variant-mismatch', 'fallback-random']);
    expect(bad.warnings[0]).toMatchObject({ modelId: EXPERT, variant: ADJ3, reason: 'variant-not-supported' });
    expect(bad.effectiveBackend).toBe('random');
  });

  it('the shared model plays all four variants', () => {
    for (const jump of ['adjacent', 'long'] as const) {
      for (const players of [2, 3] as const) {
        expect(evaluateSelection(sel('net-wasm', SHARED), { jump, players }, all).usable).toBe(true);
      }
    }
  });

  it('an unknown model id: model-load-failed', () => {
    const e = evaluateSelection(sel('net-wasm', 'ghost'), ADJ2, all);
    expect(codes(e.warnings)).toEqual(['model-load-failed', 'fallback-random']);
    expect(e.warnings[0]).toMatchObject({ modelId: 'ghost', reason: 'no-model-id' });
    expect(e.warnings[0]!.detail).toContain('ghost');
  });

  it('lists the compatible models for the variant', () => {
    expect(evaluateSelection(sel('net-wasm', null), ADJ2, all).compatibleModels.map((m) => m.id).sort()).toEqual([EXPERT, SHARED]);
    expect(evaluateSelection(sel('net-wasm', null), ADJ3, all).compatibleModels.map((m) => m.id)).toEqual([SHARED]);
    expect(evaluateSelection(sel('cpp-wasm', null), ADJ3, all).compatibleModels.map((m) => m.id)).toEqual([SHARED]);
  });

  it('net-webgpu with a model and WebGPU: the GPU plays', () => {
    expect(evaluateSelection(sel('net-webgpu', EXPERT), ADJ2, all)).toMatchObject({ usable: true, warnings: [], effectiveBackend: 'webgpu' });
  });

  it('net-webgpu while the probe has not answered counts as available', () => {
    expect(evaluateSelection(sel('net-webgpu', EXPERT), ADJ2, { wasm: true, webgpu: null }).effectiveBackend).toBe('webgpu');
  });

  it('net-webgpu with a model but no WebGPU: the CPU net plays, with backend-unavailable only', () => {
    const e = evaluateSelection(sel('net-webgpu', EXPERT), ADJ2, { wasm: true, webgpu: false, webgpuReason: 'webgpu-no-adapter', webgpuDetail: 'no adapter' });
    expect(e).toMatchObject({ usable: true, effectiveBackend: 'wasm' });
    expect(codes(e.warnings)).toEqual(['backend-unavailable']);
    expect(e.warnings[0]).toMatchObject({ backend: 'webgpu', fallback: 'wasm', reason: 'webgpu-no-adapter', detail: 'no adapter', modelId: EXPERT });
  });

  it('net-webgpu without a model and without WebGPU names the missing model only (no net would run anyway)', () => {
    const e = evaluateSelection(sel('net-webgpu', null), ADJ2, { wasm: true, webgpu: false });
    expect(codes(e.warnings)).toEqual(['no-model', 'fallback-random']);
  });

  it('no WebAssembly at all: backend-unavailable and random, also for net-webgpu without WebGPU', () => {
    const noWasm = evaluateSelection(sel('net-wasm', EXPERT), ADJ2, { wasm: false, webgpu: false });
    expect(codes(noWasm.warnings)).toEqual(['backend-unavailable', 'fallback-random']);
    expect(noWasm.warnings[0]).toMatchObject({ reason: 'webassembly-unavailable' });
    const e = evaluateSelection(sel('net-webgpu', EXPERT), ADJ2, { wasm: false, webgpu: false });
    expect(e.effectiveBackend).toBe('random');
    expect(e.warnings[0]).toMatchObject({ backend: 'webgpu', fallback: 'random', reason: 'webgpu-no-adapter' });
  });

  it('a missing probe reason is the unknown-adapter code, not an English text', () => {
    const e = evaluateSelection(sel('net-webgpu', EXPERT), ADJ2, { wasm: true, webgpu: false });
    expect(e.warnings[0]).toMatchObject({ reason: 'webgpu-no-adapter' });
    expect(e.warnings[0]!.detail).toBeUndefined();
  });

  it('a different set of models changes the answer (the argument is used, not the bundled list)', () => {
    const only = listModels().filter((m) => m.id === SHARED);
    expect(codes(evaluateSelection(sel('net-wasm', EXPERT), ADJ2, all, only).warnings)).toEqual(['model-load-failed', 'fallback-random']);
  });
});

describe('persistence', () => {
  const memory = (initial?: string): { storage: Storage; data: Map<string, string> } => {
    const data = new Map<string, string>();
    if (initial !== undefined) data.set(SELECTION_KEY, initial);
    const storage = { getItem: (k: string) => data.get(k) ?? null, setItem: (k: string, v: string) => void data.set(k, v) } as unknown as Storage;
    return { storage, data };
  };

  it('the default is the existing default engine with no model and 200 simulations', () => {
    expect(DEFAULT_SELECTION).toEqual({ engine: 'java-wasmgc', modelId: null, sims: 200 });
    expect(loadSelection(memory().storage)).toEqual(DEFAULT_SELECTION);
  });

  it('round-trips a selection', () => {
    const m = memory();
    saveSelection(sel('net-webgpu', SHARED, 800), m.storage);
    expect(loadSelection(m.storage)).toEqual(sel('net-webgpu', SHARED, 800));
  });

  it('a damaged or foreign text gives the default; a bad field gives the default of that field only', () => {
    expect(parseSelection('{"engine":')).toEqual(DEFAULT_SELECTION);
    expect(parseSelection('[]')).toEqual(DEFAULT_SELECTION);
    expect(parseSelection('null')).toEqual(DEFAULT_SELECTION);
    expect(parseSelection(JSON.stringify({ engine: 'net-wasm', modelId: 'ghost', sims: 7 }))).toEqual(sel('net-wasm', null, 200));
    expect(parseSelection(JSON.stringify({ engine: 'evil<script>', modelId: SHARED, sims: 50 }))).toEqual(sel('java-wasmgc', SHARED, 50));
    expect(parseSelection(JSON.stringify({ engine: 'server:best', modelId: 42, sims: '800' }))).toEqual(sel('server:best', null, 200));
  });

  it('drops unknown fields', () => {
    const parsed = parseSelection(JSON.stringify({ engine: 'net-wasm', modelId: null, sims: 50, extra: 1 }));
    expect(Object.keys(parsed).sort()).toEqual(['engine', 'modelId', 'sims']);
  });

  it('survives storage that throws, and no storage at all', () => {
    const throwing = { getItem: () => { throw new Error('blocked'); }, setItem: () => { throw new Error('full'); } } as unknown as Storage;
    expect(loadSelection(throwing)).toEqual(DEFAULT_SELECTION);
    expect(() => saveSelection(sel('net-wasm', null), throwing)).not.toThrow();
    expect(loadSelection(null)).toEqual(DEFAULT_SELECTION);
    expect(() => saveSelection(sel('net-wasm', null), null)).not.toThrow();
  });

  it('returns a fresh object for the default: mutating it does not change DEFAULT_SELECTION', () => {
    const a = loadSelection(memory().storage);
    a.sims = 800;
    expect(DEFAULT_SELECTION.sims).toBe(200);
  });
});
