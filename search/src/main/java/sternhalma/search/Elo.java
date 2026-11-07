package sternhalma.search;

/** Elo arithmetic for match results given as mean scores in [0, 1]. */
public final class Elo {

    private Elo() {
    }

    /** Elo difference implied by a mean score. The score is clamped to avoid infinite values. */
    public static double fromScore(double score) {
        double s = Math.min(0.999, Math.max(0.001, score));
        return 400.0 * Math.log10(s / (1.0 - s));
    }

    /** The expected score for an Elo difference. */
    public static double expectedScore(double eloDifference) {
        return 1.0 / (1.0 + Math.pow(10.0, -eloDifference / 400.0));
    }

    /** Mean of a sample. */
    public static double mean(double[] values) {
        double sum = 0;
        for (double v : values) {
            sum += v;
        }
        return sum / values.length;
    }

    /** Standard error of the mean (sample standard deviation over the square root of n). */
    public static double standardError(double[] values) {
        if (values.length < 2) {
            return Double.NaN;
        }
        double m = mean(values);
        double sq = 0;
        for (double v : values) {
            sq += (v - m) * (v - m);
        }
        return Math.sqrt(sq / (values.length - 1)) / Math.sqrt(values.length);
    }

    /** Half width of the 95 percent confidence interval in Elo, from the standard error of the score. */
    public static double confidence95(double score, double scoreStandardError) {
        double lo = fromScore(score - 1.96 * scoreStandardError);
        double hi = fromScore(score + 1.96 * scoreStandardError);
        return (hi - lo) / 2.0;
    }
}
