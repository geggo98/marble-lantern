package sternhalma.search;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import sternhalma.nn.ForwardPass;
import sternhalma.tablebase.RaceCounts;
import sternhalma.tablebase.RaceTable;

/**
 * An MCTS player described by text, e.g. {@code mcts:net=data/run/candidate.safetensors,sims=100,batch=1,cpuct=1.0,
 * noise=0,temp=0,race=1}. Parsing needs no network; the network file is named by {@code net} and loaded by whoever
 * calls {@link #create(ForwardPass, long)} (the module {@code selfplay} does, with the fastest forward pass of the
 * machine). A spec without {@code net} stands for "the network at hand" (the tools of {@code selfplay} then use their
 * own, such as the checkpoint of {@code gen0 strength}).
 *
 * <p>Keys, all optional, defaults in {@link MctsOptions#defaults()}: {@code net} (the network file, a path without a
 * comma), {@code sims}, {@code batch}, {@code cpuct}, {@code ptemp} (prior temperature), {@code noise}
 * (Dirichlet weight, 0 off), {@code alpha} (Dirichlet concentration), {@code temp} (move temperature, 0 = most
 * visited), {@code fpu}, {@code race} (0 off, 1, 2: race play in passed positions as in {@link PlayerSpec}),
 * {@code racerows} (1 or 2, needs {@code race}), {@code s1} (0 or 1), {@code win1} (0 or 1), {@code reuse} (0 or 1),
 * {@code ms} (time per move).
 *
 * @param text    the spec text
 * @param net     the network file, or null if the spec does not name one
 * @param options the search options
 */
public record MctsSpec(String text, Path net, MctsOptions options) {

    /** Whether the text names an MCTS player (kind {@code mcts}). */
    public static boolean isMcts(String text) {
        return text.startsWith("mcts:") || text.equals("mcts");
    }

    public static MctsSpec parse(String text) {
        String[] head = text.split(":", 2);
        if (!head[0].equals("mcts")) {
            throw new IllegalArgumentException("unknown player kind '" + head[0] + "', expected 'mcts'");
        }
        Map<String, String> kv = new HashMap<>();
        if (head.length == 2 && !head[1].isBlank()) {
            for (String pair : head[1].split(",")) {
                String[] split = pair.split("=", 2);
                if (split.length != 2) {
                    throw new IllegalArgumentException("expected key=value, got '" + pair + "'");
                }
                if (kv.put(split[0].trim(), split[1].trim()) != null) {
                    throw new IllegalArgumentException("option " + split[0].trim() + " is given twice");
                }
            }
        }
        String net = kv.remove("net");
        if (net != null && net.isEmpty()) {
            throw new IllegalArgumentException("net= is empty");
        }
        MctsOptions d = MctsOptions.defaults();
        int race = integer(kv.remove("race"), d.race(), "race");
        String rows = kv.remove("racerows");
        if (rows != null && race == 0) {
            throw new IllegalArgumentException("racerows needs race=1 or race=2");
        }
        String ms = kv.remove("ms");
        MctsOptions options = new MctsOptions(
                integer(kv.remove("sims"), d.sims(), "sims"),
                integer(kv.remove("batch"), d.batch(), "batch"),
                real(kv.remove("cpuct"), d.cpuct(), "cpuct"),
                real(kv.remove("ptemp"), d.priorTemperature(), "ptemp"),
                real(kv.remove("noise"), d.noise(), "noise"),
                real(kv.remove("alpha"), d.dirichlet(), "alpha"),
                real(kv.remove("temp"), d.temperature(), "temp"),
                real(kv.remove("fpu"), d.fpu(), "fpu"),
                race,
                integer(rows, d.raceRows(), "racerows"),
                flag(kv.remove("s1"), d.s1(), "s1"),
                flag(kv.remove("win1"), d.win1(), "win1"),
                flag(kv.remove("reuse"), d.reuse(), "reuse"),
                ms == null ? Long.MAX_VALUE : integer(ms, 0, "ms"));
        if (!kv.isEmpty()) {
            throw new IllegalArgumentException("unknown options " + kv.keySet());
        }
        return new MctsSpec(text, net == null ? null : Path.of(net), options);
    }

    /**
     * A new player; call once per game, because a player keeps its random generator (and, with {@code reuse=1}, its
     * tree). The network is used as it is: pass one {@link ForwardPass} per thread unless it is thread-safe.
     *
     * @param seed seeds the Dirichlet noise and the sampling of moves; with neither ({@code noise=0, temp=0}) the
     *             player is deterministic and ignores it
     */
    public MctsPlayer create(ForwardPass network, long seed) {
        return MctsPlayer.create(network, options, seed, text);
    }

    /** The race table of an option set: the table of {@code racerows}, or null when race play is off. */
    static RaceCounts raceTable(MctsOptions options) {
        return options.race() == 0 ? null : RaceTable.of(options.raceRows());
    }

    private static int integer(String value, int def, String key) {
        if (value == null) {
            return def;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer, got '" + value + "'");
        }
    }

    private static double real(String value, double def, String key) {
        if (value == null) {
            return def;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a number, got '" + value + "'");
        }
    }

    private static boolean flag(String value, boolean def, String key) {
        if (value == null) {
            return def;
        }
        return switch (value) {
            case "0" -> false;
            case "1" -> true;
            default -> throw new IllegalArgumentException(key + " must be 0 or 1, got '" + value + "'");
        };
    }
}
