package sternhalma.tornado;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import sternhalma.nn.Encoder;

/** {@link GoldenFiles#compare} wraps the reference positions, as the batch builders of CrossBench do. */
class GoldenFilesCompareTest {

    @Test
    void positionsPastTheEndOfTheReferenceWrapAround() {
        int p = Encoder.POLICY_SIZE;
        float[] refLogits = new float[3 * p];
        for (int i = 0; i < refLogits.length; i++) {
            refLogits[i] = i / p + 1.0f;
        }
        float[] refValues = {0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f};
        float[] logits = new float[2 * p];
        for (int i = 0; i < logits.length; i++) {
            logits[i] = i < p ? 3.0f : 1.0f; // reference rows 2 and 0
        }
        float[] values = {0.5f, 0.6f, 0.1f, 0.2f};
        assertEquals(0.0, GoldenFiles.compare(logits, values, refLogits, refValues, 2, 2, 2, 0.0)[0]);
    }
}
