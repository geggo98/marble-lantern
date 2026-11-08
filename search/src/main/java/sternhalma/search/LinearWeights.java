package sternhalma.search;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Fitted weights of {@link LinearEvaluator}, in logit units per feature unit, in the order of
 * {@link LinearEvaluator#NAMES}.
 *
 * <p>How they were produced (reproduce with {@code LinearTraining} in the test sources):
 * <pre>
 *   LinearTraining generate d1.csv 2000 101 4 'ab:nodes=20000|ab:nodes=20000' 'ab:nodes=5000|ab:nodes=20000' \
 *       'ab:nodes=20000|ab:eval=rows,nodes=20000' 'ab:nodes=5000|ab:nodes=5000'
 *   LinearTraining fit w.txt all d1.csv
 * </pre>
 * 2000 baseline games, 68255 positions (every third ply from ply 6), labelled with the final result for
 * the side to move; logistic regression, games with id % 5 == 0 held out. Held-out accuracy of predicting
 * the winner: 0.788 (distance difference alone: 0.768); held-out log loss 0.437 (0.482).
 *
 * <p>A second round, refitted on these games plus 1500 games played by this evaluator, predicted better
 * in its own early game but played weaker in the arena (seed 4: +440 against +526 Elo over the
 * baseline), so the first-round weights are kept.
 */
final class LinearWeights {

    /** Score units per logit unit; about 36 units per step of distance, near the move-limit score. */
    static final double SCALE = 100.0;

    /**
     * The live weights of {@code eval=linear} and of {@link Strategies#BEST_SPEC}. A refit replaces these values;
     * {@link #R1} keeps the round-1 values.
     */
    static final double[] WEIGHTS = {
        -0.361185,   // dsum
        -0.353700,   // dmax
        -0.120344,   // dsq
        0.257194,    // inGoal
        0.0330329,   // inStart
        0.0581328,   // spread
        -0.0303039,  // lateral
        0.119662,    // jumps
        -0.00720263, // adjacent
        0.0532558,   // steps
        0.270701,    // dsumPhase
        0.229450,    // tempo
    };

    /**
     * The round-1 weights, frozen on 2026-11-07 as a literal copy of {@link #WEIGHTS} at commit cb4b750 (the
     * last commit before round 2). They back the registry name {@code linear-r1}, the evaluator of the
     * permanent reference player R2 ({@link ReferencePositions#R2_SPEC}). Never change these values: R2 must
     * play the same tree in every later build, which {@code BestSpecFingerprintTest} checks.
     */
    static final double[] R1 = {
        -0.361185,   // dsum
        -0.353700,   // dmax
        -0.120344,   // dsq
        0.257194,    // inGoal
        0.0330329,   // inStart
        0.0581328,   // spread
        -0.0303039,  // lateral
        0.119662,    // jumps
        -0.00720263, // adjacent
        0.0532558,   // steps
        0.270701,    // dsumPhase
        0.229450,    // tempo
    };

    /**
     * The round-2 refit (experiment E04 of docs/baseline-round2-plan.md), registry name {@code linear-r2}; not to be
     * confused with the reference player R2, which plays {@code linear-r1}. Frozen: never change these values, add
     * a new set instead. {@code eval=linear} and {@link #R1} are unchanged by E04; only an integration commit may
     * make {@link Strategies#BEST_SPEC} use these weights.
     *
     * <p>Texel loop, two rounds, each a logistic fit (as {@code LinearTraining fit}) on moves-only self-play games
     * replayed by {@code LinearTraining fit-games}: every position from ply 6, passed positions dropped
     * ({@code LinearTraining.passed}: every piece of player 0 on a higher row than every piece of player 1), games
     * with id % 5 == 0 held out. Round 1: 2000 games of BEST_SPEC (eval=linear = {@link #R1}) at 20000 nodes from
     * the arena openings of seed 1003; held-out log loss 0.5565 (R1) to 0.5380, accuracy 0.6968 to 0.7101 on 28007
     * positions of 400 games. Round 2 (these weights): 2000 games of the same search with the round-1 weights,
     * same openings; held-out log loss 0.5232 (R1) to 0.5176, accuracy 0.7226 to 0.7278 on 30136 positions.
     * Commands and arena results: docs/search-and-evaluation.md, "Refit of the linear weights (E04)".
     */
    static final double[] ROUND2 = {
        -0.264514,   // dsum
        -0.385392,   // dmax
        -0.167810,   // dsq
        0.230892,    // inGoal
        0.110844,    // inStart
        0.119476,    // spread
        -0.109568,   // lateral
        0.0946884,   // jumps
        -0.0295270,  // adjacent
        0.0637964,   // steps
        0.223242,    // dsumPhase
        0.282466,    // tempo
    };

    /**
     * Round-2 experiment E07, registry name {@code linear-r3}: the 12 features plus {@code bestGain} in mode
     * {@link LinearEvaluator.GainMode#BOTH}, in the order of {@link LinearEvaluator#GAIN_NAMES}. Frozen: never
     * change these values, add a new set instead.
     *
     * <p>One logistic fit ({@code LinearTraining fit-games all --gain both --s1-labels}) on 4000 moves-only
     * self-play games of the wave-1 BEST candidate {@code ab:eval=linear-r2,<all options>,fut=1,futk=30,fut2=1,
     * race=1} at 20000 nodes from the arena openings of seed 1008: every position from ply 6, passed positions
     * dropped, labels from the first S1 verdict where one fires (3993 games; it changed no label), games with
     * id % 5 == 0 held out. Held-out log loss on 62957 positions of 800 games: linear-r2 0.5478, the 12-feature
     * refit on the same samples 0.5471, these weights 0.5423 (accuracy 0.7084, 0.7073, 0.7126). A second texel
     * round on 4000 games of these weights was not better in the arena (-6 and +32 Elo on seeds 31 and 32) and is
     * not used. Commands and arena results: docs/search-and-evaluation.md, "bestGain (E07)".
     */
    static final double[] ROUND3 = {
        -0.259472,   // dsum
        -0.386404,   // dmax
        -0.234400,   // dsq
        0.285310,    // inGoal
        0.142903,    // inStart
        0.0773203,   // spread
        -0.116410,   // lateral
        0.0949370,   // jumps
        -0.0580585,  // adjacent
        0.0452636,   // steps
        0.261648,    // dsumPhase
        0.327666,    // tempo
        0.156608,    // bestGain
    };

    private LinearWeights() {
    }

    /**
     * Reads an evaluator from a text file of white-space separated tokens; lines starting with '#' are comments.
     * For tuning experiments only. Two formats:
     * <ul>
     *   <li>versioned (E07): the feature count first, then for {@link LinearEvaluator#GAIN_FEATURES} the
     *       {@code bestGain} mode ({@code both} or {@code mover}), then the scale and exactly that many weights:
     *       {@code 12 <scale> <12 weights>} or {@code 13 both <scale> <13 weights>};</li>
     *   <li>unversioned (before E07): the scale and the 12 weights, 13 numbers in all.</li>
     * </ul>
     * The two cannot be confused: a versioned 12-feature file has 14 tokens, an unversioned one 13, and a
     * 13-feature file has a word as its second token. Anything else is rejected.
     */
    static LinearEvaluator fromFile(String file) {
        try {
            String[] tokens = Files.readAllLines(Path.of(file)).stream()
                    .filter(line -> !line.isBlank() && !line.startsWith("#"))
                    .flatMap(line -> Arrays.stream(line.trim().split("\\s+")))
                    .toArray(String[]::new);
            return parse(tokens, file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static LinearEvaluator parse(String[] tokens, String source) {
        int f = LinearEvaluator.FEATURES;
        int g = LinearEvaluator.GAIN_FEATURES;
        if (tokens.length == 1 + f) {
            return new LinearEvaluator(numbers(tokens, 1, source), Double.parseDouble(tokens[0]));
        }
        if (tokens.length == 2 + f && tokens[0].equals(Integer.toString(f))) {
            return new LinearEvaluator(numbers(tokens, 2, source), Double.parseDouble(tokens[1]), null);
        }
        if (tokens.length == 3 + g && tokens[0].equals(Integer.toString(g))) {
            LinearEvaluator.GainMode mode = switch (tokens[1]) {
                case "both" -> LinearEvaluator.GainMode.BOTH;
                case "mover" -> LinearEvaluator.GainMode.MOVER;
                default -> throw new IllegalArgumentException(source + ": unknown bestGain mode '" + tokens[1]
                        + "', expected both or mover");
            };
            return new LinearEvaluator(numbers(tokens, 3, source), Double.parseDouble(tokens[2]), mode);
        }
        throw new IllegalArgumentException(source + ": " + tokens.length + " tokens; expected '<scale> <12 weights>', '"
                + f + " <scale> <12 weights>' or '" + g + " both|mover <scale> <13 weights>'");
    }

    private static double[] numbers(String[] tokens, int from, String source) {
        double[] w = new double[tokens.length - from];
        for (int i = 0; i < w.length; i++) {
            try {
                w[i] = Double.parseDouble(tokens[from + i]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(source + ": weight " + i + " is not a number: " + tokens[from + i], e);
            }
        }
        return w;
    }

    /** The versioned file text of a weight set: header comment lines, then the tokens of {@link #parse}. */
    static String toText(String comment, double scale, double[] weights, LinearEvaluator.GainMode mode) {
        StringBuilder sb = new StringBuilder();
        for (String line : comment.split("\n")) {
            sb.append("# ").append(line).append('\n');
        }
        String[] names = mode == null ? LinearEvaluator.NAMES : LinearEvaluator.GAIN_NAMES;
        sb.append("# order: count");
        if (mode != null) {
            sb.append(", mode");
        }
        sb.append(", scale, ").append(String.join(" ", names)).append('\n');
        sb.append(weights.length);
        if (mode != null) {
            sb.append(' ').append(mode.name().toLowerCase(java.util.Locale.ROOT));
        }
        sb.append('\n').append(scale).append('\n');
        for (double w : weights) {
            sb.append(String.format(java.util.Locale.ROOT, "%.6g%n", w));
        }
        return sb.toString();
    }
}
