package sternhalma.nn;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import sternhalma.engine.Geometry;

/**
 * Tests of the backward rules (docs/training.md, "Tests of Part A", "Per operation"). Each test names the
 * plausible bug it must catch. The numeric tests publish the largest error as a fraction of the bound.
 *
 * <p>The adjoint tests are the strong ones: for a linear operation {@code y = A x} the backward rule must be
 * {@code A^T}, so {@code <A x, r> = <x, A^T r>} for every x and r. It holds up to rounding, with a bound of
 * 1e-6 times the sum of the absolute terms, which is some 10 times the float rounding of the forward output.
 */
class ReferenceBackwardTest {

    private static final int ROWS = HexGrid.ROWS;
    private static final int CELLS = HexGrid.CELLS;
    private static final double ADJOINT_TOLERANCE = 1e-6;

    /** Channel pairs (in, out): conv_in of TINY, blocks of TINY and SMALL, and a rectangular one. */
    private static final int[][] SHAPES = {{7, 8}, {8, 8}, {32, 32}, {5, 11}};

    private static float[] activation(Random random, int channels) {
        float[] a = new float[ROWS * channels];
        for (int i = 0; i < CELLS * channels; i++) {
            a[i] = random.nextFloat() * 2 - 1;
        }
        return a;
    }

    private static float[] weights(Random random, int n) {
        float[] w = new float[n];
        for (int i = 0; i < n; i++) {
            w[i] = random.nextFloat() * 2 - 1;
        }
        return w;
    }

    /** A gradient of an output with 121 cell rows and a zero padding row. */
    private static double[] gradient(Random random, int channels) {
        double[] d = new double[ROWS * channels];
        for (int i = 0; i < CELLS * channels; i++) {
            d[i] = random.nextDouble() * 2 - 1;
        }
        return d;
    }

    private static double dot(float[] a, double[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    private static double dot(double[] a, double[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    private static float[] abs(float[] a) {
        float[] r = new float[a.length];
        for (int i = 0; i < a.length; i++) {
            r[i] = Math.abs(a[i]);
        }
        return r;
    }

    private static double[] abs(double[] a) {
        double[] r = new double[a.length];
        for (int i = 0; i < a.length; i++) {
            r[i] = Math.abs(a[i]);
        }
        return r;
    }

    private static void assertAdjoint(double lhs, double rhs, double terms, String what, double[] worst) {
        double error = Math.abs(lhs - rhs);
        double bound = ADJOINT_TOLERANCE * terms;
        worst[0] = Math.max(worst[0], error / bound);
        assertTrue(error <= bound, what + ": <op x, r> = " + lhs + ", <x, op^T r> = " + rhs + ", bound " + bound);
    }

    private static String percent(double[] worst) {
        return String.format(Locale.ROOT, "largest error / bound %.3g", worst[0]);
    }

    // ------------------------------------------------------------------ hex convolution

    /**
     * {@code <conv(v), r> = <v, dIn> = <W, dW>} and the bias identity, for several channel shapes. Catches a
     * wrong pi (identity pi fails: the neighbour directions are not symmetric), a transposed weight index, a
     * dropped bias gradient and a wrong stride of the input or output channel.
     */
    @Test
    void hexConvolutionAdjoint(TestReporter reporter) {
        double[] worst = {0};
        for (int[] shape : SHAPES) {
            for (long seed = 1; seed <= 3; seed++) {
                int cin = shape[0];
                int cout = shape[1];
                Random random = new Random(seed * 100 + cin);
                float[] in = activation(random, cin);
                float[] w = weights(random, cout * HexGrid.STENCIL * cin);
                float[] out = new float[ROWS * cout];
                ReferenceOps.hexConv(in, 0, cin, w, new float[cout], cout, out, 0);
                double[] r = gradient(random, cout);

                double[] dIn = new double[ROWS * cin];
                double[] dW = new double[w.length];
                double[] db = new double[cout];
                ReferenceBackward.hexConvBackward(in, 0, cin, w, cout, r, 0, dW, db, dIn, 0);

                float[] outAbs = new float[ROWS * cout];
                ReferenceOps.hexConv(abs(in), 0, cin, abs(w), new float[cout], cout, outAbs, 0);
                double terms = dot(outAbs, abs(r));
                double lhs = dot(out, r);
                String what = "hex conv " + cin + " -> " + cout + ", seed " + seed;
                assertAdjoint(lhs, dot(in, dIn), terms, what + ", input", worst);
                assertAdjoint(lhs, dot(w, dW), terms, what + ", weight", worst);

                // Bias: with zero input the output is the bias in every cell.
                float[] zero = new float[ROWS * cin];
                float[] bias = weights(random, cout);
                float[] outBias = new float[ROWS * cout];
                ReferenceOps.hexConv(zero, 0, cin, w, bias, cout, outBias, 0);
                double[] dbOnly = new double[cout];
                ReferenceBackward.hexConvBackward(zero, 0, cin, w, cout, r, 0, new double[w.length], dbOnly, null, 0);
                assertAdjoint(dot(outBias, r), dot(bias, dbOnly), dot(abs(outBias), abs(r)), what + ", bias", worst);
            }
        }
        reporter.publishEntry("hex convolution adjoint", percent(worst));
    }

    /** The same identity for the gather form: {@code <conv(v), r> = <v, gather(r, W')>}. A wrong pi fails here. */
    @Test
    void gatherFormIsTheAdjointToo(TestReporter reporter) {
        double[] worst = {0};
        for (int[] shape : SHAPES) {
            int cin = shape[0];
            int cout = shape[1];
            Random random = new Random(7 * cin + cout);
            float[] in = activation(random, cin);
            float[] w = weights(random, cout * HexGrid.STENCIL * cin);
            float[] out = new float[ROWS * cout];
            ReferenceOps.hexConv(in, 0, cin, w, new float[cout], cout, out, 0);
            double[] r = gradient(random, cout);
            double[] dIn = new double[ROWS * cin];
            Arrays.fill(dIn, Double.NaN);
            ReferenceBackward.hexConvInputGradientGather(r, 0, cout, ReferenceBackward.permutedWeights(w, cin, cout),
                    cin, dIn, 0);
            float[] outAbs = new float[ROWS * cout];
            ReferenceOps.hexConv(abs(in), 0, cin, abs(w), new float[cout], cout, outAbs, 0);
            assertAdjoint(dot(out, r), dot(in, dIn), dot(outAbs, abs(r)), "gather form " + cin + " -> " + cout, worst);
        }
        reporter.publishEntry("gather form adjoint", percent(worst));
    }

    /**
     * The reference din (scatter form) equals the gather form with the permuted weight W', on TINY and SMALL
     * shapes. Catches a wrong pi or a wrong W' in the gather form, which Part B uses.
     */
    @Test
    void scatterEqualsGather(TestReporter reporter) {
        double worst = 0;
        for (int[] shape : new int[][] {{8, 8}, {32, 32}, {7, 8}}) {
            int cin = shape[0];
            int cout = shape[1];
            Random random = new Random(31 * cin + cout);
            float[] in = activation(random, cin);
            float[] w = weights(random, cout * HexGrid.STENCIL * cin);
            double[] dz = gradient(random, cout);
            double[] scatter = new double[ROWS * cin];
            ReferenceBackward.hexConvBackward(in, 0, cin, w, cout, dz, 0, new double[w.length], new double[cout],
                    scatter, 0);
            double[] gather = new double[ROWS * cin];
            ReferenceBackward.hexConvInputGradientGather(dz, 0, cout, ReferenceBackward.permutedWeights(w, cin, cout),
                    cin, gather, 0);
            double max = 0;
            for (double v : scatter) {
                max = Math.max(max, Math.abs(v));
            }
            for (int i = 0; i < scatter.length; i++) {
                double bound = 1e-6 * Math.abs(scatter[i]) + 1e-9 * max;
                double error = Math.abs(scatter[i] - gather[i]);
                worst = Math.max(worst, error / bound);
                assertTrue(error <= bound, "cin " + cin + ", cout " + cout + ", element " + i + ": scatter "
                        + scatter[i] + ", gather " + gather[i]);
            }
        }
        reporter.publishEntry("scatter against gather", String.format(Locale.ROOT, "largest error / bound %.3g", worst));
    }

    /** pi is the involution that maps a direction to the opposite one; checked against Geometry, not by value. */
    @Test
    void piPointsBack() {
        assertEquals(0, ReferenceBackward.PI[0]);
        for (int c = 0; c < CELLS; c++) {
            for (int k = 1; k < HexGrid.STENCIL; k++) {
                int n = Geometry.neighbor(c, k - 1);
                if (n >= 0) {
                    assertEquals(c, Geometry.neighbor(n, ReferenceBackward.PI[k] - 1),
                            "cell " + c + ", k " + k + " does not come back through pi(k)");
                }
            }
        }
        for (int k = 0; k < HexGrid.STENCIL; k++) {
            assertEquals(k, ReferenceBackward.PI[ReferenceBackward.PI[k]], "pi is not an involution at " + k);
        }
    }

    @Test
    void permutedWeightsAreTransposedAndPermuted() {
        int cin = 3;
        int cout = 4;
        float[] w = new float[cout * HexGrid.STENCIL * cin];
        for (int i = 0; i < w.length; i++) {
            w[i] = i + 1;
        }
        float[] p = ReferenceBackward.permutedWeights(w, cin, cout);
        assertEquals(cin * HexGrid.STENCIL * cout, p.length);
        for (int ch = 0; ch < cin; ch++) {
            for (int k = 0; k < HexGrid.STENCIL; k++) {
                for (int o = 0; o < cout; o++) {
                    int pi = k == 0 ? 0 : (k % 2 == 1 ? k + 1 : k - 1);
                    assertEquals(w[o * HexGrid.STENCIL * cin + pi * cin + ch], p[ch * HexGrid.STENCIL * cout + k * cout + o]);
                }
            }
        }
    }

    /**
     * One channel, weight 2.5 at stencil entry k only, a unit gradient at one cell: the input gradient is 2.5 in
     * exactly the row that entry k reads and nowhere else; dW holds the input row that each entry reads. Run for
     * every cell and every k, so that an off-board neighbour (the padding row) is covered too.
     */
    @Test
    void handComputedConvolutionGradientPerDirection() {
        float[] in = new float[ROWS];
        for (int c = 0; c < CELLS; c++) {
            in[c] = c + 1;
        }
        int padded = 0;
        for (int k = 0; k < HexGrid.STENCIL; k++) {
            float[] w = new float[HexGrid.STENCIL];
            w[k] = 2.5f;
            for (int cell = 0; cell < CELLS; cell++) {
                double[] dz = new double[ROWS];
                dz[cell] = 1;
                double[] dIn = new double[ROWS];
                double[] dW = new double[HexGrid.STENCIL];
                double[] db = new double[1];
                ReferenceBackward.hexConvBackward(in, 0, 1, w, 1, dz, 0, dW, db, dIn, 0);
                int source = k == 0 ? cell : Geometry.neighbor(cell, k - 1);
                double[] expectedIn = new double[ROWS];
                double[] expectedW = new double[HexGrid.STENCIL];
                if (source >= 0) {
                    expectedIn[source] = 2.5;
                } else {
                    padded++;
                }
                // dW[k2] is the input that entry k2 reads for this cell, whatever the weights are.
                for (int k2 = 0; k2 < HexGrid.STENCIL; k2++) {
                    int row = k2 == 0 ? cell : Geometry.neighbor(cell, k2 - 1);
                    expectedW[k2] = row < 0 ? 0 : row + 1;
                }
                assertArrayEquals(expectedIn, dIn, "dIn, cell " + cell + ", k " + k);
                assertArrayEquals(expectedW, dW, "dW, cell " + cell + ", k " + k);
                assertArrayEquals(new double[] {1}, db, "db, cell " + cell + ", k " + k);
            }
        }
        assertEquals(102, padded, "off-board entries over all cells and directions");
    }

    // ------------------------------------------------------------------ 1x1 convolution, dense, mean, add

    /** The policy head (8 -> 2 on 121 rows) and a wider one, as adjoints in input and weight. */
    @Test
    void pointwiseAdjoint(TestReporter reporter) {
        double[] worst = {0};
        for (int[] shape : new int[][] {{8, 2}, {32, 2}, {6, 5}}) {
            int cin = shape[0];
            int cout = shape[1];
            Random random = new Random(11 * cin + cout);
            float[] in = activation(random, cin);
            float[] w = weights(random, cout * cin);
            float[] out = new float[CELLS * cout];
            ReferenceOps.pointwise(in, 0, CELLS, cin, w, new float[cout], cout, out, 0);
            double[] r = new double[CELLS * cout];
            for (int i = 0; i < r.length; i++) {
                r[i] = random.nextDouble() * 2 - 1;
            }
            double[] dIn = new double[ROWS * cin];
            double[] dW = new double[w.length];
            double[] db = new double[cout];
            ReferenceBackward.pointwiseBackward(in, 0, CELLS, cin, w, cout, r, 0, dW, db, dIn, 0);
            float[] outAbs = new float[CELLS * cout];
            ReferenceOps.pointwise(abs(in), 0, CELLS, cin, abs(w), new float[cout], cout, outAbs, 0);
            double terms = dot(outAbs, abs(r));
            String what = "1x1 convolution " + cin + " -> " + cout;
            assertAdjoint(dot(out, r), dot(Arrays.copyOf(in, CELLS * cin), Arrays.copyOf(dIn, CELLS * cin)), terms,
                    what + ", input", worst);
            assertAdjoint(dot(out, r), dot(w, dW), terms, what + ", weight", worst);
            double[] expectedBias = new double[cout];
            for (int row = 0; row < CELLS; row++) {
                for (int o = 0; o < cout; o++) {
                    expectedBias[o] += r[row * cout + o];
                }
            }
            assertArrayEquals(expectedBias, db, 1e-12, what + ", bias");
            // The padding row is not part of the operation and must stay untouched.
            for (int ch = 0; ch < cin; ch++) {
                assertEquals(0.0, dIn[HexGrid.PADDING * cin + ch], what + ", padding row");
            }
        }
        reporter.publishEntry("1x1 convolution adjoint", percent(worst));
    }

    @Test
    void denseAdjoint(TestReporter reporter) {
        double[] worst = {0};
        for (int[] shape : new int[][] {{8, 32}, {32, 2}, {32, 3}, {64, 32}}) {
            int cin = shape[0];
            int cout = shape[1];
            Random random = new Random(13 * cin + cout);
            float[] in = weights(random, cin);
            float[] w = weights(random, cout * cin);
            float[] out = new float[cout];
            ReferenceOps.dense(in, 0, cin, w, new float[cout], cout, out, 0);
            double[] r = new double[cout];
            for (int i = 0; i < cout; i++) {
                r[i] = random.nextDouble() * 2 - 1;
            }
            double[] dIn = new double[cin];
            double[] dW = new double[w.length];
            double[] db = new double[cout];
            ReferenceBackward.denseBackward(in, 0, cin, w, cout, r, 0, dW, db, dIn, 0);
            float[] outAbs = new float[cout];
            ReferenceOps.dense(abs(in), 0, cin, abs(w), new float[cout], cout, outAbs, 0);
            double terms = dot(outAbs, abs(r));
            assertAdjoint(dot(out, r), dot(in, dIn), terms, "dense " + cin + " -> " + cout + ", input", worst);
            assertAdjoint(dot(out, r), dot(w, dW), terms, "dense " + cin + " -> " + cout + ", weight", worst);
            assertArrayEquals(r, db, 0.0, "bias gradient is the output gradient");
        }
        reporter.publishEntry("dense adjoint", percent(worst));
    }

    /** Catches a missing or doubled 1/121 and a gradient that leaks into row 121. */
    @Test
    void meanAdjoint(TestReporter reporter) {
        double[] worst = {0};
        for (int channels : new int[] {1, 8, 32}) {
            Random random = new Random(channels);
            float[] in = activation(random, channels);
            float[] out = new float[channels];
            ReferenceOps.meanOverCells(in, 0, channels, out, 0);
            double[] r = new double[channels];
            for (int i = 0; i < channels; i++) {
                r[i] = random.nextDouble() * 2 - 1;
            }
            double[] dIn = new double[ROWS * channels];
            Arrays.fill(dIn, Double.NaN);
            Arrays.fill(dIn, 0, CELLS * channels, 0.0);
            ReferenceBackward.meanBackward(r, 0, channels, dIn, 0);
            // The mean has no padding row in its input, so the inner product over the 121 cell rows is the test.
            double rhs = 0;
            double terms = 0;
            for (int i = 0; i < CELLS * channels; i++) {
                rhs += in[i] * dIn[i];
                terms += Math.abs(in[i] * dIn[i]);
            }
            assertAdjoint(dot(out, r), rhs, terms, "mean, " + channels + " channels", worst);
            for (int ch = 0; ch < channels; ch++) {
                assertEquals(0.0, dIn[HexGrid.PADDING * channels + ch], "padding row, channel " + ch);
                assertEquals(r[ch] / 121, dIn[ch], 1e-15, "every cell gets dOut / 121");
            }
        }
        reporter.publishEntry("mean adjoint", percent(worst));
    }

    /** The residual add: both inputs get the output gradient. Catches a lost identity path. */
    @Test
    void residualAddAdjoint() {
        Random random = new Random(5);
        int n = 50;
        float[] a = weights(random, n);
        float[] b = weights(random, n);
        float[] sum = a.clone();
        ReferenceOps.add(sum, 0, b, 0, n);
        double[] r = new double[n];
        for (int i = 0; i < n; i++) {
            r[i] = random.nextDouble() * 2 - 1;
        }
        double[] dA = new double[n];
        double[] dB = new double[n];
        ReferenceBackward.addBackward(r, 0, dA, 0, dB, 0, n);
        assertEquals(dot(sum, r), dot(a, dA) + dot(b, dB), 1e-12);
        assertArrayEquals(r, dA, 0.0);
        assertArrayEquals(r, dB, 0.0);
    }

    // ------------------------------------------------------------------ accumulation and the padding row

    /**
     * Every gradient output is added to, not overwritten: a buffer that starts at 3 ends 3 higher than one that
     * starts at 0. Catches {@code =} instead of {@code +=} in any of the operations.
     */
    @Test
    void gradientsAccumulate() {
        int cin = 8;
        int cout = 8;
        Random random = new Random(3);
        float[] in = activation(random, cin);
        float[] w = weights(random, cout * HexGrid.STENCIL * cin);
        double[] dz = gradient(random, cout);

        double[] dIn0 = new double[ROWS * cin];
        double[] dW0 = new double[w.length];
        double[] db0 = new double[cout];
        ReferenceBackward.hexConvBackward(in, 0, cin, w, cout, dz, 0, dW0, db0, dIn0, 0);
        double[] dIn1 = new double[ROWS * cin];
        double[] dW1 = new double[w.length];
        double[] db1 = new double[cout];
        Arrays.fill(dIn1, 3.0);
        Arrays.fill(dW1, 3.0);
        Arrays.fill(db1, 3.0);
        ReferenceBackward.hexConvBackward(in, 0, cin, w, cout, dz, 0, dW1, db1, dIn1, 0);
        for (int i = 0; i < dIn0.length; i++) {
            assertEquals(i >= CELLS * cin ? 0.0 : dIn0[i] + 3.0, dIn1[i], 1e-12, "hex dIn " + i);
        }
        for (int i = 0; i < dW0.length; i++) {
            assertEquals(dW0[i] + 3.0, dW1[i], 1e-12, "hex dW " + i);
        }
        for (int i = 0; i < db0.length; i++) {
            assertEquals(db0[i] + 3.0, db1[i], 1e-12, "hex db " + i);
        }

        double[] r = new double[CELLS * 2];
        for (int i = 0; i < r.length; i++) {
            r[i] = random.nextDouble() - 0.5;
        }
        float[] wp = weights(random, 2 * cin);
        double[] a0 = new double[ROWS * cin];
        double[] aw0 = new double[wp.length];
        double[] ab0 = new double[2];
        ReferenceBackward.pointwiseBackward(in, 0, CELLS, cin, wp, 2, r, 0, aw0, ab0, a0, 0);
        double[] a1 = new double[ROWS * cin];
        double[] aw1 = new double[wp.length];
        double[] ab1 = new double[2];
        Arrays.fill(a1, 3.0);
        Arrays.fill(aw1, 3.0);
        Arrays.fill(ab1, 3.0);
        ReferenceBackward.pointwiseBackward(in, 0, CELLS, cin, wp, 2, r, 0, aw1, ab1, a1, 0);
        for (int i = 0; i < a0.length; i++) {
            assertEquals(a0[i] + 3.0, a1[i], 1e-12, "1x1 dIn " + i);
        }
        for (int i = 0; i < aw0.length; i++) {
            assertEquals(aw0[i] + 3.0, aw1[i], 1e-12, "1x1 dW " + i);
        }
        assertEquals(ab0[1] + 3.0, ab1[1], 1e-12, "1x1 db");

        double[] pooled = gradient(random, 1);
        double[] m0 = new double[ROWS * 1];
        ReferenceBackward.meanBackward(pooled, 0, 1, m0, 0);
        double[] m1 = new double[ROWS * 1];
        Arrays.fill(m1, 3.0);
        ReferenceBackward.meanBackward(pooled, 0, 1, m1, 0);
        for (int i = 0; i < CELLS; i++) {
            assertEquals(m0[i] + 3.0, m1[i], 1e-12, "mean dIn " + i);
        }

        double[] x = {1, 2};
        double[] y = {10, 20};
        ReferenceBackward.addBackward(new double[] {5, 6}, 0, x, 0, y, 0, 2);
        assertArrayEquals(new double[] {6, 8}, x);
        assertArrayEquals(new double[] {15, 26}, y);
    }

    /**
     * Row 121 of the input gradient is 0 after the operation, whatever it held before (NaN here), and a NaN in
     * row 121 of dz is not read. A gradient that leaks into the padding row, or a buffer that is not cleaned,
     * fails.
     */
    @Test
    void paddingRowOfGradientsIsZero() {
        int cin = 8;
        int cout = 8;
        Random random = new Random(9);
        float[] in = activation(random, cin);
        float[] w = weights(random, cout * HexGrid.STENCIL * cin);
        double[] dz = gradient(random, cout);
        Arrays.fill(dz, CELLS * cout, ROWS * cout, Double.NaN);
        double[] dIn = new double[ROWS * cin];
        Arrays.fill(dIn, CELLS * cin, ROWS * cin, Double.NaN);
        double[] dW = new double[w.length];
        ReferenceBackward.hexConvBackward(in, 0, cin, w, cout, dz, 0, dW, new double[cout], dIn, 0);
        for (int ch = 0; ch < cin; ch++) {
            assertEquals(0.0, dIn[HexGrid.PADDING * cin + ch], "conv dIn padding row, channel " + ch);
        }
        for (double v : dW) {
            assertTrue(Double.isFinite(v), "a NaN in row 121 of dz reached dW");
        }

        double[] gatherIn = new double[ROWS * cin];
        Arrays.fill(gatherIn, Double.NaN);
        ReferenceBackward.hexConvInputGradientGather(dz, 0, cout, ReferenceBackward.permutedWeights(w, cin, cout), cin,
                gatherIn, 0);
        for (int ch = 0; ch < cin; ch++) {
            assertEquals(0.0, gatherIn[HexGrid.PADDING * cin + ch], "gather dIn padding row, channel " + ch);
        }
        for (double v : gatherIn) {
            assertTrue(Double.isFinite(v), "a NaN in row 121 of dz reached the gather form");
        }

        double[] meanIn = new double[ROWS * 3];
        Arrays.fill(meanIn, CELLS * 3, ROWS * 3, Double.NaN);
        ReferenceBackward.meanBackward(new double[] {1, 2, 3}, 0, 3, meanIn, 0);
        for (int ch = 0; ch < 3; ch++) {
            assertEquals(0.0, meanIn[HexGrid.PADDING * 3 + ch], "mean dIn padding row, channel " + ch);
        }
    }

    // ------------------------------------------------------------------ non-linear operations

    /**
     * Central differences in double with h = 1e-4 for ReLU at points with |z| &gt; 1e-2. The perturbed point is
     * rounded to float and the denominator is the difference of the two rounded points. Catches a derivative
     * that is 1 for dead units (including {@code >=} at 0 turned into a gradient for z &lt; 0).
     */
    @Test
    void reluMatchesFiniteDifferences() {
        Random random = new Random(21);
        int n = 400;
        float[] z = new float[n];
        for (int i = 0; i < n; i++) {
            float v;
            do {
                v = (random.nextFloat() * 2 - 1) * 2;
            } while (Math.abs(v) <= 1e-2);
            z[i] = v;
        }
        float[] out = z.clone();
        ReferenceOps.relu(out, 0, n);
        double[] d = new double[n];
        Arrays.fill(d, 1.0);
        ReferenceBackward.reluBackward(out, 0, d, 0, n);
        for (int i = 0; i < n; i++) {
            float lo = (float) (z[i] - 1e-4);
            float hi = (float) (z[i] + 1e-4);
            double numeric = (Math.max(hi, 0.0) - Math.max(lo, 0.0)) / ((double) hi - lo);
            assertEquals(numeric, d[i], 1e-6 + 1e-4 * Math.abs(numeric), "z " + z[i]);
        }
        // At 0 the derivative is 0, and dead units (output 0) pass no gradient.
        double[] atZero = {1, 1};
        ReferenceBackward.reluBackward(new float[] {0f, -0f}, 0, atZero, 0, 2);
        assertArrayEquals(new double[] {0, 0}, atZero);
    }

    @Test
    void tanhMatchesFiniteDifferences() {
        Random random = new Random(22);
        int n = 400;
        float[] v = new float[n];
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            y[i] = (random.nextDouble() * 2 - 1) * 3;
            v[i] = (float) Math.tanh(y[i]);
        }
        double[] d = new double[n];
        Arrays.fill(d, 1.0);
        ReferenceBackward.tanhBackward(v, 0, d, 0, n);
        for (int i = 0; i < n; i++) {
            double numeric = (Math.tanh(y[i] + 1e-4) - Math.tanh(y[i] - 1e-4)) / 2e-4;
            assertEquals(numeric, d[i], 1e-6 + 1e-4 * Math.abs(numeric), "y " + y[i]);
        }
        // The chain rule factor: the incoming gradient is multiplied, not replaced.
        double[] scaled = {2.5};
        ReferenceBackward.tanhBackward(new float[] {0.5f}, 0, scaled, 0, 1);
        assertEquals(2.5 * 0.75, scaled[0], 1e-15);
    }
}
