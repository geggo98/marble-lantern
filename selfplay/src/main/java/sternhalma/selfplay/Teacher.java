package sternhalma.selfplay;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Locale;

import sternhalma.engine.Position;
import sternhalma.search.AlphaBeta;
import sternhalma.search.Evaluators;
import sternhalma.search.PlayerSpec;
import sternhalma.search.Scores;
import sternhalma.search.SearchOptions;

/**
 * The classical teacher of generation 0 (round-2 plan E10; docs/training.md, "Teacher samples"). For a position it
 * computes the exact scores of all root moves at a fixed depth ({@link AlphaBeta#rootScores}: futility, late move
 * reductions and quiescence off, full window at the root, no aspiration) and turns them into
 *
 * <ul>
 *   <li>the <b>policy target</b> {@code softmax(tau * score)} over the legal moves, and</li>
 *   <li>the <b>value target</b> {@code z' = (1 - alpha(ply)) z + alpha(ply) (2 sigma(score / 75) - 1)} with the
 *       best root score as the teacher's judgement of the mover's position.</li>
 * </ul>
 *
 * <p>Band rule (E08): only {@link Scores.Kind#ORDINARY} and {@link Scores.Kind#MATE} scores are used. If the best
 * root score is in the race band, the race arithmetic behind it comes from region tables that are upper bounds and
 * ignore blockades; such a position gets no teacher value ({@code alpha = 0}: the game result or S1 label alone) and
 * no teacher policy (weight 0). A passed position ({@link sternhalma.search.RaceAdjudication#passed}) takes its value only from S1 or
 * the game result as well, whatever the search score says.
 *
 * <p>The mover of a game is chosen by the normal search; this class only labels. Not thread-safe; one instance per
 * thread (it owns a searcher).
 */
public final class Teacher {

    /** Scale of the sigmoid that turns a score into a win probability: {@code sigma(score / 75)}. */
    public static final double SCORE_SCALE = 75;

    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();

    /**
     * @param depth       plies of the root-score search
     * @param tau         inverse temperature of the policy softmax, per score unit
     * @param alphaStart  weight of the teacher's value at ply 0
     * @param alphaEnd    weight of the teacher's value from ply {@code alphaPlies} on; between them linear. The game
     *                    result is a noisy label early (random openings, 100 plies to go) and the teacher's search
     *                    score is the better early signal; late the result is nearly certain and exact
     * @param alphaPlies  plies of the ramp
     */
    public record Config(int depth, double tau, double alphaStart, double alphaEnd, int alphaPlies) {

        /** Defaults of the plan: {@code alpha} from 0.7 at ply 0 to 0.3 at ply 100. */
        public static final double ALPHA_START = 0.7;
        public static final double ALPHA_END = 0.3;
        public static final int ALPHA_PLIES = 100;

        public Config {
            if (depth < 1 || depth > 6) {
                throw new IllegalArgumentException("teacher depth must be in 1..6, got " + depth);
            }
            if (!(tau > 0)) {
                throw new IllegalArgumentException("teacher tau must be positive, got " + tau);
            }
            if (!(alphaStart >= 0 && alphaStart <= 1 && alphaEnd >= 0 && alphaEnd <= 1) || alphaPlies < 1) {
                throw new IllegalArgumentException("alpha must be in [0, 1] and the ramp at least 1 ply");
            }
        }

        public Config(int depth, double tau) {
            this(depth, tau, ALPHA_START, ALPHA_END, ALPHA_PLIES);
        }

        /** The temperature measured for a depth: 0.1 at depth 3, 0.06 at depth 2 (E10 plan). */
        public static double defaultTau(int depth) {
            return switch (depth) {
                case 3 -> 0.1;
                case 2 -> 0.06;
                default -> throw new IllegalArgumentException("no default tau for depth " + depth + "; give --teacher-tau");
            };
        }

        /** The weight of the teacher's value at a ply. */
        public double alpha(int ply) {
            double x = Math.min(1.0, (double) ply / alphaPlies);
            return alphaStart + (alphaEnd - alphaStart) * x;
        }

        /**
         * The value target for both players, relative players (mover first): the blend of the labels {@code z} with
         * the teacher's judgement of the mover, or just {@code z} where the teacher's score may not be used.
         *
         * @param z      the label, mover first
         * @param score  the best root score
         * @param passed whether the position is passed (only S1 and the result label those)
         */
        public double[] value(double[] z, int ply, int score, boolean passed) {
            double a = passed || !usesScore(score) ? 0 : alpha(ply);
            double t = 2 / (1 + Math.exp(-score / SCORE_SCALE)) - 1;
            double[] v = new double[z.length];
            for (int r = 0; r < z.length; r++) {
                double teacher = r == 0 ? t : -t;
                v[r] = a == 0 ? z[r] : (1 - a) * z[r] + a * teacher;
            }
            return v;
        }

        public String describe() {
            return String.format(Locale.ROOT, "depth=%d,tau=%s,alpha=%s>%s@%d,sigma=%s", depth, tau, alphaStart,
                    alphaEnd, alphaPlies, SCORE_SCALE);
        }
    }

    /**
     * What the teacher says about one position.
     *
     * @param policy       target over the legal moves in {@code Position.legalMoves()} order (sums to 1), or null
     *                     where the best score is in the race band
     * @param best         the best root score
     * @param scores       the root scores in {@code Position.legalMoves()} order
     */
    public record Label(float[] policy, int best, int[] scores) {
    }

    private final Config config;
    private final PlayerSpec spec;
    private final AlphaBeta search;
    private long cpuNanos;
    private long nodes;
    private long samples;

    public Teacher(Config config, PlayerSpec spec) {
        this.config = config;
        this.spec = spec;
        this.search = new AlphaBeta(Evaluators.byName(spec.evaluator(), spec.weights()), 18, true,
                spec.options().exact());
    }

    public Config config() {
        return config;
    }

    /** The options of the root-score searcher: those of the spec with futility and late move reductions off. */
    public SearchOptions options() {
        return search.options();
    }

    /** The search configuration as text, for the file metadata. */
    public String searchDescription() {
        return searchDescription(spec.text(), config);
    }

    /** The search configuration of the teacher for a player spec: its options with the pruning off. */
    static String searchDescription(String specText, Config config) {
        PlayerSpec spec = PlayerSpec.parse(specText);
        String o = spec.options().exact().describe();
        return "ab:eval=" + spec.evaluator() + (o.isEmpty() ? "" : "," + o) + ",fut=0,lmr=0,qs=0,asp=0,depth="
                + config.depth() + ",window=full";
    }

    /** Whether a search score may feed a value or policy target: ORDINARY and MATE, never RACE. */
    public static boolean usesScore(int score) {
        return Scores.kind(score) != Scores.Kind.RACE;
    }

    /** {@code softmax(tau * score)}, stable for mate scores. */
    public static double[] softmax(int[] scores, double tau) {
        double max = Double.NEGATIVE_INFINITY;
        for (int s : scores) {
            max = Math.max(max, tau * s);
        }
        double[] p = new double[scores.length];
        double sum = 0;
        for (int i = 0; i < p.length; i++) {
            p[i] = Math.exp(tau * scores[i] - max);
            sum += p[i];
        }
        for (int i = 0; i < p.length; i++) {
            p[i] /= sum;
        }
        return p;
    }

    /** Labels one position; the root scores come from the exact search. */
    public Label label(Position pos) {
        long t0 = THREADS.getCurrentThreadCpuTime();
        int[] scores = search.rootScores(pos, config.depth());
        nodes += search.nodes();
        samples++;
        int best = Integer.MIN_VALUE;
        for (int s : scores) {
            best = Math.max(best, s);
        }
        float[] policy = null;
        if (usesScore(best)) {
            double[] p = softmax(scores, config.tau());
            policy = new float[p.length];
            for (int i = 0; i < p.length; i++) {
                policy[i] = (float) p[i];
            }
        }
        cpuNanos += THREADS.getCurrentThreadCpuTime() - t0;
        return new Label(policy, best, scores);
    }

    /** Thread CPU time spent in {@link #label}. */
    public long cpuNanos() {
        return cpuNanos;
    }

    /** Search nodes of all {@link #label} calls. */
    public long nodes() {
        return nodes;
    }

    /** Positions labelled so far. */
    public long samples() {
        return samples;
    }
}
