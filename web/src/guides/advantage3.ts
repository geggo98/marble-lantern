// The advantage indicator of a three-player game, pure: from the static evaluation of each seat (Engine.evaluate3) to the share
// of each seat and the place of a seat that has finished. Docs: docs/web-ui.md, "Move guides", "The advantage indicator for three players".
import { SCORE, scoreScale } from '../engines/analysis';

/**
 * The softmax temperature, in evaluation points. evaluate3 gives each seat "own minus the mean of both opponents" of the linear
 * evaluation, so the difference of two seats' values is 1.5 times the difference of their own scores (e_i - e_j = 1.5 (o_i - o_j)).
 * A two-player score is one such difference with the scale `scoreScale` (P(win) = 1 / (1 + exp(-score / scoreScale))). With
 * T = 1.5 * scoreScale the odds of seat i against seat j, exp((e_i - e_j) / T) = exp((o_i - o_j) / scoreScale), are the odds that
 * the two-player bar shows for the same difference. So both indicators move at the same speed per step of progress.
 */
export const SHARE_TEMPERATURE = 1.5 * scoreScale;

/** The value that evaluate3 gives a seat that has finished, by place (full ranking): first, second, last. */
const PLACE_VALUES: readonly [number, number, number] = [SCORE.mate, SCORE.mate / 2, -SCORE.mate];

export interface SeatShare {
  /** 0 to 2, the absolute seat. */
  seat: number;
  /** Whole percent, or null for a finished seat. The shares of the seats that are still playing add up to exactly 100. */
  percent: number | null;
  /** The exact share in [0, 1] for drawing, or null for a finished seat. */
  share: number | null;
  /** 1 to 3 for a finished seat, else null. */
  place: 1 | 2 | 3 | null;
}

/** The place (1, 2 or 3) that a value stands for, or null if the seat is still playing. */
export function placeOf(value: number): 1 | 2 | 3 | null {
  const i = PLACE_VALUES.indexOf(value);
  return i < 0 ? null : ((i + 1) as 1 | 2 | 3);
}

/** Whole percents that add up to 100 (largest remainder), in the order of `shares`. */
function roundTo100(shares: readonly number[]): number[] {
  const floor = shares.map((s) => Math.floor(s * 100));
  let missing = 100 - floor.reduce((a, b) => a + b, 0);
  const order = shares.map((s, i) => ({ i, rest: s * 100 - floor[i]! })).sort((x, y) => y.rest - x.rest || x.i - y.i);
  for (const { i } of order) {
    if (missing <= 0) break;
    floor[i]! += 1;
    missing -= 1;
  }
  return floor;
}

/**
 * The share of each seat: softmax(e_i / T) over the seats that are still playing, in seat order. A finished seat gets its place
 * instead. If at most one seat is still playing the game is decided and the seats show places only (the last one is third or
 * second, whichever place is free).
 */
export function seatShares(evals: readonly number[], temperature: number = SHARE_TEMPERATURE): SeatShare[] {
  if (evals.length !== 3 || evals.some((e) => !Number.isFinite(e))) throw new RangeError('evaluate3 must give three finite numbers');
  const places = evals.map(placeOf);
  const playing = evals.flatMap((_, i) => (places[i] === null ? [i] : []));
  if (playing.length <= 1) {
    const taken = new Set(places.filter((p) => p !== null));
    const free = ([1, 2, 3] as const).find((p) => !taken.has(p)) ?? 3;
    return evals.map((_, seat) => ({ seat, percent: null, share: null, place: places[seat] ?? free }));
  }
  const top = Math.max(...playing.map((i) => evals[i]!));
  const weights = new Map(playing.map((i) => [i, Math.exp((evals[i]! - top) / temperature)])); // minus top: no overflow
  const sum = [...weights.values()].reduce((a, b) => a + b, 0);
  const shares = playing.map((i) => weights.get(i)! / sum);
  const percents = roundTo100(shares);
  return evals.map((_, seat) => {
    const k = playing.indexOf(seat);
    return k < 0
      ? { seat, percent: null, share: null, place: places[seat] ?? null }
      : { seat, percent: percents[k]!, share: shares[k]!, place: null };
  });
}
