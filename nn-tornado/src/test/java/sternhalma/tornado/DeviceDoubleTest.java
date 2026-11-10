package sternhalma.tornado;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.exceptions.TornadoDeviceFP64NotSupported;
import uk.ac.manchester.tornado.api.types.arrays.DoubleArray;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;

/**
 * A kernel with {@code double} does not run on the Metal device (docs/training.md, Part B, "Precision"): TornadoVM
 * says so with an exception at the first execution, so the float softmax of {@link KernelsTrain#policyLoss} is not a
 * choice that a later change can undo by accident.
 */
class DeviceDoubleTest {

    @BeforeEach
    void requireTornado() {
        TornadoAssumptions.assumeTornado();
    }

    static void squares(FloatArray x, DoubleArray out, int n) {
        for (@Parallel int i = 0; i < n; i++) {
            double d = x.get(i);
            out.set(i, d * d + 1e-12);
        }
    }

    @Test
    void aKernelWithDoubleIsRejectedLoudly() {
        FloatArray x = FloatArray.fromArray(new float[] {1f, 2f, 3f});
        DoubleArray out = new DoubleArray(3);
        TaskGraph graph = new TaskGraph("doubleprobe").transferToDevice(DataTransferMode.EVERY_EXECUTION, x)
                .task("squares", DeviceDoubleTest::squares, x, out, 3)
                .transferToHost(DataTransferMode.EVERY_EXECUTION, out);
        TornadoExecutionPlan plan = new TornadoExecutionPlan(graph.snapshot())
                .withDevice(TornadoSupport.defaultDevice());
        TornadoDeviceFP64NotSupported e = assertThrows(TornadoDeviceFP64NotSupported.class, plan::execute);
        assertTrue(e.getMessage().contains("FP64"), e.getMessage());
    }
}
