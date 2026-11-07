package sternhalma.nn;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VectorSupportTest {

    @Test
    void theVectorApiIsAvailable() {
        assertTrue(VectorSupport.lanes() >= 2, "lanes: " + VectorSupport.lanes());
    }
}
