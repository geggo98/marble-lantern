package sternhalma.selfplay;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.Locale;
import java.util.SplittableRandom;

import sternhalma.engine.Outcome;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.nn.ForwardPass;
import sternhalma.search.MctsOptions;
import sternhalma.search.MctsPlayer;
import sternhalma.search.MctsResult;
import sternhalma.selfplay.Adjudication.Firing;
import sternhalma.selfplay.Adjudication.Layer;
import sternhalma.tablebase.RaceTable;

/**
 * Plays self-play games of the training loop: PUCT search with the best network on both sides, and builds their
 * samples (docs/training.md, "As implemented (block 2h)"). One instance per thread; it owns a forward pass of the
 * network, the S1 scratch space and the opening sampler.
 *
 * <p><b>The game.</b> Random opening plies as {@link OpeningSampler} draws them (uniform, no search, policy weight 0);
 * then every move is a search of {@code sims} simulations with Dirichlet noise at the root. During the first
 * {@code temperaturePlies} plies of the game the move is drawn with probability proportional to
 * {@code visits^(1/T)}, afterwards it is the most visited one. A passed position is played by the race module when it
 * offers a move (no search), exactly as in the gating and strength games, and S1 ({@link Adjudication}) ends the game
 * with its verdict before the first move of a passed position that it can judge. Layers 2 and 3 of the adjudication
 * are off: nothing a search score says labels a position.
 *
 * <p><b>The samples</b> of the positions before the end or the S1 cut:
 * <ul>
 *   <li>policy target: the root visit shares over the legal moves, in the order of {@code Position.legalMoves()};
 *       flag bit 0 (full search) set. A position without search gets the played move alone as target, weight 0:
 *       the random opening plies (flag bit 2) and the moves of the race module; a position with a single legal move
 *       is not searched either, but its target is exact and its gradient zero, so it keeps weight 1;</li>
 *   <li>value target: the game result, or the S1 verdict for an adjudicated game (flag bit 1), seen by the mover.
 *       Never a search value, never a race-band score (there are none in an MCTS game).</li>
 * </ul>
 *
 * <p><b>Determinism.</b> A game is a function of the settings, the network and the game id: the opening, the noise
 * and the move sampling are seeded from {@code (seed, id)}; no state is shared between games.
 */
public final class LoopGame {

    /**
     * The settings of the self-play of one generation; {@link #describe()} is stored in every shard and must match on
     * resume.
     *
     * @param seed           seeds openings, noise and move sampling (the loop derives it from its seed and the
     *                       generation)
     * @param sims           simulations per move
     * @param batch          leaves per forward pass
     * @param cpuct          PUCT exploration constant
     * @param noise          weight of the root Dirichlet noise
     * @param alpha          concentration of that noise
     * @param temperature    T of the move sampling in the first {@code temperaturePlies} plies
     * @param temperaturePlies plies of the game (counted from its start) with sampling
     * @param openingMin     minimum random opening plies
     * @param openingMax     maximum random opening plies
     * @param race           race play: 0 off, 1 or 2 as in {@code MctsOptions}
     * @param adjudication   the layers (production: S1 only)
     * @param rows           the board size of the games; {@code FIVE} has no S1 (the race table is for 10 pieces)
     */
    public record Settings(long seed, int sims, int batch, double cpuct, double noise, double alpha,
            double temperature, int temperaturePlies, int openingMin, int openingMax, int race,
            Adjudication.Config adjudication, RuleSet.TriangleRows rows) {

        public Settings {
            if (!(temperature > 0) || temperaturePlies < 0) {
                throw new IllegalArgumentException("temperature must be positive and temperaturePlies >= 0");
            }
            java.util.Objects.requireNonNull(rows, "rows");
            if (rows == RuleSet.TriangleRows.FIVE && adjudication.s1()) {
                throw new IllegalArgumentException("S1 needs the race table of 10 pieces: no S1 on 5 rows");
            }
            options(sims, batch, cpuct, noise, alpha, race); // validates the search options
        }

        /** The settings of 4-row games. */
        public Settings(long seed, int sims, int batch, double cpuct, double noise, double alpha, double temperature,
                int temperaturePlies, int openingMin, int openingMax, int race, Adjudication.Config adjudication) {
            this(seed, sims, batch, cpuct, noise, alpha, temperature, temperaturePlies, openingMin, openingMax, race,
                    adjudication, RuleSet.TriangleRows.FOUR);
        }

        /** The rules of the games: the two-player defaults of the board size. */
        public RuleSet rules() {
            return RuleSet.defaults(2, rows);
        }

        MctsOptions options() {
            return options(sims, batch, cpuct, noise, alpha, race);
        }

        private static MctsOptions options(int sims, int batch, double cpuct, double noise, double alpha, int race) {
            MctsOptions d = MctsOptions.defaults();
            // temp = 0: the search itself plays the most visited move; the sampling is done here, from the visits
            return new MctsOptions(sims, batch, cpuct, d.priorTemperature(), noise, alpha, 0, d.fpu(), race,
                    d.raceRows(), d.s1(), d.win1(), false, Long.MAX_VALUE);
        }

        public String describe() {
            return String.format(Locale.ROOT, "mcts-selfplay;seed=%d;sims=%d;batch=%d;cpuct=%s;noise=%s;alpha=%s;"
                    + "temp=%s;temp-plies=%d;opening=uniform,%d-%d;race=%d;adjudication=%s", seed, sims, batch, cpuct,
                    noise, alpha, temperature, temperaturePlies, openingMin, openingMax, race, adjudication.describe())
                    + (rows == RuleSet.TriangleRows.FOUR ? "" : ";rows=" + rows.rows());
        }
    }

    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();

    private final Settings settings;
    private final ForwardPass network;
    private final MctsOptions options;
    private final RuleSet rules;
    private final Adjudication adjudication;
    private final OpeningSampler openings;

    /** @param network the forward pass of this thread (not shared) */
    public LoopGame(Settings settings, ForwardPass network) {
        this.settings = settings;
        this.network = network;
        this.options = settings.options();
        this.rules = settings.rules();
        Adjudication.Config a = settings.adjudication();
        this.adjudication = new Adjudication(a, rules.players(), a.s1() ? RaceTable.rows2() : null, null);
        this.openings = new OpeningSampler(OpeningSampler.Mode.UNIFORM, settings.openingMin(), settings.openingMax(),
                30, 1, null);
    }

    private static long cpu() {
        return THREADS.getCurrentThreadCpuTime();
    }

    /** The seed of the search of game {@code id}. */
    static long searchSeed(long seed, int id) {
        return seed * 0x9E3779B97F4A7C15L + id * 0xBF58476D1CE4E5B9L + 1;
    }

    /** The seed of the move sampling of game {@code id}, independent of {@link #searchSeed}. */
    static long samplingSeed(long seed, int id) {
        return seed * 0x94D049BB133111EBL + id * 0x9E3779B97F4A7C15L + 2;
    }

    /**
     * The move drawn from the visits: probability proportional to {@code visits^(1/T)}; moves without visits are never
     * drawn. A tie-free draw from one uniform number {@code u} in [0, 1).
     */
    static int sample(int[] visits, double temperature, double u) {
        double[] w = new double[visits.length];
        double sum = 0;
        for (int i = 0; i < visits.length; i++) {
            w[i] = visits[i] > 0 ? (temperature == 1 ? visits[i] : Math.pow(visits[i], 1 / temperature)) : 0;
            sum += w[i];
        }
        double x = u * sum;
        int last = 0;
        for (int i = 0; i < w.length; i++) {
            if (w[i] > 0) {
                last = i;
                x -= w[i];
                if (x < 0) {
                    return i;
                }
            }
        }
        return last;
    }

    /** Plays game {@code id}. */
    public GameRecord play(int id) {
        return play(id, null);
    }

    /**
     * Plays game {@code id} from {@code start} without random opening plies (tests craft positions this way, for
     * example one with a single legal move); null: the start position of the game and the opening of the id.
     */
    GameRecord play(int id, Position start) {
        long c0 = cpu();
        int[] opening = start == null ? openings.sample(rules, settings.seed(), id) : new int[0];
        Position pos = start == null ? Position.initial(rules) : start.copy();
        int[] moves = new int[rules.moveLimit() + 1];
        int[] scores = new int[rules.moveLimit() + 1];
        float[][] policy = new float[rules.moveLimit() + 1][];
        boolean[] full = new boolean[rules.moveLimit() + 1];
        boolean[] weighted = new boolean[rules.moveLimit() + 1];
        int n = 0;
        for (int m : opening) {
            pos.make(m);
            scores[n] = GameRecord.NO_SCORE;
            moves[n++] = m;
        }
        MctsPlayer player = MctsPlayer.create(network, options, searchSeed(settings.seed(), id), "selfplay");
        SplittableRandom random = new SplittableRandom(samplingSeed(settings.seed(), id));
        Adjudication.Tracker tracker = adjudication.tracker();
        Firing cut = null;
        long searchCpu = 0;
        long s1Cpu = 0;
        long sims = 0;
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
            if (f != null) {
                cut = f;
                break;
            }
            long t0 = cpu();
            int move = player.choose(pos);
            searchCpu += cpu() - t0;
            MctsResult result = player.lastResult();
            if (result == null) {
                // the race module played it: no search, no policy target
                scores[n] = GameRecord.NO_SCORE;
            } else {
                sims += result.sims();
                scores[n] = (int) Math.round(result.rootValue() * 1000);
                if (result.sims() > 0) {
                    int[] visits = result.visits();
                    if (!Arrays.equals(result.moves(), legal)) {
                        throw new IllegalStateException("the search moves are not the legal moves in their order");
                    }
                    if (pos.ply() < settings.temperaturePlies()) {
                        move = result.moves()[sample(visits, settings.temperature(), random.nextDouble())];
                    }
                    float[] share = new float[visits.length];
                    double total = 0;
                    for (int v : visits) {
                        total += v;
                    }
                    for (int i = 0; i < share.length; i++) {
                        share[i] = (float) (visits[i] / total);
                    }
                    policy[n] = share;
                    full[n] = true;
                    weighted[n] = true;
                } else {
                    weighted[n] = true; // a single legal move: exact target, zero gradient
                }
            }
            moves[n++] = move;
            pos.make(move);
        }
        boolean playedOut = outcome != null;
        int[] played = Arrays.copyOf(moves, n);
        Layer label = cut == null ? Layer.NONE : cut.layer();
        int labelPly = cut == null ? pos.ply() : cut.ply();
        double[] labelValues = cut == null ? outcome.values().clone() : SelfPlayGame.win(cut.winner());
        SelfPlayGame.Labeler labeler = (s, p, z) -> new SelfPlayGame.PlyLabel(policy[s], z, full[s], weighted[s]);
        GameRecord.Samples samples = SelfPlayGame.samples(rules, start, played, opening.length, labelPly, labelValues,
                label != Layer.NONE, 0.0, labeler);
        return new GameRecord(id, opening.length, played, Arrays.copyOf(scores, n), label, labelPly, labelValues,
                playedOut, playedOut ? pos.ply() : -1, playedOut ? outcome.values().clone() : null, tracker.s1(),
                tracker.margin(), tracker.eval(), tracker.raceScoresIgnored(), cpu() - c0, searchCpu, s1Cpu, sims,
                samples);
    }
}
