package sternhalma.search;

import java.util.Arrays;

import sternhalma.engine.Geometry;
import sternhalma.engine.Layout;
import sternhalma.engine.Position;

/**
 * The jump structure of a two-player position, kept up to date cell by cell, and the {@code bestGain} search on it
 * (round-2 experiments E07 and E13; the answers are those of {@link LinearEvaluator#bestGain}, which stays the
 * oracle and is not changed).
 *
 * <p>A jump chain moves over the free allowed cells along the edges "X to Y = X + 2e over an occupied hurdle
 * X + e" (see {@code LinearEvaluator.GainSearch} for why chains are the connected components of these edges and why
 * the moving piece's own cell does not matter). Here the edges are not looked up per cell in a list and tested
 * against the occupancy at every visit; instead {@link #adjLo}/{@link #adjHi} hold, for every cell X, the set of
 * landings Y whose hurdle is occupied, as a 128-bit set. Filling or emptying a cell x flips one bit in six sets (the
 * cell on one side of x gets the cell on the other side), so a move costs twelve bit flips and
 * {@link #toggle} undoes itself. A component is then found by a flood fill with set operations: the new cells of
 * a visit are {@code adj[c] & free & ~component}. Components are labelled lazily, when the first landing of a piece
 * reaches them; a generation stamp per cell marks the labels of the current position, so nothing is cleared.
 *
 * <p>Two players with the same allowed cells only (the two-player layout), so one graph serves both sides; the
 * constructor of the static tables checks it.
 */
final class JumpGraph {

    private static final int[] STEP = LinearEvaluator.STEP;
    private static final int[][] DIST = LinearEvaluator.DIST[0];
    private static final long ALLOWED_LO;
    private static final long ALLOWED_HI;
    /** Per player and cell: the allowed neighbours closer to the goal (one step gains exactly 1), as a 128-bit set. */
    private static final long[][] CLOSER_LO = new long[2][Geometry.CELLS];
    private static final long[][] CLOSER_HI = new long[2][Geometry.CELLS];

    static {
        Layout layout = Layout.of(2);
        long lo = 0;
        long hi = 0;
        for (int c = 0; c < Geometry.CELLS; c++) {
            if (layout.allowed(0, c) != layout.allowed(1, c)) {
                throw new AssertionError("two-player layout: players may stand on different cells");
            }
            if (layout.allowed(0, c)) {
                if (c < 64) {
                    lo |= 1L << c;
                } else {
                    hi |= 1L << c; // the shift uses c mod 64
                }
            }
        }
        ALLOWED_LO = lo;
        ALLOWED_HI = hi;
        for (int p = 0; p < 2; p++) {
            for (int c = 0; c < Geometry.CELLS; c++) {
                if (!layout.allowed(p, c)) {
                    continue;
                }
                for (int d = 0; d < Geometry.DIRECTIONS; d++) {
                    int a = STEP[c * Geometry.DIRECTIONS + d];
                    if (a < 0 || !layout.allowed(p, a)) {
                        continue;
                    }
                    int diff = DIST[p][c] - DIST[p][a];
                    if (Math.abs(diff) > 1) {
                        throw new AssertionError("neighbouring cells " + c + " and " + a + " differ by " + diff);
                    }
                    if (diff == 1) {
                        if (a < 64) {
                            CLOSER_LO[p][c] |= 1L << a;
                        } else {
                            CLOSER_HI[p][c] |= 1L << a;
                        }
                    }
                }
            }
        }
    }

    /** Occupied cells (any player) as a 128-bit set. */
    private long occLo;
    private long occHi;
    /** Per cell X: the landings Y such that the cell between X and Y is occupied. */
    private final long[] adjLo = new long[Geometry.CELLS];
    private final long[] adjHi = new long[Geometry.CELLS];

    // the labelling of the current position
    private long freeLo;
    private long freeHi;
    private int generation = 1;
    private final int[] stamp = new int[Geometry.CELLS];
    private final byte[] component = new byte[Geometry.CELLS];
    private int components;
    /** Per component: the smallest distance of player 0 and of player 1 over its cells. */
    private final int[] minDist0 = new int[Geometry.CELLS];
    private final int[] minDist1 = new int[Geometry.CELLS];

    /** Empties the board. */
    void clear() {
        occLo = 0;
        occHi = 0;
        Arrays.fill(adjLo, 0L);
        Arrays.fill(adjHi, 0L);
    }

    /** Fills the cell x if it is empty, else empties it; flipping the same cell again restores the graph. */
    void toggle(int x) {
        if (x < 64) {
            occLo ^= 1L << x;
        } else {
            occHi ^= 1L << x; // the shift uses x mod 64
        }
        for (int e = 0; e < Geometry.DIRECTIONS; e++) {
            int a = STEP[x * Geometry.DIRECTIONS + e];
            int b = STEP[x * Geometry.DIRECTIONS + (e ^ 1)];
            if (a >= 0 && b >= 0) {
                // the cell a jumps over x onto b
                if (b < 64) {
                    adjLo[a] ^= 1L << b;
                } else {
                    adjHi[a] ^= 1L << b;
                }
            }
        }
    }

    /** Whether the cell is occupied; for checks. */
    boolean occupied(int c) {
        return ((c < 64 ? occLo : occHi) >>> c & 1L) != 0;
    }

    /** Starts the labelling of the current position: no component known. */
    void begin() {
        freeLo = ALLOWED_LO & ~occLo;
        freeHi = ALLOWED_HI & ~occHi;
        components = 0;
        if (++generation == 0) {
            Arrays.fill(stamp, 0);
            generation = 1;
        }
    }

    /**
     * The best gain of player p (see {@link LinearEvaluator#bestGain}) from its pieces' {@code m} keys, packed
     * distance &lt;&lt; 8 | cell, falling; pieces in the goal come last with distance 0 and are never reached.
     * {@link #begin} must have been called for the position.
     */
    int bestGain(int p, int[] keys, int m) {
        long[] closerLo = CLOSER_LO[p];
        long[] closerHi = CLOSER_HI[p];
        int[] mins = p == 0 ? minDist0 : minDist1;
        long fLo = freeLo;
        long fHi = freeHi;
        int best = 0;
        for (int i = 0; i < m; i++) {
            int from = keys[i] & 0xFF;
            int d0 = keys[i] >>> 8;
            if (d0 <= best) {
                break; // every later piece is at most as far: none can gain more
            }
            // a step gains 1 at most (neighbouring cells differ by 1 in distance)
            if (best == 0 && ((closerLo[from] & fLo) | (closerHi[from] & fHi)) != 0) {
                best = 1;
            }
            long lo = adjLo[from] & fLo;
            long hi = adjHi[from] & fHi;
            while (lo != 0) {
                int land = Long.numberOfTrailingZeros(lo);
                lo &= lo - 1;
                int gain = d0 - mins[stamp[land] == generation ? component[land] : label(land)];
                if (gain > best) {
                    best = gain;
                }
            }
            while (hi != 0) {
                int land = 64 + Long.numberOfTrailingZeros(hi);
                hi &= hi - 1;
                int gain = d0 - mins[stamp[land] == generation ? component[land] : label(land)];
                if (gain > best) {
                    best = gain;
                }
            }
            if (best >= LinearEvaluator.GAIN_CAP) {
                return LinearEvaluator.GAIN_CAP;
            }
        }
        return best;
    }

    /** Labels the component of the free cell {@code start} with the next number and returns it. */
    private int label(int start) {
        int k = components++;
        int[] d0 = DIST[0];
        int[] d1 = DIST[1];
        long fLo = freeLo;
        long fHi = freeHi;
        long compLo = 0;
        long compHi = 0;
        long frontLo = 0;
        long frontHi = 0;
        if (start < 64) {
            compLo = 1L << start;
            frontLo = compLo;
        } else {
            compHi = 1L << start; // the shift uses start mod 64
            frontHi = compHi;
        }
        int low0 = Integer.MAX_VALUE;
        int low1 = Integer.MAX_VALUE;
        while ((frontLo | frontHi) != 0) {
            int c;
            if (frontLo != 0) {
                c = Long.numberOfTrailingZeros(frontLo);
                frontLo &= frontLo - 1;
            } else {
                c = 64 + Long.numberOfTrailingZeros(frontHi);
                frontHi &= frontHi - 1;
            }
            stamp[c] = generation;
            component[c] = (byte) k;
            low0 = Math.min(low0, d0[c]);
            low1 = Math.min(low1, d1[c]);
            long nLo = adjLo[c] & fLo & ~compLo;
            long nHi = adjHi[c] & fHi & ~compHi;
            compLo |= nLo;
            compHi |= nHi;
            frontLo |= nLo;
            frontHi |= nHi;
        }
        minDist0[k] = low0;
        minDist1[k] = low1;
        return k;
    }

    /** Throws if the graph differs from one built from the position's occupancy; for tests. */
    void verify(Position position) {
        JumpGraph fresh = new JumpGraph();
        for (int c = 0; c < Geometry.CELLS; c++) {
            if (position.owner(c) >= 0) {
                fresh.toggle(c);
            }
        }
        if (fresh.occLo != occLo || fresh.occHi != occHi || !Arrays.equals(fresh.adjLo, adjLo)
                || !Arrays.equals(fresh.adjHi, adjHi)) {
            throw new AssertionError("jump graph differs from the position's\n" + position);
        }
    }
}
