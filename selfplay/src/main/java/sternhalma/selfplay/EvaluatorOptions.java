package sternhalma.selfplay;

import java.util.Map;

/**
 * Which evaluator plays the self-play games of the loop (docs/training.md, "As implemented (block G1)"): {@code --evaluator
 * cpu|gpu}, {@code --games-in-flight N} and {@code --eval-cohorts K}. None of them is part of {@code run.json}: the two
 * evaluators play the same games up to the float differences of their forward passes, so a run may be resumed with the other
 * one (the shards record theirs in the metadata key {@code evaluator}, and the log says it for every generation).
 *
 * @param gpu           whether the games take their evaluations from the device ({@link LoopGamesJob.Device})
 * @param gamesInFlight games that run at the same time on the device, at least 1 (the batch of the device is about this
 *                      divided by the cohorts); ignored on the CPU, where {@code --threads} games run
 * @param cohorts       groups of games that the device alternates between, at least 1: with 2, the CPU prepares one while the
 *                      device computes the other
 */
record EvaluatorOptions(boolean gpu, int gamesInFlight, int cohorts) {

    static final int DEFAULT_GAMES_IN_FLIGHT = 256;
    static final int DEFAULT_COHORTS = 2;

    EvaluatorOptions {
        if (gamesInFlight < 1 || gamesInFlight > 4096 || cohorts < 1 || cohorts > 8) {
            throw new IllegalArgumentException("--games-in-flight must be 1 to 4096 and --eval-cohorts 1 to 8, got "
                    + gamesInFlight + ", " + cohorts);
        }
    }

    static EvaluatorOptions of(Map<String, String> o) {
        String evaluator = o.getOrDefault("evaluator", "cpu");
        if (!evaluator.equals("cpu") && !evaluator.equals("gpu")) {
            throw new IllegalArgumentException("--evaluator must be cpu or gpu, got " + evaluator);
        }
        return new EvaluatorOptions(evaluator.equals("gpu"),
                LoopConfig.integer(o, "games-in-flight", DEFAULT_GAMES_IN_FLIGHT),
                LoopConfig.integer(o, "eval-cohorts", DEFAULT_COHORTS));
    }
}
