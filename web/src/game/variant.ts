// The game variant: which jump rule and how many players. The default (adjacent jumps, two players) is the game the
// app has always played; every other variant is opt-in (docs/web-ui.md, section "Game variants").
import type { BoardRows } from '../board/cells';

export type JumpRule = 'adjacent' | 'long';
export type PlayerCount = 2 | 3;
/**
 * What happens after a seat has filled its goal (RuleSet.Ranking of the engine). `first`: the game ends, that seat has
 * won. `full`: the seat is out, the others play on for the lower ranks (three players; with two players the two values
 * play the same game). The game URL carries it as `r=full`; the controller switches to it after a win (GameController.continuePlay).
 */
export type RankingRule = 'first' | 'full';

/**
 * Whether a piece may enter a foreign corner, a corner triangle that is neither its start nor its goal corner (RuleSet.ForeignCamps of
 * the engine, docs/rules.md "Foreign corners"). `closed`: never, not even between two jumps (the rule the app has always played).
 * `pass-through`: a jump chain may land there between two jumps, but the move must not end there. `open`: a move may also end there.
 * The game URL carries it as `c=pass` or `c=open`.
 */
export type ForeignCamps = 'closed' | 'pass-through' | 'open';

/** A seat of the game: 0 moves first; the side to move is `ply % players`. */
export type Player = 0 | 1 | 2;

/** Rows of a house, 4 (10 pieces per seat) or 5 (15 pieces); the engine's RuleSet.TriangleRows (defined in board/cells.ts). */
export type { BoardRows };

export interface Variant {
  jump: JumpRule;
  players: PlayerCount;
  /** Missing means `first`, so every variant that was written before the field existed keeps its meaning. */
  ranking?: RankingRule;
  /** Missing means `closed`, so every variant that was written before the field existed keeps its meaning. */
  camps?: ForeignCamps;
  /** Missing means 4 rows, so every variant that was written before the field existed keeps its meaning. */
  rows?: BoardRows;
}

export const DEFAULT_VARIANT: Readonly<Variant> = Object.freeze({ jump: 'adjacent', players: 2 });

/** The ranking rule of the variant; `first` when the field is missing. */
export function rankingOf(variant: Variant): RankingRule {
  return variant.ranking ?? 'first';
}

/** The house size of the variant; 4 when the field is missing. */
export function rowsOf(variant: Variant): BoardRows {
  return variant.rows ?? 4;
}

/** The foreign-corner rule of the variant; `closed` when the field is missing. */
export function campsOf(variant: Variant): ForeignCamps {
  return variant.camps ?? 'closed';
}

/** The variant with another house size; 4 drops the field, so the result equals a variant without one. */
export function withRows(variant: Variant, rows: BoardRows): Variant {
  const { rows: _old, ...rest } = variant;
  return rows === 4 ? rest : { ...rest, rows };
}

/** The variant with another foreign-corner rule; `closed` drops the field, so the result equals a variant without one. */
export function withCamps(variant: Variant, camps: ForeignCamps): Variant {
  const { camps: _old, ...rest } = variant;
  return camps === 'closed' ? rest : { ...rest, camps };
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

/**
 * Plies after which a game ends without a winner (RuleSet.defaults(players, rows) of the engine): 200 for two players and 300 for
 * three on 4 rows, 250 and 375 on 5 rows (15 pieces move more plies; no measured 5-row game reached the limit, cpp/results/rows5-v1-2026-11-20.txt).
 * The C++ engine (shw_move_limit) and the TeaVM engine hold the same table; rows.cross.test.ts compares the three.
 */
export function moveLimit(variant: Variant): number {
  const five = rowsOf(variant) === 5;
  return variant.players === 3 ? (five ? 375 : 300) : five ? 250 : 200;
}

export function isDefault(variant: Variant): boolean {
  return variant.jump === 'adjacent' && variant.players === 2 && rankingOf(variant) === 'first' && campsOf(variant) === 'closed' && rowsOf(variant) === 4;
}

export function sameVariant(a: Variant, b: Variant): boolean {
  return a.jump === b.jump && a.players === b.players && rankingOf(a) === rankingOf(b) && campsOf(a) === campsOf(b) && rowsOf(a) === rowsOf(b);
}

/**
 * The side to move after `ply` plies in a game without seats that are out. Wrong for full ranking (`playsOn`): a seat that
 * is out is skipped there. Use `playState(history, variant).sideToMove` (board-state.ts) for every game.
 */
export function sideAt(ply: number, variant: Variant): Player {
  return (ply % variant.players) as Player;
}

/**
 * Stable text for keys, e.g. "adjacent-2"; "adjacent-3-full" for full ranking, "adjacent-2-open" or "adjacent-2-pass-through" for the
 * foreign-corner rules, plus "-rows5" on the 5-row board (the text of a `first`, `closed`, 4-row variant is unchanged).
 */
export function variantKey(variant: Variant): string {
  const camps = campsOf(variant);
  return `${variant.jump}-${variant.players}${rankingOf(variant) === 'full' ? '-full' : ''}${camps === 'closed' ? '' : `-${camps}`}${rowsOf(variant) === 4 ? '' : '-rows5'}`;
}
