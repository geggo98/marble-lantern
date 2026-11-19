package sternhalma.selfplay;

/**
 * The exact (Clopper-Pearson) one-sided upper confidence bound of a binomial error rate: the largest p with
 * P(X <= k | n, p) >= alpha, found by bisection on the binomial distribution function. With alpha = 0.05:
 * 0 of n gives 1 - 0.05^(1/n), about 3.0/n; 1 of n about 4.74/n; 2 of n about 6.3/n (round-2 plan, label policy).
 */
public final class ClopperPearson {

    private ClopperPearson() {
    }

    /** The one-sided upper bound at level 1 - alpha for k errors in n trials; 1 if n == 0 or k == n. */
    public static double upper(long k, long n, double alpha) {
        if (k < 0 || n < 0 || k > n) {
            throw new IllegalArgumentException("need 0 <= k <= n, got k=" + k + " n=" + n);
        }
        if (n == 0 || k == n) {
            return 1.0;
        }
        double lo = (double) k / n;
        double hi = 1.0;
        for (int iter = 0; iter < 200; iter++) {
            double mid = (lo + hi) / 2;
            if (cdf(k, n, mid) > alpha) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return (lo + hi) / 2;
    }

    /**
     * The one-sided lower bound at level 1 - alpha for k successes in n trials: the smallest p with
     * P(X >= k | n, p) >= alpha. By the symmetry of the binomial it is {@code 1 - upper(n - k, n, alpha)}; 0 if k == 0,
     * and 0 if n == 0.
     */
    public static double lower(long k, long n, double alpha) {
        if (k < 0 || n < 0 || k > n) {
            throw new IllegalArgumentException("need 0 <= k <= n, got k=" + k + " n=" + n);
        }
        return n == 0 ? 0.0 : 1.0 - upper(n - k, n, alpha);
    }

    /**
     * The two-sided exact interval {lower, upper} at level 1 - alpha: both bounds one-sided at alpha / 2. For n == 0 it
     * is {0, 1}. The usual interval of a win share (docs/training.md, "Evaluating variants").
     */
    public static double[] interval(long k, long n, double alpha) {
        return new double[] {lower(k, n, alpha / 2), upper(k, n, alpha / 2)};
    }

    /** The two-sided 95 % interval {lower, upper}. */
    public static double[] interval95(long k, long n) {
        return interval(k, n, 0.05);
    }

    /** One-sided 95% upper bound. */
    public static double upper95(long k, long n) {
        return upper(k, n, 0.05);
    }

    /** P(X <= k) for X ~ Binomial(n, p), summed in log space. */
    static double cdf(long k, long n, double p) {
        if (p <= 0) {
            return 1.0;
        }
        if (p >= 1) {
            return k >= n ? 1.0 : 0.0;
        }
        double logP = Math.log(p);
        double logQ = Math.log1p(-p);
        double logTerm = n * logQ; // i = 0
        double max = logTerm;
        double[] terms = new double[(int) k + 1];
        terms[0] = logTerm;
        for (long i = 1; i <= k; i++) {
            logTerm += Math.log((double) (n - i + 1) / i) + logP - logQ;
            terms[(int) i] = logTerm;
            max = Math.max(max, logTerm);
        }
        double sum = 0;
        for (double t : terms) {
            sum += Math.exp(t - max);
        }
        return Math.min(1.0, Math.exp(max) * sum);
    }
}
