// The contract between the net-hints logic (useNetHints.ts, net-advisor.ts) and the net-hints UI (Board, chip, block, GuideSettings,
// PlayScreen). Design: the net-hints design document, section 6; plan: step 5. Types only, so that both sides can be built apart.
import type { Divergence } from './divergence';

/** What the chip, the status line and the net block show (design, section 4.4). */
export type NetHintState = 'off' | 'idle' | 'loading' | 'thinking' | 'marks' | 'none' | 'skipped' | 'failed' | 'unavailable';

/** Why the level "net" cannot be chosen, or null when it can (design, section 4.2). */
export type NetUnavailableReason =
  | { kind: 'checking' }
  | { kind: 'three-players' }
  /** The code is a key suffix of `net.reason.<code>` (webgpu-no-api, webgpu-no-adapter, webgpu-device-failed, webgpu-lost, no-model-id, variant-not-supported). */
  | { kind: 'reason'; code: string };

export interface NetAvailability {
  /** True when the level can be chosen and works. */
  available: boolean;
  reason: NetUnavailableReason | null;
  /** True after the GPU was lost during this session: the level falls back to "eval" until reload (design, section 4.2). */
  lostInSession: boolean;
}

/** What useNetHints returns to the play screen. */
export interface NetHintsModel {
  availability: NetAvailability;
  state: NetHintState;
  /** The marks of the current position, null when none. At most one of each, never both on one move. */
  trap: Divergence | null;
  special: Divergence | null;
}

/** What the board draws: move codes (`from | to << 8`). */
export interface BoardNetHints {
  trap?: number;
  special?: number;
}
