package sternhalma.selfplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The bound behind every adjudication error claim (round-2 plan, label policy). */
class ClopperPearsonTest {

    @Test
    void zeroErrorsHasTheClosedForm() {
        for (int n : new int[] {1, 10, 398, 600, 999, 5000}) {
            assertEquals(1 - Math.pow(0.05, 1.0 / n), ClopperPearson.upper95(0, n), 1e-9, "n=" + n);
        }
        // the critic's figure: 0 of 398 bounds the error only at about 0.75 %
        assertEquals(0.0075, ClopperPearson.upper95(0, 398), 0.00005);
    }

    @Test
    void thePlanMinimumsForHalfAPercentHold() {
        // '< 0.5 %' needs 0 of >= 600, 1 of >= 950 or 2 of >= 1260 firings
        assertTrue(ClopperPearson.upper95(0, 600) < 0.005);
        assertTrue(ClopperPearson.upper95(0, 597) > 0.005);
        assertTrue(ClopperPearson.upper95(1, 950) < 0.005);
        assertTrue(ClopperPearson.upper95(1, 940) > 0.005);
        assertTrue(ClopperPearson.upper95(2, 1260) < 0.005);
        assertTrue(ClopperPearson.upper95(2, 1250) > 0.005);
    }

    @Test
    void theBoundIsTheExactBinomialQuantile() {
        // P(X <= k | n, upper) = 0.05, checked with a direct sum of binomial terms
        int n = 50;
        for (int k = 0; k < n; k++) {
            double p = ClopperPearson.upper95(k, n);
            double direct = 0;
            for (int i = 0; i <= k; i++) {
                direct += binomial(n, i) * Math.pow(p, i) * Math.pow(1 - p, n - i);
            }
            assertEquals(0.05, direct, 1e-9, "k=" + k);
            assertTrue(p > (double) k / n, "the upper bound exceeds the point estimate");
        }
        assertEquals(1.0, ClopperPearson.upper95(5, 5));
        assertEquals(1.0, ClopperPearson.upper95(0, 0));
        assertThrows(IllegalArgumentException.class, () -> ClopperPearson.upper95(3, 2));
    }

    // known two-sided 95 % Clopper-Pearson intervals (R: binom.test(k, n)$conf.int; tables of exact binomial intervals)
    @Test
    void theTwoSidedIntervalMatchesKnownCases() {
        assertInterval(0, 10, 0.0, 0.3085);
        assertInterval(5, 10, 0.1871, 0.8129);
        assertInterval(1, 20, 0.00126, 0.2487);
        assertInterval(50, 100, 0.3983, 0.6017);
        assertInterval(10, 10, 0.6915, 1.0);
    }

    @Test
    void theTwoSidedIntervalIsTheExactBinomialQuantilePairAndContainsThePointEstimate() {
        int n = 40;
        for (int k = 1; k < n; k++) {
            double[] ci = ClopperPearson.interval95(k, n);
            double lowerTail = 0;
            for (int i = k; i <= n; i++) {
                lowerTail += binomial(n, i) * Math.pow(ci[0], i) * Math.pow(1 - ci[0], n - i);
            }
            double upperTail = 0;
            for (int i = 0; i <= k; i++) {
                upperTail += binomial(n, i) * Math.pow(ci[1], i) * Math.pow(1 - ci[1], n - i);
            }
            assertEquals(0.025, lowerTail, 1e-9, "P(X >= k | lower), k=" + k);
            assertEquals(0.025, upperTail, 1e-9, "P(X <= k | upper), k=" + k);
            assertTrue(ci[0] < (double) k / n && (double) k / n < ci[1]);
        }
        assertEquals(0.0, ClopperPearson.interval95(0, 0)[0]);
        assertEquals(1.0, ClopperPearson.interval95(0, 0)[1]);
        assertThrows(IllegalArgumentException.class, () -> ClopperPearson.interval95(3, 2));
    }

    private static void assertInterval(long k, long n, double lo, double hi) {
        double[] ci = ClopperPearson.interval95(k, n);
        assertEquals(lo, ci[0], 5e-5, k + " of " + n + " lower");
        assertEquals(hi, ci[1], 5e-5, k + " of " + n + " upper");
    }

    private static double binomial(int n, int k) {
        double r = 1;
        for (int i = 1; i <= k; i++) {
            r = r * (n - k + i) / i;
        }
        return r;
    }
}
