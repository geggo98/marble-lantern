package sternhalma.tablebase;

import java.util.Arrays;
import java.util.Random;

import sternhalma.engine.Geometry;

/**
 * The {@link PushEstimator} with a finer correction: not one offset per number k of stones in row 1 but one per
 * occupancy pattern of row 1 (8 cells, so 256 patterns, k is the bit count). The pushed base estimate cannot see which
 * cells of row 1 are occupied beyond how the push moves them; the pattern says where the stones must go, and the median
 * error of the base for that pattern is the correction. 256 signed bytes (0.25 KB) plus the rows&gt;=2 table.
 * Not thread-safe (the wrapped estimator keeps scratch space).
 */
public final class PatternEstimator implements Rows1Estimator {

    /** The patterns of row 1: 2^8. */
    public static final int PATTERNS = 256;

    private static final int[] BIT_OF_CELL = new int[Geometry.CELLS];

    static {
        Arrays.fill(BIT_OF_CELL, -1);
        int bit = 0;
        for (int c : Rows.regionCells(1)) {
            if (Geometry.r(c) == 1) {
                BIT_OF_CELL[c] = bit++;
            }
        }
        if (bit != 8) {
            throw new IllegalStateException("row 1 should have 8 cells, found " + bit);
        }
    }

    /**
     * The corrections of the variant {@code estimate2}: {@link #fit} on 5,000,000 random states, seed 1 (the fit of
     * {@link EstimatorReport}, which judges them on seed 2). Mean absolute error 0.535 against 0.583 for the per-k median
     * of {@link PushEstimator}, on the same hold-out sample.
     */
    public static final int[] FITTED = java.util.Arrays.stream((
            "0,0,0,-1,0,-1,-1,-2,-1,-1,-1,-2,-1,-2,-2,-3,-1,-1,-1,-2,-1,-2,-2,-3,-2,-2,-2,-3,-2,-3,-3,-4,"
            + "-1,-1,-1,-2,-1,-1,-2,-2,-1,-2,-2,-3,-2,-2,-3,-4,-2,-2,-2,-3,-2,-3,-3,-4,-2,-3,-3,-4,-3,-4,-4,-5,"
            + "-1,-1,-1,-2,-1,-1,-2,-3,-1,-1,-2,-3,-2,-2,-3,-3,-1,-1,-2,-2,-2,-2,-3,-3,-2,-2,-3,-3,-3,-3,-4,-4,"
            + "-2,-2,-2,-3,-2,-2,-3,-3,-2,-2,-3,-4,-3,-3,-4,-5,-2,-3,-3,-3,-3,-3,-4,-4,-3,-3,-4,-4,-4,-4,-5,-5,"
            + "0,0,0,-1,-1,-1,-2,-2,-1,-1,-1,-2,-2,-2,-2,-3,-1,-1,-1,-2,-1,-2,-2,-3,-2,-2,-2,-3,-2,-3,-3,-4,"
            + "-1,-1,-1,-2,-1,-2,-2,-3,-1,-2,-2,-3,-2,-3,-3,-4,-2,-2,-2,-3,-2,-3,-3,-4,-3,-3,-3,-4,-3,-3,-4,-4,"
            + "-1,-1,-1,-2,-1,-2,-2,-3,-2,-2,-2,-3,-2,-3,-3,-4,-2,-2,-2,-3,-3,-3,-3,-4,-3,-3,-3,-4,-4,-4,-4,-5,"
            + "-2,-2,-2,-3,-2,-3,-3,-4,-3,-3,-3,-4,-3,-4,-4,-5,-3,-3,-3,-4,-3,-4,-4,-5,-4,-4,-4,-4,-5,-4,-5,-5").split(",")).mapToInt(Integer::parseInt).toArray();

    private final PushEstimator base;
    private final int[] correction;

    public PatternEstimator(RaceTable rows2, int[] correction) {
        if (correction.length != PATTERNS) {
            throw new IllegalArgumentException("need " + PATTERNS + " corrections, got " + correction.length);
        }
        this.base = new PushEstimator(rows2, new int[PushEstimator.CLASSES]);
        this.correction = correction.clone();
    }

    /** The occupancy pattern of row 1: bit i is set if the i-th cell of row 1 holds a stone. */
    public static int pattern(int[] cells) {
        int p = 0;
        for (int c : cells) {
            if (Geometry.r(c) == 1) {
                p |= 1 << BIT_OF_CELL[c];
            }
        }
        return p;
    }

    @Override
    public int estimate(int[] cells) {
        int b = base.base(cells);
        return b < 0 ? -1 : Math.max(0, b + correction[pattern(cells)]);
    }

    /**
     * The corrections that fit a sample: per pattern the median of {@code rows1 - base} over {@code samples} uniformly
     * random ranks (unreachable states skipped). A pattern without a sample gets the median of its k, else 0.
     */
    public static int[] fit(RaceTable rows1, RaceTable rows2, long samples, long seed) {
        PushEstimator plain = new PushEstimator(rows2, new int[PushEstimator.CLASSES]);
        Random random = new Random(seed);
        long states = rows1.states();
        int[][] histogram = new int[PATTERNS][512];
        int[][] byK = new int[PushEstimator.CLASSES][512];
        for (long i = 0; i < samples; i++) {
            long rank = Math.floorMod(random.nextLong(), states);
            int v = rows1.entry(rank);
            if (v == LookupObserver.UNKNOWN) {
                continue;
            }
            int[] cells = rows1.rankingOf().unrank(rank);
            int b = plain.base(cells);
            if (b < 0) {
                continue;
            }
            int bin = Math.max(0, Math.min(511, v - b + 256));
            histogram[pattern(cells)][bin]++;
            byK[PushEstimator.stonesInRow1(cells)][bin]++;
        }
        int[] medianOfK = new int[PushEstimator.CLASSES];
        for (int k = 0; k < medianOfK.length; k++) {
            medianOfK[k] = median(byK[k], 0);
        }
        int[] fitted = new int[PATTERNS];
        for (int p = 0; p < PATTERNS; p++) {
            fitted[p] = median(histogram[p], medianOfK[Integer.bitCount(p)]);
        }
        return fitted;
    }

    private static int median(int[] histogram, int none) {
        long n = 0;
        for (int c : histogram) {
            n += c;
        }
        if (n == 0) {
            return none;
        }
        long seen = 0;
        for (int bin = 0; bin < histogram.length; bin++) {
            seen += histogram[bin];
            if (2 * seen >= n) {
                return bin - 256;
            }
        }
        return none;
    }
}
