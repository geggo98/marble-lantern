package sternhalma.tablebase;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import sternhalma.engine.Geometry;
import sternhalma.engine.RuleSet.LeaveGoal;

/**
 * Layered retrograde breadth-first search for a single-player race table (docs/endgame-and-opening.md, "The
 * endgame database principle"): the configuration with all pieces in the goal has distance 0; the predecessors of
 * layer d that have no distance yet get d + 1, until a layer is empty.
 *
 * <p>A predecessor of a configuration S moves one piece from its cell {@code to} back to a cell {@code from} of
 * the region such that the forward move {@code from -> to} is legal: a step or a jump chain over the other pieces
 * of S (jump chains are reversible, so the reverse chain over the same hurdles proves the forward one), landing
 * on an allowed cell, and with {@code LeaveGoal.FORBIDDEN} not from the goal corner to a cell outside it. The
 * intermediate landings of a chain may lie outside the region; only the configurations before and after a move
 * must lie inside it. Every entry is therefore the exact number of moves of the solitaire game restricted to
 * configurations inside the region, which is an upper bound of the unrestricted solitaire distance.
 *
 * <p>Entries are bytes; {@link #UNKNOWN} marks a configuration that cannot reach the goal inside the region.
 * The layer scan is split over threads; two threads may write the same value d + 1 into the same entry, which is
 * harmless, and the layers are separated by joins.
 */
public final class RetrogradeBuilder {

    public static final int UNKNOWN = 255;

    private RetrogradeBuilder() {
    }

    /**
     * Builds the table of a region for player 0.
     *
     * @param ranking   the region and the piece count
     * @param leaveGoal whether a piece may leave the goal corner again
     * @param threads   worker threads for the layer scans
     */
    public static byte[] build(Ranking ranking, LeaveGoal leaveGoal, int threads) {
        long count = ranking.count();
        if (count > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("region too large for an in-memory table: " + count + " states");
        }
        byte[] table = new byte[(int) count];
        Arrays.fill(table, (byte) UNKNOWN);
        int[] goal = Geometry.cornerCells(SoloMoves.GOAL_CORNER);
        if (goal.length != ranking.pieces()) {
            throw new IllegalArgumentException("the table needs exactly one piece per goal cell");
        }
        for (int c : goal) {
            if (!ranking.contains(c)) {
                throw new IllegalArgumentException("the region must contain the goal corner");
            }
        }
        table[(int) ranking.rank(goal)] = 0;
        int workers = Math.max(1, threads);
        for (int d = 0; ; d++) {
            long found = expandLayer(ranking, leaveGoal, table, d, workers);
            if (found == 0) {
                break;
            }
            if (d + 1 >= UNKNOWN) {
                throw new IllegalStateException("distance does not fit into a byte");
            }
        }
        return table;
    }

    /** Sets every unset predecessor of a layer-d state to d + 1; returns how many states layer d had. */
    private static long expandLayer(Ranking ranking, LeaveGoal leaveGoal, byte[] table, int d, int workers) {
        int n = table.length;
        long[] counts = new long[workers];
        List<Thread> threads = new ArrayList<>();
        for (int w = 0; w < workers; w++) {
            int worker = w;
            int begin = (int) ((long) n * w / workers);
            int end = (int) ((long) n * (w + 1) / workers);
            Thread t = new Thread(() -> counts[worker] = expandRange(ranking, leaveGoal, table, d, begin, end),
                    "retrograde-" + w);
            t.start();
            threads.add(t);
        }
        long total = 0;
        for (int w = 0; w < workers; w++) {
            try {
                threads.get(w).join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while building the table", e);
            }
            total += counts[w];
        }
        return total;
    }

    private static long expandRange(Ranking ranking, LeaveGoal leaveGoal, byte[] table, int d, int begin, int end) {
        byte layer = (byte) d;
        byte next = (byte) (d + 1);
        int k = ranking.pieces();
        int[] idx = new int[k];
        int[] cells = new int[k];
        int[] pred = new int[k];
        int[] region = ranking.cells();
        boolean forbidden = leaveGoal == LeaveGoal.FORBIDDEN;
        SoloMoves moves = new SoloMoves();
        long found = 0;
        for (int rank = begin; rank < end; rank++) {
            if (table[rank] != layer) {
                continue;
            }
            found++;
            ranking.unrankIndices(rank, idx);
            long lo = 0;
            long hi = 0;
            for (int j = 0; j < k; j++) {
                cells[j] = region[idx[j]];
                lo = SoloMoves.withLo(lo, cells[j]);
                hi = SoloMoves.withHi(hi, cells[j]);
            }
            for (int j = 0; j < k; j++) {
                int to = cells[j];
                boolean toInGoal = Geometry.cornerOf(to) == SoloMoves.GOAL_CORNER;
                int piece = j;
                moves.landings(lo, hi, to, from -> {
                    if (!ranking.contains(from)) {
                        return; // the predecessor must lie inside the region
                    }
                    if (forbidden && !toInGoal && Geometry.cornerOf(from) == SoloMoves.GOAL_CORNER) {
                        return; // the forward move would leave the goal
                    }
                    System.arraycopy(cells, 0, pred, 0, k);
                    pred[piece] = from;
                    int r = (int) ranking.rank(pred);
                    if (table[r] == (byte) UNKNOWN) {
                        table[r] = next;
                    }
                });
            }
        }
        return found;
    }
}
