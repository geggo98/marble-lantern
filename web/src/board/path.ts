// Jump paths for the move animation. Display only: legality always comes from Engine.legalMoves.
import { CELL_COUNT, DIRECTIONS, neighbor } from './cells';

/**
 * The chain of cells a piece visits from `from` to `to`: [from, to] for a step, [from, a, b, to] for jumps.
 * Breadth-first over single jumps, so the chain with the fewest jumps wins. The moving piece's own start cell
 * counts as empty. Returns null if no chain exists.
 *
 * @param occupied  occupied[cell] is true if a piece stands on the cell before the move
 */
export function jumpPath(from: number, to: number, occupied: ArrayLike<boolean | number>): number[] | null {
  if (from === to) return null;
  for (let d = 0; d < DIRECTIONS; d++) {
    if (neighbor(from, d) === to) return [from, to];
  }
  const filled = (cell: number): boolean => cell !== from && Boolean(occupied[cell]);
  const previous = new Int16Array(CELL_COUNT).fill(-2);
  previous[from] = -1;
  const queue = [from];
  for (let head = 0; head < queue.length; head++) {
    const at = queue[head]!;
    for (let d = 0; d < DIRECTIONS; d++) {
      const over = neighbor(at, d);
      if (over < 0 || !filled(over)) continue;
      const land = neighbor(over, d);
      if (land < 0 || filled(land) || previous[land] !== -2) continue;
      previous[land] = at;
      if (land === to) {
        const path = [to];
        for (let c = at; c !== -1; c = previous[c]!) path.push(c);
        return path.reverse();
      }
      queue.push(land);
    }
  }
  return null;
}
