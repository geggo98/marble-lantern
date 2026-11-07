package sternhalma.tornado;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.SplittableRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import sternhalma.nn.HexGrid;
import sternhalma.nn.ReferenceOps;
import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.exceptions.TornadoExecutionPlanException;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;

/** {@link OpsKernels#residualAddRelu} on the device against {@link ReferenceOps#add} and {@link ReferenceOps#relu}. */
class OpsKernelsTest {

    @BeforeEach
    void requireTornado() {
        TornadoAssumptions.assumeTornado();
    }

    @Test
    void residualAddReluByHand() {
        int c = 3;
        float[] conv = new float[2 * HexGrid.ROWS * c];
        float[] skip = new float[conv.length];
        // Position 0, cell 0: (-2 + 1, 1 + 1, 0.5 - 1) -> ReLU -> (0, 2, 0).
        set(conv, 0, 0, c, -2f, 1f, 0.5f);
        set(skip, 0, 0, c, 1f, 1f, -1f);
        // Position 0, cell 120: only the conv side is set: (3, -4, NaN) -> (3, 0, NaN).
        set(conv, 0, 120, c, 3f, -4f, Float.NaN);
        // Position 1, cell 7: only the skip side: (0.25, -0.25, 6) -> (0.25, 0, 6).
        set(skip, 1, 7, c, 0.25f, -0.25f, 6f);
        // Padding rows hold values that a correct kernel overwrites with 0.
        set(conv, 0, HexGrid.PADDING, c, 5f, 5f, 5f);
        set(skip, 1, HexGrid.PADDING, c, 7f, -7f, Float.NaN);

        float[] out = residual(conv, skip, 2, c, false);

        float[] expected = new float[conv.length];
        set(expected, 0, 0, c, 0f, 2f, 0f);
        set(expected, 0, 120, c, 3f, 0f, Float.NaN);
        set(expected, 1, 7, c, 0.25f, 0f, 6f);
        assertTrue(Float.isNaN(out[index(0, 120, 2, c)]), "NaN is kept, as by Math.max in ReferenceOps.relu");
        // Bit for bit (JUnit compares floats by their bits, NaN equal to NaN): every element was written
        // (the output started as NaN, see residual()), and the padding rows are +0.
        OpsTesting.assertSameBits("hand-computed", expected, out);
    }

    /** Batch sizes 1, 3, 64, 513; channel counts of the presets and odd ones. */
    @Test
    void residualAddReluEqualsTheReferenceBitForBit() {
        int[][] cases = {{1, 32}, {3, 8}, {64, 32}, {513, 64}, {3, 1}, {64, 7}};
        for (int[] c : cases) {
            residualCase(c[0], c[1]);
        }
    }

    private static void residualCase(int batch, int channels) {
        SplittableRandom random = new SplittableRandom(31L * batch + channels);
        int size = HexGrid.ROWS * channels;
        float[] conv = OpsTesting.uniform(random, batch * size, 3);
        float[] skip = OpsTesting.trunk(random, batch, channels, 0f);
        for (int b = 0; b < batch; b++) {
            // The block input has a zero padding row, and conv2 writes 0 there (docs/network.md).
            Arrays.fill(conv, b * size + HexGrid.PADDING * channels, (b + 1) * size, 0f);
        }
        float[] expected = conv.clone();
        ReferenceOps.add(expected, 0, skip, 0, expected.length);
        ReferenceOps.relu(expected, 0, expected.length);

        OpsTesting.assertSameBits("separate output", expected, residual(conv, skip, batch, channels, false));
        OpsTesting.assertSameBits("in place", expected, residual(conv, skip, batch, channels, true));
    }

    @Test
    void residualAddReluZeroesThePaddingRowOfEveryPosition() {
        int batch = 5;
        int c = 4;
        float[] conv = new float[batch * HexGrid.ROWS * c];
        float[] skip = new float[conv.length];
        Arrays.fill(conv, 1f);
        Arrays.fill(skip, 2f);
        float[] out = residual(conv, skip, batch, c, false);
        for (int b = 0; b < batch; b++) {
            for (int row = 0; row < HexGrid.ROWS; row++) {
                for (int ch = 0; ch < c; ch++) {
                    float want = row == HexGrid.PADDING ? 0f : 3f;
                    assertEquals(want, out[index(b, row, ch, c)], "b=" + b + " row=" + row + " ch=" + ch);
                }
            }
        }
    }

    /**
     * Runs the kernel on fresh device arrays. The output starts as NaN, so an element the kernel does not
     * write shows up. With {@code inPlace}, the output array is the conv array.
     */
    private static float[] residual(float[] conv, float[] skip, int batch, int channels, boolean inPlace) {
        FloatArray a = FloatArray.fromArray(conv);
        FloatArray b = FloatArray.fromArray(skip);
        FloatArray out;
        TaskGraph graph = new TaskGraph("residual");
        if (inPlace) {
            out = a;
            graph.transferToDevice(DataTransferMode.EVERY_EXECUTION, a, b)
                    .task("t", OpsKernels::residualAddRelu, a, b, a, batch, channels);
        } else {
            out = new FloatArray(conv.length);
            out.init(Float.NaN);
            graph.transferToDevice(DataTransferMode.EVERY_EXECUTION, a, b, out)
                    .task("t", OpsKernels::residualAddRelu, a, b, out, batch, channels);
        }
        graph.transferToHost(DataTransferMode.EVERY_EXECUTION, out);
        try (TornadoExecutionPlan plan = new TornadoExecutionPlan(graph.snapshot())
                .withDevice(TornadoSupport.defaultDevice())) {
            plan.execute();
        } catch (TornadoExecutionPlanException e) {
            throw new IllegalStateException(e);
        }
        return out.toHeapArray();
    }

    private static int index(int b, int row, int ch, int channels) {
        return (b * HexGrid.ROWS + row) * channels + ch;
    }

    private static void set(float[] a, int b, int row, int channels, float... values) {
        for (int ch = 0; ch < values.length; ch++) {
            a[index(b, row, ch, channels)] = values[ch];
        }
    }
}
