package sternhalma.nn;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sternhalma.engine.Geometry;
import sternhalma.engine.Layout;
import sternhalma.engine.Move;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.engine.RuleSet.TriangleRows;

/**
 * The input planes on the 5-row board (plan "neural networks for the 5-row board", sections 2.1, 2.2 and 2.4): planes 3
 * and 4 mark the 15-cell houses, planes 7 and 8 use the 5-row distances, plane 11 counts 15 pieces, plane 12 is 0
 * (decision D1), the sample expander equals the encoder, and a network reads a position only of a board size that its
 * metadata key {@code triangle_rows} lists (decision D2). The expected values come from {@link Layout} and
 * {@code Position.generate} of the engine, never from the encoder itself.
 */
class FiveRowsEncodingTest {

    private static final PlaneSet[] SETS = PlaneSet.values();

    private static List<Position> positions(int players, RuleSet.JumpRule jump, long seed) {
        return Playouts.positions(players, jump, TriangleRows.FIVE, 3, 160, seed);
    }

    private static float[] encode(PlaneSet planes, Position p) {
        float[] out = new float[planes.inputSize()];
        Encoder.encode(planes, p, out, 0);
        return out;
    }

    private static float plane(PlaneSet planes, float[] in, int canonicalCell, int plane) {
        return in[Encoder.index(planes.planes(), canonicalCell, plane)];
    }

    @Test
    void goalAndStartPlanesAreTheFifteenCellHousesOfTheMover() {
        for (int players : new int[] {2, 3}) {
            Layout layout = Layout.of(players, TriangleRows.FIVE);
            Set<Integer> movers = new HashSet<>();
            for (Position p : positions(players, RuleSet.JumpRule.ADJACENT, 11 + players)) {
                Orientation o = Orientation.of(p);
                int mover = p.sideToMove();
                movers.add(mover);
                for (PlaneSet planes : SETS) {
                    float[] in = encode(planes, p);
                    int goal = 0;
                    int start = 0;
                    for (int c = 0; c < Geometry.CELLS; c++) {
                        int row = o.toCanonical(c);
                        String where = planes.id() + ", " + players + " players, mover " + mover + ", cell " + c;
                        assertEquals(layout.inGoal(mover, c) ? 1f : 0f, plane(planes, in, row, Encoder.GOAL), where);
                        assertEquals(layout.inStart(mover, c) ? 1f : 0f, plane(planes, in, row, Encoder.START), where);
                        goal += plane(planes, in, row, Encoder.GOAL) == 1f ? 1 : 0;
                        start += plane(planes, in, row, Encoder.START) == 1f ? 1 : 0;
                    }
                    assertEquals(15, goal, "goal cells");
                    assertEquals(15, start, "start cells");
                }
            }
            assertEquals(players, movers.size(), "every mover is seen");
        }
    }

    @Test
    void distancePlanesAreThoseOfTheFiveRowLayoutAndBelowTheScale() {
        for (int players : new int[] {2, 3}) {
            Layout layout = Layout.of(players, TriangleRows.FIVE);
            for (int p = 0; p < players; p++) {
                assertTrue(layout.maxDistance(p) < ClassicalPlanes.DIST_SCALE, "max distance " + layout.maxDistance(p));
            }
            for (Position pos : positions(players, RuleSet.JumpRule.ADJACENT, 21 + players)) {
                Orientation o = Orientation.of(pos);
                int mover = pos.sideToMove();
                int next = (mover + 1) % players;
                float[] in = encode(PlaneSet.CLASSIC1, pos);
                for (int c = 0; c < Geometry.CELLS; c++) {
                    int row = o.toCanonical(c);
                    float own = layout.allowed(mover, c) ? (float) layout.distance(mover, c) / 16 : 0f;
                    float other = layout.allowed(next, c) ? (float) layout.distance(next, c) / 16 : 0f;
                    assertEquals(own, plane(PlaneSet.CLASSIC1, in, row, Encoder.DIST_OWN), "cell " + c);
                    assertEquals(other, plane(PlaneSet.CLASSIC1, in, row, Encoder.DIST_NEXT), "cell " + c);
                }
            }
        }
    }

    @Test
    void reachPlanesAreTheEndCellsOfTheLegalMovesOnFiveRows() {
        for (Position pos : positions(2, RuleSet.JumpRule.ADJACENT, 31)) {
            Orientation o = Orientation.of(pos);
            float[] in = encode(PlaneSet.CLASSIC1, pos);
            boolean[] own = new boolean[Geometry.CELLS];
            for (int m : pos.legalMoves()) {
                own[Move.to(m)] = true;
            }
            boolean[] other = new boolean[Geometry.CELLS];
            Position swapped = pos.transformed(c -> c, false).setSideToMove(1 - pos.sideToMove());
            for (int m : swapped.legalMoves()) {
                other[Move.to(m)] = true;
            }
            for (int c = 0; c < Geometry.CELLS; c++) {
                assertEquals(own[c] ? 1f : 0f, plane(PlaneSet.CLASSIC1, in, o.toCanonical(c), Encoder.REACH_OWN));
                assertEquals(other[c] ? 1f : 0f, plane(PlaneSet.CLASSIC1, in, o.toCanonical(c), Encoder.REACH_NEXT));
            }
        }
    }

    /** Both armies of 15 in their goal houses: every piece of player 0 is below every piece of player 1. */
    private static Position passedFive() {
        RuleSet rules = RuleSet.defaults(2, TriangleRows.FIVE);
        Layout layout = Layout.of(2, TriangleRows.FIVE);
        Position p = Position.empty(rules);
        for (int player = 0; player < 2; player++) {
            for (int c : layout.goalCells(player)) {
                p.put(player, c);
            }
        }
        return p.setSideToMove(0);
    }

    @Test
    void passedPlaneIsOneOnAFifteenPieceRaceAndZeroAtTheStart() {
        Position race = passedFive();
        assertEquals(15, race.pieceCount(0));
        assertTrue(ClassicalPlanes.passed(race));
        assertFalse(ClassicalPlanes.passed(Position.initial(RuleSet.defaults(2, TriangleRows.FIVE))));
        for (PlaneSet planes : new PlaneSet[] {PlaneSet.CLASSIC1, PlaneSet.CLASSIC1V}) {
            float[] in = encode(planes, race);
            for (int c = 0; c < Geometry.CELLS; c++) {
                assertEquals(1f, plane(planes, in, c, Encoder.PASSED), planes.id() + ", cell " + c);
            }
        }
    }

    @Test
    void evalPlaneIsZeroOnFiveRowsAndNotOnFour() {
        boolean fourNonZero = false;
        for (Position p : Playouts.positions(2, 2, 60, 41)) {
            fourNonZero |= plane(PlaneSet.CLASSIC1, encode(PlaneSet.CLASSIC1, p), 0, Encoder.EVAL) != 0f;
        }
        assertTrue(fourNonZero, "the 4-row eval plane is on");
        for (Position p : positions(2, RuleSet.JumpRule.ADJACENT, 42)) {
            for (PlaneSet planes : new PlaneSet[] {PlaneSet.CLASSIC1, PlaneSet.CLASSIC1V}) {
                float[] in = encode(planes, p);
                for (int c = 0; c < Geometry.CELLS; c++) {
                    assertEquals(0f, plane(planes, in, c, Encoder.EVAL), planes.id() + ", ply " + p.ply());
                }
            }
        }
    }

    private static byte[] cellsOf(Position p) {
        Orientation o = Orientation.of(p);
        byte[] cells = new byte[HexGrid.CELLS];
        for (int c = 0; c < HexGrid.CELLS; c++) {
            int owner = p.owner(c);
            cells[o.toCanonical(c)] = (byte) (owner < 0 ? 0 : 1 + o.relativePlayer(owner));
        }
        return cells;
    }

    private static float[] expand(PlaneSet planes, Position p, boolean mirror, int ply) {
        float[] out = new float[planes.inputSize()];
        SampleExpander.expand(planes, p.rules().players(), p.rules().jumpRule(), p.rules().triangleRows(), cellsOf(p),
                0, ply, p.rules().moveLimit(), mirror, out, 0);
        return out;
    }

    @Test
    void expansionEqualsTheEncoderOnFiveRows() {
        for (int players : new int[] {2, 3}) {
            for (RuleSet.JumpRule jump : RuleSet.JumpRule.values()) {
                for (Position p : positions(players, jump, 51 + players)) {
                    for (PlaneSet planes : SETS) {
                        assertArrayEquals(encode(planes, p), expand(planes, p, false, p.ply()),
                                planes.id() + ", " + players + " players, " + jump + ", ply " + p.ply());
                    }
                }
            }
        }
    }

    @Test
    void mirroredExpansionIsTheEncodingOfTheMirroredPositionOnFiveRows() {
        int asymmetric = 0;
        List<Position> ps = positions(2, RuleSet.JumpRule.ADJACENT, 61);
        for (Position p : ps) {
            Position mirrored = p.transformed(Geometry::mirror, false);
            for (PlaneSet planes : SETS) {
                // the transformed position has no history: ply 0
                assertArrayEquals(encode(planes, mirrored), expand(planes, p, true, 0),
                        planes.id() + ", ply " + p.ply());
            }
            if (!java.util.Arrays.equals(encode(PlaneSet.CLASSIC1, Playouts.atPlyZero(p)),
                    encode(PlaneSet.CLASSIC1, mirrored))) {
                asymmetric++;
            }
        }
        assertTrue(asymmetric > ps.size() / 2, "the test positions are mostly not mirror-symmetric: " + asymmetric);
    }

    // ---- decision D2: the metadata key triangle_rows ----

    @Test
    void aFourRowNetworkWritesNoKeyAndOthersWriteTheirSizes() {
        NetworkConfig four = NetworkConfig.tiny(2).withPlanes(PlaneSet.CLASSIC1);
        assertFalse(four.toMetadata().containsKey(NetworkConfig.ROWS_KEY));
        assertEquals(Set.of(TriangleRows.FOUR), four.rows());
        NetworkConfig five = four.withRows(EnumSet.of(TriangleRows.FIVE));
        assertEquals("5", five.toMetadata().get(NetworkConfig.ROWS_KEY));
        assertEquals(five, NetworkConfig.fromMetadata(five.toMetadata()));
        NetworkConfig both = four.withRows(EnumSet.of(TriangleRows.FIVE, TriangleRows.FOUR));
        assertEquals("4,5", both.toMetadata().get(NetworkConfig.ROWS_KEY));
        assertEquals(both, NetworkConfig.fromMetadata(both.toMetadata()));
        assertEquals(four, NetworkConfig.fromMetadata(four.toMetadata()));
    }

    @Test
    void theKeyAcceptsOnlyItsThreeTexts() {
        Map<String, String> base = NetworkConfig.tiny(2).withPlanes(PlaneSet.CLASSIC1).toMetadata();
        assertEquals(Set.of(TriangleRows.FOUR), NetworkConfig.fromMetadata(with(base, "4")).rows());
        for (String bad : new String[] {"", "5,4", "4,4", "6", " 5", "4, 5", "five"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> NetworkConfig.fromMetadata(with(base, bad)), bad);
            assertTrue(e.getMessage().contains(NetworkConfig.ROWS_KEY), e.getMessage());
        }
        assertThrows(IllegalArgumentException.class, () -> NetworkConfig.tiny(2).withRows(Set.of()));
    }

    private static Map<String, String> with(Map<String, String> m, String rows) {
        Map<String, String> copy = new java.util.LinkedHashMap<>(m);
        copy.put(NetworkConfig.ROWS_KEY, rows);
        return copy;
    }

    @Test
    void theKeySurvivesSaveAndLoad(@TempDir Path dir) throws IOException {
        NetworkConfig five = NetworkConfig.tiny(2).withPlanes(PlaneSet.CLASSIC1).withRows(EnumSet.of(TriangleRows.FIVE));
        Path file = dir.resolve("five.safetensors");
        Parameters.init(five, 3).save(file);
        Parameters loaded = Parameters.load(file);
        assertEquals(five, loaded.config());
        assertFalse(loaded.extraMetadata().containsKey(NetworkConfig.ROWS_KEY), "a config key, not extra metadata");
        assertTrue(Files.size(file) > 0);
    }

    @Test
    void aNetworkWithoutTheKeyRefusesAFiveRowPositionAndAFiveRowNetworkAFourRowOne() {
        NetworkConfig four = NetworkConfig.tiny(2).withPlanes(PlaneSet.CLASSIC1);
        Position fiveRows = Position.initial(RuleSet.defaults(2, TriangleRows.FIVE));
        Position fourRows = Position.initial(RuleSet.defaults(2));
        Evaluator fourNet = new Evaluator(new Network(Parameters.init(four, 1)));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> fourNet.evaluate(fiveRows));
        assertTrue(e.getMessage().contains("5-row") && e.getMessage().contains("triangle_rows=4"), e.getMessage());
        assertEquals(fourRows.legalMoves().length, fourNet.evaluate(fourRows).moves().length);

        Evaluator fiveNet = new Evaluator(new Network(Parameters.init(four.withRows(EnumSet.of(TriangleRows.FIVE)), 1)));
        assertEquals(fiveRows.legalMoves().length, fiveNet.evaluate(fiveRows).moves().length);
        e = assertThrows(IllegalArgumentException.class, () -> fiveNet.evaluate(fourRows));
        assertTrue(e.getMessage().contains("4-row") && e.getMessage().contains("triangle_rows=5"), e.getMessage());

        Evaluator both = new Evaluator(new Network(Parameters.init(
                four.withRows(EnumSet.of(TriangleRows.FOUR, TriangleRows.FIVE)), 1)));
        assertEquals(2, both.evaluate(List.of(fourRows, fiveRows)).size());
    }
}
