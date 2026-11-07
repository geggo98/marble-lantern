package sternhalma.nn;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import sternhalma.engine.Position;

/**
 * {@link VectorNetwork} against the oracle {@link Network}.
 *
 * <p><b>Tolerance.</b> Every policy logit and value must satisfy {@link Network#agrees}:
 * {@code |fast - ref| <= 1e-4 + 1e-3 |ref|} (docs/network.md, "Numeric conventions"). The vector path sums
 * in float with fused multiply-adds, the reference in double, so the two differ by the float rounding of each
 * sum: at most about n 2^-24 of the sum of the absolute terms, with n &lt;= 7 * 64 = 448 terms in LARGE, and
 * this error is carried through up to 15 layers. With activations of order 1 that stays well inside the
 * bound. The tests publish the largest error they see, as a fraction of the bound, through the
 * {@link TestReporter}.
 *
 * <p><b>Weights.</b> The documented bound is relative to each output, but the rounding error is relative to
 * the size of the terms of the sum. It therefore holds only while the activations stay of order 1, that is
 * for weights of the size of {@link Parameters#init}: measured largest error / bound 0.19 for LARGE with init
 * weights. With {@code Parameters.uniform(config, seed, 0.5f)} the activations grow by a factor of about 2
 * (SMALL) to 5 (LARGE) per layer, LARGE logits reach 3e8, and a small logit is the difference of huge terms;
 * there the error is about 1e-6 of the largest logit of the position, and the documented bound fails for
 * SMALL and LARGE (measured: error / bound up to 1.03). So the tests use two kinds of weights:
 * <ul>
 *   <li>{@link Parameters#init}, and {@link Parameters#uniform} with the init bound of the trunk,
 *       {@code sqrt(6 / 7C)}, which also draws the biases, so that a lost bias shows: documented bound;</li>
 *   <li>{@link Parameters#uniform} with bound 0.5 (exploding activations): documented bound plus
 *       {@link #SCALE_TOLERANCE} times the largest |logit| of the position for the policy.</li>
 * </ul>
 * Either bound is far below what a plausible bug causes: a wrong gather entry, a lost tail lane or a missing
 * ReLU changes outputs by about the size of the outputs themselves.
 *
 * <p>The inputs are encoded positions from seeded random games and random planes in [0, 1).
 */
class VectorNetworkTest {

    /**
     * Extra policy tolerance in the tests with exploding activations, relative to the largest |logit| of the
     * same position. Measured on Apple M3 (4 lanes): at most 2.7e-6, so this leaves a margin of about 4.
     */
    private static final double SCALE_TOLERANCE = 1e-5;

    /** Error statistics of the comparisons of one test, as a fraction of the allowed bound. */
    private static final class Errors {
        double maxAbs;
        double maxRatio;
        long compared;

        /** Checks the documented bound plus {@code extra}; extra is 0 except with exploding activations. */
        void add(float fast, float ref, double extra, String what) {
            double d = Math.abs((double) fast - ref);
            double bound = Network.ABSOLUTE_TOLERANCE + Network.RELATIVE_TOLERANCE * Math.abs(ref) + extra;
            assertTrue(extra == 0 ? Network.agrees(fast, ref) : d <= bound,
                    what + ": fast " + fast + ", reference " + ref + ", allowed error " + bound);
            maxAbs = Math.max(maxAbs, d);
            maxRatio = Math.max(maxRatio, d / bound);
            compared++;
        }

        @Override
        public String toString() {
            return String.format("%d outputs, largest |error| %.3g, largest error / bound %.3g", compared, maxAbs,
                    maxRatio);
        }
    }

    /** Weights of the size of {@link Parameters#init}, biases included. */
    private static Parameters initScale(NetworkConfig config, long seed) {
        return Parameters.uniform(config, seed, (float) Math.sqrt(6.0 / (7 * config.channels())));
    }

    /** Input planes of encoded random-game positions, then random planes, {@code batch} positions in all. */
    private static float[] inputs(int players, int batch, long seed) {
        float[] input = new float[batch * Encoder.INPUT_SIZE];
        List<Position> positions = Playouts.positions(players, 2, 60, seed);
        Random random = new Random(seed);
        for (int b = 0; b < batch; b++) {
            int offset = b * Encoder.INPUT_SIZE;
            if (b % 2 == 0) {
                Encoder.encode(positions.get(random.nextInt(positions.size())), input, offset);
            } else {
                for (int i = 0; i < HexGrid.CELLS * Encoder.PLANES; i++) {
                    input[offset + i] = random.nextFloat();
                }
            }
        }
        return input;
    }

    /**
     * Runs both paths on the same input and compares every output.
     *
     * @param scaleTolerance 0 for the documented bound, else the extra policy tolerance relative to the largest
     *                       |logit| of the position
     */
    private static Errors compare(Parameters parameters, float[] input, int batch, double scaleTolerance,
            Errors errors) {
        int players = parameters.config().players();
        float[] refPolicy = new float[batch * Encoder.POLICY_SIZE];
        float[] refValues = new float[batch * players];
        new Network(parameters).forward(input, batch, refPolicy, refValues);
        float[] policy = new float[batch * Encoder.POLICY_SIZE];
        float[] values = new float[batch * players];
        new VectorNetwork(parameters).forward(input, batch, policy, values);
        String config = parameters.config().toString();
        for (int b = 0; b < batch; b++) {
            double largest = 0;
            for (int i = b * Encoder.POLICY_SIZE; i < (b + 1) * Encoder.POLICY_SIZE; i++) {
                largest = Math.max(largest, Math.abs(refPolicy[i]));
            }
            for (int i = b * Encoder.POLICY_SIZE; i < (b + 1) * Encoder.POLICY_SIZE; i++) {
                errors.add(policy[i], refPolicy[i], scaleTolerance * largest, config + " policy " + i);
            }
        }
        for (int i = 0; i < values.length; i++) {
            errors.add(values[i], refValues[i], 0, config + " value " + i);
        }
        return errors;
    }

    @Test
    void tinyAgreesWithTheReference(TestReporter reporter) {
        Errors errors = new Errors();
        Errors exploding = new Errors();
        for (int players = 2; players <= 3; players++) {
            NetworkConfig config = NetworkConfig.tiny(players);
            for (int batch : new int[] {1, 3, 64}) {
                compare(initScale(config, 10 + batch), inputs(players, batch, 20 + batch), batch, 0, errors);
                compare(Parameters.init(config, 30 + batch), inputs(players, batch, 40 + batch), batch, 0, errors);
                compare(Parameters.uniform(config, 50 + batch, 0.5f), inputs(players, batch, 60 + batch), batch,
                        SCALE_TOLERANCE, exploding);
            }
        }
        reporter.publishEntry("TINY", errors.toString());
        reporter.publishEntry("TINY, exploding activations", exploding.toString());
    }

    @Test
    void smallAgreesWithTheReference(TestReporter reporter) {
        Errors errors = new Errors();
        Errors exploding = new Errors();
        for (int batch : new int[] {1, 3, 64}) {
            int players = batch == 3 ? 3 : 2;
            NetworkConfig config = NetworkConfig.small(players);
            compare(initScale(config, 70 + batch), inputs(players, batch, 80 + batch), batch, 0, errors);
            compare(Parameters.init(config, 90 + batch), inputs(players, batch, 100 + batch), batch, 0, errors);
            compare(Parameters.uniform(config, 110 + batch, 0.5f), inputs(players, batch, 120 + batch), batch,
                    SCALE_TOLERANCE, exploding);
        }
        reporter.publishEntry("SMALL", errors.toString());
        reporter.publishEntry("SMALL, exploding activations", exploding.toString());
    }

    @Test
    void largeAgreesWithTheReference(TestReporter reporter) {
        Errors errors = new Errors();
        Errors exploding = new Errors();
        for (int batch : new int[] {1, 3, 64}) {
            int players = batch == 3 ? 3 : 2;
            NetworkConfig config = NetworkConfig.large(players);
            compare(initScale(config, 130 + batch), inputs(players, batch, 140 + batch), batch, 0, errors);
            // The reference needs about 0.1 s per LARGE position; batch 64 runs once per kind of weights.
            if (batch < 64) {
                compare(Parameters.init(config, 150 + batch), inputs(players, batch, 160 + batch), batch, 0,
                        errors);
            }
            compare(Parameters.uniform(config, 170 + batch, 0.5f), inputs(players, batch, 180 + batch), batch,
                    SCALE_TOLERANCE, exploding);
        }
        reporter.publishEntry("LARGE", errors.toString());
        reporter.publishEntry("LARGE, exploding activations", exploding.toString());
    }

    /**
     * Channel counts and hidden widths that are not multiples of the lane count (4 on 128-bit NEON, 8 on AVX2,
     * 16 on AVX-512) or of the tile width 2 L, so the padded tail lanes take part in every layer, and 0, 1 and
     * 2 blocks. 1, 3 and 5 channels give one partial tile; 13, 17 and 37 a partial tile after full ones for
     * L = 4.
     */
    @Test
    void oddSizesAgreeWithTheReference(TestReporter reporter) {
        Errors errors = new Errors();
        int[][] sizes = {{1, 1, 1}, {3, 0, 7}, {5, 2, 33}, {13, 1, 5}, {37, 2, 31}, {17, 1, 1}};
        long seed = 200;
        for (int[] s : sizes) {
            for (int players = 2; players <= 3; players++) {
                NetworkConfig config = new NetworkConfig(s[0], s[1], players, s[2]);
                assertNotEquals(0, s[0] % (2 * VectorNetwork.lanes()), "channels must leave a tail");
                for (int batch : new int[] {1, 3}) {
                    compare(initScale(config, seed++), inputs(players, batch, seed++), batch, 0, errors);
                }
            }
        }
        reporter.publishEntry("odd sizes", errors.toString());
    }

    /**
     * The scratch buffers are reused: a second call with other inputs and a third call with the first inputs
     * again must give exactly the first result. A batch must give exactly the single results, since each
     * position runs the same operations in the same order. A stale buffer or a value carried from one position
     * to the next breaks bit equality.
     */
    @Test
    void reusedBuffersAndBatchesGiveIdenticalResults() {
        NetworkConfig config = new NetworkConfig(13, 2, 3, 7);
        VectorNetwork network = new VectorNetwork(Parameters.uniform(config, 300, 0.5f));
        int batch = 5;
        float[] input = inputs(3, batch, 301);
        float[] policy = new float[batch * Encoder.POLICY_SIZE];
        float[] values = new float[batch * 3];
        network.forward(input, batch, policy, values);

        float[] otherPolicy = new float[batch * Encoder.POLICY_SIZE];
        float[] otherValues = new float[batch * 3];
        network.forward(inputs(3, batch, 302), batch, otherPolicy, otherValues);
        assertFalse(Arrays.equals(policy, otherPolicy), "other inputs give other logits");

        float[] againPolicy = new float[batch * Encoder.POLICY_SIZE];
        float[] againValues = new float[batch * 3];
        network.forward(input, batch, againPolicy, againValues);
        assertArrayEquals(policy, againPolicy);
        assertArrayEquals(values, againValues);

        // Batch order reversed, one at a time, each into its own slice.
        for (int b = batch - 1; b >= 0; b--) {
            float[] one = Arrays.copyOfRange(input, b * Encoder.INPUT_SIZE, (b + 1) * Encoder.INPUT_SIZE);
            float[] onePolicy = new float[Encoder.POLICY_SIZE];
            float[] oneValues = new float[3];
            network.forward(one, 1, onePolicy, oneValues);
            assertArrayEquals(Arrays.copyOfRange(policy, b * Encoder.POLICY_SIZE, (b + 1) * Encoder.POLICY_SIZE),
                    onePolicy, "policy of position " + b);
            assertArrayEquals(Arrays.copyOfRange(values, b * 3, (b + 1) * 3), oneValues, "values of " + b);
        }
    }

    /** Outputs beyond the batch, and input beyond it, are not touched or read. */
    @Test
    void forwardWritesOnlyItsSlices() {
        NetworkConfig config = NetworkConfig.tiny(2);
        Parameters parameters = Parameters.uniform(config, 400, 0.5f);
        int batch = 2;
        float[] input = Arrays.copyOf(inputs(2, batch, 401), (batch + 1) * Encoder.INPUT_SIZE);
        Arrays.fill(input, batch * Encoder.INPUT_SIZE, input.length, Float.NaN); // would fail the padding check
        float[] policy = new float[(batch + 1) * Encoder.POLICY_SIZE];
        float[] values = new float[(batch + 1) * 2];
        Arrays.fill(policy, Float.NaN);
        Arrays.fill(values, Float.NaN);
        new VectorNetwork(parameters).forward(input, batch, policy, values);
        for (int i = 0; i < policy.length; i++) {
            assertEquals(i >= batch * Encoder.POLICY_SIZE, Float.isNaN(policy[i]), "policy " + i);
        }
        for (int i = 0; i < values.length; i++) {
            assertEquals(i >= batch * 2, Float.isNaN(values[i]), "value " + i);
        }
    }

    /**
     * The weights are packed in the constructor: an in-place update has no effect until {@link
     * VectorNetwork#reload}, and after it the result agrees with the reference of the updated parameters. This
     * fails if reload skips a tensor (every tensor is changed) or packs into the wrong place.
     */
    @Test
    void reloadPicksUpInPlaceUpdates() {
        NetworkConfig config = new NetworkConfig(5, 1, 3, 7);
        Parameters parameters = Parameters.uniform(config, 500, 0.5f);
        VectorNetwork network = new VectorNetwork(parameters);
        float[] input = inputs(3, 3, 501);
        float[] before = new float[3 * Encoder.POLICY_SIZE];
        float[] beforeValues = new float[9];
        network.forward(input, 3, before, beforeValues);

        Parameters other = Parameters.uniform(config, 502, 0.5f);
        for (String name : Parameters.shapes(config).keySet()) {
            float[] src = other.get(name).data();
            System.arraycopy(src, 0, parameters.get(name).data(), 0, src.length);
        }
        float[] stale = new float[3 * Encoder.POLICY_SIZE];
        float[] staleValues = new float[9];
        network.forward(input, 3, stale, staleValues);
        assertArrayEquals(before, stale, "no effect before reload");
        assertArrayEquals(beforeValues, staleValues);

        network.reload();
        compare(parameters, input, 3, 0, new Errors());
        float[] after = new float[3 * Encoder.POLICY_SIZE];
        float[] afterValues = new float[9];
        network.forward(input, 3, after, afterValues);
        float[] fresh = new float[3 * Encoder.POLICY_SIZE];
        float[] freshValues = new float[9];
        new VectorNetwork(parameters).forward(input, 3, fresh, freshValues);
        assertArrayEquals(fresh, after, "reload equals a new instance");
        assertArrayEquals(freshValues, afterValues);

        // Each tensor on its own: change it, reload, and the output must change.
        for (String name : Parameters.shapes(config).keySet()) {
            float[] data = parameters.get(name).data();
            float[] saved = data.clone();
            for (int i = 0; i < data.length; i++) {
                data[i] += 0.25f;
            }
            network.reload();
            float[] changed = new float[3 * Encoder.POLICY_SIZE];
            float[] changedValues = new float[9];
            network.forward(input, 3, changed, changedValues);
            assertFalse(Arrays.equals(after, changed) && Arrays.equals(afterValues, changedValues),
                    "changing " + name + " changes the output");
            System.arraycopy(saved, 0, data, 0, data.length);
        }
    }

    /** The Evaluator gives the same moves and, within the tolerance, the same priors and values. */
    @Test
    void evaluatorAgreesWithTheReference() {
        for (int players = 2; players <= 3; players++) {
            Parameters parameters = Parameters.uniform(NetworkConfig.tiny(players), 600 + players, 0.5f);
            Evaluator reference = new Evaluator(new Network(parameters));
            Evaluator fast = new Evaluator(new VectorNetwork(parameters));
            List<Position> positions = Playouts.positions(players, 1, 30, 610 + players);
            List<Evaluation> expected = reference.evaluate(positions);
            List<Evaluation> actual = fast.evaluate(positions);
            for (int i = 0; i < positions.size(); i++) {
                assertArrayEquals(expected.get(i).moves(), actual.get(i).moves());
                for (int m = 0; m < expected.get(i).priors().length; m++) {
                    float ref = expected.get(i).priors()[m];
                    assertTrue(Network.agrees(actual.get(i).priors()[m], ref), "prior " + m + " of " + i);
                }
                for (int a = 0; a < players; a++) {
                    float ref = expected.get(i).values()[a];
                    assertTrue(Network.agrees(actual.get(i).values()[a], ref), "value " + a + " of " + i);
                }
            }
        }
    }

    @Test
    void rejectsBadArgumentsBeforeWriting() {
        VectorNetwork network = new VectorNetwork(Parameters.uniform(NetworkConfig.tiny(2), 700, 0.5f));
        float[] input = inputs(2, 2, 701);
        float[] policy = new float[2 * Encoder.POLICY_SIZE];
        float[] values = new float[4];
        Arrays.fill(policy, Float.NaN);

        float[] dirty = input.clone();
        dirty[Encoder.INPUT_SIZE + Encoder.index(HexGrid.PADDING, Encoder.PROGRESS)] = 0.5f;
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> network.forward(dirty, 2, policy, values));
        assertTrue(e.getMessage().contains("input 1"), e.getMessage());
        assertTrue(Float.isNaN(policy[0]), "position 0 was not computed before the check");

        assertThrows(IllegalArgumentException.class, () -> network.forward(input, 0, policy, values));
        assertThrows(IllegalArgumentException.class, () -> network.forward(input, 3, policy, values));
        assertThrows(IllegalArgumentException.class,
                () -> network.forward(input, 2, new float[2 * Encoder.POLICY_SIZE - 1], values));
        assertThrows(IllegalArgumentException.class, () -> network.forward(input, 2, policy, new float[3]));
        assertThrows(NullPointerException.class, () -> network.forward(null, 1, policy, values));
        assertThrows(NullPointerException.class, () -> new VectorNetwork(null));
        assertEquals(NetworkConfig.tiny(2), network.config());
    }

    @Test
    void lanesAreThePreferredSpecies() {
        assertEquals(VectorSupport.lanes(), VectorNetwork.lanes());
    }
}
