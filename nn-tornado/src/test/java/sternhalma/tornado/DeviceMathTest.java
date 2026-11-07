package sternhalma.tornado;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import sternhalma.nn.Network;
import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.common.TornadoFunctions.Task2;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.exceptions.TornadoExecutionPlanException;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;

/**
 * {@link DeviceMath} compiled for the device, against the Java semantics of {@link sternhalma.nn.ReferenceOps}:
 * {@code (float) Math.tanh(x)} and {@code Math.max(v, 0f)}. Metal's own {@code tanh} returns 0 or NaN for large
 * arguments and its {@code fmax} drops NaN (docs/tornado.md, "Pitfalls"); these tests fail if a helper falls back
 * to either.
 */
class DeviceMathTest {

    @BeforeEach
    void requireTornado() {
        TornadoAssumptions.assumeTornado();
    }

    /** Kernel: {@code out[i] = DeviceMath.tanh(in[i])}. */
    static void tanh(FloatArray in, FloatArray out) {
        for (@Parallel int i = 0; i < in.getSize(); i++) {
            out.set(i, DeviceMath.tanh(in.get(i)));
        }
    }

    /** Kernel: {@code out[i] = DeviceMath.relu(in[i])}. */
    static void relu(FloatArray in, FloatArray out) {
        for (@Parallel int i = 0; i < in.getSize(); i++) {
            out.set(i, DeviceMath.relu(in.get(i)));
        }
    }

    /**
     * Arguments from -1000 to 1000 in steps of 0.01, a fine grid of 1.5e-6 over [43.5, 45] (where Metal's tanh
     * turns to 0 and then NaN), a grid of 1e-5 over +-[8, 10.5] (around
     * {@link DeviceMath#TANH_SATURATION}, where Metal's tanh starts to exceed 1), and the special values. Where
     * the reference is NaN, or exactly +-1 beyond the saturation bound, the device must give the same bits;
     * elsewhere it must agree within the tolerance of docs/network.md. No result may leave [-1, 1]
     * (the contract of {@code ForwardPass}), as Metal's tanh does from 8.521233 on.
     */
    @Test
    void tanhSaturatesExactlyAndKeepsNaN() {
        int coarse = 200_001;
        int fine = 1_000_000;
        int edge = 250_001;
        float[] specials = {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.MAX_VALUE,
            -Float.MAX_VALUE, 0f, -0f, Float.MIN_VALUE, 44f, 44.36142f, -44.36142f, 1e30f};
        float[] x = new float[coarse + fine + 2 * edge + specials.length];
        int n = 0;
        for (int i = 0; i < coarse; i++) {
            x[n++] = -1000f + i * 0.01f;
        }
        for (int i = 0; i < fine; i++) {
            x[n++] = 43.5f + i * 1.5e-6f;
        }
        for (int sign = -1; sign <= 1; sign += 2) {
            for (int i = 0; i < edge; i++) {
                x[n++] = sign * (8f + i * 1e-5f);
            }
        }
        System.arraycopy(specials, 0, x, n, specials.length);

        float[] y = run("tanh", DeviceMathTest::tanh, x);
        long exactRequired = 0;
        double worst = 0;
        for (int i = 0; i < x.length; i++) {
            float ref = (float) Math.tanh(x[i]);
            if (y[i] > 1f || y[i] < -1f) {
                fail("tanh(" + x[i] + ") = " + y[i] + " is outside [-1, 1]");
            }
            boolean exact = Float.isNaN(ref) || (Math.abs(x[i]) > DeviceMath.TANH_SATURATION && Math.abs(ref) == 1f);
            if (exact) {
                exactRequired++;
                if (Float.floatToIntBits(ref) != Float.floatToIntBits(y[i])) {
                    fail("tanh(" + x[i] + "): device " + y[i] + ", reference " + ref);
                }
            } else {
                if (!Network.agrees(y[i], ref)) {
                    fail("tanh(" + x[i] + "): device " + y[i] + ", reference " + ref);
                }
                worst = Math.max(worst, Math.abs((double) y[i] - ref));
            }
        }
        assertTrue(exactRequired > 1_200_000, "the sweep checks the saturated range: " + exactRequired);
        System.out.printf("DeviceMath.tanh: %d arguments, %d exact, largest |error| elsewhere %.3g%n", x.length,
                exactRequired, worst);
    }

    /** ReLU keeps NaN and infinity and clips negatives to 0, as {@code Math.max(v, 0f)}. */
    @Test
    void reluKeepsNaN() {
        float[] x = {Float.NaN, -1f, 0f, -0f, 2.5f, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY,
            -Float.MIN_VALUE, Float.MAX_VALUE};
        float[] y = run("relu", DeviceMathTest::relu, x);
        for (int i = 0; i < x.length; i++) {
            float ref = Math.max(x[i], 0f);
            // -0 stays -0 on the device, where Math.max gives +0, and the device lets the negative denormal
            // -Float.MIN_VALUE through (it compares as -0): both within the tolerance. NaN is checked apart.
            assertTrue(Float.isNaN(ref) ? Float.isNaN(y[i]) : ref == y[i] || Network.agrees(y[i], ref),
                    "relu(" + x[i] + "): device " + y[i] + ", reference " + ref);
        }
    }

    private static float[] run(String name, Task2<FloatArray, FloatArray> kernel, float[] values) {
        FloatArray in = FloatArray.fromArray(values);
        FloatArray out = new FloatArray(values.length);
        TaskGraph graph = new TaskGraph("deviceMath_" + name)
                .transferToDevice(DataTransferMode.EVERY_EXECUTION, in)
                .task("k", kernel, in, out)
                .transferToHost(DataTransferMode.EVERY_EXECUTION, out);
        try (TornadoExecutionPlan plan = new TornadoExecutionPlan(graph.snapshot())) {
            plan.withDevice(TornadoSupport.defaultDevice()).execute();
        } catch (TornadoExecutionPlanException e) {
            throw new IllegalStateException("could not release the execution plan", e);
        }
        return out.toHeapArray();
    }
}
