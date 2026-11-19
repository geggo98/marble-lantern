// What the person chose for the net engines (engine, model, simulations per move) and what that means for a game variant:
// evaluateSelection tells the UI BEFORE a game starts whether a net will play and which warnings the engine will report, with the
// same rules as the engine core (core.ts, IC4). The last selection is kept in localStorage as a per-viewer convenience.
import type { Variant } from '../../game/variant';
import type { EngineId } from '../engine';
import { isNetEngine } from '../engine';
import { DEFAULT_SIMS, SIMS_PRESETS } from '../warnings';
import type { EngineWarning, Sims } from '../warnings';
import { listModels, modelFits, modelsFor } from './model-manifest';
import type { ModelInfo } from './model-manifest';

export interface NetSelection {
  /** The engine the person wants: a net engine or any other id (then model and sims do not matter). */
  engine: EngineId;
  /** The id of a model of models.json, or null for none (the default). */
  modelId: string | null;
  /** Simulations per move (net engines): 50, 200 or 800. */
  sims: Sims;
}

/** The existing default engine of the offline mode (ADR 0003) and no model. The session still decides what is really loaded first. */
export const DEFAULT_SELECTION: Readonly<NetSelection> = Object.freeze({ engine: 'java-wasmgc', modelId: null, sims: DEFAULT_SIMS });

/** What this browser can run. */
export interface NetAvailability {
  /** WebAssembly and workers: the CPU backend can run. */
  wasm: boolean;
  /** The WebGPU probe (isWebGpuAvailable): true or false, or null while it has not answered yet (treated as available). */
  webgpu: boolean | null;
  /** Why WebGPU is not usable, from the probe. */
  webgpuReason?: string;
}

export interface SelectionEvaluation {
  /** A net will play: no fallback-random warning. Always true for the other engines. */
  usable: boolean;
  /** What the engine will report at the start of the game; empty when all is well. */
  warnings: EngineWarning[];
  /** What produces the moves: the CPU net, the GPU net, random legal moves, or 'classical' for the engines without a net. */
  effectiveBackend: 'wasm' | 'webgpu' | 'random' | 'classical';
  /** The models that may play this variant (modelsFor), for the model list. */
  compatibleModels: ModelInfo[];
}

/** Judges a selection for a variant without loading anything. `models` is a test hook (default: models.json). */
export function evaluateSelection(
  selection: NetSelection,
  variant: Variant,
  availability: NetAvailability,
  models: readonly ModelInfo[] = listModels(),
): SelectionEvaluation {
  const compatibleModels = modelsFor(variant, models);
  if (!isNetEngine(selection.engine)) return { usable: true, warnings: [], effectiveBackend: 'classical', compatibleModels };
  const random = (...causes: EngineWarning[]): SelectionEvaluation => ({
    usable: false,
    warnings: [...causes, { code: 'fallback-random', ...(selection.modelId ? { modelId: selection.modelId } : {}), variant }],
    effectiveBackend: 'random',
    compatibleModels,
  });
  if (selection.modelId === null) return random({ code: 'no-model', variant });
  const info = models.find((m) => m.id === selection.modelId);
  if (!info) return random({ code: 'model-load-failed', modelId: selection.modelId, variant, reason: `no model with the id ${selection.modelId}` });
  if (!modelFits(info, variant)) {
    return random({ code: 'model-variant-mismatch', modelId: info.id, variant, reason: `the model ${info.id} does not play this variant` });
  }
  const warnings: EngineWarning[] = [];
  if (selection.engine === 'net-webgpu') {
    // null (the probe has not answered) counts as available: the engine itself asks again and falls back if it is wrong.
    if (availability.webgpu !== false) return { usable: true, warnings, effectiveBackend: 'webgpu', compatibleModels };
    // No WebGPU: the CPU net plays, if it can.
    const reason = availability.webgpuReason ?? 'WebGPU is not available';
    if (availability.wasm) {
      warnings.push({ code: 'backend-unavailable', backend: 'webgpu', fallback: 'wasm', modelId: info.id, variant, reason });
      return { usable: true, warnings, effectiveBackend: 'wasm', compatibleModels };
    }
    return random({ code: 'backend-unavailable', backend: 'webgpu', fallback: 'random', modelId: info.id, variant, reason });
  }
  if (!availability.wasm) {
    return random({ code: 'backend-unavailable', backend: 'wasm', fallback: 'random', modelId: info.id, variant, reason: 'WebAssembly is not available' });
  }
  return { usable: true, warnings, effectiveBackend: 'wasm', compatibleModels };
}

// ---- persistence ----

export const SELECTION_KEY = 'ml.net-selection';

const ENGINE_ID = /^(cpp-wasm|java-wasmgc|java-js|net-wasm|net-webgpu|server:[A-Za-z0-9._-]{1,64})$/;

/**
 * The selection a stored text holds. Anything that is not valid gives the default for that field: a damaged text gives the default
 * selection, an unknown engine id the default engine, an unknown model id or a sims value that is not a preset the defaults.
 * Unknown fields are dropped. `models` is a test hook.
 */
export function parseSelection(text: string | null, models: readonly ModelInfo[] = listModels()): NetSelection {
  const fallback = (): NetSelection => ({ ...DEFAULT_SELECTION });
  if (text === null) return fallback();
  try {
    const raw = JSON.parse(text) as Record<string, unknown> | null;
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return fallback();
    const engine = typeof raw.engine === 'string' && ENGINE_ID.test(raw.engine) ? (raw.engine as EngineId) : DEFAULT_SELECTION.engine;
    const modelId = typeof raw.modelId === 'string' && models.some((m) => m.id === raw.modelId) ? raw.modelId : null;
    const sims = (SIMS_PRESETS as readonly unknown[]).includes(raw.sims) ? (raw.sims as Sims) : DEFAULT_SELECTION.sims;
    return { engine, modelId, sims };
  } catch {
    return fallback();
  }
}

export function loadSelection(storage: Pick<Storage, 'getItem'> | null = defaultStorage(), models?: readonly ModelInfo[]): NetSelection {
  try {
    return parseSelection(storage?.getItem(SELECTION_KEY) ?? null, models);
  } catch {
    return { ...DEFAULT_SELECTION };
  }
}

export function saveSelection(selection: NetSelection, storage: Pick<Storage, 'setItem'> | null = defaultStorage()): void {
  try {
    storage?.setItem(SELECTION_KEY, JSON.stringify(selection));
  } catch {
    // blocked or full: the next visit starts from the default
  }
}

function defaultStorage(): Storage | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage;
  } catch {
    return null;
  }
}
