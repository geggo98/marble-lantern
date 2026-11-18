package sternhalma.search;

import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Supplier;

import sternhalma.engine.Geometry;
import sternhalma.engine.Position;
import sternhalma.tablebase.RaceTable;

/**
 * Registry of named evaluators for the arena. A new evaluation variant adds one class and one line to
 * {@link #REGISTRY}; the arena then accepts {@code eval=<name>} in a player spec.
 *
 * <p>Suppliers return a fresh evaluator per game, so evaluators may keep scratch space.
 */
public final class Evaluators {

    private static final Map<String, Function<Map<String, Integer>, Evaluator>> REGISTRY = new TreeMap<>();
    private static final Map<String, RaceEntry> RACE_ENTRIES = new TreeMap<>();

    static {
        put("distance", DistanceEvaluator::new);
        put("rows", RowEvaluator::new);
        put("linear", LinearEvaluator::new);
        // frozen round-1 weights: the evaluator of the reference player R2 (ReferencePositions.R2_SPEC)
        put("linear-r1", () -> new LinearEvaluator(LinearWeights.R1, LinearWeights.SCALE));
        // round-2 refit on passed-filtered BEST self-play (E04); BEST_SPEC changes only in an integration commit
        put("linear-r2", () -> new LinearEvaluator(LinearWeights.ROUND2, LinearWeights.SCALE));
        // E07: the 13th feature bestGain (both sides), refitted on passed-filtered self-play with race play
        put("linear-r3", () -> new LinearEvaluator(LinearWeights.ROUND3, LinearWeights.SCALE,
                LinearEvaluator.GainMode.BOTH));
        REGISTRY.put("stage4", Stage4Evaluator::of);
        // round-2 experiment E08: linear-r2 with exact race arithmetic as the leaf value in passed positions, inside
        // the RACE score band; racelin reads the committed rows>=2 table, racelin1 the rows>=1 table (E09; loaded from
        // tablebase/tables/ or built into the cache on first use, like racerows=1). The one weight, wout=1, estimates
        // the counts of pieces behind the region (RaceEvaluator). No racelin-r1: the R2 fingerprint stays untouched.
        putRace("racelin", "linear-r2", 2);
        putRace("racelin1", "linear-r2", 1);
        // integration of round 2: the race arithmetic of E08 over the bestGain evaluator of E07 (the evaluator of
        // Strategies.BEST_SPEC and of the reference player R3, ReferencePositions.R3_SPEC). Frozen like its parts.
        putRace("racelin1-r3", "linear-r3", 1);
        // the browser player (Strategies.BROWSER_SPEC): the same evaluation as racelin1-r3, but over the rows>=2 table
        // (12.5 MB instead of 242 MB), the only table a browser can hold. Not frozen: it may move with BEST_SPEC.
        putRace("racelin-r3", "linear-r3", 2);
    }

    /**
     * Prefixes for a race evaluator over any registered base evaluator, for experiments without a rebuild:
     * {@code eval=racelin1:linear-r3} is {@code racelin1-r3}, {@code eval=racelin:linear} wraps the live weights with
     * the rows&gt;=2 table. The weights of the spec ({@code wout}) go to the race evaluator; the base takes none.
     */
    static final String RACELIN_PREFIX = "racelin:";
    static final String RACELIN1_PREFIX = "racelin1:";

    /**
     * A {@link RaceEvaluator} over the registered evaluator {@code base} with the race table of the region
     * {@code rows} (2: the committed rows&gt;=2 table; 1: the rows&gt;=1 table of E09, from the cache, the
     * {@code .zst} archive or a rebuild), both taken from a {@link RaceTableSource} (the evaluator role). The base
     * evaluator is built over the same source, so a race evaluator never reads the ambient tables.
     */
    private static Function<Map<String, Integer>, Evaluator> race(String base, int rows, RaceTableSource source) {
        return weights -> {
            RaceTableSource.Table t = source.evaluator(rows);
            return RaceEvaluator.of(byName(base, Map.of(), source), t.counts(), t.minRow(), weights);
        };
    }

    /** Race evaluators of the registry by name: base evaluator and region, for building them over another source. */
    private record RaceEntry(String base, int rows) {
    }

    private static void putRace(String name, String base, int rows) {
        RACE_ENTRIES.put(name, new RaceEntry(base, rows));
    }

    /** Registers an evaluator without weights; weights given in a spec are then an error. */
    private static void put(String name, Supplier<Evaluator> supplier) {
        REGISTRY.put(name, weights -> {
            if (!weights.isEmpty()) {
                throw new IllegalArgumentException("evaluator '" + name + "' takes no weights, got " + weights.keySet());
            }
            return supplier.get();
        });
    }

    /**
     * Prefix for a linear evaluator with weights from a file, for tuning experiments without a rebuild:
     * {@code eval=linear-file:/path/w.txt}. See {@link LinearWeights#fromFile(String)}.
     */
    static final String LINEAR_FILE = "linear-file:";

    private Evaluators() {
    }

    /** The names of all registered evaluators; the strategy contract tests cover every one of them. */
    public static java.util.Set<String> names() {
        java.util.Set<String> all = new java.util.TreeSet<>(REGISTRY.keySet());
        all.addAll(RACE_ENTRIES.keySet());
        return java.util.Collections.unmodifiableSet(all);
    }

    public static Evaluator byName(String name) {
        return byName(name, Map.of());
    }

    /**
     * An evaluator with weights from a player spec (keys {@code w...}). A race evaluator takes its table from
     * {@link RaceTableSource#ambient()}, which is the exact shared table unless a system property says otherwise.
     */
    public static Evaluator byName(String name, Map<String, Integer> weights) {
        return byName(name, weights, RaceTableSource.ambient());
    }

    /** {@link #byName(String, Map)} with the race tables of {@code source}; the source matters for race evaluators only. */
    public static Evaluator byName(String name, Map<String, Integer> weights, RaceTableSource source) {
        if (name.startsWith(LINEAR_FILE)) {
            if (!weights.isEmpty()) {
                throw new IllegalArgumentException("'" + LINEAR_FILE + "' takes no weights, got " + weights.keySet());
            }
            return LinearWeights.fromFile(name.substring(LINEAR_FILE.length()));
        }
        if (name.startsWith(RACELIN1_PREFIX)) {
            return race(name.substring(RACELIN1_PREFIX.length()), 1, source).apply(weights);
        }
        if (name.startsWith(RACELIN_PREFIX)) {
            return race(name.substring(RACELIN_PREFIX.length()), 2, source).apply(weights);
        }
        RaceEntry entry = RACE_ENTRIES.get(name);
        if (entry != null) {
            return race(entry.base(), entry.rows(), source).apply(weights);
        }
        Function<Map<String, Integer>, Evaluator> factory = REGISTRY.get(name);
        if (factory == null) {
            throw new IllegalArgumentException("unknown evaluator '" + name + "', known: " + names());
        }
        return factory.apply(weights);
    }

    /**
     * Stage 1 of docs/search-and-evaluation.md: the sum of row distances to the goal row. Free, but full of
     * plateaus, because sideways moves score zero.
     */
    static final class RowEvaluator implements Evaluator {
        @Override
        public int evaluate(Position position) {
            int me = position.sideToMove();
            int opponent = 1 - me;
            return 10 * (rowSum(position, opponent) - rowSum(position, me));
        }

        /** Rows still to cross: the goal rows are r = 5..8 (player 0) and r = -8..-5 (player 1). */
        private static int rowSum(Position position, int player) {
            int goalRow = player == 0 ? 8 : -8;
            int sum = 0;
            for (int c = 0; c < Geometry.CELLS; c++) {
                if (position.owner(c) == player) {
                    sum += Math.abs(goalRow - Geometry.r(c));
                }
            }
            return sum;
        }
    }
}
