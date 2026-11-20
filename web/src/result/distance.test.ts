import { describe, expect, it, vi } from 'vitest';
import { GOAL_CELLS_3, cellId } from '../board/cells';
import type { AnalysisResult } from '../engines/engine';
import { makeMove } from '../engines/engine';
import { wonGame2, wonGame3Teleport } from '../engines/testing/won-game';
import { goalCells, playState, replay } from '../game/board-state';
import type { Player, Variant } from '../game/variant';
import { rankingOf } from '../game/variant';
import type { AnalyseFn } from '../guides/analyser';
import { PLAYOUT_NODES, estimateDistance } from './distance';

const TWO: Variant = { jump: 'adjacent', players: 2 };
const THREE: Variant = { jump: 'adjacent', players: 3 };
const noEngine: AnalyseFn = () => Promise.reject(new Error('the engine must not be asked'));

describe('estimateDistance, two players', () => {
  it('races the loser alone: at least one move for each piece outside its goal, finished within the cap, and no engine call', async () => {
    const { history, winner } = wonGame2();
    const loser = (winner === 0 ? 1 : 0) as Player;
    const owner = replay(history, TWO);
    const goal = new Set(goalCells(TWO)[loser]);
    const outside = [...owner.keys()].filter((c) => owner[c] === loser && !goal.has(c)).length;
    const estimate = await estimateDistance({ history, variant: TWO, winner, analyse: noEngine });
    expect(estimate).not.toBeNull();
    expect(estimate!.kind).toBe('solo');
    expect(estimate!.capped).toBe(false);
    expect(estimate!.plies).toBeGreaterThanOrEqual(outside);
    expect(estimate!.plies).toBeLessThanOrEqual(80);
  });

  it('says "more than the cap" when the race is longer than the cap', async () => {
    const { history, winner } = wonGame2();
    const estimate = await estimateDistance({ history, variant: TWO, winner, analyse: noEngine, cap: 1 });
    expect(estimate).toEqual({ kind: 'solo', plies: 1, capped: true });
  });

  it('has nothing to say when the game already used the move limit', async () => {
    const { winner } = wonGame2();
    expect(await estimateDistance({ history: new Array<number>(200).fill(0), variant: TWO, winner, analyse: noEngine })).toBeNull();
  });

  it('rejects with an AbortError on an aborted signal', async () => {
    const { history, winner } = wonGame2();
    const controller = new AbortController();
    controller.abort();
    await expect(estimateDistance({ history, variant: TWO, winner, analyse: noEngine, signal: controller.signal })).rejects.toMatchObject({ name: 'AbortError' });
  });
});

/** An engine that moves a piece of the seat on turn straight into an empty cell of its goal (the history of wonGame3Teleport is no legal game either), and lists a worse move first. */
function teleportEngine(calls: Array<{ history: readonly number[]; moves: readonly number[] | null; nodes: number; variant: Variant | undefined }>): AnalyseFn {
  return (history, moves, nodes, _signal, variant) => {
    calls.push({ history, moves, nodes, variant });
    const seat = playState(history, variant!).sideToMove;
    const owner = replay(history, variant!);
    const goal = GOAL_CELLS_3[seat];
    const piece = [...owner.keys()].find((c) => owner[c] === seat && !goal.includes(c))!;
    const good = makeMove(piece, goal.find((c) => owner[c] === -1)!);
    const bad = makeMove(piece, cellId(0, 1)); // a worse first candidate; the playout must not take it
    const result: AnalysisResult = { moves: [bad, good], scores: [-5, 40], position: 40, nodes: 1, ms: 0 };
    return Promise.resolve(result);
  };
}

describe('estimateDistance, three players', () => {
  it('plays on under full ranking with the best-scored move of each ply, until the second seat has finished', async () => {
    const history = wonGame3Teleport();
    expect(playState(history, { ...THREE, ranking: 'full' }).finishers).toEqual([0]); // seat 0 has won, nobody else has finished
    const calls: Parameters<typeof teleportEngine>[0] = [];
    const estimate = await estimateDistance({ history, variant: THREE, winner: 0, analyse: teleportEngine(calls) });
    // seats 1 and 2 alternate, seat 1 first; seat 1 needs its ten moves and finishes with the 10th of its turns: plies 1, 3, ..., 19
    expect(estimate).toEqual({ kind: 'second', plies: 19, capped: false });
    expect(calls).toHaveLength(19);
    expect(calls.every((c) => c.moves === null && c.nodes === PLAYOUT_NODES && rankingOf(c.variant!) === 'full' && c.variant!.players === 3)).toBe(true);
    expect(calls[0]!.history).toHaveLength(history.length);
    expect(calls[18]!.history).toHaveLength(history.length + 18);
  });

  it('stops at the cap and says so', async () => {
    const calls: Parameters<typeof teleportEngine>[0] = [];
    const estimate = await estimateDistance({ history: wonGame3Teleport(), variant: THREE, winner: 0, analyse: teleportEngine(calls), cap: 5 });
    expect(estimate).toEqual({ kind: 'second', plies: 5, capped: true });
    expect(calls).toHaveLength(5);
  });

  it('passes the signal on and rejects when the engine fails or the signal fires', async () => {
    const failing = vi.fn<AnalyseFn>(() => Promise.reject(Object.assign(new Error('x'), { code: 'unsupported' })));
    await expect(estimateDistance({ history: wonGame3Teleport(), variant: THREE, winner: 0, analyse: failing })).rejects.toThrow('x');
    const controller = new AbortController();
    const seen: Array<AbortSignal | undefined> = [];
    const hanging: AnalyseFn = (_h, _m, _n, signal) => {
      seen.push(signal);
      return new Promise<AnalysisResult>((_resolve, reject) => signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError'))));
    };
    const run = estimateDistance({ history: wonGame3Teleport(), variant: THREE, winner: 0, analyse: hanging, signal: controller.signal });
    controller.abort();
    await expect(run).rejects.toMatchObject({ name: 'AbortError' });
    expect(seen[0]).toBe(controller.signal);
  });

  it('rejects when the engine scores no move', async () => {
    const empty: AnalyseFn = () => Promise.resolve({ moves: [], scores: [], position: 0, nodes: 0, ms: 0 });
    await expect(estimateDistance({ history: wonGame3Teleport(), variant: THREE, winner: 0, analyse: empty })).rejects.toThrow('no move');
  });
});
