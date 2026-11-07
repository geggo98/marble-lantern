package sternhalma.search;

import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

import sternhalma.engine.Geometry;
import sternhalma.engine.Position;

/**
 * Registry of named evaluators for the arena. A new evaluation variant adds one class and one line to
 * {@link #REGISTRY}; the arena then accepts {@code eval=<name>} in a player spec.
 *
 * <p>Suppliers return a fresh evaluator per game, so evaluators may keep scratch space.
 */
public final class Evaluators {

    private static final Map<String, Supplier<Evaluator>> REGISTRY = new TreeMap<>();

    static {
        REGISTRY.put("distance", DistanceEvaluator::new);
        REGISTRY.put("rows", RowEvaluator::new);
        REGISTRY.put("linear", LinearEvaluator::new);
    }

    /**
     * Prefix for a linear evaluator with weights from a file, for tuning experiments without a rebuild:
     * {@code eval=linear-file:/path/w.txt}. See {@link LinearWeights#fromFile(String)}.
     */
    static final String LINEAR_FILE = "linear-file:";

    private Evaluators() {
    }

    public static Evaluator byName(String name) {
        if (name.startsWith(LINEAR_FILE)) {
            return LinearWeights.fromFile(name.substring(LINEAR_FILE.length()));
        }
        Supplier<Evaluator> supplier = REGISTRY.get(name);
        if (supplier == null) {
            throw new IllegalArgumentException("unknown evaluator '" + name + "', known: " + REGISTRY.keySet());
        }
        return supplier.get();
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
