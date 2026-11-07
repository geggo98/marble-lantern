package sternhalma.nn;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;
import sternhalma.engine.Geometry;

class ReferenceOpsTest {

    private static final int ROWS = HexGrid.ROWS;
    private static final int CELLS = HexGrid.CELLS;

    /**
     * Weights that select only stencil entry k: the output of cell c is the input of its k-th gather row, or 0
     * where that is the padding. Catches a wrong neighbour order, a mirrored direction, and an off-board
     * neighbour read from a real row (for example row 0 for index -1, or the cell itself).
     */
    @Test
    void eachStencilEntryReadsTheDocumentedNeighbour() {
        float[] in = new float[ROWS];
        for (int c = 0; c < CELLS; c++) {
            in[c] = c + 1; // distinct and nonzero, so each source row is identifiable
        }
        for (int k = 0; k < HexGrid.STENCIL; k++) {
            float[] weight = new float[HexGrid.STENCIL];
            weight[k] = 1f;
            float[] out = nanArray(ROWS);
            ReferenceOps.hexConv(in, 0, 1, weight, new float[] {0f}, 1, out, 0);
            int padded = 0;
            for (int c = 0; c < CELLS; c++) {
                int source = k == 0 ? c : Geometry.neighbor(c, k - 1);
                float expected = source < 0 ? 0f : source + 1;
                if (source < 0) {
                    padded++;
                }
                assertEquals(expected, out[c], "cell " + c + ", k " + k);
            }
            assertEquals(0f, out[HexGrid.PADDING], "padding row, k " + k);
            if (k > 0) {
                // 102 padding entries over 6 directions, 17 per direction (each direction is equivalent by symmetry).
                assertEquals(17, padded, "padding entries in direction " + (k - 1));
            }
        }
    }

    /**
     * Two input and three output channels, one nonzero weight at (o=2, k=3, ch=1). Catches swapped k and ch in
     * the weight index, a wrong output channel stride, and reading the wrong input channel.
     */
    @Test
    void weightIndexIsOutputThenStencilThenChannel() {
        int inC = 2;
        int outC = 3;
        float[] in = new float[ROWS * inC];
        for (int c = 0; c < CELLS; c++) {
            in[c * inC] = 1000f + c; // channel 0: must not appear in the output
            in[c * inC + 1] = c + 1; // channel 1
        }
        float[] weight = new float[outC * HexGrid.STENCIL * inC];
        weight[2 * HexGrid.STENCIL * inC + 3 * inC + 1] = 0.5f;
        float[] bias = {0.25f, -0.75f, 2f};
        float[] out = nanArray(ROWS * outC);
        ReferenceOps.hexConv(in, 0, inC, weight, bias, outC, out, 0);
        for (int c = 0; c < CELLS; c++) {
            int n = Geometry.neighbor(c, 2);
            assertEquals(0.25f, out[c * outC], "cell " + c + " channel 0");
            assertEquals(-0.75f, out[c * outC + 1], "cell " + c + " channel 1");
            assertEquals(2f + (n < 0 ? 0f : 0.5f * (n + 1)), out[c * outC + 2], "cell " + c + " channel 2");
        }
        for (int o = 0; o < outC; o++) {
            assertEquals(0f, out[HexGrid.PADDING * outC + o], "padding row channel " + o);
        }
    }

    /** Offsets on both sides: data before the slices must be ignored and not overwritten. */
    @Test
    void hexConvHonoursOffsets() {
        int inOffset = 5;
        int outOffset = 3;
        float[] in = new float[inOffset + ROWS];
        Arrays.fill(in, 0, inOffset, 99f);
        for (int c = 0; c < CELLS; c++) {
            in[inOffset + c] = c;
        }
        float[] out = nanArray(outOffset + ROWS + 2);
        float[] weight = {2f, 0, 0, 0, 0, 0, 0};
        ReferenceOps.hexConv(in, inOffset, 1, weight, new float[] {1f}, 1, out, outOffset);
        for (int i = 0; i < outOffset; i++) {
            assertTrue(Float.isNaN(out[i]), "before the slice");
        }
        for (int c = 0; c < CELLS; c++) {
            assertEquals(2f * c + 1f, out[outOffset + c]);
        }
        assertEquals(0f, out[outOffset + HexGrid.PADDING]);
        assertTrue(Float.isNaN(out[outOffset + ROWS]) && Float.isNaN(out[outOffset + ROWS + 1]), "after");
    }

    /**
     * 1e8 + 1 - 1e8 is 0 when summed in float and 1 in double. The cell's own value is 1e8, its neighbour in
     * direction 0 is 1 and in direction 1 is -1e8; the documented ascending order is k = 0, 1, 2.
     */
    @Test
    void hexConvAccumulatesInDouble() {
        int cell = 60; // the centre, which has all six neighbours
        float[] in = new float[ROWS];
        in[cell] = 1e8f;
        in[Geometry.neighbor(cell, 0)] = 1f;
        in[Geometry.neighbor(cell, 1)] = -1e8f;
        float[] weight = {1f, 1f, 1f, 0, 0, 0, 0};
        float[] out = new float[ROWS];
        ReferenceOps.hexConv(in, 0, 1, weight, new float[] {0f}, 1, out, 0);
        assertEquals(1f, out[cell]);
    }

    @Test
    void hexConvRejectsANonzeroPaddingRow() {
        float[] in = new float[ROWS * 2];
        in[HexGrid.PADDING * 2 + 1] = 1f;
        float[] weight = new float[HexGrid.STENCIL * 2];
        assertThrows(IllegalArgumentException.class,
                () -> ReferenceOps.hexConv(in, 0, 2, weight, new float[1], 1, new float[ROWS], 0));
    }

    @Test
    void hexConvRejectsShortArrays() {
        float[] w = new float[7];
        float[] b = new float[1];
        assertThrows(IllegalArgumentException.class,
                () -> ReferenceOps.hexConv(new float[ROWS - 1], 0, 1, w, b, 1, new float[ROWS], 0));
        assertThrows(IllegalArgumentException.class,
                () -> ReferenceOps.hexConv(new float[ROWS], 1, 1, w, b, 1, new float[ROWS], 0));
        assertThrows(IllegalArgumentException.class,
                () -> ReferenceOps.hexConv(new float[ROWS], 0, 1, new float[6], b, 1, new float[ROWS], 0));
        assertThrows(IllegalArgumentException.class,
                () -> ReferenceOps.hexConv(new float[ROWS], 0, 1, w, b, 1, new float[ROWS - 1], 0));
    }

    /** Hand-computed: 2 rows, 3 inputs, 2 outputs; row 2 is a sentinel that must stay untouched. */
    @Test
    void pointwiseComputesEachRowAndLeavesTheRestAlone() {
        float[] in = {1, 2, 3, -1, 0, 4, 77, 77, 77};
        float[] weight = {1, 0, -1, 0.5f, 0.5f, 2};
        float[] bias = {10, -1};
        float[] out = nanArray(1 + 3 * 2);
        ReferenceOps.pointwise(in, 0, 2, 3, weight, bias, 2, out, 1);
        // row 0: 10 + 1 - 3 = 8; -1 + 0.5 + 1 + 6 = 6.5
        // row 1: 10 - 1 - 4 = 5; -1 - 0.5 + 0 + 8 = 6.5
        assertTrue(Float.isNaN(out[0]));
        assertArrayEquals(new float[] {8f, 6.5f, 5f, 6.5f}, Arrays.copyOfRange(out, 1, 5));
        assertTrue(Float.isNaN(out[5]) && Float.isNaN(out[6]), "row 2 must not be written");
    }

    @Test
    void denseIsOneRowWithOffsets() {
        float[] in = {9, 9, 1, -2};
        float[] out = nanArray(3);
        ReferenceOps.dense(in, 2, 2, new float[] {3, 1, -1, -1}, new float[] {0.5f, 0f}, 2, out, 1);
        assertTrue(Float.isNaN(out[0]));
        assertEquals(1.5f, out[1]); // 0.5 + 3 - 2
        assertEquals(1f, out[2]); // 0 - 1 + 2
    }

    @Test
    void reluTouchesOnlyItsRange() {
        float[] a = {-1, -2, 3, -0f, Float.NaN, -5};
        ReferenceOps.relu(a, 1, 4);
        assertEquals(-1f, a[0]);
        assertEquals(0f, a[1]);
        assertEquals(3f, a[2]);
        assertEquals(Float.floatToIntBits(0f), Float.floatToIntBits(a[3]), "-0 becomes +0");
        assertTrue(Float.isNaN(a[4]), "NaN is not hidden");
        assertEquals(-5f, a[5]);
    }

    @Test
    void addUsesBothOffsets() {
        float[] a = {1, 2, 3, 4};
        float[] b = {10, 20, 30};
        ReferenceOps.add(a, 1, b, 1, 2);
        assertArrayEquals(new float[] {1, 22, 33, 4}, a);
    }

    @Test
    void tanhTouchesOnlyItsRange() {
        float[] a = {5, 0.5f, -100, 5};
        ReferenceOps.tanh(a, 1, 2);
        assertEquals(5f, a[0]);
        assertEquals((float) Math.tanh(0.5), a[1]);
        assertEquals(-1f, a[2]);
        assertEquals(5f, a[3]);
    }

    /**
     * Two channels, cell values c and 2 for channel 0 and 1, and a large value in the padding row that must
     * not be counted. The divisor is 121, not 122.
     */
    @Test
    void meanExcludesThePaddingRow() {
        float[] in = new float[1 + ROWS * 2];
        in[0] = 1e6f;
        for (int c = 0; c < CELLS; c++) {
            in[1 + 2 * c] = c;
            in[1 + 2 * c + 1] = 2f;
        }
        in[1 + 2 * HexGrid.PADDING] = 1e6f;
        in[1 + 2 * HexGrid.PADDING + 1] = 1e6f;
        float[] out = new float[2];
        ReferenceOps.meanOverCells(in, 1, 2, out, 0);
        assertEquals(60f, out[0]); // (0 + ... + 120) / 121
        assertEquals(2f, out[1]);
    }

    /** Fuzz: the convolution is linear in the input; the bias enters once. */
    @Test
    void hexConvIsAffineInTheInput() {
        Random random = new Random(7);
        int inC = 3;
        int outC = 2;
        float[] weight = randomArray(random, outC * HexGrid.STENCIL * inC);
        float[] bias = randomArray(random, outC);
        float[] x = new float[ROWS * inC];
        float[] y = new float[ROWS * inC];
        for (int i = 0; i < CELLS * inC; i++) {
            x[i] = random.nextFloat() * 2 - 1;
            y[i] = random.nextFloat() * 2 - 1;
        }
        float[] sum = new float[ROWS * inC];
        for (int i = 0; i < sum.length; i++) {
            sum[i] = x[i] + y[i];
        }
        float[] fx = new float[ROWS * outC];
        float[] fy = new float[ROWS * outC];
        float[] fs = new float[ROWS * outC];
        ReferenceOps.hexConv(x, 0, inC, weight, bias, outC, fx, 0);
        ReferenceOps.hexConv(y, 0, inC, weight, bias, outC, fy, 0);
        ReferenceOps.hexConv(sum, 0, inC, weight, bias, outC, fs, 0);
        for (int c = 0; c < CELLS; c++) {
            for (int o = 0; o < outC; o++) {
                int i = c * outC + o;
                assertEquals(fx[i] + fy[i] - bias[o], fs[i], 1e-5, "cell " + c + " channel " + o);
            }
        }
    }

    private static float[] nanArray(int n) {
        float[] a = new float[n];
        Arrays.fill(a, Float.NaN);
        return a;
    }

    private static float[] randomArray(Random random, int n) {
        float[] a = new float[n];
        for (int i = 0; i < n; i++) {
            a[i] = random.nextFloat() * 2 - 1;
        }
        return a;
    }
}
