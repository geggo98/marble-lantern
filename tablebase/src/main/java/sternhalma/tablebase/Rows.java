package sternhalma.tablebase;

import sternhalma.engine.Geometry;
import sternhalma.engine.Layout;

/**
 * The row axis of the two-player game. Player 0 advances towards {@code r > 4}, player 1 towards {@code r < -4};
 * in a player's own orientation the row of a cell is {@code r} for player 0 and {@code -r} for player 1, so both
 * players advance towards higher rows, their goal corner lies on rows 5 to 8 and their start corner on rows
 * -8 to -5. The player symmetry (top-bottom flip, {@link Geometry#playerFlip}) maps player 1's orientation onto
 * player 0's: every cell that player 1 may enter maps to a cell that player 0 may enter, and distances agree.
 *
 * <p>Every neighbour direction changes the row by -1, 0 or +1 and every jump by -2, 0 or +2, and a jump that lands
 * two rows further needs its hurdle one row further ({@code GeometryRowAxisTest} checks this exhaustively). The
 * race adjudication of the search module rests on this property.
 */
public final class Rows {

    /** First row of the goal corner in a player's own orientation. */
    public static final int GOAL_ROW = 5;
    /** Last row of the board in a player's own orientation (the tip of the goal corner). */
    public static final int LAST_ROW = 8;

    private Rows() {
    }

    /** The row of a cell in the orientation of a player (two-player layout). */
    public static int row(int player, int cell) {
        int r = Geometry.r(cell);
        return player == 0 ? r : -r;
    }

    /** The cell in player 0's orientation that corresponds to {@code cell} in {@code player}'s orientation. */
    public static int toPlayer0(int player, int cell) {
        return player == 0 ? cell : Geometry.playerFlip(cell);
    }

    /** All cells of {@code player}'s configuration mapped to player 0's orientation, in ascending cell order. */
    public static int[] toPlayer0(int player, int[] cells) {
        if (player == 0) {
            return cells.clone();
        }
        int[] mapped = new int[cells.length];
        for (int i = 0; i < cells.length; i++) {
            mapped[i] = Geometry.playerFlip(cells[i]);
        }
        java.util.Arrays.sort(mapped);
        return mapped;
    }

    /** Whether the two-player layout lets {@code player} stand on {@code cell}. */
    public static boolean allowed(int player, int cell) {
        return Layout.of(2).allowed(player, cell);
    }

    /** The allowed cells of player 0 with row {@code >= minRow}, ascending. */
    public static int[] regionCells(int minRow) {
        Layout layout = Layout.of(2);
        int n = 0;
        int[] cells = new int[Geometry.CELLS];
        for (int c = 0; c < Geometry.CELLS; c++) {
            if (layout.allowed(0, c) && Geometry.r(c) >= minRow) {
                cells[n++] = c;
            }
        }
        return java.util.Arrays.copyOf(cells, n);
    }
}
