package sternhalma.engine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import sternhalma.engine.RuleSet.BlockadeRule;
import sternhalma.engine.RuleSet.DrawHandling;
import sternhalma.engine.RuleSet.Repetition;
import sternhalma.engine.RuleSet.SuicideMoves;

/** Conformance tests 10 to 15 of docs/rules.md. */
class RulesTest {

    private static int cell(int q, int r) {
        int id = Geometry.id(q, r);
        assertTrue(id >= 0, "(" + q + "," + r + ") must be on the board");
        return id;
    }

    private static int[] goalOfPlayer0() {
        return Geometry.cornerCells(Corner.R_POS.ordinal());
    }

    /** Player 0's goal filled: {@code own} cells with player 0, the rest with player 1. */
    private static Position goalFilled(RuleSet rules, int own) {
        Position pos = Position.empty(rules);
        int[] goal = goalOfPlayer0();
        for (int i = 0; i < goal.length; i++) {
            pos.put(i < own ? 0 : 1, goal[i]);
        }
        return pos;
    }

    // ---- test 10: blockade rule ----

    @Test
    void definition1WinsWithAtLeastOneOwnPieceInTheGoal() {
        RuleSet rules = RuleSet.defaults(2);
        Position mixed = goalFilled(rules, 1);
        assertTrue(mixed.hasWon(0));
        assertEquals(0, mixed.outcome().winner());
        assertTrue(goalFilled(rules, 10).hasWon(0));
        Position onlyOpponent = goalFilled(rules, 0);
        assertFalse(onlyOpponent.hasWon(0));
        assertNull(onlyOpponent.outcome());
    }

    @Test
    void withoutTheBlockadeRuleOnlyAGoalOfOwnPiecesWins() {
        RuleSet rules = RuleSet.defaults(2).withBlockadeRule(BlockadeRule.NONE);
        assertFalse(goalFilled(rules, 1).hasWon(0));
        assertFalse(goalFilled(rules, 9).hasWon(0));
        assertFalse(goalFilled(rules, 0).hasWon(0));
        assertTrue(goalFilled(rules, 10).hasWon(0));
    }

    @Test
    void anIncompleteGoalIsNotAWin() {
        Position pos = Position.empty(RuleSet.defaults(2));
        int[] goal = goalOfPlayer0();
        for (int i = 0; i < 9; i++) {
            pos.put(0, goal[i]);
        }
        assertFalse(pos.hasWon(0));
    }

    // ---- test 11: suicide moves ----

    /** Player 1's goal is the top corner. It holds 9 pieces of player 1; (1,-5) is empty; player 0 stands at (1,-4). */
    private static Position suicideSetup(RuleSet rules) {
        Position pos = Position.empty(rules);
        int empty = cell(1, -5);
        for (int c : Geometry.cornerCells(Corner.R_NEG.ordinal())) {
            if (c != empty) {
                pos.put(1, c);
            }
        }
        return pos.put(0, 1, -4);
    }

    @Test
    void aMoveThatFillsTheOpponentsGoalIsIllegalUnderDefinition2() {
        int move = Move.of(cell(1, -4), cell(1, -5));
        Position illegal = suicideSetup(RuleSet.defaults(2));
        assertFalse(java.util.Arrays.stream(illegal.legalMoves()).anyMatch(m -> m == move));

        Position legal = suicideSetup(RuleSet.defaults(2).withSuicideMoves(SuicideMoves.LEGAL));
        assertTrue(java.util.Arrays.stream(legal.legalMoves()).anyMatch(m -> m == move));
        legal.make(move);
        Outcome outcome = legal.outcome();
        assertNotNull(outcome);
        assertEquals(1, outcome.winner(), "the opponent wins");
    }

    @Test
    void withoutTheBlockadeRuleTheSameMoveDoesNotHandOverTheWin() {
        RuleSet rules = RuleSet.defaults(2).withBlockadeRule(BlockadeRule.NONE);
        int move = Move.of(cell(1, -4), cell(1, -5));
        Position pos = suicideSetup(rules);
        assertTrue(java.util.Arrays.stream(pos.legalMoves()).anyMatch(m -> m == move));
        pos.make(move);
        assertNull(pos.outcome());
    }

    // ---- test 13: move limit and repetition ----

    @Test
    void defaultLimitsAre200PliesForTwoPlayersAnd300ForThree() {
        assertEquals(200, RuleSet.defaults(2).moveLimit());
        assertEquals(300, RuleSet.defaults(3).moveLimit());
    }

    @Test
    void theGameEndsAfterExactlyTheMoveLimit() {
        Position pos = Position.initial(RuleSet.defaults(2).withMoveLimit(10));
        java.util.Random random = new java.util.Random(7);
        for (int ply = 1; ply <= 10; ply++) {
            assertNull(pos.outcome(), "ply " + (ply - 1));
            int[] moves = pos.legalMoves();
            pos.make(moves[random.nextInt(moves.length)]);
        }
        assertEquals(10, pos.ply());
        assertEquals(Outcome.Reason.MOVE_LIMIT, pos.outcome().reason());
        assertFalse(pos.outcome().hasWinner());
    }

    /** Player 0 owns 9 goal cells and stands next to the last one, (-1,5), on (0,4). */
    private static Position oneStepFromWinning(RuleSet rules) {
        Position pos = Position.empty(rules);
        for (int c : goalOfPlayer0()) {
            if (c != cell(-1, 5)) {
                pos.put(0, c);
            }
        }
        return pos.put(0, 0, 4).put(1, 4, -8);
    }

    @Test
    void aWinOnTheLastAllowedPlyCounts() {
        Position pos = oneStepFromWinning(RuleSet.defaults(2).withMoveLimit(1));
        int move = Move.of(cell(0, 4), cell(-1, 5));
        assertTrue(java.util.Arrays.stream(pos.legalMoves()).anyMatch(m -> m == move));
        pos.make(move);
        assertEquals(1, pos.ply());
        assertEquals(Outcome.Reason.WIN, pos.outcome().reason());
        assertEquals(0, pos.outcome().winner());
    }

    private static int stepMove(Position pos) {
        for (int m : pos.legalMoves()) {
            for (int d = 0; d < Geometry.DIRECTIONS; d++) {
                if (Geometry.neighbor(Move.from(m), d) == Move.to(m)) {
                    return m;
                }
            }
        }
        throw new AssertionError("no step move");
    }

    private static void shuffleThereAndBack(Position pos) {
        int a = stepMove(pos);
        pos.make(a);
        int b = stepMove(pos);
        pos.make(b);
        pos.make(Move.of(Move.to(a), Move.from(a)));
        pos.make(Move.of(Move.to(b), Move.from(b)));
    }

    @Test
    void theFirstRepeatedPositionEndsTheGameOnlyWithRepetitionIsDraw() {
        Position draw = Position.initial(RuleSet.defaults(2).withRepetition(Repetition.REPETITION_IS_DRAW));
        shuffleThereAndBack(draw);
        assertEquals(4, draw.ply());
        assertEquals(Outcome.Reason.REPETITION, draw.outcome().reason());

        Position limited = Position.initial(RuleSet.defaults(2));
        shuffleThereAndBack(limited);
        assertNull(limited.outcome(), "with MOVE_LIMIT a repeated position is not an end");
    }

    @Test
    void theMoveLimitStillAppliesWithRepetitionIsDraw() {
        RuleSet rules = RuleSet.defaults(2).withRepetition(Repetition.REPETITION_IS_DRAW).withMoveLimit(3);
        Position pos = Position.initial(rules);
        java.util.Random random = new java.util.Random(3);
        for (int i = 0; i < 3; i++) {
            int[] moves = pos.legalMoves();
            pos.make(moves[random.nextInt(moves.length)]);
        }
        assertEquals(Outcome.Reason.MOVE_LIMIT, pos.outcome().reason());
    }

    // ---- test 14: scoring a game without a winner ----

    @Test
    void scoreByDistanceGivesTheBetterScoreToTheNearerPlayer() {
        // player 0 steps into its goal; player 1 stays at the far end of its start corner
        Position pos = Position.empty(RuleSet.defaults(2).withMoveLimit(1)).put(0, 0, 4).put(1, -4, 8);
        pos.make(Move.of(cell(0, 4), cell(-1, 5)));
        Outcome outcome = pos.outcome();
        assertEquals(Outcome.Reason.MOVE_LIMIT, outcome.reason());
        assertTrue(outcome.value(0) > 0 && outcome.value(1) < 0, outcome.toString());
        assertEquals(0.0, outcome.value(0) + outcome.value(1), 1e-12);
        assertTrue(Math.abs(outcome.value(0)) <= 1.0);
    }

    @Test
    void equalDistancesGiveEqualScores() {
        Position pos = Position.initial(RuleSet.defaults(2).withMoveLimit(2));
        int m = stepMove(pos);
        pos.make(m);
        pos.make(Move.of(Geometry.playerFlip(Move.from(m)), Geometry.playerFlip(Move.to(m))));
        assertEquals(pos.distanceSum(0), pos.distanceSum(1));
        assertArrayEquals(new double[] {0, 0}, pos.outcome().values(), 1e-12);
    }

    @Test
    void drawHandlingDrawGivesEveryPlayerTheDrawValue() {
        Position pos = Position.empty(RuleSet.defaults(2).withDrawHandling(DrawHandling.DRAW).withMoveLimit(1))
                .put(0, 0, 4).put(1, -4, 8);
        pos.make(Move.of(cell(0, 4), cell(-1, 5)));
        assertArrayEquals(new double[] {0, 0}, pos.outcome().values(), 0.0);
    }

    // ---- test 15: three players (the first-win case is in ReviewRegressionTest) ----

    @Test
    void fullRankingAndBlockingStatesAreRejectedAsNotImplemented() {
        RuleSet d = RuleSet.defaults(3);
        assertThrows(UnsupportedOperationException.class, () -> new RuleSet(3, 300, d.leaveGoal(), d.blockadeRule(),
                d.suicideMoves(), d.blockingStates(), d.repetition(), d.drawHandling(), RuleSet.Ranking.FULL_RANKING));
        assertThrows(UnsupportedOperationException.class, () -> new RuleSet(2, 200, d.leaveGoal(), d.blockadeRule(),
                d.suicideMoves(), RuleSet.BlockingStates.ILLEGAL, d.repetition(), d.drawHandling(), d.ranking()));
        assertThrows(IllegalArgumentException.class, () -> RuleSet.defaults(4));
        assertThrows(IllegalArgumentException.class, () -> RuleSet.defaults(2).withMoveLimit(0));
    }
}
