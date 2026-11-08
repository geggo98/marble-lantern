package sternhalma.selfplay;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.SplittableRandom;

import sternhalma.engine.Geometry;
import sternhalma.engine.Outcome;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.nn.Orientation;
import sternhalma.search.AlphaBetaPlayer;
import sternhalma.search.Evaluators;
import sternhalma.search.IllegalMoveException;
import sternhalma.search.PlayerSpec;
import sternhalma.search.RaceAdjudication;
import sternhalma.search.SearchResult;
import sternhalma.selfplay.Adjudication.Firing;
import sternhalma.selfplay.Adjudication.Layer;
import sternhalma.tablebase.RaceTable;

/**
 * Plays self-play games of one spec with adjudication and builds their samples. One instance per thread: it owns
 * the two players (reused between games after {@link AlphaBetaPlayer#clearSearch()}, unless {@code reuse} is off),
 * the S1 scratch space and the opening sampler.
 */
public final class SelfPlayGame {

    /**
     * The settings of a run; {@link #describe()} is stored in every shard and must match on resume.
     *
     * @param spec            the effective player spec (both sides)
     * @param seed            seed of the openings and of the control draw
     * @param adjudication    the layers
     * @param controlFraction share of adjudicated games played on to the end (1 = all)
     * @param openingMode     uniform or softmax
     * @param epsilon         policy target: 1 - epsilon on the played move, epsilon spread over all legal moves
     * @param samples         whether to build samples
     * @param teacher         teacher samples of generation 0 (E10): policy and value targets from exact root scores,
     *                        or null for the bootstrap targets
     */
    public record Settings(String spec, long seed, Adjudication.Config adjudication, double controlFraction,
            OpeningSampler.Mode openingMode, int openingMin, int openingMax, double openingTemperature,
            int openingDepth, double epsilon, boolean samples, boolean reuse, Teacher.Config teacher) {

        public Settings(String spec, long seed, Adjudication.Config adjudication, double controlFraction,
                OpeningSampler.Mode openingMode, int openingMin, int openingMax, double openingTemperature,
                int openingDepth, double epsilon, boolean samples, boolean reuse) {
            this(spec, seed, adjudication, controlFraction, openingMode, openingMin, openingMax, openingTemperature,
                    openingDepth, epsilon, samples, reuse, null);
        }

        public String describe() {
            return "spec=" + spec + ";seed=" + seed + ";adjudication=" + adjudication.describe() + ";control="
                    + controlFraction + ";opening=" + openingMode.name().toLowerCase(java.util.Locale.ROOT) + ","
                    + openingMin + "-" + openingMax + ",T=" + openingTemperature + ",depth=" + openingDepth
                    + ";epsilon=" + epsilon + ";samples=" + (samples ? 1 : 0)
                    + (teacher == null ? "" : ";teacher=" + teacher.describe());
        }
    }

    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();

    private final Settings settings;
    private final RuleSet rules = RuleSet.defaults(2);
    private final PlayerSpec spec;
    private final Adjudication adjudication;
    private final OpeningSampler openings;
    private final Teacher teacher;
    private AlphaBetaPlayer[] players;

    public SelfPlayGame(Settings settings) {
        this.settings = settings;
        this.spec = PlayerSpec.parse(settings.spec());
        this.teacher = settings.teacher() == null ? null : new Teacher(settings.teacher(), spec);
        Adjudication.Config a = settings.adjudication();
        this.adjudication = new Adjudication(a, rules.players(), a.s1() ? RaceTable.rows2() : null,
                a.evalThreshold() > 0 ? Evaluators.byName(spec.evaluator(), spec.weights()) : null);
        this.openings = new OpeningSampler(settings.openingMode(), settings.openingMin(), settings.openingMax(),
                settings.openingTemperature(), settings.openingDepth(),
                settings.openingMode() == OpeningSampler.Mode.SOFTMAX
                        ? Evaluators.byName(spec.evaluator(), spec.weights()) : null);
    }

    /** The teacher of this instance, or null without teacher samples. */
    public Teacher teacher() {
        return teacher;
    }

    /** Whether game {@code id} is a control game (played on after the adjudication). */
    public static boolean isControl(long seed, int id, double fraction) {
        if (fraction >= 1) {
            return true;
        }
        if (fraction <= 0) {
            return false;
        }
        return new SplittableRandom(seed * 0x9E3779B97F4A7C15L + id).nextDouble() < fraction;
    }

    private static long cpu() {
        return THREADS.getCurrentThreadCpuTime();
    }

    private AlphaBetaPlayer[] players() {
        if (!settings.reuse() || players == null) {
            players = new AlphaBetaPlayer[] {spec.create(), spec.create()};
        } else {
            players[0].clearSearch();
            players[1].clearSearch();
        }
        return players;
    }

    /** Plays game {@code id}. */
    public GameRecord play(int id) {
        long c0 = cpu();
        boolean control = isControl(settings.seed(), id, settings.controlFraction());
        int[] opening = openings.sample(rules, settings.seed(), id);
        Position pos = Position.initial(rules);
        int[] moves = new int[rules.moveLimit() + 1];
        int[] scores = new int[rules.moveLimit() + 1];
        int n = 0;
        for (int m : opening) {
            pos.make(m);
            scores[n] = GameRecord.NO_SCORE;
            moves[n++] = m;
        }
        AlphaBetaPlayer[] ps = players();
        long nodes0 = ps[0].totalNodes() + ps[1].totalNodes();
        Adjudication.Tracker tracker = adjudication.tracker();
        Firing cut = null;
        long searchCpu = 0;
        long s1Cpu = 0;
        Outcome outcome;
        while (true) {
            outcome = pos.outcome();
            if (outcome != null) {
                break;
            }
            int[] legal = pos.legalMoves();
            if (legal.length == 0) {
                outcome = pos.noMovesOutcome();
                break;
            }
            long a0 = cpu();
            Firing f = tracker.beforeMove(pos);
            s1Cpu += cpu() - a0;
            if (cut == null && f != null) {
                cut = f;
            }
            if (cut != null && !control) {
                break;
            }
            int mover = pos.sideToMove();
            long t0 = cpu();
            int move = ps[mover].choose(pos);
            searchCpu += cpu() - t0;
            if (Arrays.stream(legal).noneMatch(x -> x == move)) {
                throw new IllegalMoveException(ps[mover].name(), move, pos);
            }
            SearchResult result = ps[mover].lastResult();
            scores[n] = result == null ? GameRecord.NO_SCORE : result.score();
            moves[n++] = move;
            pos.make(move);
            if (pos.outcome() == null) {
                Firing g = tracker.afterMove(pos, mover, result);
                if (cut == null && g != null) {
                    cut = g;
                }
            }
        }
        boolean playedOut = outcome != null;
        int[] played = Arrays.copyOf(moves, n);
        Layer label = cut == null ? Layer.NONE : cut.layer();
        int labelPly = cut == null ? pos.ply() : cut.ply();
        double[] labelValues = cut == null ? outcome.values().clone() : win(cut.winner());
        long nodes = ps[0].totalNodes() + ps[1].totalNodes() - nodes0;
        GameRecord.Samples samples = settings.samples()
                ? samples(played, opening.length, labelPly, labelValues, label != Layer.NONE, settings.epsilon(), teacher)
                : null;
        return new GameRecord(id, opening.length, played, Arrays.copyOf(scores, n), label, labelPly, labelValues,
                playedOut, playedOut ? pos.ply() : -1, playedOut ? outcome.values().clone() : null,
                tracker.s1(), tracker.margin(), tracker.eval(), tracker.raceScoresIgnored(), cpu() - c0, searchCpu,
                s1Cpu, nodes, samples);
    }

    static double[] win(int winner) {
        double[] v = {-1, -1};
        v[winner] = 1;
        return v;
    }

    /**
     * The samples of the positions before ply {@code cut}, replayed from the start position. With a teacher the policy
     * target is its softmax over the exact root scores (flag bit 0; weight 0 for the random opening plies, as their
     * played move carries no information about the teacher, and for positions whose best score is in the race band,
     * which get the bootstrap target and no bit 0) and the value target the blend of the label and the teacher.
     */
    static GameRecord.Samples samples(int[] moves, int openingPlies, int cut, double[] labelValues,
            boolean adjudicated, double epsilon, Teacher teacher) {
        RuleSet rules = RuleSet.defaults(2);
        int players = rules.players();
        Position pos = Position.initial(rules);
        int n = cut;
        byte[] cells = new byte[n * Geometry.CELLS];
        short[] ply = new short[n];
        int[] counts = new int[n];
        int[] playedIndex = new int[n];
        float[] value = new float[n * players];
        float[] wp = new float[n];
        float[] wv = new float[n];
        byte[] flags = new byte[n];
        int total = 0;
        int[][] legalPerSample = new int[n][];
        float[][] teacherPolicy = new float[n][];
        for (int s = 0; s < n; s++) {
            Orientation o = Orientation.of(pos);
            for (int c = 0; c < Geometry.CELLS; c++) {
                int owner = pos.owner(c);
                cells[s * Geometry.CELLS + o.toCanonical(c)] = (byte) (owner < 0 ? 0 : 1 + o.relativePlayer(owner));
            }
            ply[s] = (short) pos.ply();
            int[] legal = pos.legalMoves();
            legalPerSample[s] = legal;
            counts[s] = legal.length;
            total += legal.length;
            playedIndex[s] = -1;
            for (int i = 0; i < legal.length; i++) {
                if (legal[i] == moves[s]) {
                    playedIndex[s] = i;
                }
            }
            if (playedIndex[s] < 0) {
                throw new IllegalStateException("move " + s + " is not legal on replay");
            }
            double[] z = new double[players];
            for (int r = 0; r < players; r++) {
                z[r] = labelValues[o.absolutePlayer(r)];
            }
            boolean random = s < openingPlies;
            boolean full = false;
            if (teacher != null) {
                Teacher.Label label = teacher.label(pos);
                z = teacher.config().value(z, pos.ply(), label.best(), RaceAdjudication.passed(pos));
                teacherPolicy[s] = label.policy();
                full = label.policy() != null;
            }
            for (int r = 0; r < players; r++) {
                value[s * players + r] = (float) z[r];
            }
            wp[s] = random || (teacher != null && !full) ? 0f : 1f;
            wv[s] = 1f;
            flags[s] = (byte) ((full ? 1 : 0) | (adjudicated ? 2 : 0) | (random ? 4 : 0));
            pos.make(moves[s]);
        }
        byte[] from = new byte[total];
        byte[] to = new byte[total];
        float[] target = new float[total];
        int k = 0;
        Position replay = Position.initial(rules);
        for (int s = 0; s < n; s++) {
            Orientation o = Orientation.of(replay);
            int[] legal = legalPerSample[s];
            for (int i = 0; i < legal.length; i++) {
                int cm = o.toCanonicalMove(legal[i]);
                from[k] = (byte) sternhalma.engine.Move.from(cm);
                to[k] = (byte) sternhalma.engine.Move.to(cm);
                target[k] = teacherPolicy[s] != null ? teacherPolicy[s][i]
                        : (float) (epsilon / legal.length + (i == playedIndex[s] ? 1 - epsilon : 0));
                k++;
            }
            replay.make(moves[s]);
        }
        return new GameRecord.Samples(cells, ply, counts, from, to, target, playedIndex, value, wp, wv, flags);
    }
}
