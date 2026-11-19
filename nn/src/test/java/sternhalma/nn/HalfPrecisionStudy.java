package sternhalma.nn;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import sternhalma.engine.Position;

/**
 * Not a test: how much does storing the weights as float16 (F32 to F16 to F32 at load) change the outputs of a net?
 * Usage: {@code HalfPrecisionStudy <net.safetensors> [positions=2000] [seed=11]}. Prints a table; it does not judge strength.
 *
 * <p>Positions: seeded random two-player playouts (adjacent jump rule), positions between ply 20 and ply 120 that are not
 * finished and have at least two legal moves, {@code positions} of them evenly spaced. Variants: {@code all} converts every
 * tensor, {@code weights} converts only the convolution and dense weight matrices (biases and the prior scalars stay F32).
 * Compared with the F32 net, both through the scalar reference {@link Network} and {@link Evaluator}: the raw policy logits
 * and values (maximum and mean absolute error over all 242 logits and all values), the priors over the legal moves (KL
 * divergence of the F32 priors from the F16 priors, move agreement of the arg max, mean absolute difference of the
 * absolute value).
 */
public final class HalfPrecisionStudy {

    private HalfPrecisionStudy() {
    }

    public static void main(String[] args) throws IOException {
        Parameters base = Parameters.load(Path.of(args[0]));
        int wanted = args.length > 1 ? Integer.parseInt(args[1]) : 2000;
        long seed = args.length > 2 ? Long.parseLong(args[2]) : 11;
        NetworkConfig config = base.config();
        List<Position> pool = new ArrayList<>();
        for (Position p : Playouts.positions(2, 200, 160, seed)) {
            if (p.ply() >= 20 && p.ply() <= 120 && p.outcome() == null && p.legalMoves().length >= 2) {
                pool.add(p);
            }
        }
        if (pool.size() < wanted) {
            throw new IllegalStateException("only " + pool.size() + " positions");
        }
        List<Position> positions = new ArrayList<>();
        for (int i = 0; i < wanted; i++) {
            positions.add(pool.get((int) ((long) i * pool.size() / wanted)));
        }
        System.out.println("# net " + args[0] + " " + config + ", " + positions.size() + " positions of " + pool.size()
                + " (ply 20..120, seed " + seed + ")");
        Network ref = new Network(base);
        Evaluator refEval = new Evaluator(ref);
        List<Evaluation> reference = evaluate(refEval, positions);
        float[][] refRaw = raw(ref, config, positions);

        for (String variant : new String[] {"all", "weights"}) {
            Parameters half = roundTrip(base, variant.equals("all"));
            Stats overflow = rangeStats(base);
            Network net = new Network(half);
            List<Evaluation> evaluations = evaluate(new Evaluator(net), positions);
            float[][] raw = raw(net, config, positions);
            report(variant, positions, reference, evaluations, refRaw, raw, config.players(), overflow);
        }
    }

    private static List<Evaluation> evaluate(Evaluator evaluator, List<Position> positions) {
        List<Evaluation> out = new ArrayList<>();
        for (int i = 0; i < positions.size(); i += 64) {
            out.addAll(evaluator.evaluate(positions.subList(i, Math.min(positions.size(), i + 64))));
        }
        return out;
    }

    /** logits and values of the whole position list, encoded with the plane set of the net. */
    private static float[][] raw(Network net, NetworkConfig config, List<Position> positions) {
        int n = positions.size();
        int size = config.inputSize();
        float[] input = new float[n * size];
        for (int i = 0; i < n; i++) {
            Encoder.encode(config.planes(), positions.get(i), input, i * size);
        }
        float[] logits = new float[n * Encoder.POLICY_SIZE];
        float[] values = new float[n * config.players()];
        net.forward(input, n, logits, values);
        return new float[][] {logits, values};
    }

    static float half(float x) {
        return Float.float16ToFloat(Float.floatToFloat16(x));
    }

    /** F32 to F16 to F32. {@code all}: every tensor; otherwise only the weight matrices (names ending in .weight). */
    static Parameters roundTrip(Parameters p, boolean all) {
        Map<String, Tensor> tensors = new LinkedHashMap<>();
        for (Map.Entry<String, Tensor> e : p.tensors().entrySet()) {
            Tensor t = e.getValue();
            float[] data = t.data().clone();
            if (all || t.name().endsWith(".weight")) {
                for (int i = 0; i < data.length; i++) {
                    data[i] = half(data[i]);
                }
            }
            tensors.put(e.getKey(), new Tensor(t.name(), t.shape(), data));
        }
        return Parameters.of(p.config(), tensors);
    }

    record Stats(int total, int overflow, int flushed, double maxRelRounding) {
    }

    /** How the weights sit in the F16 range: values beyond 65504, values that become 0 or subnormal, worst relative rounding. */
    static Stats rangeStats(Parameters p) {
        int total = 0;
        int overflow = 0;
        int flushed = 0;
        double worst = 0;
        for (Tensor t : p.tensors().values()) {
            for (float x : t.data()) {
                total++;
                float h = half(x);
                if (Float.isInfinite(h) && !Float.isInfinite(x)) {
                    overflow++;
                }
                if (x != 0 && Math.abs(x) < 6.103515625e-5f) {
                    flushed++; // below the smallest normal F16: subnormal or zero, fewer bits of precision
                }
                if (x != 0 && Math.abs(x) >= 6.103515625e-5f) {
                    worst = Math.max(worst, Math.abs((h - x) / x));
                }
            }
        }
        return new Stats(total, overflow, flushed, worst);
    }

    private static void report(String variant, List<Position> positions, List<Evaluation> a, List<Evaluation> b, float[][] rawA,
            float[][] rawB, int players, Stats range) {
        int n = positions.size();
        double maxLogit = 0;
        double sumLogit = 0;
        double maxValue = 0;
        double sumValue = 0;
        double maxFraction = 0;
        for (int i = 0; i < rawA[0].length; i++) {
            double d = Math.abs(rawA[0][i] - rawB[0][i]);
            maxLogit = Math.max(maxLogit, d);
            sumLogit += d;
            maxFraction = Math.max(maxFraction, d / (1e-4 + 1e-3 * Math.abs(rawA[0][i])));
        }
        for (int i = 0; i < rawA[1].length; i++) {
            double d = Math.abs(rawA[1][i] - rawB[1][i]);
            maxValue = Math.max(maxValue, d);
            sumValue += d;
            maxFraction = Math.max(maxFraction, d / (1e-4 + 1e-3 * Math.abs(rawA[1][i])));
        }
        int agree = 0;
        int disagreeNearTie = 0;
        double[] kl = new double[n];
        double[] absValueDiff = new double[n];
        for (int i = 0; i < n; i++) {
            Evaluation x = a.get(i);
            Evaluation y = b.get(i);
            int bx = argMax(x.priors());
            int by = argMax(y.priors());
            if (bx == by) {
                agree++;
            } else if (x.priors()[bx] - x.priors()[by] < 0.01) {
                disagreeNearTie++;
            }
            double k = 0;
            for (int m = 0; m < x.priors().length; m++) {
                double p = x.priors()[m];
                double q = y.priors()[m];
                if (p > 0) {
                    k += p * Math.log(p / Math.max(q, 1e-30));
                }
            }
            kl[i] = k;
            absValueDiff[i] = Math.abs(x.values()[0] - y.values()[0]);
        }
        double[] sortedKl = kl.clone();
        Arrays.sort(sortedKl);
        System.out.println(String.format(Locale.ROOT,
                "variant=%s | F16 range: %d weights, %d overflow, %d below the smallest normal, worst relative rounding of a normal weight %.2e",
                variant, range.total(), range.overflow(), range.flushed(), range.maxRelRounding()));
        System.out.println(String.format(Locale.ROOT,
                "  policy logits: max abs err %.3e, mean abs err %.3e | values: max abs err %.3e, mean abs err %.3e | worst error as a fraction of the 1e-4+1e-3|ref| bound: %.2f",
                maxLogit, sumLogit / rawA[0].length, maxValue, sumValue / rawA[1].length, maxFraction));
        System.out.println(String.format(Locale.ROOT,
                "  arg-max legal move agrees in %d of %d positions (%.2f%%); %d of the %d disagreements are near ties (F32 prior gap < 0.01)",
                agree, n, 100.0 * agree / n, disagreeNearTie, n - agree));
        System.out.println(String.format(Locale.ROOT,
                "  KL(F32 || F16) over the legal moves: mean %.3e, median %.3e, p99 %.3e, max %.3e | absolute value of player 0: mean abs diff %.3e, max %.3e",
                Arrays.stream(kl).average().orElse(0), sortedKl[n / 2], sortedKl[(int) (n * 0.99)], sortedKl[n - 1],
                Arrays.stream(absValueDiff).average().orElse(0), Arrays.stream(absValueDiff).max().orElse(0)));
    }

    private static int argMax(float[] priors) {
        int best = 0;
        for (int i = 1; i < priors.length; i++) {
            if (priors[i] > priors[best]) {
                best = i;
            }
        }
        return best;
    }
}
