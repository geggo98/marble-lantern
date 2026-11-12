package sternhalma.selfplay;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongFunction;
import java.util.function.Supplier;

import sternhalma.engine.RuleSet;
import sternhalma.search.Elo;
import sternhalma.search.Player;
import sternhalma.selfplay.MctsMatch.PairLine;

/**
 * The gating of the training loop (docs/training.md, "Gating" and "As implemented (block 2h)"): the promotion rule as
 * a pure function of the pair scores, and {@link PairJob}, the resumable job that plays the pairs of a match in shards.
 */
final class Gate {

    private Gate() {
    }

    /**
     * The outcome of a gate.
     *
     * @param promoted           the candidate becomes the best network
     * @param pairs              pairs of the match against the best network
     * @param score              mean pair score of the candidate against the best network
     * @param scoreStandardError standard error of that mean
     * @param baselinePairs      pairs against the fixed rung (0: the clause is off)
     * @param baselineCandidate  mean pair score of the candidate against the rung
     * @param baselineBest       mean pair score of the best network against the rung
     * @param baselineDiff       mean of the paired differences (candidate minus best), pair by pair
     * @param baselineDiffStandardError standard error of that mean
     * @param baselineSigmas     the clause: promotion needs {@code diff >= -sigmas * se}
     * @param baselineOk         whether the clause holds
     * @param promoteZ          the significance rule: promotion needs {@code score - promoteZ * se > 0.5} (0: just {@code score > 0.5})
     * @param z                 the statistic {@code (score - 0.5) / se}, infinite for a standard error of 0 (0 if the score is 0.5 too)
     */
    record Decision(boolean promoted, int pairs, double score, double scoreStandardError, int baselinePairs,
            double baselineCandidate, double baselineBest, double baselineDiff, double baselineDiffStandardError,
            double baselineSigmas, boolean baselineOk, double promoteZ, double z) {

        /** The normal-approximation interval, cut to the scores that exist: [0, 1]. */
        double ci95Low() {
            return Math.max(0, score - 1.96 * scoreStandardError);
        }

        double ci95High() {
            return Math.min(1, score + 1.96 * scoreStandardError);
        }

        String describe() {
            String zText = Double.isFinite(z) ? String.format(Locale.ROOT, "%.2f", z) : (z > 0 ? "+inf" : z < 0 ? "-inf" : "0");
            String clause = baselinePairs == 0 ? "baseline clause not evaluated"
                    : String.format(Locale.ROOT, "vs the rung candidate %.3f, best %.3f, difference %+.3f +- %.3f (%s)",
                            baselineCandidate, baselineBest, baselineDiff, baselineDiffStandardError,
                            baselineOk ? "ok" : "regression of more than " + baselineSigmas + " standard errors");
            return String.format(Locale.ROOT, "%s: score %.3f +- %.3f (95%% interval %.3f to %.3f) over %d pairs, z %s (needs above %.2f); %s",
                    promoted ? "promoted" : "rejected", score, scoreStandardError, ci95Low(), ci95High(), pairs, zText, promoteZ,
                    clause);
        }
    }

    /**
     * The promotion rule: the candidate's mean score against the best network is above 0.5 by more than {@code promoteZ}
     * standard errors (one-sided; {@code score - promoteZ * se > 0.5}, so 0 is the plain "above 0.5"), and its score against the
     * fixed rung is not lower than the best network's by more than {@code sigmas} standard errors (the standard error
     * of the paired differences, pair by pair: both networks play the same openings). Equal to 0.5 does not promote.
     *
     * @param gate              pair scores of the candidate against the best network
     * @param candidateBaseline pair scores of the candidate against the rung, or empty for no clause
     * @param bestBaseline      the same for the best network, pair for pair
     */
    static Decision decide(double[] gate, double[] candidateBaseline, double[] bestBaseline, double sigmas,
            double promoteZ) {
        if (gate.length < 2) {
            throw new IllegalArgumentException("a gate needs at least 2 pairs");
        }
        if (candidateBaseline.length != bestBaseline.length) {
            throw new IllegalArgumentException("the baseline matches differ in length");
        }
        double score = Elo.mean(gate);
        double se = Elo.standardError(gate);
        if (!(promoteZ >= 0)) {
            throw new IllegalArgumentException("promoteZ must be >= 0");
        }
        boolean clause = true;
        double cand = Double.NaN;
        double best = Double.NaN;
        double diff = Double.NaN;
        double diffSe = Double.NaN;
        if (candidateBaseline.length > 0) {
            if (candidateBaseline.length < 2) {
                throw new IllegalArgumentException("a baseline match needs at least 2 pairs");
            }
            double[] d = new double[candidateBaseline.length];
            for (int i = 0; i < d.length; i++) {
                d[i] = candidateBaseline[i] - bestBaseline[i];
            }
            cand = Elo.mean(candidateBaseline);
            best = Elo.mean(bestBaseline);
            diff = Elo.mean(d);
            diffSe = Elo.standardError(d);
            clause = diff >= -sigmas * diffSe;
        }
        double z = se > 0 ? (score - 0.5) / se : score > 0.5 ? Double.POSITIVE_INFINITY : score < 0.5 ? Double.NEGATIVE_INFINITY : 0;
        boolean significant = score - promoteZ * se > 0.5;
        return new Decision(significant && clause, gate.length, score, se, candidateBaseline.length, cand, best, diff,
                diffSe, sigmas, clause, promoteZ, z);
    }

    /** The pair scores in pair order. */
    static double[] scores(List<PairLine> lines) {
        double[] s = new double[lines.size()];
        for (int i = 0; i < s.length; i++) {
            s[i] = lines.get(i).score();
        }
        return s;
    }

    /**
     * Plays the pairs of one match in shards of {@code shardPairs} pairs: item = pair index, one file per shard
     * ({@code <prefix>-<shard>.jsonl}: a header line that names the match, then one line per pair). A shard that reads,
     * names this match and holds its pairs is SKIP; any other (a half file cannot exist, shards are renamed into
     * place) is renamed to {@code *.corrupt} and redone. A pair is a function of the match, so the pairs of an aborted
     * and a resumed run are those of an uninterrupted one.
     */
    static final class PairJob extends Job<PairLine> {

        private final String prefix;
        private final String identity;
        private final Supplier<Worker<PairLine>> factory;
        final AtomicLong cpuNanos = new AtomicLong();
        private final Loop.StopHook hook;
        private final String hookPhase;
        private final AtomicLong finished = new AtomicLong();

        /**
         * @param identity names the match: shards of another match in the directory are not reused
         * @param play     plays pair {@code i} (one worker per thread)
         */
        PairJob(Path dir, String prefix, String label, String identity, int pairs, int shardPairs, int threads,
                PrintStream err, Supplier<Worker<PairLine>> play, Loop.StopHook hook, String hookPhase) {
            super(dir, prefix, pairs, shardPairs, threads, err);
            this.label = label;
            this.prefix = prefix;
            this.identity = identity;
            this.factory = play;
            this.hook = hook;
            this.hookPhase = hookPhase;
        }

        @Override
        Path shardFile(int shard) {
            return dir.resolve(String.format(Locale.ROOT, "%s-%03d.jsonl", prefix, shard));
        }

        private String header(int first, int count) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("kind", "header");
            f.put("match", identity);
            f.put("first", first);
            f.put("count", count);
            return RunMonitor.json(f);
        }

        @Override
        ShardState check(Path file, int shard, int first, int count) {
            try {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                if (lines.size() != count + 1 || !lines.get(0).equals(header(first, count))) {
                    return ShardState.CORRUPT;
                }
                for (int i = 0; i < count; i++) {
                    Map<String, String> m = RunMonitor.parse(lines.get(i + 1));
                    if (m == null || PairLine.of(m).pair() != first + i) {
                        return ShardState.CORRUPT;
                    }
                }
                return ShardState.COMPLETE;
            } catch (IOException | RuntimeException e) {
                return ShardState.CORRUPT;
            }
        }

        @Override
        Supplier<Worker<PairLine>> workers() {
            return () -> {
                Worker<PairLine> inner = factory.get();
                return pair -> {
                    PairLine line = inner.run(pair);
                    cpuNanos.addAndGet(line.subjectCpuNanos() + line.opponentCpuNanos());
                    if (hook != null && hook.stop(hookPhase, finished.incrementAndGet())) {
                        requestStop();
                    }
                    return line;
                };
            };
        }

        @Override
        void write(Path file, int shard, int first, List<PairLine> results) throws IOException {
            StringBuilder sb = new StringBuilder(header(first, results.size())).append('\n');
            for (PairLine l : results) {
                sb.append(RunMonitor.json(l.fields())).append('\n');
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp." + ProcessHandle.current().pid());
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }

        /** The pairs of the shards on disk, in pair order; null if a pair is missing. */
        List<PairLine> results() throws IOException {
            List<PairLine> all = new ArrayList<>();
            for (int sh = 0; sh < shards(); sh++) {
                Path f = shardFile(sh);
                int first = sh * shardSize;
                int count = Math.min(items, first + shardSize) - first;
                if (!Files.exists(f) || check(f, sh, first, count) != ShardState.COMPLETE) {
                    return null;
                }
                List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
                for (int i = 1; i < lines.size(); i++) {
                    all.add(PairLine.of(RunMonitor.parse(lines.get(i))));
                }
            }
            return all;
        }
    }

    /** The opening position and seeds of pair {@code pair} of a match, and the players: one worker per thread. */
    static Supplier<Job.Worker<PairLine>> pairWorkers(long seed, int openingPlies, LongFunction<Player> subject,
            LongFunction<Player> opponent) {
        RuleSet rules = RuleSet.defaults(2);
        return () -> pair -> MctsMatch.playPair(StrengthEstimate.opening(rules, seed, pair, openingPlies), pair, subject,
                opponent, (seed * 1_000_003L + pair) * 2);
    }
}
