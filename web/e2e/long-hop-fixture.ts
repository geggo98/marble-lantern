// A start position for the long-hop test: an opening of plain moves (steps and adjacent jumps into the centre, legal under both jump
// rules) after which the side to move has a long hop that the neighbour-jump rule forbids. The search is deterministic (a fixed seed
// and a fixed move generator), so the fixture is the same on every run. No engine is involved: the page's own engine checks the link.
import { CELLS, BOTTOM_CORNER, TOP_CORNER } from '../src/board/cells';
import { applyMove, initialOwners } from '../src/game/board-state';
import { tinyLegalMoves } from '../src/engines/testing/tiny-moves';
import { moveFrom, moveTo } from '../src/engines/engine';
import type { Variant } from '../src/game/variant';

const ADJACENT: Variant = { jump: 'adjacent', players: 2 };
const LONG: Variant = { jump: 'long', players: 2 };
const corners = new Set([...TOP_CORNER, ...BOTTOM_CORNER]);

export interface LongHopFixture {
  /** The plies before the long hop. */
  moves: number[];
  /** The side that makes the long hop (its seat in the game). */
  side: 0 | 1;
  from: number;
  to: number;
}

function random(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** Moves that leave the corners for the centre and never enter one: legal for the real engine under both rules. */
const plain = (moves: number[]): number[] => moves.filter((m) => !corners.has(moveTo(m)));

export function findLongHop(maxPlies = 14): LongHopFixture {
  for (let seed = 1; seed < 500; seed++) {
    const next = random(seed);
    const owner = initialOwners();
    const moves: number[] = [];
    for (let ply = 0; ply < maxPlies; ply++) {
      const side = (ply % 2) as 0 | 1;
      const adjacent = tinyLegalMoves(owner, side, ADJACENT);
      const hops = plain(tinyLegalMoves(owner, side, LONG).filter((m) => !adjacent.includes(m)));
      if (hops.length > 0 && ply >= 2) {
        const hop = hops[0]!;
        return { moves, side, from: moveFrom(hop), to: moveTo(hop) };
      }
      const choices = plain(adjacent);
      if (choices.length === 0) break;
      const move = choices[Math.floor(next() * choices.length)]!;
      applyMove(owner, move);
      moves.push(move);
    }
  }
  throw new Error('no start position with a long hop found');
}

export const cellCount = CELLS.length;
