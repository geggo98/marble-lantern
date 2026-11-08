package sternhalma.search;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import sternhalma.engine.Position;

/**
 * Measuring tool of round-2 experiment E07 (not a test): the cost of one static evaluation with and without the
 * {@code bestGain} feature, in thread CPU time. Positions: the 64 reference positions ({@link ReferencePositions})
 * and every child of each, which is what a search evaluates at its leaves. The evaluators run interleaved
 * (A, B, C, A, B, C, ...) for {@code reps} repetitions after a warm-up, and the median per evaluator is printed.
 *
 * <pre>
 *   java -cp ... sternhalma.search.GainCost [reps] [eval ...]     # default: linear-r2 linear-r3 and the mover mode
 * </pre>
 */
public final class GainCost {

    private GainCost() {
    }

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        int reps = args.length > 0 ? Integer.parseInt(args[0]) : 7;
        List<String> names = new ArrayList<>(args.length > 1 ? Arrays.asList(args).subList(1, args.length)
                : List.of("linear-r2", "linear-r3", "mover"));
        List<Evaluator> evaluators = new ArrayList<>();
        for (String n : names) {
            evaluators.add(n.equals("mover")
                    ? new LinearEvaluator(LinearWeights.ROUND3, LinearWeights.SCALE, LinearEvaluator.GainMode.MOVER)
                    : Evaluators.byName(n));
        }
        List<Position> positions = new ArrayList<>();
        for (Position root : ReferencePositions.positions()) {
            positions.add(root);
            for (int m : root.legalMoves()) {
                Position child = root.copy();
                child.make(m);
                if (child.outcome() == null) {
                    positions.add(child);
                }
            }
        }
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        int k = evaluators.size();
        double[][] nsPerEval = new double[k][reps];
        long sink = 0;
        int inner = 40;
        for (int rep = -2; rep < reps; rep++) { // two warm-up repetitions
            for (int e = 0; e < k; e++) {
                Evaluator ev = evaluators.get(e);
                long t0 = bean.getCurrentThreadCpuTime();
                for (int i = 0; i < inner; i++) {
                    for (Position p : positions) {
                        sink += ev.evaluate(p);
                    }
                }
                long t1 = bean.getCurrentThreadCpuTime();
                if (rep >= 0) {
                    nsPerEval[e][rep] = (t1 - t0) / (double) (inner * positions.size());
                }
            }
        }
        System.out.printf("# GainCost: %d positions (64 roots and their children), %d repetitions, sink %d%n",
                positions.size(), reps, sink);
        double base = median(nsPerEval[0]);
        for (int e = 0; e < k; e++) {
            double m = median(nsPerEval[e]);
            System.out.printf("%-12s %7.1f ns per evaluation (min %.1f, max %.1f)  x%.2f of %s%n", names.get(e), m,
                    Arrays.stream(nsPerEval[e]).min().orElse(0), Arrays.stream(nsPerEval[e]).max().orElse(0), m / base,
                    names.get(0));
        }
    }

    private static double median(double[] v) {
        double[] s = v.clone();
        Arrays.sort(s);
        return s[s.length / 2];
    }
}
