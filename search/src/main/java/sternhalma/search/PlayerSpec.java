package sternhalma.search;

import java.util.HashMap;
import java.util.Map;

/**
 * A player described by text, e.g. {@code ab:eval=distance,nodes=20000,depth=20,tt=18}.
 *
 * <p>Keys: {@code eval} (see {@link Evaluators}), {@code nodes} (per move, default 20000), {@code depth}
 * (default 30), {@code ms} (time per move, default none), {@code tt} (log2 table entries, default 18).
 * A node budget, not a time budget, is the default so that results do not depend on machine load.
 */
public record PlayerSpec(String text, String evaluator, long nodes, int depth, long millis, int tableBits) {

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
        if (!options.isEmpty()) {
            throw new IllegalArgumentException("unknown options " + options.keySet());
        }
        return new PlayerSpec(
                text,
                evaluator == null ? "distance" : evaluator,
                nodes == null ? 20_000 : Long.parseLong(nodes),
                depth == null ? 30 : Integer.parseInt(depth),
                ms == null ? Long.MAX_VALUE : Long.parseLong(ms),
                tt == null ? 18 : Integer.parseInt(tt));
    }

    /** A new player; call once per game, because a player keeps its transposition table. */
    public AlphaBetaPlayer create() {
        return new AlphaBetaPlayer(Evaluators.byName(evaluator), depth, millis, nodes, tableBits, evaluator);
    }
}
