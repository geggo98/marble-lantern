package sternhalma.tablebase;

import sternhalma.engine.Geometry;
import sternhalma.engine.Layout;

/**
 * Solitaire move generation for player 0 on a bitboard: one player alone on the board, own pieces as the only
 * hurdles, landing cells restricted to the cells the player may enter. Steps and jump chains follow
 * docs/rules.md; the moving piece leaves its cell first and may not land on it again. Intermediate landings of a
 * chain may lie anywhere on the board (the path of a chain has no effect on legality), only the end cell is
 * filtered by the caller.
 *
 * <p>Occupancy is two {@code long}s: bit {@code c} of {@code lo} for cells 0..63, bit {@code c - 64} of
 * {@code hi} for cells 64..120.
 */
final class SoloMoves {

    static final int[][] NEIGHBOR = new int[Geometry.CELLS][Geometry.DIRECTIONS];
    static final boolean[] ALLOWED = new boolean[Geometry.CELLS];
    static final int GOAL_CORNER = Layout.of(2).goalCorner(0);

    static {
        Layout layout = Layout.of(2);
        for (int c = 0; c < Geometry.CELLS; c++) {
            ALLOWED[c] = layout.allowed(0, c);
            for (int d = 0; d < Geometry.DIRECTIONS; d++) {
                NEIGHBOR[c][d] = Geometry.neighbor(c, d);
            }
        }
    }

    /** A consumer of landing cells. */
    interface Landing {
        void accept(int cell);
    }

    private final int[] stack = new int[Geometry.CELLS];

    static boolean has(long lo, long hi, int cell) {
        return cell < 64 ? (lo >>> cell & 1L) != 0 : (hi >>> (cell - 64) & 1L) != 0;
    }

    static long withLo(long lo, int cell) {
        return cell < 64 ? lo | 1L << cell : lo;
    }

    static long withHi(long hi, int cell) {
        return cell >= 64 ? hi | 1L << (cell - 64) : hi;
    }

    static long withoutLo(long lo, int cell) {
        return cell < 64 ? lo & ~(1L << cell) : lo;
    }

    static long withoutHi(long hi, int cell) {
        return cell >= 64 ? hi & ~(1L << (cell - 64)) : hi;
    }

    /**
     * Calls {@code out} once for every cell the piece on {@code from} can reach by a step or a jump chain, with
     * {@code lo, hi} the occupancy of all pieces (including the moving piece, which is removed internally).
     */
    void landings(long lo, long hi, int from, Landing out) {
        long occLo = withoutLo(lo, from);
        long occHi = withoutHi(hi, from);
        int[] nb = NEIGHBOR[from];
        for (int d = 0; d < Geometry.DIRECTIONS; d++) {
            int n = nb[d];
            if (n >= 0 && ALLOWED[n] && !has(occLo, occHi, n)) {
                out.accept(n);
            }
        }
        long visLo = withLo(0, from);
        long visHi = withHi(0, from);
        int top = 0;
        stack[top++] = from;
        while (top > 0) {
            int c = stack[--top];
            int[] n2 = NEIGHBOR[c];
            for (int d = 0; d < Geometry.DIRECTIONS; d++) {
                int over = n2[d];
                if (over < 0 || !has(occLo, occHi, over)) {
                    continue;
                }
                int land = NEIGHBOR[over][d];
                if (land < 0 || has(occLo, occHi, land) || !ALLOWED[land] || has(visLo, visHi, land)) {
                    continue;
                }
                visLo = withLo(visLo, land);
                visHi = withHi(visHi, land);
                stack[top++] = land;
                out.accept(land);
            }
        }
    }
}
