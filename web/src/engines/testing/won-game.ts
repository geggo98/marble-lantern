// Finished games for tests of the result screen. Test-only: it uses the tiny move generator of the fake engine.
import { CELLS, START_CELLS_3, GOAL_CELLS_3, cellId } from '../../board/cells';
import { goalCells, hasWon, initialOwners } from '../../game/board-state';
import type { Player } from '../../game/variant';
import { DEFAULT_VARIANT } from '../../game/variant';
import { makeMove } from '../engine';
import { hexDistance } from '../../ui/board-model';
import { seeded } from './fake-engine';
import { tinyLegalMoves } from './tiny-moves';

/** A legal two-player game (adjacent jumps) in which both sides advance greedily until one wins; seed 3 ends after 119 plies with a win of side 0. */
export function wonGame2(seed = 3): { history: number[]; winner: Player } {
  const rand = seeded(seed);
  const owner = initialOwners(DEFAULT_VARIANT);
  const goals = goalCells(DEFAULT_VARIANT);
  const tips = goals.map((g) => g.reduce((b, c) => (Math.abs(CELLS[c]!.r) > Math.abs(CELLS[b]!.r) ? c : b), g[0]!));
  const history: number[] = [];
  for (let ply = 0; ply < 200; ply++) {
    const side = (ply % 2) as Player;
    const scored = tinyLegalMoves(owner, side, DEFAULT_VARIANT)
      .map((m) => ({ m, s: hexDistance(m & 0xff, tips[side]!) - hexDistance(m >>> 8, tips[side]!) + rand() * 1.5 }))
      .sort((a, b) => b.s - a.s);
    const m = scored[0]!.m;
    owner[m & 0xff] = -1;
    owner[m >>> 8] = side;
    history.push(m);
    if (hasWon(owner, side, DEFAULT_VARIANT)) return { history, winner: side };
  }
  throw new Error(`seed ${seed}: no win within 200 plies`);
}

/**
 * A three-player history, not a legal game (pieces jump to their goal cells directly), in which seat 0 fills its goal with its tenth
 * move, at ply 28, while seats 1 and 2 only shuffle one piece to the centre and back. Fine for code that replays turn order and
 * goals only (board-state.playState), not for an engine.
 */
export function wonGame3Teleport(): number[] {
  const history: number[] = [];
  const shuffle = [cellId(0, 0), cellId(1, 0)];
  for (let round = 0; round < 10; round++) {
    history.push(makeMove(START_CELLS_3[0][round]!, GOAL_CELLS_3[0][round]!));
    for (const seat of [1, 2] as const) {
      const home = START_CELLS_3[seat][0]!;
      history.push(round % 2 === 0 ? makeMove(home, shuffle[seat - 1]!) : makeMove(shuffle[seat - 1]!, home));
    }
  }
  return history.slice(0, 28); // drop the two shuffles after the tenth move of seat 0
}
