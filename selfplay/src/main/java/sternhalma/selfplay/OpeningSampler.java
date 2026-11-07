package sternhalma.selfplay;

import java.util.Random;

import sternhalma.engine.Geometry;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.search.AlphaBeta;
import sternhalma.search.Evaluator;

/**
 * Cheap opening diversity for a deterministic engine (round-2 plan, E05): k plies, k uniform in [minPlies,
 * maxPlies], each either uniformly random or drawn from a softmax over the scores of all root moves at temperature T
 * (score units of the evaluator; 100 units are one logit of the linear evaluation's win probability). The score of a
 * root move is the evaluation after it ({@code depth = 1}) or minus a depth-1 search from the child ({@code depth =
 * 2}, every reply evaluated).
 *
 * <p>Game {@code id} of seed {@code s} draws from {@code new Random(s * 1_000_003 + id)}, so a game's opening is
 * reproducible from (seed, id) alone. Not thread-safe (the depth-2 mode keeps a searcher).
 */
public final class OpeningSampler {

    public enum Mode { UNIFORM, SOFTMAX }

    private final Mode mode;
    private final int minPlies;
    private final int maxPlies;
    private final double temperature;
    private final int depth;
    private final Evaluator evaluator;
    private final AlphaBeta searcher;

    public OpeningSampler(Mode mode, int minPlies, int maxPlies, double temperature, int depth, Evaluator evaluator) {
        if (minPlies < 0 || maxPlies < minPlies) {
            throw new IllegalArgumentException("need 0 <= minPlies <= maxPlies");
        }
        if (mode == Mode.SOFTMAX && (temperature <= 0 || evaluator == null || depth < 1 || depth > 2)) {
            throw new IllegalArgumentException("softmax openings need a temperature > 0, an evaluator and depth 1 or 2");
        }
        this.mode = mode;
        this.minPlies = minPlies;
        this.maxPlies = maxPlies;
        this.temperature = temperature;
        this.depth = depth;
        this.evaluator = evaluator;
        this.searcher = mode == Mode.SOFTMAX && depth == 2 ? new AlphaBeta(evaluator, 1, false) : null;
    }

    public String describe() {
        return mode == Mode.UNIFORM
                ? "uniform," + minPlies + "-" + maxPlies
                : "softmax," + minPlies + "-" + maxPlies + ",T=" + temperature + ",depth=" + depth;
    }

    /** The opening moves of game {@code id}; the game ends never inside the opening (a terminal ply stops it). */
    public int[] sample(RuleSet rules, long seed, int id) {
        Random random = new Random(seed * 1_000_003L + id);
        int k = minPlies + random.nextInt(maxPlies - minPlies + 1);
        Position pos = Position.initial(rules);
        int[] out = new int[k];
        int n = 0;
        while (n < k && pos.outcome() == null) {
            int[] legal = pos.legalMoves();
            if (legal.length == 0) {
                break;
            }
            int m = mode == Mode.UNIFORM ? legal[random.nextInt(legal.length)] : softmax(pos, legal, random);
            pos.make(m);
            out[n++] = m;
        }
        return n == k ? out : java.util.Arrays.copyOf(out, n);
    }

    private int softmax(Position pos, int[] legal, Random random) {
        double[] score = new double[legal.length];
        double max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < legal.length; i++) {
            pos.make(legal[i]);
            int s;
            if (pos.outcome() != null) {
                s = pos.outcome().hasWinner() && pos.outcome().winner() != pos.sideToMove() ? AlphaBeta.MATE : 0;
            } else if (depth == 1) {
                s = -evaluator.evaluate(pos);
            } else {
                s = -searcher.search(pos, 1).score();
            }
            pos.unmake();
            score[i] = s;
            max = Math.max(max, s);
        }
        double sum = 0;
        for (int i = 0; i < score.length; i++) {
            score[i] = Math.exp((score[i] - max) / temperature);
            sum += score[i];
        }
        double u = random.nextDouble() * sum;
        for (int i = 0; i < score.length; i++) {
            u -= score[i];
            if (u < 0) {
                return legal[i];
            }
        }
        return legal[legal.length - 1];
    }

    /**
     * A key of the position up to the left-right mirror ({@link Geometry#mirror}), for diversity statistics: the
     * smaller of the Zobrist hashes of the position and of its mirror image.
     */
    public static long canonicalKey(Position pos) {
        long h = pos.hash();
        long m = pos.transformed(Geometry::mirror, false).hash();
        return Math.min(h, m);
    }
}
