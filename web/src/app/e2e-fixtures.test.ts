// The fixed games of the browser tests (web/e2e/fixtures.ts) are what their generators give today, and they are legal for the rules
// the fake move generator knows. A change of the generator or of the board makes this test fail, not the slow browser test.
import { describe, expect, it } from 'vitest';
import { LONG_HOP, PLAY_ON_WIN, THREE_PLAYER_LIMIT } from '../../e2e/fixtures';
import { findLongHop } from '../../e2e/long-hop-fixture';
import { moveFrom, moveTo } from '../engines/engine';
import { tinyLegalMoves } from '../engines/testing/tiny-moves';
import { applyMove, initialOwners, outcomeOf, playState } from '../game/board-state';
import type { Variant } from '../game/variant';
import { decodeMoves, encodeMoves } from './game-url';

describe('e2e fixtures', () => {
  it('the long-hop start is what the generator finds, and its hop is legal for long hops only', () => {
    const found = findLongHop();
    expect({ moves: encodeMoves(found.moves), plies: found.moves.length, side: found.side, from: found.from, to: found.to }).toEqual(LONG_HOP);
    const owner = initialOwners();
    for (const m of found.moves) applyMove(owner, m);
    const adjacent = tinyLegalMoves(owner, LONG_HOP.side, { jump: 'adjacent', players: 2 });
    const long = tinyLegalMoves(owner, LONG_HOP.side, { jump: 'long', players: 2 });
    const hop = long.filter((m) => moveFrom(m) === LONG_HOP.from && moveTo(m) === LONG_HOP.to);
    expect(hop).toHaveLength(1);
    expect(adjacent).not.toContain(hop[0]);
  });

  it('the three-player game has 300 legal plies and ends at the move limit without a winner', () => {
    const THREE: Variant = { jump: 'adjacent', players: 3 };
    const moves = decodeMoves(THREE_PLAYER_LIMIT, 300);
    expect(moves).toHaveLength(300);
    const owner = initialOwners(THREE);
    const replayed: number[] = [];
    moves.forEach((m, i) => {
      expect(tinyLegalMoves(owner, i % 3, THREE)).toContain(m);
      applyMove(owner, m);
      replayed.push(m);
    });
    expect(outcomeOf(owner, replayed, THREE)).toEqual({ kind: 'move-limit' });
    // the same loop as the generator: ply i takes the legal move (i * 7919 + 13) mod n
    const again = initialOwners(THREE);
    const regenerated: number[] = [];
    for (let i = 0; i < 300; i++) {
      const legal = tinyLegalMoves(again, i % 3, THREE);
      const move = legal[(i * 7919 + 13) % legal.length]!;
      applyMove(again, move);
      regenerated.push(move);
    }
    expect(encodeMoves(regenerated)).toBe(THREE_PLAYER_LIMIT);
  });

  it('the play-on game: 132 plies end with a win of seat 2 under first-winner rules, and seat 2 is the first seat out under full ranking', () => {
    const THREE: Variant = { jump: 'adjacent', players: 3 };
    const FULL: Variant = { ...THREE, ranking: 'full' };
    const moves = decodeMoves(PLAY_ON_WIN.moves, 300);
    expect(moves).toHaveLength(PLAY_ON_WIN.plies);
    const owner = initialOwners(THREE);
    moves.forEach((m, i) => {
      expect(tinyLegalMoves(owner, i % 3, THREE), `ply ${i}`).toContain(m);
      applyMove(owner, m);
    });
    expect(outcomeOf(owner, moves, THREE)).toEqual({ kind: 'win', winner: PLAY_ON_WIN.winner });
    expect(playState(moves, FULL)).toMatchObject({ finishers: [PLAY_ON_WIN.winner], finishedAt: [PLAY_ON_WIN.plies] });
  });
});
