// Which positions the live modes can explain, and why a live switch is disabled. Every live figure runs for the classic game on both board
// sizes: two players, adjacent jumps, closed foreign corners, 4 or 5 rows. The classical engines play both sizes, and the linear-r3 features
// exist for both (WebCore.features, shw_features). The net figures use the 4-row expert on 4 rows and the 5-row expert on 5 rows
// (liveModelId in mcts.ts): a network plays only the board sizes its file lists (metadata key triangle_rows, WebNetCore.rowsRefused).
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
export type LiveReason = 'nosnapshot' | 'variant' | 'over' | 'nonet';

/**
 * Why My-game mode is not available, or null when it is. `engine` is the kind of engine the figure needs. The order: no snapshot, variant,
 * finished game, then (net figures) no net engine in this browser.
 */
export function liveBlockedReason(input: LiveInput | null, engine: 'classical' | 'net'): LiveReason | null {
  if (!input) return 'nosnapshot';
  const variant = input.snapshot.variant;
  if (!isClassicAnySize(variant)) return 'variant';
  if (input.snapshot.over) return 'over';
  if (engine === 'net' && input.sources().net === null) return 'nonet';
  return null;
}
