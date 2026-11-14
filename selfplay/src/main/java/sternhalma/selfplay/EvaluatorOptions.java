package sternhalma.selfplay;

import java.util.Map;

/**
 * Which evaluator plays the self-play games and the matches of the loop (docs/training.md, "As implemented (block G1)" and
 * "(block G2)"): {@code --evaluator cpu|gpu}, {@code --games-in-flight N}, {@code --eval-cohorts K}, {@code --tail-games N},
 * {@code --tail-pairs N}, {@code --tail-threads T} and {@code --match-evaluator cpu|gpu|auto}. None of them is part of {@code run.json}: the two
 * evaluators play the same games up to the float differences of their forward passes, so a run may be resumed with the other
 * one (the shards record theirs in the metadata key {@code evaluator}, and the log says it for every generation).
 *
 * @param gpu           whether the games take their evaluations from the device ({@link LoopGamesJob.Device})
 * @param gamesInFlight games that run at the same time on the device, at least 1 (the batch of the device is about this
 *                      divided by the cohorts); ignored on the CPU, where {@code --threads} games run
 * @param cohorts       groups of games that the device alternates between, at least 1: with 2, the CPU prepares one while the
 *                      device computes the other
 * @param tailGames     the last this many games of a generation's self-play are played on the CPU path, from the start, in
 *                      {@code tailThreads} threads next to the games on the device (0: none; docs/training.md, "As implemented
 *                      (block G2)")
 * @param tailPairs     the same for the last pairs of the gate, baseline and strength matches
 * @param tailThreads   CPU threads of the tail, taken from {@code --threads}
 * @param matches       {@code --match-evaluator}: where the gate, baseline and strength matches play, {@code cpu}, {@code gpu} or
 *                      {@code auto} (as {@code --evaluator}); see {@link #matchesOnDevice()}
 */
record EvaluatorOptions(boolean gpu, int gamesInFlight, int cohorts, int tailGames, int tailPairs, int tailThreads,
        String matches) {

    static final int DEFAULT_GAMES_IN_FLIGHT = 256;
    static final int DEFAULT_COHORTS = 2;
    static final int DEFAULT_TAIL_GAMES = 0;
    static final int DEFAULT_TAIL_PAIRS = 0;
    static final int DEFAULT_TAIL_THREADS = 2;
    static final String DEFAULT_MATCH_EVALUATOR = "auto";

    EvaluatorOptions {
        if (gamesInFlight < 1 || gamesInFlight > 4096 || cohorts < 1 || cohorts > 8) {
            throw new IllegalArgumentException("--games-in-flight must be 1 to 4096 and --eval-cohorts 1 to 8, got "
                    + gamesInFlight + ", " + cohorts);
        }
        if (tailGames < 0 || tailPairs < 0 || tailThreads < 1 || tailThreads > 64) {
            throw new IllegalArgumentException("--tail-games and --tail-pairs must be >= 0 and --tail-threads 1 to 64, got "
                    + tailGames + ", " + tailPairs + ", " + tailThreads);
        }
        if (!matches.equals("cpu") && !matches.equals("gpu") && !matches.equals("auto")) {
            throw new IllegalArgumentException("--match-evaluator must be cpu, gpu or auto, got " + matches);
        }
    }

    /** Whether the matches of the loop play with the networks on the device: {@code gpu}, or {@code auto} with {@code --evaluator gpu}. */
    boolean matchesOnDevice() {
        return matches.equals("gpu") || matches.equals("auto") && gpu;
    }

    static EvaluatorOptions of(Map<String, String> o) {
        String evaluator = o.getOrDefault("evaluator", "cpu");
        if (!evaluator.equals("cpu") && !evaluator.equals("gpu")) {
            throw new IllegalArgumentException("--evaluator must be cpu or gpu, got " + evaluator);
        }
        return new EvaluatorOptions(evaluator.equals("gpu"),
                LoopConfig.integer(o, "games-in-flight", DEFAULT_GAMES_IN_FLIGHT),
                LoopConfig.integer(o, "eval-cohorts", DEFAULT_COHORTS),
                LoopConfig.integer(o, "tail-games", DEFAULT_TAIL_GAMES),
                LoopConfig.integer(o, "tail-pairs", DEFAULT_TAIL_PAIRS),
                LoopConfig.integer(o, "tail-threads", DEFAULT_TAIL_THREADS),
                o.getOrDefault("match-evaluator", DEFAULT_MATCH_EVALUATOR));
    }
}
