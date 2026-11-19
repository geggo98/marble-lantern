package sternhalma.engine;

import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.TreeSet;

/**
 * An independent definition of the move set under {@link RuleSet.JumpRule#LONG}, written from the mirror
 * formulation and plain axial coordinates: no neighbour tables, no depth-first search, no packed visited set.
 * A piece on X hops in direction d over a piece P at distance k (k &gt;= 1) if the cells at distance 1..k-1 are
 * empty and the cells at distance k+1..2k are on the board and empty; the landing is the cell at distance 2k.
 * The start cell of the moving piece counts as empty. The oracle ignores the leaveGoal and Definition-2 filters
 * (tests use rules where those are off, or filter the result themselves). Its result is a set, so a cell that a
 * piece reaches by a step and by a chain of hops appears once.
 */
final class LongJumpOracle {

    private static final int[] DQ = {1, -1, 0, 0, 1, -1};
    private static final int[] DR = {0, 0, 1, -1, -1, 1};

    private LongJumpOracle() {
    }

    /** All (from, to) pairs of the side to move as {@link Move} codes, ascending; single jumps only when {@code longHop} is false. */
    static TreeSet<Integer> moves(Position pos, boolean longHop) {
        int p = pos.sideToMove();
        TreeSet<Integer> result = new TreeSet<>();
        for (int from : pos.pieces(p)) {
            for (int to : ends(pos, p, from, longHop)) {
                result.add(Move.of(from, to));
            }
        }
        return result;
    }

    /** The end cells of the piece of player {@code p} on {@code from}: steps, then everything a chain of hops reaches. */
    static TreeSet<Integer> ends(Position pos, int p, int from, boolean longHop) {
        TreeSet<Integer> ends = new TreeSet<>();
        for (int d = 0; d < 6; d++) {
            int n = Geometry.id(Geometry.q(from) + DQ[d], Geometry.r(from) + DR[d]);
            if (n >= 0 && pos.owner(n) < 0 && pos.layout().allowed(p, n)) {
                ends.add(n);
            }
        }
        BitSet seen = new BitSet(Geometry.CELLS);
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        seen.set(from);
        queue.add(from);
        while (!queue.isEmpty()) {
            int c = queue.poll();
            for (int d = 0; d < 6; d++) {
                int land = landing(pos, p, from, c, d, longHop);
                if (land >= 0 && !seen.get(land)) {
                    seen.set(land);
                    queue.add(land);
                    ends.add(land);
                }
            }
        }
        return ends;
    }

    private static boolean empty(Position pos, int start, int cell) {
        return cell == start || pos.owner(cell) < 0;
    }

    private static int landing(Position pos, int p, int start, int c, int d, boolean longHop) {
        int q = Geometry.q(c);
        int r = Geometry.r(c);
        int maxK = longHop ? 8 : 1; // 2k cells fit on a line of 17 cells at most
        for (int k = 1; k <= maxK; k++) {
            int over = Geometry.id(q + k * DQ[d], r + k * DR[d]);
            if (over < 0) {
                return -1;
            }
            if (empty(pos, start, over)) {
                continue;
            }
            for (int i = k + 1; i <= 2 * k; i++) {
                int cell = Geometry.id(q + i * DQ[d], r + i * DR[d]);
                if (cell < 0 || !empty(pos, start, cell)) {
                    return -1;
                }
            }
            int land = Geometry.id(q + 2 * k * DQ[d], r + 2 * k * DR[d]);
            return pos.layout().allowed(p, land) ? land : -1;
        }
        return -1;
    }
}
