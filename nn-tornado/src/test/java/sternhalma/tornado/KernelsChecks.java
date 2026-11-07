package sternhalma.tornado;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.SplittableRandom;

import sternhalma.nn.HexGrid;
import sternhalma.nn.Network;
import sternhalma.nn.ReferenceOps;

/** Oracles, random data and the tolerance check of docs/network.md for the kernel tests. */
final class KernelsChecks {

    private KernelsChecks() {
    }

    /**
     * The largest {@code |actual - expected| / (1e-4 + 1e-3 * |expected|)} over all elements: at most 1 means the
     * arrays agree within the tolerance of docs/network.md. A NaN in {@code actual} gives infinity.
     */
    static double worstRatio(float[] expected, float[] actual) {
        assertEquals(expected.length, actual.length, "length");
        double worst = 0;
        for (int i = 0; i < expected.length; i++) {
            double error = Math.abs((double) actual[i] - expected[i]);
            double ratio = Double.isNaN(error) ? Double.POSITIVE_INFINITY
                    : error / (Network.ABSOLUTE_TOLERANCE + Network.RELATIVE_TOLERANCE * Math.abs(expected[i]));
            worst = Math.max(worst, ratio);
        }
        return worst;
    }

    /** The largest absolute difference; NaN anywhere gives infinity. */
    static double worstError(float[] expected, float[] actual) {
        double worst = 0;
        for (int i = 0; i < expected.length; i++) {
            double error = Math.abs((double) actual[i] - expected[i]);
            worst = Math.max(worst, Double.isNaN(error) ? Double.POSITIVE_INFINITY : error);
        }
        return worst;
    }

    /** Fails with the first element outside the tolerance; returns the worst ratio for reporting. */
    static double assertAgrees(String what, float[] expected, float[] actual) {
        assertEquals(expected.length, actual.length, what + ": length");
        for (int i = 0; i < expected.length; i++) {
            if (!Network.agrees(actual[i], expected[i])) {
                fail(what + ": element " + i + ": expected " + expected[i] + ", got " + actual[i]);
            }
        }
        return worstRatio(expected, actual);
    }

    /**
     * The oracle for a hex convolution layer, from {@link ReferenceOps} (which computes the gather from
     * {@code Geometry.neighbor}, not from the table the kernels upload): per position hexConv, then the residual
     * add, then ReLU, as {@code Network} does.
     */
    static float[] referenceConv(float[] in, float[] weight, float[] bias, float[] res, int batch, int inChannels,
            int outChannels, boolean relu) {
        int inSize = HexGrid.ROWS * inChannels;
        int outSize = HexGrid.ROWS * outChannels;
        float[] out = new float[batch * outSize];
        for (int b = 0; b < batch; b++) {
            ReferenceOps.hexConv(in, b * inSize, inChannels, weight, bias, outChannels, out, b * outSize);
        }
        if (res != null) {
            ReferenceOps.add(out, 0, res, 0, out.length);
        }
        if (relu) {
            ReferenceOps.relu(out, 0, out.length);
        }
        return out;
    }

    /** Uniform in [-bound, bound). */
    static float[] uniform(SplittableRandom random, int n, double bound) {
        float[] values = new float[n];
        for (int i = 0; i < n; i++) {
            values[i] = (float) random.nextDouble(-bound, bound);
        }
        return values;
    }

    /** {@code batch} activations [122][channels], uniform in [-1, 1) on the cells, row 121 zero. */
    static float[] activations(SplittableRandom random, int batch, int channels) {
        float[] a = uniform(random, batch * HexGrid.ROWS * channels, 1);
        for (int b = 0; b < batch; b++) {
            int padding = (b * HexGrid.ROWS + HexGrid.PADDING) * channels;
            java.util.Arrays.fill(a, padding, padding + channels, 0f);
        }
        return a;
    }

    /** The init bound of docs/network.md for a hex convolution, sqrt(6 / (7 * inChannels)). */
    static double convBound(int inChannels) {
        return Math.sqrt(6.0 / (HexGrid.STENCIL * inChannels));
    }
}
