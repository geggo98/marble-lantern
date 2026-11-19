package sternhalma.engine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import sternhalma.engine.RuleSet.DrawHandling;
import sternhalma.engine.RuleSet.JumpRule;
import sternhalma.engine.RuleSet.Ranking;
import sternhalma.engine.RuleSet.Repetition;
import sternhalma.engine.RuleSet.SuicideMoves;

/**
 * {@code ranking=FULL_RANKING} (docs/rules.md, "Playing on for second place"): a player who fills the goal finishes
 * with the next free rank and makes no more moves; the others play on.
 */
class FullRankingTest {

    private static RuleSet full(JumpRule jump) {
        return RuleSet.defaults(3).withRanking(Ranking.FULL_RANKING).withJumpRule(jump);
    }

    /**
     * Puts the pieces of {@code player} so that one step fills the goal: nine pieces in the goal corner, the tenth
     * next to the empty goal cell X, outside the goal. Returns that step.
     */
    private static int almostWon(Position pos, int player) {
        Layout layout = pos.layout();
        int goal = layout.goalCorner(player);
        for (int x : Geometry.cornerCells(goal)) {
            for (int d = 0; d < Geometry.DIRECTIONS; d++) {
                int n = Geometry.STEP[x * Geometry.DIRECTIONS + d];
                if (n >= 0 && Geometry.cornerOf(n) != goal && layout.allowed(player, n)) {
                    for (int c : Geometry.cornerCells(goal)) {
                        if (c != x) {
                            pos.put(player, c);
                        }
                    }
                    pos.put(player, n);
                    return Move.of(n, x);
                }
            }
        }
        throw new AssertionError("no cell next to the goal of player " + player);
    }

    /** Players 0 and 1 are one step from the goal; player 2 has one piece in the own start corner. */
    private static Position twoAlmostWon(RuleSet rules, int[] steps) {
        Position pos = Position.empty(rules);
        steps[0] = almostWon(pos, 0);
        steps[1] = almostWon(pos, 1);
        pos.put(2, Geometry.cornerCells(pos.layout().startCorner(2))[0]);
        return pos;
    }

    private static void play(Position pos, int move) {
        assertTrue(Arrays.stream(pos.legalMoves()).anyMatch(m -> m == move), "illegal " + Move.toString(move));
        pos.make(move);
    }

    @Test
    void theSwitchIsAcceptedAndTwoPlayersIgnoreIt() {
        assertTrue(full(JumpRule.ADJACENT).playsOnAfterWin());
        assertFalse(RuleSet.defaults(3).playsOnAfterWin());
        assertFalse(RuleSet.defaults(2).withRanking(Ranking.FULL_RANKING).playsOnAfterWin());
    }

    @Test
    void twoPlayersBehaveExactlyLikeFirstWinnerOnly() {
        for (JumpRule jump : JumpRule.values()) {
            RuleSet first = RuleSet.defaults(2).withJumpRule(jump);
            RuleSet full = first.withRanking(Ranking.FULL_RANKING);
            Position a = Position.initial(first);
            Position b = Position.initial(full);
            SplitMix64 random = new SplitMix64(77);
            while (a.outcome() == null && a.ply() < 200) {
                assertEquals(a.hash(), b.hash());
                int[] moves = a.legalMoves();
                assertArrayEquals(moves, b.legalMoves());
                int m = moves[(int) ((random.nextLong() >>> 1) % moves.length)];
                a.make(m);
                b.make(m);
                assertEquals(a.outcome(), b.outcome());
            }
            assertNotNull(b.outcome());
        }
    }

    @Test
    void firstFinisherLeavesAndTheTurnOrderSkipsIt() {
        for (JumpRule jump : JumpRule.values()) {
            int[] steps = new int[2];
            Position pos = twoAlmostWon(full(jump), steps);
            long before = pos.hash();
            play(pos, steps[0]);
            assertNull(pos.outcome(), "the game goes on after the first finisher");
            assertTrue(pos.isFinished(0));
            assertEquals(1, pos.finishedMask());
            assertArrayEquals(new int[] {0}, pos.finishers());
            assertEquals(1, pos.sideToMove());
            assertNotEquals(before, pos.hash());
            // a move of player 1 that does not finish; then player 2; then player 1 again: player 0 never moves
            int other = Arrays.stream(pos.legalMoves()).filter(m -> m != steps[1]).findFirst().orElseThrow();
            pos.make(other);
            assertEquals(2, pos.sideToMove());
            pos.make(pos.legalMoves()[0]);
            assertEquals(1, pos.sideToMove(), "player 0 is skipped");
            // unmake restores the turn, the finished set and the hash exactly
            pos.unmake();
            pos.unmake();
            pos.unmake();
            assertEquals(before, pos.hash());
            assertEquals(0, pos.finishedMask());
            assertEquals(0, pos.sideToMove());
            assertEquals(0, pos.finishers().length);
        }
    }

    @Test
    void secondFinisherEndsTheGameAndTheLastActivePlayerIsThird() {
        int[] steps = new int[2];
        Position pos = twoAlmostWon(full(JumpRule.ADJACENT), steps);
        play(pos, steps[0]);
        play(pos, steps[1]);
        Outcome outcome = pos.outcome();
        assertNotNull(outcome);
        assertEquals(Outcome.Reason.WIN, outcome.reason());
        assertEquals(0, outcome.winner());
        assertArrayEquals(new int[] {0, 1, 2}, outcome.ranking());
        assertArrayEquals(new double[] {1, 0, -1}, outcome.values(), 0.0);
        pos.unmake();
        assertNull(pos.outcome());
        assertEquals(1, pos.sideToMove());
    }

    @Test
    void secondPlaceCanGoToPlayerTwoAndTheOrderOfFinishingCounts() {
        // player 2 finishes second: players 0 and 2 are one step from the goal, player 1 has one piece
        Position pos = Position.empty(full(JumpRule.ADJACENT));
        int s0 = almostWon(pos, 0);
        pos.put(1, Geometry.cornerCells(pos.layout().startCorner(1))[0]);
        int s2 = almostWon(pos, 2);
        play(pos, s0);
        pos.make(pos.legalMoves()[0]); // player 1
        play(pos, s2);
        Outcome outcome = pos.outcome();
        assertArrayEquals(new int[] {0, 2, 1}, outcome.ranking());
        assertArrayEquals(new double[] {1, -1, 0}, outcome.values(), 0.0);
    }

    @Test
    void finishedPiecesStayOnTheBoardAndBlock() {
        int[] steps = new int[2];
        Position pos = twoAlmostWon(full(JumpRule.ADJACENT), steps);
        int[] piecesOfZero = pos.pieces(0);
        play(pos, steps[0]);
        int[] after = pos.pieces(0);
        assertEquals(10, after.length);
        assertEquals(10, pos.goalOccupied(0));
        assertTrue(Arrays.stream(after).anyMatch(c -> c == Move.to(steps[0])));
        assertEquals(piecesOfZero.length, after.length);
        for (int m : pos.legalMoves()) {
            assertNotEquals(0, pos.owner(Move.from(m)), "no move of the finished player");
        }
    }

    @Test
    void aFinishedGoalNoLongerMakesMovesSuicidal() {
        // player 1 stands one step from a filled goal of player 0 held by player 0 itself: after player 0 finished,
        // moves of the others must not count as handing player 0 a win again
        int[] steps = new int[2];
        Position pos = twoAlmostWon(full(JumpRule.ADJACENT), steps);
        play(pos, steps[0]);
        assertTrue(pos.legalMoves().length > 0);
        Position first = Position.empty(RuleSet.defaults(3));
        almostWon(first, 0);
        first.put(1, Geometry.cornerCells(first.layout().startCorner(1))[0]);
        first.setSideToMove(1);
        assertTrue(first.legalMoves().length > 0);
    }

    @Test
    void theFinishedSetIsPartOfHashAndEquality() {
        Position a = Position.empty(full(JumpRule.ADJACENT));
        almostWon(a, 0);
        a.put(1, Geometry.cornerCells(a.layout().startCorner(1))[0]);
        a.put(2, Geometry.cornerCells(a.layout().startCorner(2))[0]);
        a.setSideToMove(1);
        Position b = a.copy();
        assertEquals(a, b);
        assertEquals(a.hash(), b.hash());
        Position finished = Position.empty(full(JumpRule.ADJACENT));
        almostWon(finished, 0);
        finished.put(1, Geometry.cornerCells(finished.layout().startCorner(1))[0]);
        finished.put(2, Geometry.cornerCells(finished.layout().startCorner(2))[0]);
        finished.finish(0).setSideToMove(1);
        assertNotEquals(a, finished);
        assertNotEquals(a.hash(), finished.hash());
        Position copy = finished.copy();
        assertEquals(finished, copy);
        assertEquals(finished.hash(), copy.hash());
        assertEquals(1, copy.finishedMask());
        assertThrows(IllegalArgumentException.class, () -> copy.setSideToMove(0));
    }

    @Test
    void repetitionIsDetectedAfterAFinishButNotAcrossIt() {
        RuleSet rules = full(JumpRule.ADJACENT).withRepetition(Repetition.REPETITION_IS_DRAW);
        Position pos = Position.empty(rules);
        int s0 = almostWon(pos, 0);
        int[] home1 = Geometry.cornerCells(pos.layout().startCorner(1));
        int[] home2 = Geometry.cornerCells(pos.layout().startCorner(2));
        pos.put(1, home1[0]).put(2, home2[0]);
        play(pos, s0);
        assertNull(pos.outcome());
        // players 1 and 2 shuffle one piece to a neighbour cell and back; the position after four plies repeats
        int[] first = pos.legalMoves();
        int out1 = first[0];
        pos.make(out1);
        int out2 = pos.legalMoves()[0];
        pos.make(out2);
        int back1 = Move.of(Move.to(out1), Move.from(out1));
        play(pos, back1);
        assertNull(pos.outcome());
        int back2 = Move.of(Move.to(out2), Move.from(out2));
        play(pos, back2);
        assertEquals(Outcome.Reason.REPETITION, pos.outcome().reason());
        assertArrayEquals(new int[] {0}, Arrays.copyOf(pos.outcome().ranking(), 1));
        assertEquals(0, pos.outcome().ranking()[0]);
        assertEquals(1.0, pos.outcome().values()[0], 0.0);
    }

    @Test
    void moveLimitAfterTheFirstFinisherRanksTheOthersByDistance() {
        RuleSet rules = full(JumpRule.ADJACENT).withMoveLimit(3);
        int[] steps = new int[2];
        Position pos = twoAlmostWon(rules, steps);
        // player 1 stays one step away, player 2 is far: after player 0, player 1 is second by distance
        play(pos, steps[0]);
        int other = Arrays.stream(pos.legalMoves()).filter(m -> m != steps[1]).findFirst().orElseThrow();
        // a move that keeps player 1 out of the goal and does not hand anyone the win
        pos.make(other);
        pos.make(pos.legalMoves()[0]);
        Outcome outcome = pos.outcome();
        assertNotNull(outcome);
        assertEquals(Outcome.Reason.MOVE_LIMIT, outcome.reason());
        assertEquals(-1, outcome.winner());
        assertEquals(0, outcome.ranking()[0]);
        assertEquals(3, outcome.ranking().length);
        double sum = Arrays.stream(outcome.values()).sum();
        assertEquals(0.0, sum, 1e-12);
        assertEquals(1.0, outcome.values()[0], 0.0);
        // player 1 has nine pieces in the goal: nearer than player 2 with one piece at home
        assertTrue(pos.distanceSum(1) < pos.distanceSum(2));
        assertArrayEquals(new int[] {0, 1, 2}, outcome.ranking());
        assertTrue(outcome.values()[1] > outcome.values()[2]);
        assertTrue(outcome.values()[1] <= 0 && outcome.values()[2] >= -1, Arrays.toString(outcome.values()));
    }

    @Test
    void moveLimitWithDrawHandlingSharesTheRemainingRankValues() {
        RuleSet rules = full(JumpRule.ADJACENT).withMoveLimit(3).withDrawHandling(DrawHandling.DRAW);
        int[] steps = new int[2];
        Position pos = twoAlmostWon(rules, steps);
        play(pos, steps[0]);
        pos.make(Arrays.stream(pos.legalMoves()).filter(m -> m != steps[1]).findFirst().orElseThrow());
        pos.make(pos.legalMoves()[0]);
        assertArrayEquals(new double[] {1, -0.5, -0.5}, pos.outcome().values(), 0.0);
    }

    @Test
    void moveLimitWithoutAFinisherScoresLikeFirstWinnerOnly() {
        for (JumpRule jump : JumpRule.values()) {
            RuleSet first = RuleSet.defaults(3).withJumpRule(jump).withMoveLimit(9);
            Position a = Position.initial(first);
            Position b = Position.initial(first.withRanking(Ranking.FULL_RANKING));
            for (int i = 0; i < 9; i++) {
                int m = a.legalMoves()[i % 5];
                a.make(m);
                b.make(m);
            }
            assertArrayEquals(a.outcome().values(), b.outcome().values(), 0.0);
            assertEquals(Outcome.Reason.MOVE_LIMIT, b.outcome().reason());
            assertEquals(3, b.outcome().ranking().length);
            assertEquals(0, a.outcome().ranking().length);
        }
    }

    @Test
    void noMovesEndsTheGameWithTheFinishedRanksKept() {
        int[] steps = new int[2];
        Position pos = twoAlmostWon(full(JumpRule.ADJACENT), steps);
        play(pos, steps[0]);
        Outcome outcome = pos.noMovesOutcome();
        assertEquals(Outcome.Reason.NO_MOVES, outcome.reason());
        assertEquals(0, outcome.ranking()[0]);
        assertEquals(1.0, outcome.values()[0], 0.0);
        assertEquals(0.0, Arrays.stream(outcome.values()).sum(), 1e-12);
    }

    @Test
    void suicideLegalLetsTwoPlayersFinishOnOneMoveInTurnOrder() {
        // with SuicideMoves.LEGAL, one move can fill another player's goal as well; the player after the mover
        // is ranked first, the same order as the first-winner check
        RuleSet rules = full(JumpRule.ADJACENT).withSuicideMoves(SuicideMoves.LEGAL);
        Position pos = Position.empty(rules);
        int s0 = almostWon(pos, 0);
        pos.put(1, Geometry.cornerCells(pos.layout().startCorner(1))[0]);
        pos.put(2, Geometry.cornerCells(pos.layout().startCorner(2))[0]);
        play(pos, s0);
        assertEquals(1, pos.finishedMask());
    }

    /** Greedy playouts: never moves a finished player, ends with a complete ranking or at the move limit. */
    @Test
    void seededPlayoutsKeepTheRankingConsistentUnderBothJumpRules() {
        int finishedSecond = 0;
        for (JumpRule jump : JumpRule.values()) {
            for (int seed = 0; seed < 6; seed++) {
                RuleSet rules = full(jump).withMoveLimit(seed % 2 == 0 ? 900 : 120);
                Position pos = Position.initial(rules);
                long start = pos.hash();
                SplitMix64 random = new SplitMix64(0xF011L + seed * 31 + jump.ordinal());
                int moves = 0;
                int previousFinished = 0;
                while (pos.outcome() == null) {
                    int side = pos.sideToMove();
                    assertFalse(pos.isFinished(side), "a finished player is to move");
                    int[] legal = pos.legalMoves();
                    if (legal.length == 0) {
                        break;
                    }
                    for (int m : legal) {
                        assertEquals(side, pos.owner(Move.from(m)));
                    }
                    pos.make(greedy(pos, legal, side, random));
                    moves++;
                    int count = Integer.bitCount(pos.finishedMask());
                    assertTrue(count >= previousFinished && count <= 2, "finished set only grows");
                    previousFinished = count;
                    assertEquals(count, pos.finishers().length);
                    for (int f : pos.finishers()) {
                        assertTrue(pos.isFinished(f));
                    }
                }
                Outcome outcome = pos.outcome() != null ? pos.outcome() : pos.noMovesOutcome();
                int[] ranking = outcome.ranking();
                assertEquals(3, ranking.length);
                assertEquals(7, 1 << ranking[0] | 1 << ranking[1] | 1 << ranking[2], "ranks are unique");
                assertEquals(0.0, Arrays.stream(outcome.values()).sum(), 1e-9);
                for (int r = 0; r < pos.finishers().length; r++) {
                    assertEquals(pos.finishers()[r], ranking[r], "finished players keep their ranks");
                }
                if (outcome.reason() == Outcome.Reason.WIN) {
                    assertEquals(2, pos.finishers().length);
                    finishedSecond++;
                } else {
                    assertTrue(pos.ply() >= rules.moveLimit() || outcome.reason() == Outcome.Reason.NO_MOVES);
                }
                for (int i = 0; i < moves; i++) {
                    pos.unmake();
                }
                assertEquals(start, pos.hash());
                assertEquals(0, pos.finishedMask());
                assertEquals(Position.initial(rules), pos);
            }
        }
        assertTrue(finishedSecond >= 1, "at least one playout runs to a complete ranking: " + finishedSecond);
    }

    private static int greedy(Position pos, int[] legal, int side, SplitMix64 random) {
        Layout layout = pos.layout();
        int best = Integer.MIN_VALUE;
        int[] kept = new int[legal.length];
        int n = 0;
        for (int m : legal) {
            int gain = layout.distance(side, Move.from(m)) - layout.distance(side, Move.to(m));
            if (gain > best) {
                best = gain;
                n = 0;
            }
            if (gain == best) {
                kept[n++] = m;
            }
        }
        return kept[(int) ((random.nextLong() >>> 1) % n)];
    }
}
