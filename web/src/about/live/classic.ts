// Which positions the live modes can explain, and why a live switch is disabled. The classical figures run for the classic game on both board
// sizes: two players, adjacent jumps, closed foreign corners, 4 or 5 rows. The classical engines play both sizes, and the linear-r3 features
// exist for both (WebCore.features, shw_features). The net figures run on 4 rows only: every shipped network was trained on the 4-row board,
// and its input planes mark the 10-cell corners, so the net engines refuse a 5-row game (WebNetCore.fiveRowsRefused, docs/web-ui.md).
import { campsOf, rowsOf } from '../../game/variant';
import type { Variant } from '../../game/variant';
import type { LiveInput } from './types';

/** The classic game on 4 rows: the game every network and every example of the figures was made for. */
export function isClassic(variant: Variant): boolean {
  return isClassicAnySize(variant) && rowsOf(variant) === 4;
}

/** The classic game on either board size: two players, adjacent jumps, closed foreign corners, 4 or 5 rows. */
export function isClassicAnySize(variant: Variant): boolean {
  return variant.players === 2 && variant.jump === 'adjacent' && campsOf(variant) === 'closed';
}

/** The reasons, as the suffix of the catalog key `kit.live.reason.<reason>`. */
export type LiveReason = 'nosnapshot' | 'variant' | 'rows5' | 'over' | 'nonet';

/**
 * Why My-game mode is not available, or null when it is. `engine` is the kind of engine the figure needs. The order: no snapshot, variant,
 * (net figures) 5 rows, finished game, then (net figures) no net engine in this browser.
 */
export function liveBlockedReason(input: LiveInput | null, engine: 'classical' | 'net'): LiveReason | null {
  if (!input) return 'nosnapshot';
  const variant = input.snapshot.variant;
  if (!isClassicAnySize(variant)) return 'variant';
  if (engine === 'net' && rowsOf(variant) !== 4) return 'rows5';
  if (input.snapshot.over) return 'over';
  if (engine === 'net' && input.sources().net === null) return 'nonet';
  return null;
}
