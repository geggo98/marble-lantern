// Small helpers that the engine cores share for the game variant (jump rule and player count).
import { DEFAULT_VARIANT, rowsOf } from '../game/variant';
import type { Variant } from '../game/variant';
import type { GameSetup } from './engine';
import { EngineFailure } from './protocol';

export const variantOf = (setup: GameSetup): Variant => setup.variant ?? DEFAULT_VARIANT;

/**
 * Only the adjacent two-player game reads the race table. Long-hop play has no race play, and the three-player player has
 * none either, so those games run before (or without) a table. Neither does the 5-row board: the table was built for 10 pieces, and
 * the engines turn race play off there (RuleSet.TriangleRows).
 */
export const needsTable = (variant: Variant): boolean => variant.jump === 'adjacent' && variant.players === 2 && rowsOf(variant) === 4;

/** The `rows` argument of the TeaVM facades: "4" or "5". */
export const rowsArg = (variant: Variant): string => String(rowsOf(variant));

/** The node budget of each seat: `players` entries; a missing entry repeats the one before it (the third seat copies the second). */
export function seatNodes(setup: GameSetup): number[] {
  const variant = variantOf(setup);
  const first = setup.nodes[0];
  if (first === undefined) throw new EngineFailure('internal', 'newGame needs a node budget');
  const out: number[] = [];
  for (let seat = 0; seat < variant.players; seat++) out.push(setup.nodes[seat] ?? out[seat - 1] ?? first);
  return out;
}

/** The static evaluation vector (Engine.evaluate3) exists for three players only. */
export function requireThreePlayers(variant: Variant): void {
  if (variant.players !== 3) throw new EngineFailure('unsupported', 'the static evaluation vector is available for three players only');
}
