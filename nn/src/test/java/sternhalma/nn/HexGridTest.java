package sternhalma.nn;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import sternhalma.engine.Geometry;

class HexGridTest {

    /** Geometry direction order of docs/network.md, "Gather table", written out independently of the engine. */
    private static final int[][] DIRECTION = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, -1}, {-1, 1}};

    @Test
    void constants() {
        assertEquals(121, HexGrid.CELLS);
        assertEquals(121, HexGrid.PADDING);
        assertEquals(122, HexGrid.ROWS);
        assertEquals(7, HexGrid.STENCIL);
    }

    /** Every entry against Geometry.neighbor and against the axial direction vectors of the design document. */
    @Test
    void everyEntryMatchesGeometry() {
        for (int cell = 0; cell < HexGrid.CELLS; cell++) {
            assertEquals(cell, HexGrid.gather(cell, 0), "entry 0 of cell " + cell);
            for (int k = 1; k < HexGrid.STENCIL; k++) {
                int n = Geometry.neighbor(cell, k - 1);
                int expected = n < 0 ? HexGrid.PADDING : n;
                assertEquals(expected, HexGrid.gather(cell, k), "cell " + cell + ", k " + k);

                int byVector = Geometry.id(Geometry.q(cell) + DIRECTION[k - 1][0],
                        Geometry.r(cell) + DIRECTION[k - 1][1]);
                assertEquals(byVector < 0 ? HexGrid.PADDING : byVector, HexGrid.gather(cell, k),
                        "cell " + cell + ", k " + k + " against the direction vector");
            }
        }
    }

    /** Hand-derived: cell 0 = (4, -8) is the tip of R_NEG; only (4, -7) = 2 and (3, -7) = 1 are on the board. */
    @Test
    void cornerTipByHand() {
        assertArrayEquals(new int[] {0, 121, 121, 2, 121, 121, 1}, row(0));
        // The centre (0, 0) has all six neighbours: (1,0), (-1,0), (0,1), (0,-1), (1,-1), (-1,1).
        int centre = Geometry.id(0, 0);
        assertArrayEquals(new int[] {centre, Geometry.id(1, 0), Geometry.id(-1, 0), Geometry.id(0, 1),
                Geometry.id(0, -1), Geometry.id(1, -1), Geometry.id(-1, 1)}, row(centre));
    }

    /** The counts of docs/network.md: 73 x 6, 6 x 5, 36 x 4, 6 x 2 neighbours; 624 neighbour and 102 padding entries. */
    @Test
    void neighbourCountsArePinned() {
        int[] cellsWithNeighbours = new int[7];
        int neighbours = 0;
        int padding = 0;
        for (int cell = 0; cell < HexGrid.CELLS; cell++) {
            int count = 0;
            for (int k = 1; k < HexGrid.STENCIL; k++) {
                if (HexGrid.gather(cell, k) == HexGrid.PADDING) {
                    padding++;
                } else {
                    count++;
                }
            }
            cellsWithNeighbours[count]++;
            neighbours += count;
        }
        assertArrayEquals(new int[] {0, 0, 6, 0, 36, 6, 73}, cellsWithNeighbours);
        assertEquals(624, neighbours);
        assertEquals(102, padding);
        assertEquals(HexGrid.CELLS * HexGrid.STENCIL, HexGrid.CELLS + neighbours + padding);
    }

    /** Directions d and d ^ 1 are opposite, so stepping back along entry k returns to the cell. */
    @Test
    void oppositeEntriesStepBack() {
        for (int cell = 0; cell < HexGrid.CELLS; cell++) {
            for (int k = 1; k < HexGrid.STENCIL; k++) {
                int n = HexGrid.gather(cell, k);
                if (n != HexGrid.PADDING) {
                    int back = ((k - 1) ^ 1) + 1;
                    assertEquals(cell, HexGrid.gather(n, back), "cell " + cell + ", k " + k);
                    assertNotEquals(cell, n);
                }
            }
        }
    }

    @Test
    void tableEqualsGatherAndIsACopy() {
        int[] table = HexGrid.gatherTable();
        assertEquals(HexGrid.CELLS * HexGrid.STENCIL, table.length);
        for (int cell = 0; cell < HexGrid.CELLS; cell++) {
            for (int k = 0; k < HexGrid.STENCIL; k++) {
                assertEquals(HexGrid.gather(cell, k), table[cell * HexGrid.STENCIL + k]);
            }
        }
        table[0] = 99;
        table[3] = 99;
        assertEquals(0, HexGrid.gather(0, 0));
        assertEquals(2, HexGrid.gather(0, 3));
        assertEquals(0, HexGrid.gatherTable()[0]);
    }

    @Test
    void outOfRangeArgumentsAreRejected() {
        assertThrows(IndexOutOfBoundsException.class, () -> HexGrid.gather(-1, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> HexGrid.gather(121, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> HexGrid.gather(0, -1));
        // Would silently read entry 0 of cell 1 without a separate check of k.
        assertThrows(IndexOutOfBoundsException.class, () -> HexGrid.gather(0, 7));
        assertThrows(IndexOutOfBoundsException.class, () -> HexGrid.gather(1, -1));
    }

    private static int[] row(int cell) {
        int[] result = new int[HexGrid.STENCIL];
        for (int k = 0; k < HexGrid.STENCIL; k++) {
            result[k] = HexGrid.gather(cell, k);
        }
        return result;
    }
}
