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
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

import sternhalma.engine.RuleSet.LeaveGoal;
import sternhalma.engine.RuleSet.Repetition;
import sternhalma.engine.RuleSet.SuicideMoves;

/**
 * Tests added after an independent adversarial review of the engine. Each test kills a mutant that the
 * first test suite let survive (names of the mutants are in the review notes, not needed here).
 */
class ReviewRegressionTest {

    private static int cell(int q, int r) {
        int id = Geometry.id(q, r);
        assertTrue(id >= 0, "(" + q + "," + r + ") must be on the board");
        return id;
    }

    private static boolean hasMove(Position pos, int from, int to) {
        int move = Move.of(from, to);
        return Arrays.stream(pos.legalMoves()).anyMatch(m -> m == move);
    }

    /** Plays a step after checking that it is legal. */
    private static void play(Position pos, int fromQ, int fromR, int toQ, int toR) {
        int from = cell(fromQ, fromR);
        int to = cell(toQ, toR);
        assertTrue(hasMove(pos, from, to), "move (" + fromQ + "," + fromR + ")->(" + toQ + "," + toR + ") must be legal");
        pos.make(Move.of(from, to));
    }

    // ---- setup and misuse ----

    @Test
    void unmakeAtPlyZeroFailsCleanlyAndLeavesThePositionUsable() {
        Position pos = Position.initial(RuleSet.defaults(2));
        assertThrows(IllegalStateException.class, pos::unmake);
        assertEquals(0, pos.ply());
        pos.make(pos.legalMoves()[0]);
        assertEquals(1, pos.ply());
    }

    @Test
    void setupIsRejectedOnceAMoveHasBeenPlayed() {
        Position pos = Position.initial(RuleSet.defaults(2));
        pos.make(pos.legalMoves()[0]);
        assertThrows(IllegalStateException.class, () -> pos.put(0, cell(0, 0)));
        assertThrows(IllegalStateException.class, () -> pos.setSideToMove(0));
        long hash = pos.hash();
        pos.unmake();
        assertEquals(pos.transformed(c -> c, false).hash(), pos.hash());
        assertNotEquals(hash, pos.hash());
    }

    @Test
    void putAndSetSideToMoveRejectNonexistentPlayersAndCellsAPlayerMayNotEnter() {
        RuleSet rules = RuleSet.defaults(2);
        assertThrows(IllegalArgumentException.class, () -> Position.empty(rules).put(2, cell(0, 0)));
        assertThrows(IllegalArgumentException.class, () -> Position.empty(rules).put(-1, cell(0, 0)));
        assertThrows(IllegalArgumentException.class, () -> Position.empty(rules).setSideToMove(2));
        assertThrows(IllegalArgumentException.class, () -> Position.empty(rules).put(0, 121));
        int sideCorner = Geometry.cornerCells(Corner.Q_POS.ordinal())[0];
        assertThrows(IllegalArgumentException.class, () -> Position.empty(rules).put(0, sideCorner));
        assertThrows(IllegalStateException.class, () -> Position.empty(rules).put(0, 0, 0).put(1, 0, 0));
    }

    @Test
    void everyPieceOnTheBoardHasANonNegativeDistance() {
        Position pos = Position.initial(RuleSet.defaults(3));
        for (int p = 0; p < 3; p++) {
            for (int c : pos.pieces(p)) {
                assertTrue(pos.layout().distance(p, c) >= 0);
            }
        }
    }

    // ---- play order and copies ----

    @Test
    void threePlayersMoveInClockwiseOrderAndUnmakeRestoresIt() {
        Position pos = Position.initial(RuleSet.defaults(3));
        assertEquals(0, pos.sideToMove());
        int[] expectedNext = {1, 2, 0};
        for (int i = 0; i < 3; i++) {
            int mover = pos.sideToMove();
            int move = pos.legalMoves()[0];
            assertEquals(mover, pos.owner(Move.from(move)), "the moved piece belongs to the side to move");
            pos.make(move);
            assertEquals(expectedNext[i], pos.sideToMove());
        }
        for (int i = 2; i >= 0; i--) {
            pos.unmake();
            assertEquals(i, pos.sideToMove());
        }
    }

    @Test
    void aCopyBehavesLikeTheOriginal() {
        Position pos = Position.empty(RuleSet.defaults(2));
        for (int c : Geometry.cornerCells(pos.layout().goalCorner(0))) {
            pos.put(0, c);
        }
        Position copy = pos.copy();
        assertTrue(copy.hasWon(0));
        assertEquals(pos.outcome(), copy.outcome());

        Random random = new Random(11);
        Position walk = Position.initial(RuleSet.defaults(2).withMoveLimit(400));
        for (int ply = 0; ply < 60; ply++) {
            Position c = walk.copy();
            assertArrayEquals(walk.legalMoves(), c.legalMoves());
            for (int q = 0; q < 2; q++) {
                assertEquals(walk.hasWon(q), c.hasWon(q));
            }
            int[] moves = walk.legalMoves();
            walk.make(moves[random.nextInt(moves.length)]);
        }
    }

    // ---- suicide rule ----

    /** Player 1's goal is the top corner; it holds 9 pieces of player 1 and the cell (1,-5) is empty. */
    private static Position topCornerNearlyFull(RuleSet rules, boolean pieceOnOneMinus4) {
        Position pos = Position.empty(rules);
        for (int c : Geometry.cornerCells(Corner.R_NEG.ordinal())) {
            if (c != cell(1, -5)) {
                pos.put(1, c);
            }
        }
        if (pieceOnOneMinus4) {
            pos.put(1, 1, -4);
        }
        return pos;
    }

    @Test
    void aJumpThatFillsTheOpponentsGoalIsIllegalUnderDefinition2() {
        // player 0 on (1,-3) jumps over the player-1 piece on (1,-4) and lands on the last free goal cell (1,-5)
        int from = cell(1, -3);
        int to = cell(1, -5);
        Position illegal = topCornerNearlyFull(RuleSet.defaults(2), true).put(0, from);
        assertFalse(hasMove(illegal, from, to));

        Position legal = topCornerNearlyFull(RuleSet.defaults(2).withSuicideMoves(SuicideMoves.LEGAL), true).put(0, from);
        assertTrue(hasMove(legal, from, to));
        legal.make(Move.of(from, to));
        assertEquals(1, legal.outcome().winner());
    }

    @Test
    void thePieceThatLeavesTheOpponentsGoalIsCountedWhenCheckingTheSuicideRule() {
        // player 0 stands inside player 1's goal on (1,-5); (2,-5) is empty; 8 pieces of player 1 fill the rest.
        // Moving (1,-5)->(2,-5) keeps the corner at 9 of 10 cells, so it hands over no win.
        Position pos = Position.empty(RuleSet.defaults(2));
        for (int c : Geometry.cornerCells(Corner.R_NEG.ordinal())) {
            if (c != cell(1, -5) && c != cell(2, -5)) {
                pos.put(1, c);
            }
        }
        pos.put(0, 1, -5).put(0, 0, 0);
        assertTrue(hasMove(pos, cell(1, -5), cell(2, -5)));
    }

    // ---- leave goal ----

    @Test
    void forbiddenLeaveGoalAlsoBlocksChainsThatEndInTheStartCorner() {
        // (-1,5) over (-1,4), (-1,2), (-1,0), (-1,-2) to (-1,-3), then over (0,-4) to (1,-5) in player 0's start corner
        int from = cell(-1, 5);
        int to = cell(1, -5);
        Position forbidden = Position.empty(RuleSet.defaults(2));
        forbidden.put(0, -1, 5).put(1, -1, 4).put(1, -1, 2).put(1, -1, 0).put(1, -1, -2).put(1, 0, -4);
        assertEquals(forbidden.layout().startCorner(0), Geometry.cornerOf(to));
        assertFalse(hasMove(forbidden, from, to));

        Position allowed = Position.empty(RuleSet.defaults(2).withLeaveGoal(LeaveGoal.ALLOWED));
        allowed.put(0, -1, 5).put(1, -1, 4).put(1, -1, 2).put(1, -1, 0).put(1, -1, -2).put(1, 0, -4);
        assertTrue(hasMove(allowed, from, to));
        assertEquals(ReferenceGenerator.legalMoves(allowed),
                Arrays.stream(allowed.legalMoves()).boxed().map(m -> Move.from(m) * 1000 + Move.to(m)).collect(java.util.stream.Collectors.toSet()));
    }

    // ---- repetition ----

    @Test
    void theSameCellsWithTheOtherSideToMoveIsNoRepetition() {
        Position pos = Position.empty(RuleSet.defaults(2).withRepetition(Repetition.REPETITION_IS_DRAW))
                .put(0, 0, 0).put(1, 0, 4);
        play(pos, 0, 0, 1, 0);
        play(pos, 0, 4, 0, 3);
        play(pos, 1, 0, 1, -1);
        play(pos, 0, 3, 0, 4);
        play(pos, 1, -1, 0, 0);
        assertEquals(5, pos.ply());
        assertEquals(1, pos.sideToMove());
        assertNull(pos.outcome(), "same cells as at the start, but the other player is to move");
    }

    @Test
    void swappingTheColoursOfTwoPiecesIsNoRepetitionForTwoPlayers() {
        Position pos = Position.empty(RuleSet.defaults(2).withRepetition(Repetition.REPETITION_IS_DRAW))
                .put(0, 0, 0).put(1, 1, -1);
        play(pos, 0, 0, 1, 0);
        play(pos, 1, -1, 0, -1);
        play(pos, 1, 0, 1, -1);
        play(pos, 0, -1, 0, 0);
        assertEquals(0, pos.sideToMove());
        assertEquals(0, pos.owner(cell(1, -1)));
        assertEquals(1, pos.owner(cell(0, 0)));
        assertNull(pos.outcome());
    }

    @Test
    void swappingTheColoursOfPlayersZeroAndTwoIsNoRepetitionForThreePlayers() {
        Position pos = Position.empty(RuleSet.defaults(3).withRepetition(Repetition.REPETITION_IS_DRAW))
                .put(0, 0, 0).put(1, -3, 0).put(2, 1, -1);
        play(pos, 0, 0, 1, 0);
        play(pos, -3, 0, -2, 0);
        play(pos, 1, -1, 0, -1);
        play(pos, 1, 0, 1, -1);
        play(pos, -2, 0, -3, 0);
        play(pos, 0, -1, 0, 0);
        assertEquals(0, pos.sideToMove());
        assertNull(pos.outcome());
    }

    @Test
    void repetitionAfterAnOddNumberOfPliesIsDetectedForThreePlayers() {
        int[][][] triangles = {
            {{0, 0}, {1, 0}, {1, -1}},
            {{-3, 0}, {-2, 0}, {-2, -1}},
            {{1, 2}, {2, 2}, {2, 1}},
        };
        Position pos = Position.empty(RuleSet.defaults(3).withRepetition(Repetition.REPETITION_IS_DRAW))
                .put(0, 0, 0).put(1, -3, 0).put(2, 1, 2);
        for (int step = 0; step < 3; step++) {
            for (int player = 0; player < 3; player++) {
                assertNull(pos.outcome(), "ply " + pos.ply());
                int[] from = triangles[player][step];
                int[] to = triangles[player][(step + 1) % 3];
                play(pos, from[0], from[1], to[0], to[1]);
            }
        }
        assertEquals(9, pos.ply());
        assertEquals(Outcome.Reason.REPETITION, pos.outcome().reason());
    }

    // ---- the default limit of 200 plies ----

    @Test
    void aRandomGameEndsAfterExactlyTheDefaultLimitOf200Plies() {
        Position pos = Position.initial(RuleSet.defaults(2));
        Random random = new Random(7);
        for (int ply = 0; ply < 200; ply++) {
            assertNull(pos.outcome(), "ply " + ply);
            int[] moves = pos.legalMoves();
            pos.make(moves[random.nextInt(moves.length)]);
        }
        assertEquals(200, pos.ply());
        assertEquals(Outcome.Reason.MOVE_LIMIT, pos.outcome().reason());
    }

    @Test
    void aWinOnPly200CountsUnderTheDefaultLimit() {
        // Player 1 owns 9 cells of the top corner and cycles a tenth piece through a triangle; player 0 cycles
        // a lone piece inside its own goal. Player 1 makes plies 1, 3, ..., 199 (the 100th is the winning step).
        Position pos = Position.empty(RuleSet.defaults(2));
        for (int c : Geometry.cornerCells(Corner.R_NEG.ordinal())) {
            if (c != cell(1, -5)) {
                pos.put(1, c);
            }
        }
        pos.put(1, 1, -4).put(0, -4, 8);
        int[][] player0 = {{-4, 8}, {-4, 7}, {-3, 7}};
        int[][] player1 = {{1, -4}, {1, -3}, {0, -3}};
        int own0 = 0;
        int own1 = 0;
        for (int ply = 0; ply < 199; ply++) {
            assertNull(pos.outcome(), "ply " + ply);
            if (ply % 2 == 0) {
                int[] from = player0[own0 % 3];
                int[] to = player0[(own0 + 1) % 3];
                play(pos, from[0], from[1], to[0], to[1]);
                own0++;
            } else {
                int[] from = player1[own1 % 3];
                int[] to = player1[(own1 + 1) % 3];
                play(pos, from[0], from[1], to[0], to[1]);
                own1++;
            }
        }
        assertEquals(199, pos.ply());
        assertEquals(99, own1);
        assertNull(pos.outcome());
        play(pos, 1, -4, 1, -5);
        assertEquals(200, pos.ply());
        assertEquals(Outcome.Reason.WIN, pos.outcome().reason());
        assertEquals(1, pos.outcome().winner());
    }

    @Test
    void theDefaultLimitStillAppliesWithRepetitionIsDraw() {
        Position pos = Position.initial(RuleSet.defaults(2).withRepetition(Repetition.REPETITION_IS_DRAW));
        Random random = new Random(5);
        Set<Long> seen = new HashSet<>();
        seen.add(pos.hash());
        for (int ply = 0; ply < 200; ply++) {
            assertNull(pos.outcome(), "ply " + ply);
            int[] moves = pos.legalMoves();
            int chosen = -1;
            for (int attempt = 0; attempt < 50 && chosen < 0; attempt++) {
                int m = moves[random.nextInt(moves.length)];
                pos.make(m);
                if (seen.add(pos.hash())) {
                    chosen = m;
                } else {
                    pos.unmake();
                }
            }
            assertTrue(chosen >= 0, "no non-repeating move found at ply " + ply);
        }
        assertEquals(200, pos.ply());
        Outcome outcome = pos.outcome();
        assertNotNull(outcome);
        assertEquals(Outcome.Reason.MOVE_LIMIT, outcome.reason());
    }

    // ---- three players ----

    @Test
    void threePlayerGameEndsAtTheFirstWinFromAReachablePosition() {
        Position pos = Position.empty(RuleSet.defaults(3));
        for (int c : Geometry.cornerCells(Corner.R_POS.ordinal())) {
            if (c != cell(-1, 5)) {
                pos.put(0, c);
            }
        }
        pos.put(0, 0, 4).put(1, 4, -4).put(2, -4, 0);
        assertTrue(pos.layout().allowed(1, cell(4, -4)) && pos.layout().allowed(2, cell(-4, 0)));
        play(pos, 0, 4, -1, 5);
        Outcome outcome = pos.outcome();
        assertEquals(Outcome.Reason.WIN, outcome.reason());
        assertArrayEquals(new double[] {1.0, -0.5, -0.5}, outcome.values(), 1e-12);
    }
}
