package sternhalma.selfplay;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The paired difference of two result files that were played from the same openings (docs/training.md, "Evaluating
 * variants"): candidate A against candidate B, both against the same opponents.
 *
 * <pre>
 *   PairedReport A.jsonl B.jsonl [--json OUT.json]
 * </pre>
 *
 * <p>A file is the {@code openings-*.jsonl} of {@link ThreeSeatMatch} (unit: an opening, value: the share of its three
 * games the candidate won) or the {@code pairs-*.jsonl} of {@link MctsMatch} (unit: a pair, value: its score). The two files
 * must be of the same kind. The units are matched by their index; units that only one file has are ignored and counted. For
 * every common unit the difference d = A - B is taken, and the mean difference, its standard error {@code sd(d) / sqrt(n)}
 * and {@code z = mean / standard error} are reported, with the two-sided p-value of the normal distribution. A difference
 * counts as a gain only at {@code |z| > 1.96} (the success criterion of docs/training.md). Pairing removes what the openings
 * contribute to both files, which is why it is tighter than comparing two shares with their own intervals.
 *
 * <p>Two three-seat files that name different opening positions for the same index are refused: they were not played from the
 * same openings (different seed, opening plies or rule), and a paired difference of them would be meaningless. A pairs file
 * does not store its openings, so for those the caller has to keep seed and opening plies equal. If every difference is equal
 * the standard error is 0: z is 0 for a mean of 0 and infinite otherwise.
 */
final class PairedReport {

    static final double Z95 = 1.96;
    /** A standard error below this is rounding noise of equal differences: the spread is 0. */
    static final double NO_SPREAD = 1e-12;

    /** The comparison of two files. */
    record Report(String kind, int units, int onlyInA, int onlyInB, double meanA, double meanB, double meanDifference,
            double standardError, double z, double pValue) {

        boolean significant() {
            return Math.abs(z) > Z95;
        }

        String text() {
            return String.format(Locale.ROOT, "%s: %d common units (%d only in A, %d only in B); mean A %.4f, mean B %.4f; A - B"
                    + " = %+.4f +- %.4f (standard error); z = %+.2f, p = %.4f (two-sided); %s", kind, units, onlyInA, onlyInB,
                    meanA, meanB, meanDifference, standardError, z, pValue,
                    significant() ? (z > 0 ? "A is better at |z| > 1.96" : "B is better at |z| > 1.96")
                            : "no difference at |z| > 1.96");
        }

        Map<String, Object> fields() {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("kind", kind);
            f.put("units", units);
            f.put("only_in_a", onlyInA);
            f.put("only_in_b", onlyInB);
            f.put("mean_a", meanA);
            f.put("mean_b", meanB);
            f.put("mean_difference", meanDifference);
            f.put("standard_error", standardError);
            f.put("z", z);
            f.put("p_value", pValue);
            f.put("significant_at_1.96", significant());
            return f;
        }
    }

    /** One file: its kind, and per unit its value and (three-seat files) the hash of the opening position. */
    private record Loaded(String kind, Map<Integer, Double> values, Map<Integer, Long> openings) {
    }

    private PairedReport() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            Path json = null;
            Path[] files = new Path[2];
            int n = 0;
            for (int i = 0; i < args.length; i++) {
                if (args[i].equals("--json") && i + 1 < args.length) {
                    json = Path.of(args[++i]);
                } else if (n < 2 && !args[i].startsWith("--")) {
                    files[n++] = Path.of(args[i]);
                } else {
                    throw new IllegalArgumentException("usage: PairedReport A.jsonl B.jsonl [--json OUT.json]");
                }
            }
            if (n != 2) {
                throw new IllegalArgumentException("usage: PairedReport A.jsonl B.jsonl [--json OUT.json]");
            }
            Report report = compare(files[0], files[1]);
            out.println(report.text());
            if (json != null) {
                Path tmp = json.resolveSibling(json.getFileName() + ".tmp." + ProcessHandle.current().pid());
                Files.writeString(tmp, RunMonitor.json(report.fields()) + "\n", StandardCharsets.UTF_8);
                Files.move(tmp, json, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return 0;
        } catch (IllegalArgumentException e) {
            err.println("pairedreport: " + e.getMessage());
            return 2;
        } catch (IOException e) {
            err.println("pairedreport: " + e);
            return 1;
        }
    }

    static Report compare(Path a, Path b) throws IOException {
        return compare(load(a), load(b), a + " vs " + b);
    }

    private static Report compare(Loaded a, Loaded b, String names) {
        if (!a.kind().equals(b.kind())) {
            throw new IllegalArgumentException("the files are of different kinds (" + a.kind() + ", " + b.kind() + "): " + names);
        }
        double[] d = new double[Math.min(a.values().size(), b.values().size())];
        int n = 0;
        double sumA = 0;
        double sumB = 0;
        for (Map.Entry<Integer, Double> e : a.values().entrySet()) {
            Double other = b.values().get(e.getKey());
            if (other == null) {
                continue;
            }
            Long hashA = a.openings().get(e.getKey());
            Long hashB = b.openings().get(e.getKey());
            if (hashA != null && hashB != null && !hashA.equals(hashB)) {
                throw new IllegalArgumentException("unit " + e.getKey() + " starts from another opening in the two files: they"
                        + " were not played from the same openings (seed, opening plies or jump rule differ)");
            }
            d[n++] = e.getValue() - other;
            sumA += e.getValue();
            sumB += other;
        }
        if (n < 2) {
            throw new IllegalArgumentException("fewer than two common units: " + names);
        }
        double mean = 0;
        for (int i = 0; i < n; i++) {
            mean += d[i];
        }
        mean /= n;
        double sq = 0;
        for (int i = 0; i < n; i++) {
            sq += (d[i] - mean) * (d[i] - mean);
        }
        double se = Math.sqrt(sq / (n - 1) / n);
        // equal differences leave rounding noise of about 1e-17 in se (1/3 has no exact double); values are in [0, 1]
        if (se < NO_SPREAD) {
            se = 0;
        }
        double z = se > 0 ? mean / se : mean == 0 ? 0 : Math.copySign(Double.POSITIVE_INFINITY, mean);
        return new Report(a.kind(), n, a.values().size() - n, b.values().size() - n, sumA / n, sumB / n, mean, se, z,
                twoSidedP(z));
    }

    /** The two-sided p-value of a standard normal statistic: erfc(|z| / sqrt 2). */
    static double twoSidedP(double z) {
        return Double.isInfinite(z) ? 0 : erfc(Math.abs(z) / Math.sqrt(2));
    }

    /** The complementary error function (Numerical Recipes, erfcc, fractional error below 1.2e-7). */
    private static double erfc(double x) {
        double t = 1.0 / (1.0 + 0.5 * x);
        double poly = -x * x - 1.26551223 + t * (1.00002368 + t * (0.37409196 + t * (0.09678418 + t * (-0.18628806
                + t * (0.27886807 + t * (-1.13520398 + t * (1.48851587 + t * (-0.82215223 + t * 0.17087277))))))));
        return t * Math.exp(poly);
    }

    private static Loaded load(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("not a file: " + file);
        }
        Map<Integer, Double> values = new TreeMap<>();
        Map<Integer, Long> openings = new TreeMap<>();
        String kind = null;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            Map<String, String> m = RunMonitor.parse(line);
            if (m == null) {
                continue; // a torn last line
            }
            try {
                if (m.containsKey("opening") && m.containsKey("c_sims")) {
                    ThreeSeatMatch.OpeningLine l = ThreeSeatMatch.OpeningLine.of(m);
                    kind = merge(kind, "three-seat openings (share of the candidate's wins)", file);
                    values.put(l.opening(), l.winShare());
                    openings.put(l.opening(), l.openingHash());
                } else if (m.containsKey("pair") && m.containsKey("o_nodes")) {
                    MctsMatch.PairLine l = MctsMatch.PairLine.of(m);
                    kind = merge(kind, "pairs (score of the subject)", file);
                    values.put(l.pair(), l.score());
                }
            } catch (NumberFormatException e) {
                // a torn line
            }
        }
        if (kind == null) {
            throw new IllegalArgumentException("no openings or pairs in " + file);
        }
        return new Loaded(kind, values, openings);
    }

    private static String merge(String kind, String found, Path file) {
        if (kind != null && !kind.equals(found)) {
            throw new IllegalArgumentException("lines of two kinds in " + file);
        }
        return found;
    }
}
