// The game variant: which jump rule and how many players. The default (adjacent jumps, two players) is the game the
// app has always played; every other variant is opt-in (docs/web-ui.md, section "Game variants").
export type JumpRule = 'adjacent' | 'long';
export type PlayerCount = 2 | 3;
/**
 * What happens after a seat has filled its goal (RuleSet.Ranking of the engine). `first`: the game ends, that seat has
 * won. `full`: the seat is out, the others play on for the lower ranks (three players; with two players the two values
 * play the same game). Only the engines know the field so far; the game URL and the controller do not carry it yet.
 */
export type RankingRule = 'first' | 'full';

/** A seat of the game: 0 moves first; the side to move is `ply % players`. */
export type Player = 0 | 1 | 2;

export interface Variant {
  jump: JumpRule;
  players: PlayerCount;
  /** Missing means `first`, so every variant that was written before the field existed keeps its meaning. */
  ranking?: RankingRule;
}

export const DEFAULT_VARIANT: Readonly<Variant> = Object.freeze({ jump: 'adjacent', players: 2 });

/** The ranking rule of the variant; `first` when the field is missing. */
export function rankingOf(variant: Variant): RankingRule {
  return variant.ranking ?? 'first';
}

/** Whether a finished seat makes no more moves while the others play on: `full` ranking with three players. */
export function playsOn(variant: Variant): boolean {
  return rankingOf(variant) === 'full' && variant.players === 3;
}

/** The variant with another ranking rule; `first` drops the field, so the result equals a variant without one. */
export function withRanking(variant: Variant, ranking: RankingRule): Variant {
  const { ranking: _old, ...rest } = variant;
  return ranking === 'first' ? rest : { ...rest, ranking };
}

/** The seats of the variant in playing order: [0, 1] or [0, 1, 2]. */
export function sidesOf(variant: Variant): readonly Player[] {
  return variant.players === 3 ? [0, 1, 2] : [0, 1];
}

/** Plies after which a game ends without a winner (RuleSet.defaults(players): 200 for two players, 300 for three). */
export function moveLimit(variant: Variant): number {
  return variant.players === 3 ? 300 : 200;
}

export function isDefault(variant: Variant): boolean {
  return variant.jump === 'adjacent' && variant.players === 2 && rankingOf(variant) === 'first';
}

export function sameVariant(a: Variant, b: Variant): boolean {
  return a.jump === b.jump && a.players === b.players && rankingOf(a) === rankingOf(b);
}

/** The side to move after `ply` plies. */
export function sideAt(ply: number, variant: Variant): Player {
  return (ply % variant.players) as Player;
}

/** Stable text for keys, e.g. "adjacent-2"; "adjacent-3-full" for full ranking (the text of a `first` variant is unchanged). */
export function variantKey(variant: Variant): string {
  return `${variant.jump}-${variant.players}${rankingOf(variant) === 'full' ? '-full' : ''}`;
}
