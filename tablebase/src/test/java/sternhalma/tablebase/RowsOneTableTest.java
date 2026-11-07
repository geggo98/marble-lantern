package sternhalma.tablebase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Locale;

import org.junit.jupiter.api.Test;

import sternhalma.engine.Geometry;
import sternhalma.engine.Layout;

/**
 * The optional rows&gt;=1 table (round-2 experiment E09). Every test here is skipped with a message when the table is
 * not in the cache ({@code ./gradlew :tablebase:buildTable -Prows=1} builds it in a few minutes; the tests never
 * build it themselves). The checks follow the validation plan of docs/endgame-and-opening.md: the recorded checksum
 * and layer sizes, the committed rows&gt;=2 table as a reference on every state both tables define, layer
 * consistency with the engine's moves on a sample, and a forward breadth-first search on sampled configurations.
 * {@code TableValidation --rows 1 --samples 0 --compare} runs the layer check on every entry.
 */
class RowsOneTableTest {

    static RaceTable rows1() {
        RaceTable t = RaceTable.rows1IfCached();
        assumeTrue(t != null, "the rows>=1 table is not in the cache (tablebase/build/tables/rows1.bin or "
                + "-Dsternhalma.tablebase.dir); build it once with ./gradlew --no-build-cache :tablebase:buildTable -Prows=1");
        return t;
    }

    @Test
    void theCachedTableHasTheRecordedChecksumRegionAndLayerSizes() {
        RaceTable table = rows1();
        assertEquals(1, table.minRow());
        assertEquals(254_186_856L, table.states());
        assertEquals(RaceTable.ROWS1_SHA256, table.sha256Hex());
        int[] goal = Geometry.cornerCells(Layout.of(2).goalCorner(0));
        assertEquals(0, table.count(goal));
        long[] layers = table.layerSizes();
        assertEquals(0, layers[layers.length - 1], "every rows>=1 configuration reaches the goal inside the region");
        assertEquals(19, table.maxCount());
        assertArrayEquals(new long[] {1, 14, 164, 1493, 11353, 72696, 384397, 1638799, 5542557, 14761224, 30633718,
            48819428, 58547341, 50838402, 29862660, 10768087, 2108414, 190455, 5610, 43}, java.util.Arrays.copyOf(layers, 20));
        // a configuration with a piece on row 1 is covered here and not by the default table
        int[] cells = goal.clone();
        cells[0] = Geometry.id(-1, 1);
        assertTrue(Rows.allowed(0, cells[0]) && Geometry.r(cells[0]) == 1);
        assertFalse(RaceTable.rows2().covers(cells));
        assertTrue(table.covers(cells));
        assertTrue(table.count(cells) >= 1 && table.count(cells) <= 4, "count " + table.count(cells));
    }

    private static void assertArrayEquals(long[] expected, long[] actual) {
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual,
                "layer sizes " + java.util.Arrays.toString(actual));
    }

    /**
     * On every state of the rows&gt;=2 region the larger region can only shorten the route: the rows&gt;=1 entry is
     * never larger and never unknown. A share of the states is strictly shorter (a route through row 1).
     */
    @Test
    void theRowsOneEntryNeverExceedsTheRowsTwoEntryOnAnyOfTheThirteenMillionSharedStates() {
        TableValidation.Comparison c = TableValidation.compare(RaceTable.rows2(), rows1());
        System.out.printf(Locale.ROOT, "rows>=1 against rows>=2 on %d states: equal %d, shorter %d (%.2f%%, largest gap %d), "
                + "longer %d, unknown %d%n", c.states(), c.equal(), c.smaller(), 100.0 * c.smaller() / c.states(),
                c.maxGap(), c.larger(), c.unknownInLarge());
        assertEquals(13_123_110L, c.states());
        assertEquals(0, c.larger(), "a larger region never lengthens a route");
        assertEquals(0, c.unknownInLarge());
        assertTrue(c.smaller() > 0 && c.smaller() < c.states() / 20, c.smaller() + " states are shorter through row 1");
        assertTrue(c.maxGap() <= 2, "gap " + c.maxGap());
    }

    @Test
    void theTableIsLayerConsistentWithTheEngineMovesOnASample() {
        TableValidation.LayerCheck check = TableValidation.layerConsistency(rows1(), 200_000, 17, 4);
        assertEquals(200_000, check.checked());
        assertTrue(check.consistent(), check.toString());
    }

    @Test
    void aForwardBreadthFirstSearchFindsExactlyTheTableDistanceOnSampledConfigurations() {
        TableValidation.ForwardCheck f = TableValidation.forwardBfs(rows1(), 300, 5, 19, 300_000, 4);
        System.out.println("forward BFS on rows>=1: " + f);
        assertEquals(300, f.sampled());
        assertEquals(0, f.mismatches(), f.toString());
        assertTrue(f.solved() >= 250, f.solved() + " solved");
        assertTrue(f.maxDistanceSolved() >= 4);
    }
}
