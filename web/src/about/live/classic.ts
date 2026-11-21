// Which positions the live modes can explain, and why a live switch is disabled. The live modes run for classic positions only: two players,
// adjacent jumps, the 4-row board, closed foreign corners (the position the linear-r3 features and the shipped network are made for).
import { campsOf, rowsOf } from '../../game/variant';
import type { Variant } from '../../game/variant';
import type { LiveInput } from './types';

export function isClassic(variant: Variant): boolean {
  return variant.players === 2 && variant.jump === 'adjacent' && rowsOf(variant) === 4 && campsOf(variant) === 'closed';
}

/** The reasons, as the suffix of the catalog key `kit.live.reason.<reason>`. */
export type LiveReason = 'nosnapshot' | 'variant' | 'over' | 'nonet';

/**
 * Why My-game mode is not available, or null when it is. `engine` is the kind of engine the figure needs. The order is the order of the
 * table in the plan: no snapshot, variant, finished game, then (net figures) no net engine in this browser.
 */
export function liveBlockedReason(input: LiveInput | null, engine: 'classical' | 'net'): LiveReason | null {
  if (!input) return 'nosnapshot';
  if (!isClassic(input.snapshot.variant)) return 'variant';
  if (input.snapshot.over) return 'over';
  if (engine === 'net' && input.sources().net === null) return 'nonet';
  return null;
}
