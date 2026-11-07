package sternhalma.search;

import java.util.Locale;

/**
 * A sequential probability ratio test on pair scores, the optional stop rule of the arena ({@code --sprt}).
 *
 * <p>H0: the Elo difference is {@code elo0}; H1: it is {@code elo1}. The pair scores are treated as normally
 * distributed with the sample variance of the pairs played so far (the usual generalised SPRT); the
 * log-likelihood ratio after n pairs with mean m and variance v is
 * {@code n (s1 - s0) (2m - s0 - s1) / (2v)}, where s0 and s1 are the expected scores of elo0 and elo1. The test
 * accepts H1 when the ratio reaches {@code ln((1 - beta) / alpha)} and H0 when it falls to
 * {@code ln(beta / (1 - alpha))}.
 *
 * <p>For authors' tuning loops only: verifier runs stay fixed-size (docs/search-and-evaluation.md).
 */
public record Sprt(double elo0, double elo1, double alpha, double beta) {

    /** The decision after some pairs. */
    public enum Decision { CONTINUE, ACCEPT_H0, ACCEPT_H1 }

    public Sprt {
        if (!(elo1 > elo0)) {
            throw new IllegalArgumentException("SPRT needs elo0 < elo1, got " + elo0 + "," + elo1);
        }
        if (!(alpha > 0 && alpha < 0.5 && beta > 0 && beta < 0.5)) {
            throw new IllegalArgumentException("SPRT needs 0 < alpha, beta < 0.5");
        }
    }

    /** {@code elo0,elo1} with alpha = beta = 0.05. */
    public static Sprt parse(String text) {
        String[] parts = text.split(",");
        if (parts.length != 2) {
            throw new IllegalArgumentException("expected --sprt elo0,elo1, got '" + text + "'");
        }
        return new Sprt(Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim()), 0.05, 0.05);
    }

    public double lowerBound() {
        return Math.log(beta / (1 - alpha));
    }

    public double upperBound() {
        return Math.log((1 - beta) / alpha);
    }

    /** The log-likelihood ratio of the first {@code n} pair scores; 0 while the variance is 0. */
    public double llr(double[] pairScores, int n) {
        if (n < 2) {
            return 0;
        }
        double sum = 0;
        for (int i = 0; i < n; i++) {
            sum += pairScores[i];
        }
        double mean = sum / n;
        double sq = 0;
        for (int i = 0; i < n; i++) {
            sq += (pairScores[i] - mean) * (pairScores[i] - mean);
        }
        double variance = sq / n;
        if (variance <= 0) {
            return 0;
        }
        double s0 = Elo.expectedScore(elo0);
        double s1 = Elo.expectedScore(elo1);
        return n * (s1 - s0) * (2 * mean - s0 - s1) / (2 * variance);
    }

    public Decision decide(double llr) {
        if (llr >= upperBound()) {
            return Decision.ACCEPT_H1;
        }
        if (llr <= lowerBound()) {
            return Decision.ACCEPT_H0;
        }
        return Decision.CONTINUE;
    }

    /** A report line, e.g. {@code SPRT elo0=0 elo1=20 llr=3.01 [-2.94, 2.94] after 120 pairs: H1 accepted}. */
    public String describe(double llr, int pairs) {
        String verdict = switch (decide(llr)) {
            case ACCEPT_H1 -> "H1 accepted";
            case ACCEPT_H0 -> "H0 accepted";
            case CONTINUE -> "no decision";
        };
        return String.format(Locale.ROOT, "SPRT elo0=%s elo1=%s alpha=%s beta=%s llr=%.2f [%.2f, %.2f] after %d pairs: %s",
                trim(elo0), trim(elo1), trim(alpha), trim(beta), llr, lowerBound(), upperBound(), pairs, verdict);
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
    }
}
