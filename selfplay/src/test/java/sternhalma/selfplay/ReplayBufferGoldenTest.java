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
 * (commit 6985ca4), so they pin the old behaviour and not the new code.
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
                buf.putDouble(d);
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
        assertEquals("50fd0f57b6d80b3c4758a16c2be2efd010146fd955b88f4180c4c1fe3e4a6efb", golden(2, PlaneSet.PLAIN, true));
    }

    @Test
    void twoPlayerClassic1BatchesAreStable() throws Exception {
        assertEquals("e74d063174e57753c13606ffc9bab776008c12a4f750638da3ca4ee7a988d13d", golden(2, PlaneSet.CLASSIC1, true));
    }

    @Test
    void threePlayerPlainBatchesAreStable() throws Exception {
        assertEquals("1eaf24dc977afcdb61392e393d98ae91cbceaf65753bccad60c723382a9669ac", golden(3, PlaneSet.PLAIN, true));
    }

    @Test
    void threePlayerClassic1vStrictBatchesAreStable() throws Exception {
        assertEquals("ed1f42dcdc1368cdb72d9b7eb4e3ec36ce1108e9ea26dc898b03bdc53966970c", golden(3, PlaneSet.CLASSIC1V, false));
    }
}
