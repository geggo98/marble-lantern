// How the seats of a game look and are called: the marble of each seat, its name, and the cues the board and the status row
// draw from them. Pure functions, no DOM. See docs/web-ui.md, section "Board and play screen for three seats".
//
// Two players keep the classic look: the person is amber (A), the engine blue with a ring (B); in a demo seat 1 is amber.
// Three players use three marbles that differ in more than colour: amber plain (A), blue with a ring (B), pearl with a diamond
// (C). With one person, the person is amber and the engines take B and C in seat order. In hot-seat the marble follows the seat
// (seat 0 A, seat 1 B, seat 2 C), so that each of the two people keeps one marble through the game.
import type { ControllerSeat } from '../game/game';
import type { SideStyle } from './primitives';

export interface SeatView {
  /** 0 moves first. */
  seat: number;
  style: SideStyle;
  kind: ControllerSeat;
  /** The only person of the game sits here (so the texts say "you"). */
  you: boolean;
  /** 1 to 3, as the texts name the seat. */
  number: number;
}

const ORDER: readonly SideStyle[] = ['a', 'b', 'c'];

/** The view of every seat. `demo`: no person plays; the two-player demo keeps seat 1 amber as before. */
export function seatViews(seats: readonly ControllerSeat[], demo: boolean): SeatView[] {
  const humans = seats.flatMap((s, i) => (s === 'human' ? [i] : []));
  const single = !demo && humans.length === 1;
  const styles: SideStyle[] = [];
  if (seats.length === 2) {
    const aSeat = demo ? 1 : (humans[0] ?? 1);
    seats.forEach((_, i) => styles.push(i === aSeat ? 'a' : 'b'));
  } else if (single) {
    const rest = ORDER.filter((s) => s !== 'a');
    let next = 0;
    seats.forEach((_, i) => styles.push(i === humans[0] ? 'a' : rest[next++]!));
  } else {
    seats.forEach((_, i) => styles.push(ORDER[i]!));
  }
  return seats.map((kind, i) => ({ seat: i, style: styles[i]!, kind, you: single && humans[0] === i, number: i + 1 }));
}

/** The name key of a seat: "Player 2 (you)" for the one person, "Player 3 (engine)", plain "Player 1" for a person in hot-seat. */
export type SeatNameKey = 'seat_player' | 'seat_you' | 'seat_engine';
export function seatNameKey(view: SeatView): SeatNameKey {
  if (view.you) return 'seat_you';
  return view.kind === 'engine' ? 'seat_engine' : 'seat_player';
}
