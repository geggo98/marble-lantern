package sternhalma.nn;

import sternhalma.engine.Geometry;

/**
 * The gather table of the hex convolution (docs/network.md, "Gather table").
 *
 * <p>For every cell the table lists 7 rows of the activation array: entry 0 is the cell itself, entry k for
 * k = 1 to 6 is {@code Geometry.neighbor(cell, k - 1)}, or {@link #PADDING} if that neighbour is off the
 * board. Activations have {@link #ROWS} rows; row {@link #PADDING} is always zero.
 *
 * <p>The table is the same for every player count and every mover, because the network sees canonical
 * cells only (see {@link Orientation}).
 */
public final class HexGrid {

    /** Board cells; rows 0 to {@code CELLS - 1} of an activation. */
    public static final int CELLS = Geometry.CELLS;
    /** Index of the zero padding row. */
    public static final int PADDING = CELLS;
    /** Rows of an activation array: the cells plus the padding row. */
    public static final int ROWS = CELLS + 1;
    /** Entries per cell in the gather table: the cell and its 6 neighbours. */
    public static final int STENCIL = 1 + Geometry.DIRECTIONS;

    /** The table, flat as [cell][k]. Never handed out; {@link #gatherTable()} returns a copy. */
    private static final int[] TABLE = new int[CELLS * STENCIL];

    static {
        for (int cell = 0; cell < CELLS; cell++) {
            TABLE[cell * STENCIL] = cell;
            for (int d = 0; d < Geometry.DIRECTIONS; d++) {
                int n = Geometry.neighbor(cell, d);
                TABLE[cell * STENCIL + 1 + d] = n < 0 ? PADDING : n;
            }
        }
    }

    private HexGrid() {
    }

    /**
     * Gather entry k of a cell.
     *
     * @param cell a cell from 0 to 120
     * @param k    0 for the cell itself, 1 to 6 for the neighbour in Geometry direction k - 1
     * @return a row from 0 to 121; 121 ({@link #PADDING}) for a neighbour off the board
     * @throws IndexOutOfBoundsException if cell or k is out of range
     */
    public static int gather(int cell, int k) {
        // Both checks are needed: cell * STENCIL + k alone would accept (cell 0, k 7) as (cell 1, k 0).
        java.util.Objects.checkIndex(cell, CELLS);
        java.util.Objects.checkIndex(k, STENCIL);
        return TABLE[cell * STENCIL + k];
    }

    /**
     * The whole table as a new flat array of {@code CELLS * STENCIL} entries; entry {@code cell * STENCIL + k}
     * equals {@link #gather(int, int) gather(cell, k)}. Kernels upload this array.
     */
    public static int[] gatherTable() {
        return TABLE.clone();
    }
}
