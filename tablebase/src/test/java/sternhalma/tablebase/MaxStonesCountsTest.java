package sternhalma.tablebase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * {@link MaxStonesCounts}: exact where the number of stones in row 1 is at most N, the fallback elsewhere. The boundary
 * k == N is the case a flipped comparison ({@code <} for {@code <=}) gets wrong, so the tests draw it on purpose.
 */
class MaxStonesCountsTest {

    private static final int SENTINEL = 12_345;

    private static final RaceCounts FALLBACK = new RaceCounts() {
        @Override
        public boolean covers(int[] ownCells) {
            return true;
        }

        @Override
        public int count(int[] ownCells) {
            return SENTINEL;
        }
    };

    private static RaceTable rows1() {
        RaceTable t = RaceTable.rows1IfCached();
        assumeTrue(t != null, "the rows>=1 table is not available (-Psternhalma.tablebase.dir)");
        return t;
    }

    @Test
    void exactUpToAndIncludingMaxKAndTheFallbackAbove() {
        RaceTable rows1 = rows1();
        long[] seenAt = new long[PushEstimator.CLASSES];
        Random random = new Random(5);
        for (int maxK = 0; maxK <= 3; maxK++) {
            MaxStonesCounts counts = new MaxStonesCounts(rows1, maxK, FALLBACK);
            java.util.Arrays.fill(seenAt, 0);
            for (int i = 0; i < 200_000; i++) {
                long rank = Math.floorMod(random.nextLong(), rows1.states());
                int exact = rows1.entry(rank);
                if (exact == LookupObserver.UNKNOWN) {
                    continue;
                }
                int[] cells = rows1.rankingOf().unrank(rank);
                int k = PushEstimator.stonesInRow1(cells);
                seenAt[k]++;
                assertEquals(k <= maxK ? exact : SENTINEL, counts.count(cells), "maxK=" + maxK + " k=" + k);
            }
            assertTrue(seenAt[maxK] > 100, "the boundary k == maxK was drawn only " + seenAt[maxK] + " times");
            assertTrue(seenAt[maxK + 1] > 100, "k == maxK + 1 was drawn only " + seenAt[maxK + 1] + " times");
        }
    }

    @Test
    void aConfigurationOutsideTheRegionIsNotCovered() {
        RaceTable rows1 = rows1();
        MaxStonesCounts counts = new MaxStonesCounts(rows1, 2, FALLBACK);
        assertEquals(-1, counts.count(new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}), "cells in rows below 1");
    }

    @Test
    void badArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new MaxStonesCounts(RaceTable.rows2(), 1, FALLBACK));
        assertThrows(IllegalArgumentException.class, () -> new MaxStonesCounts(rows1(), -1, FALLBACK));
    }
}
