// What the start dialog asks: the jump rule, the foreign-corner rule, the game mode and the seats; and how that becomes a GameSetup. The last choice is
// kept in localStorage as a per-viewer convenience (every read and write is guarded: storage can be missing or blocked).
import { hotSeatVsEngine, humanVsEngine, humanVsTwoEngines, variantOf, withBoardRows, withForeignCamps } from '../game/setup';
import type { GameSetup } from '../game/setup';
import { withRanking } from '../game/variant';
import type { BoardRows, ForeignCamps, JumpRule, Player, RankingRule } from '../game/variant';

export type GameMode = 'two' | 'hotseat' | 'engines';

export interface StartChoice {
  jump: JumpRule;
  /** May pieces enter the foreign triangles (docs/rules.md, "Foreign corners")? A choice stored before the field existed reads as `closed`. */
  camps: ForeignCamps;
  /**
   * The house size of a NEW game: 4 rows (10 pieces) or 5 rows (15 pieces). The dialog preselects 5 unless the person chose a size: a
   * fresh visitor and a stored choice without the field, or without the version marker (CHOICE_VERSION), both read as 5. Only games that
   * already exist stay on 4 rows: a game link without `t=` (game-url.ts) and the board behind a link dialog (App.tsx).
   */
  rows: BoardRows;
  /**
   * What happens after the first player fills the goal, in a three-player mode (docs/rules.md, "Ranking"). `first` ends the game, `full` plays on
   * for 2nd place. A stored choice without the field reads as `first`; a two-player game ignores it.
   */
  ranking: RankingRule;
  mode: GameMode;
  /** Mode 'engines': the seat of the person (0 moves first). */
  humanSeat: Player;
  /** Mode 'hotseat': the seat of the engine. */
  engineSeat: Player;
}

export const DEFAULT_CHOICE: Readonly<StartChoice> = Object.freeze({ jump: 'adjacent', camps: 'closed', rows: 5, ranking: 'first', mode: 'two', humanSeat: 0, engineSeat: 2 });
export const CHOICE_KEY = 'ml.start-choice';
/**
 * Version of the stored choice. Version 2 (2026-11-20) marks a choice in which `rows` was chosen with the 5-row default in view: only then an
 * explicit 4 is a decision. A choice without the marker was written while 4 was preselected, so its 4 only records the old default.
 * Version 3 (2026-11-22): a version-2 4 may stem from a bug, not from a decision. After "New game" in the dialog of a game link the begin
 * dialog kept the link fallback (4 rows) and "Start" saved it with v:2. Version 2 therefore no longer vouches for a 4; only a 4 saved from
 * version 3 on is an explicit choice.
 */
export const CHOICE_VERSION = 3;

const isSeat = (v: unknown): v is Player => v === 0 || v === 1 || v === 2;

/** The choice a stored text holds; anything that is not a valid choice gives the default. Unknown fields are dropped. */
export function parseChoice(text: string | null): StartChoice {
  if (text === null) return { ...DEFAULT_CHOICE };
  try {
    const raw = JSON.parse(text) as Record<string, unknown> | null;
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return { ...DEFAULT_CHOICE };
    return {
      jump: raw.jump === 'long' ? 'long' : 'adjacent',
      camps: raw.camps === 'pass-through' || raw.camps === 'open' ? raw.camps : 'closed',
      // Only a choice with the version marker keeps an explicit 4. Without the marker the 4 was preselected, not chosen (owner wish 2026-11-20),
      // and a missing or invalid size is "no size chosen" too: both read as the default 5. The stored choice is the state of the dialog, not
      // of a game, so it cannot turn an existing game into a 5-row game (those come from links only).
      rows: raw.v === CHOICE_VERSION && raw.rows === 4 ? 4 : DEFAULT_CHOICE.rows,
      ranking: raw.ranking === 'full' ? 'full' : 'first',
      mode: raw.mode === 'hotseat' || raw.mode === 'engines' ? raw.mode : 'two',
      humanSeat: isSeat(raw.humanSeat) ? raw.humanSeat : DEFAULT_CHOICE.humanSeat,
      engineSeat: isSeat(raw.engineSeat) ? raw.engineSeat : DEFAULT_CHOICE.engineSeat,
    };
  } catch {
    return { ...DEFAULT_CHOICE };
  }
}

export function loadChoice(storage: Pick<Storage, 'getItem'> | null = defaultStorage()): StartChoice {
  try {
    return parseChoice(storage?.getItem(CHOICE_KEY) ?? null);
  } catch {
    return { ...DEFAULT_CHOICE };
  }
}

export function saveChoice(choice: StartChoice, storage: Pick<Storage, 'setItem'> | null = defaultStorage()): void {
  try {
    storage?.setItem(CHOICE_KEY, JSON.stringify({ ...choice, v: CHOICE_VERSION }));
  } catch {
    // blocked or full: the dialog starts from the default next time
  }
}

function defaultStorage(): Storage | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage;
  } catch {
    return null;
  }
}

/** The game of a choice. `humanBegins` answers "Do you want to begin?" and counts for two players only. */
export function setupOfChoice(choice: StartChoice, humanBegins: boolean, level: number): GameSetup {
  const base = withBoardRows(withForeignCamps(seatsOfChoice(choice, humanBegins, level), choice.camps), choice.rows);
  // The ranking rule has no effect with two players; the variant of a two-player game never carries it.
  return choice.mode === 'two' ? base : { ...base, variant: withRanking(variantOf(base), choice.ranking) };
}

function seatsOfChoice(choice: StartChoice, humanBegins: boolean, level: number): GameSetup {
  switch (choice.mode) {
    case 'hotseat':
      return hotSeatVsEngine(level, choice.engineSeat, choice.jump);
    case 'engines':
      return humanVsTwoEngines(choice.humanSeat, level, choice.jump);
    default:
      return humanVsEngine(humanBegins ? 0 : 1, level, choice.jump);
  }
}
