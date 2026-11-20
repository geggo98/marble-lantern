import { describe, expect, it } from 'vitest';
import { DEFAULT_VARIANT } from '../../game/variant';
import type { Variant } from '../../game/variant';
import type { EngineId } from '../engine';
import { listModels } from './models';
import type { ModelInfo } from './models';
import {
  DEFAULT_SELECTION, LAST_MODEL_KEY, SELECTION_KEY, bestModel, evaluateSelection, loadLastModel, loadSelection, parseSelection, resolveModel,
  saveLastModel, saveSelection,
} from './selection';
import type { NetAvailability, NetSelection } from './selection';

const ADJ2 = DEFAULT_VARIANT as Variant;
const ADJ3: Variant = { jump: 'adjacent', players: 3 };
const EXPERT = 'small-2p-adjacent-expert';
const SHARED = 'small-shared-all';
const all: NetAvailability = { wasm: true, webgpu: true };
const sel = (engine: EngineId, modelId: string | null, sims: NetSelection['sims'] = 200): NetSelection => ({ engine, modelId, sims });
/** A selection with no model chosen (the app picks one). */
const auto = (engine: EngineId, sims: NetSelection['sims'] = 200): NetSelection => ({ engine, sims });
const LONG2: Variant = { jump: 'long', players: 2 };
const LONG3: Variant = { jump: 'long', players: 3 };
const expertOnly = (): ModelInfo[] => listModels().filter((m) => m.id === EXPERT);
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

  it('the expert model is fine for adjacent two-player and a mismatch for the others when no model fits them', () => {
    expect(evaluateSelection(sel('net-wasm', EXPERT), ADJ2, all)).toMatchObject({ usable: true, warnings: [], effectiveBackend: 'wasm', modelId: EXPERT, autoModel: false });
    const bad = evaluateSelection(sel('net-wasm', EXPERT), ADJ3, all, expertOnly());
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

  // docs/web-ui.md, "Choosing a net engine": the models were trained with closed foreign corners only, so none fits the other rules
  it('no model fits open or pass-through foreign corners: no-model when nothing is chosen, a mismatch for a chosen model', () => {
    for (const camps of ['pass-through', 'open'] as const) {
      for (const jump of ['adjacent', 'long'] as const) {
        for (const players of [2, 3] as const) {
          const variant: Variant = { jump, players, camps };
          expect(codes(evaluateSelection(auto('net-wasm'), variant, all).warnings), `${camps} ${jump} ${players}`).toEqual(['no-model', 'fallback-random']);
          expect(codes(evaluateSelection(sel('net-wasm', SHARED), variant, all).warnings)).toEqual(['model-variant-mismatch', 'fallback-random']);
        }
      }
    }
    expect(evaluateSelection(sel('net-wasm', SHARED), { jump: 'long', players: 3, camps: 'closed' }, all).usable).toBe(true);
    expect(evaluateSelection(sel('cpp-wasm', null), { jump: 'adjacent', players: 2, camps: 'open' }, all).usable).toBe(true);
  });

  // every shipped model was trained on the 4-row start with 10 pieces (docs/web-ui.md, "Choosing a net engine")
  it('no model fits the 5-row board: no-model when nothing is chosen, a mismatch for a chosen model, in all four variants', () => {
    for (const jump of ['adjacent', 'long'] as const) {
      for (const players of [2, 3] as const) {
        const variant: Variant = { jump, players, rows: 5 };
        expect(bestModel(variant)).toBeNull();
        expect(codes(evaluateSelection(auto('net-wasm'), variant, all).warnings), `${jump} ${players}`).toEqual(['no-model', 'fallback-random']);
        expect(codes(evaluateSelection(sel('net-wasm', SHARED), variant, all).warnings)).toEqual(['model-variant-mismatch', 'fallback-random']);
        // the same game on 4 rows has a model, so the 5-row refusal is about the board
        expect(evaluateSelection(sel('net-wasm', SHARED), { jump, players }, all).usable).toBe(true);
      }
    }
    expect(evaluateSelection(sel('cpp-wasm', null), { jump: 'adjacent', players: 2, rows: 5 }, all).usable).toBe(true);
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

describe('resolveModel (the model that plays)', () => {
  it('"None" chosen: none, also when models exist', () => {
    expect(resolveModel({ modelId: null }, ADJ2)).toEqual({ modelId: null, auto: false });
    expect(resolveModel({ modelId: null }, ADJ2, listModels(), SHARED)).toEqual({ modelId: null, auto: false });
  });

  it('a chosen model that fits the variant plays, whatever the last model', () => {
    expect(resolveModel({ modelId: SHARED }, ADJ2, listModels(), EXPERT)).toEqual({ modelId: SHARED, auto: false });
  });

  it('a chosen model that does not fit is replaced by the best fitting one', () => {
    expect(resolveModel({ modelId: EXPERT }, ADJ3)).toEqual({ modelId: SHARED, auto: true });
    expect(resolveModel({ modelId: EXPERT }, LONG2)).toEqual({ modelId: SHARED, auto: true });
  });

  it('a chosen model that does not fit, and nothing fits: it stays (the engine reports the mismatch)', () => {
    expect(resolveModel({ modelId: EXPERT }, ADJ3, expertOnly())).toEqual({ modelId: EXPERT, auto: false });
  });

  it('a chosen id that is not in the manifest stays (model-load-failed), it is not silently replaced', () => {
    expect(resolveModel({ modelId: 'ghost' }, ADJ2)).toEqual({ modelId: 'ghost', auto: false });
  });

  it('nothing chosen: the last model if it is in the manifest and fits the variant', () => {
    expect(resolveModel({}, ADJ2, listModels(), SHARED)).toEqual({ modelId: SHARED, auto: true });
  });

  it('nothing chosen, the last model vanished from the manifest: the strongest', () => {
    expect(resolveModel({}, ADJ2, listModels(), 'removed-model')).toEqual({ modelId: EXPERT, auto: true });
  });

  it('nothing chosen, the last model does not fit the variant: the best that fits', () => {
    expect(resolveModel({}, ADJ3, listModels(), EXPERT)).toEqual({ modelId: SHARED, auto: true });
    expect(resolveModel({ modelId: undefined }, LONG3, listModels(), EXPERT)).toEqual({ modelId: SHARED, auto: true });
  });

  it('nothing chosen and nothing available for the variant: none, which the engine reports as no-model (never a mismatch)', () => {
    expect(resolveModel({}, ADJ3, expertOnly(), EXPERT)).toEqual({ modelId: null, auto: true });
    expect(resolveModel({}, ADJ2, [])).toEqual({ modelId: null, auto: true });
    expect(codes(evaluateSelection(auto('net-wasm'), ADJ3, all, expertOnly()).warnings)).toEqual(['no-model', 'fallback-random']);
  });

  it('evaluateSelection judges the picked model: nothing chosen means a net plays', () => {
    expect(evaluateSelection(auto('net-wasm'), ADJ2, all)).toMatchObject({ usable: true, warnings: [], modelId: EXPERT, autoModel: true });
    expect(evaluateSelection(auto('net-wasm'), ADJ3, all, listModels(), EXPERT)).toMatchObject({ usable: true, modelId: SHARED, autoModel: true });
    expect(evaluateSelection(auto('cpp-wasm'), ADJ2, all)).toMatchObject({ modelId: null, autoModel: false });
  });
});

describe('bestModel', () => {
  const entry = (id: string, params: number, strength?: Record<string, unknown>): ModelInfo => ({
    ...listModels()[0]!, id, params, variants: [ADJ2], strength, // no figures unless given (the bundled entry has its own)
  });

  it('with the bundled manifest: the expert for adjacent 2 players, the shared model elsewhere', () => {
    expect(bestModel(ADJ2)?.id).toBe(EXPERT);
    expect(bestModel(LONG2)?.id).toBe(SHARED);
    expect(bestModel(ADJ3)?.id).toBe(SHARED);
    expect(bestModel(LONG3)?.id).toBe(SHARED);
  });

  it('a figure against a larger opponent wins, then the better figure; a model with a figure beats one without', () => {
    const weak = entry('a-weak', 10, { mcts200_vs_ab1600_adjacent: { elo: 300 } });
    const strong = entry('b-strong', 5, { mcts200_vs_ab25600_adjacent: { elo: -50 } });
    const better = entry('c-better', 5, { mcts200_vs_ab25600_adjacent: { elo: 20 } });
    const big = entry('d-big', 999_999);
    expect(bestModel(ADJ2, [weak, strong, big])?.id).toBe('b-strong');
    expect(bestModel(ADJ2, [weak, strong, better, big])?.id).toBe('c-better');
    expect(bestModel(ADJ2, [big, weak])?.id).toBe('a-weak');
  });

  it('a figure of another variant does not count; without figures the larger model wins, then the id', () => {
    const otherVariant = entry('a-long', 1, { mcts200_vs_ab25600_long: { elo: 900 } });
    const big = entry('b-big', 100);
    const twin = entry('c-twin', 100);
    expect(bestModel(ADJ2, [otherVariant, big])?.id).toBe('b-big');
    expect(bestModel(ADJ2, [twin, big])?.id).toBe('b-big');
    expect(bestModel(ADJ2, [])).toBeNull();
  });

  it('three players: the larger share of the points wins', () => {
    const v3 = (id: string, score: number): ModelInfo => ({ ...entry(id, 1), variants: [ADJ3], strength: { mcts200_3p_score_vs_2xab3_5000: { adjacent: score } } });
    expect(bestModel(ADJ3, [v3('a', 0.5), v3('b', 0.8), v3('c', 0.6)])?.id).toBe('b');
  });
});

describe('persistence', () => {
  const memory = (initial?: string): { storage: Storage; data: Map<string, string> } => {
    const data = new Map<string, string>();
    if (initial !== undefined) data.set(SELECTION_KEY, initial);
    const storage = { getItem: (k: string) => data.get(k) ?? null, setItem: (k: string, v: string) => void data.set(k, v) } as unknown as Storage;
    return { storage, data };
  };

  it('the default is the existing default engine with no model chosen and 200 simulations', () => {
    expect(DEFAULT_SELECTION).toEqual({ engine: 'java-wasmgc', sims: 200 });
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
    expect(parseSelection(JSON.stringify({ engine: 'net-wasm', modelId: 'ghost', sims: 7 }))).toEqual(auto('net-wasm', 200));
    expect(parseSelection(JSON.stringify({ engine: 'evil<script>', modelId: SHARED, sims: 50 }))).toEqual(sel('java-wasmgc', SHARED, 50));
    expect(parseSelection(JSON.stringify({ engine: 'server:best', modelId: 42, sims: '800' }))).toEqual(auto('server:best', 200));
  });

  it('version 1 (no v): a stored null was the default, so it means nothing chosen; version 2: null is the choice "None"', () => {
    expect(parseSelection(JSON.stringify({ engine: 'net-wasm', modelId: null, sims: 50 }))).toEqual(auto('net-wasm', 50));
    expect(parseSelection(JSON.stringify({ v: 2, engine: 'net-wasm', modelId: null, sims: 50 }))).toEqual(sel('net-wasm', null, 50));
    expect(parseSelection(JSON.stringify({ v: 2, engine: 'net-wasm', sims: 50 }))).toEqual(auto('net-wasm', 50));
    expect(parseSelection(JSON.stringify({ v: 1, engine: 'net-wasm', modelId: null, sims: 50 }))).toEqual(auto('net-wasm', 50));
    expect(parseSelection(JSON.stringify({ engine: 'net-wasm', modelId: SHARED, sims: 50 }))).toEqual(sel('net-wasm', SHARED, 50)); // a model of version 1 stays
  });

  it('a stored model that vanished from the manifest means nothing chosen, also in version 2', () => {
    expect(parseSelection(JSON.stringify({ v: 2, engine: 'net-wasm', modelId: EXPERT, sims: 200 }), expertOnly())).toEqual(sel('net-wasm', EXPERT));
    expect(parseSelection(JSON.stringify({ v: 2, engine: 'net-wasm', modelId: SHARED, sims: 200 }), expertOnly())).toEqual(auto('net-wasm'));
  });

  it('writes version 2: the choice "None" round-trips as null, nothing chosen as a missing field', () => {
    const m = memory();
    saveSelection(sel('net-wasm', null), m.storage);
    expect(JSON.parse(m.data.get(SELECTION_KEY)!)).toEqual({ v: 2, engine: 'net-wasm', modelId: null, sims: 200 });
    expect(loadSelection(m.storage)).toEqual(sel('net-wasm', null));
    saveSelection(auto('net-wasm'), m.storage);
    expect(JSON.parse(m.data.get(SELECTION_KEY)!)).toEqual({ v: 2, engine: 'net-wasm', sims: 200 });
    expect(loadSelection(m.storage)).toEqual(auto('net-wasm'));
  });

  it('drops unknown fields', () => {
    const parsed = parseSelection(JSON.stringify({ v: 2, engine: 'net-wasm', modelId: null, sims: 50, extra: 1 }));
    expect(Object.keys(parsed).sort()).toEqual(['engine', 'modelId', 'sims']);
  });

  it('the last model: round-trips, is dropped when it vanished from the manifest, survives blocked storage', () => {
    const m = memory();
    expect(loadLastModel(m.storage)).toBeNull();
    saveLastModel(SHARED, m.storage);
    expect(m.data.get(LAST_MODEL_KEY)).toBe(SHARED);
    expect(loadLastModel(m.storage)).toBe(SHARED);
    expect(loadLastModel(m.storage, expertOnly())).toBeNull();
    const throwing = { getItem: () => { throw new Error('blocked'); }, setItem: () => { throw new Error('full'); } } as unknown as Storage;
    expect(loadLastModel(throwing)).toBeNull();
    expect(() => saveLastModel(SHARED, throwing)).not.toThrow();
    expect(loadLastModel(null)).toBeNull();
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
