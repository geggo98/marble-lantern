package sternhalma.tornado;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sternhalma.tornado.KernelsChecks.assertAgrees;
import static sternhalma.tornado.KernelsChecks.uniform;
import static sternhalma.tornado.KernelsChecks.worstError;

import java.util.Arrays;
import java.util.SplittableRandom;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import sternhalma.nn.Encoder;
import sternhalma.nn.HexGrid;
import sternhalma.nn.NetworkConfig;
import sternhalma.nn.ReferenceOps;

class KernelsDenseTest {

    private static double worstRatio;
    private static double worstAbsolute;

    @BeforeEach
    void requireTornado() {
        TornadoAssumptions.assumeTornado();
    }

    @AfterAll
    static void report() {
        System.out.printf("KernelsDense: largest error %.3g absolute, %.4f of the tolerance%n", worstAbsolute,
                worstRatio);
    }

    /**
     * The policy head reads the 121 cell rows of each [122][C] activation. The padding rows hold NaN here: a kernel
     * that reads them, or steps through the batch with 121 instead of 122 rows, gives NaN or wrong logits.
     */
    @Test
    void thePolicyHeadMatchesTheReferenceAndSkipsThePaddingRow() {
        for (int[] c : new int[][] {{8, 1}, {32, 1}, {32, 5}, {64, 1}, {64, 33}}) {
            thePolicyHeadMatchesTheReferenceAndSkipsThePaddingRow(c[0], c[1]);
        }
    }

    private static void thePolicyHeadMatchesTheReferenceAndSkipsThePaddingRow(int channels, int batch) {
        SplittableRandom random = new SplittableRandom(channels * 100L + batch);
        int out = Encoder.POLICY_CHANNELS;
        float[] weight = uniform(random, out * channels, Math.sqrt(6.0 / channels));
        float[] bias = uniform(random, out, 1);
        float[] in = uniform(random, batch * HexGrid.ROWS * channels, 1);
        for (int b = 0; b < batch; b++) {
            int padding = (b * HexGrid.ROWS + HexGrid.PADDING) * channels;
            Arrays.fill(in, padding, padding + channels, Float.NaN);
        }
        float[] expected = new float[batch * HexGrid.CELLS * out];
        for (int b = 0; b < batch; b++) {
            ReferenceOps.pointwise(in, b * HexGrid.ROWS * channels, HexGrid.CELLS, channels, weight, bias, out,
                    expected, b * HexGrid.CELLS * out);
        }
        float[] actual = run(weight, bias, batch, HexGrid.CELLS, HexGrid.ROWS, channels, out, KernelsDense.NONE, in);
        record("policy C=" + channels + " batch=" + batch, expected, actual);
        assertTrue(Arrays.stream(box(expected)).anyMatch(v -> v < 0), "logits are not clipped");
    }

    @Test
    void theFirstValueLayerMatchesTheReferenceWithReLU() {
        for (int[] c : new int[][] {{8, 1}, {32, 1}, {32, 7}, {64, 1}, {64, 65}}) {
            theFirstValueLayerMatchesTheReferenceWithReLU(c[0], c[1]);
        }
    }

    private static void theFirstValueLayerMatchesTheReferenceWithReLU(int channels, int batch) {
        SplittableRandom random = new SplittableRandom(channels * 1000L + batch);
        int hidden = NetworkConfig.VALUE_HIDDEN;
        float[] weight = uniform(random, hidden * channels, Math.sqrt(6.0 / channels));
        float[] bias = uniform(random, hidden, 1);
        float[] in = uniform(random, batch * channels, 1);
        float[] expected = new float[batch * hidden];
        for (int b = 0; b < batch; b++) {
            ReferenceOps.dense(in, b * channels, channels, weight, bias, hidden, expected, b * hidden);
        }
        long negatives = Arrays.stream(box(expected)).filter(v -> v < 0).count();
        ReferenceOps.relu(expected, 0, expected.length);
        float[] actual = run(weight, bias, batch, 1, 1, channels, hidden, KernelsDense.RELU, in);
        record("dense1 C=" + channels + " batch=" + batch, expected, actual);
        assertTrue(negatives > expected.length / 5, "ReLU clips many outputs: " + negatives);
    }

    @Test
    void theSecondValueLayerMatchesTheReferenceWithTanh() {
        for (int[] c : new int[][] {{2, 1}, {3, 1}, {2, 9}, {3, 70}}) {
            theSecondValueLayerMatchesTheReferenceWithTanh(c[0], c[1]);
        }
    }

    private static void theSecondValueLayerMatchesTheReferenceWithTanh(int players, int batch) {
        SplittableRandom random = new SplittableRandom(players * 10000L + batch);
        int hidden = NetworkConfig.VALUE_HIDDEN;
        float[] weight = uniform(random, players * hidden, Math.sqrt(6.0 / hidden));
        float[] bias = uniform(random, players, 1);
        // Hidden values are ReLU outputs, so non-negative; scaled so that tanh is neither linear nor saturated.
        float[] in = uniform(random, batch * hidden, 1);
        for (int i = 0; i < in.length; i++) {
            in[i] = Math.abs(in[i]) * 0.5f;
        }
        float[] expected = new float[batch * players];
        for (int b = 0; b < batch; b++) {
            ReferenceOps.dense(in, b * hidden, hidden, weight, bias, players, expected, b * players);
        }
        ReferenceOps.tanh(expected, 0, expected.length);
        float[] actual = run(weight, bias, batch, 1, 1, hidden, players, KernelsDense.TANH, in);
        record("dense2 P=" + players + " batch=" + batch, expected, actual);
    }

    /**
     * Two groups of three rows, two of which are used, two inputs and two outputs: out = act(W in + b) by hand.
     * W = [[1, 2], [-3, 0.5]], b = [0.25, -1]. Row 2 of each group is not used and holds a huge value.
     */
    @Test
    void aHandComputedCaseFixesTheRowMappingAndTheActivations() {
        float[] weight = {1, 2, -3, 0.5f};
        float[] bias = {0.25f, -1};
        float big = 1e30f;
        float[] in = {
            1, 1,     // group 0, row 0: [1 + 2 + 0.25, -3 + 0.5 - 1]  = [3.25, -3.5]
            2, -1,    // group 0, row 1: [2 - 2 + 0.25, -6 - 0.5 - 1]  = [0.25, -7.5]
            big, big, // group 0, row 2: not used
            0, 4,     // group 1, row 0: [8 + 0.25, 2 - 1]            = [8.25, 1]
            -1, 0,    // group 1, row 1: [-1 + 0.25, 3 - 1]           = [-0.75, 2]
            big, big, // group 1, row 2: not used
        };
        float[] linear = {3.25f, -3.5f, 0.25f, -7.5f, 8.25f, 1, -0.75f, 2};
        assertArrayEquals(linear, run(weight, bias, 2, 2, 3, 2, 2, KernelsDense.NONE, in));
        float[] relu = {3.25f, 0, 0.25f, 0, 8.25f, 1, 0, 2};
        assertArrayEquals(relu, run(weight, bias, 2, 2, 3, 2, 2, KernelsDense.RELU, in));
        float[] tanh = new float[linear.length];
        for (int i = 0; i < tanh.length; i++) {
            tanh[i] = (float) Math.tanh(linear[i]);
        }
        assertAgrees("tanh", tanh, run(weight, bias, 2, 2, 3, 2, 2, KernelsDense.TANH, in));
    }

    /**
     * Saturation and NaN: one input, weight 1, bias 0, so out = act(in). tanh must give exactly +-1 where the
     * reference does (Metal's {@code tanh} gives 0 from about 44 on and NaN from about 44.4) and keep NaN; ReLU
     * must keep NaN (Metal's {@code fmax}, which {@code Math.max} compiles to, drops it).
     */
    @Test
    void tanhSaturatesAndBothActivationsKeepNaN() {
        float[] in = {44f, 44.3f, 44.5f, 100f, 1e30f, Float.POSITIVE_INFINITY, -44.5f, -1000f,
            Float.NEGATIVE_INFINITY, Float.NaN, 9.5f, -0.5f};
        float[] tanh = new float[in.length];
        float[] relu = new float[in.length];
        for (int i = 0; i < in.length; i++) {
            tanh[i] = (float) Math.tanh(in[i]);
            relu[i] = Math.max(in[i], 0f);
        }
        float[] one = {1f};
        float[] zero = {0f};
        float[] actualTanh = run(one, zero, in.length, 1, 1, 1, 1, KernelsDense.TANH, in);
        float[] actualRelu = run(one, zero, in.length, 1, 1, 1, 1, KernelsDense.RELU, in);
        for (int i = 0; i < in.length; i++) {
            if (Float.isNaN(tanh[i]) || Math.abs(tanh[i]) == 1f) {
                assertEquals(tanh[i], actualTanh[i], "tanh(" + in[i] + ")");   // NaN equals NaN here
            } else {
                assertAgrees("tanh(" + in[i] + ")", new float[] {tanh[i]}, new float[] {actualTanh[i]});
            }
            assertEquals(relu[i], actualRelu[i], "relu(" + in[i] + ")");
        }
    }

    @Test
    void wrongArgumentsAreRejected() {
        var device = TornadoSupport.defaultDevice();
        float[] w = new float[6];
        float[] b = new float[2];
        assertThrows(IllegalArgumentException.class, () -> new KernelsDenseLayer(w, b, 0, 1, 1, 3, 2, 0, device));
        assertThrows(IllegalArgumentException.class, () -> new KernelsDenseLayer(w, b, 1, 2, 1, 3, 2, 0, device));
        assertThrows(IllegalArgumentException.class, () -> new KernelsDenseLayer(w, b, 1, 1, 1, 3, 2, 3, device));
        assertThrows(IllegalArgumentException.class,
                () -> new KernelsDenseLayer(new float[5], b, 1, 1, 1, 3, 2, 0, device));
        assertThrows(IllegalArgumentException.class,
                () -> new KernelsDenseLayer(w, new float[3], 1, 1, 1, 3, 2, 0, device));
        try (KernelsDenseLayer layer = new KernelsDenseLayer(w, b, 2, 1, 1, 3, 2, 0, device)) {
            assertThrows(IllegalStateException.class, layer::lastProfile);
            assertThrows(IllegalArgumentException.class, () -> layer.apply(new float[5]));
            assertArrayEquals(new float[4], layer.apply(new float[6]));
        }
    }

    private static float[] run(float[] weight, float[] bias, int groups, int groupRows, int inGroupRows,
            int inSize, int outSize, int activation, float[] in) {
        try (KernelsDenseLayer layer = new KernelsDenseLayer(weight, bias, groups, groupRows, inGroupRows, inSize,
                outSize, activation, TornadoSupport.defaultDevice())) {
            float[] out = layer.apply(in);
            assertTrue(layer.lastProfile().kernelNanos() > 0, "the kernel ran on the device");
            return out;
        }
    }

    private static void record(String what, float[] expected, float[] actual) {
        double ratio = assertAgrees(what, expected, actual);
        worstRatio = Math.max(worstRatio, ratio);
        worstAbsolute = Math.max(worstAbsolute, worstError(expected, actual));
    }

    private static Float[] box(float[] a) {
        Float[] boxed = new Float[a.length];
        for (int i = 0; i < a.length; i++) {
            boxed[i] = a[i];
        }
        return boxed;
    }
}
