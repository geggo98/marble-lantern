// The game variant: which jump rule and how many players. The default (adjacent jumps, two players) is the game the
// app has always played; every other variant is opt-in (docs/web-ui.md, section "Game variants").
export type JumpRule = 'adjacent' | 'long';
export type PlayerCount = 2 | 3;

/** A seat of the game: 0 moves first; the side to move is `ply % players`. */
export type Player = 0 | 1 | 2;

export interface Variant {
  jump: JumpRule;
  players: PlayerCount;
}

export const DEFAULT_VARIANT: Readonly<Variant> = Object.freeze({ jump: 'adjacent', players: 2 });

/** The seats of the variant in playing order: [0, 1] or [0, 1, 2]. */
export function sidesOf(variant: Variant): readonly Player[] {
  return variant.players === 3 ? [0, 1, 2] : [0, 1];
}

/** Plies after which a game ends without a winner (RuleSet.defaults(players): 200 for two players, 300 for three). */
export function moveLimit(variant: Variant): number {
  return variant.players === 3 ? 300 : 200;
}

export function isDefault(variant: Variant): boolean {
  return variant.jump === 'adjacent' && variant.players === 2;
}

export function sameVariant(a: Variant, b: Variant): boolean {
  return a.jump === b.jump && a.players === b.players;
}

/** The side to move after `ply` plies. */
export function sideAt(ply: number, variant: Variant): Player {
  return (ply % variant.players) as Player;
}

/** Stable text for keys, e.g. "adjacent-2". */
export function variantKey(variant: Variant): string {
  return `${variant.jump}-${variant.players}`;
}
