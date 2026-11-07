package sternhalma.search;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import sternhalma.tablebase.RaceTable;

/**
 * A player described by text, e.g. {@code ab:eval=distance,nodes=20000,depth=20,tt=18}.
 *
 * <p>Keys: {@code eval} (see {@link Evaluators}), {@code nodes} (per move, default 20000), {@code depth}
 * (default 30), {@code ms} (time per move, default none), {@code tt} (log2 table entries, default 18).
 * Keys of the form {@code w<letters/digits>} with integer values are evaluator weights, e.g.
 * {@code ab:eval=stage4,ws=5}; the evaluator decides which weights it accepts.
 * A node budget, not a time budget, is the default so that results do not depend on machine load.
 *
 * <p>Search enhancements ({@link SearchOptions}), all off by default: {@code pvs=1}, {@code killers=1},
 * {@code history=1}, {@code lmr=1|2}, {@code asp=1} (default window) or {@code asp=<half-width>},
 * {@code ttrep=1} (depth-preferred table), {@code mate=1} (mate scores relative to the node),
 * {@code partial=1} (use the best move of an interrupted iteration), {@code fut=<margin>} and
 * {@code fut2=<margin>} (futility pruning at remaining depth 1 and 2, 0 off), {@code futk=<units per step>}
 * (gain factor of the futility bound, default {@value SearchOptions#DEFAULT_FUTILITY_GAIN}).
 *
 * <p>Race play ({@link RaceMove}), off by default: {@code race=1} plays passed positions from the race table and the
 * solitaire beam without a search, {@code race=2} does so only while the race is not known to be lost;
 * {@code racetable=0} disables the table (beam only). Two players only; with three players the search decides.
 *
 * @param race      0 off, 1 or 2 the race mode
 * @param raceTable whether race play may use the race table
 */
public record PlayerSpec(String text, String evaluator, long nodes, int depth, long millis, int tableBits,
        SearchOptions options, Map<String, Integer> weights, int race, boolean raceTable) {

    public PlayerSpec {
        weights = Map.copyOf(weights);
        if (race < 0 || race > 2) {
            throw new IllegalArgumentException("race must be 0, 1 or 2, got " + race);
        }
    }

    public PlayerSpec(String text, String evaluator, long nodes, int depth, long millis, int tableBits,
            SearchOptions options, Map<String, Integer> weights) {
        this(text, evaluator, nodes, depth, millis, tableBits, options, weights, 0, true);
    }

    public PlayerSpec(String text, String evaluator, long nodes, int depth, long millis, int tableBits,
            SearchOptions options) {
        this(text, evaluator, nodes, depth, millis, tableBits, options, Map.of());
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
        int asp = intOption(options.remove("asp"));
        int fut = intOption(options.remove("fut"));
        String futk = options.remove("futk");
        int fut2 = intOption(options.remove("fut2"));
        SearchOptions search = new SearchOptions(
                flag(options.remove("pvs"), "pvs"),
                flag(options.remove("killers"), "killers"),
                flag(options.remove("history"), "history"),
                intOption(options.remove("lmr")),
                asp == 1 ? SearchOptions.DEFAULT_ASPIRATION : asp,
                flag(options.remove("ttrep"), "ttrep"),
                flag(options.remove("mate"), "mate"),
                flag(options.remove("partial"), "partial"),
                fut,
                futk == null ? SearchOptions.DEFAULT_FUTILITY_GAIN : Integer.parseInt(futk),
                fut2);
        int race = intOption(options.remove("race"));
        String raceTable = options.remove("racetable");
        boolean useTable = raceTable == null || flag(raceTable, "racetable");
        // what is left and looks like w<letters/digits> are evaluator weights
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
                search,
                weights,
                race,
                useTable);
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
        String label = weights.isEmpty() ? evaluator : evaluator + weights;
        RaceMove raceMove = race == 0 ? null : new RaceMove(raceTable ? RaceTable.rows2() : null, race);
        return new AlphaBetaPlayer(Evaluators.byName(evaluator, weights), depth, millis, nodes, tableBits, label,
                options, raceMove);
    }
}
