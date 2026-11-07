package sternhalma.nn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NetworkConfigTest {

    /** The preset table of docs/network.md, "Presets and parameter counts". */
    @Test
    void parameterCountsMatchTheDesignDocument() {
        assertEquals(1_684, NetworkConfig.tiny(2).parameterCount());
        assertEquals(1_717, NetworkConfig.tiny(3).parameterCount());
        assertEquals(60_388, NetworkConfig.small(2).parameterCount());
        assertEquals(60_421, NetworkConfig.small(3).parameterCount());
        assertEquals(350_308, NetworkConfig.large(2).parameterCount());
        assertEquals(350_341, NetworkConfig.large(3).parameterCount());
    }

    /** Counts each layer by hand, so a wrong term in the formula cannot hide behind the preset values. */
    @Test
    void parameterCountAddsEveryLayer() {
        NetworkConfig config = new NetworkConfig(5, 2, 3, 4);
        int convIn = 5 * 49 + 5;
        int block = 2 * (5 * 35 + 5);
        int policy = 2 * 5 + 2;
        int value = (4 * 5 + 4) + (3 * 4 + 3);
        assertEquals(convIn + 2 * block + policy + value, config.parameterCount());
        assertEquals(convIn + policy + value, new NetworkConfig(5, 0, 3, 4).parameterCount());
    }

    @Test
    void presetsHaveTheOwnersSizes() {
        assertEquals(new NetworkConfig(8, 1, 2, 32), NetworkConfig.tiny(2));
        assertEquals(new NetworkConfig(32, 4, 3, 32), NetworkConfig.small(3));
        assertEquals(new NetworkConfig(64, 6, 2, 32), NetworkConfig.large(2));
    }

    @Test
    void invalidSizesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new NetworkConfig(0, 1, 2, 32));
        assertThrows(IllegalArgumentException.class, () -> new NetworkConfig(8, -1, 2, 32));
        assertThrows(IllegalArgumentException.class, () -> new NetworkConfig(8, 1, 4, 32));
        assertThrows(IllegalArgumentException.class, () -> new NetworkConfig(8, 1, 1, 32));
        assertThrows(IllegalArgumentException.class, () -> new NetworkConfig(8, 1, 2, 0));
    }

    /** The documented maxima are allowed, one more is rejected, for each bounded size. */
    @Test
    void sizesAreBoundedByTheDocumentedMaxima() {
        assertEquals(1024, NetworkConfig.MAX_CHANNELS);
        assertEquals(64, NetworkConfig.MAX_BLOCKS);
        assertEquals(1024, NetworkConfig.MAX_VALUE_HIDDEN);
        new NetworkConfig(1024, 64, 3, 1024);
        assertThrows(IllegalArgumentException.class, () -> new NetworkConfig(1025, 1, 2, 32));
        assertThrows(IllegalArgumentException.class, () -> new NetworkConfig(8, 65, 2, 32));
        assertThrows(IllegalArgumentException.class, () -> new NetworkConfig(8, 1, 2, 1025));
        assertThrows(IllegalArgumentException.class, () -> new NetworkConfig(8, 100_000_000, 2, 32));
    }

    @Test
    void metadataHasTheDocumentedKeysInOrder() {
        Map<String, String> m = new NetworkConfig(32, 4, 3, 16).toMetadata();
        assertEquals(List.of("format", "format_version", "channels", "blocks", "players", "value_hidden",
                "input_planes"), List.copyOf(m.keySet()));
        assertEquals(List.of("sternhalma-nn", "1", "32", "4", "3", "16", "7"), List.copyOf(m.values()));
    }

    @Test
    void metadataRoundTripsAndIgnoresExtraKeys() {
        NetworkConfig config = new NetworkConfig(12, 3, 3, 20);
        Map<String, String> m = new LinkedHashMap<>(config.toMetadata());
        m.put("training_step", "1234");
        assertEquals(config, NetworkConfig.fromMetadata(m));
    }

    @Test
    void badMetadataIsRejected() {
        Map<String, String> good = NetworkConfig.small(2).toMetadata();
        for (String key : good.keySet()) {
            Map<String, String> missing = new LinkedHashMap<>(good);
            missing.remove(key);
            assertThrows(IllegalArgumentException.class, () -> NetworkConfig.fromMetadata(missing), key);
        }
        assertRejected(good, "format", "other");
        assertRejected(good, "format_version", "2");
        assertRejected(good, "input_planes", "8");
        assertRejected(good, "channels", "+32");
        assertRejected(good, "channels", "3 2");
        assertRejected(good, "channels", "");
        assertRejected(good, "channels", "99999999999");
        assertRejected(good, "players", "4");
        assertRejected(good, "blocks", "-1");
        assertRejected(good, "blocks", "100000000");
        assertRejected(good, "channels", "1025");
        assertRejected(good, "value_hidden", "1025");
    }

    private static void assertRejected(Map<String, String> good, String key, String value) {
        Map<String, String> bad = new LinkedHashMap<>(good);
        bad.put(key, value);
        assertThrows(IllegalArgumentException.class, () -> NetworkConfig.fromMetadata(bad), key + "=" + value);
    }
}
