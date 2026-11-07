package sternhalma.tornado;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Arrays;
import java.util.SplittableRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.exceptions.TornadoInternalError;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;

class BatchedMatMulTest {

    // Three different sizes, none a power of two: swapped rows, columns or batch index fails the comparison.
    // ROWS > BATCH, so that the weights are more bytes than one batch of inputs (see weightsStayOnTheDevice).
    private static final int ROWS = 37;
    private static final int COLS = 129;
    private static final int BATCH = 17;

    @BeforeEach
    void requireTornado() {
        TornadoAssumptions.assumeTornado();
    }

    @Test
    void theDeviceResultMatchesJavaWithinTheProjectTolerance() {
        SplittableRandom random = new SplittableRandom(11);
        float[] weights = random(random, ROWS * COLS);
        float[] in = random(random, BATCH * COLS);
        try (BatchedMatMul mm = new BatchedMatMul(weights, ROWS, COLS, BATCH, TornadoSupport.defaultDevice())) {
            float[] out = mm.apply(in);
            assertWithinTolerance(reference(weights, in, ROWS, COLS, BATCH), out);
        }
    }

    @Test
    void everyCallStreamsItsOwnInputs() {
        SplittableRandom random = new SplittableRandom(12);
        float[] weights = random(random, ROWS * COLS);
        float[] in1 = random(random, BATCH * COLS);
        float[] in2 = random(random, BATCH * COLS);
        try (BatchedMatMul mm = new BatchedMatMul(weights, ROWS, COLS, BATCH, TornadoSupport.defaultDevice())) {
            float[] out1 = mm.apply(in1);
            float[] out2 = mm.apply(in2);
            float[] out1Again = mm.apply(in1);
            assertWithinTolerance(reference(weights, in1, ROWS, COLS, BATCH), out1);
            assertWithinTolerance(reference(weights, in2, ROWS, COLS, BATCH), out2);
            // Same kernel, same summation order: the device is deterministic for one plan.
            assertArrayEquals(out1, out1Again);
        }
    }

    @Test
    void weightsStayOnTheDeviceAndTheKernelRunsThere() {
        SplittableRandom random = new SplittableRandom(13);
        float[] weights = random(random, ROWS * COLS);
        float[] in = random(random, BATCH * COLS);
        long weightBytes = 4L * ROWS * COLS;
        long inputBytes = 4L * BATCH * COLS;
        try (BatchedMatMul mm = new BatchedMatMul(weights, ROWS, COLS, BATCH, TornadoSupport.defaultDevice())) {
            mm.apply(in);
            ExecutionProfile first = mm.lastProfile();
            mm.apply(in);
            ExecutionProfile second = mm.lastProfile();

            assertTrue(first.compileNanos() > 0, "first call compiles: " + first);
            assertEquals(0, second.compileNanos(), "second call reuses the kernel: " + second);
            assertTrue(first.kernelNanos() > 0 && second.kernelNanos() > 0, "kernel ran on the device: " + first
                    + " " + second);
            assertTrue(first.bytesToDevice() >= weightBytes + inputBytes, "first call sends weights: " + first);
            assertTrue(second.bytesToDevice() >= inputBytes && second.bytesToDevice() < weightBytes,
                    "second call sends inputs only: " + second);
        }
    }

    @Test
    void laterChangesToTheCallersWeightsAreNotSeen() {
        SplittableRandom random = new SplittableRandom(14);
        float[] weights = random(random, ROWS * COLS);
        float[] original = weights.clone();
        float[] in = random(random, BATCH * COLS);
        try (BatchedMatMul mm = new BatchedMatMul(weights, ROWS, COLS, BATCH, TornadoSupport.defaultDevice())) {
            mm.apply(in);
            Arrays.fill(weights, 5f);
            assertWithinTolerance(reference(original, in, ROWS, COLS, BATCH), mm.apply(in));
        }
    }

    @Test
    void wrongSizesAreRejected() {
        var device = TornadoSupport.defaultDevice();
        assertThrows(IllegalArgumentException.class, () -> new BatchedMatMul(new float[6], 2, 3, 0, device));
        assertThrows(IllegalArgumentException.class, () -> new BatchedMatMul(new float[5], 2, 3, 1, device));
        try (BatchedMatMul mm = new BatchedMatMul(new float[6], 2, 3, 4, device)) {
            assertThrows(IllegalStateException.class, mm::lastProfile);
            assertThrows(IllegalArgumentException.class, () -> mm.apply(new float[11]));
            assertEquals(8, mm.apply(new float[12]).length);
        }
    }

    /** A kernel TornadoVM cannot compile: it allocates an array whose size depends on the work item. */
    static void unsupportedKernel(FloatArray in, FloatArray out) {
        for (@Parallel int i = 0; i < out.getSize(); i++) {
            float[] scratch = new float[i + 1];
            scratch[0] = in.get(i);
            out.set(i, scratch[0]);
        }
    }

    @Test
    void aKernelTheDeviceCannotRunFailsInsteadOfFallingBackToJava() {
        FloatArray in = new FloatArray(16);
        FloatArray out = new FloatArray(16);
        TaskGraph graph = new TaskGraph("unsupported")
                .transferToDevice(DataTransferMode.EVERY_EXECUTION, in)
                .task("t", BatchedMatMulTest::unsupportedKernel, in, out)
                .transferToHost(DataTransferMode.EVERY_EXECUTION, out);
        try (TornadoExecutionPlan plan = new TornadoExecutionPlan(graph.snapshot())) {
            // An Error, not an exception: callers that catch RuntimeException do not see it.
            assertThrows(TornadoInternalError.class, plan::execute);
        } catch (Exception e) {
            fail(e);
        }
    }

    /** {@code out[b][r] = sum_c w[r][c] * in[b][c]}, summed in double: the oracle, not a copy of the kernel. */
    private static double[] reference(float[] w, float[] in, int rows, int cols, int batch) {
        double[] out = new double[batch * rows];
        for (int b = 0; b < batch; b++) {
            for (int r = 0; r < rows; r++) {
                double sum = 0;
                for (int c = 0; c < cols; c++) {
                    sum += (double) w[r * cols + c] * in[b * cols + c];
                }
                out[b * rows + r] = sum;
            }
        }
        return out;
    }

    /** docs/network.md, "Numeric conventions": |fast - ref| <= 1e-4 + 1e-3 * |ref|. */
    private static void assertWithinTolerance(double[] expected, float[] actual) {
        assertEquals(expected.length, actual.length);
        double worst = 0;
        for (int i = 0; i < expected.length; i++) {
            double error = Math.abs(actual[i] - expected[i]);
            worst = Math.max(worst, error);
            if (error > 1e-4 + 1e-3 * Math.abs(expected[i])) {
                fail("index " + i + ": expected " + expected[i] + ", got " + actual[i]);
            }
        }
        System.out.println("BatchedMatMul largest absolute error against the double reference: " + worst);
    }

    private static float[] random(SplittableRandom random, int n) {
        float[] values = new float[n];
        for (int i = 0; i < n; i++) {
            values[i] = (float) random.nextDouble(-1, 1);
        }
        return values;
    }
}
