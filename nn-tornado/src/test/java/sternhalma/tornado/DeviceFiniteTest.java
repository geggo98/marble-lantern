package sternhalma.tornado;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.common.TornadoFunctions;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.exceptions.TornadoExecutionPlanException;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * {@link DeviceMath#notFinite} on the device, and a record of how other ways to write the test behave there
 * (docs/tornado.md, "Pitfalls", item 11): on the Metal backend of TornadoVM 7.3.1 the comparisons
 * {@code x - x == 0} and {@code x != x} and {@code Float.isNaN} compile to results that ignore NaN, so a finite
 * check written with them reports nothing. {@code Float.isFinite} is right only as the condition of an {@code if}; the exponent bits are right everywhere.
 */
class DeviceFiniteTest {

    private static final float[] VALUES = {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 1f,
        -3.4028235e38f, 0f, -0f, Float.MIN_VALUE, Float.intBitsToFloat(0x7fc00001), Float.intBitsToFloat(0xffc12345)};

    @BeforeEach
    void requireTornado() {
        TornadoAssumptions.assumeTornado();
    }

    // One kernel per way to write the test, so that the compiler cannot share work between them.

    static void helper(FloatArray x, IntArray out, int n) {
        for (@Parallel int i = 0; i < n; i++) {
            out.set(i, DeviceMath.notFinite(x.get(i)) ? 1 : 0);
        }
    }

    static void direct(FloatArray x, IntArray out, int n) {
        for (@Parallel int i = 0; i < n; i++) {
            out.set(i, !Float.isFinite(x.get(i)) ? 1 : 0);
        }
    }

    static void subtract(FloatArray x, IntArray out, int n) {
        for (@Parallel int i = 0; i < n; i++) {
            float v = x.get(i);
            out.set(i, !(v - v == 0f) ? 1 : 0);
        }
    }

    static void unequal(FloatArray x, IntArray out, int n) {
        for (@Parallel int i = 0; i < n; i++) {
            float v = x.get(i);
            out.set(i, v != v ? 1 : 0);
        }
    }

    static void isNaN(FloatArray x, IntArray out, int n) {
        for (@Parallel int i = 0; i < n; i++) {
            out.set(i, Float.isNaN(x.get(i)) ? 1 : 0);
        }
    }

    static void rawBits(FloatArray x, IntArray out, int n) {
        for (@Parallel int i = 0; i < n; i++) {
            out.set(i, (Float.floatToRawIntBits(x.get(i)) & 0x7f800000) == 0x7f800000 ? 1 : 0);
        }
    }

    private static int[] run(TornadoFunctions.Task3<FloatArray, IntArray, Integer> kernel)
            throws TornadoExecutionPlanException {
        FloatArray x = FloatArray.fromArray(VALUES);
        IntArray out = new IntArray(VALUES.length);
        TaskGraph graph = new TaskGraph("finiteprobe").transferToDevice(DataTransferMode.EVERY_EXECUTION, x)
                .task("probe", kernel, x, out, VALUES.length)
                .transferToHost(DataTransferMode.EVERY_EXECUTION, out);
        try (TornadoExecutionPlan plan = new TornadoExecutionPlan(graph.snapshot())
                .withDevice(TornadoSupport.defaultDevice())) {
            plan.execute();
        }
        return out.toHeapArray();
    }

    /** NaN with any payload, both infinities: flagged; the largest finite float, denormals, zeros: not. */
    @Test
    void notFiniteSeesEveryNonFiniteFloat() throws TornadoExecutionPlanException {
        int[] helper = run(DeviceFiniteTest::helper);
        int[] bits = run(DeviceFiniteTest::rawBits);
        for (int i = 0; i < VALUES.length; i++) {
            int expected = Float.isFinite(VALUES[i]) ? 0 : 1;
            assertEquals(expected, helper[i], "DeviceMath.notFinite(" + VALUES[i] + ")");
            assertEquals(expected, bits[i], "raw bits of " + VALUES[i]);
        }
        int[] subtract = run(DeviceFiniteTest::subtract);
        int[] unequal = run(DeviceFiniteTest::unequal);
        int[] isNaN = run(DeviceFiniteTest::isNaN);
        int[] direct = run(DeviceFiniteTest::direct);
        StringBuilder alternatives = new StringBuilder();
        for (int i = 0; i < VALUES.length; i++) {
            alternatives.append(String.format("%s: x-x!=0 %d, x!=x %d, isNaN %d, !isFinite as value %d; ", VALUES[i],
                    subtract[i], unequal[i], isNaN[i], direct[i]));
        }
        System.out.println("Finite checks on the device (1 = flagged): " + alternatives);
    }
}
