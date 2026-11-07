package sternhalma.tornado;

import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.common.TornadoDevice;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.exceptions.TornadoExecutionPlanException;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;

/**
 * The smallest TornadoVM kernel: element-wise {@code sum[i] = a[i] + b[i]}.
 *
 * <p>It exists to prove that a kernel compiles and runs on the device of this machine; see
 * docs/tornado.md. The plan copies both inputs to the device and the sum back on every execution.
 */
public final class VectorAdd {

    private VectorAdd() {
    }

    /**
     * The kernel. TornadoVM compiles it for the device; called directly, it runs as plain Java.
     * All three arrays must have the same size.
     */
    public static void add(FloatArray a, FloatArray b, FloatArray sum) {
        for (@Parallel int i = 0; i < sum.getSize(); i++) {
            sum.set(i, a.get(i) + b.get(i));
        }
    }

    /**
     * Adds {@code a} and {@code b} on {@code device} in one execution and returns the sum.
     *
     * @throws IllegalArgumentException if the arrays differ in size
     */
    public static FloatArray run(FloatArray a, FloatArray b, TornadoDevice device) {
        if (a.getSize() != b.getSize()) {
            throw new IllegalArgumentException("sizes differ: " + a.getSize() + " and " + b.getSize());
        }
        FloatArray sum = new FloatArray(a.getSize());
        TaskGraph graph = new TaskGraph("vectorAdd")
                .transferToDevice(DataTransferMode.EVERY_EXECUTION, a, b)
                .task("add", VectorAdd::add, a, b, sum)
                .transferToHost(DataTransferMode.EVERY_EXECUTION, sum);
        try (TornadoExecutionPlan plan = new TornadoExecutionPlan(graph.snapshot())) {
            plan.withDevice(device).execute();
        } catch (TornadoExecutionPlanException e) {
            throw new IllegalStateException("could not release the execution plan", e);
        }
        return sum;
    }
}
