package sternhalma.engine;

import java.util.ArrayDeque;

/**
 * Start and goal corners, the cells each player may enter, and the single-agent distance tables.
 *
 * <p>Player 0 starts in the top corner (r &lt; -4). With three players the play order is clockwise
 * (docs/rules.md): top, lower right, lower left. Every goal is the corner opposite to the start corner.
 */
public final class Layout {

    private static final Layout TWO = new Layout(2);
    private static final Layout THREE = new Layout(3);

    private final int players;
    private final int[] startCorner;
    private final int[] goalCorner;
    private final int[] goalPlayer = new int[6];
    private final boolean[][] allowed;
    private final int[][] distance;
    private final int[] maxDistance;

    private Layout(int players) {
        this.players = players;
        this.startCorner = players == 2
                ? new int[] {Corner.R_NEG.ordinal(), Corner.R_POS.ordinal()}
                : new int[] {Corner.R_NEG.ordinal(), Corner.S_NEG.ordinal(), Corner.Q_NEG.ordinal()};
        this.goalCorner = new int[players];
        java.util.Arrays.fill(goalPlayer, -1);
        for (int p = 0; p < players; p++) {
            goalCorner[p] = Corner.ofIndex(startCorner[p]).opposite().ordinal();
            goalPlayer[goalCorner[p]] = p;
        }
        this.allowed = new boolean[players][Geometry.CELLS];
        this.distance = new int[players][Geometry.CELLS];
        this.maxDistance = new int[players];
        for (int p = 0; p < players; p++) {
            for (int c = 0; c < Geometry.CELLS; c++) {
                int corner = Geometry.cornerOf(c);
                allowed[p][c] = corner < 0 || corner == startCorner[p] || corner == goalCorner[p];
            }
            computeDistances(p);
        }
    }

    public static Layout of(int players) {
        return switch (players) {
            case 2 -> TWO;
            case 3 -> THREE;
            default -> throw new IllegalArgumentException("players must be 2 or 3, got " + players);
        };
    }

    /** Breadth-first search from the goal cells over the cells the player may enter. */
    private void computeDistances(int p) {
        int[] dist = distance[p];
        java.util.Arrays.fill(dist, -1);
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int c : Geometry.cornerCells(goalCorner[p])) {
            dist[c] = 0;
            queue.add(c);
        }
        while (!queue.isEmpty()) {
            int c = queue.poll();
            for (int d = 0; d < Geometry.DIRECTIONS; d++) {
                int n = Geometry.neighbor(c, d);
                if (n >= 0 && allowed[p][n] && dist[n] < 0) {
                    dist[n] = dist[c] + 1;
                    queue.add(n);
                }
            }
        }
        for (int c = 0; c < Geometry.CELLS; c++) {
            if (allowed[p][c]) {
                if (dist[c] < 0) {
                    throw new IllegalStateException("cell " + c + " cannot reach the goal of player " + p);
                }
                maxDistance[p] = Math.max(maxDistance[p], dist[c]);
            }
        }
    }

    public int players() {
        return players;
    }

    /** Clockwise index of the start corner of a player. */
    public int startCorner(int player) {
        return startCorner[player];
    }

    /** Clockwise index of the goal corner of a player. */
    public int goalCorner(int player) {
        return goalCorner[player];
    }

    /** The player whose goal is the given corner, or -1 if nobody's goal is there. */
    public int goalPlayer(int corner) {
        return corner < 0 ? -1 : goalPlayer[corner];
    }

    /** Whether the player may stand on the cell: central hexagon, own start corner or own goal corner. */
    public boolean allowed(int player, int cell) {
        return allowed[player][cell];
    }

    /**
     * Single-agent distance of a cell for a player: the number of steps to the nearest goal cell on an
     * empty board, over the cells the player may enter (docs/search-and-evaluation.md, stage 2).
     */
    public int distance(int player, int cell) {
        return distance[player][cell];
    }

    /** The largest distance in the table of a player. */
    public int maxDistance(int player) {
        return maxDistance[player];
    }
}
