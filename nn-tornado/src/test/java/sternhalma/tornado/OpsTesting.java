package sternhalma.tornado;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.SplittableRandom;

import sternhalma.nn.Encoder;
import sternhalma.nn.HexGrid;
import sternhalma.nn.Network;
import sternhalma.nn.NetworkConfig;
import sternhalma.nn.Parameters;
import sternhalma.nn.ReferenceOps;

/** Reference computations and comparisons for the tests of the Ops... classes. */
final class OpsTesting {

    private OpsTesting() {
    }

    /**
     * Asserts the tolerance of docs/network.md, "Numeric conventions", for every element, and returns the
     * largest error as a fraction of the bound.
     */
    static double assertAgrees(String what, float[] expected, float[] actual, int length) {
        double worst = 0;
        for (int i = 0; i < length; i++) {
            double bound = Network.ABSOLUTE_TOLERANCE + Network.RELATIVE_TOLERANCE * Math.abs(expected[i]);
            double error = Math.abs((double) actual[i] - expected[i]);
            if (!(error <= bound)) {
                fail(what + ", index " + i + ": expected " + expected[i] + ", got " + actual[i]);
            }
            worst = Math.max(worst, error / bound);
        }
        return worst;
    }

    /**
     * Like {@link #assertAgrees}, but NaN is a result too: where {@code expected} is NaN, {@code actual} must be
     * NaN, and where it is not, {@code actual} must agree within the tolerance.
     *
     * @return the number of NaN elements of {@code expected}
     */
    static int assertAgreesOrBothNaN(String what, float[] expected, float[] actual) {
        assertEquals(expected.length, actual.length, what + ": length");
        int nans = 0;
        for (int i = 0; i < expected.length; i++) {
            if (Float.isNaN(expected[i])) {
                nans++;
                if (!Float.isNaN(actual[i])) {
                    fail(what + ", index " + i + ": expected NaN, got " + actual[i]);
                }
            } else if (!Network.agrees(actual[i], expected[i])) {
                fail(what + ", index " + i + ": expected " + expected[i] + ", got " + actual[i]);
            }
        }
        return nans;
    }

    /** Asserts bit-for-bit equality, with a message that names the first difference. */
    static void assertSameBits(String what, float[] expected, float[] actual) {
        assertEquals(expected.length, actual.length, what + ": length");
        for (int i = 0; i < expected.length; i++) {
            if (Float.floatToIntBits(expected[i]) != Float.floatToIntBits(actual[i])) {
                fail(what + ", index " + i + ": expected " + expected[i] + ", got " + actual[i]);
            }
        }
    }

    /**
     * A batch of trunk activations [batch][122][channels] as a ReLU output: values in [0, 2), about a quarter of
     * them exactly 0. The padding row holds {@code padding}, so a kernel that reads it can be caught.
     */
    static float[] trunk(SplittableRandom random, int batch, int channels, float padding) {
        float[] x = new float[batch * HexGrid.ROWS * channels];
        for (int b = 0; b < batch; b++) {
            for (int row = 0; row < HexGrid.ROWS; row++) {
                for (int ch = 0; ch < channels; ch++) {
                    int i = (b * HexGrid.ROWS + row) * channels + ch;
                    if (row == HexGrid.PADDING) {
                        x[i] = padding;
                    } else {
                        double v = random.nextDouble(-0.5, 2);
                        x[i] = v < 0 ? 0f : (float) v;
                    }
                }
            }
        }
        return x;
    }

    /** Random encoder-like input [batch][122][7]: planes in [0, 1), padding row 0. */
    static float[] input(SplittableRandom random, int batch) {
        float[] in = new float[batch * Encoder.INPUT_SIZE];
        for (int b = 0; b < batch; b++) {
            for (int i = 0; i < HexGrid.CELLS * Encoder.PLANES; i++) {
                in[b * Encoder.INPUT_SIZE + i] = (float) random.nextDouble();
            }
        }
        return in;
    }

    /** The policy head of the reference, per position: {@code ReferenceOps.pointwise} on the 121 cell rows. */
    static float[] policyReference(Parameters p, float[] trunk, int batch) {
        int c = p.config().channels();
        float[] out = new float[batch * Encoder.POLICY_SIZE];
        for (int b = 0; b < batch; b++) {
            ReferenceOps.pointwise(trunk, b * HexGrid.ROWS * c, HexGrid.CELLS, c, p.get("head_policy.weight").data(),
                    p.get("head_policy.bias").data(), Encoder.POLICY_CHANNELS, out, b * Encoder.POLICY_SIZE);
        }
        return out;
    }

    /** The value head of the reference, per position: mean over cells, dense, ReLU, dense, tanh. */
    static float[] valueReference(Parameters p, float[] trunk, int batch) {
        NetworkConfig config = p.config();
        int c = config.channels();
        int h = config.valueHidden();
        int players = config.players();
        float[] out = new float[batch * players];
        float[] pooled = new float[c];
        float[] hidden = new float[h];
        for (int b = 0; b < batch; b++) {
            ReferenceOps.meanOverCells(trunk, b * HexGrid.ROWS * c, c, pooled, 0);
            ReferenceOps.dense(pooled, 0, c, p.get("head_value.dense1.weight").data(),
                    p.get("head_value.dense1.bias").data(), h, hidden, 0);
            ReferenceOps.relu(hidden, 0, h);
            ReferenceOps.dense(hidden, 0, h, p.get("head_value.dense2.weight").data(),
                    p.get("head_value.dense2.bias").data(), players, out, b * players);
            ReferenceOps.tanh(out, b * players, players);
        }
        return out;
    }

    /**
     * The trunk of the reference network (docs/network.md, layers 1 to 3) for a batch of inputs: the activation
     * that the heads read. The same steps as {@code Network.forwardOne}; the full-head test compares the heads
     * on top of it with {@link Network#forward}, so a slip here fails that test too.
     */
    static float[] referenceTrunk(Parameters p, float[] input, int batch) {
        int c = p.config().channels();
        int size = HexGrid.ROWS * c;
        float[] trunk = new float[batch * size];
        float[] x = new float[size];
        float[] t = new float[size];
        float[] u = new float[size];
        for (int b = 0; b < batch; b++) {
            ReferenceOps.hexConv(input, b * Encoder.INPUT_SIZE, Encoder.PLANES, p.get("conv_in.weight").data(),
                    p.get("conv_in.bias").data(), c, x, 0);
            ReferenceOps.relu(x, 0, size);
            for (int i = 0; i < p.config().blocks(); i++) {
                String block = "block" + i;
                ReferenceOps.hexConv(x, 0, c, p.get(block + ".conv1.weight").data(),
                        p.get(block + ".conv1.bias").data(), c, t, 0);
                ReferenceOps.relu(t, 0, size);
                ReferenceOps.hexConv(t, 0, c, p.get(block + ".conv2.weight").data(),
                        p.get(block + ".conv2.bias").data(), c, u, 0);
                ReferenceOps.add(u, 0, x, 0, size);
                ReferenceOps.relu(u, 0, size);
                System.arraycopy(u, 0, x, 0, size);
            }
            System.arraycopy(x, 0, trunk, b * size, size);
        }
        return trunk;
    }

    /** Uniform values in [-bound, bound). */
    static float[] uniform(SplittableRandom random, int n, double bound) {
        float[] a = new float[n];
        for (int i = 0; i < n; i++) {
            a[i] = (float) random.nextDouble(-bound, bound);
        }
        return a;
    }
}
