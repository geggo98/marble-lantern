package sternhalma.engine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import sternhalma.engine.RuleSet.LeaveGoal;
import sternhalma.engine.RuleSet.SuicideMoves;
import sternhalma.engine.RuleSet.TriangleRows;

/**
 * The house size switch (RuleSet.TriangleRows, docs/rules.md "Board size"): the layout per (players, rows) and the
 * position on 15 pieces. Expected values are written down by coordinates, not taken from the code under test.
 */
class TriangleRowsTest {

    private static final RuleSet FIVE2 = RuleSet.defaults(2, TriangleRows.FIVE);
    private static final RuleSet FIVE3 = RuleSet.defaults(3, TriangleRows.FIVE);

    private static int cell(int q, int r) {
        int id = Geometry.id(q, r);
        assertTrue(id >= 0, "(" + q + "," + r + ") must be on the board");
        return id;
    }

    /** The hexagon edge row in front of each corner, by coordinates: corner index to {q, r} pairs. */
    private static int[][] edgeRow(Corner corner) {
        return switch (corner) {
            case R_NEG -> new int[][] {{0, -4}, {1, -4}, {2, -4}, {3, -4}, {4, -4}};
            case Q_POS -> new int[][] {{4, -4}, {4, -3}, {4, -2}, {4, -1}, {4, 0}};
            case S_NEG -> new int[][] {{0, 4}, {1, 3}, {2, 2}, {3, 1}, {4, 0}}; // s = -4
            case R_POS -> new int[][] {{-4, 4}, {-3, 4}, {-2, 4}, {-1, 4}, {0, 4}};
            case Q_NEG -> new int[][] {{-4, 0}, {-4, 1}, {-4, 2}, {-4, 3}, {-4, 4}};
            case S_POS -> new int[][] {{-4, 0}, {-3, -1}, {-2, -2}, {-1, -3}, {0, -4}}; // s = 4
        };
    }

    private static TreeSet<Integer> expectedHouse(Corner corner) {
        TreeSet<Integer> cells = new TreeSet<>();
        for (int c : Geometry.cornerCells(corner.ordinal())) {
            cells.add(c);
        }
        for (int[] qr : edgeRow(corner)) {
            cells.add(cell(qr[0], qr[1]));
        }
        return cells;
    }

    private static TreeSet<Integer> set(int[] cells) {
        TreeSet<Integer> s = new TreeSet<>();
        for (int c : cells) {
            s.add(c);
        }
        return s;
    }

    // ---- RuleSet ----

    @Test
    void ruleSetTextsOfTheFourRowBoardAreUnchanged() {
        assertEquals("RuleSet[players=2, moveLimit=200, leaveGoal=FORBIDDEN, blockadeRule=OPPONENT_FILLS, "
                + "suicideMoves=ILLEGAL, blockingStates=NONE, repetition=MOVE_LIMIT, "
                + "drawHandling=SCORE_BY_DISTANCE, ranking=FIRST_WINNER_ONLY, jumpRule=ADJACENT]",
                RuleSet.defaults(2).toString());
        assertTrue(RuleSet.defaults(3).withForeignCamps(RuleSet.ForeignCamps.OPEN).toString()
                .endsWith("jumpRule=ADJACENT, foreignCamps=OPEN]"));
        assertEquals(RuleSet.defaults(3), RuleSet.defaults(3, TriangleRows.FOUR));
    }

    @Test
    void fiveRowsRoundTripAndShowInTheText() {
        assertTrue(FIVE2.toString().endsWith("jumpRule=ADJACENT, triangleRows=FIVE]"), FIVE2.toString());
        assertTrue(FIVE3.withForeignCamps(RuleSet.ForeignCamps.OPEN).toString()
                .endsWith("foreignCamps=OPEN, triangleRows=FIVE]"));
        assertEquals(TriangleRows.FIVE, FIVE2.triangleRows());
        assertEquals(RuleSet.defaults(2), FIVE2.withTriangleRows(TriangleRows.FOUR).withMoveLimit(200));
        assertNotEquals(RuleSet.defaults(2), FIVE2);
        // withers keep the house size
        assertEquals(TriangleRows.FIVE, FIVE2.withMoveLimit(7).withJumpRule(RuleSet.JumpRule.LONG)
                .withForeignCamps(RuleSet.ForeignCamps.OPEN).withLeaveGoal(LeaveGoal.ALLOWED).triangleRows());
        assertEquals(10, TriangleRows.FOUR.pieces());
        assertEquals(15, TriangleRows.FIVE.pieces());
        assertEquals(4, TriangleRows.FOUR.rows());
        assertEquals(5, TriangleRows.FIVE.rows());
    }

    @Test
    void shortConstructorsMeanFourRows() {
        RuleSet d = RuleSet.defaults(2);
        assertEquals(TriangleRows.FOUR, new RuleSet(2, 200, d.leaveGoal(), d.blockadeRule(), d.suicideMoves(),
                d.blockingStates(), d.repetition(), d.drawHandling(), d.ranking()).triangleRows());
        assertEquals(TriangleRows.FOUR, new RuleSet(2, 200, d.leaveGoal(), d.blockadeRule(), d.suicideMoves(),
                d.blockingStates(), d.repetition(), d.drawHandling(), d.ranking(), RuleSet.JumpRule.LONG)
                .triangleRows());
        assertEquals(TriangleRows.FOUR, new RuleSet(2, 200, d.leaveGoal(), d.blockadeRule(), d.suicideMoves(),
                d.blockingStates(), d.repetition(), d.drawHandling(), d.ranking(), RuleSet.JumpRule.LONG,
                RuleSet.ForeignCamps.OPEN).triangleRows());
    }

    @Test
    void moveLimitDefaultsPerHouseSize() {
        assertEquals(200, RuleSet.defaults(2).moveLimit());
        assertEquals(300, RuleSet.defaults(3).moveLimit());
        assertEquals(250, FIVE2.moveLimit());
        assertEquals(375, FIVE3.moveLimit());
    }

    // ---- Layout ----

    @Test
    void fourRowLayoutEqualsTheCornerTables() {
        for (int players = 2; players <= 3; players++) {
            Layout layout = Layout.of(players, TriangleRows.FOUR);
            assertSame(layout, Layout.of(players));
            assertEquals(10, layout.pieces());
            assertEquals(10, layout.goalSize());
            for (int p = 0; p < players; p++) {
                int[] dist = new int[Geometry.CELLS];
                Arrays.fill(dist, -1);
                ArrayDeque<Integer> queue = new ArrayDeque<>();
                for (int c : Geometry.cornerCells(layout.goalCorner(p))) {
                    dist[c] = 0;
                    queue.add(c);
                }
                while (!queue.isEmpty()) {
                    int c = queue.poll();
                    for (int d = 0; d < 6; d++) {
                        int n = Geometry.neighbor(c, d);
                        int k = n < 0 ? -1 : Geometry.cornerOf(n);
                        if (n >= 0 && (k < 0 || k == layout.startCorner(p) || k == layout.goalCorner(p))
                                && dist[n] < 0) {
                            dist[n] = dist[c] + 1;
                            queue.add(n);
                        }
                    }
                }
                int max = 0;
                for (int c = 0; c < Geometry.CELLS; c++) {
                    assertEquals(Geometry.cornerOf(c) == layout.startCorner(p), layout.inStart(p, c));
                    assertEquals(Geometry.cornerOf(c) == layout.goalCorner(p), layout.inGoal(p, c));
                    assertEquals(layout.goalPlayer(Geometry.cornerOf(c)), layout.goalPlayerOfCell(c));
                    if (layout.allowed(p, c)) {
                        assertEquals(dist[c], layout.distance(p, c), "player " + p + " cell " + c);
                        max = Math.max(max, dist[c]);
                    }
                }
                assertEquals(max, layout.maxDistance(p));
                assertEquals(13, max);
            }
        }
    }

    @Test
    void everyFiveRowHouseIsTheCornerPlusItsHexagonEdgeRow() {
        for (int players = 2; players <= 3; players++) {
            Layout layout = Layout.of(players, TriangleRows.FIVE);
            assertEquals(15, layout.pieces());
            assertEquals(15, layout.goalSize());
            for (int p = 0; p < players; p++) {
                assertEquals(expectedHouse(Corner.ofIndex(layout.startCorner(p))), set(layout.startCells(p)));
                assertEquals(expectedHouse(Corner.ofIndex(layout.goalCorner(p))), set(layout.goalCells(p)));
                for (int c = 0; c < Geometry.CELLS; c++) {
                    assertEquals(set(layout.startCells(p)).contains(c), layout.inStart(p, c));
                    assertEquals(set(layout.goalCells(p)).contains(c), layout.inGoal(p, c));
                }
            }
        }
    }

    @Test
    void twoPlayersStartAndGoalDoNotOverlapAndThreePlayersOverlapInTheSixVertexCells() {
        Layout two = Layout.of(2, TriangleRows.FIVE);
        for (int c = 0; c < Geometry.CELLS; c++) {
            for (int p = 0; p < 2; p++) {
                assertFalse(two.inStart(p, c) && two.inGoal(p, c), "cell " + c);
            }
        }
        Layout three = Layout.of(3, TriangleRows.FIVE);
        TreeSet<Integer> shared = new TreeSet<>();
        for (int c = 0; c < Geometry.CELLS; c++) {
            int starts = 0;
            int goals = 0;
            for (int p = 0; p < 3; p++) {
                starts += three.inStart(p, c) ? 1 : 0;
                goals += three.inGoal(p, c) ? 1 : 0;
            }
            assertTrue(starts <= 1, "two start houses share cell " + c);
            assertTrue(goals <= 1, "two goal houses share cell " + c);
            if (starts == 1 && goals == 1) {
                shared.add(c);
            }
        }
        TreeSet<Integer> vertices = new TreeSet<>();
        int[][] qr = {{4, -4}, {4, 0}, {0, 4}, {-4, 4}, {-4, 0}, {0, -4}};
        for (int[] v : qr) {
            vertices.add(cell(v[0], v[1]));
        }
        assertEquals(vertices, shared);
        // the goal owner of a shared cell is the neighbour whose goal house holds it
        assertEquals(2, three.goalPlayerOfCell(cell(4, -4)));
        assertEquals(1, three.goalPlayerOfCell(cell(0, -4)));
    }

    @Test
    void fiveRowDistancesGrowByTheEdgeRow() {
        for (int players = 2; players <= 3; players++) {
            Layout layout = Layout.of(players, TriangleRows.FIVE);
            for (int p = 0; p < players; p++) {
                assertEquals(12, layout.maxDistance(p));
                for (int c : layout.goalCells(p)) {
                    assertEquals(0, layout.distance(p, c));
                }
            }
        }
        Layout two = Layout.of(2, TriangleRows.FIVE);
        assertEquals(12, two.distance(0, cell(4, -8))); // the tip of the start corner, 12 rows from the goal row r = 4
        assertEquals(1, two.distance(0, cell(0, 3)));   // the row in front of the goal house
        assertEquals(13, Layout.of(2, TriangleRows.FOUR).distance(0, cell(4, -8)));
        // foreign corners are not home cells under FIVE either
        assertFalse(two.allowed(0, cell(8, -4)));
        assertTrue(two.allowed(0, cell(4, 0)));
    }

    /**
     * docs/rules.md, "Foreign corners", distance bound on 5 rows: a foreign cell gets the step count to the goal house
     * over the whole board, and the largest such value (8) stays below the largest home distance (12).
     */
    @Test
    void foreignCellsOnFiveRowsKeepTheWholeBoardDistanceBelowTheLargestHomeDistance() {
        for (int players = 2; players <= 3; players++) {
            Layout layout = Layout.of(players, TriangleRows.FIVE);
            for (int p = 0; p < players; p++) {
                int[] whole = new int[Geometry.CELLS];
                Arrays.fill(whole, -1);
                ArrayDeque<Integer> queue = new ArrayDeque<>();
                for (int c : TriangleRowsOracle.house(TriangleRowsOracle.goal(players, p), 5)) {
                    whole[c] = 0;
                    queue.add(c);
                }
                while (!queue.isEmpty()) {
                    int c = queue.poll();
                    for (int d = 0; d < Geometry.DIRECTIONS; d++) {
                        int n = Geometry.neighbor(c, d);
                        if (n >= 0 && whole[n] < 0) {
                            whole[n] = whole[c] + 1;
                            queue.add(n);
                        }
                    }
                }
                int largestForeign = 0;
                for (int c = 0; c < Geometry.CELLS; c++) {
                    if (!layout.allowed(p, c)) {
                        assertEquals(whole[c], layout.distance(p, c), "players " + players + " player " + p + " cell " + c);
                        largestForeign = Math.max(largestForeign, layout.distance(p, c));
                    }
                }
                assertEquals(8, largestForeign, "players " + players + " player " + p);
                assertTrue(largestForeign < layout.maxDistance(p));
            }
        }
    }

    /**
     * docs/rules.md, "Move counts on the full board", 5 rows: 18 first moves and 324 positions after two plies, for both
     * jump rules. Hand count of the 18: the 5 pieces of the edge row r = -4 step to 2 cells each (10), the 4 pieces of
     * row r = -5 jump over the edge row to 2 cells each (8). The engine and the coordinate oracle must both give them.
     */
    @Test
    void firstMovesAndPositionsAfterTwoPliesOnFiveRows() {
        for (RuleSet.JumpRule jump : RuleSet.JumpRule.values()) {
            Position pos = Position.initial(FIVE2.withJumpRule(jump));
            TreeSet<Long> engineAfterTwo = new TreeSet<>();
            int[] first = pos.legalMoves();
            for (int m1 : first) {
                pos.make(m1);
                for (int m2 : pos.legalMoves()) {
                    pos.make(m2);
                    engineAfterTwo.add(pos.hash());
                    pos.unmake();
                }
                pos.unmake();
            }
            assertEquals(18, first.length, jump + ": engine first moves");
            assertEquals(324, engineAfterTwo.size(), jump + ": engine positions after two plies");

            boolean longHop = jump == RuleSet.JumpRule.LONG;
            int[] owner = new int[Geometry.CELLS];
            Arrays.fill(owner, -1);
            for (int p = 0; p < 2; p++) {
                for (int c : TriangleRowsOracle.house(TriangleRowsOracle.start(2, p), 5)) {
                    owner[c] = p;
                }
            }
            TreeSet<String> oracleAfterTwo = new TreeSet<>();
            TreeSet<Integer> oracleFirst = TriangleRowsOracle.moves(owner, 2, 0, longHop, RuleSet.ForeignCamps.CLOSED, 5);
            for (int m1 : oracleFirst) {
                int[] one = owner.clone();
                one[Move.to(m1)] = 0;
                one[Move.from(m1)] = -1;
                for (int m2 : TriangleRowsOracle.moves(one, 2, 1, longHop, RuleSet.ForeignCamps.CLOSED, 5)) {
                    int[] two = one.clone();
                    two[Move.to(m2)] = 1;
                    two[Move.from(m2)] = -1;
                    oracleAfterTwo.add(Arrays.toString(two));
                }
            }
            assertEquals(18, oracleFirst.size(), jump + ": oracle first moves");
            assertEquals(324, oracleAfterTwo.size(), jump + ": oracle positions after two plies");
        }
    }

    // ---- Position ----

    @Test
    void startPositionsHoldFifteenPiecesPerPlayer() {
        Position two = Position.initial(FIVE2);
        assertEquals(15, two.pieceCount(0));
        assertEquals(15, two.pieceCount(1));
        assertEquals(15, two.pieces(0).length);
        assertArrayEquals(Layout.of(2, TriangleRows.FIVE).startCells(0), two.pieces(0));
        assertEquals(15, two.goalOccupied(0)); // the goal of one player is the start house of the other
        assertEquals(15, two.goalOccupied(1));
        // rows r = -8..-4 hold 1..5 pieces, each 4 - r rows from the goal row r = 4: 12 + 2*11 + 3*10 + 4*9 + 5*8
        assertEquals(140, two.distanceSum(0));
        assertEquals(140, two.distanceSum(1));
    }

    @Test
    void threePlayerStartHousesAlreadyHoldTwoForeignGoalPieces() {
        Position three = Position.initial(FIVE3);
        for (int p = 0; p < 3; p++) {
            assertEquals(15, three.pieceCount(p));
            assertEquals(2, three.goalOccupied(p), "goal of player " + p);
            assertFalse(three.hasWon(p));
        }
        assertNull(three.outcome());
    }

    private static Position goalWith(RuleSet rules, int own, int opponent) {
        Position pos = Position.empty(rules);
        int[] goal = Layout.of(rules.players(), rules.triangleRows()).goalCells(0);
        for (int i = 0; i < own + opponent; i++) {
            pos.put(i < own ? 0 : 1, goal[i]);
        }
        return pos;
    }

    @Test
    void aGoalNeedsAllFifteenCells() {
        assertFalse(goalWith(FIVE2, 14, 0).hasWon(0));
        assertNull(goalWith(FIVE2, 14, 0).outcome());
        assertTrue(goalWith(FIVE2, 15, 0).hasWon(0));
        assertEquals(0, goalWith(FIVE2, 15, 0).outcome().winner());
        // ten cells (the corner triangle alone) are not a full goal on the 5-row board
        assertFalse(goalWith(FIVE2, 10, 0).hasWon(0));
        // OPPONENT_FILLS: one own piece among 14 foreign ones wins, none does not
        assertTrue(goalWith(FIVE2, 1, 14).hasWon(0));
        assertFalse(goalWith(FIVE2, 0, 15).hasWon(0));
        RuleSet strict = FIVE2.withBlockadeRule(RuleSet.BlockadeRule.NONE);
        assertFalse(goalWith(strict, 14, 1).hasWon(0));
        assertTrue(goalWith(strict, 15, 0).hasWon(0));
    }

    /** Player 0 stands on the goal edge row cell (0,4), the rest of its pieces nowhere near. */
    private static List<Integer> targetsFrom(RuleSet rules, int from) {
        Position pos = Position.empty(rules).put(0, from).put(1, cell(0, -8 + 4)); // a far, harmless opponent piece
        List<Integer> to = new ArrayList<>();
        int[] buf = new int[Position.MAX_MOVES];
        int n = pos.generate(buf);
        for (int i = 0; i < n; i++) {
            to.add(Move.to(buf[i]));
        }
        return to;
    }

    @Test
    void aPieceOnTheGoalEdgeRowCannotLeaveTheHouseUnderForbidden() {
        int from = cell(0, 4);
        List<Integer> forbidden = targetsFrom(FIVE2, from);
        assertFalse(forbidden.isEmpty());
        for (int to : forbidden) {
            assertTrue(Geometry.r(to) >= 4, "left the goal house to r=" + Geometry.r(to));
        }
        // the same piece on the 4-row board stands in the hexagon and may go anywhere
        assertTrue(targetsFrom(RuleSet.defaults(2), from).stream().anyMatch(to -> Geometry.r(to) < 4));
        // and with leaving allowed it may leave the 5-row house too
        assertTrue(targetsFrom(FIVE2.withLeaveGoal(LeaveGoal.ALLOWED), from).stream()
                .anyMatch(to -> Geometry.r(to) < 4));
    }

    @Test
    void aJumpChainOfAPieceInTheGoalHouseMustEndInsideTheHouse() {
        // (-2,4) jumps over (-1,4) and lands on (0,4) in the house: allowed.
        // A jump over (-2,3) would land on (-2,2), outside: not a move under FORBIDDEN.
        Position pos = Position.empty(FIVE2).put(0, cell(-2, 4)).put(1, cell(-2, 3)).put(1, cell(-1, 4));
        int[] buf = new int[Position.MAX_MOVES];
        int n = pos.generate(buf);
        List<Integer> to = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            to.add(Move.to(buf[i]));
        }
        assertTrue(to.contains(cell(0, 4)));
        assertFalse(to.contains(cell(-2, 2)));
    }

    /** Player 1's goal is the top house (r <= -4): {@code filled} pieces of player 1 there, a gap at (0,-4). */
    private static Position suicideSetup(RuleSet rules, int filled) {
        Position pos = Position.empty(rules);
        int placed = 0;
        for (int c : Layout.of(2, TriangleRows.FIVE).goalCells(1)) {
            if (c != cell(0, -4) && placed < filled) {
                pos.put(1, c);
                placed++;
            }
        }
        pos.put(0, cell(0, -3));
        return pos;
    }

    private static boolean canStepInto(Position pos, int target) {
        int[] buf = new int[Position.MAX_MOVES];
        int n = pos.generate(buf);
        for (int i = 0; i < n; i++) {
            if (Move.to(buf[i]) == target) {
                return true;
            }
        }
        return false;
    }

    @Test
    void theSuicideCheckFiresAtFourteenOfFifteen() {
        int gap = cell(0, -4);
        // 14 pieces of player 1 in its goal: filling the 15th cell would hand it the win
        assertFalse(canStepInto(suicideSetup(FIVE2, 14), gap));
        assertTrue(canStepInto(suicideSetup(FIVE2.withSuicideMoves(SuicideMoves.LEGAL), 14), gap));
        // 13 pieces: the move makes 14, no win, still legal
        assertTrue(canStepInto(suicideSetup(FIVE2, 13), gap));
    }

    @Test
    void makeAndUnmakeRestoreTheHashAndTheHashMatchesARebuild() {
        for (RuleSet rules : new RuleSet[] {FIVE2, FIVE3, FIVE3.withLeaveGoal(LeaveGoal.ALLOWED),
                FIVE2.withJumpRule(RuleSet.JumpRule.LONG)}) {
            Random random = new Random(12345);
            Position pos = Position.initial(rules);
            long start = pos.hash();
            int[] buf = new int[Position.MAX_MOVES];
            List<Long> hashes = new ArrayList<>();
            for (int ply = 0; ply < 80 && pos.outcome() == null; ply++) {
                int n = pos.generate(buf);
                assertTrue(n > 0);
                hashes.add(pos.hash());
                pos.make(buf[random.nextInt(n)]);
                Position rebuilt = Position.empty(rules);
                for (int p = 0; p < rules.players(); p++) {
                    for (int c : pos.pieces(p)) {
                        rebuilt.put(p, c);
                    }
                }
                rebuilt.setSideToMove(pos.sideToMove());
                assertEquals(rebuilt.hash(), pos.hash());
                assertEquals(rebuilt, pos);
            }
            Position copy = pos.copy();
            for (int i = hashes.size() - 1; i >= 0; i--) {
                pos.unmake();
                assertEquals(hashes.get(i), pos.hash());
            }
            assertEquals(start, pos.hash());
            assertEquals(Position.initial(rules), pos);
            assertEquals(hashes.size(), copy.ply());
            for (int p = 0; p < rules.players(); p++) {
                assertEquals(15, pos.pieceCount(p));
            }
        }
    }

    @Test
    void goalCountsStayConsistentOverARandomGame() {
        for (RuleSet rules : new RuleSet[] {FIVE2, FIVE3}) {
            Layout layout = Layout.of(rules.players(), TriangleRows.FIVE);
            Random random = new Random(7);
            Position pos = Position.initial(rules);
            int[] buf = new int[Position.MAX_MOVES];
            for (int ply = 0; ply < 120 && pos.outcome() == null; ply++) {
                int n = pos.generate(buf);
                if (n == 0) {
                    break;
                }
                pos.make(buf[random.nextInt(n)]);
                for (int q = 0; q < rules.players(); q++) {
                    int count = 0;
                    for (int c : layout.goalCells(q)) {
                        count += pos.owner(c) >= 0 ? 1 : 0;
                    }
                    assertEquals(count, pos.goalOccupied(q), "ply " + ply + " goal " + q);
                }
            }
        }
    }

    @Test
    void positionsOfDifferentHouseSizesAreNotEqualAndTheMoveLimitEndsAFiveRowGame() {
        assertNotEquals(Position.empty(RuleSet.defaults(2)), Position.empty(FIVE2));
        RuleSet rules = FIVE2.withMoveLimit(6);
        Position pos = Position.initial(rules);
        int[] buf = new int[Position.MAX_MOVES];
        Random random = new Random(3);
        for (int i = 0; i < 6; i++) {
            assertNull(pos.outcome());
            pos.make(firstMove(pos, buf, random));
        }
        Outcome out = pos.outcome();
        assertEquals(Outcome.Reason.MOVE_LIMIT, out.reason());
        // SCORE_BY_DISTANCE: (mean - own sum) / (15 pieces * largest distance 12)
        double mean = (pos.distanceSum(0) + pos.distanceSum(1)) / 2.0;
        for (int p = 0; p < 2; p++) {
            assertEquals((mean - pos.distanceSum(p)) / (15 * 12), out.values()[p], 1e-12);
        }
        assertNotEquals(0.0, out.values()[0]);
    }

    private static int firstMove(Position pos, int[] buf, Random random) {
        int n = pos.generate(buf);
        return buf[random.nextInt(n)];
    }

    @Test
    void moreThanFifteenPiecesAreRefused() {
        Position pos = Position.empty(FIVE2);
        int placed = 0;
        for (int c = 0; c < Geometry.CELLS && placed < 16; c++) {
            if (Layout.of(2, TriangleRows.FIVE).allowed(0, c)) {
                pos.put(0, c);
                placed++;
            }
        }
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> pos.pieces(0));
    }
}
