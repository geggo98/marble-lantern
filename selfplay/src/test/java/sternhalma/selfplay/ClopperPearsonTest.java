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

    private static double binomial(int n, int k) {
        double r = 1;
        for (int i = 1; i <= k; i++) {
            r = r * (n - k + i) / i;
        }
        return r;
    }
}
