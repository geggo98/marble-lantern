package sternhalma.search;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.BitSet;

import sternhalma.tablebase.BlockRestrictedCounts;
import sternhalma.tablebase.EstimatedCounts;
import sternhalma.tablebase.MaxStonesCounts;
import sternhalma.tablebase.PushEstimator;
import sternhalma.tablebase.RaceCounts;
import sternhalma.tablebase.RaceTable;
import sternhalma.tablebase.RowOneBlocks;

/**
 * Where the two users of a race table get it from: the seam between the leaf evaluator ({@link RaceEvaluator}) and
 * race play ({@link RaceMove}) on one side and the tables on the other. The default source returns the shared exact
 * table of the region, which is what every player did before this seam existed; the other sources serve
 * measurements (docs/ is written later; see the probe tools of the selfplay module).
 *
 * <p>A source answers per role, so that an experiment can swap the table of one role only. Only a request for the
 * rows&gt;=1 region is ever changed; a request for rows&gt;=2 always gets the exact rows&gt;=2 table (the browser spec).
 *
 * <p><b>Variants</b> by text, for {@link #parse} and the system properties {@value #PROPERTY_EVAL},
 * {@value #PROPERTY_PLAY} and {@value #PROPERTY_BOTH} (the latter sets both roles; a role property wins):
 * <ul>
 *   <li>{@code exact}: the rows&gt;=1 table (the default);</li>
 *   <li>{@code rows2}: the rows&gt;=2 table in place of rows&gt;=1, as {@code racerows=2} would give;</li>
 *   <li>{@code blocks:FILE}: rows&gt;=1 inside the 64 KB blocks of the file ({@link RowOneBlocks}), else as {@code rows2};</li>
 *   <li>{@code estimate[:c0,...,c8]}: the {@link PushEstimator} with the given corrections per k (default all 0);
 *       the rows&gt;=1 table is not read.</li>
 *   <li>{@code maxk:N[:estimate|rows2]}: the exact rows&gt;=1 count where the side has at most N stones in row 1, else the
 *       push estimator (default) or the rows&gt;=2 count; simulates a rows&gt;=1 table that stores only k &lt;= N.</li>
 * </ul>
 * With no property set, {@link #ambient()} is {@link #DEFAULT} and the construction path of a player is the old one.
 */
public interface RaceTableSource {

    String PROPERTY_BOTH = "sternhalma.race.variant";
    String PROPERTY_EVAL = "sternhalma.race.eval";
    String PROPERTY_PLAY = "sternhalma.race.play";

    /**
     * A table for a role and the row the table is meant to cover.
     *
     * @param counts the counts
     * @param minRow the first row of the region that the counts may cover (1 or 2): the futility hook of the evaluator
     *               needs a region that contains everything the counts cover
     */
    record Table(RaceCounts counts, int minRow) {
    }

    /** The table of the leaf evaluator for a spec that asks for the region {@code rows} (1 or 2). */
    Table evaluator(int rows);

    /** The table of race play for a spec that asks for the region {@code rows} (1 or 2). */
    Table racePlay(int rows);

    /** The shared exact tables: what a player used before this seam existed. */
    RaceTableSource DEFAULT = new RaceTableSource() {
        @Override
        public Table evaluator(int rows) {
            return exact(rows);
        }

        @Override
        public Table racePlay(int rows) {
            return exact(rows);
        }

        @Override
        public String toString() {
            return "exact";
        }
    };

    /** The exact table of a region. */
    static Table exact(int rows) {
        RaceTable t = RaceTable.of(rows);
        return new Table(t, t.minRow());
    }

    /** One variant for both roles. */
    static RaceTableSource of(String variant) {
        return of(variant, variant);
    }

    /** Two variants, one per role; text as in the class comment. */
    static RaceTableSource of(String evaluatorVariant, String racePlayVariant) {
        Variant e = Variant.parse(evaluatorVariant);
        Variant p = Variant.parse(racePlayVariant);
        return new RaceTableSource() {
            @Override
            public Table evaluator(int rows) {
                return e.table(rows);
            }

            @Override
            public Table racePlay(int rows) {
                return p.table(rows);
            }

            @Override
            public String toString() {
                return "eval=" + evaluatorVariant + ",play=" + racePlayVariant;
            }
        };
    }

    /** A source from two explicit tables per role (for tools that wrap the tables, such as the rank logger). */
    static RaceTableSource ofTables(java.util.function.IntFunction<Table> evaluator,
            java.util.function.IntFunction<Table> racePlay) {
        return new RaceTableSource() {
            @Override
            public Table evaluator(int rows) {
                return evaluator.apply(rows);
            }

            @Override
            public Table racePlay(int rows) {
                return racePlay.apply(rows);
            }
        };
    }

    /**
     * The source named by the system properties, read once per JVM; {@link #DEFAULT} if none is set. A bad value is an
     * error at the first player, not a silent fallback to the exact table.
     */
    static RaceTableSource ambient() {
        return Ambient.SOURCE;
    }

    /** Holder: the properties are read when the first player is built, not when this interface loads. */
    final class Ambient {
        static final RaceTableSource SOURCE = read();

        private Ambient() {
        }

        private static RaceTableSource read() {
            String both = System.getProperty(PROPERTY_BOTH);
            String eval = System.getProperty(PROPERTY_EVAL, both);
            String play = System.getProperty(PROPERTY_PLAY, both);
            if (eval == null && play == null) {
                return DEFAULT;
            }
            return of(eval == null ? "exact" : eval, play == null ? "exact" : play);
        }
    }

    /** A parsed variant text. */
    final class Variant {
        private final String text;
        private final String kind;
        private final Path file;
        private final int[] corrections;
    private final int maxK;
    private final boolean estimateFallback;
        private BitSet blocks;

        private Variant(String text, String kind, Path file, int[] corrections) {
            this(text, kind, file, corrections, 0, false);
        }

        private Variant(String text, String kind, Path file, int[] corrections, int maxK, boolean estimateFallback) {
            this.maxK = maxK;
            this.estimateFallback = estimateFallback;
            this.text = text;
            this.kind = kind;
            this.file = file;
            this.corrections = corrections;
        }

        static Variant parse(String text) {
            String[] head = text.split(":", 2);
            switch (head[0]) {
                case "exact", "rows2" -> {
                    if (head.length != 1) {
                        throw new IllegalArgumentException("race variant '" + text + "' takes no argument");
                    }
                    return new Variant(text, head[0], null, null);
                }
                case "blocks" -> {
                    if (head.length != 2 || head[1].isBlank()) {
                        throw new IllegalArgumentException("race variant 'blocks' needs a file: blocks:FILE");
                    }
                    return new Variant(text, "blocks", Path.of(head[1]), null);
                }
                case "estimate" -> {
                    int[] c = new int[PushEstimator.CLASSES];
                    if (head.length == 2) {
                        String[] parts = head[1].split(",");
                        if (parts.length != c.length) {
                            throw new IllegalArgumentException("race variant 'estimate' takes " + c.length
                                    + " corrections, got " + parts.length);
                        }
                        for (int i = 0; i < c.length; i++) {
                            c[i] = Integer.parseInt(parts[i].strip());
                        }
                    }
                    return new Variant(text, "estimate", null, c);
                }
                case "maxk" -> {
                    String[] parts = head.length == 2 ? head[1].split(":", -1) : new String[0];
                    if (parts.length < 1 || parts.length > 2) {
                        throw new IllegalArgumentException("race variant 'maxk' needs maxk:N[:estimate|rows2]");
                    }
                    int n = Integer.parseInt(parts[0].strip());
                    if (n < 0 || n > PushEstimator.CLASSES - 1) {
                        throw new IllegalArgumentException("race variant 'maxk': N must be 0.." + (PushEstimator.CLASSES - 1));
                    }
                    String fallback = parts.length == 2 ? parts[1] : "estimate";
                    if (!fallback.equals("estimate") && !fallback.equals("rows2")) {
                        throw new IllegalArgumentException("race variant 'maxk': fallback must be estimate or rows2, got '"
                                + fallback + "'");
                    }
                    return new Variant(text, "maxk", null, new int[PushEstimator.CLASSES], n, fallback.equals("estimate"));
                }
                default -> throw new IllegalArgumentException("unknown race variant '" + text
                        + "', expected exact, rows2, blocks:FILE, estimate[:c0,...,c8] or maxk:N[:estimate|rows2]");
            }
        }

        synchronized Table table(int rows) {
            if (rows == 2 || kind.equals("exact")) {
                return exact(rows);
            }
            return switch (kind) {
                case "rows2" -> exact(2);
                case "blocks" -> {
                    if (blocks == null) {
                        try {
                            blocks = RowOneBlocks.read(file);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    }
                    yield new Table(new BlockRestrictedCounts(RaceTable.rows1(), RaceTable.rows2(), blocks), 1);
                }
                case "maxk" -> new Table(new MaxStonesCounts(RaceTable.rows1(), maxK,
                        estimateFallback ? new EstimatedCounts(new PushEstimator(RaceTable.rows2(), corrections))
                                : RaceTable.rows2()), 1);
                // one estimator per table: it keeps scratch space and must not be shared between searchers
                default -> new Table(new EstimatedCounts(new PushEstimator(RaceTable.rows2(), corrections)), 1);
            };
        }

        @Override
        public String toString() {
            return text + (corrections == null ? "" : " " + Arrays.toString(corrections));
        }
    }
}
