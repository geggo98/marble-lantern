package sternhalma.tornado;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.common.TornadoFunctions;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.exceptions.TornadoExecutionPlanException;
import uk.ac.manchester.tornado.api.math.TornadoMath;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;

/**
 * Accuracy of {@code TornadoMath.exp} and {@code TornadoMath.log} on the device, the two functions of the float
 * softmax of {@link KernelsTrain#policyLoss}. Measured, not assumed: the loss kernel treats a single legal move as a
 * special case because with the first version a single move left a gradient of 1e-7 instead of exactly 0.
 */
class DeviceExpLogTest {

    @BeforeEach
    void requireTornado() {
        TornadoAssumptions.assumeTornado();
    }

    static void expKernel(FloatArray x, FloatArray out, int n) {
        for (@Parallel int i = 0; i < n; i++) {
            out.set(i, TornadoMath.exp(x.get(i)));
        }
    }

    static void logKernel(FloatArray x, FloatArray out, int n) {
        for (@Parallel int i = 0; i < n; i++) {
            out.set(i, TornadoMath.log(x.get(i)));
        }
    }

    private static float[] run(TornadoFunctions.Task3<FloatArray, FloatArray, Integer> kernel,
            float[] in) throws TornadoExecutionPlanException {
        FloatArray x = FloatArray.fromArray(in);
        FloatArray out = new FloatArray(in.length);
        TaskGraph graph = new TaskGraph("explog").transferToDevice(DataTransferMode.EVERY_EXECUTION, x)
                .task("f", kernel, x, out, in.length).transferToHost(DataTransferMode.EVERY_EXECUTION, out);
        try (TornadoExecutionPlan plan = new TornadoExecutionPlan(graph.snapshot())
                .withDevice(TornadoSupport.defaultDevice())) {
            plan.execute();
        }
        return out.toHeapArray();
    }

    /** exp on [-30, 0] (the softmax arguments, all at most 0), relative to the double result. */
    @Test
    void expIsAccurateWhereTheSoftmaxUsesIt() throws TornadoExecutionPlanException {
        int n = 30_001;
        float[] x = new float[n];
        for (int i = 0; i < n; i++) {
            x[i] = -30f * i / (n - 1);
        }
        float[] got = run(DeviceExpLogTest::expKernel, x);
        double worst = 0;
        for (int i = 0; i < n; i++) {
            double ref = Math.exp(x[i]);
            worst = Math.max(worst, Math.abs(got[i] - ref) / ref);
        }
        float atZero = got[0];
        System.out.printf("Metal exp on [-30, 0]: largest relative error %.3g (%.1f float ulps); exp(0) = %s%n", worst,
                worst / 5.96e-8, atZero);
        assertTrue(worst < 1e-5, "relative error " + worst);
    }

    /** log on [1, 1000] (the sum of the softmax, from 1 to the number of moves), absolute error. */
    @Test
    void logIsAccurateWhereTheSoftmaxUsesIt() throws TornadoExecutionPlanException {
        int n = 30_001;
        float[] x = new float[n];
        for (int i = 0; i < n; i++) {
            x[i] = 1f + 999f * i / (n - 1);
        }
        float[] got = run(DeviceExpLogTest::logKernel, x);
        double worst = 0;
        for (int i = 0; i < n; i++) {
            worst = Math.max(worst, Math.abs(got[i] - Math.log(x[i])));
        }
        System.out.printf("Metal log on [1, 1000]: largest absolute error %.3g; log(1) = %s%n", worst, got[0]);
        assertTrue(worst < 1e-5, "absolute error " + worst);
    }
}
