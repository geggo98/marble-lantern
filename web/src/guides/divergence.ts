// Where the network and the classical search disagree, for the move guides. Pure functions, no React.
// Definitions: the plan "Festlegung", checked against the simulation of 2026-11-27 (analyze3.py).
import { scoreBand } from '../engines/analysis';
import { rankBand } from './bands';
import type { Band } from './bands';

/**
 * A move counts as a trap when the network values it at least this far below its favourite (values lie in -1..1, from the
 * point of view of the mover).
 *
 * Origin: simulation of 2026-11-27, 20 games, 2763 positions; the rates of traps and special moves per threshold are in the table of
 * the plan. Limits: small sample, one type of opponent, two players only. Step 6 of the plan calibrates this value against
 * real measurements; until then it is a guide, not a result.
 */
export const TRAP_DELTA = 0.25;

/**
 * Fewest visits at which the root value of a move is used. Below this the value is too noisy, and the advisor searches the
 * move once more (a probe) and compares probe with probe.
 *
 * Origin and limits: as for TRAP_DELTA; step 6 of the plan calibrates it.
 */
export const MIN_VISITS = 16;

export interface Divergence {
  move: number;
  /** Value of `move` for the mover, from the root or from a probe. */
  netValue: number;
  /** Value of the net favourite on the same basis. For a special move, `move` is the favourite and both values are equal. */
  favouriteValue: number;
  /** Classical band of `move` against the best classical score. */
  classicalBand: Band;
}

export interface ClassicalResult { moves: number[]; scores: number[] }
export interface NetResult { moves: number[]; visits: number[]; values: number[] }

/** Bands in which a favourite counts as a special move (Spezialzug): fair, weak, poor. */
const SPECIAL_BANDS: readonly Band[] = ['equal', 'worse', 'much-worse'];

interface Context {
  best: number;
  fav: number;
  bestScore: number;
  visits: Map<number, number>;
  value: Map<number, number>;
}

/** Null when no mark is possible: fewer than two moves, or a race or mate position. Throws RangeError on unequal lengths. */
function context(classical: ClassicalResult, net: NetResult): Context | null {
  if (classical.moves.length !== classical.scores.length) throw new RangeError('classical result: moves and scores differ in length');
  if (net.moves.length !== net.visits.length || net.moves.length !== net.values.length) {
    throw new RangeError('net result: moves, visits and values differ in length');
  }
  if (classical.moves.length < 2 || net.moves.length < 1) return null;
  let bi = 0;
  for (let i = 1; i < classical.moves.length; i++) if (classical.scores[i]! > classical.scores[bi]!) bi = i;
  const bestScore = classical.scores[bi]!;
  if (scoreBand(bestScore) !== 'ordinary') return null;

  const visits = new Map<number, number>();
  const value = new Map<number, number>();
  net.moves.forEach((m, i) => { visits.set(m, net.visits[i]!); value.set(m, net.values[i]!); });
  // Favourite: most visits, ties to the lowest move code.
  let fav = net.moves[0]!;
  for (const m of net.moves) {
    const v = visits.get(m)!;
    const fv = visits.get(fav)!;
    if (v > fv || (v === fv && m < fav)) fav = m;
  }
  // Best classical move: highest score, ties to the one with most net visits (then the lowest move code).
  let best = classical.moves[bi]!;
  for (let i = 0; i < classical.moves.length; i++) {
    if (classical.scores[i]! !== bestScore) continue;
    const m = classical.moves[i]!;
    const vm = visits.get(m) ?? 0;
    const vb = visits.get(best) ?? 0;
    if (vm > vb || (vm === vb && m < best)) best = m;
  }
  return { best, fav, bestScore, visits, value };
}

/**
 * Moves that need a probe so that a trap can be decided: when the classically best move is not the favourite and has fewer
 * than MIN_VISITS visits, both it and the favourite (probe against probe, like with like). Empty when no mark is possible or
 * the best move has enough visits.
 */
export function needsProbe(classical: ClassicalResult, net: NetResult): number[] {
  const c = context(classical, net);
  if (!c || c.best === c.fav) return [];
  return (c.visits.get(c.best) ?? 0) >= MIN_VISITS ? [] : [c.best, c.fav];
}

/**
 * The special move (Spezialzug): the net favourite when the classical search rates it fair, weak or poor. The trap (Falle): the classically best move when
 * the favourite is another move and the net values the best move at least TRAP_DELTA below the favourite. At most one of each.
 * A trap needs values on one basis: root values when the best move has MIN_VISITS visits, else probes of both moves; with a
 * probe missing there is no trap. `probes` maps a move to its value for the mover.
 */
export function findDivergences(
  classical: ClassicalResult,
  net: NetResult,
  probes: ReadonlyMap<number, number>,
): { special?: Divergence; trap?: Divergence } {
  const c = context(classical, net);
  if (!c) return {};
  const out: { special?: Divergence; trap?: Divergence } = {};
  const favRoot = c.value.get(c.fav)!;

  const favIdx = classical.moves.indexOf(c.fav);
  if (favIdx >= 0) {
    const favBand = rankBand(classical.scores[favIdx]!, c.bestScore);
    if (SPECIAL_BANDS.includes(favBand)) out.special = { move: c.fav, netValue: favRoot, favouriteValue: favRoot, classicalBand: favBand };
  }

  if (c.best !== c.fav) {
    const root = (c.visits.get(c.best) ?? 0) >= MIN_VISITS;
    const qBest = root ? c.value.get(c.best) : probes.get(c.best);
    const qFav = root ? favRoot : probes.get(c.fav);
    if (qBest !== undefined && qFav !== undefined && qBest <= qFav - TRAP_DELTA) {
      out.trap = { move: c.best, netValue: qBest, favouriteValue: qFav, classicalBand: 'much-better' };
    }
  }
  return out;
}
