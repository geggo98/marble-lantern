package sternhalma.tornado;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;

import sternhalma.engine.Position;
import sternhalma.nn.Encoder;
import sternhalma.nn.Evaluation;
import sternhalma.nn.Evaluator;
import sternhalma.nn.HexGrid;
import sternhalma.nn.Network;
import sternhalma.nn.NetworkConfig;
import sternhalma.nn.Parameters;
import sternhalma.tornado.KernelsHexConvLayer.Variant;

/**
 * {@link TornadoNetwork} against the oracle {@link Network}, with the tolerance of docs/network.md, "Numeric
 * conventions": every policy logit, value and prior must satisfy {@link Network#agrees}. The weights are
 * {@link Parameters#init} and {@link Parameters#uniform} at the init bound {@code sqrt(6 / 7C)}; the second also
 * draws the biases, so that a lost bias shows. These are the weights for which the documented bound holds
 * (docs/network.md). The inputs are encoded positions of seeded random games and random planes in [0, 1). The
 * tests publish the largest error as a fraction of the bound through the {@link TestReporter}.
 *
 * <p>Batch sizes are chosen against plausible bugs: 3 and 67 fill no power-of-two bucket, so a bucket's unused
 * slots and the values offset of the bucket (not of the call) are exercised; with the automatic kernel rule,
 * 1 and 3 use NAIVE (LARGE: only 1), 64 and 67 SIMDGROUP; LARGE has 6 blocks, so a wrong rotation of the three
 * activation buffers fails.
 *
 * <p>Tests that need a device call {@link TornadoAssumptions#assumeTornado()} first.
 */
class TornadoNetworkTest {

    /** Error statistics of one test, as a fraction of the allowed bound. */
    private static final class Errors {
        double maxAbs;
        double maxRatio;
        long compared;

        void add(double fast, double ref, String what) {
            double d = Math.abs(fast - ref);
            double bound = Network.ABSOLUTE_TOLERANCE + Network.RELATIVE_TOLERANCE * Math.abs(ref);
            if (!Network.agrees(fast, ref)) {
                fail(what + ": device " + fast + ", reference " + ref + ", allowed error " + bound);
            }
            maxAbs = Math.max(maxAbs, d);
            maxRatio = Math.max(maxRatio, d / bound);
            compared++;
        }

        @Override
        public String toString() {
            return String.format("%d outputs, largest |error| %.3g, largest error / bound %.4f", compared, maxAbs,
                    maxRatio);
        }
    }

    /** The outputs of a forward pass. */
    private record Outputs(float[] policy, float[] values) {
    }

    /** Weights of the size of {@link Parameters#init}, biases included. */
    private static Parameters initScale(NetworkConfig config, long seed) {
        return Parameters.uniform(config, seed, (float) Math.sqrt(6.0 / (7 * config.channels())));
    }

    /** Encoded positions of random games (even indices) and random planes (odd indices). */
    private static float[] inputs(int players, int batch, long seed) {
        float[] input = new float[batch * Encoder.INPUT_SIZE];
        List<Position> positions = Playouts.positions(players, 2, 120, seed);
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

    /** The reference outputs, one position per task in parallel ({@link Network} is thread-safe). */
    private static Outputs reference(Parameters parameters, float[] input, int batch) {
        int players = parameters.config().players();
        float[] policy = new float[batch * Encoder.POLICY_SIZE];
        float[] values = new float[batch * players];
        Network network = new Network(parameters);
        IntStream.range(0, batch).parallel().forEach(b -> {
            float[] p = new float[Encoder.POLICY_SIZE];
            float[] v = new float[players];
            network.forward(Arrays.copyOfRange(input, b * Encoder.INPUT_SIZE, (b + 1) * Encoder.INPUT_SIZE), 1,
                    p, v);
            System.arraycopy(p, 0, policy, b * Encoder.POLICY_SIZE, p.length);
            System.arraycopy(v, 0, values, b * players, players);
        });
        return new Outputs(policy, values);
    }

    private static Outputs forward(TornadoNetwork network, float[] input, int batch) {
        float[] policy = new float[batch * Encoder.POLICY_SIZE];
        float[] values = new float[batch * network.config().players()];
        network.forward(input, batch, policy, values);
        return new Outputs(policy, values);
    }

    /** Compares the first {@code batch} positions of {@code actual} with the reference. */
    private static void compare(String what, Outputs expected, Outputs actual, int batch, int players,
            Errors errors) {
        for (int i = 0; i < batch * Encoder.POLICY_SIZE; i++) {
            errors.add(actual.policy[i], expected.policy[i],
                    what + " logit " + i % Encoder.POLICY_SIZE + " of position " + i / Encoder.POLICY_SIZE);
        }
        for (int i = 0; i < batch * players; i++) {
            errors.add(actual.values[i], expected.values[i],
                    what + " value " + i % players + " of position " + i / players);
        }
    }

    /**
     * The reference outputs of different positions differ by far more than the bound, so a pass that gives every
     * position the result of another (a lost batch offset) cannot agree with the reference by chance.
     *
     * @return the fraction of values below 0.9 in magnitude; near 0 means that tanh hides the value head
     */
    private static double assertInformative(String what, Outputs ref, int batch, int players) {
        int differing = 0;
        for (int b = 1; b < batch; b++) {
            double largest = 0;
            for (int i = 0; i < Encoder.POLICY_SIZE; i++) {
                largest = Math.max(largest, Math.abs(ref.policy[b * Encoder.POLICY_SIZE + i] - ref.policy[i]));
            }
            if (largest > 0.01) {
                differing++;
            }
        }
        assertTrue(differing >= (batch - 1) / 2, what + ": only " + differing + " of " + (batch - 1)
                + " positions differ from position 0 in a logit by more than 0.01");
        long unsaturated = IntStream.range(0, batch * players).filter(i -> Math.abs(ref.values[i]) < 0.9).count();
        return unsaturated / (double) (batch * players);
    }

    @Test
    void theBucketIsTheNextPowerOfTwoCappedAtTheMaximum() {
        int[][] cases = {
            {1, 1024, 1}, {2, 1024, 2}, {3, 1024, 4}, {4, 1024, 4}, {5, 1024, 8}, {63, 1024, 64}, {64, 1024, 64},
            {65, 1024, 128}, {1023, 1024, 1024}, {1024, 1024, 1024}, {5000, 1024, 1024},
            {3, 6, 4}, {5, 6, 6}, {6, 6, 6}, {7, 6, 6}, {1, 1, 1}, {9, 1, 1},
        };
        for (int[] c : cases) {
            assertEquals(c[2], TornadoNetwork.bucket(c[0], c[1]), "bucket(" + c[0] + ", " + c[1] + ")");
        }
        assertThrows(IllegalArgumentException.class, () -> TornadoNetwork.bucket(0, 8));
        assertThrows(IllegalArgumentException.class, () -> TornadoNetwork.bucket(1, 0));
        assertEquals(Variant.NAIVE, TornadoNetwork.variantFor(4, 32));
        assertEquals(Variant.SIMDGROUP, TornadoNetwork.variantFor(8, 32));
        assertEquals(Variant.NAIVE, TornadoNetwork.variantFor(2, 64));
        assertEquals(Variant.SIMDGROUP, TornadoNetwork.variantFor(4, 64));
        assertEquals(Variant.NAIVE, TornadoNetwork.variantFor(16, 8));
        assertEquals(Variant.SIMDGROUP, TornadoNetwork.variantFor(1, 1024));
    }

    /**
     * TINY, SMALL and LARGE, two and three players, both kinds of weights, batches 1, 3, 64 and 67 on one
     * instance (buckets 1, 4, 64 and 128). With {@link Parameters#init} (zero biases), the LARGE trunk grows
     * through its six residual blocks until every value is saturated (measured: 0 of 134 below 0.9); so per config
     * at least one kind of weights must leave a quarter of the values unsaturated, which keeps tanh and the dense
     * layers under test.
     */
    @Test
    void everyPresetAgreesWithTheReference(TestReporter reporter) {
        TornadoAssumptions.assumeTornado();
        int[] batches = {1, 3, 64, 67};
        int largest = 67;
        long seed = 100;
        for (NetworkConfig config : List.of(NetworkConfig.tiny(2), NetworkConfig.tiny(3), NetworkConfig.small(2),
                NetworkConfig.small(3), NetworkConfig.large(2), NetworkConfig.large(3))) {
            double unsaturated = 0;
            for (boolean init : new boolean[] {true, false}) {
                seed++;
                Parameters parameters = init ? Parameters.init(config, seed) : initScale(config, seed);
                int players = config.players();
                float[] input = inputs(players, largest, seed);
                Outputs expected = reference(parameters, input, largest);
                double fraction = assertInformative(config + (init ? " init" : " uniform"), expected, largest,
                        players);
                unsaturated = Math.max(unsaturated, fraction);
                Errors errors = new Errors();
                try (TornadoNetwork network = new TornadoNetwork(parameters)) {
                    for (int batch : batches) {
                        compare(config + (init ? " init" : " uniform") + " batch " + batch, expected,
                                forward(network, input, batch), batch, players, errors);
                    }
                    assertEquals(batches.length, network.plansBuilt());
                }
                reporter.publishEntry(config + (init ? " init" : " uniform at init bound"), errors
                        + String.format(", %.0f %% of the values below 0.9", 100 * fraction));
            }
            assertTrue(unsaturated >= 0.25, config + ": with both kinds of weights fewer than a quarter of the values"
                    + " are below 0.9 in magnitude (" + unsaturated + ")");
        }
    }

    /**
     * Every convolution kernel in the whole pass, at a bucket where the automatic rule takes NAIVE (4) and one
     * where it takes SIMDGROUP (64).
     */
    @Test
    void everyKernelVariantAgreesWithTheReference(TestReporter reporter) {
        TornadoAssumptions.assumeTornado();
        NetworkConfig config = NetworkConfig.small(3);
        Parameters parameters = initScale(config, 200);
        float[] input = inputs(3, 64, 201);
        Outputs expected = reference(parameters, input, 64);
        for (Variant variant : Variant.values()) {
            Errors errors = new Errors();
            try (TornadoNetwork network = new TornadoNetwork(parameters, TornadoSupport.defaultDevice(), 1024,
                    variant, true)) {
                for (int batch : new int[] {3, 64}) {
                    compare(variant + " batch " + batch, expected, forward(network, input, batch), batch, 3, errors);
                }
            }
            reporter.publishEntry(variant.toString(), errors.toString());
        }
    }

    /**
     * A batch above maxBatch is split into chunks: with maxBatch 4, batch 11 is 4 + 4 + 3; with maxBatch 6 (no
     * power of two), batch 13 is 6 + 6 + 1. Every position must land at its own offset.
     */
    @Test
    void batchesAboveTheMaximumAreSplitIntoChunks() {
        TornadoAssumptions.assumeTornado();
        NetworkConfig config = NetworkConfig.small(2);
        Parameters parameters = initScale(config, 300);
        float[] input = inputs(2, 13, 301);
        Outputs expected = reference(parameters, input, 13);
        assertInformative("SMALL", expected, 13, 2);
        Errors errors = new Errors();
        try (TornadoNetwork network = new TornadoNetwork(parameters, TornadoSupport.defaultDevice(), 4)) {
            compare("maxBatch 4", expected, forward(network, input, 11), 11, 2, errors);
            assertEquals(4, network.maxBatch());
            assertEquals(1, network.plansBuilt(), "chunks of 4 and 3 both use bucket 4");
        }
        try (TornadoNetwork network = new TornadoNetwork(parameters, TornadoSupport.defaultDevice(), 6)) {
            compare("maxBatch 6", expected, forward(network, input, 13), 13, 2, errors);
            assertEquals(2, network.plansBuilt(), "buckets 6 and 1");
        }
    }

    /**
     * The slots of a bucket that a call does not use hold the input of an earlier call. Here that input is huge
     * (all planes 1000), so its results would be far off if they leaked into a used slot. The output arrays are
     * longer than needed and pre-filled with NaN: only the first {@code batch} slices may change.
     */
    @Test
    void unusedSlotsOfABucketDoNotLeakAndOnlyTheBatchIsWritten() {
        TornadoAssumptions.assumeTornado();
        NetworkConfig config = NetworkConfig.small(3);
        Parameters parameters = initScale(config, 400);
        float[] huge = new float[4 * Encoder.INPUT_SIZE];
        for (int b = 0; b < 4; b++) {
            Arrays.fill(huge, b * Encoder.INPUT_SIZE, b * Encoder.INPUT_SIZE + HexGrid.CELLS * Encoder.PLANES, 1000f);
        }
        float[] input = inputs(3, 3, 401);
        Outputs expected = reference(parameters, input, 3);
        try (TornadoNetwork network = new TornadoNetwork(parameters)) {
            forward(network, huge, 4);
            float[] policy = new float[4 * Encoder.POLICY_SIZE];
            float[] values = new float[4 * 3];
            Arrays.fill(policy, Float.NaN);
            Arrays.fill(values, Float.NaN);
            network.forward(input, 3, policy, values);
            assertEquals(1, network.plansBuilt(), "batch 3 and 4 share bucket 4");
            compare("after a huge input", expected, new Outputs(policy, values), 3, 3, new Errors());
            for (int i = 3 * Encoder.POLICY_SIZE; i < policy.length; i++) {
                assertTrue(Float.isNaN(policy[i]), "policy " + i + " is beyond the batch but was written");
            }
            for (int i = 3 * 3; i < values.length; i++) {
                assertTrue(Float.isNaN(values[i]), "value " + i + " is beyond the batch but was written");
            }
        }
    }

    /**
     * After the first call of a bucket, a call sends only the input of the bucket to the device and brings only
     * the head output buffer back, compiles nothing and runs the kernels on the device.
     */
    @Test
    void aWarmCallMovesOnlyTheInputAndTheOutput() {
        TornadoAssumptions.assumeTornado();
        NetworkConfig config = NetworkConfig.small(2);
        Parameters parameters = initScale(config, 500);
        float[] input = inputs(2, 3, 501);
        long inputBytes = 4L * 4 * Encoder.INPUT_SIZE; // bucket 4
        long outputBytes = 4L * OpsKernels.outputSize(4, 2);
        long activationBytes = 4L * 4 * HexGrid.ROWS * config.channels();
        try (TornadoNetwork network = TornadoNetwork.profiled(parameters, TornadoSupport.defaultDevice(), 1024)) {
            assertThrows(IllegalStateException.class, network::lastProfile);
            forward(network, input, 3);
            ExecutionProfile first = network.lastProfile();
            assertTrue(first.compileNanos() > 0, "the first call compiles: " + first);
            assertTrue(first.bytesToDevice() > inputBytes + 4L * config.parameterCount(),
                    "the first call sends the parameters: " + first);
            for (int call = 0; call < 3; call++) {
                forward(network, input, 3);
                ExecutionProfile warm = network.lastProfile();
                assertEquals(0, warm.compileNanos(), "a warm call compiles nothing: " + warm);
                assertTrue(warm.kernelNanos() > 0, "the kernels ran on the device: " + warm);
                assertTrue(warm.bytesToDevice() >= inputBytes && warm.bytesToDevice() <= inputBytes + 64,
                        "only the input goes to the device: " + warm + ", input " + inputBytes);
                assertTrue(warm.bytesToHost() >= outputBytes && warm.bytesToHost() <= outputBytes + 64,
                        "only the head output comes back: " + warm + ", output " + outputBytes);
                assertTrue(warm.bytesToHost() < activationBytes, "no activation comes back");
            }
        }
    }

    /**
     * The parameters are copied in the constructor: an in-place update has no effect until {@link
     * TornadoNetwork#reload}. After it, a bucket used before and a bucket used for the first time both agree
     * with the reference of the new parameters, and changing any single tensor changes the output.
     */
    @Test
    void reloadPicksUpInPlaceUpdatesInEveryPlan() {
        TornadoAssumptions.assumeTornado();
        NetworkConfig config = new NetworkConfig(5, 2, 3, 7);
        Parameters parameters = initScale(config, 600);
        float[] input = inputs(3, 20, 601);
        try (TornadoNetwork network = new TornadoNetwork(parameters)) {
            Outputs before = forward(network, input, 3);
            Outputs beforeLarge = forward(network, input, 20);

            Parameters other = initScale(config, 602);
            for (String name : Parameters.shapes(config).keySet()) {
                float[] src = other.get(name).data();
                System.arraycopy(src, 0, parameters.get(name).data(), 0, src.length);
            }
            Outputs stale = forward(network, input, 3);
            assertArrayEquals(before.policy, stale.policy, "no effect before reload");
            assertArrayEquals(before.values, stale.values, "no effect before reload");
            assertArrayEquals(beforeLarge.values, forward(network, input, 20).values, "no effect before reload");

            network.reload();
            Outputs expected = reference(parameters, input, 20);
            Errors errors = new Errors();
            compare("bucket 4 after reload", expected, forward(network, input, 3), 3, 3, errors);
            compare("bucket 32 after reload", expected, forward(network, input, 20), 20, 3, errors);
            compare("bucket 8, first used after reload", expected, forward(network, input, 7), 7, 3, errors);

            Outputs after = forward(network, input, 3);
            for (String name : Parameters.shapes(config).keySet()) {
                float[] data = parameters.get(name).data();
                float[] saved = data.clone();
                for (int i = 0; i < data.length; i++) {
                    data[i] += 0.25f;
                }
                network.reload();
                Outputs changed = forward(network, input, 3);
                assertFalse(Arrays.equals(after.policy, changed.policy) && Arrays.equals(after.values, changed.values),
                        "changing " + name + " changes the output");
                System.arraycopy(saved, 0, data, 0, data.length);
            }
        }
    }

    /**
     * A value head driven into saturation by a large dense2 bias. Metal's {@code tanh} returns 0 from an argument
     * of about 44 on and NaN from about 44.4 (docs/tornado.md, "Pitfalls"); the reference gives exactly +-1.
     * The biases put the tanh argument of every position just above 44, far above, at +infinity and far below.
     * Compared through {@link TornadoNetwork#forward} and through {@link Evaluator}.
     */
    @Test
    void aSaturatedValueHeadGivesPlusOrMinusOne() {
        TornadoAssumptions.assumeTornado();
        NetworkConfig config = NetworkConfig.small(3);
        float[][] biases = {{44.3f, 100f, -1000f}, {1000f, 45f, Float.POSITIVE_INFINITY}};
        List<Position> positions = Playouts.sample(3, 12, 901);
        for (float[] bias : biases) {
            Parameters parameters = initScale(config, 900);
            float[] b2 = parameters.get("head_value.dense2.bias").data();
            System.arraycopy(bias, 0, b2, 0, bias.length);
            float[] input = inputs(3, 8, 902);
            Outputs expected = reference(parameters, input, 8);
            for (int i = 0; i < 8 * 3; i++) {
                assertEquals(1f, Math.abs(expected.values[i]), "the reference saturates: value " + i);
            }
            try (TornadoNetwork network = new TornadoNetwork(parameters)) {
                for (int batch : new int[] {1, 8}) {
                    compare("bias " + Arrays.toString(bias) + " batch " + batch, expected,
                            forward(network, input, batch), batch, 3, new Errors());
                }
                Evaluator reference = new Evaluator(new Network(parameters));
                List<Evaluation> actual = new Evaluator(network).evaluate(positions);
                for (int i = 0; i < positions.size(); i++) {
                    assertArrayEquals(reference.evaluate(positions.get(i)).values(), actual.get(i).values(),
                            "Evaluator values of position " + i + ", bias " + Arrays.toString(bias));
                }
            }
        }
    }

    /**
     * A NaN weight in conv_in must make every output NaN, as in the reference, and {@link Evaluator} must then
     * throw for both networks. Metal's {@code fmax}, which {@code Math.max} compiles to, drops NaN: a ReLU written
     * with it turns the NaN outputs of conv_in into 0, and the pass returns plausible finite numbers. Every kernel
     * variant, at a NAIVE bucket (1) and a SIMDGROUP bucket (64, also with the automatic rule).
     */
    @Test
    void aNaNWeightGivesNaNOutputsAsInTheReference() {
        TornadoAssumptions.assumeTornado();
        NetworkConfig config = NetworkConfig.small(2);
        Parameters parameters = initScale(config, 950);
        parameters.get("conv_in.weight").data()[3] = Float.NaN;
        float[] input = inputs(2, 64, 951);
        Outputs expected = reference(parameters, input, 64);
        for (float v : expected.policy) {
            assertTrue(Float.isNaN(v), "the reference gives NaN logits");
        }
        for (float v : expected.values) {
            assertTrue(Float.isNaN(v), "the reference gives NaN values");
        }
        Position position = Playouts.sample(2, 1, 952).get(0);
        assertThrows(IllegalStateException.class, () -> new Evaluator(new Network(parameters)).evaluate(position));
        for (Variant variant : Variant.values()) {
            try (TornadoNetwork network = new TornadoNetwork(parameters, TornadoSupport.defaultDevice(), 1024,
                    variant, true)) {
                for (int batch : new int[] {1, 64}) {
                    Outputs actual = forward(network, input, batch);
                    for (int i = 0; i < actual.policy.length; i++) {
                        assertTrue(Float.isNaN(actual.policy[i]),
                                variant + " batch " + batch + ": logit " + i + " is " + actual.policy[i]);
                    }
                    for (int i = 0; i < actual.values.length; i++) {
                        assertTrue(Float.isNaN(actual.values[i]),
                                variant + " batch " + batch + ": value " + i + " is " + actual.values[i]);
                    }
                }
                assertThrows(IllegalStateException.class, () -> new Evaluator(network).evaluate(position),
                        variant + ": the Evaluator rejects the NaN outputs");
            }
        }
    }

    /**
     * The padding row is checked in every position: position 0 alone, position 0 of a batch, and the last
     * position of a batch above maxBatch (in its last chunk). Nothing runs before the check.
     */
    @Test
    void aDirtyPaddingRowIsRejectedInEveryPosition() {
        TornadoAssumptions.assumeTornado();
        NetworkConfig config = NetworkConfig.tiny(2);
        Parameters parameters = initScale(config, 960);
        float[] input = inputs(2, 6, 961);
        try (TornadoNetwork network = new TornadoNetwork(parameters, TornadoSupport.defaultDevice(), 4)) {
            int[][] cases = {{1, 0, Encoder.PROGRESS}, {2, 0, 0}, {6, 5, Encoder.PLANES - 1}};
            for (int[] c : cases) {
                int batch = c[0];
                int position = c[1];
                float[] dirty = input.clone();
                dirty[position * Encoder.INPUT_SIZE + Encoder.index(HexGrid.PADDING, c[2])] = 0.5f;
                IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                        () -> network.forward(dirty, batch, new float[batch * Encoder.POLICY_SIZE],
                                new float[batch * 2]),
                        "batch " + batch + ", dirty position " + position);
                assertTrue(e.getMessage().contains("input " + position), e.getMessage());
                assertEquals(0, network.plansBuilt(), "nothing ran before the check");
            }
        }
    }

    /** Wrong arguments, a concurrent call and a call after close all throw, and nothing runs before the checks. */
    @Test
    void misuseFailsLoudly() {
        TornadoAssumptions.assumeTornado();
        NetworkConfig config = NetworkConfig.tiny(2);
        Parameters parameters = initScale(config, 700);
        assertThrows(NullPointerException.class, () -> new TornadoNetwork(null));
        assertThrows(NullPointerException.class, () -> new TornadoNetwork(parameters, null, 8));
        assertThrows(IllegalArgumentException.class,
                () -> new TornadoNetwork(parameters, TornadoSupport.defaultDevice(), 0));
        // 3,000,000 * 122 * 8 floats overflow an int.
        assertThrows(IllegalArgumentException.class,
                () -> new TornadoNetwork(parameters, TornadoSupport.defaultDevice(), 3_000_000));

        TornadoNetwork network = new TornadoNetwork(parameters);
        assertFalse(network.threadSafe());
        assertEquals(config, network.config());
        float[] input = inputs(2, 2, 701);
        float[] policy = new float[2 * Encoder.POLICY_SIZE];
        float[] values = new float[4];

        float[] dirty = input.clone();
        dirty[Encoder.INPUT_SIZE + Encoder.index(HexGrid.PADDING, Encoder.PROGRESS)] = 0.5f;
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> network.forward(dirty, 2, policy, values));
        assertTrue(e.getMessage().contains("input 1"), e.getMessage());
        assertEquals(0, network.plansBuilt(), "nothing ran before the check");
        assertThrows(IllegalArgumentException.class, () -> network.forward(input, 0, policy, values));
        assertThrows(IllegalArgumentException.class, () -> network.forward(input, 3, policy, values));
        assertThrows(IllegalArgumentException.class,
                () -> network.forward(input, 2, new float[2 * Encoder.POLICY_SIZE - 1], values));
        assertThrows(IllegalArgumentException.class, () -> network.forward(input, 2, policy, new float[3]));
        assertThrows(NullPointerException.class, () -> network.forward(null, 1, policy, values));

        network.whileBusy(() -> {
            IllegalStateException busy = assertThrows(IllegalStateException.class,
                    () -> network.forward(input, 2, policy, values));
            assertTrue(busy.getMessage().contains("not thread-safe"), busy.getMessage());
            assertThrows(IllegalStateException.class, network::reload);
            assertThrows(IllegalStateException.class, network::close);
        });
        // The failed calls left the flag alone: the instance still works.
        network.forward(input, 2, policy, values);
        IllegalStateException unprofiled = assertThrows(IllegalStateException.class, network::lastProfile);
        assertTrue(unprofiled.getMessage().contains("profiler"), unprofiled.getMessage());
        Outputs expected = reference(parameters, input, 2);
        compare("after the busy calls", expected, new Outputs(policy, values), 2, 2, new Errors());

        network.close();
        network.close();
        IllegalStateException closed = assertThrows(IllegalStateException.class,
                () -> network.forward(input, 2, policy, values));
        assertTrue(closed.getMessage().contains("closed"), closed.getMessage());
        assertThrows(IllegalStateException.class, network::reload);
    }

    /**
     * End to end: {@code new Evaluator(new TornadoNetwork(p))} gives the same legal moves and, within the
     * tolerance, the same priors and values as {@code new Evaluator(new Network(p))}, on positions of random
     * playouts with every mover, for two and three players. The positions are evaluated as one batch and as
     * single positions.
     */
    @Test
    void theEvaluatorAgreesWithTheReferenceEvaluator(TestReporter reporter) {
        TornadoAssumptions.assumeTornado();
        for (int players = 2; players <= 3; players++) {
            for (NetworkConfig config : List.of(NetworkConfig.small(players), NetworkConfig.large(players))) {
                Parameters parameters = initScale(config, 800 + players);
                List<Position> positions = Playouts.sample(players, 90, 810 + players);
                boolean[] movers = new boolean[players];
                positions.forEach(p -> movers[p.sideToMove()] = true);
                for (int m = 0; m < players; m++) {
                    assertTrue(movers[m], "player " + m + " moves in some position");
                }
                Evaluator reference = new Evaluator(new Network(parameters));
                List<Evaluation> expected = IntStream.range(0, positions.size()).parallel()
                        .mapToObj(i -> reference.evaluate(positions.get(i))).toList();
                Errors priors = new Errors();
                Errors values = new Errors();
                double sharpest = 0;
                try (TornadoNetwork network = new TornadoNetwork(parameters)) {
                    Evaluator device = new Evaluator(network);
                    List<Evaluation> batch = device.evaluate(positions);
                    for (int i = 0; i < positions.size(); i++) {
                        Evaluation single = i % 10 == 0 ? device.evaluate(positions.get(i)) : null;
                        for (Evaluation actual : single == null ? List.of(batch.get(i))
                                : List.of(batch.get(i), single)) {
                            Evaluation ref = expected.get(i);
                            assertArrayEquals(ref.moves(), actual.moves(), "moves of position " + i);
                            for (int m = 0; m < ref.priors().length; m++) {
                                priors.add(actual.priors()[m], ref.priors()[m], config + " prior " + m + " of " + i);
                            }
                            for (int a = 0; a < players; a++) {
                                values.add(actual.values()[a], ref.values()[a], config + " value " + a + " of " + i);
                            }
                        }
                        float[] p = expected.get(i).priors();
                        if (p.length > 1) {
                            float max = 0;
                            float min = 1;
                            for (float v : p) {
                                max = Math.max(max, v);
                                min = Math.min(min, v);
                            }
                            sharpest = Math.max(sharpest, max / min);
                        }
                    }
                }
                // Near-uniform priors would make the prior comparison weak.
                assertTrue(sharpest > 2, "the largest prior ratio within a position is only " + sharpest);
                reporter.publishEntry(config + " priors", priors.toString());
                reporter.publishEntry(config + " values", values.toString());
            }
        }
    }

    /**
     * E11: the first convolution reads the 13 planes of {@code classic1-planes}, as the reference does. (The residual
     * priors of {@code classic1} are tested in {@link TornadoClassic1Test}.)
     */
    @Test
    void classicalPlanesAgreeWithTheReference() {
        TornadoAssumptions.assumeTornado();
        NetworkConfig config = NetworkConfig.tiny(2).withPlanes(sternhalma.nn.PlaneSet.CLASSIC1_PLANES);
        Parameters parameters = initScale(config, 5);
        List<Position> positions = Playouts.positions(2, 2, 60, 9);
        int batch = 6;
        int size = config.inputSize();
        float[] input = new float[batch * size];
        for (int b = 0; b < batch; b++) {
            Encoder.encode(config.planes(), positions.get(5 * b + 3), input, b * size);
        }
        float[] expectedPolicy = new float[batch * Encoder.POLICY_SIZE];
        float[] expectedValues = new float[batch * 2];
        new Network(parameters).forward(input, batch, expectedPolicy, expectedValues);
        float[] policy = new float[batch * Encoder.POLICY_SIZE];
        float[] values = new float[batch * 2];
        try (TornadoNetwork network = new TornadoNetwork(parameters)) {
            network.forward(input, batch, policy, values);
        }
        Errors errors = new Errors();
        for (int i = 0; i < policy.length; i++) {
            errors.add(policy[i], expectedPolicy[i], "logit " + i);
        }
        for (int i = 0; i < values.length; i++) {
            errors.add(values[i], expectedValues[i], "value " + i);
        }
    }
}
