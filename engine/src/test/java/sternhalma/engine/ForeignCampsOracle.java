package sternhalma.engine;

import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.TreeSet;

import sternhalma.engine.RuleSet.ForeignCamps;

/**
 * An independent definition of the move set under {@link ForeignCamps} (docs/rules.md, "Foreign corners"), written
 * from axial coordinates only: no layout tables, no neighbour tables, no depth-first search. The home cells of a
 * player are the central hexagon (|q|, |r|, |s| &lt;= 4) and the two corners that the rules document gives the player:
 * with two players r &lt; -4 and r &gt; 4 for both; with three players r &lt; -4 / r &gt; 4 (player 0), s &lt; -4 /
 * s &gt; 4 (player 1) and q &lt; -4 / q &gt; 4 (player 2). Every other corner cell is foreign.
 *
 * <ul>
 *   <li>CLOSED: steps and every landing of a chain only on home cells.</li>
 *   <li>PASS_THROUGH: steps only on home cells; a chain may land on any cell, but only home landings are moves.</li>
 *   <li>OPEN: steps and landings on any cell, and every landing is a move.</li>
 * </ul>
 *
 * The oracle ignores the leaveGoal and Definition-2 filters; tests switch them off or filter the result themselves.
 */
final class ForeignCampsOracle {

    private static final int[] DQ = {1, -1, 0, 0, 1, -1};
    private static final int[] DR = {0, 0, 1, -1, -1, 1};

    private ForeignCampsOracle() {
    }

    /** Whether the cell is a home cell of player {@code p} in a game of {@code players} players. */
    static boolean home(int players, int p, int cell) {
        int q = Geometry.q(cell);
        int r = Geometry.r(cell);
        int s = -q - r;
        if (Math.abs(q) <= 4 && Math.abs(r) <= 4 && Math.abs(s) <= 4) {
            return true;
        }
        int axis = players == 2 ? r : p == 0 ? r : p == 1 ? s : q;
        return Math.abs(axis) > 4;
    }

    /** All (from, to) pairs of the side to move as {@link Move} codes, ascending. */
    static TreeSet<Integer> moves(Position pos, boolean longHop, ForeignCamps camps) {
        int p = pos.sideToMove();
        int players = pos.rules().players();
        TreeSet<Integer> result = new TreeSet<>();
        for (int from : pos.pieces(p)) {
            for (int d = 0; d < 6; d++) {
                int n = Geometry.id(Geometry.q(from) + DQ[d], Geometry.r(from) + DR[d]);
                if (n >= 0 && pos.owner(n) < 0 && (camps == ForeignCamps.OPEN || home(players, p, n))) {
                    result.add(Move.of(from, n));
                }
            }
            BitSet seen = new BitSet(Geometry.CELLS);
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            seen.set(from);
            queue.add(from);
            while (!queue.isEmpty()) {
                int c = queue.poll();
                for (int d = 0; d < 6; d++) {
                    int land = landing(pos, from, c, d, longHop);
                    if (land < 0 || seen.get(land)) {
                        continue;
                    }
                    boolean isHome = home(players, p, land);
                    if (camps == ForeignCamps.CLOSED && !isHome) {
                        continue;
                    }
                    seen.set(land);
                    queue.add(land);
                    if (camps == ForeignCamps.OPEN || isHome) {
                        result.add(Move.of(from, land));
                    }
                }
            }
        }
        return result;
    }

    private static boolean empty(Position pos, int start, int cell) {
        return cell == start || pos.owner(cell) < 0;
    }

    /** The landing of a hop from {@code c} in direction {@code d}, on any board cell, or -1. */
    private static int landing(Position pos, int start, int c, int d, boolean longHop) {
        int q = Geometry.q(c);
        int r = Geometry.r(c);
        int maxK = longHop ? 8 : 1;
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
            return Geometry.id(q + 2 * k * DQ[d], r + 2 * k * DR[d]);
        }
        return -1;
    }
}
