package sternhalma.selfplay;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.LongFunction;

import sternhalma.engine.Outcome;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.nn.ForwardPass;
import sternhalma.search.Elo;
import sternhalma.search.GameRunner;
import sternhalma.search.Player;
import sternhalma.search.PlayerSpec;
import sternhalma.search.Strategies;

/**
 * The Elo estimate (c2) of a network: its raw policy ({@link RawPolicyPlayer}) plays a paired mini-arena against each
 * rung of a fixed classical ladder, and the Elo difference to the rung is reported with a 95 % interval, with the
 * arithmetic of {@code Arena} and {@link Elo} (docs/training.md, "Run monitor").
 *
 * <p>A rung is {@code BEST_SPEC} at a node limit, so the opponent does not depend on machine load. Each pair starts from
 * the same seeded random opening of {@link #OPENING_PLIES} plies (as {@code Arena} does) and is played twice with the
 * colours swapped; the score of a pair is the mean of the two game scores {@code (1 + value) / 2}. The result for a rung
 * is the mean pair score, its standard error and {@code Elo.fromScore}; a mean score of exactly 0 or 1 is flagged
 * {@code clamped} (the Elo is then only a bound).
 *
 * <p>The result of one checkpoint is a file of JSON lines, one per rung, written under a temporary name and renamed;
 * {@link #complete} says whether an existing file holds all the requested rungs, so a rerun can skip.
 */
final class StrengthEstimate {

    static final int OPENING_PLIES = 4;
    /** The node limits of the default ladder. */
    static final List<Integer> DEFAULT_LADDER = List.of(200);

    /**
     * The result against one rung.
     *
     * @param rung   the opponent's node limit
     * @param spec   the opponent's spec text
     * @param clamped whether the mean score is 0 or 1, so that {@code elo} is a bound
     */
    record Rung(int rung, String spec, int pairs, long seed, double score, double scoreStandardError, double elo,
            double eloConfidence95, int wins, int draws, int losses, boolean clamped) {

        Map<String, Object> fields(long step) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("step", step);
            f.put("rung", rung);
            f.put("spec", spec);
            f.put("pairs", pairs);
            f.put("seed", seed);
            f.put("score", score);
            f.put("score_se", scoreStandardError);
            f.put("elo", elo);
            f.put("elo_ci95", eloConfidence95);
            f.put("wins", wins);
            f.put("draws", draws);
            f.put("losses", losses);
            f.put("clamped", clamped);
            return f;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "vs %d nodes: Elo %+.0f +- %.0f (95%%)%s, score %.3f, %d-%d-%d games,"
                    + " %d pairs", rung, elo, eloConfidence95, clamped ? " clamped" : "", score, wins, draws, losses,
                    pairs);
        }
    }

    private StrengthEstimate() {
    }

    /**
     * The smallest node limit above 1 that is a rung of its own. {@code AlphaBeta.search} always completes depth 1
     * (the node limit is checked only from depth 2 on), so every limit below the cost of a full depth-1 search plays
     * exactly the depth-1 moves: measured on 100 random positions, {@code BEST_SPEC} with 1, 5 or 20 nodes chose the
     * same move as {@code depth=1} in 100 of 100 (a depth-1 search costs 47 nodes on average); with 100 nodes 94 of
     * 100 are the same, with 400 nodes 75. Rung 1 is therefore "depth 1", the weakest rung, and 2 to 99 are refused
     * instead of repeating it.
     */
    static final int MIN_RUNG_ABOVE_ONE = 100;

    /** Refuses a ladder with a rung in 2 to {@link #MIN_RUNG_ABOVE_ONE} - 1, which would repeat rung 1. */
    static void requireDistinctRungs(List<Integer> ladder) {
        for (int nodes : ladder) {
            if (nodes < 1 || nodes > 1 && nodes < MIN_RUNG_ABOVE_ONE) {
                throw new IllegalArgumentException("a ladder rung is 1 (depth 1: the search always completes depth 1)"
                        + " or at least " + MIN_RUNG_ABOVE_ONE + " nodes; " + nodes + " nodes would play the same"
                        + " moves as rung 1");
            }
        }
        if (new java.util.HashSet<>(ladder).size() != ladder.size()) {
            throw new IllegalArgumentException("a ladder rung is listed twice: " + ladder);
        }
    }

    /** The spec text of a rung. */
    static String spec(String base, int nodes) {
        return base + ",nodes=" + nodes + ",tt=16";
    }

    /** The opening of pair {@code pair}: {@code plies} uniformly random legal plies from the start position. */
    static Position opening(RuleSet rules, long seed, int pair, int plies) {
        Random random = new Random(seed * 1_000_003L + pair);
        Position pos = Position.initial(rules);
        for (int i = 0; i < plies; i++) {
            int[] moves = pos.legalMoves();
            pos.make(moves[random.nextInt(moves.length)]);
        }
        return pos;
    }

    /** Both games of one pair; the score of the network, in [0, 1], for each and whether it won. */
    private record PairResult(double score, int wins, int draws, int losses) {
    }

    /**
     * Plays the mini-arena of every rung.
     *
     * @param base     the spec of the ladder opponent without {@code nodes} (normally {@code Strategies.BEST_SPEC})
     * @param ladder   node limits, one rung each
     * @param stop     asked before each pair; true discards everything and returns null
     * @param progress called with the number of pairs finished (of this call) after each pair, from any thread
     * @return one result per rung, or null if stopped
     */
    static List<Rung> estimate(ForwardPass network, String base, List<Integer> ladder, int pairs, long seed, int threads,
            BooleanSupplier stop, IntConsumer progress) {
        RawPolicyPlayer raw = new RawPolicyPlayer(network, "raw-policy");
        return estimate(gameSeed -> raw, base, ladder, pairs, seed, threads, stop, progress);
    }

    /**
     * The same estimate for any player under test, e.g. {@link NetPlayers#factory}.
     *
     * @param subject makes the player for one game from a seed that is distinct for every game of the estimate (a
     *                player that is stateless or deterministic ignores it); called from the thread that plays
     */
    static List<Rung> estimate(LongFunction<Player> subject, String base, List<Integer> ladder, int pairs, long seed,
            int threads, BooleanSupplier stop, IntConsumer progress) {
        if (pairs < 2 || threads < 1 || ladder.isEmpty()) {
            throw new IllegalArgumentException("need pairs >= 2, threads >= 1 and a ladder");
        }
        RuleSet rules = RuleSet.defaults(2);
        List<Rung> result = new ArrayList<>();
        AtomicInteger done = new AtomicInteger();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int nodes : ladder) {
                PlayerSpec spec = PlayerSpec.parse(spec(base, nodes));
                List<Future<PairResult>> futures = new ArrayList<>();
                for (int pair = 0; pair < pairs; pair++) {
                    int index = pair;
                    futures.add(pool.submit(() -> {
                        if (stop.getAsBoolean()) {
                            return null;
                        }
                        Position start = opening(rules, seed, index, OPENING_PLIES);
                        PairResult r = play(start, subject, spec, (seed * 1_000_003L + index) * 2);
                        progress.accept(done.incrementAndGet());
                        return r;
                    }));
                }
                double[] scores = new double[pairs];
                int wins = 0;
                int draws = 0;
                int losses = 0;
                boolean stopped = false;
                for (int pair = 0; pair < pairs; pair++) {
                    PairResult r;
                    try {
                        r = futures.get(pair).get();
                    } catch (ExecutionException e) {
                        futures.forEach(f -> f.cancel(false));
                        throw new IllegalStateException("game failed", e.getCause());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("interrupted", e);
                    }
                    if (r == null) {
                        stopped = true;
                        continue;
                    }
                    scores[pair] = r.score;
                    wins += r.wins;
                    draws += r.draws;
                    losses += r.losses;
                }
                if (stopped) {
                    return null;
                }
                double score = Elo.mean(scores);
                double se = Elo.standardError(scores);
                result.add(new Rung(nodes, spec.text(), pairs, seed, score, se, Elo.fromScore(score),
                        Elo.confidence95(score, se), wins, draws, losses, clamped(score)));
            }
        }
        return result;
    }

    /** Whether a mean score of exactly 0 or 1 makes the Elo only a bound. */
    static boolean clamped(double score) {
        return score <= 0 || score >= 1;
    }

    private static PairResult play(Position start, LongFunction<Player> subject, PlayerSpec spec, long gameSeed) {
        double sum = 0;
        int wins = 0;
        int draws = 0;
        int losses = 0;
        for (int rawIndex = 0; rawIndex < 2; rawIndex++) {
            Player mine = subject.apply(gameSeed + rawIndex);
            GameRunner.Result r = rawIndex == 0
                    ? GameRunner.play(start, mine, spec.create())
                    : GameRunner.play(start, spec.create(), mine);
            Outcome outcome = r.outcome();
            sum += (1.0 + outcome.value(rawIndex)) / 2.0;
            if (outcome.hasWinner()) {
                if (outcome.winner() == rawIndex) {
                    wins++;
                } else {
                    losses++;
                }
            } else {
                draws++;
            }
        }
        return new PairResult(sum / 2.0, wins, draws, losses);
    }

    /** The result file of a checkpoint step: {@code dir/step-<8 digits>.jsonl}. */
    static Path file(Path dir, long step) {
        return dir.resolve(String.format("step-%08d.jsonl", step));
    }

    /** The result file of a checkpoint step for a player other than the raw policy: {@code step-<8 digits>.player-<hash>.jsonl}. */
    static Path playerFile(Path dir, long step, String player) {
        return dir.resolve(String.format("step-%08d.player-%s.jsonl", step,
                Gen0.sha256Hex(player.getBytes(StandardCharsets.UTF_8)).substring(0, 12)));
    }

    /** Writes the result file atomically (temporary name, then rename). */
    static void write(Path file, long step, List<Rung> rungs) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Rung r : rungs) {
            sb.append(RunMonitor.json(r.fields(step))).append('\n');
        }
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Whether the file holds exactly the requested rungs with the requested pairs and seed (and so is a finished
     * result that a rerun may skip). A missing, unreadable or different file is not complete.
     */
    static boolean complete(Path file, String base, List<Integer> ladder, int pairs, long seed) {
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.size() != ladder.size()) {
                return false;
            }
            for (int i = 0; i < lines.size(); i++) {
                Map<String, String> m = RunMonitor.parse(lines.get(i));
                if (m == null || !m.getOrDefault("spec", "").equals(spec(base, ladder.get(i)))
                        || !m.getOrDefault("pairs", "").equals(Integer.toString(pairs))
                        || !m.getOrDefault("seed", "").equals(Long.toString(seed)) || !m.containsKey("elo")) {
                    return false;
                }
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    static String defaultBase() {
        return Strategies.BEST_SPEC;
    }
}
