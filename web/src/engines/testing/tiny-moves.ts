// A tiny move generator, used by the fake engine in tests and as the TypeScript side of the foreign-corner cross test
// (camps.cross.test.ts): steps and jump chains, for two or three players, both jump rules and the three foreign-corner rules
// (variant.camps). It knows no rule beyond the cells a piece may enter: the centre, its own start corner and its own goal
// corner, and the foreign corners as the variant allows. It has no leave-goal and no Definition-2 filter. Do not import it from
// production code.
import { CELLS, DIRECTIONS, TOP_CORNER, BOTTOM_CORNER, neighbor } from '../../board/cells';
import { goalCells, startCells } from '../../game/board-state';
import { DEFAULT_VARIANT, campsOf } from '../../game/variant';
import type { Variant } from '../../game/variant';
import { makeMove } from '../engine';

const inCentre = (id: number): boolean => {
  const c = CELLS[id]!;
  const s = -c.q - c.r;
  return Math.abs(c.q) <= 4 && Math.abs(c.r) <= 4 && Math.abs(s) <= 4;
};

const twoPlayerCorners = new Set([...TOP_CORNER, ...BOTTOM_CORNER]);

/** May a piece of this side stand on the cell? Two players: any of the two corners (the old fake rule); three: own start and goal corner. */
export function allowed(side: number, id: number, variant: Variant = DEFAULT_VARIANT): boolean {
  if (inCentre(id)) return true;
  if (variant.players === 2) return twoPlayerCorners.has(id);
  return startCells(variant)[side]!.includes(id) || goalCells(variant)[side]!.includes(id);
}

/**
 * All moves of one side as from | (to << 8), without duplicates. Foreign corners (docs/rules.md, "Foreign corners"): `closed` never
 * lands there, `pass-through` lands there inside a chain but does not end there, `open` ends there too.
 */
export function tinyLegalMoves(owner: Int8Array, side: number, variant: Variant = DEFAULT_VARIANT): number[] {
  const camps = campsOf(variant);
  const open = camps === 'open';
  const moves = new Set<number>();
  for (let from = 0; from < owner.length; from++) {
    if (owner[from] !== side) continue;
    for (let d = 0; d < DIRECTIONS; d++) {
      const step = neighbor(from, d);
      if (step >= 0 && owner[step] === -1 && (open || allowed(side, step, variant))) moves.add(makeMove(from, step));
    }
    // jump chains: breadth-first over landing cells, the moving piece's start cell counts as empty
    const empty = (id: number): boolean => owner[id] === -1 || id === from;
    const seen = new Set<number>([from]);
    const queue = [from];
    for (let head = 0; head < queue.length; head++) {
      const at = queue[head]!;
      for (let d = 0; d < DIRECTIONS; d++) {
        const land = variant.jump === 'long' ? longLanding(at, d, empty, from) : adjacentLanding(at, d, empty, from);
        if (land < 0 || seen.has(land)) continue;
        const home = allowed(side, land, variant);
        if (!home && camps === 'closed') continue;
        seen.add(land);
        queue.push(land);
        if (home || open) moves.add(makeMove(from, land));
      }
    }
  }
  return [...moves].sort((a, b) => a - b);
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
  if (p < 0 || p === from) return -1; // no piece on this line
  let l = p;
  for (let i = 0; i < k; i++) {
    l = neighbor(l, d);
    if (l < 0 || !empty(l)) return -1;
  }
  return l;
}
