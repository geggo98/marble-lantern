package sternhalma.search;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

import sternhalma.engine.RuleSet.JumpRule;
import sternhalma.engine.RuleSet.TriangleRows;

/**
 * A three-player player described by text, e.g. {@code ab3:nodes=20000,tt=16,jump=long}.
 *
 * <p>Keys: {@code eval} ({@code linear-r2}, the default, {@code linear-r1} or {@code linear}: the 12-feature linear
 * weights; the race evaluators and {@code bestGain} are two-player only), {@code nodes} (per move, default 20000),
 * {@code depth} (default 30), {@code ms} (time per move, default none; makes the result machine dependent),
 * {@code tt} (log2 table entries, default 16) and {@code jump=adjacent|long} (default adjacent; the rule of the games
 * the player plays) and {@code rows=4|5} (default 4; the house size of the games the player plays). No race table and no race play: the player works for both jump rules.
 */
public record ThreePlayerSpec(String text, String evaluator, long nodes, int depth, long millis, int tableBits,
        JumpRule jumpRule, TriangleRows rows) {

    public ThreePlayerSpec(String text, String evaluator, long nodes, int depth, long millis, int tableBits,
            JumpRule jumpRule) {
        this(text, evaluator, nodes, depth, millis, tableBits, jumpRule, TriangleRows.FOUR);
    }

    public ThreePlayerSpec {
        Objects.requireNonNull(rows, "rows");
        Objects.requireNonNull(jumpRule, "jumpRule");
        weightsOf(evaluator);
    }

    private static double[] weightsOf(String evaluator) {
        return switch (evaluator) {
            case "linear-r2" -> LinearWeights.ROUND2;
            case "linear-r1" -> LinearWeights.R1;
            case "linear" -> LinearWeights.WEIGHTS;
            default -> throw new IllegalArgumentException("three-player evaluator must be linear-r2, linear-r1 or "
                    + "linear, got '" + evaluator + "'");
        };
    }

    public static ThreePlayerSpec parse(String text) {
        String[] head = text.split(":", 2);
        if (!head[0].equals("ab3")) {
            throw new IllegalArgumentException("unknown player kind '" + head[0] + "', expected 'ab3'");
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
        String jump = options.remove("jump");
        String rows = options.remove("rows");
        if (!options.isEmpty()) {
            throw new IllegalArgumentException("unknown options " + new TreeSet<>(options.keySet()));
        }
        JumpRule rule;
        if (jump == null || jump.equals("adjacent")) {
            rule = JumpRule.ADJACENT;
        } else if (jump.equals("long")) {
            rule = JumpRule.LONG;
        } else {
            throw new IllegalArgumentException("jump must be adjacent or long, got '" + jump + "'");
        }
        return new ThreePlayerSpec(text, evaluator == null ? "linear-r2" : evaluator,
                nodes == null ? 20_000 : Long.parseLong(nodes), depth == null ? 30 : Integer.parseInt(depth),
                ms == null ? Long.MAX_VALUE : Long.parseLong(ms), tt == null ? 16 : Integer.parseInt(tt), rule,
                PlayerSpec.parseRows(rows));
    }

    /** A new player; call once per game, because a player keeps its transposition table. */
    public ThreePlayerPlayer create() {
        return new ThreePlayerPlayer(weightsOf(evaluator), evaluator, depth, millis, nodes, tableBits, jumpRule, rows);
    }
}
