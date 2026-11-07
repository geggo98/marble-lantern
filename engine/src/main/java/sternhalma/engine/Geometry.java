package sternhalma.engine;

/**
 * The 121-cell star board: cube/axial hex coordinates, neighbour tables and corner membership.
 *
 * <p>A cell (q, r) with s = -q - r is on the board if max(q, r, s) &lt;= 4 or min(q, r, s) &gt;= -4.
 * Cell ids run from 0 to 120, ordered by r and then by q.
 */
public final class Geometry {

    public static final int CELLS = 121;
    public static final int DIRECTIONS = 6;
    /** Cells per corner triangle. */
    public static final int CORNER_SIZE = 10;

    /** Direction vectors; directions d and d ^ 1 are opposite. */
    private static final int[] DQ = {1, -1, 0, 0, 1, -1};
    private static final int[] DR = {0, 0, 1, -1, -1, 1};

    private static final int OFFSET = 8;
    private static final int SIDE = 2 * OFFSET + 1;

    private static final int[] Q = new int[CELLS];
    private static final int[] R = new int[CELLS];
    private static final int[] ID = new int[SIDE * SIDE];
    private static final int[][] NEIGHBOR = new int[CELLS][DIRECTIONS];
    private static final int[] CORNER = new int[CELLS];
    private static final int[][] CORNER_CELLS = new int[6][];
    private static final int[] MIRROR = new int[CELLS];
    private static final int[] PLAYER_FLIP = new int[CELLS];
    private static final int[] ROTATE_180 = new int[CELLS];
    /** Flat neighbour table: {@code STEP[c * DIRECTIONS + d]} is {@link #neighbor}(c, d). For the engine's hot loops. */
    static final int[] STEP = new int[CELLS * DIRECTIONS];
    /** Flat jump-landing table: the neighbour of the neighbour in the same direction, or -1 off the board. */
    static final int[] LAND = new int[CELLS * DIRECTIONS];

    static {
        java.util.Arrays.fill(ID, -1);
        int n = 0;
        for (int r = -OFFSET; r <= OFFSET; r++) {
            for (int q = -OFFSET; q <= OFFSET; q++) {
                if (onBoard(q, r)) {
                    Q[n] = q;
                    R[n] = r;
                    ID[index(q, r)] = n;
                    n++;
                }
            }
        }
        if (n != CELLS) {
            throw new IllegalStateException("board has " + n + " cells, expected " + CELLS);
        }
        int[] cornerCount = new int[6];
        for (int c = 0; c < CELLS; c++) {
            for (int d = 0; d < DIRECTIONS; d++) {
                NEIGHBOR[c][d] = id(Q[c] + DQ[d], R[c] + DR[d]);
            }
            CORNER[c] = cornerIndex(Q[c], R[c], -Q[c] - R[c]);
            if (CORNER[c] >= 0) {
                cornerCount[CORNER[c]]++;
            }
        }
        for (int k = 0; k < 6; k++) {
            if (cornerCount[k] != CORNER_SIZE) {
                throw new IllegalStateException("corner " + k + " has " + cornerCount[k] + " cells");
            }
            CORNER_CELLS[k] = new int[CORNER_SIZE];
        }
        int[] fill = new int[6];
        for (int c = 0; c < CELLS; c++) {
            if (CORNER[c] >= 0) {
                CORNER_CELLS[CORNER[c]][fill[CORNER[c]]++] = c;
            }
        }
        for (int c = 0; c < CELLS; c++) {
            int q = Q[c];
            int r = R[c];
            int s = -q - r;
            MIRROR[c] = id(s, r);            // left-right flip: (q, r, s) -> (s, r, q)
            PLAYER_FLIP[c] = id(-s, -r);     // player symmetry: (q, r, s) -> (-s, -r, -q)
            ROTATE_180[c] = id(-q, -r);      // 180-degree rotation
        }
        for (int c = 0; c < CELLS; c++) {
            for (int d = 0; d < DIRECTIONS; d++) {
                int over = NEIGHBOR[c][d];
                STEP[c * DIRECTIONS + d] = over;
                LAND[c * DIRECTIONS + d] = over < 0 ? -1 : NEIGHBOR[over][d];
            }
        }
    }

    private Geometry() {
    }

    private static int index(int q, int r) {
        return (r + OFFSET) * SIDE + (q + OFFSET);
    }

    private static boolean onBoard(int q, int r) {
        int s = -q - r;
        int max = Math.max(q, Math.max(r, s));
        int min = Math.min(q, Math.min(r, s));
        return max <= 4 || min >= -4;
    }

    /** Clockwise corner index (see {@link Corner}) or -1 for the central hexagon. */
    private static int cornerIndex(int q, int r, int s) {
        if (q > 4) {
            return Corner.Q_POS.ordinal();
        }
        if (s < -4) {
            return Corner.S_NEG.ordinal();
        }
        if (r > 4) {
            return Corner.R_POS.ordinal();
        }
        if (q < -4) {
            return Corner.Q_NEG.ordinal();
        }
        if (s > 4) {
            return Corner.S_POS.ordinal();
        }
        if (r < -4) {
            return Corner.R_NEG.ordinal();
        }
        return -1;
    }

    /** Cell id for axial coordinates, or -1 if the cell is off the board. */
    public static int id(int q, int r) {
        if (q < -OFFSET || q > OFFSET || r < -OFFSET || r > OFFSET) {
            return -1;
        }
        return ID[index(q, r)];
    }

    public static int q(int cell) {
        return Q[cell];
    }

    public static int r(int cell) {
        return R[cell];
    }

    public static int s(int cell) {
        return -Q[cell] - R[cell];
    }

    /** Neighbour of a cell in a direction (0 to 5), or -1 off the board. */
    public static int neighbor(int cell, int direction) {
        return NEIGHBOR[cell][direction];
    }

    /** The cell two steps away in a direction (the landing cell of a single jump), or -1 off the board. */
    public static int jumpLanding(int cell, int direction) {
        return LAND[cell * DIRECTIONS + direction];
    }

    /** Clockwise index of the corner that contains the cell, or -1 for the central hexagon. */
    public static int cornerOf(int cell) {
        return CORNER[cell];
    }

    /** The 10 cells of a corner. The returned array must not be modified. */
    public static int[] cornerCells(int cornerIndex) {
        return CORNER_CELLS[cornerIndex];
    }

    /** Left-right flip, (q, r, s) to (s, r, q). */
    public static int mirror(int cell) {
        return MIRROR[cell];
    }

    /** Player symmetry (top-bottom flip), (q, r, s) to (-s, -r, -q). */
    public static int playerFlip(int cell) {
        return PLAYER_FLIP[cell];
    }

    /** 180-degree rotation, (q, r, s) to (-q, -r, -s). */
    public static int rotate180(int cell) {
        return ROTATE_180[cell];
    }
}
