package sternhalma.tablebase;

/**
 * Sees every lookup of the rows&gt;=1 table that an {@link ObservedRows1Counts} serves. The call happens after the
 * value is read and does not change it.
 */
public interface LookupObserver {

    /** Role tag: the lookup was made by the leaf evaluator. */
    int LEAF = 0;
    /** Role tag: the lookup was made by race play. */
    int RACEPLAY = 1;

    /** The raw value of an unreachable configuration (255) and of an absent fallback. */
    int UNKNOWN = RetrogradeBuilder.UNKNOWN;

    /**
     * @param role     {@link #LEAF} or {@link #RACEPLAY}
     * @param cells    the configuration in player 0's orientation (do not keep the array)
     * @param rank     the rank in the rows&gt;=1 ranking
     * @param rows1    the raw rows&gt;=1 entry ({@link #UNKNOWN} for an unreachable configuration)
     * @param rows2    the rows&gt;=2 count, or {@link #UNKNOWN} if that table does not cover the configuration (a
     *                 piece in row 1) or the configuration is unreachable there
     * @param estimate the baseline estimate ({@link PushEstimator}, no correction), or {@link #UNKNOWN}
     */
    void lookup(int role, int[] cells, long rank, int rows1, int rows2, int estimate);
}
