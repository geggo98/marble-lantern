package sternhalma.search;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import sternhalma.engine.Geometry;
import sternhalma.engine.Outcome;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;

/**
 * Reproduces the fit of {@link LinearWeights}. Not part of the production path.
 *
 * <pre>
 *   # 1. training data: games between player specs, a sample every few plies, labelled with the result
 *   java -cp engine/build/classes/java/main:search/build/classes/java/main:search/build/classes/java/test \
 *       sternhalma.search.LinearTraining generate data.csv 2400 101 4 \
 *       'ab:nodes=20000|ab:nodes=20000' 'ab:nodes=5000|ab:nodes=20000' 'ab:nodes=20000|ab:eval=rows'
 *   # 2. logistic regression, games with id % 5 == 0 held out
 *   java -cp ... sternhalma.search.LinearTraining fit weights.txt all data.csv [more.csv ...]
 *
 *   # round 2 (E04): moves-only self-play game files, then a fit that replays them (see GameFiles)
 *   java -cp ... sternhalma.search.LinearTraining games data/e04/r1 2000 1003 8 '&lt;spec&gt;'
 *   java -cp ... sternhalma.search.LinearTraining fit-games w.txt all [--keep-passed] data/e04/r1
 *   java -cp ... sternhalma.search.LinearTraining score-games data/e04/r1   # linear-r1 vs linear-r2, held out
 *
 *   # E07: the 13th feature bestGain (mode both or mover), labels from the first S1 verdict where one fires;
 *   # also prints the 12-feature refit on the same samples; writes the versioned weight-file format
 *   java -cp ... sternhalma.search.LinearTraining fit-games w.txt all --gain both --s1-labels data/e07/r1
 * </pre>
 *
 * <p>Generation: game g plays matchup g % k (k matchups given as {@code specA|specB}); colours alternate
 * every k games; the opening is 4 to 10 random plies from a seed derived from (seed, g). From ply 6 on,
 * every third non-terminal position is written with its {@link LinearEvaluator#features} and the label
 * (1 + value) / 2 of the final outcome for the side to move (1 win, 0 loss, distance score at the move
 * limit). The output is written to a temporary file and renamed when complete.
 *
 * <p>Fit: logistic regression with soft labels (cross-entropy), Newton's method on standardised features
 * with a small L2 penalty, then mapped back to raw feature units. {@code mask} is {@code all} or a
 * comma-separated list of feature names; masked-out weights are 0. The weights file holds the scale
 * and then the weights, the format of {@link LinearWeights#fromFile}.
 */
public final class LinearTraining {

    private static final int F = LinearEvaluator.FEATURES;

    private LinearTraining() {
    }

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "generate" -> generate(Path.of(args[1]), Integer.parseInt(args[2]), Long.parseLong(args[3]),
                    Integer.parseInt(args[4]), Arrays.copyOfRange(args, 5, args.length));
            case "fit" -> fit(Path.of(args[1]), args[2], Arrays.copyOfRange(args, 3, args.length), 100.0);
            case "games" -> System.exit(GameFiles.generate(Path.of(args[1]), Integer.parseInt(args[2]),
                    Long.parseLong(args[3]), Integer.parseInt(args[4]), args[5]));
            case "fit-games" -> fitGames(Path.of(args[1]), args[2], Arrays.copyOfRange(args, 3, args.length));
            case "score-games" -> scoreGames(Arrays.copyOfRange(args, 1, args.length));
            default -> throw new IllegalArgumentException("usage: generate|fit|games|fit-games|score-games ...");
        }
    }

    // ---- moves-only game files (round 2, E04) ----

    /**
     * A passed position: every piece of player 0 stands on a higher row than every piece of player 1, so the
     * armies have passed each other and the game is two solitaire races (the definition of round-2 plan E03,
     * duplicated here so that E04 does not depend on the race module). Two players only; false otherwise.
     * Player 0 starts at r &lt; -4 and moves towards +r ({@link sternhalma.engine.Layout}).
     */
    static boolean passed(Position pos) {
        if (pos.rules().players() != 2) {
            return false;
        }
        int min0 = Integer.MAX_VALUE;
        int max1 = Integer.MIN_VALUE;
        for (int c = 0; c < Geometry.CELLS; c++) {
            int o = pos.owner(c);
            if (o == 0) {
                min0 = Math.min(min0, Geometry.r(c));
            } else if (o == 1) {
                max1 = Math.max(max1, Geometry.r(c));
            }
        }
        return min0 > max1;
    }

    /**
     * Samples of every position of the given games from ply {@code fromPly} on that is not terminal, has a legal
     * move and (with {@code dropPassed}) is not {@link #passed}; label (1 + value) / 2 of the recorded outcome for
     * the side to move. {@code counts}, if not null, receives {kept, passed dropped}.
     */
    static List<Sample> samples(List<GameFiles.Game> games, int fromPly, boolean dropPassed, long[] counts) {
        return samples(games, fromPly, dropPassed, counts, null, null);
    }

    /**
     * As {@link #samples(List, int, boolean, long[])}, with the {@code bestGain} feature in mode {@code gain}
     * ({@link LinearEvaluator#GAIN_FEATURES} values per sample; null: the 12 features) and, if {@code s1} is not
     * null, the label of a game taken from the first S1 verdict ({@link RaceAdjudication}) of the replay instead of
     * the played result: the verdict is the game value of the position (round-2 amendment to E03), and a won race
     * that the players failed to convert must not label the positions before it as lost. {@code counts}, if not
     * null and long enough, receives {kept, passed dropped, games with an S1 verdict, games whose label the
     * verdict changed}.
     */
    static List<Sample> samples(List<GameFiles.Game> games, int fromPly, boolean dropPassed, long[] counts,
            LinearEvaluator.GainMode gain, RaceAdjudication s1) {
        List<Sample> out = new ArrayList<>();
        long dropped = 0;
        long fired = 0;
        long changed = 0;
        for (GameFiles.Game g : games) {
            double value0 = g.value0();
            if (s1 != null) {
                Double verdict = s1Value0(g, s1);
                if (verdict != null) {
                    fired++;
                    if (verdict != value0) {
                        changed++;
                    }
                    value0 = verdict;
                }
            }
            Position pos = Position.initial(RuleSet.defaults(2));
            for (int i = 0; i <= g.moves().length; i++) {
                if (pos.outcome() != null || pos.legalMoves().length == 0) {
                    break;
                }
                if (pos.ply() >= fromPly) {
                    if (dropPassed && passed(pos)) {
                        dropped++;
                    } else {
                        double[] x;
                        if (gain == null) {
                            x = new double[F];
                            LinearEvaluator.features(pos, x);
                        } else {
                            x = new double[LinearEvaluator.GAIN_FEATURES];
                            LinearEvaluator.features(pos, x, gain);
                        }
                        double value = pos.sideToMove() == 0 ? value0 : -value0;
                        out.add(new Sample(g.id(), pos.ply(), (1 + value) / 2, x));
                    }
                }
                if (i < g.moves().length) {
                    pos.make(g.moves()[i]);
                }
            }
        }
        if (counts != null) {
            counts[0] = out.size();
            counts[1] = dropped;
            if (counts.length >= 4) {
                counts[2] = fired;
                counts[3] = changed;
            }
        }
        return out;
    }

    /** The value for player 0 (1 or -1) of the first S1 verdict in the replay of a game, or null if none fires. */
    static Double s1Value0(GameFiles.Game g, RaceAdjudication s1) {
        Position pos = Position.initial(RuleSet.defaults(2));
        for (int i = 0; i <= g.moves().length; i++) {
            if (pos.outcome() != null) {
                return null;
            }
            if (RaceAdjudication.passed(pos)) {
                RaceAdjudication.Verdict v = s1.adjudicate(pos);
                if (v != null) {
                    return v.winner() == 0 ? 1.0 : -1.0;
                }
            }
            if (i < g.moves().length) {
                pos.make(g.moves()[i]);
            }
        }
        return null;
    }

    /**
     * {@code fit-games <out> <mask> [--keep-passed] <game files...>}: replays moves-only game files, samples every
     * position from ply 6 (passed positions dropped unless {@code --keep-passed}), fits as {@link #fit} with game
     * id % 5 == 0 held out, and reports the frozen {@link LinearWeights#R1} weights on the same held-out set.
     */
    static void fitGames(Path out, String maskSpec, String[] args) throws IOException {
        List<String> argList = Arrays.asList(args);
        boolean dropPassed = !argList.contains("--keep-passed");
        boolean s1Labels = argList.contains("--s1-labels");
        LinearEvaluator.GainMode gain = null;
        List<String> fileList = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--gain")) {
                gain = LinearEvaluator.GainMode.valueOf(args[++i].toUpperCase(Locale.ROOT));
            } else if (!args[i].startsWith("--")) {
                fileList.add(args[i]);
            }
        }
        String[] files = fileList.toArray(String[]::new);
        String[] names = gain == null ? LinearEvaluator.NAMES : LinearEvaluator.GAIN_NAMES;
        List<GameFiles.Game> games = GameFiles.readAll(files);
        long[] counts = new long[4];
        RaceAdjudication s1 = s1Labels ? new RaceAdjudication(sternhalma.tablebase.RaceTable.rows2()) : null;
        List<Sample> all = samples(games, 6, dropPassed, counts, gain, s1);
        System.out.printf(Locale.ROOT, "games %d, samples %d, passed positions dropped %d (filter %s)%n",
                games.size(), counts[0], counts[1], dropPassed ? "on" : "off");
        if (s1Labels) {
            System.out.printf(Locale.ROOT, "S1 labels: %d games with a verdict, %d labels changed by it%n", counts[2],
                    counts[3]);
        }
        List<Sample> train = all.stream().filter(s -> !heldOut(s)).toList();
        List<Sample> test = all.stream().filter(LinearTraining::heldOut).toList();
        System.out.printf(Locale.ROOT, "samples: %d train, %d held out (%d games held out)%n", train.size(),
                test.size(), test.stream().mapToInt(Sample::game).distinct().count());
        double[] base = logistic(train, mask("dsum", names), 1e-6);
        double[] w = logistic(train, mask(maskSpec, names), 1e-6);
        report("distance only", train, test, base);
        report("frozen R1 (linear-r1)", train, test, LinearWeights.R1);
        report("frozen linear-r2", train, test, LinearWeights.ROUND2);
        if (gain != null) {
            // the same samples without bestGain: the 12-feature refit that the new feature must beat
            boolean[] without = mask(maskSpec, names);
            without[LinearEvaluator.BEST_GAIN] = false;
            report("12-feature refit, same data", train, test, logistic(train, without, 1e-6));
        }
        report("model " + maskSpec + (gain == null ? "" : ", bestGain " + gain), train, test, w);
        for (int i = 0; i < w.length; i++) {
            System.out.printf(Locale.ROOT, "  %-10s %+.6g%n", names[i], w[i]);
        }
        String comment = "LinearTraining fit-games, mask=" + maskSpec + ", passed filter " + (dropPassed ? "on" : "off")
                + ", S1 labels " + (s1Labels ? "on" : "off") + ", files=" + String.join(" ", files);
        Files.writeString(out, LinearWeights.toText(comment, 100.0, w, gain));
    }

    /**
     * {@code score-games <game files...>}: held-out log loss and accuracy (game id % 5 == 0, passed positions
     * dropped, from ply 6) of the frozen weight sets {@code linear-r1} and {@code linear-r2}.
     */
    static void scoreGames(String[] files) throws IOException {
        long[] counts = new long[2];
        List<Sample> test = samples(GameFiles.readAll(files), 6, true, counts).stream()
                .filter(LinearTraining::heldOut).toList();
        System.out.printf(Locale.ROOT, "held-out samples %d (passed dropped overall: %d)%n", test.size(), counts[1]);
        for (String[] named : new String[][] {{"linear-r1"}, {"linear-r2"}}) {
            double[] s = score(test, named[0].equals("linear-r1") ? LinearWeights.R1 : LinearWeights.ROUND2);
            System.out.printf(Locale.ROOT, "%-10s held-out logloss %.4f acc %.4f%n", named[0], s[0], s[1]);
        }
    }

    // ---- data generation ----

    record Sample(int game, int ply, double label, double[] x) {
    }

    static List<Sample> playAndSample(int game, long seed, PlayerSpec first, PlayerSpec second) {
        Random random = new Random(seed * 1_000_003L + game);
        Position pos = Position.initial(RuleSet.defaults(2));
        int openingPlies = 4 + random.nextInt(7);
        for (int i = 0; i < openingPlies; i++) {
            int[] moves = pos.legalMoves();
            pos.make(moves[random.nextInt(moves.length)]);
        }
        Player[] players = {first.create(), second.create()};
        List<double[]> xs = new ArrayList<>();
        List<Integer> sides = new ArrayList<>();
        List<Integer> plies = new ArrayList<>();
        Outcome outcome;
        while (true) {
            outcome = pos.outcome();
            if (outcome != null) {
                break;
            }
            if (pos.legalMoves().length == 0) {
                outcome = pos.noMovesOutcome();
                break;
            }
            if (pos.ply() >= 6 && pos.ply() % 3 == 0) {
                double[] x = new double[F];
                LinearEvaluator.features(pos, x);
                xs.add(x);
                sides.add(pos.sideToMove());
                plies.add(pos.ply());
            }
            pos.make(players[pos.sideToMove()].choose(pos));
        }
        List<Sample> result = new ArrayList<>();
        for (int i = 0; i < xs.size(); i++) {
            result.add(new Sample(game, plies.get(i), (1 + outcome.value(sides.get(i))) / 2, xs.get(i)));
        }
        return result;
    }

    static void generate(Path out, int games, long seed, int threads, String[] matchups) throws Exception {
        int k = matchups.length;
        PlayerSpec[][] specs = new PlayerSpec[k][2];
        for (int i = 0; i < k; i++) {
            String[] ab = matchups[i].split("\\|");
            specs[i][0] = PlayerSpec.parse(ab[0]);
            specs[i][1] = PlayerSpec.parse(ab[1]);
        }
        AtomicInteger done = new AtomicInteger();
        long t0 = System.nanoTime();
        List<Future<List<Sample>>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int g = 0; g < games; g++) {
                int game = g;
                futures.add(pool.submit(() -> {
                    PlayerSpec[] m = specs[game % k];
                    boolean swap = (game / k) % 2 == 1;
                    List<Sample> s = playAndSample(game, seed, swap ? m[1] : m[0], swap ? m[0] : m[1]);
                    int n = done.incrementAndGet();
                    if (n % 50 == 0 || n == games) {
                        double el = (System.nanoTime() - t0) / 1e9;
                        System.err.printf(Locale.ROOT, "%d/%d games  %.1f/s  ETA %.0fs  elapsed %.0fs%n",
                                n, games, n / el, (games - n) / (n / el), el);
                    }
                    return s;
                }));
            }
            Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
            try (BufferedWriter w = Files.newBufferedWriter(tmp)) {
                w.write("game,ply,label," + String.join(",", LinearEvaluator.NAMES) + "\n");
                for (Future<List<Sample>> f : futures) {
                    for (Sample s : f.get()) {
                        StringBuilder sb = new StringBuilder();
                        sb.append(s.game()).append(',').append(s.ply()).append(',').append(s.label());
                        for (double v : s.x()) {
                            sb.append(',').append(v);
                        }
                        w.write(sb.append('\n').toString());
                    }
                }
            }
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
    }

    // ---- fit ----

    static List<Sample> read(String[] files) throws IOException {
        List<Sample> samples = new ArrayList<>();
        int gameOffset = 0;
        for (String file : files) {
            int maxGame = 0;
            List<String> lines = Files.readAllLines(Path.of(file));
            for (String line : lines.subList(1, lines.size())) {
                String[] t = line.split(",");
                double[] x = new double[F];
                for (int i = 0; i < F; i++) {
                    x[i] = Double.parseDouble(t[3 + i]);
                }
                int game = Integer.parseInt(t[0]);
                maxGame = Math.max(maxGame, game);
                samples.add(new Sample(gameOffset + game, Integer.parseInt(t[1]), Double.parseDouble(t[2]), x));
            }
            gameOffset += ((maxGame + 1 + 4) / 5) * 5; // keeps game % 5 of every file
        }
        return samples;
    }

    static boolean heldOut(Sample s) {
        return s.game() % 5 == 0;
    }

    /**
     * Logistic regression on the masked features; returns raw-unit weights, one per mask entry (12, or 13 with
     * {@code bestGain}; the samples must have that many values). The tempo term {@link LinearEvaluator#TEMPO} is the
     * intercept.
     */
    static double[] logistic(List<Sample> train, boolean[] mask, double l2) {
        int n = train.size();
        int F = mask.length;
        if (!mask[LinearEvaluator.TEMPO]) {
            throw new IllegalArgumentException("the fit needs the tempo term as its intercept");
        }
        double[] mean = new double[F];
        double[] sd = new double[F];
        for (Sample s : train) {
            for (int i = 0; i < F; i++) {
                mean[i] += s.x()[i] / n;
            }
        }
        for (Sample s : train) {
            for (int i = 0; i < F; i++) {
                sd[i] += (s.x()[i] - mean[i]) * (s.x()[i] - mean[i]) / n;
            }
        }
        int tempo = LinearEvaluator.TEMPO;
        int[] idx = java.util.stream.IntStream.range(0, F).filter(i -> mask[i]).toArray();
        int m = idx.length;
        double[][] z = new double[n][m];
        for (int r = 0; r < n; r++) {
            for (int j = 0; j < m; j++) {
                int i = idx[j];
                z[r][j] = i == tempo ? 1.0 : (train.get(r).x()[i] - mean[i]) / Math.sqrt(Math.max(sd[i], 1e-12));
            }
        }
        double[] b = new double[m];
        for (int iter = 0; iter < 30; iter++) {
            double[] grad = new double[m];
            double[][] hess = new double[m][m];
            for (int r = 0; r < n; r++) {
                double p = sigmoid(dot(b, z[r]));
                double y = train.get(r).label();
                double w = p * (1 - p);
                for (int j = 0; j < m; j++) {
                    grad[j] += (p - y) * z[r][j];
                    for (int l = 0; l <= j; l++) {
                        hess[j][l] += w * z[r][j] * z[r][l];
                    }
                }
            }
            for (int j = 0; j < m; j++) {
                for (int l = 0; l < j; l++) {
                    hess[l][j] = hess[j][l];
                }
                grad[j] += l2 * n * b[j];
                hess[j][j] += l2 * n;
            }
            double[] step = solve(hess, grad);
            double change = 0;
            for (int j = 0; j < m; j++) {
                b[j] -= step[j];
                change = Math.max(change, Math.abs(step[j]));
            }
            if (change < 1e-9) {
                break;
            }
        }
        // back to raw units: w_i = b_j / sd_i, intercept absorbs -sum w_i * mean_i; the mean shift is only
        // valid for the intercept if tempo is used, which the fit always includes
        double[] w = new double[F];
        double intercept = 0;
        for (int j = 0; j < m; j++) {
            int i = idx[j];
            if (i == tempo) {
                intercept += b[j];
            } else {
                w[i] = b[j] / Math.sqrt(Math.max(sd[i], 1e-12));
                intercept -= w[i] * mean[i];
            }
        }
        w[tempo] = intercept;
        return w;
    }

    static double sigmoid(double v) {
        return 1 / (1 + Math.exp(-v));
    }

    static double dot(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * b[i];
        }
        return s;
    }

    /** Gaussian elimination with partial pivoting. */
    static double[] solve(double[][] aIn, double[] bIn) {
        int n = bIn.length;
        double[][] a = new double[n][];
        for (int i = 0; i < n; i++) {
            a[i] = aIn[i].clone();
        }
        double[] b = bIn.clone();
        for (int c = 0; c < n; c++) {
            int p = c;
            for (int r = c + 1; r < n; r++) {
                if (Math.abs(a[r][c]) > Math.abs(a[p][c])) {
                    p = r;
                }
            }
            double[] t = a[c];
            a[c] = a[p];
            a[p] = t;
            double tb = b[c];
            b[c] = b[p];
            b[p] = tb;
            for (int r = c + 1; r < n; r++) {
                double f = a[r][c] / a[c][c];
                for (int k = c; k < n; k++) {
                    a[r][k] -= f * a[c][k];
                }
                b[r] -= f * b[c];
            }
        }
        double[] x = new double[n];
        for (int r = n - 1; r >= 0; r--) {
            double s = b[r];
            for (int k = r + 1; k < n; k++) {
                s -= a[r][k] * x[k];
            }
            x[r] = s / a[r][r];
        }
        return x;
    }

    /** {logloss, accuracy of the winner among decided games, count}. */
    static double[] score(List<Sample> data, double[] w) {
        double loss = 0;
        int correct = 0;
        int decided = 0;
        for (Sample s : data) {
            double p = Math.min(1 - 1e-12, Math.max(1e-12, sigmoid(dot(w, s.x()))));
            loss -= s.label() * Math.log(p) + (1 - s.label()) * Math.log(1 - p);
            if (s.label() != 0.5) {
                decided++;
                if ((p > 0.5) == (s.label() > 0.5)) {
                    correct++;
                }
            }
        }
        return new double[] {loss / data.size(), (double) correct / decided, data.size()};
    }

    static boolean[] mask(String spec) {
        return mask(spec, LinearEvaluator.NAMES);
    }

    /** A mask over the given feature names ({@link LinearEvaluator#NAMES} or {@link LinearEvaluator#GAIN_NAMES}). */
    static boolean[] mask(String spec, String[] names) {
        boolean[] mask = new boolean[names.length];
        if (spec.equals("all")) {
            Arrays.fill(mask, true);
            return mask;
        }
        mask[LinearEvaluator.TEMPO] = true;
        for (String name : spec.split(",")) {
            int i = Arrays.asList(names).indexOf(name);
            if (i < 0) {
                throw new IllegalArgumentException("unknown feature " + name);
            }
            mask[i] = true;
        }
        return mask;
    }

    static void fit(Path out, String maskSpec, String[] files, double scale) throws IOException {
        List<Sample> all = read(files);
        List<Sample> train = all.stream().filter(s -> !heldOut(s)).toList();
        List<Sample> test = all.stream().filter(LinearTraining::heldOut).toList();
        System.out.printf(Locale.ROOT, "samples: %d train, %d held out (%d games held out)%n", train.size(),
                test.size(), test.stream().mapToInt(Sample::game).distinct().count());
        double[] base = logistic(train, mask("dsum"), 1e-6);
        double[] w = logistic(train, mask(maskSpec), 1e-6);
        report("distance only", train, test, base);
        report("model " + maskSpec, train, test, w);
        for (int lo = 0; lo < 200; lo += 40) {
            int from = lo;
            List<Sample> bucket = test.stream().filter(s -> s.ply() >= from && s.ply() < from + 40).toList();
            if (!bucket.isEmpty()) {
                System.out.printf(Locale.ROOT, "  plies %3d-%3d: n=%6d  acc distance %.4f  model %.4f%n", from,
                        from + 39, bucket.size(), score(bucket, base)[1], score(bucket, w)[1]);
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# LinearTraining fit, mask=").append(maskSpec).append(", files=")
                .append(String.join(" ", files)).append('\n');
        sb.append("# order: scale, ").append(String.join(" ", LinearEvaluator.NAMES)).append('\n');
        sb.append(scale).append('\n');
        for (int i = 0; i < F; i++) {
            sb.append(String.format(Locale.ROOT, "%.6g%n", w[i]));
            System.out.printf(Locale.ROOT, "  %-10s %+.6g%n", LinearEvaluator.NAMES[i], w[i]);
        }
        Files.writeString(out, sb.toString());
    }

    static void report(String label, List<Sample> train, List<Sample> test, double[] w) {
        double[] tr = score(train, w);
        double[] te = score(test, w);
        System.out.printf(Locale.ROOT, "%-28s train logloss %.4f acc %.4f | held-out logloss %.4f acc %.4f%n",
                label, tr[0], tr[1], te[0], te[1]);
    }
}
