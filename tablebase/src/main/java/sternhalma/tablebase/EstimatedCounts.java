package sternhalma.tablebase;

/**
 * Race counts for the rows&gt;=1 region from a {@link Rows1Estimator}; the table itself is never read. Covers every
 * configuration of the region for which the estimator gives a value.
 */
public final class EstimatedCounts implements RaceCounts {

    private final Ranking ranking;
    private final Rows1Estimator estimator;

    public EstimatedCounts(Rows1Estimator estimator) {
        this.ranking = RaceTable.ROWS1.ranking();
        this.estimator = estimator;
    }

    @Override
    public boolean covers(int[] ownCells) {
        return count(ownCells) >= 0;
    }

    @Override
    public int count(int[] ownCells) {
        return ranking.covers(ownCells) ? estimator.estimate(ownCells) : -1;
    }
}
