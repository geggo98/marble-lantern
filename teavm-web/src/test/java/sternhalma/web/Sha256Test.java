package sternhalma.web;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.security.MessageDigest;
import java.util.Random;
import org.junit.jupiter.api.Test;

class Sha256Test {
    /** The padding changes at 55/56 and 63/64 bytes, and a second block starts at 119/120: test around each edge. */
    @Test
    void agreesWithMessageDigestAroundTheBlockEdges() throws Exception {
        Random random = new Random(42);
        for (int length : new int[] {0, 1, 55, 56, 57, 63, 64, 65, 119, 120, 121, 128, 1000}) {
            byte[] data = new byte[length];
            random.nextBytes(data);
            assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(data), Sha256.digest(data, 0, length), "length " + length);
        }
    }

    @Test
    void hashesOnlyTheRequestedRange() throws Exception {
        byte[] data = new byte[300];
        new Random(7).nextBytes(data);
        byte[] part = java.util.Arrays.copyOfRange(data, 100, 250);
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(part), Sha256.digest(data, 100, 250));
    }
}
