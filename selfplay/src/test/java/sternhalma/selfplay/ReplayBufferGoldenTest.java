package sternhalma.selfplay;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sternhalma.nn.PlaneSet;
import sternhalma.nn.TrainingBatch;

/**
 * A golden batch: the batches of the strict (single player count) buffers for a fixed seed must not change when the
 * buffer learns mixed variants. The expected hashes were taken from the code before the mixed-variant change
 * (commit 6985ca4) and re-taken with the entropy rounded to 1e-9, because `Math.log` differs in the last bit between CPUs (the first CI run on Linux x86 failed on the raw doubles); the data they pin are unchanged.
 */
class ReplayBufferGoldenTest {

    @TempDir
    Path dir;

    private static String hash(TrainingBatch... batches) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        for (TrainingBatch b : batches) {
            ByteBuffer buf = ByteBuffer.allocate(8 + 4 * (b.input().length + b.moveTarget().length
                    + b.valueTarget().length + b.policyWeight().length + b.valueWeight().length
                    + 2 * b.moveFrom().length + b.moveOffsets().length) + 8 * b.targetEntropy().length);
            buf.putInt(b.size()).putInt(b.players());
            for (float f : b.input()) {
                buf.putFloat(f);
            }
            for (float f : b.moveTarget()) {
                buf.putFloat(f);
            }
            for (float f : b.valueTarget()) {
                buf.putFloat(f);
            }
            for (float f : b.policyWeight()) {
                buf.putFloat(f);
            }
            for (float f : b.valueWeight()) {
                buf.putFloat(f);
            }
            for (int i : b.moveFrom()) {
                buf.putInt(i);
            }
            for (int i : b.moveTo()) {
                buf.putInt(i);
            }
            for (int i : b.moveOffsets()) {
                buf.putInt(i);
            }
            for (double d : b.targetEntropy()) {
                buf.putLong(Math.round(d * 1e9)); // entropy uses Math.log, whose last bit differs between CPUs: round
            }
            md.update(buf.array());
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private String golden(int players, PlaneSet planes, boolean augment) throws Exception {
        ReplayBuffer b = new ReplayBuffer(players, Integer.MAX_VALUE, augment, planes);
        b.addGeneration(0, List.of(SyntheticSamples.write(dir, "p" + players + planes.id() + ".safetensors", players,
                SyntheticSamples.samples(players, 77, 120))));
        return hash(b.batch(42, 0, 0, 16), b.batch(42, 0, 1, 16), b.batch(42, 3, 9, 16));
    }

    @Test
    void twoPlayerPlainBatchesAreStable() throws Exception {
        assertEquals("79b6383163ab9bff55b21640abe4ab7348e7d14d6599c77b4ad67fe0714bf7e9", golden(2, PlaneSet.PLAIN, true));
    }

    @Test
    void twoPlayerClassic1BatchesAreStable() throws Exception {
        assertEquals("6dbcdc7aeeb6fa944a733e82a1510f932713510c9d3381336500fba2305a2572", golden(2, PlaneSet.CLASSIC1, true));
    }

    @Test
    void threePlayerPlainBatchesAreStable() throws Exception {
        assertEquals("ac451b5b15f41264442070a386b53ae2ca3c76d52a881c776cba401ca3c7f353", golden(3, PlaneSet.PLAIN, true));
    }

    @Test
    void threePlayerClassic1vStrictBatchesAreStable() throws Exception {
        assertEquals("7e2d9b08f48ba56025703fd8b7d0d97195438c22e4ce6aee893830c0358e92a3", golden(3, PlaneSet.CLASSIC1V, false));
    }
}
