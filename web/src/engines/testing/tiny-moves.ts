// A tiny move generator, used only by the fake engine in tests: steps and jump chains.
// It knows no rule beyond "a piece may enter the centre, its own start corner and its own goal corner".
// It is not an oracle for the real engines. Do not import it from production code.
import { CELLS, DIRECTIONS, TOP_CORNER, BOTTOM_CORNER, neighbor } from '../../board/cells';
import { makeMove } from '../engine';

const inCentre = (id: number): boolean => {
  const c = CELLS[id]!;
  const s = -c.q - c.r;
  return Math.abs(c.q) <= 4 && Math.abs(c.r) <= 4 && Math.abs(s) <= 4;
};

const own = [new Set([...TOP_CORNER, ...BOTTOM_CORNER]), new Set([...TOP_CORNER, ...BOTTOM_CORNER])];

/** May a piece of this side stand on the cell? */
export function allowed(side: 0 | 1, id: number): boolean {
  return inCentre(id) || own[side]!.has(id);
}

/** All moves of one side as from | (to << 8), without duplicates. */
export function tinyLegalMoves(owner: Int8Array, side: 0 | 1): number[] {
  const moves = new Set<number>();
  for (let from = 0; from < owner.length; from++) {
    if (owner[from] !== side) continue;
    for (let d = 0; d < DIRECTIONS; d++) {
      const step = neighbor(from, d);
      if (step >= 0 && owner[step] === -1 && allowed(side, step)) moves.add(makeMove(from, step));
    }
    // jump chains: breadth-first over landing cells, the moving piece's start cell counts as empty
    const seen = new Set<number>([from]);
    const queue = [from];
    for (let head = 0; head < queue.length; head++) {
      const at = queue[head]!;
      for (let d = 0; d < DIRECTIONS; d++) {
        const over = neighbor(at, d);
        if (over < 0 || over === from || owner[over] === -1) continue;
        const land = neighbor(over, d);
        if (land < 0 || seen.has(land) || (owner[land] !== -1 && land !== from) || !allowed(side, land)) continue;
        seen.add(land);
        queue.push(land);
        moves.add(makeMove(from, land));
      }
    }
  }
  return [...moves].sort((a, b) => a - b);
}
