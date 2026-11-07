package sternhalma.tornado;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.SplittableRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import sternhalma.nn.Encoder;
import sternhalma.nn.HexGrid;
import sternhalma.nn.Network;
import sternhalma.nn.NetworkConfig;
import sternhalma.nn.Parameters;

/**
 * {@link OpsHeads} (and with it {@link OpsKernels#policyHead}, {@link OpsKernels#valueHead} and
 * {@link OpsParameters}) on the device, against hand-computed results, the reference operations and
 * {@link Network}.
 */
class OpsHeadsTest {

    @BeforeEach
    void requireTornado() {
        TornadoAssumptions.assumeTornado();
    }

    /**
     * C = 2, H = 3, P = 3, two positions. Position 0 has channel 0 = cell id and channel 1 = 1 on every cell,
     * position 1 has channel 0 = 2 * cell id. The padding rows hold 1e6, which shows up in every output if a head
     * reads row 121.
     */
    @Test
    void bothHeadsByHand() {
        NetworkConfig config = new NetworkConfig(2, 1, 3, 3);
        Parameters p = Parameters.uniform(config, 1, 0.5f);
        set(p, "head_policy.weight", 1f, 0f, -0.5f, 2f);
        set(p, "head_policy.bias", 0.5f, -1f);
        set(p, "head_value.dense1.weight", 0.01f, 0.3f, -0.002f, -1f, 0.01f, 0f);
        set(p, "head_value.dense1.bias", 0.1f, 1.5f, -1f);
        set(p, "head_value.dense2.weight", 1f, -2f, 3f, 0.5f, 1.5f, -3f, -1f, 0.25f, 2f);
        set(p, "head_value.dense2.bias", 0.1f, 0.2f, -0.3f);
        int c = 2;
        float[] trunk = new float[2 * HexGrid.ROWS * c];
        for (int cell = 0; cell < HexGrid.CELLS; cell++) {
            trunk[cell * c] = cell;
            trunk[cell * c + 1] = 1f;
            trunk[(HexGrid.ROWS + cell) * c] = 2f * cell;
        }
        trunk[HexGrid.PADDING * c] = 1e6f;
        trunk[HexGrid.PADDING * c + 1] = 1e6f;
        trunk[(HexGrid.ROWS + HexGrid.PADDING) * c] = 1e6f;
        trunk[(HexGrid.ROWS + HexGrid.PADDING) * c + 1] = 1e6f;

        float[] logits = new float[2 * Encoder.POLICY_SIZE];
        float[] values = new float[2 * 3];
        try (OpsHeads heads = new OpsHeads(new OpsParameters(p), 2, TornadoSupport.defaultDevice())) {
            heads.apply(trunk, logits, values);
        }

        // Policy: from = 1 * x0 + 0 * x1 + 0.5, to = -0.5 * x0 + 2 * x1 - 1.
        float[] expectedLogits = new float[logits.length];
        for (int cell = 0; cell < HexGrid.CELLS; cell++) {
            expectedLogits[Encoder.policyIndex(cell, Encoder.FROM)] = cell + 0.5f;
            expectedLogits[Encoder.policyIndex(cell, Encoder.TO)] = -0.5f * cell + 1f;
            expectedLogits[Encoder.POLICY_SIZE + Encoder.policyIndex(cell, Encoder.FROM)] = 2f * cell + 0.5f;
            expectedLogits[Encoder.POLICY_SIZE + Encoder.policyIndex(cell, Encoder.TO)] = -cell - 1f;
        }
        OpsTesting.assertAgrees("logits", expectedLogits, logits, logits.length);

        // Position 0: pooled = (mean of 0..120 = 60, 1).
        //   hidden = ReLU(0.01*60 + 0.3*1 + 0.1, -0.002*60 - 1 + 1.5, 0.01*60 - 1) = (1.0, 0.38, 0)
        //   z = (1 - 0.76 + 0 + 0.1, 0.5 + 0.57 - 0 + 0.2, -1 + 0.095 + 0 - 0.3) = (0.34, 1.27, -1.205)
        // Position 1: pooled = (120, 0).
        //   hidden = ReLU(1.2 + 0.1, -0.24 + 1.5, 1.2 - 1) = (1.3, 1.26, 0.2)
        //   z = (1.3 - 2.52 + 0.6 + 0.1, 0.65 + 1.89 - 0.6 + 0.2, -1.3 + 0.315 + 0.4 - 0.3) = (-0.52, 2.14, -0.885)
        // Mutations, seen in position 0, output 0 (tanh 0.3275, bound 4.3e-4): no tanh gives 0.34; no ReLU on
        // hidden 2 (-0.4) gives z0 = -0.86; the mean over cells 0..119 or a division by 122 gives z0 = 0.333.
        float[] expectedValues = {
                (float) Math.tanh(0.34), (float) Math.tanh(1.27), (float) Math.tanh(-1.205),
                (float) Math.tanh(-0.52), (float) Math.tanh(2.14), (float) Math.tanh(-0.885)};
        double worst = OpsTesting.assertAgrees("values", expectedValues, values, values.length);
        System.out.println("OpsHeads hand case, largest value error as a fraction of the bound: " + worst);
    }

    /** Pooling over 120 cells instead of 121 changes position 0's mean only through cell 120. */
    @Test
    void poolingIncludesCell120AndDividesBy121() {
        NetworkConfig config = new NetworkConfig(1, 1, 2, 1);
        Parameters p = Parameters.uniform(config, 2, 0.5f);
        set(p, "head_value.dense1.weight", 1f);
        set(p, "head_value.dense1.bias", 0f);
        set(p, "head_value.dense2.weight", 1f, -1f);
        set(p, "head_value.dense2.bias", 0f, 0f);
        float[] trunk = new float[HexGrid.ROWS];
        trunk[0] = 0.121f * 50;     // cell 0
        trunk[120] = 0.121f * 50;   // cell 120, the last cell
        // pooled = 2 * 6.05 / 121 = 0.1; hidden = 0.1; values = (tanh 0.1, tanh -0.1).
        // Over 120 cells it would be 0.05 (or 0.0504 with /120); over 122 rows with /122 it would be 0.0992.
        float[] values = new float[2];
        try (OpsHeads heads = new OpsHeads(new OpsParameters(p), 1, TornadoSupport.defaultDevice())) {
            heads.apply(trunk, new float[Encoder.POLICY_SIZE], values);
        }
        OpsTesting.assertAgrees("values", new float[] {(float) Math.tanh(0.1), (float) Math.tanh(-0.1)}, values, 2);
        assertTrue(Math.abs(values[0] - Math.tanh(0.1)) < 1e-6, "pooled value " + values[0]);
    }

    /**
     * The path for C &gt; {@link OpsKernels#VALUE_GROUP} (one work item per channel, parts = 1): C = 300, only
     * cell 120 set, in channel 0 and in the last channel 299. pooled = 6.05 / 121 = 0.05 in both, hidden = 0.1,
     * values = (tanh 0.1, tanh -0.1). Pooling that drops cell 120 gives 0, a division by 122 gives 0.0992.
     */
    @Test
    void poolingOfMoreThan256ChannelsIncludesCell120AndDividesBy121() {
        int c = 300;
        NetworkConfig config = new NetworkConfig(c, 1, 2, 1);
        Parameters p = Parameters.uniform(config, 3, 0.5f);
        float[] w1 = new float[c];
        w1[0] = 1f;
        w1[c - 1] = 1f;
        set(p, "head_value.dense1.weight", w1);
        set(p, "head_value.dense1.bias", 0f);
        set(p, "head_value.dense2.weight", 1f, -1f);
        set(p, "head_value.dense2.bias", 0f, 0f);
        float[] trunk = new float[HexGrid.ROWS * c];
        trunk[120 * c] = 0.121f * 50;
        trunk[120 * c + c - 1] = 0.121f * 50;
        float[] values = new float[2];
        try (OpsHeads heads = new OpsHeads(new OpsParameters(p), 1, TornadoSupport.defaultDevice())) {
            heads.apply(trunk, new float[Encoder.POLICY_SIZE], values);
        }
        OpsTesting.assertAgrees("values", new float[] {(float) Math.tanh(0.1), (float) Math.tanh(-0.1)}, values, 2);
        assertTrue(Math.abs(values[0] - Math.tanh(0.1)) < 1e-6, "pooled value " + values[0]);
    }

    /**
     * A dense2 bias that drives tanh into saturation. Metal's {@code tanh} returns 0 from an argument of about 44
     * on and NaN from about 44.4; the reference gives exactly +-1 (docs/tornado.md, "Pitfalls").
     */
    @Test
    void aSaturatedValueIsExactlyPlusOrMinusOne() {
        float[][] biases = {{44.3f, 1000f, Float.POSITIVE_INFINITY}, {-1000f, 50f, 44.5f}};
        for (float[] bias : biases) {
            for (int batch : new int[] {1, 64}) {
                NetworkConfig config = new NetworkConfig(32, 1, 3, 32);
                Parameters p = Parameters.uniform(config, 13, (float) Math.sqrt(3.0 / 32));
                set(p, "head_value.dense2.bias", bias);
                float[] trunk = OpsTesting.trunk(new SplittableRandom(13), batch, 32, 0f);
                float[] values = new float[batch * 3];
                try (OpsHeads heads = new OpsHeads(new OpsParameters(p), batch, TornadoSupport.defaultDevice())) {
                    heads.apply(trunk, new float[batch * Encoder.POLICY_SIZE], values);
                }
                float[] expected = OpsTesting.valueReference(p, trunk, batch);
                for (float v : expected) {
                    assertEquals(1f, Math.abs(v), "the reference saturates");
                }
                OpsTesting.assertSameBits("values, bias " + Arrays.toString(bias) + ", batch " + batch, expected,
                        values);
            }
        }
    }

    /**
     * A NaN in a cell row of the trunk (not the padding row) makes the values of that position NaN, as in the
     * reference, whose ReLU ({@code Math.max}) keeps NaN; the other positions are unaffected. A dense1 ReLU that
     * drops NaN gives the finite values tanh(dense2.bias) instead. Both pooling paths (C = 32 and 300).
     */
    @Test
    void aNaNInACellRowGivesNaNValuesForItsPositionOnly() {
        for (int channels : new int[] {32, 300}) {
            NetworkConfig config = new NetworkConfig(channels, 1, 2, 32);
            Parameters p = Parameters.uniform(config, 17, (float) Math.sqrt(3.0 / channels));
            int batch = 3;
            float[] trunk = OpsTesting.trunk(new SplittableRandom(17), batch, channels, 0f);
            trunk[(HexGrid.ROWS + 60) * channels + channels / 2] = Float.NaN;   // position 1, cell 60
            float[] logits = new float[batch * Encoder.POLICY_SIZE];
            float[] values = new float[batch * 2];
            try (OpsHeads heads = new OpsHeads(new OpsParameters(p), batch, TornadoSupport.defaultDevice())) {
                heads.apply(trunk, logits, values);
            }
            int nanValues = OpsTesting.assertAgreesOrBothNaN("values, C=" + channels,
                    OpsTesting.valueReference(p, trunk, batch), values);
            assertEquals(2, nanValues, "the reference gives NaN for both values of position 1");
            int nanLogits = OpsTesting.assertAgreesOrBothNaN("logits, C=" + channels,
                    OpsTesting.policyReference(p, trunk, batch), logits);
            assertEquals(2, nanLogits, "the two logits of cell 60 of position 1");
        }
    }

    /**
     * The local barriers of {@link OpsKernels#valueHead}, with shapes that put the writers and the readers of
     * local memory into different SIMD groups and give the writers more work than the readers: C = 1 or 2 (one
     * work item sums 256 or 128 partial sums, while H = 1024 or 512 readers in all eight SIMD groups wait for
     * pooled[]), and C = 1024 with H = 1024 (long dense1 sums before dense2 reads act[]). Large batches and
     * several calls per plan give the race many chances. Races depend on timing, so a pass does not prove the
     * barriers; see docs/tornado.md for which barrier mutants these cases killed.
     */
    @Test
    void theValueHeadBarriersHoldUnderLoad() {
        // {C, H, P, batch}
        int[][] cases = {{1, 1024, 3, 1024}, {2, 512, 2, 1024}, {8, 1024, 3, 513}, {1024, 1024, 3, 256}};
        for (int[] c : cases) {
            int channels = c[0];
            int hidden = c[1];
            int players = c[2];
            int batch = c[3];
            NetworkConfig config = new NetworkConfig(channels, 1, players, hidden);
            Parameters p = Parameters.uniform(config, 19, (float) Math.sqrt(3.0 / Math.max(channels, hidden)));
            SplittableRandom random = new SplittableRandom(19L * channels);
            try (OpsHeads heads = new OpsHeads(new OpsParameters(p), batch, TornadoSupport.defaultDevice())) {
                for (int call = 0; call < 4; call++) {
                    float[] trunk = OpsTesting.trunk(random, batch, channels, 0f);
                    // Positions of very different scale, so that a value read from another position is wrong.
                    int size = HexGrid.ROWS * channels;
                    for (int i = 0; i < trunk.length; i++) {
                        trunk[i] *= 0.25f * (1 + (i / size) % 8);
                    }
                    float[] values = new float[batch * players];
                    heads.apply(trunk, new float[batch * Encoder.POLICY_SIZE], values);
                    OpsTesting.assertAgrees("values C=" + channels + " H=" + hidden + " call " + call,
                            OpsTesting.valueReference(p, trunk, batch), values, values.length);
                }
            }
        }
    }

    /**
     * Each head against the reference operations, on random post-ReLU activations whose padding rows are NaN
     * (a head that reads row 121 gives NaN). The configurations cover the pooling paths: C divides
     * {@link OpsKernels#VALUE_GROUP} (8, 32, 64), C does not (100: 2 partial sums per channel, 56 idle work
     * items), C above it (300: one work item per channel), H above it (300), and C = 1.
     */
    @Test
    void eachHeadAgreesWithTheReference() {
        // {C, H, P, batch}
        int[][] cases = {
            {8, 32, 2, 1}, {8, 32, 2, 3}, {8, 32, 3, 64}, {8, 32, 2, 513},
            {32, 32, 3, 1}, {32, 32, 2, 3}, {32, 32, 2, 64}, {32, 32, 3, 513},
            {64, 32, 2, 1}, {64, 32, 3, 3}, {64, 32, 2, 64}, {64, 32, 2, 513},
            {100, 300, 3, 3}, {300, 32, 2, 3}, {300, 300, 2, 64}, {1, 1, 2, 3}};
        for (int[] c : cases) {
            headsCase(c[0], c[1], c[2], c[3]);
        }
    }

    private static void headsCase(int channels, int hidden, int players, int batch) {
        NetworkConfig config = new NetworkConfig(channels, 1, players, hidden);
        // Weights of about init size, biases included, so that the values are spread over (-1, 1).
        float bound = (float) Math.sqrt(3.0 / Math.max(channels, hidden));
        Parameters p = Parameters.uniform(config, 100 + batch, bound);
        SplittableRandom random = new SplittableRandom(7L * channels + batch);
        float[] trunk = OpsTesting.trunk(random, batch, channels, Float.NaN);
        float[] logits = new float[batch * Encoder.POLICY_SIZE];
        float[] values = new float[batch * players];
        Arrays.fill(logits, Float.NaN);
        Arrays.fill(values, Float.NaN);
        try (OpsHeads heads = new OpsHeads(new OpsParameters(p), batch, TornadoSupport.defaultDevice())) {
            heads.apply(trunk, logits, values);
        }
        double policyWorst = OpsTesting.assertAgrees("logits", OpsTesting.policyReference(p, trunk, batch), logits,
                logits.length);
        float[] expectedValues = OpsTesting.valueReference(p, trunk, batch);
        double valueWorst = OpsTesting.assertAgrees("values", expectedValues, values, values.length);
        // tanh is only tested if some values are well inside (-1, 1), where tanh(z) differs from z and from +-1.
        long unsaturated = 0;
        for (float v : expectedValues) {
            if (Math.abs(v) > 0.05 && Math.abs(v) < 0.9) {
                unsaturated++;
            }
        }
        assertTrue(unsaturated * 4 >= expectedValues.length || batch * players < 8, "weights saturate tanh: "
                + Arrays.toString(Arrays.copyOf(expectedValues, Math.min(12, expectedValues.length))));
        System.out.printf("OpsHeads C=%d H=%d P=%d batch=%d: largest error / bound: logits %.4f, values %.4f%n",
                channels, hidden, players, batch, policyWorst, valueWorst);
    }

    /**
     * The heads on top of the reference trunk give the outputs of {@link Network#forward}. Parameters from
     * {@link Parameters#init} and from {@link Parameters#uniform} with the init bound sqrt(6 / 7C), the weights
     * for which docs/network.md states the tolerance.
     */
    @Test
    void theHeadsOnTheReferenceTrunkEqualNetwork() {
        networkCase("tiny", 2, 1, false);
        networkCase("tiny", 3, 3, true);
        networkCase("tiny", 2, 64, true);
        networkCase("tiny", 3, 513, false);
        networkCase("small", 2, 3, true);
        networkCase("small", 3, 64, false);
        networkCase("large", 2, 3, true);
    }

    private static void networkCase(String preset, int players, int batch, boolean uniform) {
        NetworkConfig config = switch (preset) {
            case "tiny" -> NetworkConfig.tiny(players);
            case "small" -> NetworkConfig.small(players);
            case "large" -> NetworkConfig.large(players);
            default -> throw new IllegalArgumentException(preset);
        };
        Parameters p = uniform
                ? Parameters.uniform(config, 5, (float) Math.sqrt(6.0 / (7 * config.channels())))
                : Parameters.init(config, 5);
        float[] input = OpsTesting.input(new SplittableRandom(batch), batch);
        float[] refLogits = new float[batch * Encoder.POLICY_SIZE];
        float[] refValues = new float[batch * players];
        new Network(p).forward(input, batch, refLogits, refValues);

        float[] trunk = OpsTesting.referenceTrunk(p, input, batch);
        float[] logits = new float[refLogits.length];
        float[] values = new float[refValues.length];
        try (OpsHeads heads = new OpsHeads(new OpsParameters(p), batch, TornadoSupport.defaultDevice())) {
            heads.apply(trunk, logits, values);
        }
        double policyWorst = OpsTesting.assertAgrees("logits", refLogits, logits, logits.length);
        double valueWorst = OpsTesting.assertAgrees("values", refValues, values, values.length);
        System.out.printf("OpsHeads vs Network %s P=%d batch=%d uniform=%b: largest error / bound: logits %.4f,"
                + " values %.4f%n", preset, players, batch, uniform, policyWorst, valueWorst);
    }

    @Test
    void parametersStayOnTheDeviceAndOneTransferBringsBothHeadsBack() {
        NetworkConfig config = NetworkConfig.small(2);
        Parameters p = Parameters.uniform(config, 9, 0.2f);
        int batch = 64;
        float[] trunk = OpsTesting.trunk(new SplittableRandom(9), batch, config.channels(), 0f);
        long trunkBytes = 4L * trunk.length;
        // Only arrays that a task reads are copied; the convolution weights of the declared set are not.
        long headBytes = 4L * (2 * config.channels() + 2 + (config.channels() + 1) * config.valueHidden()
                + (config.valueHidden() + 1) * config.players());
        long outBytes = 4L * OpsKernels.outputSize(batch, 2);
        try (OpsHeads heads = new OpsHeads(new OpsParameters(p), batch, TornadoSupport.defaultDevice())) {
            float[] logits = new float[batch * Encoder.POLICY_SIZE];
            float[] values = new float[batch * 2];
            heads.apply(trunk, logits, values);
            ExecutionProfile first = heads.lastProfile();
            heads.apply(trunk, logits, values);
            ExecutionProfile second = heads.lastProfile();
            System.out.println("OpsHeads SMALL batch 64: first " + first + ", second " + second);
            assertTrue(first.compileNanos() > 0, "first call compiles: " + first);
            assertEquals(0, second.compileNanos(), "second call reuses the kernels: " + second);
            assertTrue(second.kernelNanos() > 0, "kernels ran on the device: " + second);
            assertTrue(first.bytesToDevice() >= trunkBytes + headBytes, "first call sends the head parameters: "
                    + first);
            // The head parameters of SMALL (4,752 bytes) are more than the slack allowed here.
            assertTrue(second.bytesToDevice() >= trunkBytes && second.bytesToDevice() < trunkBytes + 1024,
                    "second call sends the trunk only: " + second);
            // One output buffer: the logits and values plus at most one array header.
            assertTrue(second.bytesToHost() >= outBytes && second.bytesToHost() <= outBytes + 64,
                    "one transfer of " + outBytes + " bytes back: " + second);
        }
    }

    /**
     * In-place changes of the {@link Parameters} are invisible until {@link OpsParameters#reload()}; after it,
     * every plan that shares the {@link OpsParameters} uses the new values, and copies them to the device once.
     */
    @Test
    void reloadUpdatesTheResidentParametersOfEveryPlan() {
        NetworkConfig config = NetworkConfig.tiny(3);
        Parameters p = Parameters.uniform(config, 11, 0.4f);
        OpsParameters onDevice = new OpsParameters(p);
        SplittableRandom random = new SplittableRandom(11);
        float[] trunk1 = OpsTesting.trunk(random, 1, config.channels(), 0f);
        float[] trunk3 = OpsTesting.trunk(random, 3, config.channels(), 0f);
        try (OpsHeads one = new OpsHeads(onDevice, 1, TornadoSupport.defaultDevice());
                OpsHeads three = new OpsHeads(onDevice, 3, TornadoSupport.defaultDevice())) {
            check(one, p, trunk1, 1);
            check(three, p, trunk3, 3);
            float[] oldLogits = OpsTesting.policyReference(p, trunk3, 3);
            float[] oldValues = OpsTesting.valueReference(p, trunk3, 3);

            // A training step, in place: every head tensor changes.
            for (String name : new String[] {"head_policy.weight", "head_policy.bias", "head_value.dense1.weight",
                    "head_value.dense1.bias", "head_value.dense2.weight", "head_value.dense2.bias"}) {
                float[] data = p.get(name).data();
                for (int i = 0; i < data.length; i++) {
                    data[i] = -data[i] + 0.05f;
                }
            }
            float[] logits = new float[3 * Encoder.POLICY_SIZE];
            float[] values = new float[9];
            three.apply(trunk3, logits, values);
            OpsTesting.assertAgrees("before reload: old logits", oldLogits, logits, logits.length);
            OpsTesting.assertAgrees("before reload: old values", oldValues, values, values.length);
            long before = three.lastProfile().bytesToDevice();

            onDevice.reload();
            assertEquals(1, onDevice.version());
            check(three, p, trunk3, 3);
            long withReload = three.lastProfile().bytesToDevice();
            check(three, p, trunk3, 3);
            long after = three.lastProfile().bytesToDevice();
            check(one, p, trunk1, 1);
            // plan.transferToDevice copies outside execute(), so the profiler of the call does not count it.
            assertEquals(before, withReload, "execute() itself sends the trunk only");
            assertEquals(before, after, "execute() itself sends the trunk only");
        }
    }

    @Test
    void wrongSizesAreRejected() {
        NetworkConfig config = NetworkConfig.tiny(2);
        OpsParameters onDevice = new OpsParameters(Parameters.uniform(config, 1, 0.1f));
        var device = TornadoSupport.defaultDevice();
        assertThrows(IllegalArgumentException.class, () -> new OpsHeads(onDevice, 0, device));
        assertThrows(IllegalArgumentException.class, () -> onDevice.get("head_policy"));
        try (OpsHeads heads = new OpsHeads(onDevice, 2, device)) {
            int trunk = 2 * HexGrid.ROWS * config.channels();
            assertThrows(IllegalStateException.class, heads::lastProfile);
            assertThrows(IllegalArgumentException.class,
                    () -> heads.apply(new float[trunk - 1], new float[484], new float[4]));
            assertThrows(IllegalArgumentException.class,
                    () -> heads.apply(new float[trunk], new float[483], new float[4]));
            assertThrows(IllegalArgumentException.class,
                    () -> heads.apply(new float[trunk], new float[484], new float[3]));
        }
    }

    private static void check(OpsHeads heads, Parameters p, float[] trunk, int batch) {
        float[] logits = new float[batch * Encoder.POLICY_SIZE];
        float[] values = new float[batch * p.config().players()];
        heads.apply(trunk, logits, values);
        OpsTesting.assertAgrees("logits", OpsTesting.policyReference(p, trunk, batch), logits, logits.length);
        OpsTesting.assertAgrees("values", OpsTesting.valueReference(p, trunk, batch), values, values.length);
    }

    private static void set(Parameters p, String name, float... values) {
        float[] data = p.get(name).data();
        assertEquals(data.length, values.length, name);
        System.arraycopy(values, 0, data, 0, values.length);
    }
}
