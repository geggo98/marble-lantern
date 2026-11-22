package sternhalma.nn;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import sternhalma.engine.RuleSet.TriangleRows;

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
 * @param rows        the board sizes the network was trained for (docs/network.md, "Board sizes"): not empty, an
 *                    unmodifiable set; {@link TriangleRows#FOUR} alone for every network before the 5-row board. The
 *                    {@link Evaluator} refuses a position of another size. Stored in the optional metadata key
 *                    {@value #ROWS_KEY}, which is written only when the set is not {@code {FOUR}}
 */
public record NetworkConfig(int channels, int blocks, int players, int valueHidden, PlaneSet planes,
        Set<TriangleRows> rows) {

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
    /**
     * The optional metadata key of {@link #rows()}: {@code 4}, {@code 5} or {@code 4,5}. A file without it is a
     * network of the 4-row board. The key needs no new format version: readers that do not know it (C++, older Java)
     * ignore it, and the tensors do not depend on it.
     */
    public static final String ROWS_KEY = "triangle_rows";

    private static final Set<TriangleRows> FOUR_ONLY = Collections.unmodifiableSet(EnumSet.of(TriangleRows.FOUR));

    /** A network with the plain planes. */
    public NetworkConfig(int channels, int blocks, int players, int valueHidden) {
        this(channels, blocks, players, valueHidden, PlaneSet.PLAIN);
    }

    /** A network of the 4-row board. */
    public NetworkConfig(int channels, int blocks, int players, int valueHidden, PlaneSet planes) {
        this(channels, blocks, players, valueHidden, planes, FOUR_ONLY);
    }

    public NetworkConfig {
        Objects.requireNonNull(planes, "planes");
        Objects.requireNonNull(rows, "rows");
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("rows must name at least one board size");
        }
        rows = rows.equals(FOUR_ONLY) ? FOUR_ONLY : Collections.unmodifiableSet(EnumSet.copyOf(rows));
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
        return new NetworkConfig(channels, blocks, other.variants() ? 3 : players, valueHidden, other, rows);
    }

    /** The same network for other board sizes. */
    public NetworkConfig withRows(Set<TriangleRows> other) {
        return new NetworkConfig(channels, blocks, players, valueHidden, planes, other);
    }

    /** Whether the network was trained for positions of this board size. */
    public boolean supports(TriangleRows size) {
        return rows.contains(size);
    }

    /** The text of {@link #ROWS_KEY}: the row counts in ascending order, comma separated, e.g. {@code 4,5}. */
    public static String rowsText(Set<TriangleRows> rows) {
        StringBuilder sb = new StringBuilder();
        for (TriangleRows r : TriangleRows.values()) {
            if (rows.contains(r)) {
                sb.append(sb.length() == 0 ? "" : ",").append(r.rows());
            }
        }
        return sb.toString();
    }

    /**
     * The board sizes of a {@link #ROWS_KEY} text: {@code 4}, {@code 5} or {@code 4,5}; null means {@code 4}.
     *
     * @throws IllegalArgumentException for any other text (also {@code 5,4}, a repeated size or spaces)
     */
    public static Set<TriangleRows> parseRows(String text) {
        if (text == null) {
            return FOUR_ONLY;
        }
        return switch (text) {
            case "4" -> FOUR_ONLY;
            case "5" -> Collections.unmodifiableSet(EnumSet.of(TriangleRows.FIVE));
            case "4,5" -> Collections.unmodifiableSet(EnumSet.of(TriangleRows.FOUR, TriangleRows.FIVE));
            default -> throw new IllegalArgumentException("metadata key " + ROWS_KEY + " must be 4, 5 or 4,5, got '"
                    + text + "'");
        };
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
     * The key {@value #ROWS_KEY} comes last, and only for a network that is not for the 4-row board alone.
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
        if (!rows.equals(FOUR_ONLY)) {
            // only then: the metadata, and so the bytes, of every 4-row network stay what they were
            m.put(ROWS_KEY, rowsText(rows));
        }
        return m;
    }

    /**
     * The config that {@link #toMetadata} wrote. Other keys are ignored. Version 1 files have 7 planes and no key
     * {@code planes}; they read as {@link PlaneSet#PLAIN}. Without the key {@value #ROWS_KEY} the network is for the
     * 4-row board.
     *
     * @throws IllegalArgumentException if format or format_version is unknown, input_planes does not belong to the
     *                                  plane set (7 in version 1), a version 2 file has no or an unknown {@code
     *                                  planes}, a config key is missing, not a decimal integer, or out of range,
     *                                  or {@value #ROWS_KEY} is not one of its texts ({@link #parseRows})
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
                planes,
                parseRows(metadata.get(ROWS_KEY)));
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
