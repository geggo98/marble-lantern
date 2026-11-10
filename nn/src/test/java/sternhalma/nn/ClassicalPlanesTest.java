package sternhalma.nn;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import sternhalma.engine.Geometry;
import sternhalma.engine.Layout;
import sternhalma.engine.Move;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;

/**
 * The classical input planes of {@link PlaneSet#CLASSIC1} (docs/network.md, "Classical planes and residual priors"):
 * hand-checked cases, an independent oracle for distance and reachability, the identity of the live encoder and the
 * sample expander for every mover, and the mirror.
 */
class ClassicalPlanesTest {

    private static final PlaneSet C1 = PlaneSet.CLASSIC1;

    private static float[] encode(PlaneSet planes, Position position) {
        float[] out = new float[planes.inputSize()];
        Arrays.fill(out, Float.NaN);
        Encoder.encode(planes, position, out, 0);
        return out;
    }

    private static float plane(float[] in, int row, int plane) {
        return in[Encoder.index(Encoder.CLASSIC_PLANES, row, plane)];
    }

    private static Set<Integer> rowsWith(float[] in, int plane) {
        Set<Integer> rows = new HashSet<>();
        for (int c = 0; c < HexGrid.CELLS; c++) {
            if (plane(in, c, plane) != 0f) {
                rows.add(c);
            }
        }
        return rows;
    }

    /** Every one of the 121 cell rows of the plane is exactly 1f inside {@code members} and exactly 0f outside. */
    private static void assertExactPlane(float[] in, int plane, Set<Integer> members, String what) {
        for (int c = 0; c < HexGrid.CELLS; c++) {
            float expected = members.contains(c) ? 1f : 0f;
            assertEquals(expected, plane(in, c, plane), 0f, what + ", row " + c);
        }
    }

    private static List<Position> positions(int players, long seed) {
        List<Position> all = Playouts.positions(players, 3, 160, seed);
        List<Position> picked = new ArrayList<>();
        for (int i = 0; i < all.size(); i += 3) {
            picked.add(all.get(i));
        }
        return picked;
    }

    /** Two players: every piece of player 0 on the rows 1 to 4 of the central hexagon, of player 1 on -4 to -1. */
    private static Position passedPosition(int mover) {
        Layout layout = Layout.of(2);
        Position p = Position.empty(RuleSet.defaults(2));
        int[] count = new int[2];
        for (int c = 0; c < Geometry.CELLS; c++) {
            if (Geometry.cornerOf(c) >= 0 || !layout.allowed(0, c)) {
                continue;
            }
            if (Geometry.r(c) >= 1 && Geometry.r(c) <= 4 && count[0] < 10) {
                p.put(0, c);
                count[0]++;
            } else if (Geometry.r(c) <= -1 && Geometry.r(c) >= -4 && count[1] < 10) {
                p.put(1, c);
                count[1]++;
            }
        }
        assertEquals(10, count[0]);
        assertEquals(10, count[1]);
        return p.setSideToMove(mover);
    }

    // ---- constants and layout ----

    @Test
    void theClassicalPlanesFollowThePlainOnesAndTheSetsAgreeWithTheirSizes() {
        assertEquals(List.of(7, 8, 9, 10, 11, 12), List.of(Encoder.DIST_OWN, Encoder.DIST_NEXT, Encoder.REACH_OWN,
                Encoder.REACH_NEXT, Encoder.PASSED, Encoder.EVAL));
        assertEquals(13, Encoder.CLASSIC_PLANES);
        assertEquals(122 * 13, C1.inputSize());
        assertEquals(Encoder.INPUT_SIZE, PlaneSet.PLAIN.inputSize());
        assertTrue(C1.residualPriors());
        assertFalse(PlaneSet.CLASSIC1_PLANES.residualPriors());
        assertFalse(PlaneSet.PLAIN.classical());
        assertEquals(3 * 13 + 4, Encoder.index(13, 3, 4));
        for (PlaneSet set : PlaneSet.values()) {
            assertEquals(set, PlaneSet.ofId(set.id()));
        }
        assertThrows(IllegalArgumentException.class, () -> PlaneSet.ofId("classic2"));
    }

    @Test
    void thePlainPlanesOfAClassicalEncodingEqualThePlainEncoding() {
        for (int players = 2; players <= 3; players++) {
            for (Position p : positions(players, 5 + players)) {
                float[] plain = encode(PlaneSet.PLAIN, p);
                float[] classic = encode(C1, p);
                for (int row = 0; row < HexGrid.ROWS; row++) {
                    for (int plane = 0; plane < Encoder.PLANES; plane++) {
                        assertEquals(plain[Encoder.index(row, plane)], plane(classic, row, plane),
                                players + " players, row " + row + ", plane " + plane);
                    }
                }
            }
        }
    }

    @Test
    void everyFloatIsWrittenTheOffsetIsHonouredAndThePaddingRowIsZero() {
        Position p = positions(2, 3).get(30);
        float[] expected = encode(C1, p);
        for (float v : expected) {
            assertFalse(Float.isNaN(v), "every float of the slice is written");
        }
        float[] buffer = new float[3 * C1.inputSize()];
        Arrays.fill(buffer, 7f);
        Encoder.encode(C1, p, buffer, C1.inputSize());
        assertArrayEquals(expected, Arrays.copyOfRange(buffer, C1.inputSize(), 2 * C1.inputSize()));
        assertEquals(7f, buffer[C1.inputSize() - 1]);
        assertEquals(7f, buffer[2 * C1.inputSize()]);
        for (int plane = 0; plane < Encoder.CLASSIC_PLANES; plane++) {
            assertEquals(0f, plane(expected, HexGrid.PADDING, plane), "padding row, plane " + plane);
        }
        assertThrows(IndexOutOfBoundsException.class, () -> Encoder.encode(C1, p, new float[C1.inputSize() - 1], 0));
        // the plain encoding must not need the room of the classical one
        Encoder.encode(PlaneSet.PLAIN, p, new float[Encoder.INPUT_SIZE], 0);
    }

    // ---- distance planes ----

    /** Breadth-first distance to the goal corner over the cells the player may stand on; written for this test. */
    private static int[] distances(int players, int player) {
        Layout layout = Layout.of(players);
        int[] dist = new int[Geometry.CELLS];
        Arrays.fill(dist, -1);
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int c = 0; c < Geometry.CELLS; c++) {
            if (Geometry.cornerOf(c) == layout.goalCorner(player)) {
                dist[c] = 0;
                queue.add(c);
            }
        }
        while (!queue.isEmpty()) {
            int c = queue.poll();
            for (int d = 0; d < 6; d++) {
                int n = Geometry.neighbor(c, d);
                if (n >= 0 && layout.allowed(player, n) && dist[n] < 0) {
                    dist[n] = dist[c] + 1;
                    queue.add(n);
                }
            }
        }
        return dist;
    }

    @Test
    void distancePlanesAreTheSingleAgentDistanceOfTheMoverAndOfTheNextPlayerInCanonicalCells() {
        for (int players = 2; players <= 3; players++) {
            for (Position p : positions(players, 17 + players)) {
                float[] in = encode(C1, p);
                Orientation o = Orientation.of(p);
                int[] own = distances(players, p.sideToMove());
                int[] next = distances(players, (p.sideToMove() + 1) % players);
                Layout layout = Layout.of(players);
                for (int c = 0; c < HexGrid.CELLS; c++) {
                    int row = o.toCanonical(c);
                    float expectedOwn = layout.allowed(p.sideToMove(), c) ? own[c] / 16f : 0f;
                    float expectedNext = layout.allowed((p.sideToMove() + 1) % players, c) ? next[c] / 16f : 0f;
                    assertEquals(expectedOwn, plane(in, row, Encoder.DIST_OWN), "own, cell " + c);
                    assertEquals(expectedNext, plane(in, row, Encoder.DIST_NEXT), "next, cell " + c);
                }
            }
        }
    }

    @Test
    void distancePlanesInCanonicalOrientationDoNotDependOnTheMover() {
        for (int players = 2; players <= 3; players++) {
            float[][] first = new float[players][];
            for (Position p : positions(players, 29 + players)) {
                float[] in = encode(C1, p);
                int m = p.sideToMove();
                float[] both = new float[2 * HexGrid.ROWS];
                for (int row = 0; row < HexGrid.ROWS; row++) {
                    both[row] = plane(in, row, Encoder.DIST_OWN);
                    both[HexGrid.ROWS + row] = plane(in, row, Encoder.DIST_NEXT);
                }
                if (first[m] == null) {
                    first[m] = both;
                } else {
                    assertArrayEquals(first[m], both, players + " players, mover " + m);
                }
            }
            for (int m = 1; m < players; m++) {
                assertArrayEquals(first[0], first[m], "mover " + m + " sees the same distance planes as mover 0");
            }
        }
    }

    @Test
    void distancePlanesByHand() {
        float[] in = encode(C1, Position.initial(RuleSet.defaults(2)));
        // the goal corner is R_POS, cells 111 to 120: distance 0; the start corner is the farthest region
        for (int c = 111; c < 121; c++) {
            assertEquals(0f, plane(in, c, Encoder.DIST_OWN));
        }
        // the centre is 5 rows away from row r = 5, where the goal corner begins
        assertEquals(5f / 16f, plane(in, 60, Encoder.DIST_OWN));
        // the next player's goal is the mover's start corner
        for (int c = 0; c < 10; c++) {
            assertEquals(0f, plane(in, c, Encoder.DIST_NEXT));
        }
        assertEquals(5f / 16f, plane(in, 60, Encoder.DIST_NEXT));
        // the tip of the mover's start corner is the farthest cell of the board from the goal
        float max = 0f;
        for (int c = 0; c < HexGrid.CELLS; c++) {
            max = Math.max(max, plane(in, c, Encoder.DIST_OWN));
        }
        assertEquals(max, plane(in, 0, Encoder.DIST_OWN));
        assertEquals(13f / 16f, max, "the tip is 13 rows from the goal corner");
        assertTrue(plane(in, 0, Encoder.DIST_OWN) > plane(in, 60, Encoder.DIST_OWN));
    }

    // ---- reachability planes ----

    @Test
    void reachPlanesByHandForLonePieces() {
        Position p = Position.empty(RuleSet.defaults(2)).put(0, 60).put(1, 120);
        float[] in = encode(C1, p);
        Set<Integer> own = new HashSet<>();
        for (int d = 0; d < 6; d++) {
            own.add(Geometry.neighbor(60, d));
        }
        assertEquals(own, rowsWith(in, Encoder.REACH_OWN), "mover on the centre: its six neighbours");
        Set<Integer> next = new HashSet<>();
        for (int d = 0; d < 6; d++) {
            int n = Geometry.neighbor(120, d);
            if (n >= 0) {
                next.add(n);
            }
        }
        assertEquals(2, next.size(), "a corner tip has two neighbours");
        assertEquals(next, rowsWith(in, Encoder.REACH_NEXT), "the side not to move is generated with swapped sides");
        assertExactPlane(in, Encoder.REACH_OWN, own, "by hand, own");
        assertExactPlane(in, Encoder.REACH_NEXT, next, "by hand, next");

        // The same pieces with player 1 to move: canonical cells 120 -> 0 (own piece), 60 -> 60 (next player's).
        Position q = Position.empty(RuleSet.defaults(2)).put(0, 60).put(1, 120).setSideToMove(1);
        float[] flipped = encode(C1, q);
        Set<Integer> ownFlipped = new HashSet<>();
        for (int d = 0; d < 6; d++) {
            int n = Geometry.neighbor(0, d);
            if (n >= 0) {
                ownFlipped.add(n);
            }
        }
        assertEquals(ownFlipped, rowsWith(flipped, Encoder.REACH_OWN));
        assertEquals(own, rowsWith(flipped, Encoder.REACH_NEXT));
        assertExactPlane(flipped, Encoder.REACH_OWN, ownFlipped, "by hand, flipped own");
        assertExactPlane(flipped, Encoder.REACH_NEXT, own, "by hand, flipped next");
    }

    @Test
    void reachPlanesContainJumpChainsAndNoOccupiedCells() {
        // Mover's piece on the centre with a hurdle at (1, 0) and another at (3, 0)... : the chain 0 -> 2 -> 4 needs
        // hurdles at (1, 0) and (3, 0); the landings (2, 0) and (4, 0) are both reachable by one move.
        Position p = Position.empty(RuleSet.defaults(2)).put(0, 0, 0).put(1, 1, 0).put(1, 3, 0).put(1, -4, 8);
        float[] in = encode(C1, p);
        Set<Integer> reach = rowsWith(in, Encoder.REACH_OWN);
        assertTrue(reach.contains(Geometry.id(2, 0)), "single jump");
        assertTrue(reach.contains(Geometry.id(4, 0)), "chain of two jumps");
        assertFalse(reach.contains(Geometry.id(1, 0)), "an occupied cell is no landing");
        assertFalse(reach.contains(Geometry.id(3, 0)), "an occupied cell is no landing");
        assertTrue(reach.contains(Geometry.id(-1, 0)), "a plain step");
    }

    /** Landing cells of the legal moves of a player on a position without goal-corner pieces; written for this test. */
    private static Set<Integer> landingsOracle(Position p, int player) {
        Layout layout = Layout.of(2);
        Set<Integer> landings = new HashSet<>();
        for (int piece : p.pieces(player)) {
            for (int d = 0; d < 6; d++) {
                int n = Geometry.neighbor(piece, d);
                if (n >= 0 && layout.allowed(player, n) && p.owner(n) < 0) {
                    landings.add(n);
                }
            }
            Set<Integer> seen = new HashSet<>(List.of(piece));
            ArrayDeque<Integer> queue = new ArrayDeque<>(List.of(piece));
            while (!queue.isEmpty()) {
                int c = queue.poll();
                for (int d = 0; d < 6; d++) {
                    int hurdle = Geometry.neighbor(c, d);
                    int land = Geometry.jumpLanding(c, d);
                    boolean hurdleOccupied = hurdle >= 0 && p.owner(hurdle) >= 0 && hurdle != piece;
                    if (land >= 0 && hurdleOccupied && layout.allowed(player, land)
                            && (p.owner(land) < 0 || land == piece) && seen.add(land)) {
                        queue.add(land);
                        landings.add(land);
                    }
                }
            }
        }
        return landings;
    }

    @Test
    void reachPlanesEqualAnIndependentLandingOracleForBothSides() {
        int checked = 0;
        for (Position p : positions(2, 41)) {
            int me = p.sideToMove();
            boolean plain = true;
            for (int player = 0; player < 2; player++) {
                for (int cell : p.pieces(player)) {
                    plain &= Geometry.cornerOf(cell) != Layout.of(2).goalCorner(player);
                }
            }
            if (!plain) {
                continue; // a piece in its goal corner may not leave it: the oracle does not know that rule
            }
            float[] in = encode(C1, p);
            Orientation o = Orientation.of(p);
            Set<Integer> own = new HashSet<>();
            for (int c : landingsOracle(p, me)) {
                own.add(o.toCanonical(c));
            }
            Set<Integer> next = new HashSet<>();
            for (int c : landingsOracle(p, 1 - me)) {
                next.add(o.toCanonical(c));
            }
            assertEquals(own, rowsWith(in, Encoder.REACH_OWN), "own, ply " + p.ply());
            assertEquals(next, rowsWith(in, Encoder.REACH_NEXT), "next, ply " + p.ply());
            // membership is not enough: the value inside the set is exactly 1f and outside exactly 0f (a plane written
            // as 0.5f passes the membership test above)
            assertExactPlane(in, Encoder.REACH_OWN, own, "own, ply " + p.ply());
            assertExactPlane(in, Encoder.REACH_NEXT, next, "next, ply " + p.ply());
            checked++;
        }
        assertTrue(checked > 80, "positions checked: " + checked);
    }

    @Test
    void reachOwnIsTheSetOfEndCellsOfTheLegalMovesAlsoUnderTheLeaveGoalRule() {
        // Pieces in the goal corner may not leave it (default rules): a position with such pieces
        Position p = passedPosition(0);
        Position q = Position.initial(RuleSet.defaults(2));
        for (Position position : List.of(p, q)) {
            float[] in = encode(C1, position);
            Orientation o = Orientation.of(position);
            Set<Integer> ends = new HashSet<>();
            for (int move : position.legalMoves()) {
                ends.add(o.toCanonical(Move.to(move)));
            }
            assertEquals(ends, rowsWith(in, Encoder.REACH_OWN));
            assertExactPlane(in, Encoder.REACH_OWN, ends, "ends of the legal moves, ply " + position.ply());
        }
    }

    // ---- passed flag ----

    @Test
    void passedFlagIsBroadcastForAPassedPositionAndOnlyThen() {
        for (int mover = 0; mover < 2; mover++) {
            Position passed = passedPosition(mover);
            assertTrue(ClassicalPlanes.passed(passed));
            float[] in = encode(C1, passed);
            for (int c = 0; c < HexGrid.CELLS; c++) {
                assertEquals(1f, plane(in, c, Encoder.PASSED), "mover " + mover + ", row " + c);
            }
        }
        float[] start = encode(C1, Position.initial(RuleSet.defaults(2)));
        for (int c = 0; c < HexGrid.CELLS; c++) {
            assertEquals(0f, plane(start, c, Encoder.PASSED));
        }
        // one piece of player 0 behind a piece of player 1 breaks it; so does a missing piece
        Position unpassed = Position.empty(RuleSet.defaults(2));
        Position pp = passedPosition(0);
        for (int player = 0; player < 2; player++) {
            for (int c : pp.pieces(player)) {
                unpassed.put(player, c);
            }
        }
        int low = Arrays.stream(pp.pieces(0)).min().getAsInt();
        assertTrue(ClassicalPlanes.passed(unpassed));
        Position swapped = Position.empty(RuleSet.defaults(2));
        for (int c : pp.pieces(1)) {
            swapped.put(1, c);
        }
        for (int c : pp.pieces(0)) {
            if (c != low) {
                swapped.put(0, c);
            }
        }
        assertFalse(ClassicalPlanes.passed(swapped), "nine pieces are not passed");
        // the boundary: player 0's rearmost row equal to player 1's rearmost row is not passed
        Position tie = Position.empty(RuleSet.defaults(2));
        for (int c : pp.pieces(1)) {
            tie.put(1, c);
        }
        int top1 = Arrays.stream(pp.pieces(1)).max().getAsInt();
        int tied = -1;
        for (int c = 0; c < Geometry.CELLS && tied < 0; c++) {
            if (Geometry.r(c) == Geometry.r(top1) && c != top1 && tie.owner(c) < 0
                    && Layout.of(2).allowed(0, c)) {
                tied = c;
            }
        }
        for (int c : pp.pieces(0)) {
            tie.put(0, c == low ? tied : c);
        }
        assertFalse(ClassicalPlanes.passed(tie), "equal rows are not passed");
        for (Position threePlayers : positions(3, 3)) {
            assertFalse(ClassicalPlanes.passed(threePlayers));
        }
    }

    // ---- evaluation plane ----

    @Test
    void evalPlaneIsTheLinearLogitOfTheMoverInEveryCellRowAndZeroForThreePlayers() {
        int nonZero = 0;
        for (Position p : positions(2, 53)) {
            float[] in = encode(C1, p);
            float expected = (float) ClassicalPlanes.linearLogit(p);
            for (int c = 0; c < HexGrid.CELLS; c++) {
                assertEquals(expected, plane(in, c, Encoder.EVAL), "row " + c);
            }
            if (expected != 0f) {
                nonZero++;
            }
        }
        assertTrue(nonZero > 50);
        for (Position p : positions(3, 54)) {
            float[] in = encode(C1, p);
            for (int c = 0; c < HexGrid.CELLS; c++) {
                assertEquals(0f, plane(in, c, Encoder.EVAL));
            }
        }
        assertThrows(IllegalArgumentException.class, () -> ClassicalPlanes.linearLogit(positions(3, 1).get(5)));
    }

    @Test
    void theLinearLogitIsTheSameFromEachPlayersSeat() {
        // The position seen from the other seat: rotate by 180 degrees and swap the players. Nothing changes for the mover.
        int checked = 0;
        for (Position p : positions(2, 61)) {
            Position swapped = p.transformed(Geometry::rotate180, true);
            assertEquals(ClassicalPlanes.linearLogit(p), ClassicalPlanes.linearLogit(swapped), 1e-12, "ply " + p.ply());
            checked++;
        }
        assertTrue(checked > 80);
        // but the side to move matters (tempo and the sign of every difference)
        Position p = positions(2, 62).get(40);
        Position other = p.transformed(cell -> cell, false).setSideToMove(1 - p.sideToMove());
        assertNotEquals(ClassicalPlanes.linearLogit(p), ClassicalPlanes.linearLogit(other));
    }

    @Test
    void theLinearLogitOfTheStartPositionIsThePureTempoTerm() {
        // 28.2 % tempo weight; at the start both sides are equal, so only the constant feature is left
        double[] w = ClassicalPlanes.linearWeights();
        assertEquals(12, w.length);
        assertEquals(w[11], ClassicalPlanes.linearLogit(Position.initial(RuleSet.defaults(2))), 1e-12);
    }

    // ---- identity of the live encoder and the sample expander ----

    private static float[] expand(PlaneSet planes, Position position, boolean mirror, int ply) {
        float[] out = new float[planes.inputSize()];
        Arrays.fill(out, Float.NaN);
        SampleExpander.expand(planes, position.rules().players(), SampleExpanderTest.cellsOf(position), 0, ply,
                position.rules().moveLimit(), mirror, out, 0);
        return out;
    }

    @Test
    void expansionEqualsTheEncoderForEveryMoverOfTwoAndThreePlayers() {
        for (int players = 2; players <= 3; players++) {
            Set<Integer> movers = new HashSet<>();
            List<Position> ps = new ArrayList<>(positions(players, 71 * players));
            if (players == 2) {
                ps.add(passedPosition(0));
                ps.add(passedPosition(1));
            }
            assertTrue(ps.size() > 100);
            for (Position p : ps) {
                movers.add(p.sideToMove());
                assertArrayEquals(encode(C1, p), expand(C1, p, false, p.ply()), players + " players, ply " + p.ply());
                assertArrayEquals(encode(PlaneSet.CLASSIC1_PLANES, p), expand(PlaneSet.CLASSIC1_PLANES, p, false, p.ply()));
            }
            assertEquals(players, movers.size(), "every mover occurs");
        }
    }

    @Test
    void mirroredExpansionIsTheEncodingOfTheMirroredPositionAndTheRowsPermuted() {
        Set<Integer> movers = new HashSet<>();
        int asymmetric = 0;
        List<Position> ps = new ArrayList<>(positions(2, 83));
        ps.add(passedPosition(0));
        ps.add(passedPosition(1));
        for (Position p : ps) {
            movers.add(p.sideToMove());
            float[] plain = encode(C1, p);
            float[] rows = new float[plain.length];
            for (int c = 0; c < HexGrid.CELLS; c++) {
                System.arraycopy(plain, Encoder.index(13, c, 0), rows, Encoder.index(13, Geometry.mirror(c), 0), 13);
            }
            assertArrayEquals(rows, expand(C1, p, true, p.ply()), "rows permuted, ply " + p.ply());
            Position mirrored = p.transformed(Geometry::mirror, false);
            assertArrayEquals(encode(C1, mirrored), expand(C1, p, true, 0), "mirrored position, ply " + p.ply());
            if (!Arrays.equals(plain, rows)) {
                asymmetric++;
            }
        }
        assertEquals(2, movers.size());
        assertTrue(asymmetric > ps.size() / 2);
    }

    @Test
    void theExpanderRejectsAPieceOnACellItsOwnerMayNotEnter() {
        // relative player 0 on a cell of the side corner R_... that does not exist in the two-player layout
        byte[] cells = new byte[HexGrid.CELLS];
        int bad = -1;
        for (int c = 0; c < HexGrid.CELLS && bad < 0; c++) {
            if (!Layout.of(2).allowed(0, c)) {
                bad = c;
            }
        }
        assertTrue(bad >= 0);
        cells[bad] = 1;
        float[] out = new float[C1.inputSize()];
        assertThrows(IllegalArgumentException.class, () -> SampleExpander.expand(C1, 2, cells, 0, 0, 200, false, out, 0));
    }
}
