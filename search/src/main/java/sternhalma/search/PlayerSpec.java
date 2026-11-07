package sternhalma.search;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * A player described by text, e.g. {@code ab:eval=distance,nodes=20000,depth=20,tt=18}.
 *
 * <p>Keys: {@code eval} (see {@link Evaluators}), {@code nodes} (per move, default 20000), {@code depth}
 * (default 30), {@code ms} (time per move, default none), {@code tt} (log2 table entries, default 18).
 * Keys of the form {@code w<letters/digits>} with integer values are evaluator weights, e.g.
 * {@code ab:eval=stage4,ws=5}; the evaluator decides which weights it accepts.
 * A node budget, not a time budget, is the default so that results do not depend on machine load.
 */
public record PlayerSpec(String text, String evaluator, long nodes, int depth, long millis, int tableBits,
        Map<String, Integer> weights) {

    public PlayerSpec {
        weights = Map.copyOf(weights);
    }

    public PlayerSpec(String text, String evaluator, long nodes, int depth, long millis, int tableBits) {
        this(text, evaluator, nodes, depth, millis, tableBits, Map.of());
    }

    public static PlayerSpec parse(String text) {
        String[] head = text.split(":", 2);
        if (!head[0].equals("ab")) {
            throw new IllegalArgumentException("unknown player kind '" + head[0] + "', expected 'ab'");
        }
        Map<String, String> options = new HashMap<>();
        if (head.length == 2 && !head[1].isBlank()) {
            for (String pair : head[1].split(",")) {
                String[] kv = pair.split("=", 2);
                if (kv.length != 2) {
                    throw new IllegalArgumentException("expected key=value, got '" + pair + "'");
                }
                options.put(kv[0].trim(), kv[1].trim());
            }
        }
        String evaluator = options.remove("eval");
        String nodes = options.remove("nodes");
        String depth = options.remove("depth");
        String ms = options.remove("ms");
        String tt = options.remove("tt");
        Map<String, Integer> weights = new TreeMap<>();
        options.keySet().removeIf(key -> {
            if (key.matches("w[a-z0-9]+")) {
                weights.put(key, Integer.parseInt(options.get(key)));
                return true;
            }
            return false;
        });
        if (!options.isEmpty()) {
            throw new IllegalArgumentException("unknown options " + options.keySet());
        }
        return new PlayerSpec(
                text,
                evaluator == null ? "distance" : evaluator,
                nodes == null ? 20_000 : Long.parseLong(nodes),
                depth == null ? 30 : Integer.parseInt(depth),
                ms == null ? Long.MAX_VALUE : Long.parseLong(ms),
                tt == null ? 18 : Integer.parseInt(tt),
                weights);
    }

    /** A new player; call once per game, because a player keeps its transposition table. */
    public AlphaBetaPlayer create() {
        String label = weights.isEmpty() ? evaluator : evaluator + weights;
        return new AlphaBetaPlayer(Evaluators.byName(evaluator, weights), depth, millis, nodes, tableBits, label);
    }
}
