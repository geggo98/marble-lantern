package sternhalma.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import sternhalma.engine.RuleSet.JumpRule;
import sternhalma.engine.RuleSet.LeaveGoal;
import sternhalma.engine.RuleSet.SuicideMoves;

/**
 * The Halma long hop ({@link JumpRule#LONG}, docs/rules.md "Jump rule"). The hand-computed positions use cells of
 * the central hexagon on the row r = 0 (q from -4 to 4; (-5, 0) and (5, 0) are off the board) and fix the expected
 * landings by counting cells. The property tests compare the engine with {@link LongJumpOracle}, which is written
 * from the definition and shares no code with the engine.
 */
class LongJumpTest {

    private static final RuleSet ADJACENT = RuleSet.defaults(2);
    private static final RuleSet LONG = RuleSet.defaults(2).withJumpRule(JumpRule.LONG);
    /** Rules with the leaveGoal and Definition-2 filters off, which the oracle does not model. */
    private static final RuleSet LONG_RELAXED = LONG.withLeaveGoal(LeaveGoal.ALLOWED).withSuicideMoves(SuicideMoves.LEGAL);

    private static int cell(int q, int r) {
        int id = Geometry.id(q, r);
        assertTrue(id >= 0, "(" + q + "," + r + ") is off the board");
        return id;
    }

    private static Set<Integer> ends(Position pos, int from) {
        Set<Integer> ends = new TreeSet<>();
        for (int move : pos.legalMoves()) {
            if (Move.from(move) == from) {
                assertTrue(ends.add(Move.to(move)), "duplicate move to " + Move.to(move));
            }
        }
        return ends;
    }

    private static Set<Integer> cells(int[]... coordinates) {
        Set<Integer> set = new TreeSet<>();
        for (int[] c : coordinates) {
            set.add(cell(c[0], c[1]));
        }
        return set;
    }

    private static int[] at(int q, int r) {
        return new int[] {q, r};
    }

    // ---- hand-computed positions ----

    @Test
    void hopOverAdjacentPieceIsTheSingleJump() {
        // k = 1: X (-2,0), P (-1,0), landing (0,0). Six steps are possible except onto P: five steps and one hop.
        Position pos = Position.empty(LONG).put(0, -2, 0).put(1, -1, 0);
        assertEquals(cells(at(-3, 0), at(-2, 1), at(-2, -1), at(-1, -1), at(-3, 1), at(0, 0)), ends(pos, cell(-2, 0)));
    }

    @Test
    void hopFromTwoStepsAway() {
        // k = 2: X (-3,0), empty (-2,0), P (-1,0), empty (0,0), landing (1,0). Six steps plus the hop.
        Position pos = Position.empty(LONG).put(0, -3, 0).put(1, -1, 0);
        assertEquals(cells(at(-2, 0), at(-4, 0), at(-3, 1), at(-3, -1), at(-2, -1), at(-4, 1), at(1, 0)),
                ends(pos, cell(-3, 0)));
        // the single-jump rule has no move there
        Position adjacent = Position.empty(ADJACENT).put(0, -3, 0).put(1, -1, 0);
        assertFalse(ends(adjacent, cell(-3, 0)).contains(cell(1, 0)));
    }

    @Test
    void hopFromThreeStepsAway() {
        // k = 3: X (-4,0), empty (-3,0) and (-2,0), P (-1,0), empty (0,0), (1,0), landing (2,0)
        Position pos = Position.empty(LONG).put(0, -4, 0).put(1, -1, 0);
        Set<Integer> ends = ends(pos, cell(-4, 0));
        assertTrue(ends.contains(cell(2, 0)));
        assertFalse(ends.contains(cell(0, 0)), "no landing nearer than the mirror cell");
        assertFalse(ends.contains(cell(1, 0)));
        assertFalse(ends.contains(cell(3, 0)));
    }

    @Test
    void anotherPieceOnThePathToTheHurdleChangesTheHurdle() {
        // X (-4,0), B (-3,0), C (-2,0), P (-1,0): the first piece on the line is B, its landing (-2,0) is taken
        Position pos = Position.empty(LONG).put(0, -4, 0).put(1, -3, 0).put(1, -2, 0).put(1, -1, 0);
        Set<Integer> ends = ends(pos, cell(-4, 0));
        assertFalse(ends.contains(cell(-2, 0)));
        assertFalse(ends.contains(cell(2, 0)), "the hop over P would have to pass B");
        assertFalse(ends.contains(cell(0, 0)));
    }

    @Test
    void blockedLandingForbidsTheHop() {
        // X (-3,0), P (-1,0), landing (1,0) taken
        Position pos = Position.empty(LONG).put(0, -3, 0).put(1, -1, 0).put(1, 1, 0);
        assertFalse(ends(pos, cell(-3, 0)).contains(cell(1, 0)));
    }

    @Test
    void pieceBetweenHurdleAndLandingForbidsTheHop() {
        // k = 3: X (-4,0), P (-1,0), landing (2,0); a piece on (1,0) lies between P and the landing
        Position pos = Position.empty(LONG).put(0, -4, 0).put(1, -1, 0).put(1, 1, 0);
        assertFalse(ends(pos, cell(-4, 0)).contains(cell(2, 0)));
        // the same position without that piece allows it (the test would otherwise prove nothing)
        Position free = Position.empty(LONG).put(0, -4, 0).put(1, -1, 0);
        assertTrue(ends(free, cell(-4, 0)).contains(cell(2, 0)));
    }

    @Test
    void landingOffTheBoardForbidsTheHop() {
        // row r = 0 ends at q = 4. X (1,0), P (3,0): landing (5,0) is off the board
        Position pos = Position.empty(LONG).put(0, 1, 0).put(1, 3, 0);
        assertEquals(-1, Geometry.id(5, 0));
        assertEquals(6, ends(pos, cell(1, 0)).size(), "six steps, no hop");
        // k = 1 at the edge: X (3,0), P (4,0), landing (5,0)
        Position edge = Position.empty(LONG).put(0, 3, 0).put(1, 4, 0);
        assertFalse(ends(edge, cell(3, 0)).contains(cell(4, 0)));
        assertEquals(6 - 1, ends(edge, cell(3, 0)).size(), "five steps, no hop");
    }

    @Test
    void hopOverOwnAndForeignPieces() {
        for (int owner = 0; owner <= 1; owner++) {
            Position pos = Position.empty(LONG).put(0, -3, 0).put(owner, -1, 0);
            assertTrue(ends(pos, cell(-3, 0)).contains(cell(1, 0)), "hurdle of player " + owner);
        }
    }

    @Test
    void chainedLongHops() {
        // X (-4,0) over (-2,0) (k = 2) to (0,0); then over (0,-2) (k = 2, direction (0,-1)) to (0,-4)
        Position pos = Position.empty(LONG).put(0, -4, 0).put(1, -2, 0).put(1, 0, -2);
        Set<Integer> ends = ends(pos, cell(-4, 0));
        assertTrue(ends.contains(cell(0, 0)));
        assertTrue(ends.contains(cell(0, -4)), "second hop of the chain");
        Position adjacent = Position.empty(ADJACENT).put(0, -4, 0).put(1, -2, 0).put(1, 0, -2);
        assertFalse(ends(adjacent, cell(-4, 0)).contains(cell(0, -4)));
    }

    @Test
    void startCellCountsAsEmptyDuringTheChain() {
        // X (0,0) hops east over (1,0) to (2,0), north over (2,-1) to (2,-2), then south-west over (-1,1): that line
        // passes the start cell (0,0) at k = 2 and the hurdle is k = 3 away; the landing is (-4,4). Without the
        // start cell counting as empty the first piece on the line would be X itself.
        Position pos = Position.empty(LONG).put(0, 0, 0).put(1, 1, 0).put(1, 2, -1).put(1, -1, 1);
        assertTrue(ends(pos, cell(0, 0)).contains(cell(-4, 4)));
    }

    @Test
    void leaveGoalStillAppliesToLongHops() {
        // player 0's goal is the corner r > 4 (row r = 5 holds q = -4..-1). X (-3,5) hops north-east over (-2,4)
        // (k = 1) to (-1,3), or over (-1,3) (k = 2) to (1,1); both landings are outside the goal corner.
        int[][] hurdleAndLanding = {{-2, 4, -1, 3}, {-1, 3, 1, 1}};
        int from = cell(-3, 5);
        for (int[] h : hurdleAndLanding) {
            Position forbidden = Position.empty(LONG).put(0, -3, 5).put(1, h[0], h[1]);
            assertFalse(ends(forbidden, from).contains(cell(h[2], h[3])), "hop out of the goal");
            for (int to : ends(forbidden, from)) {
                assertEquals(Geometry.cornerOf(from), Geometry.cornerOf(to), "left the goal corner");
            }
            Position allowed = Position.empty(LONG.withLeaveGoal(LeaveGoal.ALLOWED)).put(0, -3, 5).put(1, h[0], h[1]);
            assertTrue(ends(allowed, from).contains(cell(h[2], h[3])), "same hop with leaveGoal=ALLOWED");
        }
    }

    @Test
    void landingMustBeAnAllowedCell() {
        // player 0 may not stand in the corner q > 4. (5,-1) is a cell of that corner: X (3,-1), P (4,-1), k = 1
        Position pos = Position.empty(LONG).put(0, 3, -1).put(1, 4, -1);
        assertEquals(Corner.Q_POS.ordinal(), Geometry.cornerOf(cell(5, -1)));
        assertFalse(ends(pos, cell(3, -1)).contains(cell(5, -1)));
        // k = 2: X (2,-2), P (4,-2), landing (6,-2), also a cell of that corner
        Position far = Position.empty(LONG).put(0, 2, -2).put(1, 4, -2);
        assertEquals(Corner.Q_POS.ordinal(), Geometry.cornerOf(cell(6, -2)));
        assertFalse(ends(far, cell(2, -2)).contains(cell(6, -2)));
    }

    // ---- RuleSet ----

    @Test
    void ruleSetDefaultsAndWithMethodsKeepTheJumpRule() {
        assertEquals(JumpRule.ADJACENT, RuleSet.defaults(2).jumpRule());
        assertEquals(JumpRule.ADJACENT, RuleSet.defaults(3).jumpRule());
        RuleSet d = RuleSet.defaults(2);
        assertEquals(JumpRule.ADJACENT, new RuleSet(2, 200, d.leaveGoal(), d.blockadeRule(), d.suicideMoves(),
                d.blockingStates(), d.repetition(), d.drawHandling(), d.ranking()).jumpRule());
        assertEquals(d, new RuleSet(2, 200, d.leaveGoal(), d.blockadeRule(), d.suicideMoves(),
                d.blockingStates(), d.repetition(), d.drawHandling(), d.ranking()));
        RuleSet l = d.withJumpRule(JumpRule.LONG);
        assertEquals(JumpRule.LONG, l.jumpRule());
        assertNotEquals(d, l);
        assertEquals(d, l.withJumpRule(JumpRule.ADJACENT));
        assertEquals(l, l.withJumpRule(JumpRule.LONG));
        assertEquals(l.hashCode(), d.withJumpRule(JumpRule.LONG).hashCode());
        // every other with* method keeps the jump rule
        List<RuleSet> kept = List.of(
                l.withMoveLimit(77), l.withLeaveGoal(LeaveGoal.ALLOWED),
                l.withBlockadeRule(RuleSet.BlockadeRule.NONE), l.withSuicideMoves(SuicideMoves.LEGAL),
                l.withRepetition(RuleSet.Repetition.REPETITION_IS_DRAW),
                l.withDrawHandling(RuleSet.DrawHandling.DRAW));
        for (RuleSet r : kept) {
            assertEquals(JumpRule.LONG, r.jumpRule(), r.toString());
        }
        // and the other fields survive withJumpRule
        RuleSet custom = d.withMoveLimit(55).withLeaveGoal(LeaveGoal.ALLOWED).withRepetition(RuleSet.Repetition.REPETITION_IS_DRAW);
        assertEquals(custom, custom.withJumpRule(JumpRule.LONG).withJumpRule(JumpRule.ADJACENT));
        assertThrows(NullPointerException.class, () -> d.withJumpRule(null));
    }

    @Test
    void positionsWithDifferentJumpRulesAreNotEqual() {
        assertNotEquals(Position.initial(ADJACENT), Position.initial(LONG));
        assertEquals(Position.initial(LONG), Position.initial(LONG));
    }

    // ---- properties ----

    /** Random positions: pieces scattered over the cells that the players may enter, plus mid-game playout positions. */
    private static List<Position> randomPositions(RuleSet rules, long seed, int scatter, int playouts) {
        SplitMix64 random = new SplitMix64(seed);
        List<Position> positions = new ArrayList<>();
        Layout layout = Layout.of(2);
        List<Integer> allowed = new ArrayList<>();
        for (int c = 0; c < Geometry.CELLS; c++) {
            if (layout.allowed(0, c)) {
                allowed.add(c);
            }
        }
        for (int i = 0; i < scatter; i++) {
            Position pos = Position.empty(rules);
            Set<Integer> used = new HashSet<>();
            int perPlayer = 1 + (int) ((random.nextLong() >>> 1) % 10);
            for (int p = 0; p < 2; p++) {
                for (int n = 0; n < perPlayer; n++) {
                    int c;
                    do {
                        c = allowed.get((int) ((random.nextLong() >>> 1) % allowed.size()));
                    } while (!used.add(c));
                    pos.put(p, c);
                }
            }
            pos.setSideToMove((int) ((random.nextLong() >>> 1) % 2));
            positions.add(pos);
        }
        for (int i = 0; i < playouts; i++) {
            Position pos = Position.initial(rules);
            int plies = 4 + (int) ((random.nextLong() >>> 1) % 80);
            while (pos.ply() < plies && pos.outcome() == null) {
                int[] moves = pos.legalMoves();
                if (moves.length == 0) {
                    break;
                }
                pos.make(moves[(int) ((random.nextLong() >>> 1) % moves.length)]);
                if (pos.ply() % 8 == 0) {
                    positions.add(pos.copy());
                }
            }
            positions.add(pos);
        }
        return positions;
    }

    private static Set<Integer> asSet(int[] moves) {
        Set<Integer> set = new TreeSet<>();
        for (int m : moves) {
            assertTrue(set.add(m), "duplicate (from,to) pair " + Move.toString(m));
        }
        return set;
    }

    @Test
    void engineEqualsTheOracleOnRandomPositionsAndHasNoDuplicates() {
        long hops = 0;
        long longerHops = 0;
        for (Position pos : randomPositions(LONG_RELAXED, 0xA11CEL, 1500, 150)) {
            Set<Integer> engine = asSet(pos.legalMoves());
            assertEquals(LongJumpOracle.moves(pos, true), engine, "position:\n" + pos);
            for (int m : engine) {
                if (hexDistance(Move.from(m), Move.to(m)) > 1) {
                    hops++;
                }
            }
            for (int m : engine) {
                if (hexDistance(Move.from(m), Move.to(m)) > 2) {
                    longerHops++;
                }
            }
        }
        // the sample must contain hops that the single-jump rule does not have, or the test proves little
        assertTrue(hops > 5_000, "hops: " + hops);
        assertTrue(longerHops > 1_000, "longer hops: " + longerHops);
    }

    @Test
    void adjacentRuleStillEqualsTheOracleInItsSingleJumpMode() {
        for (Position pos : randomPositions(ADJACENT.withLeaveGoal(LeaveGoal.ALLOWED).withSuicideMoves(SuicideMoves.LEGAL), 0xB0B, 600, 60)) {
            assertEquals(LongJumpOracle.moves(pos, false), asSet(pos.legalMoves()), "position:\n" + pos);
        }
    }

    @Test
    void longMovesAreASupersetOfAdjacentMoves() {
        long strict = 0;
        for (Position pos : randomPositions(LONG_RELAXED, 0xC0FFEEL, 1000, 100)) {
            Position adjacent = Position.empty(LONG_RELAXED.withJumpRule(JumpRule.ADJACENT));
            for (int p = 0; p < 2; p++) {
                for (int c : pos.pieces(p)) {
                    adjacent.put(p, c);
                }
            }
            adjacent.setSideToMove(pos.sideToMove());
            Set<Integer> shorter = asSet(adjacent.legalMoves());
            Set<Integer> longer = asSet(pos.legalMoves());
            assertTrue(longer.containsAll(shorter), "LONG lacks an ADJACENT move in\n" + pos);
            if (longer.size() > shorter.size()) {
                strict++;
            }
        }
        assertTrue(strict > 500, "positions where LONG has more moves: " + strict);
    }

    @Test
    void generationOrderIsStepsFirstThenHopsPerPieceByAscendingCell() {
        for (Position pos : randomPositions(LONG, 0xD0D0L, 300, 50)) {
            int[] moves = pos.legalMoves();
            int lastFrom = -1;
            boolean inHops = false;
            for (int m : moves) {
                int from = Move.from(m);
                assertTrue(from >= lastFrom, "pieces by ascending cell");
                if (from != lastFrom) {
                    inHops = false;
                    lastFrom = from;
                }
                boolean step = hexDistance(from, Move.to(m)) == 1;
                if (inHops) {
                    assertFalse(step, "a step after a hop landing of the same piece");
                }
                inHops |= !step;
            }
        }
    }

    private static int hexDistance(int a, int b) {
        int dq = Geometry.q(a) - Geometry.q(b);
        int dr = Geometry.r(a) - Geometry.r(b);
        return Math.max(Math.abs(dq), Math.max(Math.abs(dr), Math.abs(dq + dr)));
    }

    // ---- perft-style move-count table ----

    /** Leaf count of the game tree to {@code depth}, with the moves from the oracle (independent of the generator). */
    private static long oraclePerft(Position pos, int depth) {
        TreeSet<Integer> moves = LongJumpOracle.moves(pos, true);
        if (depth == 1) {
            return moves.size();
        }
        long total = 0;
        for (int m : moves) {
            pos.make(m);
            total += oraclePerft(pos, depth - 1);
            pos.unmake();
        }
        return total;
    }

    private static long perft(Position pos, int depth) {
        int[] moves = pos.legalMoves();
        if (depth == 1) {
            return moves.length;
        }
        long total = 0;
        for (int m : moves) {
            pos.make(m);
            total += perft(pos, depth - 1);
            pos.unmake();
        }
        return total;
    }

    /** Position after three plies chosen by a seeded playout over the sorted LONG moves (independent of generation order). */
    private static Position threePlies(RuleSet rules, long seed) {
        Position pos = Position.initial(rules);
        SplitMix64 random = new SplitMix64(seed);
        for (int i = 0; i < 3; i++) {
            int[] sorted = LongJumpOracle.moves(pos, true).stream().mapToInt(Integer::intValue).toArray();
            pos.make(sorted[(int) ((random.nextLong() >>> 1) % sorted.length)]);
        }
        return pos;
    }

    @Test
    void perftTableFromTheStartPosition() {
        // leaf counts at depth 1, 2, 3 (the table was computed with the oracle and the engine; both agree below)
        long[] adjacent = {14, 196, 4_648};
        long[] expectedLong = {PERFT_LONG_1, PERFT_LONG_2, PERFT_LONG_3};
        for (int depth = 1; depth <= 3; depth++) {
            assertEquals(adjacent[depth - 1], perft(Position.initial(ADJACENT), depth), "ADJACENT depth " + depth);
            assertEquals(expectedLong[depth - 1], perft(Position.initial(LONG), depth), "LONG depth " + depth);
            assertEquals(expectedLong[depth - 1], oraclePerft(Position.initial(LONG), depth), "oracle depth " + depth);
        }
    }

    private static final long PERFT_LONG_1 = 14;
    private static final long PERFT_LONG_2 = 196;
    private static final long PERFT_LONG_3 = 4_872;

    @Test
    void perftTableAfterThreeSeededPlies() {
        // per seed: legal moves, leaf count at depth 2 (computed with the oracle, which the engine must match)
        long[][] expected = {{26, 780}, {27, 945}, {27, 648}};
        long[] seeds = {1, 2, 3};
        for (int i = 0; i < seeds.length; i++) {
            Position pos = threePlies(LONG, seeds[i]);
            assertEquals(expected[i][0], pos.legalMoves().length, "moves, seed " + seeds[i]);
            assertEquals(expected[i][0], LongJumpOracle.moves(pos, true).size());
            assertEquals(expected[i][1], perft(pos, 2), "perft 2, seed " + seeds[i]);
            assertEquals(expected[i][1], oraclePerft(pos, 2));
        }
    }
}
