package sternhalma.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Conformance tests 4 to 9 of docs/rules.md. */
class MoveGenerationTest {

    private static Set<Integer> engineMoves(Position pos) {
        Set<Integer> result = new HashSet<>();
        for (int m : pos.legalMoves()) {
            result.add(Move.from(m) * 1000 + Move.to(m));
        }
        return result;
    }

    private static int cell(int q, int r) {
        int id = Geometry.id(q, r);
        assertTrue(id >= 0, "(" + q + "," + r + ") must be on the board");
        return id;
    }

    private static long classOf(Position pos) {
        // class under the left-right flip: the smaller of the two hashes identifies it
        return Math.min(pos.hash(), pos.transformed(Geometry::mirror, false).hash());
    }

    @Test
    void startPositionHas14LegalFirstMovesAndTheReferenceGeneratorAgrees() {
        Position pos = Position.initial(RuleSet.defaults(2));
        assertEquals(14, pos.legalMoves().length);
        assertEquals(ReferenceGenerator.legalMoves(pos), engineMoves(pos));
    }

    @Test
    void firstMovesFormSevenClassesUnderTheLeftRightFlip() {
        Position pos = Position.initial(RuleSet.defaults(2));
        Set<Long> classes = new HashSet<>();
        for (int m : pos.legalMoves()) {
            pos.make(m);
            classes.add(classOf(pos));
            pos.unmake();
        }
        assertEquals(7, classes.size());
    }

    @Test
    void afterTwoPliesThereAre196PositionsOr98UpToTheLeftRightFlip() {
        Position pos = Position.initial(RuleSet.defaults(2));
        Set<Position> distinct = new HashSet<>();
        Set<Long> classes = new HashSet<>();
        for (int m1 : pos.legalMoves()) {
            pos.make(m1);
            for (int m2 : pos.legalMoves()) {
                pos.make(m2);
                distinct.add(pos.copy());
                classes.add(classOf(pos));
                pos.unmake();
            }
            pos.unmake();
        }
        assertEquals(196, distinct.size());
        assertEquals(98, classes.size());
    }

    @Test
    void theReferenceGeneratorCountsTheSame14Moves196PositionsAnd98Classes() {
        Position pos = Position.initial(RuleSet.defaults(2));
        Set<Integer> first = ReferenceGenerator.legalMoves(pos);
        assertEquals(14, first.size());
        Set<Position> distinct = new HashSet<>();
        Set<Long> classes = new HashSet<>();
        for (int m1 : first) {
            pos.make(Move.of(m1 / 1000, m1 % 1000));
            for (int m2 : ReferenceGenerator.legalMoves(pos)) {
                pos.make(Move.of(m2 / 1000, m2 % 1000));
                distinct.add(pos.copy());
                classes.add(classOf(pos));
                pos.unmake();
            }
            pos.unmake();
        }
        assertEquals(196, distinct.size());
        assertEquals(98, classes.size());
    }

    @Test
    void engineAndReferenceAgreeAlongRandomPlayouts() {
        for (int players = 2; players <= 3; players++) {
            for (long seed = 1; seed <= 6; seed++) {
                java.util.Random random = new java.util.Random(seed * 31 + players);
                Position pos = Position.initial(RuleSet.defaults(players).withMoveLimit(400));
                for (int ply = 0; ply < 120 && pos.outcome() == null; ply++) {
                    int[] moves = pos.legalMoves();
                    assertEquals(ReferenceGenerator.legalMoves(pos), engineMoves(pos),
                            "players=" + players + " seed=" + seed + " ply=" + ply + "\n" + pos);
                    assertEquals(moves.length, engineMoves(pos).size(), "duplicate (start, end) pair");
                    if (moves.length == 0) {
                        break;
                    }
                    pos.make(moves[random.nextInt(moves.length)]);
                }
            }
        }
    }

    @Test
    void anEndCellReachedByTwoJumpPathsIsReportedOnce() {
        // Start (0,0). Path A: over (1,0) to (2,0), then over (2,1) to (2,2).
        //              Path B: over (0,1) to (0,2), then over (1,2) to (2,2).
        Position pos = Position.empty(RuleSet.defaults(2));
        pos.put(0, 0, 0);
        pos.put(1, 1, 0).put(1, 2, 1).put(1, 0, 1).put(1, 1, 2);
        int target = cell(2, 2);
        long toTarget = java.util.Arrays.stream(pos.legalMoves()).filter(m -> Move.to(m) == target).count();
        assertEquals(1, toTarget);
        assertEquals(ReferenceGenerator.legalMoves(pos), engineMoves(pos));
        assertEquals(pos.legalMoves().length, engineMoves(pos).size());
    }

    @Test
    void aJumpChainCanTurnBackwards() {
        // Player 0 plays towards r > 4. Chain: (0,0) over (1,0) to (2,0), then over (2,-1) to (2,-2),
        // a jump towards smaller r. (2,-2) is not adjacent to the start and has no direct jump.
        Position pos = Position.empty(RuleSet.defaults(2));
        pos.put(0, 0, 0).put(1, 1, 0).put(1, 2, -1);
        assertTrue(engineMoves(pos).contains(cell(0, 0) * 1000 + cell(2, -2)));
        assertEquals(ReferenceGenerator.legalMoves(pos), engineMoves(pos));
    }

    @Test
    void noMoveEndsOnItsOwnStartCell() {
        // six pieces around the start cell allow loops through the start cell
        Position ring = Position.empty(RuleSet.defaults(2));
        int centre = cell(0, 0);
        ring.put(0, centre);
        for (int d = 0; d < Geometry.DIRECTIONS; d++) {
            ring.put(1, Geometry.neighbor(centre, d));
        }
        int[] moves = ring.legalMoves();
        assertTrue(moves.length > 0);
        for (int m : moves) {
            assertTrue(Move.from(m) != Move.to(m));
        }
        assertEquals(ReferenceGenerator.legalMoves(ring), engineMoves(ring));
    }

    @Test
    void forbiddenLeaveGoalRemovesMovesOutOfTheGoalAndAllowedKeepsThem() {
        // Player 0's goal is the bottom corner (r > 4). (-1,5) is a goal cell next to the hexagon cell (0,4).
        int from = cell(-1, 5);
        int out = cell(0, 4);
        assertEquals(Corner.R_POS.ordinal(), Geometry.cornerOf(from));
        assertEquals(-1, Geometry.cornerOf(out));
        RuleSet forbidden = RuleSet.defaults(2);
        Position a = Position.empty(forbidden).put(0, from).put(1, cell(4, -8));
        Position b = Position.empty(forbidden.withLeaveGoal(RuleSet.LeaveGoal.ALLOWED)).put(0, from).put(1, cell(4, -8));
        assertFalse(engineMoves(a).contains(from * 1000 + out));
        assertTrue(engineMoves(b).contains(from * 1000 + out));
        assertTrue(engineMoves(a).contains(from * 1000 + cell(-2, 5)), "a step inside the goal stays legal");
        assertEquals(ReferenceGenerator.legalMoves(a), engineMoves(a));
        assertEquals(ReferenceGenerator.legalMoves(b), engineMoves(b));
    }

    @Test
    void forbiddenLeaveGoalChecksTheEndCellOnly() {
        // (-2,5) over (-2,4) lands on (-2,3), outside the goal; from there over (-3,4) to (-4,5), inside the goal.
        Position pos = Position.empty(RuleSet.defaults(2));
        pos.put(0, -2, 5).put(1, -2, 4).put(1, -3, 4).put(1, 4, -8);
        int from = cell(-2, 5);
        assertEquals(Corner.R_POS.ordinal(), Geometry.cornerOf(cell(-4, 5)));
        assertEquals(-1, Geometry.cornerOf(cell(-2, 3)));
        assertTrue(engineMoves(pos).contains(from * 1000 + cell(-4, 5)), "chain ends in the goal: legal");
        assertFalse(engineMoves(pos).contains(from * 1000 + cell(-2, 3)), "chain would stop outside the goal: illegal");
        assertEquals(ReferenceGenerator.legalMoves(pos), engineMoves(pos));
    }

    @Test
    void piecesNeverStopInTheOtherCorners() {
        Position pos = Position.initial(RuleSet.defaults(2));
        for (int m : pos.legalMoves()) {
            int corner = Geometry.cornerOf(Move.to(m));
            assertTrue(corner < 0 || corner == pos.layout().startCorner(0) || corner == pos.layout().goalCorner(0));
        }
    }
}
