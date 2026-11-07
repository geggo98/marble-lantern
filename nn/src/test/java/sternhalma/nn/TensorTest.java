package sternhalma.nn;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class TensorTest {

    @Test
    void shapeMustMatchTheDataLength() {
        assertEquals(6, new Tensor("a", new int[] {2, 3}, new float[6]).size());
        assertThrows(IllegalArgumentException.class, () -> new Tensor("a", new int[] {2, 3}, new float[5]));
        assertThrows(IllegalArgumentException.class, () -> new Tensor("a", new int[] {2, -3}, new float[6]));
        assertThrows(IllegalArgumentException.class, () -> new Tensor("", new int[] {1}, new float[1]));
    }

    @Test
    void scalarsAndEmptyTensorsAreAllowed() {
        assertEquals(1, new Tensor("s", new int[0], new float[1]).size());
        assertEquals(0, new Tensor("e", new int[] {4, 0}, new float[0]).size());
        assertThrows(IllegalArgumentException.class, () -> new Tensor("s", new int[0], new float[0]));
    }

    /** A product that overflows int must not wrap around to a value that matches the data length. */
    @Test
    void hugeShapesAreRejected() {
        // 65536 * 65536 = 2^32, which is 0 as an int.
        assertThrows(IllegalArgumentException.class, () -> new Tensor("h", new int[] {65536, 65536}, new float[0]));
        assertThrows(IllegalArgumentException.class, () -> Tensor.zeros("h", 65536, 65536));
    }

    /**
     * Review finding: a zero dimension makes the tensor empty whatever the other dimensions are, also when
     * the product of the dimensions before it already exceeds Integer.MAX_VALUE.
     */
    @Test
    void anyZeroDimensionGivesAnEmptyTensor() {
        int max = Integer.MAX_VALUE;
        for (int[] shape : new int[][] {{max, 2, 0}, {0, max, 2}, {max, 0, 2}, {65536, 65536, 0}, {max, max, max, 0}}) {
            assertEquals(0, Tensor.elementCount(shape), Arrays.toString(shape));
            assertEquals(0, new Tensor("x", shape, new float[0]).size(), Arrays.toString(shape));
            assertEquals(0, Tensor.zeros("x", shape).size(), Arrays.toString(shape));
        }
        // Without a zero, the same dimensions overflow and are rejected.
        assertTrue(Tensor.elementCount(new int[] {max, 2}) > max);
        assertTrue(Tensor.elementCount(new int[] {max, max, max}) > max, "no long overflow back to small");
        assertThrows(IllegalArgumentException.class, () -> new Tensor("x", new int[] {max, 2}, new float[0]));
        assertThrows(IllegalArgumentException.class, () -> Tensor.zeros("x", max, 2));
        // A negative dimension is an error also after a zero or after an overflow.
        assertThrows(IllegalArgumentException.class, () -> Tensor.elementCount(new int[] {0, -1}));
        assertThrows(IllegalArgumentException.class, () -> Tensor.elementCount(new int[] {max, 2, -1}));
    }

    @Test
    void shapeIsCopiedAndDataIsShared() {
        int[] shape = {2};
        float[] data = {1, 2};
        Tensor t = new Tensor("a", shape, data);
        shape[0] = 7;
        t.shape()[0] = 9;
        assertArrayEquals(new int[] {2}, t.shape());
        assertSame(data, t.data());
    }

    @Test
    void equalityComparesContent() {
        Tensor a = new Tensor("a", new int[] {2}, new float[] {1, 2});
        assertEquals(a, new Tensor("a", new int[] {2}, new float[] {1, 2}));
        assertEquals(a.hashCode(), new Tensor("a", new int[] {2}, new float[] {1, 2}).hashCode());
        assertNotEquals(a, new Tensor("b", new int[] {2}, new float[] {1, 2}));
        assertNotEquals(a, new Tensor("a", new int[] {1, 2}, new float[] {1, 2}));
        assertNotEquals(a, new Tensor("a", new int[] {2}, new float[] {1, 3}));
    }

    /** The documented equality: NaN equals NaN, and 0.0f differs from -0.0f (unlike float ==). */
    @Test
    void equalityTreatsNanAndNegativeZeroAsDocumented() {
        Tensor nan = new Tensor("a", new int[] {1}, new float[] {Float.NaN});
        assertEquals(nan, new Tensor("a", new int[] {1}, new float[] {Float.NaN}));
        assertNotEquals(new Tensor("a", new int[] {1}, new float[] {0.0f}),
                new Tensor("a", new int[] {1}, new float[] {-0.0f}));
        assertNotEquals(new Tensor("a", new int[0], new float[] {1}), new Tensor("a", new int[] {1}, new float[] {1}));
    }

    @Test
    void zerosHasTheShape() {
        Tensor t = Tensor.zeros("z", 3, 4);
        assertArrayEquals(new int[] {3, 4}, t.shape());
        assertArrayEquals(new float[12], t.data());
    }
}
