package sternhalma.nn;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests of {@link Checkpoint} (docs/training.md, "Checkpoints"). */
class CheckpointTest {

    @TempDir
    Path dir;

    private static Parameters params(long seed) {
        return Parameters.uniform(RunFixtures.TINY, seed, 0.3f);
    }

    /** An optimiser that has taken a few steps with random gradients, so that every state tensor is non-zero. */
    private static Optimizer trained(Parameters p, Optimizer.Kind kind, int steps) {
        Optimizer o = new Optimizer(p, RunFixtures.settings(kind));
        Random random = new Random(3);
        for (int i = 0; i < steps; i++) {
            ReferenceTrainer.Gradients g = new ReferenceTrainer.Gradients(p.config());
            for (double[] a : g.tensors().values()) {
                for (int j = 0; j < a.length; j++) {
                    a[j] = random.nextGaussian() * 0.1;
                }
            }
            o.step(g, 1e-2);
        }
        return o;
    }

    private static void assertSameState(Optimizer expected, Optimizer actual) {
        assertEquals(expected.settings(), actual.settings());
        assertEquals(expected.steps(), actual.steps());
        if (expected.settings().kind() == Optimizer.Kind.ADAMW) {
            assertSameArrays(expected.m(), actual.m());
            assertSameArrays(expected.v(), actual.v());
        } else {
            assertSameArrays(expected.u(), actual.u());
        }
    }

    private static void assertSameArrays(Map<String, float[]> expected, Map<String, float[]> actual) {
        assertEquals(List.copyOf(expected.keySet()), List.copyOf(actual.keySet()));
        for (String name : expected.keySet()) {
            assertArrayEquals(expected.get(name), actual.get(name), name);
        }
    }

    private static void assertSameParameters(Parameters expected, Parameters actual) {
        assertEquals(expected.config(), actual.config());
        assertEquals(List.copyOf(expected.tensors().keySet()), List.copyOf(actual.tensors().keySet()));
        for (String name : expected.tensors().keySet()) {
            assertArrayEquals(expected.get(name).data(), actual.get(name).data(), name);
        }
    }

    // ------------------------------------------------------------------ round trip

    @Test
    void roundTripKeepsParametersOptimiserStateAndMetadata() throws IOException {
        for (Optimizer.Kind kind : Optimizer.Kind.values()) {
            Parameters p = params(1);
            Optimizer o = trained(p, kind, 3);
            Path file = dir.resolve(kind + ".safetensors");
            long bytes = Checkpoint.write(file, p, o, 4, RunFixtures.SHA);
            assertEquals(Files.size(file), bytes);
            Checkpoint.Loaded loaded = Checkpoint.read(file);
            assertSameParameters(p, loaded.parameters());
            assertSameState(o, loaded.optimizer());
            assertEquals(4, loaded.generation());
            assertEquals(3, loaded.step());
            assertEquals(RunFixtures.SHA, loaded.runConfigSha256());
        }
    }

    /** The metadata keys of docs/training.md, "Checkpoints", and the tensor names. */
    @Test
    void fileHasTheDocumentedMetadataAndTensors() throws IOException {
        Parameters p = params(1);
        Path file = dir.resolve("adamw.safetensors");
        Checkpoint.write(file, p, trained(p, Optimizer.Kind.ADAMW, 2), 5, RunFixtures.SHA);
        Safetensors.Contents c = Safetensors.read(file);
        Map<String, String> md = c.metadata();
        assertEquals("sternhalma-train", md.get("format"));
        assertEquals("1", md.get("format_version"));
        assertEquals("8", md.get("channels"));
        assertEquals("1", md.get("blocks"));
        assertEquals("2", md.get("players"));
        assertEquals("32", md.get("value_hidden"));
        assertEquals("7", md.get("input_planes"));
        assertEquals("adamw", md.get("optimizer"));
        assertEquals("2", md.get("step"));
        assertEquals("5", md.get("generation"));
        assertEquals("0.9", md.get("beta1"));
        assertEquals("0.999", md.get("beta2"));
        assertEquals("1.0E-8", md.get("eps"));
        assertEquals("0.01", md.get("weight_decay"));
        assertEquals(RunFixtures.SHA, md.get("run_config_sha256"));
        List<String> expected = new ArrayList<>();
        for (String name : Parameters.shapes(RunFixtures.TINY).keySet()) {
            expected.add(name);
        }
        for (String prefix : List.of("optim.m.", "optim.v.")) {
            for (String name : Parameters.shapes(RunFixtures.TINY).keySet()) {
                expected.add(prefix + name);
            }
        }
        assertEquals(expected.stream().sorted().toList(), c.tensors().keySet().stream().sorted().toList());

        Path sgd = dir.resolve("sgd.safetensors");
        Checkpoint.write(sgd, p, trained(p, Optimizer.Kind.SGD, 1), 0, RunFixtures.SHA);
        Safetensors.Contents s = Safetensors.read(sgd);
        assertEquals("sgd", s.metadata().get("optimizer"));
        assertTrue(s.tensors().containsKey("optim.u.conv_in.weight"));
        assertFalse(s.tensors().containsKey("optim.m.conv_in.weight"));
        assertEquals(2 * Parameters.shapes(RunFixtures.TINY).size(), s.tensors().size());
    }

    @Test
    void aCheckpointIsNotANetworkFile() throws IOException {
        Parameters p = params(1);
        Path file = dir.resolve("c.safetensors");
        Checkpoint.write(file, p, trained(p, Optimizer.Kind.ADAMW, 1), 0, RunFixtures.SHA);
        assertThrows(IOException.class, () -> Parameters.load(file));
    }

    @Test
    void writeIsAtomicAndLeavesNoTemporaryFile() throws IOException {
        Parameters p = params(1);
        Path file = dir.resolve("c.safetensors");
        Checkpoint.write(file, p, trained(p, Optimizer.Kind.SGD, 1), 0, RunFixtures.SHA);
        Checkpoint.write(file, p, trained(p, Optimizer.Kind.SGD, 2), 0, RunFixtures.SHA);
        try (var files = Files.list(dir)) {
            assertEquals(List.of("c.safetensors"), files.map(f -> f.getFileName().toString()).toList());
        }
        assertEquals(2, Checkpoint.read(file).step());
    }

    @Test
    void writeRejectsABadShaAndGeneration() {
        Parameters p = params(1);
        Optimizer o = new Optimizer(p, Optimizer.Settings.adamw());
        assertThrows(IllegalArgumentException.class, () -> Checkpoint.write(dir.resolve("a"), p, o, 0, "xyz"));
        assertThrows(IllegalArgumentException.class, () -> Checkpoint.write(dir.resolve("a"), p, o, -1, RunFixtures.SHA));
    }

    // ------------------------------------------------------------------ corrupt files

    private Path valid(Optimizer.Kind kind) throws IOException {
        Parameters p = params(1);
        Path file = dir.resolve("valid-" + kind + ".safetensors");
        Checkpoint.write(file, p, trained(p, kind, 2), 1, RunFixtures.SHA);
        return file;
    }

    @Test
    void truncatedFilesAreRejected() throws IOException {
        byte[] bytes = Files.readAllBytes(valid(Optimizer.Kind.ADAMW));
        for (int length : new int[] {0, 1, 7, 8, 9, 100, bytes.length / 2, bytes.length - 4, bytes.length - 1}) {
            Path cut = dir.resolve("cut-" + length);
            Files.write(cut, Arrays.copyOf(bytes, length));
            assertThrows(IOException.class, () -> Checkpoint.read(cut), "length " + length);
        }
    }

    @Test
    void garbageAndMissingFilesAreRejectedWithIoException() throws IOException {
        Path garbage = dir.resolve("garbage");
        byte[] noise = new byte[4096];
        new Random(1).nextBytes(noise);
        Files.write(garbage, noise);
        assertThrows(IOException.class, () -> Checkpoint.read(garbage));
        assertThrows(IOException.class, () -> Checkpoint.read(dir.resolve("absent")));
    }

    /** Tolerant means: never an unchecked exception for a damaged file, only IOException or a valid result. */
    @Test
    void randomByteDamageNeverEscapesAsAnUncheckedException() throws IOException {
        byte[] bytes = Files.readAllBytes(valid(Optimizer.Kind.SGD));
        int headerLength = (int) java.nio.ByteBuffer.wrap(bytes, 0, 8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .getLong();
        Random random = new Random(42);
        int rejected = 0;
        for (int trial = 0; trial < 400; trial++) {
            byte[] damaged = bytes.clone();
            int flips = 1 + random.nextInt(3);
            for (int i = 0; i < flips; i++) {
                int at = trial % 2 == 0 ? random.nextInt(8 + headerLength) : random.nextInt(damaged.length);
                damaged[at] ^= (byte) (1 << random.nextInt(8));
            }
            Path file = dir.resolve("damaged");
            Files.write(file, damaged);
            try {
                Checkpoint.read(file);
            } catch (IOException e) {
                rejected++;
            }
        }
        assertTrue(rejected > 100, "most header damage must be detected, only " + rejected + " of 400");
    }

    private void rewrite(Path source, Path target, Consumer<Map<String, String>> metadata,
            Consumer<Map<String, Tensor>> tensors) throws IOException {
        Safetensors.Contents c = Safetensors.read(source);
        Map<String, String> md = new LinkedHashMap<>(c.metadata());
        Map<String, Tensor> t = new LinkedHashMap<>(c.tensors());
        metadata.accept(md);
        tensors.accept(t);
        Safetensors.write(target, t, md);
    }

    private void assertRejected(Path source, String what, Consumer<Map<String, String>> metadata,
            Consumer<Map<String, Tensor>> tensors) throws IOException {
        Path bad = dir.resolve("bad-" + what.replace(' ', '_'));
        rewrite(source, bad, metadata, tensors);
        assertThrows(IOException.class, () -> Checkpoint.read(bad), what);
    }

    @Test
    void wrongMetadataIsRejected() throws IOException {
        Path source = valid(Optimizer.Kind.ADAMW);
        Consumer<Map<String, Tensor>> same = t -> { };
        assertRejected(source, "network format", m -> m.put("format", "sternhalma-nn"), same);
        assertRejected(source, "future version", m -> m.put("format_version", "3"), same);
        assertRejected(source, "no optimizer", m -> m.remove("optimizer"), same);
        assertRejected(source, "unknown optimizer", m -> m.put("optimizer", "adam"), same);
        assertRejected(source, "negative step", m -> m.put("step", "-1"), same);
        assertRejected(source, "text step", m -> m.put("step", "five"), same);
        assertRejected(source, "huge step", m -> m.put("step", "99999999999999999999"), same);
        assertRejected(source, "no step", m -> m.remove("step"), same);
        assertRejected(source, "bad generation", m -> m.put("generation", "x"), same);
        assertRejected(source, "no beta1", m -> m.remove("beta1"), same);
        assertRejected(source, "beta1 out of range", m -> m.put("beta1", "1.5"), same);
        assertRejected(source, "text beta2", m -> m.put("beta2", "abc"), same);
        assertRejected(source, "nan eps", m -> m.put("eps", "NaN"), same);
        assertRejected(source, "no sha", m -> m.remove("run_config_sha256"), same);
        assertRejected(source, "short sha", m -> m.put("run_config_sha256", "abc"), same);
        assertRejected(source, "huge config", m -> m.put("channels", "100000"), same);
    }

    @Test
    void wrongTensorsAreRejected() throws IOException {
        Path adamw = valid(Optimizer.Kind.ADAMW);
        Consumer<Map<String, String>> same = m -> { };
        assertRejected(adamw, "missing parameter", same, t -> t.remove("conv_in.bias"));
        assertRejected(adamw, "missing m", same, t -> t.remove("optim.m.conv_in.bias"));
        assertRejected(adamw, "missing v", same, t -> t.remove("optim.v.head_policy.weight"));
        assertRejected(adamw, "extra tensor", same, t -> t.put("junk", Tensor.zeros("junk", 3)));
        assertRejected(adamw, "extra u", same, t -> t.put("optim.u.conv_in.bias", Tensor.zeros("optim.u.conv_in.bias", 8)));
        assertRejected(adamw, "wrong m shape", same, t -> t.put("optim.m.conv_in.bias", Tensor.zeros("optim.m.conv_in.bias", 3)));
        assertRejected(adamw, "wrong parameter shape", same, t -> t.put("conv_in.bias", Tensor.zeros("conv_in.bias", 3)));
        assertRejected(adamw, "nan parameter", same, t -> {
            float[] data = t.get("head_policy.bias").data().clone();
            data[1] = Float.NaN;
            t.put("head_policy.bias", new Tensor("head_policy.bias", new int[] {2}, data));
        });
        assertRejected(adamw, "infinite m", same, t -> {
            float[] data = t.get("optim.m.conv_in.bias").data().clone();
            data[0] = Float.POSITIVE_INFINITY;
            t.put("optim.m.conv_in.bias", new Tensor("optim.m.conv_in.bias", new int[] {8}, data));
        });
        assertRejected(adamw, "negative v", same, t -> {
            float[] data = t.get("optim.v.conv_in.bias").data().clone();
            data[0] = -1e-3f;
            t.put("optim.v.conv_in.bias", new Tensor("optim.v.conv_in.bias", new int[] {8}, data));
        });
        Path sgd = valid(Optimizer.Kind.SGD);
        assertRejected(sgd, "sgd with m", same, t -> t.put("optim.m.conv_in.bias", Tensor.zeros("optim.m.conv_in.bias", 8)));
        assertRejected(sgd, "sgd without u", same, t -> t.remove("optim.u.conv_in.weight"));
        // SGD metadata over AdamW tensors: the kind decides which tensors are required.
        assertRejected(adamw, "kind and tensors disagree", m -> m.put("optimizer", "sgd"), t -> { });
    }

    @Test
    void unknownExtraMetadataIsTolerated() throws IOException {
        Path source = valid(Optimizer.Kind.ADAMW);
        Path extra = dir.resolve("extra");
        rewrite(source, extra, m -> m.put("note", "written by a later version"), t -> { });
        assertEquals(2, Checkpoint.read(extra).step());
    }

    // ------------------------------------------------------------------ directory

    private Path writeStep(long step, Optimizer.Kind kind) throws IOException {
        Parameters p = params(step);
        Optimizer o = trained(p, kind, (int) step);
        Path file = Checkpoint.file(dir, step);
        Checkpoint.write(file, p, o, 0, RunFixtures.SHA);
        return file;
    }

    @Test
    void fileNamesCarryTheStepWithEightDigits() {
        assertEquals("step-00000500.safetensors", Checkpoint.file(dir, 500).getFileName().toString());
        assertEquals(500, Checkpoint.stepOf(Checkpoint.file(dir, 500)));
        assertEquals(123456789, Checkpoint.stepOf(Checkpoint.file(dir, 123456789)));
        assertEquals(-1, Checkpoint.stepOf(dir.resolve("candidate.safetensors")));
        assertEquals(-1, Checkpoint.stepOf(dir.resolve("step-5.safetensors")));
        assertEquals(-1, Checkpoint.stepOf(dir.resolve("step-00000005.safetensors.corrupt")));
    }

    @Test
    void recoverTakesTheNewestFileThatReads() throws IOException {
        writeStep(2, Optimizer.Kind.ADAMW);
        writeStep(4, Optimizer.Kind.ADAMW);
        Path newest = writeStep(6, Optimizer.Kind.ADAMW);
        Files.write(dir.resolve("notes.txt"), new byte[] {1});
        Checkpoint.Recovery clean = Checkpoint.recover(dir);
        assertEquals(6, clean.loaded().step());
        assertTrue(clean.quarantined().isEmpty());

        byte[] bytes = Files.readAllBytes(newest);
        Files.write(newest, Arrays.copyOf(bytes, bytes.length - 100));
        Checkpoint.Recovery recovery = Checkpoint.recover(dir);
        assertEquals(4, recovery.loaded().step());
        assertEquals(List.of(newest), recovery.quarantined());
        assertFalse(Files.exists(newest));
        assertTrue(Files.exists(dir.resolve(newest.getFileName() + ".corrupt")));

        Checkpoint.Recovery again = Checkpoint.recover(dir);
        assertEquals(4, again.loaded().step());
        assertTrue(again.quarantined().isEmpty(), "the corrupt file was renamed and is not looked at again");
    }

    @Test
    void recoverQuarantinesAFileWhoseNameDisagreesWithItsStep() throws IOException {
        writeStep(3, Optimizer.Kind.SGD);
        Path liar = writeStep(5, Optimizer.Kind.SGD);
        Files.move(liar, Checkpoint.file(dir, 9));
        Checkpoint.Recovery recovery = Checkpoint.recover(dir);
        assertEquals(3, recovery.loaded().step());
        assertEquals(1, recovery.quarantined().size());
    }

    @Test
    void recoverWithNothingUsableReturnsNull() throws IOException {
        assertNull(Checkpoint.recover(dir).loaded(), "empty directory");
        assertNull(Checkpoint.recover(dir.resolve("absent")).loaded(), "missing directory");
        Files.write(Checkpoint.file(dir, 1), new byte[] {1, 2, 3});
        Checkpoint.Recovery recovery = Checkpoint.recover(dir);
        assertNull(recovery.loaded());
        assertEquals(1, recovery.quarantined().size());
    }

    @Test
    void keepLastDeletesTheOlderCheckpointsOnly() throws IOException {
        for (int step : new int[] {1, 2, 3, 4, 5}) {
            writeStep(step, Optimizer.Kind.SGD);
        }
        Files.write(dir.resolve("candidate.safetensors"), new byte[] {1});
        Files.write(dir.resolve("step-00000001.safetensors.corrupt"), new byte[] {1});
        assertEquals(3, Checkpoint.keepLast(dir, 2));
        assertEquals(List.of(4L, 5L), Checkpoint.list(dir).stream().map(Checkpoint::stepOf).toList());
        assertTrue(Files.exists(dir.resolve("candidate.safetensors")));
        assertTrue(Files.exists(dir.resolve("step-00000001.safetensors.corrupt")));
        assertEquals(0, Checkpoint.keepLast(dir, 2));
        assertThrows(IllegalArgumentException.class, () -> Checkpoint.keepLast(dir, 0));
    }

    @Test
    void removeTemporariesDeletesLeftoversOfInterruptedWrites() throws IOException {
        writeStep(1, Optimizer.Kind.SGD);
        Files.write(dir.resolve("step-00000002.safetensors.1a2b3c.tmp"), new byte[] {1});
        Files.write(dir.resolve("step-00000003.safetensors.ffff.tmp"), new byte[] {1});
        Files.write(dir.resolve("other.txt"), new byte[] {1});
        assertEquals(2, Checkpoint.removeTemporaries(dir));
        assertEquals(0, Checkpoint.removeTemporaries(dir));
        assertTrue(Files.exists(Checkpoint.file(dir, 1)));
        assertTrue(Files.exists(dir.resolve("other.txt")));
        assertNotNull(Checkpoint.recover(dir).loaded());
    }

    /** A classical network keeps its scalars and their optimiser state through a checkpoint (format version 2). */
    @Test
    void aClassicalNetworkRoundTripsWithItsPriorScalars() throws IOException {
        NetworkConfig config = RunFixtures.TINY.withPlanes(PlaneSet.CLASSIC1);
        Parameters p = Parameters.uniform(config, 4, 0.3f);
        Optimizer o = trained(p, Optimizer.Kind.ADAMW, 3);
        Path file = dir.resolve("classic.safetensors");
        Checkpoint.write(file, p, o, 2, RunFixtures.SHA);
        Safetensors.Contents c = Safetensors.read(file);
        assertEquals("2", c.metadata().get("format_version"));
        assertEquals("classic1", c.metadata().get("planes"));
        assertEquals("13", c.metadata().get("input_planes"));
        assertTrue(c.tensors().containsKey("optim.m.prior.beta"));
        assertTrue(c.tensors().containsKey("optim.v.prior.k"));
        Checkpoint.Loaded loaded = Checkpoint.read(file);
        assertSameParameters(p, loaded.parameters());
        assertSameState(o, loaded.optimizer());
        assertEquals(PlaneSet.CLASSIC1, loaded.parameters().config().planes());
        // the same file read as a plain network file would lack the planes: the weights of both kinds stay apart
        Parameters plain = Parameters.uniform(RunFixtures.TINY, 4, 0.3f);
        Path plainFile = dir.resolve("plain.safetensors");
        Checkpoint.write(plainFile, plain, trained(plain, Optimizer.Kind.ADAMW, 1), 0, RunFixtures.SHA);
        assertEquals("1", Safetensors.read(plainFile).metadata().get("format_version"));
        assertNull(Safetensors.read(plainFile).metadata().get("planes"));
    }
}
