package sternhalma.tablebase;

/**
 * The rows&gt;=1 table restricted to the configurations with at most {@code maxK} stones in row 1. This simulates a
 * rows&gt;=1 table that stores only those states (the row-1 decomposition: k &lt;= 1 is 68.4 M states, k &lt;= 2
 * 155 M states of the 254 M). A configuration with k &lt;= maxK gets the exact count; any other gets the answer of the
 * fallback, which is an estimator ({@link EstimatedCounts}) or the rows&gt;=2 table, as the caller chose. The fallback
 * may be not covering (-1), as the rows&gt;=2 table is for a configuration whose pushed image it does not know.
 */
public final class MaxStonesCounts implements RaceCounts {

    private final RaceTable rows1;
    private final int maxK;
    private final RaceCounts fallback;

    public MaxStonesCounts(RaceTable rows1, int maxK, RaceCounts fallback) {
        if (rows1.minRow() != 1) {
            throw new IllegalArgumentException("need the rows>=1 table");
        }
        if (maxK < 0) {
            throw new IllegalArgumentException("maxK must not be negative: " + maxK);
        }
        this.rows1 = rows1;
        this.maxK = maxK;
        this.fallback = fallback;
    }

    @Override
    public boolean covers(int[] ownCells) {
        return count(ownCells) >= 0;
    }

    @Override
    public int count(int[] ownCells) {
        Ranking r1 = rows1.rankingOf();
        if (!r1.covers(ownCells)) {
            return -1;
        }
        if (PushEstimator.stonesInRow1(ownCells) <= maxK) {
            int v = rows1.entry(r1.rank(ownCells));
            return v == LookupObserver.UNKNOWN ? -1 : v;
        }
        return fallback.count(ownCells);
    }
}
