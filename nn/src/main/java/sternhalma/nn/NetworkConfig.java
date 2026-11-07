package sternhalma.nn;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The size of a network (docs/network.md, "Layers").
 *
 * <p>The sizes are bounded ({@link #MAX_CHANNELS}, {@link #MAX_BLOCKS}, {@link #MAX_VALUE_HIDDEN}), so that the
 * metadata of a small file cannot make a reader allocate gigabytes by the config alone.
 *
 * @param channels    channels C of the trunk, 1 to {@link #MAX_CHANNELS}
 * @param blocks      residual blocks B, 0 to {@link #MAX_BLOCKS}
 * @param players     players P, 2 or 3; the width of the value head output
 * @param valueHidden hidden width H of the value head, 1 to {@link #MAX_VALUE_HIDDEN}; 32 in every preset
 */
public record NetworkConfig(int channels, int blocks, int players, int valueHidden) {

    /** Hidden width of the value head in every preset. */
    public static final int VALUE_HIDDEN = 32;

    /** Largest channel count C (docs/network.md, "Size limits"). */
    public static final int MAX_CHANNELS = 1024;
    /** Largest block count B (docs/network.md, "Size limits"). */
    public static final int MAX_BLOCKS = 64;
    /** Largest hidden width H of the value head (docs/network.md, "Size limits"). */
    public static final int MAX_VALUE_HIDDEN = 1024;

    /** Value of the metadata key {@code format}. */
    public static final String FORMAT = "sternhalma-nn";
    /** The file format version that this code writes and reads. */
    public static final int FORMAT_VERSION = 1;

    public NetworkConfig {
        if (channels < 1 || channels > MAX_CHANNELS) {
            throw new IllegalArgumentException("channels must be in [1, " + MAX_CHANNELS + "], got " + channels);
        }
        if (blocks < 0 || blocks > MAX_BLOCKS) {
            throw new IllegalArgumentException("blocks must be in [0, " + MAX_BLOCKS + "], got " + blocks);
        }
        if (players != 2 && players != 3) {
            throw new IllegalArgumentException("players must be 2 or 3, got " + players);
        }
        if (valueHidden < 1 || valueHidden > MAX_VALUE_HIDDEN) {
            throw new IllegalArgumentException("value_hidden must be in [1, " + MAX_VALUE_HIDDEN + "], got "
                    + valueHidden);
        }
    }

    /** 8 channels, 1 block: for fast tests. */
    public static NetworkConfig tiny(int players) {
        return new NetworkConfig(8, 1, players, VALUE_HIDDEN);
    }

    /** 32 channels, 4 blocks. */
    public static NetworkConfig small(int players) {
        return new NetworkConfig(32, 4, players, VALUE_HIDDEN);
    }

    /** 64 channels, 6 blocks. */
    public static NetworkConfig large(int players) {
        return new NetworkConfig(64, 6, players, VALUE_HIDDEN);
    }

    /**
     * Number of floats in all parameter tensors:
     * {@code (7*I*C + C) + B*2*(7*C*C + C) + (2*C + 2) + (H*C + H) + (P*H + P)} with I = 7 input planes.
     */
    public long parameterCount() {
        long c = channels;
        long h = valueHidden;
        long convIn = (long) HexGrid.STENCIL * Encoder.PLANES * c + c;
        long block = 2 * ((long) HexGrid.STENCIL * c * c + c);
        long policy = Encoder.POLICY_CHANNELS * c + Encoder.POLICY_CHANNELS;
        long value = (h * c + h) + ((long) players * h + players);
        return convIn + blocks * block + policy + value;
    }

    /**
     * The metadata entries of docs/network.md, "Metadata keys": format, format_version, channels, blocks,
     * players, value_hidden and input_planes, in this order.
     */
    public Map<String, String> toMetadata() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("format", FORMAT);
        m.put("format_version", Integer.toString(FORMAT_VERSION));
        m.put("channels", Integer.toString(channels));
        m.put("blocks", Integer.toString(blocks));
        m.put("players", Integer.toString(players));
        m.put("value_hidden", Integer.toString(valueHidden));
        m.put("input_planes", Integer.toString(Encoder.PLANES));
        return m;
    }

    /**
     * The config that {@link #toMetadata} wrote. Other keys are ignored.
     *
     * @throws IllegalArgumentException if format or format_version is unknown, input_planes is not 7, or a
     *                                  config key is missing, not a decimal integer, or out of range
     */
    public static NetworkConfig fromMetadata(Map<String, String> metadata) {
        if (!FORMAT.equals(metadata.get("format"))) {
            throw new IllegalArgumentException("unknown format: " + metadata.get("format"));
        }
        if (intValue(metadata, "format_version") != FORMAT_VERSION) {
            throw new IllegalArgumentException("unknown format_version: " + metadata.get("format_version"));
        }
        if (intValue(metadata, "input_planes") != Encoder.PLANES) {
            throw new IllegalArgumentException("input_planes must be " + Encoder.PLANES + ", got "
                    + metadata.get("input_planes"));
        }
        return new NetworkConfig(
                intValue(metadata, "channels"),
                intValue(metadata, "blocks"),
                intValue(metadata, "players"),
                intValue(metadata, "value_hidden"));
    }

    private static int intValue(Map<String, String> metadata, String key) {
        String text = metadata.get(key);
        if (text == null) {
            throw new IllegalArgumentException("metadata key " + key + " is missing");
        }
        // Only plain decimal digits: Integer.parseInt would also accept "+3" and non-ASCII digits.
        if (text.isEmpty() || text.length() > 9 || !text.chars().allMatch(ch -> ch >= '0' && ch <= '9')) {
            throw new IllegalArgumentException("metadata key " + key + " is not a decimal integer: " + text);
        }
        return Integer.parseInt(text);
    }
}
