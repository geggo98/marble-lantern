package sternhalma.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

import sternhalma.engine.Corner;
import sternhalma.engine.Geometry;
import sternhalma.engine.Move;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;

class AlphaBetaTest {

    private static final Evaluator EVALUATOR = new DistanceEvaluator();

    private static int cell(int q, int r) {
        return Geometry.id(q, r);
    }

    /** Player 0 owns 9 goal cells and stands on (0,4), one step from the last goal cell (-1,5). */
    private static Position oneStepFromWinning() {
        Position pos = Position.empty(RuleSet.defaults(2));
        for (int c : Geometry.cornerCells(Corner.R_POS.ordinal())) {
            if (c != cell(-1, 5)) {
                pos.put(0, c);
            }
        }
        return pos.put(0, 0, 4).put(1, 4, -8);
    }

    /** Plain negamax without pruning, the oracle for the pruned search. */
    private static int plain(Position pos, int depth, int height) {
        if (pos.outcome() != null) {
            return AlphaBeta.terminalScore(pos, height);
        }
        if (depth == 0) {
            return EVALUATOR.evaluate(pos);
        }
        int[] moves = pos.legalMoves();
        if (moves.length == 0) {
            return AlphaBeta.terminalScore(pos, height);
        }
        int best = Integer.MIN_VALUE;
        for (int m : moves) {
            pos.make(m);
            best = Math.max(best, -plain(pos, depth - 1, height + 1));
            pos.unmake();
        }
        return best;
    }

    private static Position randomPosition(long seed, int plies) {
        Random random = new Random(seed);
        Position pos = Position.initial(RuleSet.defaults(2));
        for (int i = 0; i < plies; i++) {
            int[] moves = pos.legalMoves();
            pos.make(moves[random.nextInt(moves.length)]);
        }
        return pos;
    }

    @Test
    void findsTheImmediateWinAtDepthOne() {
        SearchResult result = new AlphaBeta(EVALUATOR).search(oneStepFromWinning(), 1);
        assertEquals(Move.of(cell(0, 4), cell(-1, 5)), result.move());
        assertEquals(AlphaBeta.MATE - 1, result.score());
    }

    @Test
    void stopsDeepeningOnceAForcedWinIsKnown() {
        SearchResult result = new AlphaBeta(EVALUATOR).search(oneStepFromWinning(), 6);
        assertEquals(1, result.depth());
        assertEquals(Move.of(cell(0, 4), cell(-1, 5)), result.move());
    }

    @Test
    void prunedSearchGivesTheSameValueAsPlainNegamaxWithAndWithoutTheTable() {
        for (long seed = 1; seed <= 6; seed++) {
            Position pos = randomPosition(seed, 8 + (int) seed);
            for (int depth = 1; depth <= 3; depth++) {
                int expected = plain(pos.copy(), depth, 0);
                assertEquals(expected, new AlphaBeta(EVALUATOR, 10, false).search(pos, depth).score(),
                        "no table, seed " + seed + " depth " + depth);
                assertEquals(expected, new AlphaBeta(EVALUATOR, 16, true).search(pos, depth).score(),
                        "table, seed " + seed + " depth " + depth);
            }
        }
    }

    @Test
    void searchDoesNotChangeThePositionItIsGiven() {
        Position pos = randomPosition(5, 12);
        Position before = pos.copy();
        new AlphaBeta(EVALUATOR).search(pos, 3);
        assertEquals(before, pos);
        assertEquals(before.ply(), pos.ply());
    }

    @Test
    void aTimeLimitStillReturnsALegalMoveFromACompletedDepth() {
        Position pos = Position.initial(RuleSet.defaults(2));
        SearchResult result = new AlphaBeta(EVALUATOR).search(pos, AlphaBeta.MAX_DEPTH, 30);
        assertTrue(result.depth() >= 1, result.toString());
        assertTrue(java.util.Arrays.stream(pos.legalMoves()).anyMatch(m -> m == result.move()), result.toString());
    }

    @Test
    void aFinishedGameHasNoMove() {
        Position pos = oneStepFromWinning();
        pos.make(Move.of(cell(0, 4), cell(-1, 5)));
        assertEquals(-1, new AlphaBeta(EVALUATOR).search(pos, 2).move());
    }

    @Test
    void rejectsThreePlayers() {
        Position pos = Position.initial(RuleSet.defaults(3));
        assertThrows(UnsupportedOperationException.class, () -> new AlphaBeta(EVALUATOR).search(pos, 1));
    }

    @Test
    void beatsARandomPlayerFromBothSides() {
        Position start = Position.initial(RuleSet.defaults(2));
        for (long seed = 1; seed <= 3; seed++) {
            Player ab = new AlphaBetaPlayer(2);
            Player random = new RandomPlayer(seed);
            double asFirst = GameRunner.play(start, ab, random).outcome().value(0);
            double asSecond = GameRunner.play(start, random, ab).outcome().value(1);
            assertTrue(asFirst > 0, "alpha-beta as first player, seed " + seed + ": " + asFirst);
            assertTrue(asSecond > 0, "alpha-beta as second player, seed " + seed + ": " + asSecond);
        }
    }
}
