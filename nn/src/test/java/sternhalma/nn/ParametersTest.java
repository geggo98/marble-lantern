package sternhalma.nn;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class ParametersTest {

    @TempDir
    Path dir;

    /** The tensor name table of docs/network.md, written out for TINY with 3 players and two blocks. */
    @Test
    void shapesFollowTheTensorNameTable() {
        NetworkConfig config = new NetworkConfig(8, 2, 3, 32);
        LinkedHashMap<String, int[]> shapes = Parameters.shapes(config);
        List<String> names = List.of(
                "conv_in.weight", "conv_in.bias",
                "block0.conv1.weight", "block0.conv1.bias", "block0.conv2.weight", "block0.conv2.bias",
                "block1.conv1.weight", "block1.conv1.bias", "block1.conv2.weight", "block1.conv2.bias",
                "head_policy.weight", "head_policy.bias",
                "head_value.dense1.weight", "head_value.dense1.bias",
                "head_value.dense2.weight", "head_value.dense2.bias");
        assertEquals(names, List.copyOf(shapes.keySet()));
        List<int[]> expected = List.of(
                new int[] {8, 49}, new int[] {8},
                new int[] {8, 56}, new int[] {8}, new int[] {8, 56}, new int[] {8},
                new int[] {8, 56}, new int[] {8}, new int[] {8, 56}, new int[] {8},
                new int[] {2, 8}, new int[] {2},
                new int[] {32, 8}, new int[] {32},
                new int[] {3, 32}, new int[] {3});
        List<int[]> actual = List.copyOf(shapes.values());
        for (int i = 0; i < names.size(); i++) {
            assertArrayEquals(expected.get(i), actual.get(i), names.get(i));
        }
    }

    /** Ten or more blocks: decimal block numbers without leading zeros, in numeric order. */
    @Test
    void blockNamesAreDecimal() {
        List<String> names = List.copyOf(Parameters.shapes(new NetworkConfig(1, 11, 2, 1)).keySet());
        assertEquals("block9.conv2.bias", names.get(2 + 4 * 9 + 3));
        assertEquals("block10.conv1.weight", names.get(2 + 4 * 10));
    }

    /** The element counts add up to parameterCount, which NetworkConfigTest pins to the preset table. */
    @Test
    void shapeSizesAddUpToTheParameterCount() {
        List<NetworkConfig> configs = List.of(NetworkConfig.tiny(2), NetworkConfig.tiny(3),
                NetworkConfig.small(2), NetworkConfig.large(3), new NetworkConfig(5, 0, 2, 3));
        for (NetworkConfig config : configs) {
            long total = 0;
            for (int[] shape : Parameters.shapes(config).values()) {
                total += Tensor.elementCount(shape);
            }
            assertEquals(config.parameterCount(), total, config.toString());
        }
        assertEquals(60_388, total(Parameters.init(NetworkConfig.small(2), 1)));
        assertEquals(350_308, total(Parameters.init(NetworkConfig.large(2), 1)));
    }

    @Test
    void shapesReturnsAFreshMap() {
        NetworkConfig config = NetworkConfig.tiny(2);
        Parameters.shapes(config).get("conv_in.weight")[0] = 99;
        Parameters.shapes(config).clear();
        assertEquals(8, Parameters.shapes(config).get("conv_in.weight")[0]);
    }

    @Test
    void initIsDeterministicAndDependsOnTheSeed() {
        NetworkConfig config = NetworkConfig.tiny(3);
        assertEquals(Parameters.init(config, 42).tensors(), Parameters.init(config, 42).tensors());
        assertNotEquals(Parameters.init(config, 42).tensors(), Parameters.init(config, 43).tensors());
    }

    /**
     * Pins the draw order: one Random, tensors in shapes order, row-major, and biases draw nothing. With
     * TINY, conv_in.weight takes draws 1 to 392 (8 * 49), so the first weight of block0.conv1 is draw 393.
     */
    @Test
    void initDrawsInTheDocumentedOrder() {
        NetworkConfig config = NetworkConfig.tiny(2);
        Parameters p = Parameters.init(config, 42);
        Random random = new Random(42);
        float aIn = (float) Math.sqrt(6.0 / 49);
        float[] convIn = p.get("conv_in.weight").data();
        for (int i = 0; i < convIn.length; i++) {
            assertEquals((random.nextFloat() * 2 - 1) * aIn, convIn[i], "conv_in.weight[" + i + "]");
        }
        // conv_in.bias draws nothing, so the next draw is block0.conv1.weight[0].
        float aBlock = (float) Math.sqrt(6.0 / 56);
        assertEquals((random.nextFloat() * 2 - 1) * aBlock, p.get("block0.conv1.weight").data()[0]);
    }

    /**
     * Each weight lies in [-a, a] with a = sqrt(6 / fanIn) and comes close to a. A fanIn taken from the wrong
     * dimension changes a by a factor of at least 2 for every tensor here, so one of the two checks fails.
     */
    @Test
    void initUsesTheFanInOfEachTensor() {
        NetworkConfig config = NetworkConfig.tiny(2);
        Parameters p = Parameters.init(config, 5);
        Map<String, Integer> fanIn = Map.of(
                "conv_in.weight", 49,
                "block0.conv1.weight", 56,
                "block0.conv2.weight", 56,
                "head_policy.weight", 8,
                "head_value.dense1.weight", 8,
                "head_value.dense2.weight", 32);
        for (Map.Entry<String, Tensor> e : p.tensors().entrySet()) {
            float[] data = e.getValue().data();
            if (e.getKey().endsWith(".bias")) {
                for (float v : data) {
                    assertEquals(0f, v, e.getKey());
                }
                continue;
            }
            double a = Math.sqrt(6.0 / fanIn.get(e.getKey()));
            double max = 0;
            for (float v : data) {
                max = Math.max(max, Math.abs(v));
            }
            assertTrue(max <= a, e.getKey() + ": max " + max + " > " + a);
            assertTrue(max > 0.6 * a, e.getKey() + ": max " + max + " far below " + a);
        }
    }

    @Test
    void uniformDrawsBiasesTooAndStaysInTheBound() {
        Parameters p = Parameters.uniform(NetworkConfig.tiny(2), 3, 0.25f);
        Random random = new Random(3);
        assertEquals((random.nextFloat() * 2 - 1) * 0.25f, p.get("conv_in.weight").data()[0]);
        for (Map.Entry<String, Tensor> e : p.tensors().entrySet()) {
            boolean nonzero = false;
            for (float v : e.getValue().data()) {
                assertTrue(Math.abs(v) <= 0.25f, e.getKey());
                nonzero |= v != 0;
            }
            assertTrue(nonzero, e.getKey() + " is all zero");
        }
        // The draws continue across tensors: the bias is not a copy of the weight's first elements.
        assertNotEquals(p.get("conv_in.weight").data()[0], p.get("conv_in.bias").data()[0]);
    }

    @Test
    void uniformRejectsBadBounds() {
        NetworkConfig config = NetworkConfig.tiny(2);
        for (float bound : new float[] {0f, -1f, Float.NaN, Float.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> Parameters.uniform(config, 1, bound),
                    Float.toString(bound));
        }
    }

    @Test
    void ofUsesTheGivenTensorsInShapesOrder() {
        NetworkConfig config = NetworkConfig.tiny(2);
        Map<String, Tensor> source = Parameters.uniform(config, 9, 1f).tensors();
        List<String> reversed = new ArrayList<>(source.keySet()).reversed();
        LinkedHashMap<String, Tensor> shuffled = new LinkedHashMap<>();
        for (String name : reversed) {
            shuffled.put(name, source.get(name));
        }
        Parameters p = Parameters.of(config, shuffled);
        assertEquals(List.copyOf(Parameters.shapes(config).keySet()), List.copyOf(p.tensors().keySet()));
        assertSame(source.get("head_policy.weight").data(), p.get("head_policy.weight").data(), "not copied");
        assertEquals(config, p.config());
    }

    @Test
    void ofRejectsWrongNamesAndShapes() {
        NetworkConfig config = NetworkConfig.tiny(2);
        Map<String, Tensor> good = Parameters.init(config, 1).tensors();

        Map<String, Tensor> missing = new LinkedHashMap<>(good);
        missing.remove("head_value.dense2.bias");
        assertThrows(IllegalArgumentException.class, () -> Parameters.of(config, missing));

        Map<String, Tensor> extra = new LinkedHashMap<>(good);
        extra.put("block1.conv1.weight", Tensor.zeros("block1.conv1.weight", 8, 56));
        assertThrows(IllegalArgumentException.class, () -> Parameters.of(config, extra));

        Map<String, Tensor> transposed = new LinkedHashMap<>(good);
        transposed.put("head_policy.weight", Tensor.zeros("head_policy.weight", 8, 2));
        assertThrows(IllegalArgumentException.class, () -> Parameters.of(config, transposed));

        Map<String, Tensor> renamed = new LinkedHashMap<>(good);
        renamed.put("conv_in.bias", Tensor.zeros("head_policy.bias", 8));
        assertThrows(IllegalArgumentException.class, () -> Parameters.of(config, renamed));

        // The same tensors belong to a different config only if the shapes match.
        assertThrows(IllegalArgumentException.class, () -> Parameters.of(NetworkConfig.tiny(3), good));
    }

    @Test
    void getAndTensorsGuardTheirContract() {
        Parameters p = Parameters.init(NetworkConfig.tiny(2), 1);
        assertThrows(IllegalArgumentException.class, () -> p.get("block1.conv1.weight"));
        assertThrows(UnsupportedOperationException.class, () -> p.tensors().remove("conv_in.bias"));
        assertTrue(p.extraMetadata().isEmpty());
    }

    @Test
    void saveAndLoadRoundTripTensorsConfigAndExtraMetadata() throws IOException {
        NetworkConfig config = new NetworkConfig(3, 2, 3, 5);
        Parameters p = Parameters.uniform(config, 11, 1f);
        p.extraMetadata().put("training_step", "1234");
        p.extraMetadata().put("seed", "11");
        Path file = dir.resolve("net.safetensors");
        p.save(file);

        Parameters q = Parameters.load(file);
        assertEquals(config, q.config());
        assertEquals(p.tensors(), q.tensors());
        assertEquals(List.copyOf(p.tensors().keySet()), List.copyOf(q.tensors().keySet()));
        assertEquals(Map.of("training_step", "1234", "seed", "11"), q.extraMetadata());

        // The file itself has the config keys first, then the extra keys, and the tensors in shapes order.
        Safetensors.Contents raw = Safetensors.read(file);
        List<String> keys = new ArrayList<>(config.toMetadata().keySet());
        keys.add("training_step");
        keys.add("seed");
        assertEquals(keys, List.copyOf(raw.metadata().keySet()));
        assertEquals(List.copyOf(Parameters.shapes(config).keySet()), List.copyOf(raw.tensors().keySet()));
    }

    @Test
    void loadedParametersAreIndependentOfTheSaved() throws IOException {
        Parameters p = Parameters.uniform(NetworkConfig.tiny(2), 2, 1f);
        Path file = dir.resolve("a.safetensors");
        p.save(file);
        Parameters q = Parameters.load(file);
        p.get("conv_in.bias").data()[0] = 123f;
        assertNotEquals(123f, q.get("conv_in.bias").data()[0]);
    }

    @Test
    void saveRejectsConfigKeysInTheExtraMetadata() {
        Parameters p = Parameters.init(NetworkConfig.tiny(2), 1);
        p.extraMetadata().put("channels", "16");
        Path file = dir.resolve("bad.safetensors");
        assertThrows(IllegalStateException.class, () -> p.save(file));
        assertFalse(Files.exists(file));
    }

    @Test
    void loadRejectsFilesThatDoNotMatchTheirConfig() throws IOException {
        NetworkConfig config = NetworkConfig.tiny(2);
        Map<String, Tensor> good = Parameters.init(config, 1).tensors();
        Map<String, String> meta = config.toMetadata();

        Map<String, Tensor> missing = new LinkedHashMap<>(good);
        missing.remove("block0.conv2.weight");
        assertLoadFails(missing, meta);

        Map<String, Tensor> extra = new LinkedHashMap<>(good);
        extra.put("block1.conv1.bias", Tensor.zeros("block1.conv1.bias", 8));
        assertLoadFails(extra, meta);

        Map<String, Tensor> wrongShape = new LinkedHashMap<>(good);
        wrongShape.put("conv_in.weight", Tensor.zeros("conv_in.weight", 49, 8));
        assertLoadFails(wrongShape, meta);

        // Tensors of TINY(2) under the metadata of TINY(3): dense2 has the wrong shape.
        assertLoadFails(good, NetworkConfig.tiny(3).toMetadata());

        Map<String, String> unknownFormat = new LinkedHashMap<>(meta);
        unknownFormat.put("format", "other");
        assertLoadFails(good, unknownFormat);

        Map<String, String> noVersion = new LinkedHashMap<>(meta);
        noVersion.remove("format_version");
        assertLoadFails(good, noVersion);

        assertLoadFails(good, Map.of());
    }

    /**
     * Review finding: metadata alone must not make the loader allocate by the config. A file of a few hundred
     * bytes without tensors that claims 100,000,000 blocks used to run out of memory in shapes(). Each file
     * here has no tensors, so a loader that allocates by the config before it compares the tensors would need
     * gigabytes. The timeout turns a slow failure into a clear one.
     */
    @Test
    @Timeout(10)
    void loadRejectsAbsurdMetadataWithAnIOException() throws IOException {
        Map<String, String> meta = NetworkConfig.tiny(2).toMetadata();
        for (String[] bad : new String[][] {
                {"blocks", "100000000"}, {"blocks", "65"},
                {"channels", "100000000"}, {"channels", "1025"},
                {"value_hidden", "100000000"}, {"value_hidden", "1025"}}) {
            Map<String, String> absurd = new LinkedHashMap<>(meta);
            absurd.put(bad[0], bad[1]);
            Path file = Files.createTempFile(dir, "absurd", ".safetensors");
            Safetensors.write(file, Map.of(), absurd);
            IOException e = assertThrows(IOException.class, () -> Parameters.load(file), bad[0] + "=" + bad[1]);
            assertTrue(e.getMessage().contains(bad[0]), e.getMessage());
        }
        // The largest allowed config without its tensors: rejected as missing tensors, not by allocating them.
        Map<String, String> largest = new LinkedHashMap<>(meta);
        largest.put("channels", Integer.toString(NetworkConfig.MAX_CHANNELS));
        largest.put("blocks", Integer.toString(NetworkConfig.MAX_BLOCKS));
        largest.put("value_hidden", Integer.toString(NetworkConfig.MAX_VALUE_HIDDEN));
        Path file = Files.createTempFile(dir, "largest", ".safetensors");
        Safetensors.write(file, Map.of(), largest);
        IOException e = assertThrows(IOException.class, () -> Parameters.load(file));
        assertTrue(e.getMessage().contains("missing"), e.getMessage());
    }

    /** One malformed file per rule of "Metadata keys": each gives an IOException that names the tensor. */
    @Test
    void loadNamesTheTensorThatDoesNotMatch() throws IOException {
        NetworkConfig config = NetworkConfig.tiny(2);
        Map<String, Tensor> good = Parameters.init(config, 1).tensors();
        Map<String, String> meta = config.toMetadata();

        Map<String, Tensor> missing = new LinkedHashMap<>(good);
        missing.remove("head_value.dense1.bias");
        assertLoadFailsWith(missing, meta, "head_value.dense1.bias");

        Map<String, Tensor> extra = new LinkedHashMap<>(good);
        extra.put("block1.conv2.bias", Tensor.zeros("block1.conv2.bias", 8));
        assertLoadFailsWith(extra, meta, "block1.conv2.bias");

        Map<String, Tensor> wrongShape = new LinkedHashMap<>(good);
        wrongShape.put("block0.conv1.weight", Tensor.zeros("block0.conv1.weight", 8, 49));
        assertLoadFailsWith(wrongShape, meta, "block0.conv1.weight");

        // And the valid file still loads.
        Path file = dir.resolve("good.safetensors");
        Safetensors.write(file, good, meta);
        assertEquals(good, Parameters.load(file).tensors());
    }

    @Test
    void loadRejectsAFileThatIsNotSafetensors() throws IOException {
        Path file = dir.resolve("junk.safetensors");
        Files.write(file, new byte[] {1, 2, 3});
        assertThrows(IOException.class, () -> Parameters.load(file));
    }

    private void assertLoadFails(Map<String, Tensor> tensors, Map<String, String> metadata) throws IOException {
        Path file = Files.createTempFile(dir, "bad", ".safetensors");
        Safetensors.write(file, tensors, metadata);
        assertThrows(IOException.class, () -> Parameters.load(file));
    }

    private void assertLoadFailsWith(Map<String, Tensor> tensors, Map<String, String> metadata, String part)
            throws IOException {
        Path file = Files.createTempFile(dir, "bad", ".safetensors");
        Safetensors.write(file, tensors, metadata);
        IOException e = assertThrows(IOException.class, () -> Parameters.load(file));
        assertTrue(e.getMessage().contains(part), () -> "'" + e.getMessage() + "' should name " + part);
    }

    private static long total(Parameters p) {
        long n = 0;
        for (Tensor t : p.tensors().values()) {
            n += t.size();
        }
        return n;
    }
}
