package sternhalma.tablebase;

import java.util.Arrays;

/**
 * Combinatorial number system over the cells of a region: a bijection between the k-subsets of the region's
 * cells and the integers {@code 0 .. C(n, k) - 1}, so that a table needs no keys.
 *
 * <p>With the subset as ascending region indices {@code i_0 < ... < i_(k-1)}, the rank is the sum of
 * {@code C(i_j, j + 1)}. Unranking is greedy from the largest index down.
 */
public final class Ranking {

    private final int[] cells;
    private final int[] indexOfCell;
    private final int pieces;
    private final long[][] binomial;
    private final long count;

    /**
     * @param cells  the cells of the region, ascending cell ids
     * @param pieces the subset size k
     */
    public Ranking(int[] cells, int pieces) {
        this.cells = cells.clone();
        this.pieces = pieces;
        this.indexOfCell = new int[sternhalma.engine.Geometry.CELLS];
        Arrays.fill(indexOfCell, -1);
        for (int i = 0; i < cells.length; i++) {
            if (i > 0 && cells[i] <= cells[i - 1]) {
                throw new IllegalArgumentException("cells must be strictly ascending");
            }
            indexOfCell[cells[i]] = i;
        }
        this.binomial = new long[cells.length + 1][pieces + 1];
        for (int n = 0; n <= cells.length; n++) {
            binomial[n][0] = 1;
            for (int k = 1; k <= Math.min(n, pieces); k++) {
                binomial[n][k] = binomial[n - 1][k - 1] + (k <= n - 1 ? binomial[n - 1][k] : 0);
            }
        }
        this.count = binomial[cells.length][pieces];
    }

    /** Number of states, {@code C(n, k)}. */
    public long count() {
        return count;
    }

    public int pieces() {
        return pieces;
    }

    /** The region's cells, ascending. The returned array must not be modified. */
    public int[] cells() {
        return cells;
    }

    /** Whether a cell belongs to the region. */
    public boolean contains(int cell) {
        return indexOfCell[cell] >= 0;
    }

    /** Whether every cell lies in the region and there are exactly {@code pieces} of them. */
    public boolean covers(int[] ownCells) {
        if (ownCells.length != pieces) {
            return false;
        }
        for (int c : ownCells) {
            if (c < 0 || c >= indexOfCell.length || indexOfCell[c] < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * The rank of a configuration given as cells in any order (all distinct, all in the region).
     *
     * @throws IllegalArgumentException if the configuration is not covered
     */
    public long rank(int[] ownCells) {
        if (!covers(ownCells)) {
            throw new IllegalArgumentException("configuration outside the region: " + Arrays.toString(ownCells));
        }
        int[] idx = new int[pieces];
        for (int j = 0; j < pieces; j++) {
            idx[j] = indexOfCell[ownCells[j]];
        }
        Arrays.sort(idx);
        return rankIndices(idx);
    }

    /** The rank of ascending region indices. */
    long rankIndices(int[] idx) {
        long rank = 0;
        for (int j = 0; j < pieces; j++) {
            if (j > 0 && idx[j] <= idx[j - 1]) {
                throw new IllegalArgumentException("indices must be strictly ascending: " + Arrays.toString(idx));
            }
            rank += binomial[idx[j]][j + 1];
        }
        return rank;
    }

    /** Writes the ascending region indices of {@code rank} into {@code out} (length {@code pieces}). */
    void unrankIndices(long rank, int[] out) {
        long rest = rank;
        int bound = cells.length;
        for (int j = pieces - 1; j >= 0; j--) {
            // the largest i < bound with C(i, j+1) <= rest
            int lo = j;
            int hi = bound - 1;
            while (lo < hi) {
                int mid = (lo + hi + 1) >>> 1;
                if (binomial[mid][j + 1] <= rest) {
                    lo = mid;
                } else {
                    hi = mid - 1;
                }
            }
            out[j] = lo;
            rest -= binomial[lo][j + 1];
            bound = lo;
        }
        if (rest != 0) {
            throw new IllegalArgumentException("rank out of range: " + rank);
        }
    }

    /** The configuration of a rank as ascending cell ids. */
    public int[] unrank(long rank) {
        int[] idx = new int[pieces];
        unrankIndices(rank, idx);
        int[] result = new int[pieces];
        for (int j = 0; j < pieces; j++) {
            result[j] = cells[idx[j]];
        }
        return result;
    }
}
