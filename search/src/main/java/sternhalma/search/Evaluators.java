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

    static {
        put("distance", DistanceEvaluator::new);
        put("rows", RowEvaluator::new);
        put("linear", LinearEvaluator::new);
        // frozen round-1 weights: the evaluator of the reference player R2 (ReferencePositions.R2_SPEC)
        put("linear-r1", () -> new LinearEvaluator(LinearWeights.R1, LinearWeights.SCALE));
        // round-2 refit on passed-filtered BEST self-play (E04); BEST_SPEC changes only in an integration commit
        put("linear-r2", () -> new LinearEvaluator(LinearWeights.ROUND2, LinearWeights.SCALE));
        REGISTRY.put("stage4", Stage4Evaluator::of);
        // round-2 experiment E08: linear-r2 with exact race arithmetic as the leaf value in passed positions, inside
        // the RACE score band; racelin reads the committed rows>=2 table, racelin1 the rows>=1 table (E09; loaded from
        // tablebase/tables/ or built into the cache on first use, like racerows=1). The one weight, wout=1, estimates
        // the counts of pieces behind the region (RaceEvaluator). No racelin-r1: the R2 fingerprint stays untouched.
        REGISTRY.put("racelin", weights -> RaceEvaluator.of(new LinearEvaluator(LinearWeights.ROUND2,
                LinearWeights.SCALE), RaceTable.rows2(), RaceTable.ROWS2.minRow(), weights));
        REGISTRY.put("racelin1", weights -> RaceEvaluator.of(new LinearEvaluator(LinearWeights.ROUND2,
                LinearWeights.SCALE), RaceTable.rows1(), RaceTable.ROWS1.minRow(), weights));
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
        return java.util.Collections.unmodifiableSet(REGISTRY.keySet());
    }

    public static Evaluator byName(String name) {
        return byName(name, Map.of());
    }

    /** An evaluator with weights from a player spec (keys {@code w...}). */
    public static Evaluator byName(String name, Map<String, Integer> weights) {
        if (name.startsWith(LINEAR_FILE)) {
            if (!weights.isEmpty()) {
                throw new IllegalArgumentException("'" + LINEAR_FILE + "' takes no weights, got " + weights.keySet());
            }
            return LinearWeights.fromFile(name.substring(LINEAR_FILE.length()));
        }
        Function<Map<String, Integer>, Evaluator> factory = REGISTRY.get(name);
        if (factory == null) {
            throw new IllegalArgumentException("unknown evaluator '" + name + "', known: " + REGISTRY.keySet());
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
