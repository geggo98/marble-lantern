// Who plays which seat, in which game variant. The app builds its controls on this object instead of separate flags.
//
// A game has two or three seats (variant.players); seat 0 moves first. A seat is a human or an engine. The three game modes
// have constructors:
//   humanVsEngine(side, level)            two players, one human, one engine (the classic game)
//   hotSeatVsEngine(level, engineSeat)    three players, two humans on the same device, one engine
//   humanVsTwoEngines(humanSeat, level)   three players, one human, two engines
// One level applies to all engine seats (the engine card has one strength setting).
//
// Extension point: a 'network' seat (a neural network player) has a type but no controller support and no UI yet. Do not build
// that UI before the controller plays such a seat.
import type { EngineId } from '../engines/engine';
import type { ControllerSeat } from './game';
import { DEFAULT_VARIANT, withCamps, withRows } from './variant';
import type { BoardRows, ForeignCamps, JumpRule, Player, Variant } from './variant';
import type { Side } from './board-state';

export type SeatKind = 'human' | 'engine' | 'network';

export interface Seat {
  kind: SeatKind;
  /** Which engine plays this seat; unset means the engine of the session. */
  engine?: EngineId;
  /** Strength level (index into NODE_LEVELS); unset means the default level. */
  level?: number;
}

export interface GameSetup {
  /** seats[side]: seat 0 moves first. Two seats for two players, three for three. */
  seats: readonly Seat[];
  /** The game variant; unset means the default (adjacent jumps, two players). Its player count equals `seats.length`. */
  variant?: Variant;
}

const variantFor = (players: 2 | 3, jump: JumpRule): Variant => (players === 2 && jump === 'adjacent' ? { ...DEFAULT_VARIANT } : { jump, players });

/** The variant of a setup: its own, or the default. */
export const variantOf = (setup: GameSetup): Variant => setup.variant ?? DEFAULT_VARIANT;

export function humanVsEngine(humanSide: Side, level: number, jump: JumpRule = 'adjacent'): GameSetup {
  const human: Seat = { kind: 'human' };
  const engine: Seat = { kind: 'engine', level };
  return { seats: humanSide === 0 ? [human, engine] : [engine, human], variant: variantFor(2, jump) };
}

export function engineVsEngine(level: number, jump: JumpRule = 'adjacent'): GameSetup {
  return { seats: [{ kind: 'engine', level }, { kind: 'engine', level }], variant: variantFor(2, jump) };
}

/** Three players on one device: two humans take turns at the same board, the engine plays `engineSeat` (default: the last seat). */
export function hotSeatVsEngine(level: number, engineSeat: Player = 2, jump: JumpRule = 'adjacent'): GameSetup {
  const seats = [0, 1, 2].map((seat): Seat => (seat === engineSeat ? { kind: 'engine', level } : { kind: 'human' }));
  return { seats, variant: variantFor(3, jump) };
}

/** Three players: one human on `humanSeat` (default: the first seat), the other two seats are engines. */
export function humanVsTwoEngines(humanSeat: Player, level: number, jump: JumpRule = 'adjacent'): GameSetup {
  const seats = [0, 1, 2].map((seat): Seat => (seat === humanSeat ? { kind: 'human' } : { kind: 'engine', level }));
  return { seats, variant: variantFor(3, jump) };
}

/** The same seats with another foreign-corner rule (docs/rules.md, "Foreign corners"); `closed` leaves no trace in the variant. */
export function withForeignCamps(setup: GameSetup, camps: ForeignCamps): GameSetup {
  return { ...setup, variant: withCamps(variantOf(setup), camps) };
}

/** The same seats on another house size (4 or 5 rows); 4 leaves no trace in the variant. */
export function withBoardRows(setup: GameSetup, rows: BoardRows): GameSetup {
  return { ...setup, variant: withRows(variantOf(setup), rows) };
}

/** The seats that a person plays. */
export function humanSeatsOf(setup: GameSetup): Player[] {
  return setup.seats.flatMap((seat, i) => (seat.kind === 'human' ? [i as Player] : []));
}

/** The seats that an engine plays. */
export function engineSeatsOf(setup: GameSetup): Player[] {
  return setup.seats.flatMap((seat, i) => (seat.kind === 'engine' ? [i as Player] : []));
}

/** The seat of the one human; null when no seat or more than one seat is human. */
export function humanSeatOf(setup: GameSetup): Player | null {
  const humans = humanSeatsOf(setup);
  return humans.length === 1 ? humans[0]! : null;
}

/** The side of the one human of a two-player game; null for no human, more than one, and for three players. */
export function humanSideOf(setup: GameSetup): Side | null {
  return setup.seats.length === 2 ? (humanSeatOf(setup) as Side | null) : null;
}

/** No human seat: the engines play each other. */
export function isDemo(setup: GameSetup): boolean {
  return setup.seats.every((seat) => seat.kind !== 'human');
}

/** Three players with two humans on one device and one engine. */
export function isHotSeat(setup: GameSetup): boolean {
  return setup.seats.length === 3 && humanSeatsOf(setup).length === 2 && engineSeatsOf(setup).length === 1;
}

/** The level of the first engine seat; the fallback when there is none. */
export function levelOf(setup: GameSetup, fallback: number): number {
  return setup.seats.find((seat) => seat.kind === 'engine')?.level ?? fallback;
}

/** The same seats with a new level on every engine seat. */
export function withLevel(setup: GameSetup, level: number): GameSetup {
  return { ...setup, seats: setup.seats.map((s): Seat => (s.kind === 'engine' ? { ...s, level } : s)) };
}

/** The same game with the person on `seat` and engines on every other seat (the level of the setup). */
export function withHumanSeat(setup: GameSetup, seat: Player, fallbackLevel = 0): GameSetup {
  const level = levelOf(setup, fallbackLevel);
  return { ...setup, seats: setup.seats.map((_, i): Seat => (i === seat ? { kind: 'human' } : { kind: 'engine', level })) };
}

/** The same game with an engine on every seat: a demo. */
export function withAllEngines(setup: GameSetup, fallbackLevel = 0): GameSetup {
  const level = levelOf(setup, fallbackLevel);
  return { ...setup, seats: setup.seats.map((): Seat => ({ kind: 'engine', level })) };
}

/** The seats in the form the controller takes (`human` or `engine`); throws for a seat it cannot play. */
export function controllerSeats(setup: GameSetup): ControllerSeat[] {
  return setup.seats.map((s) => {
    if (s.kind === 'network') throw new Error('the game controller does not play network seats yet');
    return s.kind;
  });
}

/**
 * True when the controller can play this setup: the seat count fits the player count and no seat is a network seat. Two players:
 * one human and one engine, or two engines. Three players: at most two humans (hot-seat has two, one engine).
 */
export function isSupported(setup: GameSetup): boolean {
  const variant = variantOf(setup);
  if (setup.seats.length !== variant.players || setup.seats.some((s) => s.kind === 'network')) return false;
  if (variant.players === 2) return humanSideOf(setup) !== null || isDemo(setup);
  return humanSeatsOf(setup).length <= 2;
}
