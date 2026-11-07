package sternhalma.search;

import java.util.HashMap;
import java.util.Map;

/**
 * A player described by text, e.g. {@code ab:eval=distance,nodes=20000,depth=20,tt=18}.
 *
 * <p>Keys: {@code eval} (see {@link Evaluators}), {@code nodes} (per move, default 20000), {@code depth}
 * (default 30), {@code ms} (time per move, default none), {@code tt} (log2 table entries, default 18).
 * A node budget, not a time budget, is the default so that results do not depend on machine load.
 *
 * <p>Search enhancements ({@link SearchOptions}), all off by default: {@code pvs=1}, {@code killers=1},
 * {@code history=1}, {@code lmr=1|2}, {@code asp=1} (default window) or {@code asp=<half-width>},
 * {@code ttrep=1} (depth-preferred table), {@code mate=1} (mate scores relative to the node),
 * {@code partial=1} (use the best move of an interrupted iteration).
 */
public record PlayerSpec(String text, String evaluator, long nodes, int depth, long millis, int tableBits,
        SearchOptions options) {

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
        int asp = intOption(options.remove("asp"));
        SearchOptions search = new SearchOptions(
                flag(options.remove("pvs"), "pvs"),
                flag(options.remove("killers"), "killers"),
                flag(options.remove("history"), "history"),
                intOption(options.remove("lmr")),
                asp == 1 ? SearchOptions.DEFAULT_ASPIRATION : asp,
                flag(options.remove("ttrep"), "ttrep"),
                flag(options.remove("mate"), "mate"),
                flag(options.remove("partial"), "partial"));
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
                search);
    }

    private static boolean flag(String value, String key) {
        if (value == null || value.equals("0")) {
            return false;
        }
        if (value.equals("1")) {
            return true;
        }
        throw new IllegalArgumentException(key + " must be 0 or 1, got '" + value + "'");
    }

    private static int intOption(String value) {
        return value == null ? 0 : Integer.parseInt(value);
    }

    /** A new player; call once per game, because a player keeps its transposition table. */
    public AlphaBetaPlayer create() {
        return new AlphaBetaPlayer(Evaluators.byName(evaluator), depth, millis, nodes, tableBits, evaluator,
                options);
    }
}
