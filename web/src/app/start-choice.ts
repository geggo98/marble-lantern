// What the start dialog asks: the jump rule, the game mode and the seats; and how that becomes a GameSetup. The last choice is
// kept in localStorage as a per-viewer convenience (every read and write is guarded: storage can be missing or blocked).
import { hotSeatVsEngine, humanVsEngine, humanVsTwoEngines } from '../game/setup';
import type { GameSetup } from '../game/setup';
import type { JumpRule, Player } from '../game/variant';

export type GameMode = 'two' | 'hotseat' | 'engines';

export interface StartChoice {
  jump: JumpRule;
  mode: GameMode;
  /** Mode 'engines': the seat of the person (0 moves first). */
  humanSeat: Player;
  /** Mode 'hotseat': the seat of the engine. */
  engineSeat: Player;
}

export const DEFAULT_CHOICE: Readonly<StartChoice> = Object.freeze({ jump: 'adjacent', mode: 'two', humanSeat: 0, engineSeat: 2 });
export const CHOICE_KEY = 'ml.start-choice';

const isSeat = (v: unknown): v is Player => v === 0 || v === 1 || v === 2;

/** The choice a stored text holds; anything that is not a valid choice gives the default. Unknown fields are dropped. */
export function parseChoice(text: string | null): StartChoice {
  if (text === null) return { ...DEFAULT_CHOICE };
  try {
    const raw = JSON.parse(text) as Record<string, unknown> | null;
    if (!raw || typeof raw !== 'object') return { ...DEFAULT_CHOICE };
    return {
      jump: raw.jump === 'long' ? 'long' : 'adjacent',
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
    storage?.setItem(CHOICE_KEY, JSON.stringify(choice));
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
  switch (choice.mode) {
    case 'hotseat':
      return hotSeatVsEngine(level, choice.engineSeat, choice.jump);
    case 'engines':
      return humanVsTwoEngines(choice.humanSeat, level, choice.jump);
    default:
      return humanVsEngine(humanBegins ? 0 : 1, level, choice.jump);
  }
}
