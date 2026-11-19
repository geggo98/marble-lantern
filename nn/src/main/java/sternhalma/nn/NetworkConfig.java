package sternhalma.nn;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The size of a network (docs/network.md, "Layers").
 *
 * <p>The sizes are bounded ({@link #MAX_CHANNELS}, {@link #MAX_BLOCKS}, {@link #MAX_VALUE_HIDDEN}), so that the
 * metadata of a small file cannot make a reader allocate gigabytes by the config alone.
 *
 * @param channels    channels C of the trunk, 1 to {@link #MAX_CHANNELS}
 * @param blocks      residual blocks B, 0 to {@link #MAX_BLOCKS}
 * @param players     players P, 2 or 3; the width of the value head output. A network of {@link PlaneSet#CLASSIC1V}
 *                    has P = 3 and serves positions of two and three players
 * @param valueHidden hidden width H of the value head, 1 to {@link #MAX_VALUE_HIDDEN}; 32 in every preset
 * @param planes      the input planes and priors, {@link PlaneSet#PLAIN} for the networks of format version 1
 */
public record NetworkConfig(int channels, int blocks, int players, int valueHidden, PlaneSet planes) {

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
    /** The format version of a network with the plain planes; version 1 files are read as {@link PlaneSet#PLAIN}. */
    public static final int FORMAT_VERSION = 1;
    /** The format version of a network with classical planes: adds the metadata key {@code planes}. */
    public static final int FORMAT_VERSION_PLANES = 2;
    /**
     * The format version of a network of {@link PlaneSet#CLASSIC1V}: 15 input planes and a value head of width 3 that
     * serves two and three players (docs/network.md, "Format version 3 (variants)").
     */
    public static final int FORMAT_VERSION_VARIANTS = 3;

    /** A network with the plain planes. */
    public NetworkConfig(int channels, int blocks, int players, int valueHidden) {
        this(channels, blocks, players, valueHidden, PlaneSet.PLAIN);
    }

    public NetworkConfig {
        Objects.requireNonNull(planes, "planes");
        if (channels < 1 || channels > MAX_CHANNELS) {
            throw new IllegalArgumentException("channels must be in [1, " + MAX_CHANNELS + "], got " + channels);
        }
        if (blocks < 0 || blocks > MAX_BLOCKS) {
            throw new IllegalArgumentException("blocks must be in [0, " + MAX_BLOCKS + "], got " + blocks);
        }
        if (players != 2 && players != 3) {
            throw new IllegalArgumentException("players must be 2 or 3, got " + players);
        }
        if (planes.variants() && players != 3) {
            throw new IllegalArgumentException("planes " + planes.id() + " needs players 3 (the value head serves two and"
                    + " three players), got " + players);
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
     * The same network size with another set of input planes and priors. {@link PlaneSet#CLASSIC1V} sets players to 3,
     * the value head width of that plane set.
     */
    public NetworkConfig withPlanes(PlaneSet other) {
        return new NetworkConfig(channels, blocks, other.variants() ? 3 : players, valueHidden, other);
    }

    /** Input planes per cell: 7 for {@link PlaneSet#PLAIN}, 13 for the classical sets, 15 for {@link PlaneSet#CLASSIC1V}. */
    public int inputPlanes() {
        return planes.planes();
    }

    /** Floats of one encoded position: 122 rows times {@link #inputPlanes()}. */
    public int inputSize() {
        return planes.inputSize();
    }

    /**
     * Number of floats in all parameter tensors:
     * {@code (7*I*C + C) + B*2*(7*C*C + C) + (2*C + 2) + (H*C + H) + (P*H + P)} with I input planes, plus 2 for
     * {@code prior.beta} and {@code prior.k} if the plane set has residual priors.
     */
    public long parameterCount() {
        long c = channels;
        long h = valueHidden;
        long convIn = (long) HexGrid.STENCIL * inputPlanes() * c + c;
        long block = 2 * ((long) HexGrid.STENCIL * c * c + c);
        long policy = Encoder.POLICY_CHANNELS * c + Encoder.POLICY_CHANNELS;
        long value = (h * c + h) + ((long) players * h + players);
        long priors = planes.residualPriors() ? 2 : 0;
        return convIn + blocks * block + policy + value + priors;
    }

    /**
     * The metadata entries of docs/network.md, "Metadata keys": format, format_version, channels, blocks,
     * players, value_hidden and input_planes, in this order, and for a classical plane set the key {@code planes}
     * after them. A network with the plain planes is written as format version 1, so that older readers still read
     * it; a plane set with classical planes is format version 2, and {@link PlaneSet#CLASSIC1V} is format version 3.
     */
    public Map<String, String> toMetadata() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("format", FORMAT);
        m.put("format_version", Integer.toString(planes == PlaneSet.PLAIN ? FORMAT_VERSION
                : planes.variants() ? FORMAT_VERSION_VARIANTS : FORMAT_VERSION_PLANES));
        m.put("channels", Integer.toString(channels));
        m.put("blocks", Integer.toString(blocks));
        m.put("players", Integer.toString(players));
        m.put("value_hidden", Integer.toString(valueHidden));
        m.put("input_planes", Integer.toString(inputPlanes()));
        if (planes != PlaneSet.PLAIN) {
            m.put("planes", planes.id());
        }
        return m;
    }

    /**
     * The config that {@link #toMetadata} wrote. Other keys are ignored. Version 1 files have 7 planes and no key
     * {@code planes}; they read as {@link PlaneSet#PLAIN}.
     *
     * @throws IllegalArgumentException if format or format_version is unknown, input_planes does not belong to the
     *                                  plane set (7 in version 1), a version 2 file has no or an unknown {@code
     *                                  planes}, or a config key is missing, not a decimal integer, or out of range
     */
    public static NetworkConfig fromMetadata(Map<String, String> metadata) {
        if (!FORMAT.equals(metadata.get("format"))) {
            throw new IllegalArgumentException("unknown format: " + metadata.get("format"));
        }
        int version = intValue(metadata, "format_version");
        PlaneSet planes;
        if (version == FORMAT_VERSION) {
            if (metadata.containsKey("planes")) {
                throw new IllegalArgumentException("format_version 1 has no key planes, got " + metadata.get("planes"));
            }
            planes = PlaneSet.PLAIN;
        } else if (version == FORMAT_VERSION_PLANES) {
            if (!metadata.containsKey("planes")) {
                throw new IllegalArgumentException("metadata key planes is missing");
            }
            planes = PlaneSet.ofId(metadata.get("planes"));
            if (planes.variants()) {
                throw new IllegalArgumentException("planes " + planes.id() + " is format_version 3, not 2");
            }
        } else if (version == FORMAT_VERSION_VARIANTS) {
            if (!metadata.containsKey("planes")) {
                throw new IllegalArgumentException("metadata key planes is missing");
            }
            planes = PlaneSet.ofId(metadata.get("planes"));
            if (!planes.variants()) {
                throw new IllegalArgumentException("format_version 3 is for planes classic1v, got " + planes.id());
            }
        } else {
            throw new IllegalArgumentException("unknown format_version: " + metadata.get("format_version"));
        }
        if (intValue(metadata, "input_planes") != planes.planes()) {
            throw new IllegalArgumentException("input_planes must be " + planes.planes() + " for planes "
                    + planes.id() + ", got " + metadata.get("input_planes"));
        }
        return new NetworkConfig(
                intValue(metadata, "channels"),
                intValue(metadata, "blocks"),
                intValue(metadata, "players"),
                intValue(metadata, "value_hidden"),
                planes);
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
