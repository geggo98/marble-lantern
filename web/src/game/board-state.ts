// Pure game state: the move history is the state; this module replays it into an owner array.
import { CELL_COUNT, GOAL_CELLS, GOAL_CELLS_3, START_CELLS, START_CELLS_3 } from '../board/cells';
import { moveFrom, moveTo } from '../engines/engine';
import { DEFAULT_VARIANT, moveLimit } from './variant';
import type { Player, Variant } from './variant';

/** The side of a two-player game. */
export type Side = 0 | 1;
export type { Player };

/** Plies after which a two-player game ends without a winner (RuleSet.defaults(2)); `moveLimit(variant)` has the other counts. */
export const MOVE_LIMIT = 200;

export type Outcome =
  | { kind: 'win'; winner: Player }
  | { kind: 'move-limit' }
  /** The side to move has no legal move. */
  | { kind: 'no-moves'; side: Player }
  /** The engine reports a draw for another reason (a repeated position). */
  | { kind: 'draw' };

/** Start corners per seat: top and bottom for two players, the three clockwise corners for three (board/cells.ts). */
export const startCells = (variant: Variant): readonly (readonly number[])[] => (variant.players === 3 ? START_CELLS_3 : START_CELLS);
export const goalCells = (variant: Variant): readonly (readonly number[])[] => (variant.players === 3 ? GOAL_CELLS_3 : GOAL_CELLS);

/** owner[cell] is the seat (0, 1 or 2), or -1 for an empty cell. */
export function initialOwners(variant: Variant = DEFAULT_VARIANT): Int8Array {
  const owner = new Int8Array(CELL_COUNT).fill(-1);
  startCells(variant).forEach((cells, side) => {
    for (const c of cells) owner[c] = side;
  });
  return owner;
}

/** The side to move of a two-player game after `ply` plies. */
export const sideToMove = (ply: number): Side => (ply % 2 === 0 ? 0 : 1);

/** Applies a move without checking legality. Returns the owner array. */
export function applyMove(owner: Int8Array, move: number): Int8Array {
  const from = moveFrom(move);
  const to = moveTo(move);
  const side = owner[from];
  if (side === undefined || side < 0) throw new RangeError(`no piece on cell ${from}`);
  if (owner[to] !== -1) throw new RangeError(`cell ${to} is occupied`);
  owner[to] = side;
  owner[from] = -1;
  return owner;
}

export function replay(history: readonly number[], variant: Variant = DEFAULT_VARIANT): Int8Array {
  const owner = initialOwners(variant);
  for (const move of history) applyMove(owner, move);
  return owner;
}

/**
 * Definition 1 of docs/rules.md (blockadeRule = OPPONENT_FILLS): the goal is full and at least one piece
 * in it is the player's own.
 */
export function hasWon(owner: Int8Array, side: Player, variant: Variant = DEFAULT_VARIANT): boolean {
  const goal = goalCells(variant)[side] ?? [];
  let own = 0;
  for (const c of goal) {
    const o = owner[c];
    if (o === -1) return false;
    if (o === side) own++;
  }
  return own > 0;
}

/** The outcome after `history`, or null while the game goes on. "No moves" needs the engine and is set elsewhere. */
export function outcomeOf(owner: Int8Array, history: readonly number[], variant: Variant = DEFAULT_VARIANT): Outcome | null {
  if (history.length > 0) {
    const mover = ((history.length - 1) % variant.players) as Player;
    if (hasWon(owner, mover, variant)) return { kind: 'win', winner: mover };
  }
  if (history.length >= moveLimit(variant)) return { kind: 'move-limit' };
  return null;
}
