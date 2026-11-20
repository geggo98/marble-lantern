// What the person chose for the net engines (engine, model, simulations per move) and what that means for a game variant:
// resolveModel picks the model that plays when nothing (or a model that cannot play the variant) was chosen, and evaluateSelection tells
// the UI BEFORE a game starts whether a net will play and which warnings the engine will report, with the same rules as the engine core
// (core.ts, IC4). The last selection and the last chosen model are kept in localStorage as a per-viewer convenience.
import type { Variant } from '../../game/variant';
import type { EngineId } from '../engine';
import { isNetEngine } from '../engine';
import { DEFAULT_SIMS, SIMS_PRESETS } from '../warnings';
import type { EngineWarning, ReasonCode, Sims } from '../warnings';
import { listModels, modelFits, modelsFor } from './model-manifest';
import type { ModelInfo } from './model-manifest';
import { allStrengthFacts } from './model-strength';

export interface NetSelection {
  /** The engine the person wants: a net engine or any other id (then model and sims do not matter). */
  engine: EngineId;
  /**
   * What the person chose in the model list. Absent (undefined): nothing chosen yet, the app picks the model for the variant of the game
   * (resolveModel). null: the person chose "None" (the engine plays random moves and says so). A string: the id of a model of models.json.
   */
  modelId?: string | null | undefined;
  /** Simulations per move (net engines): 50, 200 or 800. */
  sims: Sims;
}

/**
 * The existing default engine of the offline mode (ADR 0003), no model chosen (the app picks one) and the medium strength
 * (200 simulations). The session still decides what is really loaded first.
 */
export const DEFAULT_SELECTION: Readonly<NetSelection> = Object.freeze({ engine: 'java-wasmgc', sims: DEFAULT_SIMS });

// ---- the model that plays ----

/** How strong a model is in a variant, from its manifest figures: the largest opponent measured, then the figure against it. */
function strengthKey(info: ModelInfo, variant: Variant): [number, number] | null {
  let best: [number, number] | null = null;
  for (const f of allStrengthFacts(info)) {
    if (f.players !== variant.players || f.jump !== variant.jump) continue;
    const value = f.players === 2 ? (f.elo ?? 0) : (f.score ?? 0);
    if (!best || f.opponent > best[0] || (f.opponent === best[0] && value > best[1])) best = [f.opponent, value];
  }
  return best;
}

type Key = [number, number] | null;

function stronger(a: ModelInfo, ak: Key, b: ModelInfo, bk: Key): boolean {
  if (ak && !bk) return true;
  if (!ak && bk) return false;
  if (ak && bk && ak[0] !== bk[0]) return ak[0] > bk[0];
  if (ak && bk && ak[1] !== bk[1]) return ak[1] > bk[1];
  if (a.params !== b.params) return a.params > b.params;
  return a.id < b.id;
}

/**
 * The strongest model that may play the variant, or null when none may. A model with a strength figure for the variant beats one
 * without; between two with figures, the one measured against the larger node budget wins, then the better figure against it (Elo for
 * two players, the share of the points for three). The figures are not on one scale, so this is a rule of thumb, not a ranking by Elo.
 * Without figures the larger network (more weights) wins; the id breaks ties, so the answer does not depend on the manifest order.
 */
export function bestModel(variant: Variant, models: readonly ModelInfo[] = listModels()): ModelInfo | null {
  let best: { info: ModelInfo; key: Key } | null = null;
  for (const info of modelsFor(variant, models)) {
    const key = strengthKey(info, variant);
    if (!best || stronger(info, key, best.info, best.key)) best = { info, key };
  }
  return best?.info ?? null;
}

export interface ResolvedModel {
  /** The model the net engine gets for this variant, or null for none. */
  modelId: string | null;
  /** True when the app picked it (nothing chosen, or the choice cannot play the variant); false when it is the person's choice. */
  auto: boolean;
}

/**
 * The model a net engine plays a variant with:
 * - "None" chosen (null): none; the engine reports no-model.
 * - a chosen model that may play the variant, or an id that is not in the manifest (the engine reports model-load-failed): that one.
 * - a chosen model that cannot play the variant: the best model for the variant; when there is none, the chosen one stays and the
 *   engine reports model-variant-mismatch. That warning therefore comes only from a choice of the person.
 * - nothing chosen: the model chosen last (`lastModel`) if it is in the manifest and may play the variant, else the best one, else none.
 */
export function resolveModel(
  selection: Pick<NetSelection, 'modelId'>,
  variant: Variant,
  models: readonly ModelInfo[] = listModels(),
  lastModel: string | null = null,
): ResolvedModel {
  const chosen = selection.modelId;
  if (chosen === null) return { modelId: null, auto: false };
  if (chosen !== undefined) {
    const info = models.find((m) => m.id === chosen);
    if (!info || modelFits(info, variant)) return { modelId: chosen, auto: false };
    const best = bestModel(variant, models);
    return best ? { modelId: best.id, auto: true } : { modelId: chosen, auto: false };
  }
  const last = lastModel === null ? undefined : models.find((m) => m.id === lastModel);
  if (last && modelFits(last, variant)) return { modelId: last.id, auto: true };
  return { modelId: bestModel(variant, models)?.id ?? null, auto: true };
}

/** What this browser can run. */
export interface NetAvailability {
  /** WebAssembly and workers: the CPU backend can run. */
  wasm: boolean;
  /** The WebGPU probe (isWebGpuAvailable): true or false, or null while it has not answered yet (treated as available). */
  webgpu: boolean | null;
  /** Why WebGPU is not usable, from the probe, as a code. */
  webgpuReason?: ReasonCode;
  /** The technical English text of the cause (the collapsed "Details"). */
  webgpuDetail?: string;
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
  /** The model a net engine plays with (resolveModel); null for none and for the engines without a net. */
  modelId: string | null;
  /** True when the app picked the model (resolveModel). */
  autoModel: boolean;
}

/**
 * Judges a selection for a variant without loading anything. `models` is a test hook (default: models.json); `lastModel` is the model
 * chosen last (resolveModel).
 */
export function evaluateSelection(
  selection: NetSelection,
  variant: Variant,
  availability: NetAvailability,
  models: readonly ModelInfo[] = listModels(),
  lastModel: string | null = null,
): SelectionEvaluation {
  const compatibleModels = modelsFor(variant, models);
  if (!isNetEngine(selection.engine)) return { usable: true, warnings: [], effectiveBackend: 'classical', compatibleModels, modelId: null, autoModel: false };
  const { modelId, auto: autoModel } = resolveModel(selection, variant, models, lastModel);
  const result = (usable: boolean, warnings: EngineWarning[], effectiveBackend: SelectionEvaluation['effectiveBackend']): SelectionEvaluation =>
    ({ usable, warnings, effectiveBackend, compatibleModels, modelId, autoModel });
  const random = (...causes: EngineWarning[]): SelectionEvaluation =>
    result(false, [...causes, { code: 'fallback-random', ...(modelId ? { modelId } : {}), variant }], 'random');
  if (modelId === null) return random({ code: 'no-model', variant });
  const info = models.find((m) => m.id === modelId);
  if (!info) return random({ code: 'model-load-failed', modelId, variant, reason: 'no-model-id', detail: `no model with the id ${modelId}` });
  if (!modelFits(info, variant)) {
    return random({ code: 'model-variant-mismatch', modelId: info.id, variant, reason: 'variant-not-supported', detail: `the model ${info.id} does not play this variant` });
  }
  const warnings: EngineWarning[] = [];
  if (selection.engine === 'net-webgpu') {
    // null (the probe has not answered) counts as available: the engine itself asks again and falls back if it is wrong.
    if (availability.webgpu !== false) return result(true, warnings, 'webgpu');
    // No WebGPU: the CPU net plays, if it can.
    const why = { reason: availability.webgpuReason ?? 'webgpu-no-adapter', ...(availability.webgpuDetail ? { detail: availability.webgpuDetail } : {}) } as const;
    if (availability.wasm) {
      warnings.push({ code: 'backend-unavailable', backend: 'webgpu', fallback: 'wasm', modelId: info.id, variant, ...why });
      return result(true, warnings, 'wasm');
    }
    return random({ code: 'backend-unavailable', backend: 'webgpu', fallback: 'random', modelId: info.id, variant, ...why });
  }
  if (!availability.wasm) {
    return random({ code: 'backend-unavailable', backend: 'wasm', fallback: 'random', modelId: info.id, variant, reason: 'webassembly-unavailable', detail: 'WebAssembly is not available' });
  }
  return result(true, warnings, 'wasm');
}

// ---- persistence ----

export const SELECTION_KEY = 'ml.net-selection';
/** The model the person chose last, kept apart from the selection: resolveModel falls back to it when nothing is chosen. */
export const LAST_MODEL_KEY = 'ml.net-last-model';
/**
 * The format of the stored selection. Version 1 (no field `v`) wrote `modelId: null` for its default, which the person could not tell
 * from the choice "None", so a stored null of version 1 means "nothing chosen". From version 2 on, null is the choice "None" and a
 * missing modelId means "nothing chosen".
 */
export const SELECTION_VERSION = 2;

const ENGINE_ID = /^(cpp-wasm|java-wasmgc|java-js|net-wasm|net-webgpu|server:[A-Za-z0-9._-]{1,64})$/;

/**
 * The selection a stored text holds. Anything that is not valid gives the default for that field: a damaged text gives the default
 * selection, an unknown engine id the default engine, a sims value that is not a preset 200. A model id that is not in the manifest
 * (the model was removed) and a null of version 1 mean "nothing chosen"; a null of version 2 is the choice "None". Unknown fields
 * are dropped. `models` is a test hook.
 */
export function parseSelection(text: string | null, models: readonly ModelInfo[] = listModels()): NetSelection {
  const fallback = (): NetSelection => ({ ...DEFAULT_SELECTION });
  if (text === null) return fallback();
  try {
    const raw = JSON.parse(text) as Record<string, unknown> | null;
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return fallback();
    const engine = typeof raw.engine === 'string' && ENGINE_ID.test(raw.engine) ? (raw.engine as EngineId) : DEFAULT_SELECTION.engine;
    const sims = (SIMS_PRESETS as readonly unknown[]).includes(raw.sims) ? (raw.sims as Sims) : DEFAULT_SELECTION.sims;
    if (typeof raw.modelId === 'string' && models.some((m) => m.id === raw.modelId)) return { engine, modelId: raw.modelId, sims };
    if (raw.modelId === null && raw.v === SELECTION_VERSION) return { engine, modelId: null, sims };
    return { engine, sims };
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
    const { engine, modelId, sims } = selection;
    storage?.setItem(SELECTION_KEY, JSON.stringify({ v: SELECTION_VERSION, engine, ...(modelId === undefined ? {} : { modelId }), sims }));
  } catch {
    // blocked or full: the next visit starts from the default
  }
}

/** The model chosen last, if it is still in the manifest; null otherwise and when storage is missing or blocked. */
export function loadLastModel(storage: Pick<Storage, 'getItem'> | null = defaultStorage(), models: readonly ModelInfo[] = listModels()): string | null {
  try {
    const id = storage?.getItem(LAST_MODEL_KEY) ?? null;
    return id !== null && models.some((m) => m.id === id) ? id : null;
  } catch {
    return null;
  }
}

export function saveLastModel(modelId: string, storage: Pick<Storage, 'setItem'> | null = defaultStorage()): void {
  try {
    storage?.setItem(LAST_MODEL_KEY, modelId);
  } catch {
    // blocked or full: the app picks the best model instead
  }
}

function defaultStorage(): Storage | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage;
  } catch {
    return null;
  }
}
