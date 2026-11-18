// Tests of the analysis API that every real engine core must pass: the same suite runs against CppCore (C++ Wasm) and JavaCore
// (TeaVM, both targets). Used by cpp/core.test.ts and java/core.test.ts; do not import it from production code.
import { describe, expect, it } from 'vitest';
import { EngineFailure } from '../protocol';
import type { AnalysisResult, BenchResult, ChooseResult, GameSetup } from '../engine';
import { SCORE, scoreBand } from '../analysis';

/** The part of CppCore and JavaCore that the suite uses. */
export interface AnalysingCore {
  newGame(setup: GameSetup): void;
  legalMoves(history: readonly number[]): number[];
  choose(history: readonly number[]): ChooseResult;
  analyse(history: readonly number[], moves: readonly number[] | null, nodes: number): AnalysisResult;
  bench(plies: number, nodes: number): BenchResult;
}

/** The benchmark game of plan section 8; the values come from the JVM, not from the engine under test. */
export const BENCH = { plies: 40, nodes: 20000, checksum: -1986550544382302149n, totalNodes: 800000 };
export const checksumOf = (moves: readonly number[]): bigint => moves.reduce((c, m) => BigInt.asIntN(64, c * 31n + BigInt(m)), 0n);

const failureOf = (run: () => unknown): EngineFailure => {
  try {
    run();
  } catch (e) {
    if (e instanceof EngineFailure) return e;
    throw e;
  }
  throw new Error('expected an EngineFailure');
};

export interface PlayedGame {
  /** All moves of a finished game; the last move wins. */
  history: number[];
  /** Number of plies before the first move that the race module chose (a passed position), or -1. */
  racePly: number;
}

/** Plays a game to its end with `nodes` per move. The side that made the last move has won. */
export function playGame(core: AnalysingCore, nodes: number): PlayedGame {
  core.newGame({ nodes: [nodes, nodes] });
  const history: number[] = [];
  let racePly = -1;
  for (let ply = 0; ply < 400; ply++) {
    if (core.legalMoves(history).length === 0) return { history, racePly };
    const c = core.choose(history);
    if (c.raceMove && racePly < 0) racePly = ply;
    history.push(c.move);
  }
  throw new Error('the game did not end within 400 plies');
}

export function analysisSuite(label: string, make: () => Promise<AnalysingCore>): void {
  describe(`analyse, ${label}`, () => {
    it('scores all legal moves in the order of legalMoves; position is the best score; nodes are the budget for each move', async () => {
      const core = await make();
      const legal = core.legalMoves([]);
      const r = core.analyse([], null, 500);
      expect(r.moves).toEqual(legal);
      expect(r.scores).toHaveLength(legal.length);
      expect(r.scores.every(Number.isInteger)).toBe(true);
      expect(r.position).toBe(Math.max(...r.scores));
      expect(r.nodes).toBeGreaterThan(legal.length * 100);
      expect(r.nodes).toBeLessThanOrEqual(legal.length * (500 + 64));
      expect(r.ms).toBeGreaterThanOrEqual(0);
      // the start position is balanced: no move wins or loses, every score is in the ordinary band
      expect(r.scores.map(scoreBand)).toEqual(r.scores.map(() => 'ordinary'));
      expect(Math.abs(r.position)).toBeLessThan(200);
    });

    it('scores a subset in the order given, with the same scores as the full list, and repeats exactly', async () => {
      const core = await make();
      const game = playGame(core, 200).history.slice(0, 12);
      const full = core.analyse(game, null, 1000);
      const picks = [full.moves[5]!, full.moves[0]!, full.moves[full.moves.length - 1]!];
      const some = core.analyse(game, picks, 1000);
      expect(some.moves).toEqual(picks);
      expect(some.scores).toEqual([full.scores[5], full.scores[0], full.scores[full.scores.length - 1]]);
      expect(some.position).toBe(Math.max(...some.scores));
      // a different budget in between replaces the analysis player; coming back must give the same numbers
      core.analyse(game, picks, 300);
      expect(core.analyse(game, null, 1000).scores).toEqual(full.scores);
    });

    it('rejects an illegal move, an empty list, a bad budget and a finished game with the right codes', async () => {
      const core = await make();
      const legal = core.legalMoves([]);
      const notLegal = [...Array(121).keys()].map((c) => c | (c << 8)).find((m) => !legal.includes(m))!;
      expect(failureOf(() => core.analyse([], [notLegal], 100)).code).toBe('illegal-move');
      expect(failureOf(() => core.analyse([], [legal[0]!, notLegal], 100)).code).toBe('illegal-move');
      expect(failureOf(() => core.analyse([], [], 100)).code).toBe('illegal-move');
      expect(failureOf(() => core.analyse([], null, 0)).code).toBe('internal');
      expect(failureOf(() => core.analyse([legal[0]!, legal[0]!], null, 100)).code).toBe('illegal-history');
      const { history } = playGame(core, 100);
      expect(failureOf(() => core.analyse(history, null, 100)).code).toBe('illegal-history');
    });

    it('does not change choose(): the benchmark fingerprint holds with an analysis between every two moves', async () => {
      const core = await make();
      core.newGame({ nodes: [BENCH.nodes, BENCH.nodes] });
      const history: number[] = [];
      let nodes = 0;
      for (let ply = 0; ply < BENCH.plies; ply++) {
        const c = core.choose(history);
        nodes += c.nodes;
        history.push(c.move);
        core.analyse(history, null, ply % 2 === 0 ? 300 : 700);
      }
      expect(checksumOf(history)).toBe(BENCH.checksum);
      expect(nodes).toBe(BENCH.totalNodes);
      // and the bench request, which starts its own players, is unaffected as well
      const b = core.bench(BENCH.plies, BENCH.nodes);
      expect(checksumOf(b.moves)).toBe(BENCH.checksum);
      expect(b.rows1Requests).toBe(0);
    }, 120_000);

    it('has the sign of the mover: the move that wins at once scores MATE - 1 and the rest less; a move that allows it scores -(MATE - 2)', async () => {
      const core = await make();
      const { history } = playGame(core, 300);
      const n = history.length;
      const winning = history[n - 1]!;
      const atWin = core.analyse(history.slice(0, n - 1), null, 500);
      const at = atWin.moves.indexOf(winning);
      expect(at).toBeGreaterThanOrEqual(0);
      expect(atWin.scores[at]).toBe(SCORE.mate - 1);
      expect(atWin.position).toBe(SCORE.mate - 1);
      expect(atWin.scores.filter((s) => s === SCORE.mate - 1)).toHaveLength(1);
      // the winner's other moves do not win at once, so each scores lower than the winning one
      expect(Math.max(...atWin.scores.filter((_, i) => i !== at))).toBeLessThan(SCORE.mate - 1);
      // one ply earlier the loser made the move that allows the win: the same mate, one ply farther
      const allowing = history[n - 2]!;
      const atLoss = core.analyse(history.slice(0, n - 2), [allowing], 500);
      expect(atLoss.scores[0]).toBe(-(SCORE.mate - 2));
      expect(atLoss.scores[0]).toBe(-atWin.scores[at]! + 1);
      expect(scoreBand(atLoss.scores[0]!)).toBe('mate');
    }, 60_000);

    it('scores a position that the race module plays without a search: a real score with the sign of the eventual winner, where choose() reports 0', async () => {
      const core = await make();
      const { history, racePly } = playGame(core, 300);
      expect(racePly).toBeGreaterThan(0);
      const winner = (history.length - 1) % 2;
      core.newGame({ nodes: [300, 300] });
      const choice = core.choose(history.slice(0, racePly));
      expect(choice.raceMove).toBe(true);
      expect(choice.score).toBe(0);
      // the analysis player has no race module: it searches, and a passed position is scored by the evaluator (race table where it covers)
      for (const ply of [racePly, history.length - 6]) {
        const r = core.analyse(history.slice(0, ply), null, 1000);
        expect(r.moves.length).toBeGreaterThan(1);
        expect(r.nodes).toBeGreaterThan(0);
        expect(Math.sign(r.position), `ply ${ply}`).toBe(ply % 2 === winner ? 1 : -1);
        expect(new Set(r.scores).size).toBeGreaterThan(1);
      }
    }, 60_000);
  });
}
