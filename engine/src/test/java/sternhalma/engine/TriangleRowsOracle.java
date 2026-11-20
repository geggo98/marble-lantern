package sternhalma.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import sternhalma.engine.RuleSet.ForeignCamps;

/**
 * An independent definition of houses, moves and wins for {@link RuleSet.TriangleRows} (docs/rules.md, "Board size"),
 * written from cube coordinates (q, r, s = -q - r) only. It uses no {@link Layout}, no {@link Geometry} table and
 * no engine move generation; even the cell numbering is its own (cells by ascending r, then ascending q), and
 * {@code TriangleRowsGoldenTest} checks that it equals {@link Geometry#id}.
 *
 * <p><b>Houses.</b> A corner has an axis a (one of q, r, s) and a sign σ (+1 or -1); the other two axes are b and c. A
 * house of n rows (4 or 5) is the set of board cells with {@code σ·a >= 9 - n}, {@code σ·b <= 0} and
 * {@code σ·c <= 0}. With n = 4 that is the 10-cell corner triangle; with n = 5 it adds the 5 hexagon cells of the
 * row {@code σ·a = 4}. The two side conditions keep out the cells of that row that belong to the neighbouring corner
 * triangles. The test does not look at the corner tables, so a wrong house in the engine shows as a difference.
 *
 * <p>Start corners (docs/rules.md): two players r&lt;0 side (player 0) and r&gt;0 side (player 1); three players
 * r&lt;0 side, s&lt;0 side, q&lt;0 side. The goal is the opposite corner, that is, the same axis with the other sign.
 *
 * <p><b>Moves</b> under the project defaults (leaveGoal FORBIDDEN, suicideMoves ILLEGAL, blockade OPPONENT_FILLS):
 * <ul>
 *   <li>a step to an empty neighbour, or a chain of hops (ADJACENT: over a neighbour; LONG: the Halma long hop over
 *       one piece at distance k to the cell at distance 2k, with the cells between empty); the moving piece's own
 *       start cell counts as empty;</li>
 *   <li>foreign corners stay the 10-cell corner triangles (σ·a &gt;= 5) of corners that are neither the player's start
 *       nor goal corner, under {@link ForeignCamps} CLOSED, PASS_THROUGH and OPEN as in {@link ForeignCampsOracle};</li>
 *   <li>a piece in its own goal house must end its move in the goal house (the chain may leave it in between);</li>
 *   <li>a move is illegal if afterwards an opponent's goal house is full and holds at least one piece of that
 *       opponent.</li>
 * </ul>
 */
final class TriangleRowsOracle {

    /** Board radius of the central hexagon. */
    private static final int HEX = 4;
    private static final int[] DQ = {1, -1, 0, 0, 1, -1};
    private static final int[] DR = {0, 0, 1, -1, -1, 1};

    /** Own numbering: cell id to (q, r), and (q + 8, r + 8) to cell id or -1. */
    private static final int[] Q;
    private static final int[] R;
    private static final int[][] ID = new int[17][17];

    static {
        List<int[]> cells = new ArrayList<>();
        for (int r = -8; r <= 8; r++) {
            for (int q = -8; q <= 8; q++) {
                ID[q + 8][r + 8] = -1;
                int s = -q - r;
                boolean onBoard = Math.max(q, Math.max(r, s)) <= HEX || Math.min(q, Math.min(r, s)) >= -HEX;
                if (onBoard && Math.abs(q) <= 8 && Math.abs(r) <= 8 && Math.abs(s) <= 8) {
                    cells.add(new int[] {q, r});
                }
            }
        }
        Q = new int[cells.size()];
        R = new int[cells.size()];
        for (int i = 0; i < cells.size(); i++) {
            Q[i] = cells.get(i)[0];
            R[i] = cells.get(i)[1];
            ID[Q[i] + 8][R[i] + 8] = i;
        }
    }

    /** A corner: axis 0 = q, 1 = r, 2 = s, and the sign of that axis inside the corner. */
    record Corner(int axis, int sign) {
        Corner opposite() {
            return new Corner(axis, -sign);
        }
    }

    private TriangleRowsOracle() {
    }

    static int cells() {
        return Q.length;
    }

    static int q(int cell) {
        return Q[cell];
    }

    static int r(int cell) {
        return R[cell];
    }

    /** The cell at (q, r), or -1 if it is not on the board. */
    static int id(int q, int r) {
        if (q < -8 || q > 8 || r < -8 || r > 8) {
            return -1;
        }
        return ID[q + 8][r + 8];
    }

    private static int coordinate(int cell, int axis) {
        return switch (axis) {
            case 0 -> Q[cell];
            case 1 -> R[cell];
            default -> -Q[cell] - R[cell];
        };
    }

    static Corner start(int players, int p) {
        if (players == 2) {
            return new Corner(1, p == 0 ? -1 : 1);
        }
        return new Corner(p == 0 ? 1 : p == 1 ? 2 : 0, -1);
    }

    static Corner goal(int players, int p) {
        return start(players, p).opposite();
    }

    /** Whether the cell is in the house of {@code rows} rows of the corner. */
    static boolean inHouse(Corner corner, int cell, int rows) {
        int sigma = corner.sign();
        if (sigma * coordinate(cell, corner.axis()) < 9 - rows) {
            return false;
        }
        for (int other = 0; other < 3; other++) {
            if (other != corner.axis() && sigma * coordinate(cell, other) > 0) {
                return false;
            }
        }
        return true;
    }

    /** The cells of the house, ascending. */
    static TreeSet<Integer> house(Corner corner, int rows) {
        TreeSet<Integer> cells = new TreeSet<>();
        for (int c = 0; c < Q.length; c++) {
            if (inHouse(corner, c, rows)) {
                cells.add(c);
            }
        }
        return cells;
    }

    /** Whether the player may rest on the cell: the hexagon and its own start and goal corner triangles. */
    static boolean home(int players, int p, int cell) {
        int q = Q[cell];
        int r = R[cell];
        int s = -q - r;
        if (Math.abs(q) <= HEX && Math.abs(r) <= HEX && Math.abs(s) <= HEX) {
            return true;
        }
        return inHouse(start(players, p), cell, 4) || inHouse(goal(players, p), cell, 4);
    }

    /**
     * Whether player {@code p} has won on the board {@code owner} (cell to player or -1): the goal house is full and
     * holds at least one piece of {@code p}.
     */
    static boolean wins(int[] owner, int players, int p, int rows) {
        boolean own = false;
        for (int c : house(goal(players, p), rows)) {
            if (owner[c] < 0) {
                return false;
            }
            own |= owner[c] == p;
        }
        return own;
    }

    /**
     * The legal moves of player {@code p} on the board {@code owner}, as {@link Move} codes (ascending by code, which is
     * by (to, from)), under the project defaults with the given jump rule, foreign-corner rule and house size.
     */
    static TreeSet<Integer> moves(int[] owner, int players, int p, boolean longHop, ForeignCamps camps, int rows) {
        return moves(owner, players, p, longHop, camps, rows, true);
    }

    /** {@link #moves}, with the suicide rule (Definition 2) switched off if {@code suicideIllegal} is false. */
    static TreeSet<Integer> moves(int[] owner, int players, int p, boolean longHop, ForeignCamps camps, int rows,
            boolean suicideIllegal) {
        Corner goal = goal(players, p);
        TreeSet<Integer> result = new TreeSet<>();
        for (int from = 0; from < owner.length; from++) {
            if (owner[from] != p) {
                continue;
            }
            TreeSet<Integer> ends = new TreeSet<>();
            for (int d = 0; d < 6; d++) {
                int n = id(Q[from] + DQ[d], R[from] + DR[d]);
                if (n >= 0 && owner[n] < 0 && (camps == ForeignCamps.OPEN || home(players, p, n))) {
                    ends.add(n);
                }
            }
            boolean[] seen = new boolean[Q.length];
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            seen[from] = true;
            queue.add(from);
            while (!queue.isEmpty()) {
                int c = queue.poll();
                for (int d = 0; d < 6; d++) {
                    int land = landing(owner, from, c, d, longHop);
                    if (land < 0 || seen[land]) {
                        continue;
                    }
                    boolean isHome = home(players, p, land);
                    if (camps == ForeignCamps.CLOSED && !isHome) {
                        continue;
                    }
                    seen[land] = true;
                    queue.add(land);
                    if (camps == ForeignCamps.OPEN || isHome) {
                        ends.add(land);
                    }
                }
            }
            boolean inGoal = inHouse(goal, from, rows);
            for (int to : ends) {
                if (inGoal && !inHouse(goal, to, rows)) {
                    continue; // leaveGoal FORBIDDEN
                }
                owner[from] = -1;
                owner[to] = p;
                boolean handsAWin = false;
                for (int o = 0; o < players; o++) {
                    handsAWin |= o != p && wins(owner, players, o, rows);
                }
                owner[to] = -1;
                owner[from] = p;
                if (!handsAWin || !suicideIllegal) { // suicideMoves ILLEGAL
                    result.add(Move.of(from, to));
                }
            }
        }
        return result;
    }

    private static boolean empty(int[] owner, int start, int cell) {
        return cell == start || owner[cell] < 0;
    }

    /** The landing of a hop from {@code c} in direction {@code d}, on any board cell, or -1. */
    private static int landing(int[] owner, int start, int c, int d, boolean longHop) {
        int maxK = longHop ? 8 : 1;
        for (int k = 1; k <= maxK; k++) {
            int over = id(Q[c] + k * DQ[d], R[c] + k * DR[d]);
            if (over < 0) {
                return -1;
            }
            if (empty(owner, start, over)) {
                continue;
            }
            for (int i = k + 1; i <= 2 * k; i++) {
                int cell = id(Q[c] + i * DQ[d], R[c] + i * DR[d]);
                if (cell < 0 || !empty(owner, start, cell)) {
                    return -1;
                }
            }
            return id(Q[c] + 2 * k * DQ[d], R[c] + 2 * k * DR[d]);
        }
        return -1;
    }
}
