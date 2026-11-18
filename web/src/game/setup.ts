// Who plays which side. The app builds its controls on this object instead of separate flags.
//
// Extension point: today there are exactly two seats, and the UI offers only 'human' and 'engine' for each. Later the
// number of players may grow (the rules already know three-player boards), and 'network' seats (a neural network
// player) get their own choice. The model leaves room for both; the GameController does not play 'network' seats yet,
// and no UI for them exists. Do not build those UIs before the controller supports them.
import type { EngineId } from '../engines/engine';
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
  /** seats[side]: seat 0 moves first. Exactly two seats for now. */
  seats: [Seat, Seat];
}

export function humanVsEngine(humanSide: Side, level: number): GameSetup {
  const human: Seat = { kind: 'human' };
  const engine: Seat = { kind: 'engine', level };
  return { seats: humanSide === 0 ? [human, engine] : [engine, human] };
}

export function engineVsEngine(level: number): GameSetup {
  return { seats: [{ kind: 'engine', level }, { kind: 'engine', level }] };
}

/** The side of the one human seat; null when no seat or more than one seat is human. */
export function humanSideOf(setup: GameSetup): Side | null {
  const humans = setup.seats.flatMap((seat, side) => (seat.kind === 'human' ? [side as Side] : []));
  return humans.length === 1 ? humans[0]! : null;
}

/** No human seat: the engines play each other. */
export function isDemo(setup: GameSetup): boolean {
  return setup.seats.every((seat) => seat.kind !== 'human');
}

/** The level of the first engine seat; the fallback when there is none. */
export function levelOf(setup: GameSetup, fallback: number): number {
  return setup.seats.find((seat) => seat.kind === 'engine')?.level ?? fallback;
}

/** The same seats with a new level on every engine seat. */
export function withLevel(setup: GameSetup, level: number): GameSetup {
  const seat = (s: Seat): Seat => (s.kind === 'engine' ? { ...s, level } : s);
  return { seats: [seat(setup.seats[0]), seat(setup.seats[1])] };
}

/** True when the controller can play this setup today: one human and one engine, or two engines. */
export function isSupported(setup: GameSetup): boolean {
  return setup.seats.every((s) => s.kind !== 'network') && (humanSideOf(setup) !== null || isDemo(setup));
}
