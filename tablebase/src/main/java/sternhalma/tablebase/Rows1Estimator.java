package sternhalma.tablebase;

/** A pluggable estimate of the rows&gt;=1 count of a configuration, for replacing the 242 MB table. */
public interface Rows1Estimator {

    /**
     * The estimated rows&gt;=1 count of a configuration given in player 0's orientation, or -1 if the estimator
     * has none. The configuration lies in the rows&gt;=1 region (ten distinct cells, none in a row below 1).
     */
    int estimate(int[] ownCells);
}
