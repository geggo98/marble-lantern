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
            default -> throw new IllegalArgumentException("usage: generate|fit ...");
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

    /** Logistic regression on the masked features; returns raw-unit weights. */
    static double[] logistic(List<Sample> train, boolean[] mask, double l2) {
        int n = train.size();
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
        int tempo = F - 1;
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
        boolean[] mask = new boolean[F];
        if (spec.equals("all")) {
            Arrays.fill(mask, true);
            return mask;
        }
        mask[F - 1] = true;
        for (String name : spec.split(",")) {
            int i = Arrays.asList(LinearEvaluator.NAMES).indexOf(name);
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
