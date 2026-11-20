// The distance of a result: how many plies the side that did not win would still have needed. An estimate, computed after the
// game by playing the final position out. Definition and limits: docs/web-ui.md, "Distance of the result".
import type { AnalyseFn } from '../guides/analyser';
import { playState, replay } from '../game/board-state';
import type { Player, Variant } from '../game/variant';
import { moveLimit, withRanking } from '../game/variant';
import { soloRace } from './solo';

/** Node budget of the engine for each candidate move of a playout ply (the analysis scores every legal move with it). */
export const PLAYOUT_NODES = 500;
/** The most moves of the losing side in a two-player solo race. */
export const SOLO_CAP = 100;
/** The most plies of the engine playout of a three-player game. */
export const PLAYOUT_CAP = 60;

/**
 * - `solo`: two players; `plies` are the moves of the losing side alone, until its goal is full.
 * - `second`: three players; `plies` are all plies of the playout of the two other seats, until the first of them has filled its goal (second place).
 * `capped`: the cap was reached first; the distance is more than `plies`.
 */
export interface DistanceEstimate {
  kind: 'solo' | 'second';
  plies: number;
  capped: boolean;
}

export interface DistanceInput {
  /** The moves of the finished game; the last move made `winner` win. */
  history: readonly number[];
  /** The variant of the finished game (first-winner rules). */
  variant: Variant;
  winner: Player;
  /** The analysis of the engine in use (or the helper of a net engine): one score per legal move, the best is the engine's choice. */
  analyse: AnalyseFn;
  signal?: AbortSignal | undefined;
  /** Another cap than SOLO_CAP (two players) or PLAYOUT_CAP (three players); for tests. */
  cap?: number;
}

/**
 * Estimates the distance, or resolves null when there is nothing to say (a game cut by the move limit would end before the race
 * does). Rejects with an AbortError on abort and with the engine's error when the playout fails; the caller shows nothing then.
 */
export async function estimateDistance(input: DistanceInput): Promise<DistanceEstimate | null> {
  const { history, variant, winner, analyse, signal, cap: capOverride } = input;
  if (history.length >= moveLimit(variant)) return null;
  if (variant.players === 2) {
    const loser = (winner === 0 ? 1 : 0) as Player;
    const cap = Math.min(capOverride ?? SOLO_CAP, moveLimit(variant) - history.length);
    const r = await soloRace(replay(history, variant), loser, variant, { cap, signal });
    return { kind: 'solo', plies: r.moves, capped: r.capped };
  }
  return await playOutThree(history, variant, analyse, capOverride ?? PLAYOUT_CAP, signal);
}

/** Argmax of the scores; the first of equal scores wins, so the playout is deterministic. */
function bestMove(moves: readonly number[], scores: readonly number[]): number {
  let best = -1;
  for (let i = 0; i < moves.length; i++) if (best < 0 || scores[i]! > scores[best]!) best = i;
  if (best < 0) throw new Error('the engine scored no move');
  return moves[best]!;
}

/**
 * Three players: the game goes on under full ranking (the winner is out, its pieces stay), and the engine chooses the moves of
 * both other seats with PLAYOUT_NODES per candidate move, until the second seat has finished.
 */
async function playOutThree(history: readonly number[], variant: Variant, analyse: AnalyseFn, maxPlies: number, signal?: AbortSignal): Promise<DistanceEstimate> {
  const full = withRanking(variant, 'full');
  const start = history.length;
  const cap = Math.min(maxPlies, moveLimit(full) - start);
  const played = [...history];
  while (playState(played, full).finishers.length < 2) {
    if (signal?.aborted) throw new DOMException('The estimate was aborted', 'AbortError');
    if (played.length - start >= cap) return { kind: 'second', plies: played.length - start, capped: true };
    const result = await analyse([...played], null, PLAYOUT_NODES, signal, full);
    played.push(bestMove(result.moves, result.scores));
  }
  return { kind: 'second', plies: played.length - start, capped: false };
}
