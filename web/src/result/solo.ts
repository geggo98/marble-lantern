// A solo race for the losing side of a finished two-player game: the winner has filled its goal and makes no more moves, so the
// loser races alone. The engines cannot do this themselves: a finished two-player game is over for them (analyse rejects with
// illegal-history, and full ranking plays the same game as first-winner rules for two players, docs/rules.md). The moves here are
// searched by a small beam search over the sum of the distances to the goal tip; the result is an estimate, an upper bound on the
// true shortest race. See docs/web-ui.md, "Distance of the result".
import { CELLS, DIRECTIONS, neighbor } from '../board/cells';
import { goalCells, hasWon, startCells } from '../game/board-state';
import type { Player, Variant } from '../game/variant';
import { makeMove } from '../engines/engine';
import { hexDistance } from '../ui/board-model';

const inCentre = (id: number): boolean => {
  const c = CELLS[id]!;
  return Math.abs(c.q) <= 4 && Math.abs(c.r) <= 4 && Math.abs(-c.q - c.r) <= 4;
};

/**
 * The legal moves (from | to << 8) of `side` on `owner`: steps and jump chains. A piece stands in the centre, its start corner or
 * its goal corner only; a piece in its goal never ends a move outside it (leaveGoal = FORBIDDEN, docs/rules.md).
 */
export function soloMoves(owner: Int8Array, side: Player, variant: Variant): number[] {
  const goal = new Set(goalCells(variant)[side]);
  const stand = new Set([...startCells(variant)[side]!, ...goal]);
  const allowed = (id: number): boolean => inCentre(id) || stand.has(id);
  const moves = new Set<number>();
  for (let from = 0; from < owner.length; from++) {
    if (owner[from] !== side) continue;
    const ok = (to: number): boolean => allowed(to) && !(goal.has(from) && !goal.has(to));
    const empty = (id: number): boolean => owner[id] === -1 || id === from;
    for (let d = 0; d < DIRECTIONS; d++) {
      const step = neighbor(from, d);
      if (step >= 0 && owner[step] === -1 && ok(step)) moves.add(makeMove(from, step));
    }
    const seen = new Set<number>([from]);
    const queue = [from];
    for (let head = 0; head < queue.length; head++) {
      const at = queue[head]!;
      for (let d = 0; d < DIRECTIONS; d++) {
        const land = variant.jump === 'long' ? longLanding(at, d, empty, from) : adjacentLanding(at, d, empty, from);
        if (land < 0 || seen.has(land) || !allowed(land)) continue;
        seen.add(land);
        queue.push(land);
        if (ok(land)) moves.add(makeMove(from, land));
      }
    }
  }
  return [...moves];
}

function adjacentLanding(at: number, d: number, empty: (id: number) => boolean, from: number): number {
  const over = neighbor(at, d);
  if (over < 0 || over === from || empty(over)) return -1;
  const land = neighbor(over, d);
  return land >= 0 && empty(land) ? land : -1;
}

/** The long hop of docs/rules.md, "Jump rule": over the first piece P at distance k, to the mirror cell k steps behind P. */
function longLanding(at: number, d: number, empty: (id: number) => boolean, from: number): number {
  let k = 1;
  let p = neighbor(at, d);
  while (p >= 0 && empty(p)) {
    p = neighbor(p, d);
    k++;
  }
  if (p < 0 || p === from) return -1;
  let l = p;
  for (let i = 0; i < k; i++) {
    l = neighbor(l, d);
    if (l < 0 || !empty(l)) return -1;
  }
  return l;
}

export interface SoloOptions {
  /** The most moves of the losing side that are searched. */
  cap: number;
  /** Beam width. */
  width?: number;
  signal?: AbortSignal | undefined;
}

/** `moves` of the losing side until its goal is full (hasWon); `capped` when the cap was reached first (then `moves` is the cap). */
export interface SoloResult {
  moves: number;
  capped: boolean;
}

const abortError = (): Error => new DOMException('The estimate was aborted', 'AbortError');
const yieldToUi = (): Promise<void> => new Promise((resolve) => setTimeout(resolve, 0));

/**
 * Plays `side` alone from `owner` (not changed) until `hasWon(owner, side)`: at each ply the beam keeps the `width` best positions by
 * the sum of the distances of the pieces to the tip of the goal. Returns after each ply to the event loop, so the page stays
 * responsive; an abort rejects with an AbortError, and a side without a legal move rejects with an Error.
 */
export async function soloRace(owner: Int8Array, side: Player, variant: Variant, options: SoloOptions): Promise<SoloResult> {
  const { cap, width = 60, signal } = options;
  const goal = goalCells(variant)[side]!;
  const tip = goal.reduce((best, c) => (Math.abs(CELLS[c]!.r) > Math.abs(CELLS[best]!.r) ? c : best), goal[0]!);
  // a piece outside the goal counts its distance to the tip; a piece inside counts a tenth of it, so that real progress ranks above shuffling in the goal
  const inGoal = new Set(goal);
  const potential = (o: Int8Array): number => {
    let sum = 0;
    for (let c = 0; c < o.length; c++) if (o[c] === side) sum += hexDistance(c, tip) * (inGoal.has(c) ? 0.1 : 1);
    return sum;
  };
  const keyOf = (o: Int8Array): string => {
    let key = '';
    for (let c = 0; c < o.length; c++) if (o[c] === side) key += String.fromCharCode(c);
    return key;
  };
  if (hasWon(owner, side, variant)) return { moves: 0, capped: false };
  let beam: Int8Array[] = [owner.slice()];
  for (let ply = 1; ply <= cap; ply++) {
    if (signal?.aborted) throw abortError();
    const next = new Map<string, { o: Int8Array; p: number }>();
    for (const o of beam) {
      for (const move of soloMoves(o, side, variant)) {
        const child = o.slice();
        child[move & 0xff] = -1;
        child[move >>> 8] = side;
        if (hasWon(child, side, variant)) return { moves: ply, capped: false };
        const key = keyOf(child);
        if (!next.has(key)) next.set(key, { o: child, p: potential(child) });
      }
    }
    if (next.size === 0) throw new Error('the side has no legal move');
    beam = [...next.values()].sort((a, b) => a.p - b.p).slice(0, width).map((e) => e.o);
    await yieldToUi();
  }
  return { moves: cap, capped: true };
}
