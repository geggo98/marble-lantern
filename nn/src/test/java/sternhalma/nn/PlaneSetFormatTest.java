package sternhalma.nn;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sternhalma.engine.Position;

/**
 * Format version 2 (docs/network.md, "Metadata keys"): the plane set in the config, the metadata and the file, the
 * parameter counts, and that version 1 files stay readable as the plain 7-plane network.
 */
class PlaneSetFormatTest {

    @TempDir
    Path dir;

    private static Map<String, String> metadata(NetworkConfig config) {
        return new LinkedHashMap<>(config.toMetadata());
    }

    private static void assertRejected(Map<String, String> metadata, String why) {
        assertThrows(IllegalArgumentException.class, () -> NetworkConfig.fromMetadata(metadata), why);
    }

    @Test
    void aPlainConfigIsStillWrittenAsVersion1WithoutPlanes() {
        Map<String, String> m = NetworkConfig.small(2).toMetadata();
        assertEquals(List.of("format", "format_version", "channels", "blocks", "players", "value_hidden", "input_planes"),
                List.copyOf(m.keySet()));
        assertEquals("1", m.get("format_version"));
        assertEquals("7", m.get("input_planes"));
    }

    @Test
    void aClassicalConfigIsVersion2WithThePlanesKeyLast() {
        for (PlaneSet planes : List.of(PlaneSet.CLASSIC1, PlaneSet.CLASSIC1_PLANES)) {
            Map<String, String> m = NetworkConfig.small(2).withPlanes(planes).toMetadata();
            assertEquals(List.of("format", "format_version", "channels", "blocks", "players", "value_hidden",
                    "input_planes", "planes"), List.copyOf(m.keySet()));
            assertEquals("2", m.get("format_version"));
            assertEquals("13", m.get("input_planes"));
            assertEquals(planes.id(), m.get("planes"));
        }
    }

    @Test
    void everyConfigRoundTripsThroughItsMetadata() {
        for (int players = 2; players <= 3; players++) {
            for (PlaneSet planes : PlaneSet.values()) {
                NetworkConfig config = NetworkConfig.small(players).withPlanes(planes);
                assertEquals(config, NetworkConfig.fromMetadata(config.toMetadata()));
            }
        }
        assertNotEquals(NetworkConfig.tiny(2), NetworkConfig.tiny(2).withPlanes(PlaneSet.CLASSIC1));
        assertEquals(NetworkConfig.tiny(2), new NetworkConfig(8, 1, 2, 32, PlaneSet.PLAIN));
    }

    @Test
    void aVersion1FileReadsAsThePlainNetworkAndTheOtherCombinationsAreRejected() {
        // what version 1 writers wrote
        Map<String, String> v1 = new LinkedHashMap<>();
        v1.put("format", "sternhalma-nn");
        v1.put("format_version", "1");
        v1.put("channels", "8");
        v1.put("blocks", "1");
        v1.put("players", "2");
        v1.put("value_hidden", "32");
        v1.put("input_planes", "7");
        assertEquals(NetworkConfig.tiny(2), NetworkConfig.fromMetadata(v1));

        Map<String, String> v1WithPlanes = new LinkedHashMap<>(v1);
        v1WithPlanes.put("planes", "classic1");
        assertRejected(v1WithPlanes, "version 1 has no planes key");
        Map<String, String> v1Wide = new LinkedHashMap<>(v1);
        v1Wide.put("input_planes", "13");
        assertRejected(v1Wide, "version 1 has 7 planes");

        Map<String, String> v2 = metadata(NetworkConfig.tiny(2).withPlanes(PlaneSet.CLASSIC1));
        NetworkConfig.fromMetadata(v2);
        Map<String, String> noPlanes = new LinkedHashMap<>(v2);
        noPlanes.remove("planes");
        assertRejected(noPlanes, "version 2 needs the planes key");
        Map<String, String> unknown = new LinkedHashMap<>(v2);
        unknown.put("planes", "classic2");
        assertRejected(unknown, "unknown plane set");
        Map<String, String> narrow = new LinkedHashMap<>(v2);
        narrow.put("input_planes", "7");
        assertRejected(narrow, "classic1 has 13 planes");
        Map<String, String> version3 = new LinkedHashMap<>(v2);
        version3.put("format_version", "3");
        assertRejected(version3, "unknown version");
        // version 2 may also say plain, with 7 planes
        Map<String, String> v2Plain = new LinkedHashMap<>(v2);
        v2Plain.put("planes", "plain");
        v2Plain.put("input_planes", "7");
        assertEquals(NetworkConfig.tiny(2), NetworkConfig.fromMetadata(v2Plain));
    }

    @Test
    void parameterCountsOfTheClassicalSetsArePinned() {
        // conv_in has 7 * 13 instead of 7 * 7 weights per channel: + 7 * 6 * C; the priors add 2 scalars
        assertEquals(1_684, NetworkConfig.tiny(2).parameterCount());
        assertEquals(1_684 + 7 * 6 * 8, NetworkConfig.tiny(2).withPlanes(PlaneSet.CLASSIC1_PLANES).parameterCount());
        assertEquals(1_684 + 7 * 6 * 8 + 2, NetworkConfig.tiny(2).withPlanes(PlaneSet.CLASSIC1).parameterCount());
        assertEquals(60_388 + 7 * 6 * 32 + 2, NetworkConfig.small(2).withPlanes(PlaneSet.CLASSIC1).parameterCount());
        assertEquals(61_734, NetworkConfig.small(2).withPlanes(PlaneSet.CLASSIC1).parameterCount());
        for (PlaneSet planes : PlaneSet.values()) {
            NetworkConfig config = NetworkConfig.small(3).withPlanes(planes);
            long total = 0;
            for (int[] shape : Parameters.shapes(config).values()) {
                total += Tensor.elementCount(shape);
            }
            assertEquals(config.parameterCount(), total, planes.id());
        }
    }

    @Test
    void theShapesPutThePriorScalarsLastAndWidenTheFirstConvolution() {
        Map<String, int[]> plain = Parameters.shapes(NetworkConfig.tiny(2));
        Map<String, int[]> classic = Parameters.shapes(NetworkConfig.tiny(2).withPlanes(PlaneSet.CLASSIC1));
        assertArrayEquals(new int[] {8, 49}, plain.get("conv_in.weight"));
        assertArrayEquals(new int[] {8, 91}, classic.get("conv_in.weight"));
        List<String> names = new ArrayList<>(classic.keySet());
        assertEquals(List.of(ResidualPriors.BETA, ResidualPriors.K), names.subList(names.size() - 2, names.size()));
        assertArrayEquals(new int[] {1}, classic.get(ResidualPriors.BETA));
        assertEquals(plain.size() + 2, classic.size());
        assertFalse(Parameters.shapes(NetworkConfig.tiny(2).withPlanes(PlaneSet.CLASSIC1_PLANES))
                .containsKey(ResidualPriors.BETA));
    }

    @Test
    void aClassicalFileRoundTripsAndAPlainFileKeepsVersion1() throws IOException {
        for (PlaneSet planes : PlaneSet.values()) {
            Parameters p = Parameters.uniform(NetworkConfig.tiny(2).withPlanes(planes), 5, 0.3f);
            p.extraMetadata().put("step", "12");
            Path file = dir.resolve(planes.id() + ".safetensors");
            p.save(file);
            Safetensors.Contents contents = Safetensors.read(file);
            assertEquals(planes == PlaneSet.PLAIN ? "1" : "2", contents.metadata().get("format_version"));
            Parameters loaded = Parameters.load(file);
            assertEquals(p.config(), loaded.config());
            assertEquals("12", loaded.extraMetadata().get("step"));
            assertEquals(List.copyOf(p.tensors().keySet()), List.copyOf(loaded.tensors().keySet()));
            for (String name : p.tensors().keySet()) {
                assertArrayEquals(p.get(name).data(), loaded.get(name).data(), name);
            }
        }
    }

    @Test
    void aFileWhoseTensorsDoNotMatchItsPlaneSetIsRejected() throws IOException {
        // classical metadata, plain tensors: the first convolution has the wrong width and the priors are missing
        Parameters plain = Parameters.uniform(NetworkConfig.tiny(2), 5, 0.3f);
        Map<String, String> md = metadata(NetworkConfig.tiny(2).withPlanes(PlaneSet.CLASSIC1));
        Path file = dir.resolve("mixed.safetensors");
        Safetensors.write(file, plain.tensors(), md);
        assertThrows(IOException.class, () -> Parameters.load(file));
        // plain metadata with classical tensors: unexpected tensors
        Parameters classic = Parameters.uniform(NetworkConfig.tiny(2).withPlanes(PlaneSet.CLASSIC1), 5, 0.3f);
        Path other = dir.resolve("mixed2.safetensors");
        Safetensors.write(other, classic.tensors(), metadata(NetworkConfig.tiny(2)));
        assertThrows(IOException.class, () -> Parameters.load(other));
    }

    @Test
    void aVectorNetworkSeesAnInPlaceChangeOfTheScalarsAfterReload() {
        NetworkConfig config = NetworkConfig.tiny(2).withPlanes(PlaneSet.CLASSIC1);
        Parameters p = Parameters.init(config, 1);
        VectorNetwork vector = new VectorNetwork(p);
        Position pos = Playouts.positions(2, 1, 30, 4).get(12);
        float[] input = new float[config.inputSize()];
        Encoder.encode(PlaneSet.CLASSIC1, pos, input, 0);
        float[] before = new float[Encoder.POLICY_SIZE];
        float[] valuesBefore = new float[2];
        vector.forward(input, 1, before, valuesBefore);
        p.get(ResidualPriors.BETA).data()[0] = 3f;
        p.get(ResidualPriors.K).data()[0] = 0.1f;
        float[] stale = new float[Encoder.POLICY_SIZE];
        vector.forward(input, 1, stale, new float[2]);
        assertArrayEquals(before, stale, "the packed copy is used until reload");
        vector.reload();
        float[] after = new float[Encoder.POLICY_SIZE];
        float[] valuesAfter = new float[2];
        vector.forward(input, 1, after, valuesAfter);
        assertFalse(Arrays.equals(before, after));
        assertNotEquals(valuesBefore[0], valuesAfter[0]);
        float[] reference = new float[Encoder.POLICY_SIZE];
        new Network(p).forward(input, 1, reference, new float[2]);
        for (int i = 0; i < reference.length; i++) {
            assertTrue(Network.agrees(after[i], reference[i]), "logit " + i);
        }
    }
}
