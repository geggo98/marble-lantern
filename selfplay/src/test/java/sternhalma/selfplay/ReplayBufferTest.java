package sternhalma.selfplay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sternhalma.engine.Geometry;
import sternhalma.nn.BatchSampler;
import sternhalma.nn.Encoder;
import sternhalma.nn.HexGrid;
import sternhalma.nn.TrainingBatch;
import sternhalma.selfplay.SampleFile.Tensor;
import sternhalma.selfplay.SyntheticSamples.Sample;

/** The replay buffer: window, loading, validation, the seeded draw and the expansion into batches. */
class ReplayBufferTest {

    @TempDir
    Path dir;

    private Path write(String name, int players, List<Sample> samples) throws IOException {
        return SyntheticSamples.write(dir, name, players, samples);
    }

    private ReplayBuffer buffer(int players, int window, boolean augment, List<Sample> samples) throws IOException {
        ReplayBuffer b = new ReplayBuffer(players, window, augment);
        b.addGeneration(0, List.of(write("all.safetensors", players, samples)));
        return b;
    }

    private static float[] encode(Sample s) {
        float[] out = new float[Encoder.INPUT_SIZE];
        Encoder.encode(s.position(), out, 0);
        return out;
    }

    private static float[] mirrorRows(float[] input) {
        float[] out = new float[input.length];
        for (int c = 0; c < HexGrid.CELLS; c++) {
            System.arraycopy(input, Encoder.index(c, 0), out, Encoder.index(Geometry.mirror(c), 0), Encoder.PLANES);
        }
        return out;
    }

    private static float[] inputOf(TrainingBatch b, int i) {
        return Arrays.copyOfRange(b.input(), i * Encoder.INPUT_SIZE, (i + 1) * Encoder.INPUT_SIZE);
    }

    private static void assertSameBatch(TrainingBatch a, TrainingBatch b) {
        assertEquals(a.size(), b.size());
        assertArrayEquals(a.input(), b.input());
        assertArrayEquals(a.moveOffsets(), b.moveOffsets());
        assertArrayEquals(a.moveFrom(), b.moveFrom());
        assertArrayEquals(a.moveTo(), b.moveTo());
        assertArrayEquals(a.moveTarget(), b.moveTarget());
        assertArrayEquals(a.valueTarget(), b.valueTarget());
        assertArrayEquals(a.policyWeight(), b.policyWeight());
        assertArrayEquals(a.valueWeight(), b.valueWeight());
    }

    // ---- window ----

    @Test
    void windowDropsWholeOldestGenerations() throws IOException {
        List<Sample> all = SyntheticSamples.samples(2, 1, 100);
        ReplayBuffer b = new ReplayBuffer(2, 70, true);
        b.addGeneration(0, List.of(write("g0.safetensors", 2, all.subList(0, 30))));
        b.addGeneration(1, List.of(write("g1.safetensors", 2, all.subList(30, 60))));
        assertEquals(60, b.size());
        assertEquals(List.of(0, 1), b.generations());
        b.addGeneration(2, List.of(write("g2.safetensors", 2, all.subList(60, 90))));
        // 90 > 70: generation 0 goes as a whole, not just the 20 samples that exceed the window
        assertEquals(List.of(1, 2), b.generations());
        assertEquals(60, b.size());
        assertEquals(0, b.size(0));
        assertEquals(30, b.size(1));
        // the oldest sample is now the first sample of generation 1
        assertArrayEquals(inputOf(b.batchOf(new int[] {0}, new boolean[] {false}), 0), encode(all.get(30)));
        b.addGeneration(5, List.of(write("g5.safetensors", 2, all.subList(0, 40))));
        assertEquals(List.of(2, 5), b.generations(), "90 and then 100 samples: only generation 1 and then none more");
        assertEquals(70, b.size());
    }

    @Test
    void windowDropsSeveralGenerationsAtOnceAndKeepsAnOversizedNewestGeneration() throws IOException {
        List<Sample> all = SyntheticSamples.samples(2, 2, 100);
        ReplayBuffer b = new ReplayBuffer(2, 50, false);
        b.addGeneration(0, List.of(write("g0.safetensors", 2, all.subList(0, 20))));
        b.addGeneration(1, List.of(write("g1.safetensors", 2, all.subList(20, 40))));
        assertEquals(40, b.size());
        b.addGeneration(2, List.of(write("g2.safetensors", 2, all.subList(40, 100))));
        assertEquals(List.of(2), b.generations(), "60 > 50 after generation 2 alone: the newest stays");
        assertEquals(60, b.size());
    }

    @Test
    void aWindowOfExactlyTheSizeKeepsEveryGenerationAndAGenerationOfSeveralFilesGoesTogether() throws IOException {
        List<Sample> all = SyntheticSamples.samples(2, 20, 100);
        ReplayBuffer b = new ReplayBuffer(2, 60, false);
        b.addGeneration(0, List.of(write("a0.safetensors", 2, all.subList(0, 10)),
                write("a1.safetensors", 2, all.subList(10, 30))));
        b.addGeneration(1, List.of(write("b0.safetensors", 2, all.subList(30, 60))));
        assertEquals(60, b.size(), "60 samples are not more than the window of 60");
        assertEquals(List.of(0, 1), b.generations());
        b.addGeneration(2, List.of(write("c0.safetensors", 2, all.subList(60, 65))));
        // 65 > 60: both files of generation 0 go, the 20 samples of its second file do not stay behind
        assertEquals(List.of(1, 2), b.generations());
        assertEquals(0, b.size(0));
        assertEquals(35, b.size());
    }

    @Test
    void generationsMustIncrease() throws IOException {
        List<Sample> all = SyntheticSamples.samples(2, 3, 10);
        ReplayBuffer b = new ReplayBuffer(2, 100, false);
        b.addGeneration(3, List.of(write("a.safetensors", 2, all)));
        assertThrows(IllegalArgumentException.class,
                () -> b.addGeneration(3, List.of(write("b.safetensors", 2, all))));
        assertThrows(IllegalArgumentException.class,
                () -> b.addGeneration(1, List.of(write("c.safetensors", 2, all))));
        assertEquals(10, b.size());
    }

    @Test
    void severalFilesOfOneGenerationConcatenateInOrder() throws IOException {
        List<Sample> all = SyntheticSamples.samples(2, 4, 30);
        write("samples-s1-0001.safetensors", 2, all.subList(10, 30));
        write("samples-s1-0000.safetensors", 2, all.subList(0, 10));
        ReplayBuffer b = new ReplayBuffer(2, 1000, false);
        ReplayBuffer.Loaded loaded = b.addGeneration(0, dir);
        assertEquals(2, loaded.files());
        assertEquals(30, loaded.samples());
        int[] all30 = new int[30];
        for (int i = 0; i < 30; i++) {
            all30[i] = i;
        }
        TrainingBatch batch = b.batchOf(all30, new boolean[30]);
        for (int i = 0; i < 30; i++) {
            assertArrayEquals(encode(all.get(i)), inputOf(batch, i), "sample " + i);
        }
    }

    // ---- the draw ----

    @Test
    void sameSeedGenerationAndStepGiveTheSameBatch() throws IOException {
        ReplayBuffer b = buffer(2, 1000, true, SyntheticSamples.samples(2, 5, 200));
        assertSameBatch(b.batch(7, 3, 11, 32), b.batch(7, 3, 11, 32));
        // a second buffer from the same files draws the same batch
        ReplayBuffer other = new ReplayBuffer(2, 1000, true);
        try {
            other.addGeneration(0, List.of(dir.resolve("all.safetensors")));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        assertSameBatch(b.batch(7, 3, 11, 32), other.batch(7, 3, 11, 32));
    }

    @Test
    void differentSeedGenerationOrStepGiveDifferentBatches() throws IOException {
        ReplayBuffer b = buffer(2, 1000, true, SyntheticSamples.samples(2, 6, 200));
        TrainingBatch base = b.batch(7, 3, 11, 32);
        for (TrainingBatch other : List.of(b.batch(8, 3, 11, 32), b.batch(7, 4, 11, 32), b.batch(7, 3, 12, 32))) {
            assertFalse(Arrays.equals(base.input(), other.input()));
        }
        // (generation 1, step 0) and (generation 0, step 1) are unrelated, as in BatchSampler
        assertFalse(Arrays.equals(b.batch(7, 1, 0, 32).input(), b.batch(7, 0, 1, 32).input()));
    }

    @Test
    void drawsWithReplacementUniformlyOverTheWholeWindow() throws IOException {
        List<Sample> a = SyntheticSamples.samples(2, 7, 30);
        List<Sample> c = SyntheticSamples.samples(2, 8, 30);
        ReplayBuffer b = new ReplayBuffer(2, 1000, false);
        b.addGeneration(0, List.of(write("g0.safetensors", 2, a)));
        b.addGeneration(1, List.of(write("g1.safetensors", 2, c)));
        TrainingBatch batch = b.batch(1, 0, 0, 6000);
        int[] indices = BatchSampler.indices(1, 0, 0, 60, 6000);
        int[] counts = new int[60];
        for (int i : indices) {
            counts[i]++;
        }
        for (int s = 0; s < 60; s++) {
            assertTrue(counts[s] > 40, "sample " + s + " drawn " + counts[s] + " times (mean 100)");
        }
        for (int i = 0; i < 6000; i += 97) {
            Sample expected = indices[i] < 30 ? a.get(indices[i]) : c.get(indices[i] - 30);
            assertArrayEquals(encode(expected), inputOf(batch, i));
        }
    }

    // ---- expansion into batches ----

    @Test
    void batchRowsEqualEncoderAndCarryMovesTargetsAndWeights() throws IOException {
        for (int players = 2; players <= 3; players++) {
            List<Sample> samples = SyntheticSamples.samples(players, 9 + players, 160);
            ReplayBuffer b = buffer(players, 1000, false, samples);
            int[] idx = BatchSampler.indices(5, 1, 2, samples.size(), 64);
            TrainingBatch batch = b.batch(5, 1, 2, 64);
            Set<Integer> movers = new HashSet<>();
            for (int i = 0; i < idx.length; i++) {
                Sample s = samples.get(idx[i]);
                movers.add(s.position().sideToMove());
                assertArrayEquals(encode(s), inputOf(batch, i), players + " players, sample " + idx[i]);
                int first = batch.moveOffsets()[i];
                assertEquals(s.from().length, batch.moveOffsets()[i + 1] - first);
                double sum = 0;
                for (float t : s.target()) {
                    sum += t;
                }
                for (int m = 0; m < s.from().length; m++) {
                    assertEquals(s.from()[m], batch.moveFrom()[first + m]);
                    assertEquals(s.to()[m], batch.moveTo()[first + m]);
                    assertEquals((float) (s.target()[m] / sum), batch.moveTarget()[first + m], "normalised");
                }
                assertArrayEquals(s.value(), Arrays.copyOfRange(batch.valueTarget(), i * players, (i + 1) * players));
                assertEquals(s.wp(), batch.policyWeight()[i]);
                assertEquals(s.wv(), batch.valueWeight()[i]);
            }
            assertEquals(players, movers.size(), "every mover is drawn");
            boolean zero = false;
            for (float w : batch.policyWeight()) {
                zero |= w == 0f;
            }
            assertTrue(zero, "policy weight 0 is kept");
        }
    }

    @Test
    void twoPlayerBatchesMirrorAboutHalfOfTheSamplesAndKeepTheRest() throws IOException {
        List<Sample> samples = SyntheticSamples.samples(2, 12, 300);
        ReplayBuffer b = buffer(2, 1000, true, samples);
        int n = 2000;
        int[] idx = BatchSampler.indices(5, 0, 0, samples.size(), n);
        TrainingBatch batch = b.batch(5, 0, 0, n);
        int mirrored = 0;
        for (int i = 0; i < n; i++) {
            Sample s = samples.get(idx[i]);
            float[] plain = encode(s);
            float[] flipped = mirrorRows(plain);
            float[] got = inputOf(batch, i);
            boolean isMirror;
            if (Arrays.equals(plain, flipped)) {
                continue; // symmetric position: no way to tell
            } else if (Arrays.equals(got, flipped)) {
                isMirror = true;
            } else {
                assertArrayEquals(plain, got, "sample " + idx[i] + " is neither the original nor its mirror");
                isMirror = false;
            }
            mirrored += isMirror ? 1 : 0;
            int first = batch.moveOffsets()[i];
            for (int m = 0; m < s.from().length; m++) {
                int f = isMirror ? Geometry.mirror(s.from()[m]) : s.from()[m];
                int t = isMirror ? Geometry.mirror(s.to()[m]) : s.to()[m];
                assertEquals(f, batch.moveFrom()[first + m], "moves follow the planes");
                assertEquals(t, batch.moveTo()[first + m]);
            }
            // targets, values and weights do not change
            assertEquals(s.wp(), batch.policyWeight()[i]);
            assertEquals(s.wv(), batch.valueWeight()[i]);
            assertArrayEquals(s.value(), Arrays.copyOfRange(batch.valueTarget(), 2 * i, 2 * i + 2));
        }
        assertTrue(mirrored > 0.4 * n && mirrored < 0.6 * n, "mirrored " + mirrored + " of " + n);
    }

    @Test
    void augmentationOffMirrorsNothing() throws IOException {
        List<Sample> samples = SyntheticSamples.samples(2, 13, 100);
        ReplayBuffer b = buffer(2, 1000, false, samples);
        int[] idx = BatchSampler.indices(5, 0, 0, samples.size(), 200);
        TrainingBatch batch = b.batch(5, 0, 0, 200);
        for (int i = 0; i < 200; i++) {
            assertArrayEquals(encode(samples.get(idx[i])), inputOf(batch, i));
        }
    }

    @Test
    void threePlayerBatchesAreNeverMirrored() throws IOException {
        List<Sample> samples = SyntheticSamples.samples(3, 14, 100);
        ReplayBuffer b = buffer(3, 1000, true, samples); // augmentation requested: ignored
        int[] idx = BatchSampler.indices(5, 0, 0, samples.size(), 400);
        TrainingBatch batch = b.batch(5, 0, 0, 400);
        for (int i = 0; i < 400; i++) {
            assertArrayEquals(encode(samples.get(idx[i])), inputOf(batch, i));
        }
        assertThrows(IllegalArgumentException.class, () -> b.batchOf(new int[] {0}, new boolean[] {true}));
    }

    @Test
    void anEmptyBufferHasNoBatch() {
        assertThrows(IllegalStateException.class, () -> new ReplayBuffer(2, 10, true).batch(1, 0, 0, 4));
    }

    // ---- corrupt files ----

    @Test
    void corruptFilesAreSkippedAndRenamedAndGoodFilesStillLoad() throws IOException {
        List<Sample> samples = SyntheticSamples.samples(2, 15, 24);
        write("samples-a-0000.safetensors", 2, samples.subList(0, 8));
        byte[] good = Files.readAllBytes(write("samples-a-0001.safetensors", 2, samples.subList(8, 16)));
        Files.write(dir.resolve("samples-a-0001.safetensors"), Arrays.copyOf(good, good.length - 5)); // truncated
        Files.writeString(dir.resolve("samples-a-0002.safetensors"), "not safetensors at all");
        write("samples-a-0003.safetensors", 2, samples.subList(16, 24));
        Map<String, String> wrongFormat = SyntheticSamples.metadata(2);
        wrongFormat.put("format_version", "2");
        SampleFile.write(dir.resolve("samples-a-0004.safetensors"), SyntheticSamples.tensors(2, samples.subList(0, 3)),
                wrongFormat);
        Files.writeString(dir.resolve("notes.txt"), "ignored");

        ReplayBuffer b = new ReplayBuffer(2, 1000, false);
        ReplayBuffer.Loaded loaded = b.addGeneration(0, dir);
        assertEquals(2, loaded.files());
        assertEquals(16, loaded.samples());
        assertEquals(16, b.size());
        assertEquals(3, loaded.skipped().size());
        assertEquals(3, loaded.quarantined().size());
        for (String name : List.of("samples-a-0001", "samples-a-0002", "samples-a-0004")) {
            assertFalse(Files.exists(dir.resolve(name + ".safetensors")), name + " renamed away");
            assertTrue(Files.exists(dir.resolve(name + ".safetensors.corrupt")), name + " kept as .corrupt");
        }
        assertTrue(Files.exists(dir.resolve("samples-a-0000.safetensors")));
        // the good samples are the ones of files 0 and 3, in that order
        TrainingBatch batch = b.batchOf(new int[] {0, 7, 8, 15}, new boolean[4]);
        for (int i = 0; i < 4; i++) {
            int[] origin = {0, 7, 16, 23};
            assertArrayEquals(encode(samples.get(origin[i])), inputOf(batch, i));
        }
        // a second load of the next generation finds nothing left to rename
        ReplayBuffer again = new ReplayBuffer(2, 1000, false);
        assertTrue(again.addGeneration(0, dir).quarantined().isEmpty());
    }

    @Test
    void listVariantSkipsButDoesNotRename() throws IOException {
        Path bad = dir.resolve("bad.safetensors");
        Files.writeString(bad, "junk");
        ReplayBuffer b = new ReplayBuffer(2, 100, false);
        ReplayBuffer.Loaded loaded = b.addGeneration(0, List.of(bad));
        assertEquals(List.of(bad), loaded.skipped());
        assertTrue(loaded.quarantined().isEmpty());
        assertTrue(Files.exists(bad));
        assertEquals(0, b.size());
    }

    @Test
    void everyKindOfDamageIsRejected() throws IOException {
        List<Sample> samples = SyntheticSamples.samples(2, 16, 12);
        int moves = samples.stream().mapToInt(s -> s.from().length).sum();
        Map<String, Consumer<Map<String, Tensor>>> damages = new java.util.LinkedHashMap<>();
        damages.put("cell byte above players", t -> {
            byte[] c = t.get("cells").u8();
            c[3 * HexGrid.CELLS + 40] = 3;
            t.put("cells", Tensor.u8(c, 12, HexGrid.CELLS));
        });
        damages.put("missing tensor", t -> t.remove("move_target"));
        damages.put("negative ply", t -> {
            short[] ply = t.get("ply").i16();
            ply[2] = -1;
            t.put("ply", Tensor.i16(ply, 12));
        });
        damages.put("move limit 0", t -> {
            short[] limit = t.get("move_limit").i16();
            limit[2] = 0;
            t.put("move_limit", Tensor.i16(limit, 12));
        });
        damages.put("offsets do not end at M", t -> {
            int[] o = t.get("move_offsets").i32();
            o[12]--;
            t.put("move_offsets", Tensor.i32(o, 13));
        });
        damages.put("offsets decrease", t -> {
            int[] o = t.get("move_offsets").i32();
            o[5] = o[4] - 1;
            t.put("move_offsets", Tensor.i32(o, 13));
        });
        damages.put("move cell 121", t -> {
            byte[] f = t.get("move_from").u8();
            f[4] = (byte) 121;
            t.put("move_from", Tensor.u8(f, moves));
        });
        damages.put("negative target", t -> {
            float[] f = t.get("move_target").f32();
            f[2] = -0.1f;
            t.put("move_target", Tensor.f32(f, moves));
        });
        damages.put("NaN target", t -> {
            float[] f = t.get("move_target").f32();
            f[2] = Float.NaN;
            t.put("move_target", Tensor.f32(f, moves));
        });
        damages.put("value above 1", t -> {
            float[] f = t.get("value_target").f32();
            f[5] = 1.5f;
            t.put("value_target", Tensor.f32(f, 12, 2));
        });
        damages.put("negative weight", t -> {
            float[] f = t.get("value_weight").f32();
            f[1] = -1;
            t.put("value_weight", Tensor.f32(f, 12));
        });
        damages.put("policy weight with zero targets", t -> {
            float[] f = t.get("move_target").f32();
            int[] o = t.get("move_offsets").i32();
            Arrays.fill(f, o[0], o[1], 0f); // sample 0 has policy weight 1
            t.put("move_target", Tensor.f32(f, moves));
        });
        damages.put("duplicate move", t -> {
            byte[] f = t.get("move_from").u8();
            byte[] g = t.get("move_to").u8();
            int[] o = t.get("move_offsets").i32();
            f[o[0] + 1] = f[o[0]];
            g[o[0] + 1] = g[o[0]];
            t.put("move_from", Tensor.u8(f, moves));
            t.put("move_to", Tensor.u8(g, moves));
        });
        for (Map.Entry<String, Consumer<Map<String, Tensor>>> d : damages.entrySet()) {
            Map<String, Tensor> t = SyntheticSamples.tensors(2, samples);
            d.getValue().accept(t);
            Path f = dir.resolve("damaged.safetensors");
            SampleFile.write(f, t, SyntheticSamples.metadata(2));
            ReplayBuffer b = new ReplayBuffer(2, 100, false);
            ReplayBuffer.Loaded loaded = b.addGeneration(0, List.of(f));
            assertEquals(1, loaded.skipped().size(), d.getKey());
            assertEquals(0, b.size(), d.getKey());
        }
        // control: the undamaged file loads
        Path ok = write("ok.safetensors", 2, samples);
        assertEquals(12, new ReplayBuffer(2, 100, false).addGeneration(0, List.of(ok)).samples());
    }

    @Test
    void aFileOfAnotherPlayerCountIsAConfigurationErrorNotCorruption() throws IOException {
        Path three = write("samples-three.safetensors", 3, SyntheticSamples.samples(3, 17, 5));
        ReplayBuffer b = new ReplayBuffer(2, 100, false);
        assertThrows(IllegalArgumentException.class, () -> b.addGeneration(0, List.of(three)));
        assertThrows(IllegalArgumentException.class, () -> b.addGeneration(0, dir));
        assertTrue(Files.exists(three), "not renamed");
        assertEquals(0, b.size());
    }

    @Test
    void anEmptyFileIsValidAndAddsNothing() throws IOException {
        Path empty = write("empty.safetensors", 2, new ArrayList<>());
        ReplayBuffer b = new ReplayBuffer(2, 100, false);
        ReplayBuffer.Loaded loaded = b.addGeneration(0, List.of(empty));
        assertEquals(1, loaded.files());
        assertEquals(0, loaded.samples());
        assertTrue(loaded.skipped().isEmpty());
        Path some = write("some.safetensors", 2, SyntheticSamples.samples(2, 18, 6));
        b.addGeneration(1, List.of(some));
        assertEquals(6, b.size());
        assertNotEquals(0, b.batch(1, 0, 0, 4).size());
    }

    @Test
    void indexOutsideTheBufferIsRejected() throws IOException {
        ReplayBuffer b = buffer(2, 100, false, SyntheticSamples.samples(2, 19, 10));
        assertThrows(IllegalArgumentException.class, () -> b.batchOf(new int[] {10}, new boolean[1]));
        assertThrows(IllegalArgumentException.class, () -> b.batchOf(new int[] {-1}, new boolean[1]));
        assertThrows(IllegalArgumentException.class, () -> b.batchOf(new int[] {0}, new boolean[2]));
    }
}
