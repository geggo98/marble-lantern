// Pure game state: the move history is the state; this module replays it into an owner array.
import { CELL_COUNT, GOAL_CELLS, START_CELLS } from '../board/cells';
import { moveFrom, moveTo } from '../engines/engine';

export type Side = 0 | 1;

/** Plies after which a game ends without a winner (RuleSet.defaults(2)). */
export const MOVE_LIMIT = 200;

export type Outcome =
  | { kind: 'win'; winner: Side }
  | { kind: 'move-limit' }
  /** The side to move has no legal move. */
  | { kind: 'no-moves'; side: Side };

/** owner[cell] is 0 or 1, or -1 for an empty cell. */
export function initialOwners(): Int8Array {
  const owner = new Int8Array(CELL_COUNT).fill(-1);
  for (const c of START_CELLS[0]) owner[c] = 0;
  for (const c of START_CELLS[1]) owner[c] = 1;
  return owner;
}

export const sideToMove = (ply: number): Side => (ply % 2 === 0 ? 0 : 1);

/** Applies a move without checking legality. Returns the side that moved. */
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

export function replay(history: readonly number[]): Int8Array {
  const owner = initialOwners();
  for (const move of history) applyMove(owner, move);
  return owner;
}

/**
 * Definition 1 of docs/rules.md (blockadeRule = OPPONENT_FILLS): the goal is full and at least one piece
 * in it is the player's own.
 */
export function hasWon(owner: Int8Array, side: Side): boolean {
  const goal = GOAL_CELLS[side];
  let own = 0;
  for (const c of goal) {
    const o = owner[c];
    if (o === -1) return false;
    if (o === side) own++;
  }
  return own > 0;
}

/** The outcome after `history`, or null while the game goes on. "No moves" needs the engine and is set elsewhere. */
export function outcomeOf(owner: Int8Array, history: readonly number[]): Outcome | null {
  if (history.length > 0) {
    const mover = sideToMove(history.length - 1);
    if (hasWon(owner, mover)) return { kind: 'win', winner: mover };
  }
  if (history.length >= MOVE_LIMIT) return { kind: 'move-limit' };
  return null;
}
