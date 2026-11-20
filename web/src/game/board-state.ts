// Pure game state: the move history is the state; this module replays it into an owner array.
import { CELL_COUNT, goalHouses, startHouses } from '../board/cells';
import { moveFrom, moveTo } from '../engines/engine';
import { DEFAULT_VARIANT, moveLimit, playsOn, rowsOf } from './variant';
import type { Player, Variant } from './variant';

/** The side of a two-player game. */
export type Side = 0 | 1;
export type { Player };

/** Plies after which a two-player game on 4 rows ends without a winner (RuleSet.defaults(2)); `moveLimit(variant)` has the other counts. */
export const MOVE_LIMIT = 200;

export type Outcome =
  | { kind: 'win'; winner: Player }
  | { kind: 'move-limit' }
  /** The side to move has no legal move. */
  | { kind: 'no-moves'; side: Player }
  /** The engine reports a draw for another reason (a repeated position). */
  | { kind: 'draw' }
  /**
   * A full-ranking game of three players is over (variant.ranking 'full'). `ranking` lists the seats by rank, first place first.
   * `reason`: `complete` when two seats filled their goal (the last seat takes the last rank), `move-limit` when the limit ended
   * the game (the seats still playing are ranked by their distance to the goal), `stalled` for the other endings the engine
   * knows (a repeated position, a seat without a legal move).
   */
  | { kind: 'ranking'; ranking: readonly [Player, Player, Player]; reason: 'complete' | 'move-limit' | 'stalled' };

/** Start houses per seat: top and bottom for two players, the three clockwise corners for three; 10 cells with 4 rows, 15 with 5 (board/cells.ts). */
export const startCells = (variant: Variant): readonly (readonly number[])[] => startHouses(variant.players, rowsOf(variant));
export const goalCells = (variant: Variant): readonly (readonly number[])[] => goalHouses(variant.players, rowsOf(variant));

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

/** The turn state of a game: who moves next, who is out, and who made the last move. */
export interface PlayState {
  /** The seat to move after the history. */
  sideToMove: Player;
  /** The seats that filled their goal under full ranking, by rank (first place first); empty under first-winner rules. */
  finishers: readonly Player[];
  /** The seat that made the last move; null for an empty history. */
  lastMover: Player | null;
  /** The history length at which the first seat finished (the winning move included); null while nobody has. */
  firstFinishAt: number | null;
  /** For every finisher, in rank order, the history length at which it finished (the move that filled the goal included). */
  finishedAt: readonly number[];
}

/**
 * Replays the turn order of a game. First-winner rules and two players: `ply % players`, nobody is out. Full ranking with three
 * players (playsOn): after every move the seats whose goal condition holds finish, in turn order starting after the mover and
 * ending with the mover, at most two seats in all; the next seat to move is the first one after the mover that is not out. This is
 * Position.recordFinishers and Position.nextActive of the engine; web/src/engines/ranking.cross.test.ts compares both on whole games.
 * Do not use `sideAt` (variant.ts) for a full-ranking game: it ignores the seats that are out.
 */
export function playState(history: readonly number[], variant: Variant = DEFAULT_VARIANT): PlayState {
  const players = variant.players;
  if (!playsOn(variant)) {
    return {
      sideToMove: (history.length % players) as Player,
      finishers: [],
      lastMover: history.length === 0 ? null : (((history.length - 1) % players) as Player),
      firstFinishAt: null,
      finishedAt: [],
    };
  }
  const owner = initialOwners(variant);
  const finishers: Player[] = [];
  let side = 0;
  let lastMover: Player | null = null;
  let firstFinishAt: number | null = null;
  const finishedAt: number[] = [];
  const nextActive = (from: number): Player => {
    for (let i = 1; i <= players; i++) {
      const q = ((from + i) % players) as Player;
      if (!finishers.includes(q)) return q;
    }
    return from as Player;
  };
  history.forEach((move, index) => {
    const mover = side as Player;
    applyMove(owner, move);
    for (const q of newFinishers(owner, mover, finishers, variant)) {
      finishers.push(q);
      firstFinishAt ??= index + 1;
      finishedAt.push(index + 1);
    }
    lastMover = mover;
    side = nextActive(mover);
  });
  return { sideToMove: side as Player, finishers, lastMover, firstFinishAt, finishedAt };
}

/**
 * The seats that finish with a move of `mover`, in rank order: the seats whose goal condition holds, in turn order starting after the
 * mover and ending with the mover, as long as fewer than players - 1 seats are out (Position.recordFinishers of the engine).
 */
export function newFinishers(owner: Int8Array, mover: Player, out: readonly Player[], variant: Variant): Player[] {
  const players = variant.players;
  const added: Player[] = [];
  for (let i = 1; i <= players; i++) {
    const q = ((mover + i) % players) as Player;
    if (out.length + added.length < players - 1 && !out.includes(q) && hasWon(owner, q, variant)) added.push(q);
  }
  return added;
}

/** The seats by rank once two seats are out: the finishers, then the one seat left. Null while two or more seats play. */
export function completeRanking(finishers: readonly Player[]): [Player, Player, Player] | null {
  if (finishers.length < 2) return null;
  const last = ([0, 1, 2] as const).find((p) => !finishers.includes(p))!;
  return [finishers[0]!, finishers[1]!, last];
}

/**
 * The outcome after `history`, or null while the game goes on. "No moves" needs the engine and is set elsewhere; so does the
 * end of a full-ranking game by the move limit (the engine ranks the seats that are still playing by distance).
 */
export function outcomeOf(owner: Int8Array, history: readonly number[], variant: Variant = DEFAULT_VARIANT): Outcome | null {
  if (playsOn(variant)) {
    const ranking = completeRanking(playState(history, variant).finishers);
    return ranking ? { kind: 'ranking', ranking, reason: 'complete' } : null;
  }
  if (history.length > 0) {
    const mover = ((history.length - 1) % variant.players) as Player;
    if (hasWon(owner, mover, variant)) return { kind: 'win', winner: mover };
  }
  if (history.length >= moveLimit(variant)) return { kind: 'move-limit' };
  return null;
}
