package sternhalma.engine;

import java.util.ArrayDeque;

/**
 * Start and goal corners, the cells each player may enter, and the single-agent distance tables.
 *
 * <p>Player 0 starts in the top corner (r &lt; -4, or r &lt;= -4 with 5-row houses). With three players the play order is clockwise
 * (docs/rules.md): top, lower right, lower left. Every goal is the corner opposite to the start corner.
 */
public final class Layout {

    private static final Layout[][] CACHE = new Layout[2][4];

    static {
        for (RuleSet.TriangleRows rows : RuleSet.TriangleRows.values()) {
            CACHE[rows.ordinal()][2] = new Layout(2, rows);
            CACHE[rows.ordinal()][3] = new Layout(3, rows);
        }
    }

    private final int players;
    private final RuleSet.TriangleRows rows;
    /** Per player: whether the cell is in the player's start house, and in the goal house. They overlap across players. */
    private final boolean[][] inStart;
    private final boolean[][] inGoal;
    private final int[][] startCells;
    private final int[][] goalCells;
    private final int[] startCorner;
    private final int[] goalCorner;
    private final int[] goalPlayer = new int[6];
    /** The player whose goal holds the cell, per cell, or -1. */
    private final int[] goalPlayerOfCell = new int[Geometry.CELLS];
    private final boolean[][] allowed;
    /** Per player, index cell * 6 + direction: the neighbour if the player may enter it, else -1. */
    private final int[][] stepTo;
    /** Per player, index cell * 6 + direction: the jump landing if the player may enter it, else -1. */
    private final int[][] jumpTo;
    private final int[][] distance;
    private final int[] maxDistance;

    private Layout(int players, RuleSet.TriangleRows rows) {
        this.players = players;
        this.rows = rows;
        this.startCorner = players == 2
                ? new int[] {Corner.R_NEG.ordinal(), Corner.R_POS.ordinal()}
                : new int[] {Corner.R_NEG.ordinal(), Corner.S_NEG.ordinal(), Corner.Q_NEG.ordinal()};
        this.goalCorner = new int[players];
        java.util.Arrays.fill(goalPlayer, -1);
        for (int p = 0; p < players; p++) {
            goalCorner[p] = Corner.ofIndex(startCorner[p]).opposite().ordinal();
            goalPlayer[goalCorner[p]] = p;
        }
        // Houses: per player a start and a goal house of rows.rows() rows. Under FIVE two houses of adjacent corners
        // share the cell at a hexagon vertex, so membership is per player, never one corner index per cell.
        this.inStart = new boolean[players][Geometry.CELLS];
        this.inGoal = new boolean[players][Geometry.CELLS];
        this.startCells = new int[players][];
        this.goalCells = new int[players][];
        java.util.Arrays.fill(goalPlayerOfCell, -1);
        for (int p = 0; p < players; p++) {
            for (int c = 0; c < Geometry.CELLS; c++) {
                inStart[p][c] = Geometry.inHouse(startCorner[p], c, rows.rows());
                inGoal[p][c] = Geometry.inHouse(goalCorner[p], c, rows.rows());
                if (inGoal[p][c]) {
                    if (goalPlayerOfCell[c] >= 0) {
                        throw new IllegalStateException("goal houses overlap at cell " + c);
                    }
                    goalPlayerOfCell[c] = p;
                }
            }
            startCells[p] = cellsOf(inStart[p]);
            goalCells[p] = cellsOf(inGoal[p]);
            if (startCells[p].length != rows.pieces() || goalCells[p].length != rows.pieces()) {
                throw new IllegalStateException("house of player " + p + " has " + startCells[p].length + "/"
                        + goalCells[p].length + " cells, expected " + rows.pieces());
            }
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
        this.stepTo = new int[players][Geometry.CELLS * Geometry.DIRECTIONS];
        this.jumpTo = new int[players][Geometry.CELLS * Geometry.DIRECTIONS];
        for (int p = 0; p < players; p++) {
            for (int i = 0; i < Geometry.CELLS * Geometry.DIRECTIONS; i++) {
                int step = Geometry.STEP[i];
                int land = Geometry.LAND[i];
                stepTo[p][i] = step >= 0 && allowed[p][step] ? step : -1;
                jumpTo[p][i] = land >= 0 && allowed[p][land] ? land : -1;
            }
        }
    }

    private static int[] cellsOf(boolean[] mask) {
        int n = 0;
        for (boolean b : mask) {
            if (b) {
                n++;
            }
        }
        int[] cells = new int[n];
        int i = 0;
        for (int c = 0; c < mask.length; c++) {
            if (mask[c]) {
                cells[i++] = c;
            }
        }
        return cells;
    }

    /** The layout with 4-row houses. */
    public static Layout of(int players) {
        return of(players, RuleSet.TriangleRows.FOUR);
    }

    public static Layout of(int players, RuleSet.TriangleRows rows) {
        if (players != 2 && players != 3) {
            throw new IllegalArgumentException("players must be 2 or 3, got " + players);
        }
        return CACHE[rows.ordinal()][players];
    }

    /** Breadth-first search from the goal cells over the cells the player may enter. */
    private void computeDistances(int p) {
        int[] dist = distance[p];
        java.util.Arrays.fill(dist, -1);
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int c : goalCells[p]) {
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
        // Foreign corners (RuleSet.ForeignCamps): a piece of p stands there only under OPEN. Their distance is the
        // number of steps to the nearest goal cell over all board cells; the cells above keep their values.
        int[] all = new int[Geometry.CELLS];
        java.util.Arrays.fill(all, -1);
        for (int c : goalCells[p]) {
            all[c] = 0;
            queue.add(c);
        }
        while (!queue.isEmpty()) {
            int c = queue.poll();
            for (int d = 0; d < Geometry.DIRECTIONS; d++) {
                int n = Geometry.neighbor(c, d);
                if (n >= 0 && all[n] < 0) {
                    all[n] = all[c] + 1;
                    queue.add(n);
                }
            }
        }
        for (int c = 0; c < Geometry.CELLS; c++) {
            if (!allowed[p][c]) {
                dist[c] = all[c];
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

    /** The house size of this layout. */
    public RuleSet.TriangleRows rows() {
        return rows;
    }

    /** Pieces per player, which is the number of cells of a house: 10 or 15. */
    public int pieces() {
        return rows.pieces();
    }

    /** Cells of the goal house; a goal is full when this many cells hold a piece. Same as {@link #pieces()}. */
    public int goalSize() {
        return rows.pieces();
    }

    /** Whether the cell is in the start house of the player. */
    public boolean inStart(int player, int cell) {
        return inStart[player][cell];
    }

    /** Whether the cell is in the goal house of the player. */
    public boolean inGoal(int player, int cell) {
        return inGoal[player][cell];
    }

    /** The goal-house membership of a player per cell. Shared, read-only. */
    boolean[] goalMask(int player) {
        return inGoal[player];
    }

    /** The cells of the start house of a player in ascending order, as a new array. */
    public int[] startCells(int player) {
        return startCells[player].clone();
    }

    /** The cells of the goal house of a player in ascending order, as a new array. */
    public int[] goalCells(int player) {
        return goalCells[player].clone();
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

    /** The player whose goal house contains the cell, or -1 (nobody's goal). */
    public int goalPlayerOfCell(int cell) {
        return goalPlayerOfCell[cell];
    }

    /** Flat step table of a player with the allowed cells folded in (see {@link #stepTo}). Shared, read-only. */
    int[] stepTable(int player) {
        return stepTo[player];
    }

    /** Flat jump-landing table of a player with the allowed cells folded in. Shared, read-only. */
    int[] jumpTable(int player) {
        return jumpTo[player];
    }

    /**
     * Whether the cell is a home cell of the player: central hexagon, own start corner or own goal corner. Under
     * {@link RuleSet.ForeignCamps#CLOSED} and {@code PASS_THROUGH} a move ends only on such a cell.
     */
    public boolean allowed(int player, int cell) {
        return allowed[player][cell];
    }

    /**
     * Single-agent distance of a cell for a player: the number of steps to the nearest goal cell on an
     * empty board, over the home cells of the player (docs/search-and-evaluation.md, stage 2). A cell of a foreign
     * corner, where a piece stands only under {@link RuleSet.ForeignCamps#OPEN}, gets the number of steps over all
     * cells.
     */
    public int distance(int player, int cell) {
        return distance[player][cell];
    }

    /** The largest distance of a home cell of the player. */
    public int maxDistance(int player) {
        return maxDistance[player];
    }
}
