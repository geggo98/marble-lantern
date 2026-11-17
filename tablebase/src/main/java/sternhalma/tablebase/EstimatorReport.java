package sternhalma.tablebase;

import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

/**
 * Error report of the baseline estimator ({@link PushEstimator}) against the exact rows&gt;=1 table on uniformly
 * random states. Needs both tables (property {@code sternhalma.tablebase.dir} or the cache).
 *
 * <pre>
 *   EstimatorReport [--samples 1000000] [--fit-samples 1000000] [--seed 1]
 * </pre>
 *
 * The corrections are fitted on one sample (seed S) and judged on another (seed S+1), so the numbers are hold-out
 * numbers. Per k (stones in row 1): states drawn, unreachable (entry 255, skipped), without an estimate, exact,
 * within 1, mean and mean absolute error, largest absolute error. Then the pair test: for random pairs (a, b) of
 * reachable states, how often the estimate has the same sign of {@code b - a} as the table. That sign decides a race.
 * A uniform sample is not the distribution of an engine's lookups (see the probe tool); these numbers say how good the
 * estimator is as a function, not how good the player is.
 */
public final class EstimatorReport {

    private EstimatorReport() {
    }

    public static void main(String[] args) {
        long samples = 1_000_000;
        long fitSamples = 1_000_000;
        long seed = 1;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--samples" -> samples = Long.parseLong(args[++i]);
                case "--fit-samples" -> fitSamples = Long.parseLong(args[++i]);
                case "--seed" -> seed = Long.parseLong(args[++i]);
                default -> {
                    System.err.println("usage: EstimatorReport [--samples N] [--fit-samples N] [--seed S]");
                    System.exit(2);
                }
            }
        }
        RaceTable rows1 = RaceTable.rows1();
        RaceTable rows2 = RaceTable.rows2();
        System.out.println("tables: rows1 " + rows1.states() + " states, rows2 " + rows2.states() + " states");
        int[] fitted = PushEstimator.fit(rows1, rows2, fitSamples, seed);
        System.out.println("fit: " + fitSamples + " random states (seed " + seed + "), median corrections by k = "
                + Arrays.toString(fitted));
        System.out.println("variant text for -Dsternhalma.race.eval/-Dsternhalma.race.play: estimate:"
                + String.join(",", Arrays.stream(fitted).mapToObj(Integer::toString).toList()));
        report("plain (all corrections 0)", rows1, new PushEstimator(rows2, new int[PushEstimator.CLASSES]),
                samples, seed + 1);
        report("fitted", rows1, new PushEstimator(rows2, fitted), samples, seed + 1);
    }

    static void report(String label, RaceTable rows1, PushEstimator est, long samples, long seed) {
        int classes = PushEstimator.CLASSES;
        long[] drawn = new long[classes];
        long[] unreachable = new long[classes];
        long[] none = new long[classes];
        long[] exact = new long[classes];
        long[] within1 = new long[classes];
        long[] sum = new long[classes];
        long[] sumAbs = new long[classes];
        long[] maxAbs = new long[classes];
        Random random = new Random(seed);
        long states = rows1.states();
        long pairs = 0;
        long pairsSame = 0;
        long pairsUsable = 0;
        int prevTruth = -1;
        int prevEst = -1;
        for (long i = 0; i < samples; i++) {
            long rank = Math.floorMod(random.nextLong(), states);
            int[] cells = rows1.rankingOf().unrank(rank);
            int k = PushEstimator.stonesInRow1(cells);
            drawn[k]++;
            int truth = rows1.entry(rank);
            if (truth == LookupObserver.UNKNOWN) {
                unreachable[k]++;
                continue;
            }
            int e = est.estimate(cells);
            if (e < 0) {
                none[k]++;
            } else {
                int d = e - truth;
                exact[k] += d == 0 ? 1 : 0;
                within1[k] += Math.abs(d) <= 1 ? 1 : 0;
                sum[k] += d;
                sumAbs[k] += Math.abs(d);
                maxAbs[k] = Math.max(maxAbs[k], Math.abs(d));
            }
            if (prevTruth >= 0) { // pair (previous reachable state, this one)
                pairs++;
                if (e >= 0 && prevEst >= 0) {
                    pairsUsable++;
                    pairsSame += Integer.signum(truth - prevTruth) == Integer.signum(e - prevEst) ? 1 : 0;
                }
                prevTruth = -1;
            } else {
                prevTruth = truth;
                prevEst = e;
            }
        }
        System.out.println();
        System.out.println("== " + label + ": " + samples + " uniformly random states (seed " + seed + ") ==");
        System.out.println("k  drawn      unreachable  no-estimate  exact%  within1%  mean-err  mean|err|  max|err|");
        long[] total = new long[8];
        for (int k = 0; k < classes; k++) {
            if (drawn[k] == 0) {
                continue;
            }
            long n = drawn[k] - unreachable[k] - none[k];
            System.out.println(line(Integer.toString(k), drawn[k], unreachable[k], none[k], exact[k], within1[k], sum[k],
                    sumAbs[k], maxAbs[k], n));
            total[0] += drawn[k];
            total[1] += unreachable[k];
            total[2] += none[k];
            total[3] += exact[k];
            total[4] += within1[k];
            total[5] += sum[k];
            total[6] += sumAbs[k];
            total[7] = Math.max(total[7], maxAbs[k]);
        }
        System.out.println(line("all", total[0], total[1], total[2], total[3], total[4], total[5], total[6], total[7],
                total[0] - total[1] - total[2]));
        System.out.printf(Locale.ROOT, "pairs: %d formed, %d with an estimate for both, sign of (b - a) agrees in %.2f%%%n",
                pairs, pairsUsable, pairsUsable == 0 ? Double.NaN : 100.0 * pairsSame / pairsUsable);
    }

    private static String line(String k, long drawn, long unreachable, long none, long exact, long within1, long sum,
            long sumAbs, long maxAbs, long n) {
        return String.format(Locale.ROOT, "%-3s%-11d%-13d%-13d%-8.2f%-10.2f%-10.3f%-11.3f%d", k, drawn, unreachable,
                none, n == 0 ? Double.NaN : 100.0 * exact / n, n == 0 ? Double.NaN : 100.0 * within1 / n,
                n == 0 ? Double.NaN : (double) sum / n, n == 0 ? Double.NaN : (double) sumAbs / n, maxAbs);
    }
}
