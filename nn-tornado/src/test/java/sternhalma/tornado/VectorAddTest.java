package sternhalma.tornado;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.SplittableRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import uk.ac.manchester.tornado.api.types.arrays.FloatArray;

class VectorAddTest {

    @BeforeEach
    void requireTornado() {
        TornadoAssumptions.assumeTornado();
    }

    @Test
    void theDeviceSumEqualsTheJavaSumBitForBit() {
        // Not a multiple of any work-group size, so a kernel that drops the tail or writes past it fails.
        int n = 1_000_003;
        SplittableRandom random = new SplittableRandom(7);
        FloatArray a = new FloatArray(n);
        FloatArray b = new FloatArray(n);
        for (int i = 0; i < n; i++) {
            a.set(i, (float) random.nextDouble(-1e3, 1e3));
            b.set(i, (float) random.nextDouble(-1e3, 1e3));
        }

        FloatArray sum = VectorAdd.run(a, b, TornadoSupport.defaultDevice());

        assertEquals(n, sum.getSize());
        for (int i = 0; i < n; i++) {
            float expected = a.get(i) + b.get(i);
            // One IEEE float addition is correctly rounded on the device too: no tolerance needed.
            if (Float.floatToRawIntBits(expected) != Float.floatToRawIntBits(sum.get(i))) {
                assertEquals(expected, sum.get(i), "index " + i);
            }
        }
    }

    @Test
    void arraysOfDifferentSizeAreRejected() {
        FloatArray a = new FloatArray(8);
        FloatArray b = new FloatArray(9);
        assertThrows(IllegalArgumentException.class, () -> VectorAdd.run(a, b, TornadoSupport.defaultDevice()));
    }
}
