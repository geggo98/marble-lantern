// Warnings of the net engines (interface contract IC4). A net engine always answers choose(); when it cannot use the net it
// plays a random legal move and says why. Each warning carries the data for its message; the UI maps `code` to an i18n text.
import type { Variant } from '../game/variant';

export type WarningCode =
  /** No model is selected. */
  | 'no-model'
  /** The model may not play this variant (models.json), or its planes or players do not fit (bad-weights-info of the Java side). */
  | 'model-variant-mismatch'
  /** The wanted backend cannot run: no WebGPU adapter, a lost device, no WebAssembly flavour. */
  | 'backend-unavailable'
  /** The model file could not be fetched, failed verification, or the backend rejected the weights. */
  | 'model-load-failed'
  /** Consequence of one of the causes above: the engine plays random legal moves. */
  | 'fallback-random';

/**
 * Why a warning happened, as a code the UI turns into a localized text (net.reason.<code>). The technical cause in English goes in
 * `detail`, which the UI shows only in a collapsed "Details".
 */
export type ReasonCode =
  | 'no-model-id' // the selected model id is not in models.json
  | 'variant-not-supported' // the model does not play this variant, or its planes or players do not fit
  | 'webassembly-unavailable' // no WebAssembly or no CPU backend
  | 'webgpu-no-api' // no navigator.gpu (no WebGPU in this browser, or not a secure context)
  | 'webgpu-no-adapter' // requestAdapter() gave nothing, or timed out
  | 'webgpu-device-failed' // requesting or creating the GPU device failed
  | 'webgpu-lost' // the GPU device was lost
  | 'load-failed' // the model file could not be fetched or read, or the backend rejected the weights
  | 'verify-failed' // the model file does not match its checksum or manifest entry
  | 'unknown';

/** What a WebGPU probe says: usable or not, and when not, why. */
export interface ProbeResult {
  available: boolean;
  reason?: ReasonCode;
  /** English text of the technical cause, for the collapsed "Details". */
  detail?: string;
}

export interface EngineWarning {
  code: WarningCode;
  /** The selected model, when there is one. */
  modelId?: string;
  /** The variant of the game. */
  variant?: Variant;
  /** The cause as a code; the message text per code is a catalog entry. */
  reason?: ReasonCode;
  /** English text of the technical cause, for the log and the collapsed "Details" of the message. */
  detail?: string;
  /** backend-unavailable: the backend that was wanted. */
  backend?: 'wasm' | 'webgpu';
  /**
   * backend-unavailable: what plays instead. 'wasm' is the CPU net (the warning is then the only one); 'random' means no net can
   * be used and a fallback-random warning follows.
   */
  fallback?: 'wasm' | 'random';
}

/** Net settings that travel with newGame. */
export interface NetOptions {
  /** Simulations per move. */
  sims: number;
  /** Leaves per forward pass (1 to 16). Default: 1 for the CPU backend, 4 for WebGPU (8 from 800 simulations). */
  batch?: number;
}

export const SIMS_PRESETS = [50, 200, 800] as const;
export type Sims = (typeof SIMS_PRESETS)[number];
export const DEFAULT_SIMS: Sims = 200;

/** Default leaves per forward pass: the CPU batches nothing; WebGPU batches 4, and 8 once the search is long enough to afford it. */
export function defaultBatch(backend: 'wasm' | 'webgpu', sims: number): number {
  if (backend === 'wasm') return 1;
  return sims >= 800 ? 8 : 4;
}

export const hasCode = (warnings: readonly EngineWarning[], code: WarningCode): boolean => warnings.some((w) => w.code === code);
