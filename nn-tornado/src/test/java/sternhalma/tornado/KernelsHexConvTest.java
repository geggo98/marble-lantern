package sternhalma.tornado;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sternhalma.tornado.KernelsChecks.activations;
import static sternhalma.tornado.KernelsChecks.assertAgrees;
import static sternhalma.tornado.KernelsChecks.convBound;
import static sternhalma.tornado.KernelsChecks.referenceConv;
import static sternhalma.tornado.KernelsChecks.uniform;
import static sternhalma.tornado.KernelsChecks.worstError;
import static sternhalma.tornado.KernelsChecks.worstRatio;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import sternhalma.nn.Encoder;
import sternhalma.nn.HexGrid;
import sternhalma.nn.NetworkConfig;
import sternhalma.tornado.KernelsHexConvLayer.Variant;

class KernelsHexConvTest {

    /** The largest error ratio (error / tolerance) of {@link #bothKernelsMatchTheReference}, per variant. */
    private static final double[] WORST = new double[Variant.values().length];
    private static final double[] WORST_ABSOLUTE = new double[Variant.values().length];

    @BeforeEach
    void requireTornado() {
        TornadoAssumptions.assumeTornado();
    }

    @AfterAll
    static void report() {
        for (Variant v : Variant.values()) {
            if (WORST_ABSOLUTE[v.ordinal()] == 0 && WORST[v.ordinal()] == 0) {
                System.out.printf("KernelsHexConv %s: not run on this device%n", v);
                continue;
            }
            System.out.printf("KernelsHexConv %s: largest error %.3g absolute, %.4f of the tolerance%n", v,
                    WORST_ABSOLUTE[v.ordinal()], WORST[v.ordinal()]);
        }
    }

    /** The kind of layer, as in docs/network.md "Layers". */
    enum Kind {
        /** conv_in: 7 planes to C channels, ReLU. */
        CONV_IN,
        /** block conv1: C to C, ReLU. */
        CONV1,
        /** block conv2: C to C, add the block input, ReLU. */
        CONV2,
        /** No residual, no ReLU: negative outputs must survive. */
        LINEAR
    }

    /**
     * Every layer shape of the three presets, both kernels, batch 1 and batches whose 122-row activations fill no
     * tile evenly. Dynamic tests, not @ParameterizedTest: junit-jupiter-params cannot reach JUnit's commons
     * module, which the TornadoVM module path supplies (docs/tornado.md, "Pitfalls").
     */
    @TestFactory
    Stream<DynamicTest> bothKernelsMatchTheReference() {
        List<DynamicTest> cases = new ArrayList<>();
        NetworkConfig[] presets = {NetworkConfig.tiny(2), NetworkConfig.small(2), NetworkConfig.large(2)};
        // 1, and batches whose 122-row activations fill no tile evenly (3 * 122 = 366, 67 * 122 = 8174).
        int[] batches = {1, 3, 67};
        for (Variant variant : KernelsChecks.variants()) {
            for (NetworkConfig preset : presets) {
                for (Kind kind : Kind.values()) {
                    for (int batch : batches) {
                        int channels = preset.channels();
                        cases.add(DynamicTest.dynamicTest(variant + " C=" + channels + " " + kind + " batch=" + batch,
                                () -> matchesTheReference(variant, channels, kind, batch)));
                    }
                }
            }
        }
        return cases.stream();
    }

    private static void matchesTheReference(Variant variant, int channels, Kind kind, int batch) {
        int in = kind == Kind.CONV_IN ? Encoder.PLANES : channels;
        boolean residual = kind == Kind.CONV2;
        boolean relu = kind != Kind.LINEAR;
        SplittableRandom random = new SplittableRandom(31L * channels + 7L * batch + kind.ordinal());
        float[] weight = uniform(random, channels * HexGrid.STENCIL * in, convBound(in));
        float[] bias = uniform(random, channels, convBound(in));
        float[] input = activations(random, batch, in);
        float[] res = residual ? activations(random, batch, channels) : null;
        float[] expected = referenceConv(input, weight, bias, res, batch, in, channels, relu);

        float[] actual;
        try (KernelsHexConvLayer layer = new KernelsHexConvLayer(variant, weight, bias, in, channels, batch,
                residual, relu, TornadoSupport.defaultDevice())) {
            actual = residual ? layer.apply(input, res) : layer.apply(input);
            assertTrue(layer.lastProfile().kernelNanos() > 0, "the kernel ran on the device");
        }
        double ratio = assertAgrees(variant + " C=" + channels + " " + kind + " batch=" + batch, expected, actual);
        synchronized (WORST) {
            WORST[variant.ordinal()] = Math.max(WORST[variant.ordinal()], ratio);
            WORST_ABSOLUTE[variant.ordinal()] = Math.max(WORST_ABSOLUTE[variant.ordinal()],
                    worstError(expected, actual));
        }
        // The data must make the activation matter, or a missing or extra ReLU would pass.
        long zeros = 0;
        long negatives = 0;
        for (float v : expected) {
            zeros += v == 0f ? 1 : 0;
            negatives += v < 0f ? 1 : 0;
        }
        if (relu) {
            assertTrue(zeros > expected.length / 5, "ReLU clips many outputs: " + zeros);
        } else {
            assertTrue(negatives > expected.length / 5, "many outputs are negative: " + negatives);
        }
    }

    @Test
    void theOptimisedKernelsAgreeWithTheNaiveKernel() {
        for (int channels : new int[] {32, 64}) {
            SplittableRandom random = new SplittableRandom(channels);
            int batch = 9;
            float[] weight = uniform(random, channels * HexGrid.STENCIL * channels, convBound(channels));
            float[] bias = uniform(random, channels, convBound(channels));
            float[] input = activations(random, batch, channels);
            float[] res = activations(random, batch, channels);
            float[] naive = run(Variant.NAIVE, weight, bias, channels, channels, batch, input, res, true);
            for (Variant variant : KernelsChecks.variants()) {
                if (variant == Variant.NAIVE) {
                    continue;
                }
                float[] fast = run(variant, weight, bias, channels, channels, batch, input, res, true);
                double ratio = assertAgrees(variant + " against naive, C=" + channels, naive, fast);
                System.out.printf("KernelsHexConv %s against naive, C=%d: largest difference %.3g, %.4f of the"
                        + " tolerance%n", variant, channels, worstError(naive, fast), ratio);
            }
        }
    }

    /**
     * Cells 0 (a corner tip with two neighbours) and 60 (the centre), one input channel, one output channel per
     * gather entry: output o is the input at gather entry o plus bias o. The expected values are written out by
     * hand from the Geometry directions (+1, 0), (-1, 0), (0, +1), (0, -1), (+1, -1), (-1, +1): cell 0 has
     * neighbours 2 (direction 2) and 1 (direction 5), cell 60 has 61, 59, 70, 50, 51, 69. Every value is exact
     * in float, so the comparison is exact.
     */
    @Test
    void aHandComputedCaseFixesTheNeighbourOrderTheBiasAndTheBatchOffset() {
        forEachVariant(KernelsHexConvTest::aHandComputedCaseFixesTheNeighbourOrderTheBiasAndTheBatchOffset);
    }

    private static void aHandComputedCaseFixesTheNeighbourOrderTheBiasAndTheBatchOffset(Variant variant) {
        int out = HexGrid.STENCIL;
        float[] weight = new float[out * HexGrid.STENCIL];
        for (int o = 0; o < out; o++) {
            weight[o * HexGrid.STENCIL + o] = 1f;
        }
        float[] bias = {0.5f, -0.25f, 0.125f, 1f, -2f, 4f, -8f};
        int batch = 2;
        float[] input = new float[batch * HexGrid.ROWS];
        for (int cell = 0; cell < HexGrid.CELLS; cell++) {
            input[cell] = cell + 1;                         // position 0: cell + 1
            input[HexGrid.ROWS + cell] = 1000 + cell;       // position 1: 1000 + cell
        }
        float[] result = run(variant, weight, bias, 1, out, batch, input, null, false);

        float[][] expected = {
            // position 0, cell 0: itself, -, -, cell 2, -, -, cell 1
            {1, 0, 0, 3, 0, 0, 2},
            // position 0, cell 60: itself, 61, 59, 70, 50, 51, 69
            {61, 62, 60, 71, 51, 52, 70},
            // position 1, cell 0
            {1000, 0, 0, 1002, 0, 0, 1001},
            // position 1, cell 60
            {1060, 1061, 1059, 1070, 1050, 1051, 1069},
        };
        int[] rows = {0, 60, HexGrid.ROWS, HexGrid.ROWS + 60};
        for (int i = 0; i < rows.length; i++) {
            float[] row = new float[out];
            float[] want = new float[out];
            for (int o = 0; o < out; o++) {
                row[o] = result[rows[i] * out + o];
                want[o] = expected[i][o] + bias[o];
            }
            assertArrayEquals(want, row, "row " + rows[i]);
        }
        for (int b = 0; b < batch; b++) {
            for (int o = 0; o < out; o++) {
                assertEquals(0f, result[(b * HexGrid.ROWS + HexGrid.PADDING) * out + o], "padding row");
            }
        }
    }

    /**
     * The device output buffer starts as NaN, and the bias is large and positive. A kernel that skips an
     * element leaves NaN; a kernel that computes the padding row like a cell writes at least the bias there.
     */
    @Test
    void everyElementIsWrittenAndThePaddingRowIsZero() {
        forEachVariant(KernelsHexConvTest::everyElementIsWrittenAndThePaddingRowIsZero);
    }

    private static void everyElementIsWrittenAndThePaddingRowIsZero(Variant variant) {
        // 8 output channels: a tile of 32 channels is three quarters masked.
        for (int channels : new int[] {8, 64}) {
            SplittableRandom random = new SplittableRandom(5);
            int batch = 3;
            float[] weight = uniform(random, channels * HexGrid.STENCIL * channels, convBound(channels));
            float[] bias = new float[channels];
            java.util.Arrays.fill(bias, 100f);
            float[] input = activations(random, batch, channels);
            float[] res = activations(random, batch, channels);
            float[] result;
            try (KernelsHexConvLayer layer = new KernelsHexConvLayer(variant, weight, bias, channels, channels,
                    batch, true, true, TornadoSupport.defaultDevice(), HexGrid.gatherTable(), Float.NaN)) {
                result = layer.apply(input, res);
            }
            for (int i = 0; i < result.length; i++) {
                int row = (i / channels) % HexGrid.ROWS;
                if (row == HexGrid.PADDING) {
                    assertEquals(0f, result[i], "padding row, element " + i);
                } else {
                    assertTrue(result[i] > 50f, "cell row, element " + i + ": " + result[i]);
                }
            }
        }
    }

    /**
     * The random comparison must be able to fail: it is run against kernels fed with plausible bugs, and each
     * must exceed the tolerance by far. If this test fails, the comparison above proves nothing.
     */
    @Test
    void theComparisonCatchesSwappedNeighboursAMissingBiasAndAMissingResidual() {
        forEachVariant(KernelsHexConvTest::theComparisonCatchesSwappedNeighboursAMissingBiasAndAMissingResidual);
    }

    private static void theComparisonCatchesSwappedNeighboursAMissingBiasAndAMissingResidual(Variant variant) {
        int channels = 32;
        int batch = 2;
        SplittableRandom random = new SplittableRandom(77);
        float[] weight = uniform(random, channels * HexGrid.STENCIL * channels, convBound(channels));
        float[] bias = uniform(random, channels, convBound(channels));
        float[] input = activations(random, batch, channels);
        float[] res = activations(random, batch, channels);
        float[] expected = referenceConv(input, weight, bias, res, batch, channels, channels, true);
        var device = TornadoSupport.defaultDevice();

        // Directions 0 and 1 swapped in the gather table (an easy slip: they are opposite directions).
        int[] swapped = HexGrid.gatherTable();
        for (int cell = 0; cell < HexGrid.CELLS; cell++) {
            int t = swapped[cell * HexGrid.STENCIL + 1];
            swapped[cell * HexGrid.STENCIL + 1] = swapped[cell * HexGrid.STENCIL + 2];
            swapped[cell * HexGrid.STENCIL + 2] = t;
        }
        try (KernelsHexConvLayer layer = new KernelsHexConvLayer(variant, weight, bias, channels, channels, batch,
                true, true, device, swapped, 0f)) {
            assertTrue(worstRatio(expected, layer.apply(input, res)) > 10, "swapped neighbours are detected");
        }
        try (KernelsHexConvLayer layer = new KernelsHexConvLayer(variant, weight, new float[channels], channels,
                channels, batch, true, true, device)) {
            assertTrue(worstRatio(expected, layer.apply(input, res)) > 10, "a missing bias is detected");
        }
        try (KernelsHexConvLayer layer = new KernelsHexConvLayer(variant, weight, bias, channels, channels, batch,
                false, true, device)) {
            assertTrue(worstRatio(expected, layer.apply(input)) > 10, "a missing residual is detected");
        }
        // And the unmutated kernel passes, so the three failures above come from the mutations.
        assertTrue(worstRatio(expected, run(variant, weight, bias, channels, channels, batch, input, res, true)) <= 1);
    }

    @Test
    void weightsStayOnTheDeviceAndRepeatedCallsAreIndependent() {
        forEachVariant(KernelsHexConvTest::weightsStayOnTheDeviceAndRepeatedCallsAreIndependent);
    }

    private static void weightsStayOnTheDeviceAndRepeatedCallsAreIndependent(Variant variant) {
        int channels = 64;
        SplittableRandom random = new SplittableRandom(3);
        float[] weight = uniform(random, channels * HexGrid.STENCIL * channels, convBound(channels));
        float[] bias = uniform(random, channels, convBound(channels));
        float[] in1 = activations(random, 1, channels);
        float[] in2 = activations(random, 1, channels);
        long weightBytes = 4L * weight.length;
        long inputBytes = 4L * in1.length;
        try (KernelsHexConvLayer layer = new KernelsHexConvLayer(variant, weight, bias, channels, channels, 1, false,
                true, TornadoSupport.defaultDevice())) {
            float[] out1 = layer.apply(in1);
            ExecutionProfile first = layer.lastProfile();
            float[] out2 = layer.apply(in2);
            ExecutionProfile second = layer.lastProfile();
            java.util.Arrays.fill(weight, 9f);
            float[] out1Again = layer.apply(in1);

            assertTrue(first.compileNanos() > 0, "first call compiles: " + first);
            assertEquals(0, second.compileNanos(), "second call reuses the kernel: " + second);
            assertTrue(second.kernelNanos() > 0, "the kernel ran on the device: " + second);
            assertTrue(first.bytesToDevice() >= weightBytes + inputBytes, "first call sends the weights: " + first);
            assertTrue(second.bytesToDevice() >= inputBytes && second.bytesToDevice() < weightBytes,
                    "second call sends the input only: " + second);
            assertArrayEquals(out1, out1Again, "same input, same kernel: same result; the caller's weights are"
                    + " not seen");
            assertTrue(worstRatio(out1, out2) > 1, "a new input gives a new result");
        }
    }

    /**
     * A NaN weight makes its output channel NaN in every cell, as in the reference, whose ReLU
     * ({@code Math.max}) keeps NaN. Metal compiles {@code Math.max} to {@code fmax}, which drops NaN, so a ReLU
     * written with it gives 0 or plausible finite values there. With ReLU, with residual and ReLU, and without
     * either.
     */
    @Test
    void aNaNWeightGivesNaNOutputsAsInTheReference() {
        forEachVariant(KernelsHexConvTest::aNaNWeightGivesNaNOutputsAsInTheReference);
    }

    private static void aNaNWeightGivesNaNOutputsAsInTheReference(Variant variant) {
        int channels = 32;
        int batch = 3;
        for (Kind kind : new Kind[] {Kind.CONV1, Kind.CONV2, Kind.LINEAR}) {
            SplittableRandom random = new SplittableRandom(41 + kind.ordinal());
            float[] weight = uniform(random, channels * HexGrid.STENCIL * channels, convBound(channels));
            weight[5 * HexGrid.STENCIL * channels + 10] = Float.NaN;    // output channel 5
            float[] bias = uniform(random, channels, convBound(channels));
            float[] input = activations(random, batch, channels);
            boolean residual = kind == Kind.CONV2;
            boolean relu = kind != Kind.LINEAR;
            float[] res = residual ? activations(random, batch, channels) : null;
            float[] expected = referenceConv(input, weight, bias, res, batch, channels, channels, relu);
            float[] actual = run(variant, weight, bias, channels, channels, batch, input, res, relu);
            int nans = OpsTesting.assertAgreesOrBothNaN(variant + " " + kind, expected, actual);
            assertEquals(batch * HexGrid.CELLS, nans, kind + ": channel 5 is NaN in every cell of the reference");
        }
    }

    /**
     * A layer with residual sends the residual on every call: two calls with the same input and different
     * residuals both agree with the reference. A residual sent only once adds the first residual again.
     */
    @Test
    void theResidualMovesOnEveryCall() {
        forEachVariant(KernelsHexConvTest::theResidualMovesOnEveryCall);
    }

    private static void theResidualMovesOnEveryCall(Variant variant) {
        int channels = 32;
        int batch = 3;
        SplittableRandom random = new SplittableRandom(43);
        float[] weight = uniform(random, channels * HexGrid.STENCIL * channels, convBound(channels));
        float[] bias = uniform(random, channels, convBound(channels));
        float[] input = activations(random, batch, channels);
        try (KernelsHexConvLayer layer = new KernelsHexConvLayer(variant, weight, bias, channels, channels, batch,
                true, true, TornadoSupport.defaultDevice())) {
            for (int call = 0; call < 3; call++) {
                float[] res = activations(random, batch, channels);
                assertAgrees(variant + " call " + call,
                        referenceConv(input, weight, bias, res, batch, channels, channels, true),
                        layer.apply(input, res));
            }
        }
    }

    /**
     * The local barriers of the tiled kernels, under load: LARGE block shape (C = 64, 14 steps of the k loop) at
     * batch 256 with residual, and the LARGE conv_in (7 to 64, 2 steps) at batch 67, both with ReLU, eight calls
     * with new inputs on one layer each. A missing barrier lets a SIMD group overwrite a tile that another one still
     * multiplies. Races depend on timing, so a pass does not prove the barriers; docs/tornado.md says which barrier
     * mutants this case killed.
     */
    @Test
    void theTileBarriersHoldUnderLoad() {
        forEachVariant(KernelsHexConvTest::theTileBarriersHoldUnderLoad);
    }

    private static void theTileBarriersHoldUnderLoad(Variant variant) {
        int channels = 64;
        // {input channels, batch}: the LARGE block (residual) and the LARGE conv_in (no residual).
        for (int[] shape : new int[][] {{channels, 256}, {Encoder.PLANES, 67}}) {
            int in = shape[0];
            int batch = shape[1];
            boolean residual = in == channels;
            SplittableRandom random = new SplittableRandom(47 + in);
            float[] weight = uniform(random, channels * HexGrid.STENCIL * in, convBound(in));
            float[] bias = uniform(random, channels, convBound(in));
            try (KernelsHexConvLayer layer = new KernelsHexConvLayer(variant, weight, bias, in, channels, batch,
                    residual, true, TornadoSupport.defaultDevice())) {
                for (int call = 0; call < 8; call++) {
                    float[] input = activations(random, batch, in);
                    float[] res = residual ? activations(random, batch, channels) : null;
                    assertAgrees(variant + " C_in=" + in + " call " + call,
                            referenceConv(input, weight, bias, res, batch, in, channels, true),
                            residual ? layer.apply(input, res) : layer.apply(input));
                }
            }
        }
    }

    @Test
    void wrongArgumentsAreRejected() {
        var device = TornadoSupport.defaultDevice();
        Variant v = Variant.TILED;
        assertThrows(IllegalArgumentException.class,
                () -> new KernelsHexConvLayer(v, new float[49], new float[1], 7, 1, 0, false, false, device));
        assertThrows(IllegalArgumentException.class,
                () -> new KernelsHexConvLayer(v, new float[48], new float[1], 7, 1, 1, false, false, device));
        assertThrows(IllegalArgumentException.class,
                () -> new KernelsHexConvLayer(v, new float[49], new float[2], 7, 1, 1, false, false, device));
        try (KernelsHexConvLayer plain = new KernelsHexConvLayer(v, new float[49], new float[1], 7, 1, 2, false,
                false, device);
                KernelsHexConvLayer withResidual = new KernelsHexConvLayer(v, new float[49], new float[1], 7, 1, 2,
                        true, false, device)) {
            int inLength = 2 * HexGrid.ROWS * 7;
            assertThrows(IllegalStateException.class, plain::lastProfile);
            assertThrows(IllegalArgumentException.class, () -> plain.apply(new float[inLength - 1]));
            assertThrows(IllegalStateException.class, () -> plain.apply(new float[inLength], new float[244]));
            assertThrows(IllegalStateException.class, () -> withResidual.apply(new float[inLength]));
            assertThrows(IllegalArgumentException.class,
                    () -> withResidual.apply(new float[inLength], new float[243]));
            float[] badPadding = new float[inLength];
            badPadding[(HexGrid.ROWS + HexGrid.PADDING) * 7 + 6] = 1f;   // position 1, plane 6
            assertThrows(IllegalArgumentException.class, () -> plain.apply(badPadding));
            assertEquals(2 * HexGrid.ROWS, plain.apply(new float[inLength]).length);
        }
    }

    /** Runs the check for every kernel; a failure names the kernel. */
    private static void forEachVariant(java.util.function.Consumer<Variant> check) {
        for (Variant variant : KernelsChecks.variants()) {
            try {
                check.accept(variant);
            } catch (AssertionError e) {
                throw new AssertionError(variant + ": " + e.getMessage(), e);
            }
        }
    }

    private static float[] run(Variant variant, float[] weight, float[] bias, int in, int out, int batch,
            float[] input, float[] res, boolean relu) {
        try (KernelsHexConvLayer layer = new KernelsHexConvLayer(variant, weight, bias, in, out, batch, res != null,
                relu, TornadoSupport.defaultDevice())) {
            return res != null ? layer.apply(input, res) : layer.apply(input);
        }
    }
}
