package sternhalma.search;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;

/**
 * {@link AlphaBeta#rootScores} (round-2 plan E10): exact scores of all root moves at a fixed depth, with futility and
 * late move reductions off, a full window at the root, and a table that cannot make a result depend on earlier calls.
 */
class RootScoresTest {

    private static final String OPTIONS = "pvs=1,killers=1,history=1,lmr=2,asp=1,ttrep=1,mate=1,partial=1";
    /** The BEST options with the pruning of the real teacher spec; {@code exact()} switches the pruning off. */
    private static final SearchOptions PRUNING = PlayerSpec.parse("ab:" + OPTIONS + ",fut=1,futk=20,fut2=1").options();
    private static final SearchOptions EXACT = PRUNING.exact();

    private static Position game(long seed, int plies) {
        Random random = new Random(seed);
        Position pos = Position.initial(RuleSet.defaults(2));
        for (int i = 0; i < plies && pos.outcome() == null; i++) {
            int[] moves = pos.legalMoves();
            pos.make(moves[random.nextInt(moves.length)]);
        }
        return pos;
    }

    /** Plain negamax without pruning or table: the oracle. */
    private static int plain(Evaluator eval, Position pos, int depth, int height) {
        if (pos.outcome() != null) {
            return AlphaBeta.terminalScore(pos, height);
        }
        if (depth == 0) {
            return eval.evaluate(pos);
        }
        int[] moves = pos.legalMoves();
        if (moves.length == 0) {
            return AlphaBeta.terminalScore(pos, height);
        }
        int best = Integer.MIN_VALUE;
        for (int m : moves) {
            pos.make(m);
            best = Math.max(best, -plain(eval, pos, depth - 1, height + 1));
            pos.unmake();
        }
        return best;
    }

    static int[] oracle(Evaluator eval, Position pos, int depth) {
        int[] moves = pos.legalMoves();
        int[] scores = new int[moves.length];
        Position copy = pos.copy();
        for (int i = 0; i < moves.length; i++) {
            copy.make(moves[i]);
            scores[i] = -plain(eval, copy, depth - 1, 1);
            copy.unmake();
        }
        return scores;
    }

    @Test
    void scoresEqualPlainNegamaxForEveryRootMoveInTheOrderOfLegalMoves() {
        for (String name : new String[] {"linear-r3", "racelin"}) {
            Evaluator eval = Evaluators.byName(name);
            AlphaBeta search = new AlphaBeta(eval, 16, true, EXACT);
            for (int depth = 1; depth <= 3; depth++) {
                for (int ply : new int[] {0, 6, 20, 45, 80}) {
                    Position pos = game(1010 + ply, ply);
                    assertArrayEquals(oracle(eval, pos, depth), search.rootScores(pos, depth),
                            name + " depth " + depth + " ply " + ply);
                }
            }
        }
    }

    @Test
    void deeperSearchesFromTheStartPositionAreExactToo() {
        // from depth 4 on, one position can occur at two different remaining depths inside one call (two steps
        // against one jump); only entries of exactly the depth asked for may be used
        Evaluator eval = Evaluators.byName("linear-r3");
        Position pos = Position.initial(RuleSet.defaults(2));
        AlphaBeta search = new AlphaBeta(eval, 16, true, EXACT);
        search.rootScores(pos, 3); // fills the table with shallower entries first
        assertArrayEquals(oracle(eval, pos, 4), search.rootScores(pos, 4));
        assertArrayEquals(oracle(eval, pos, 5), search.rootScores(pos, 5));
    }

    @Test
    void scoresDoNotDependOnEarlierCalls() {
        Evaluator eval = Evaluators.byName("racelin");
        AlphaBeta reused = new AlphaBeta(eval, 16, true, EXACT);
        Position a = game(1011, 30);
        Position b = game(1012, 31);
        // depth 3 first, so the table holds deeper entries when depth 2 is asked; the same position twice; a
        // second position
        int[][] ask = {{0, 3}, {0, 2}, {1, 3}, {0, 3}, {1, 2}, {0, 1}, {1, 3}, {0, 2}};
        for (int[] x : ask) {
            Position pos = x[0] == 0 ? a : b;
            AlphaBeta fresh = new AlphaBeta(eval, 16, true, EXACT);
            int[] expected = fresh.rootScores(pos, x[1]);
            assertArrayEquals(expected, reused.rootScores(pos, x[1]), "position " + x[0] + " depth " + x[1]);
            // not even the work differs: no entry of an earlier call is found
            assertEquals(fresh.nodes(), reused.nodes(), "nodes, position " + x[0] + " depth " + x[1]);
        }
        // the overlap is real: a successor of a, asked after a, still equals a fresh searcher
        Position next = a.copy();
        next.make(a.legalMoves()[0]);
        assertArrayEquals(new AlphaBeta(eval, 16, true, EXACT).rootScores(next, 3), reused.rootScores(next, 3));
    }

    @Test
    void anOrdinarySearchBetweenCallsChangesNothing() {
        Evaluator eval = Evaluators.byName("linear-r3");
        AlphaBeta shared = new AlphaBeta(eval, 16, true, EXACT);
        Position pos = game(1013, 25);
        int[] first = shared.rootScores(pos, 3);
        shared.search(pos, 6, Long.MAX_VALUE, 20000); // fills the table with deeper entries of the plain keys
        assertArrayEquals(first, shared.rootScores(pos, 3));
    }

    @Test
    void theBestRootScoreIsTheScoreOfASearchToTheSameDepth() {
        Evaluator eval = Evaluators.byName("linear-r3");
        for (int depth = 1; depth <= 3; depth++) {
            Position pos = game(1014 + depth, 12 * depth);
            int[] scores = new AlphaBeta(eval, 16, true, EXACT).rootScores(pos, depth);
            int best = Integer.MIN_VALUE;
            for (int s : scores) {
                best = Math.max(best, s);
            }
            assertEquals(best, new AlphaBeta(eval, 16, true, EXACT).search(pos, depth).score(), "depth " + depth);
        }
    }

    @Test
    void refusesOptionsWithPruningBecauseTheirScoresAreNotTheDepthValue() {
        Evaluator eval = Evaluators.byName("linear-r3");
        Position pos = game(1016, 20);
        assertFalse(PRUNING.exactRoot());
        assertThrows(IllegalStateException.class, () -> new AlphaBeta(eval, 14, true, PRUNING).rootScores(pos, 2));
        SearchOptions lmrOnly = PRUNING.withFutility(0, 20, 0);
        assertFalse(lmrOnly.exactRoot());
        assertThrows(IllegalStateException.class, () -> new AlphaBeta(eval, 14, true, lmrOnly).rootScores(pos, 2));
        assertTrue(lmrOnly.exact().exactRoot());
        assertEquals(0, EXACT.lmr());
        assertFalse(EXACT.futilityOn());
        assertTrue(EXACT.pvs() && EXACT.killers() && EXACT.history() && EXACT.mateFix(), "the rest is kept");
    }

    @Test
    void theDifferenceFromPrunedSearchIsReal() {
        // futility and LMR change the depth-3 score on some positions, which is why the options are refused
        Evaluator eval = Evaluators.byName("linear-r3");
        int differing = 0;
        for (int i = 0; i < 40; i++) {
            Position pos = game(2000 + i, 10 + 2 * i);
            if (pos.outcome() != null) {
                continue;
            }
            int[] exact = new AlphaBeta(eval, 16, true, EXACT).rootScores(pos, 3);
            int best = Integer.MIN_VALUE;
            for (int s : exact) {
                best = Math.max(best, s);
            }
            if (new AlphaBeta(eval, 16, true, PRUNING).search(pos, 3).score() != best) {
                differing++;
            }
        }
        assertTrue(differing > 0, "the pruned search differs from the exact one on some of 40 positions");
    }

    @Test
    void aWinningMoveScoresAsMate() {
        Position win = WinInOneTest.winInOne(9);
        AlphaBeta search = new AlphaBeta(Evaluators.byName("linear-r3"), 14, true, EXACT);
        int[] scores = search.rootScores(win, 2);
        int mates = 0;
        for (int s : scores) {
            if (Scores.kind(s) == Scores.Kind.MATE && s > 0) {
                mates++;
                assertEquals(AlphaBeta.MATE - 1, s, "a win in one move is MATE - 1 at height 1");
            }
        }
        assertTrue(mates >= 1);
    }

    @Test
    void racePositionsKeepTheirBandSoThatConsumersCanIgnoreThem() {
        // racelin returns race-band leaf values in passed positions; they pass through rootScores unchanged
        Evaluator eval = Evaluators.byName("racelin");
        int race = 0;
        Random random = new Random(92);
        for (int i = 0; i < 10; i++) {
            Position pos = RaceEvaluatorTest.deepPassed(random, i % 2, 4);
            for (int s : new AlphaBeta(eval, 16, true, EXACT).rootScores(pos, 2)) {
                race += Scores.kind(s) == Scores.Kind.RACE ? 1 : 0;
            }
        }
        assertTrue(race > 0, "passed positions yield race-band root scores");
    }

    @Test
    void incrementalEvaluationInAssertModeKeepsReusedAndFreshSearchersEqual() {
        // E13: the incremental state is reset per call; assert mode compares every evaluation with the full one
        for (String name : new String[] {"linear-r3", "racelin"}) {
            Evaluator eval = Evaluators.byName(name);
            assertTrue(eval.incremental() != null, name + " has an incremental state");
            AlphaBeta reused = new AlphaBeta(eval, 16, true, EXACT, true);
            Position a = game(1021, 35);
            Position b = game(1022, 50);
            for (int[] x : new int[][] {{0, 3}, {1, 2}, {0, 2}, {1, 3}, {0, 3}}) {
                Position pos = x[0] == 0 ? a : b;
                int[] fresh = new AlphaBeta(eval, 16, true, EXACT, true).rootScores(pos, x[1]);
                assertArrayEquals(fresh, reused.rootScores(pos, x[1]), name + " " + x[0] + "/" + x[1]);
                assertArrayEquals(oracle(eval, pos, x[1]), fresh, name + " against plain negamax");
            }
        }
    }
}
