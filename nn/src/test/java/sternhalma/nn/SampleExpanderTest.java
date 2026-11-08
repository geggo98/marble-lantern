package sternhalma.nn;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import sternhalma.engine.Geometry;
import sternhalma.engine.Move;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;

/** Expansion of stored canonical cells into planes, and the mirror augmentation (docs/training.md). */
class SampleExpanderTest {

    /** The canonical cells of the sample file: 0 empty, 1 + relative player; built from the orientation. */
    static byte[] cellsOf(Position position) {
        Orientation o = Orientation.of(position);
        byte[] cells = new byte[HexGrid.CELLS];
        for (int c = 0; c < HexGrid.CELLS; c++) {
            int owner = position.owner(c);
            cells[o.toCanonical(c)] = (byte) (owner < 0 ? 0 : 1 + o.relativePlayer(owner));
        }
        return cells;
    }

    private static float[] expand(Position position, boolean mirror) {
        float[] out = new float[Encoder.INPUT_SIZE];
        SampleExpander.expand(position.rules().players(), cellsOf(position), 0, position.ply(),
                position.rules().moveLimit(), mirror, out, 0);
        return out;
    }

    private static float[] encode(Position position) {
        float[] out = new float[Encoder.INPUT_SIZE];
        Encoder.encode(position, out, 0);
        return out;
    }

    /** Rows of an encoded position permuted: row c goes to row mirror(c); the padding row stays. */
    private static float[] mirrorRows(float[] input) {
        float[] out = new float[input.length];
        for (int c = 0; c < HexGrid.CELLS; c++) {
            System.arraycopy(input, Encoder.index(c, 0), out, Encoder.index(Geometry.mirror(c), 0), Encoder.PLANES);
        }
        return out;
    }

    private static List<Position> positions(int players, long seed) {
        List<Position> all = Playouts.positions(players, 3, 160, seed);
        List<Position> picked = new ArrayList<>();
        for (int i = 0; i < all.size(); i += 3) {
            picked.add(all.get(i));
        }
        return picked;
    }

    @Test
    void expansionEqualsEncoderForEveryMoverOfTwoAndThreePlayers() {
        for (int players = 2; players <= 3; players++) {
            Set<Integer> movers = new HashSet<>();
            List<Position> ps = positions(players, 11L * players);
            assertTrue(ps.size() > 100);
            for (Position p : ps) {
                movers.add(p.sideToMove());
                assertArrayEquals(encode(p), expand(p, false), players + " players, ply " + p.ply());
            }
            assertEquals(players, movers.size(), "every mover occurs");
        }
    }

    @Test
    void progressPlaneSaturatesAtTheMoveLimit() {
        RuleSet rules = RuleSet.defaults(2).withMoveLimit(6);
        Position p = Position.initial(rules);
        Random random = new Random(3);
        for (int ply = 0; ply < 10; ply++) {
            int[] moves = p.legalMoves();
            p.make(moves[random.nextInt(moves.length)]);
            assertArrayEquals(encode(p), expand(p, false), "ply " + p.ply());
        }
        assertEquals(1f, expand(p, false)[Encoder.index(60, Encoder.PROGRESS)]);
    }

    @Test
    void everyFloatOfTheSliceIsWrittenAndOffsetsAreHonoured() {
        Position p = positions(2, 5).get(40);
        float[] expected = encode(p);
        float[] buffer = new float[3 * Encoder.INPUT_SIZE];
        Arrays.fill(buffer, 7f);
        SampleExpander.expand(2, cellsOf(p), 0, p.ply(), p.rules().moveLimit(), false, buffer, Encoder.INPUT_SIZE);
        assertArrayEquals(expected, Arrays.copyOfRange(buffer, Encoder.INPUT_SIZE, 2 * Encoder.INPUT_SIZE));
        assertEquals(7f, buffer[Encoder.INPUT_SIZE - 1], "before the slice");
        assertEquals(7f, buffer[2 * Encoder.INPUT_SIZE], "after the slice");
        // the cells may sit inside a longer array
        byte[] cells = new byte[5 + HexGrid.CELLS];
        System.arraycopy(cellsOf(p), 0, cells, 5, HexGrid.CELLS);
        float[] out = new float[Encoder.INPUT_SIZE];
        SampleExpander.expand(2, cells, 5, p.ply(), p.rules().moveLimit(), false, out, 0);
        assertArrayEquals(expected, out);
    }

    @Test
    void rejectsBadArguments() {
        float[] out = new float[Encoder.INPUT_SIZE];
        byte[] cells = new byte[HexGrid.CELLS];
        assertThrows(IllegalArgumentException.class, () -> SampleExpander.expand(4, cells, 0, 0, 10, false, out, 0));
        assertThrows(IllegalArgumentException.class, () -> SampleExpander.expand(2, cells, 0, -1, 10, false, out, 0));
        assertThrows(IllegalArgumentException.class, () -> SampleExpander.expand(2, cells, 0, 0, 0, false, out, 0));
        byte[] three = new byte[HexGrid.CELLS];
        three[7] = 3;
        assertThrows(IllegalArgumentException.class, () -> SampleExpander.expand(2, three, 0, 0, 10, false, out, 0),
                "relative player 2 does not exist with two players");
        SampleExpander.expand(3, three, 0, 0, 10, false, out, 0);
        assertThrows(IndexOutOfBoundsException.class,
                () -> SampleExpander.expand(2, cells, 0, 0, 10, false, new float[Encoder.INPUT_SIZE - 1], 0));
        assertThrows(IndexOutOfBoundsException.class,
                () -> SampleExpander.expand(2, new byte[HexGrid.CELLS - 1], 0, 0, 10, false, out, 0));
    }

    // ---- Symmetric augmentation, test (1): the encoding identity ----

    @Test
    void mirroredExpansionIsTheEncodingOfTheMirroredPositionWithEachMover() {
        Set<Integer> movers = new HashSet<>();
        int asymmetric = 0;
        List<Position> ps = positions(2, 21);
        for (Position p : ps) {
            movers.add(p.sideToMove());
            Position mirrored = p.transformed(Geometry::mirror, false);
            float[] viaRows = mirrorRows(encode(p));
            assertArrayEquals(viaRows, expand(p, true), "rows permuted, ply " + p.ply());
            // the transformed position has no history: ply 0, so compare at ply 0
            float[] atZero = new float[Encoder.INPUT_SIZE];
            SampleExpander.expand(2, cellsOf(p), 0, 0, p.rules().moveLimit(), true, atZero, 0);
            assertArrayEquals(encode(mirrored), atZero, "encoding of the mirrored position, ply " + p.ply());
            if (!Arrays.equals(encode(p), viaRows)) {
                asymmetric++;
            }
        }
        assertEquals(2, movers.size());
        assertTrue(asymmetric > ps.size() / 2, "the test positions are mostly not mirror-symmetric: " + asymmetric);
    }

    @Test
    void theMirrorTableIsAnInvolutionThatKeepsRowAndCorners() {
        int moved = 0;
        for (int c = 0; c < HexGrid.CELLS; c++) {
            int m = SampleExpander.mirrorCell(c);
            assertEquals(c, SampleExpander.mirrorCell(m));
            assertEquals(Geometry.r(c), Geometry.r(m));
            assertEquals(Geometry.s(c), Geometry.q(m));
            if (m != c) {
                moved++;
            }
        }
        assertTrue(moved > 90, "most cells are off the axis: " + moved);
    }

    // ---- test (2): the legal moves ----

    @Test
    void legalMovesOfTheMirroredPositionAreTheMirroredCanonicalMoves() {
        int checked = 0;
        for (Position p : positions(2, 31)) {
            if (p.legalMoves().length == 0) {
                continue;
            }
            Position mirrored = p.transformed(Geometry::mirror, false);
            Orientation o = Orientation.of(p);
            Set<Integer> expected = new HashSet<>();
            for (int move : p.legalMoves()) {
                int c = o.toCanonicalMove(move);
                expected.add(Move.of(SampleExpander.mirrorCell(Move.from(c)), SampleExpander.mirrorCell(Move.to(c))));
            }
            Orientation om = Orientation.of(mirrored);
            Set<Integer> actual = new HashSet<>();
            for (int move : mirrored.legalMoves()) {
                actual.add(om.toCanonicalMove(move));
            }
            assertEquals(expected, actual, "ply " + p.ply());
            checked++;
        }
        assertTrue(checked > 100);
    }

    // ---- test (3): the loss ----

    /** One-sample batch of a position, its legal moves with fixed random targets, optionally mirrored. */
    private static TrainingBatch sample(Position p, boolean mirror) {
        Orientation o = Orientation.of(p);
        int[] moves = p.legalMoves();
        int[] from = new int[moves.length];
        int[] to = new int[moves.length];
        double[] target = new double[moves.length];
        Random random = new Random(p.hashCode());
        for (int m = 0; m < moves.length; m++) {
            int c = o.toCanonicalMove(moves[m]);
            from[m] = mirror ? SampleExpander.mirrorCell(Move.from(c)) : Move.from(c);
            to[m] = mirror ? SampleExpander.mirrorCell(Move.to(c)) : Move.to(c);
            target[m] = 0.05 + random.nextDouble();
        }
        float[] z = {0.7f, -0.7f};
        return new TrainingBatch.Builder(2).add(expand(p, mirror), from, to, target, z, 1f, 1f).build();
    }

    /**
     * The network averaged over the mirror: every hex convolution tap k gets the mean of itself and of the tap that
     * the mirror maps it to. The other layers act per cell or on the mean over cells and need no change.
     */
    static Parameters mirrorSymmetric(Parameters parameters) {
        int[] tap = mirrorTaps();
        Map<String, Tensor> tensors = new LinkedHashMap<>();
        for (Map.Entry<String, Tensor> e : parameters.tensors().entrySet()) {
            Tensor t = e.getValue();
            float[] data = t.data().clone();
            if (e.getKey().endsWith(".weight") && t.shape()[1] % HexGrid.STENCIL == 0
                    && !e.getKey().startsWith("head")) {
                int width = t.shape()[1];
                int in = width / HexGrid.STENCIL;
                for (int o = 0; o < t.shape()[0]; o++) {
                    for (int k = 0; k < HexGrid.STENCIL; k++) {
                        for (int ch = 0; ch < in; ch++) {
                            data[o * width + k * in + ch] = 0.5f * (t.data()[o * width + k * in + ch]
                                    + t.data()[o * width + tap[k] * in + ch]);
                        }
                    }
                }
            }
            tensors.put(e.getKey(), new Tensor(e.getKey(), t.shape(), data));
        }
        return Parameters.of(parameters.config(), tensors);
    }

    /** Stencil entry k (0 = the cell, 1 to 6 = Geometry direction k - 1) to the entry that the mirror maps it to. */
    static int[] mirrorTaps() {
        int[] tap = new int[HexGrid.STENCIL];
        for (int k = 1; k < HexGrid.STENCIL; k++) {
            tap[k] = -1;
            for (int k2 = 1; k2 < HexGrid.STENCIL; k2++) {
                boolean all = true;
                for (int c = 0; c < HexGrid.CELLS; c++) {
                    int n = Geometry.neighbor(c, k - 1);
                    int image = Geometry.neighbor(Geometry.mirror(c), k2 - 1);
                    all &= n < 0 ? image < 0 : image == Geometry.mirror(n);
                }
                if (all) {
                    tap[k] = k2;
                }
            }
            assertTrue(tap[k] > 0, "the mirror maps direction " + (k - 1) + " to a direction");
        }
        return tap;
    }

    @Test
    void mirrorChangesTheLossForRandomWeightsButNotForAMirrorSymmetricNetwork() {
        Parameters random = Parameters.init(NetworkConfig.tiny(2), 77);
        Parameters symmetric = mirrorSymmetric(random);
        assertFalse(Arrays.equals(random.get("block0.conv1.weight").data(), symmetric.get("block0.conv1.weight").data()),
                "symmetrising changes the weights");
        int differs = 0;
        int checked = 0;
        double worstSymmetric = 0;
        for (Position p : positions(2, 41)) {
            if (p.legalMoves().length == 0 || p.ply() < 6) {
                continue;
            }
            double plain = loss(random, sample(p, false));
            double mirrored = loss(random, sample(p, true));
            if (Math.abs(plain - mirrored) > 1e-3 * Math.abs(plain)) {
                differs++;
            }
            double a = loss(symmetric, sample(p, false));
            double b = loss(symmetric, sample(p, true));
            worstSymmetric = Math.max(worstSymmetric, Math.abs(a - b) / Math.abs(a));
            checked++;
        }
        assertTrue(checked > 50);
        assertTrue(differs > checked * 0.8, "random weights: loss differs on the mirror in " + differs + " of " + checked);
        assertTrue(worstSymmetric < 1e-5, "mirror-symmetric network: relative loss difference " + worstSymmetric);
    }

    private static double loss(Parameters parameters, TrainingBatch batch) {
        return new ReferenceTrainer(parameters, 1.0).accumulate(batch, 1, 1).loss();
    }

    // ---- test (4): three players ----

    @Test
    void threePlayersAreNeverMirrored() {
        Position p = positions(3, 51).get(30);
        float[] out = new float[Encoder.INPUT_SIZE];
        assertThrows(IllegalArgumentException.class,
                () -> SampleExpander.expand(3, cellsOf(p), 0, p.ply(), p.rules().moveLimit(), true, out, 0));
    }
}
