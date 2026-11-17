package sternhalma.tablebase;

import java.util.Arrays;
import java.util.Random;

import sternhalma.engine.Geometry;

/**
 * The baseline estimator of the rows&gt;=1 count: push every stone in row 1 to the nearest free cell of row 2 (next
 * rows if full; ties: lower cell id), count one move for each, add the rows&gt;=2 count of the pushed configuration,
 * then add a correction that depends on the number k of stones in row 1. This is the estimate of
 * {@code RaceEvaluator} ({@code wout=1}) with a per-k offset. It needs the rows&gt;=2 table (2.8 MB packed) and
 * nine integers, nothing else. Not thread-safe (scratch space).
 *
 * <p>The correction for k is the median of {@code rows1 - base} over the reachable states of a random sample
 * ({@link #fit}); the plain estimate has all corrections 0. An estimate below 0 is clamped to 0.
 */
public final class PushEstimator implements Rows1Estimator {

    /** Correction classes: k = 0 .. 8 stones in row 1 (row 1 has 8 cells). */
    public static final int CLASSES = 9;

    private final RaceTable rows2;
    private final int[] correction;
    private final int[][] rowCells;
    private final int[] scratch = new int[sternhalma.engine.Position.PIECES];
    private final boolean[] occupied = new boolean[Geometry.CELLS];

    public PushEstimator(RaceTable rows2, int[] correction) {
        if (rows2.minRow() != 2 || correction.length != CLASSES) {
            throw new IllegalArgumentException("need the rows>=2 table and " + CLASSES + " corrections");
        }
        this.rows2 = rows2;
        this.correction = correction.clone();
        this.rowCells = new int[Rows.LAST_ROW - 1][];
        for (int row = 2; row <= Rows.LAST_ROW; row++) {
            int r = row;
            rowCells[row - 2] = Arrays.stream(Rows.regionCells(2)).filter(c -> Geometry.r(c) == r).toArray();
        }
    }

    public int[] correction() {
        return correction.clone();
    }

    /** The number of stones in row 1. */
    public static int stonesInRow1(int[] cells) {
        int k = 0;
        for (int c : cells) {
            if (Geometry.r(c) == 1) {
                k++;
            }
        }
        return k;
    }

    /** The estimate without the correction, or -1 if the pushed configuration is not covered by the rows&gt;=2 table. */
    public int base(int[] cells) {
        System.arraycopy(cells, 0, scratch, 0, scratch.length);
        Arrays.sort(scratch);
        Arrays.fill(occupied, false);
        for (int c : scratch) {
            occupied[c] = true;
        }
        int moves = 0;
        for (int i = 0; i < scratch.length && Geometry.r(scratch[i]) < 2; i++) {
            int from = scratch[i];
            if (Geometry.r(from) < 1) {
                return -1;
            }
            moves++;
            int target = nearestFree(Geometry.q(from));
            occupied[from] = false;
            occupied[target] = true;
            scratch[i] = target;
        }
        Arrays.sort(scratch);
        int count = rows2.count(scratch);
        return count < 0 ? -1 : moves + count;
    }

    private int nearestFree(int q) {
        for (int[] row : rowCells) {
            int best = -1;
            int bestDistance = Integer.MAX_VALUE;
            for (int c : row) {
                int d = Math.abs(Geometry.q(c) - q);
                if (!occupied[c] && d < bestDistance) {
                    best = c;
                    bestDistance = d;
                }
            }
            if (best >= 0) {
                return best;
            }
        }
        throw new IllegalStateException("no free cell in rows >= 2");
    }

    @Override
    public int estimate(int[] cells) {
        int base = base(cells);
        if (base < 0) {
            return -1;
        }
        return Math.max(0, base + correction[stonesInRow1(cells)]);
    }

    /**
     * The corrections that fit a sample: for every k the median of {@code rows1 - base} over {@code samples} uniformly
     * random ranks of the rows&gt;=1 table (unreachable states and states without a base are skipped). A class
     * without a sample gets 0.
     */
    public static int[] fit(RaceTable rows1, RaceTable rows2, long samples, long seed) {
        PushEstimator plain = new PushEstimator(rows2, new int[CLASSES]);
        Random random = new Random(seed);
        long states = rows1.states();
        int[][] histogram = new int[CLASSES][512];
        for (long i = 0; i < samples; i++) {
            long rank = Math.floorMod(random.nextLong(), states);
            int v = rows1.entry(rank);
            if (v == LookupObserver.UNKNOWN) {
                continue;
            }
            int[] cells = rows1.rankingOf().unrank(rank);
            int base = plain.base(cells);
            if (base < 0) {
                continue;
            }
            histogram[stonesInRow1(cells)][Math.max(0, Math.min(511, v - base + 256))]++;
        }
        int[] fitted = new int[CLASSES];
        for (int k = 0; k < CLASSES; k++) {
            long n = 0;
            for (int c : histogram[k]) {
                n += c;
            }
            if (n == 0) {
                continue;
            }
            long seen = 0;
            for (int bin = 0; bin < 512; bin++) {
                seen += histogram[k][bin];
                if (2 * seen >= n) {
                    fitted[k] = bin - 256;
                    break;
                }
            }
        }
        return fitted;
    }
}
