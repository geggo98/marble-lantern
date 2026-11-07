package sternhalma.tablebase;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

import sternhalma.engine.Geometry;
import sternhalma.engine.Layout;

class RankingTest {

    @Test
    void regionSizesExcludeTheSideCorners() {
        assertEquals(10, Rows.regionCells(5).length, "the goal corner");
        assertEquals(15, Rows.regionCells(4).length);
        assertEquals(21, Rows.regionCells(3).length);
        assertEquals(28, Rows.regionCells(2).length);
        assertEquals(36, Rows.regionCells(1).length);
        assertEquals(45, Rows.regionCells(0).length);
        assertEquals(13_123_110L, new Ranking(Rows.regionCells(2), 10).count());
        assertEquals(254_186_856L, new Ranking(Rows.regionCells(1), 10).count());
        assertEquals(352_716L, new Ranking(Rows.regionCells(3), 10).count());
        assertEquals(3003L, new Ranking(Rows.regionCells(4), 10).count());
    }

    @Test
    void everyRankOfASmallRegionRoundTrips() {
        Ranking ranking = new Ranking(Rows.regionCells(4), 10);
        Set<String> seen = new HashSet<>();
        for (long rank = 0; rank < ranking.count(); rank++) {
            int[] cells = ranking.unrank(rank);
            assertEquals(10, cells.length);
            for (int i = 1; i < cells.length; i++) {
                assertTrue(cells[i] > cells[i - 1], "ascending");
            }
            assertTrue(seen.add(java.util.Arrays.toString(cells)), "distinct");
            assertEquals(rank, ranking.rank(cells));
            // the order of the input does not matter
            int[] shuffled = cells.clone();
            int t = shuffled[0];
            shuffled[0] = shuffled[9];
            shuffled[9] = t;
            assertEquals(rank, ranking.rank(shuffled));
        }
        assertEquals(3003, seen.size());
    }

    @Test
    void sampledRanksOfTheRaceRegionRoundTripAndTheExtremesAreRight() {
        Ranking ranking = RaceTable.ranking();
        int[] cells = ranking.cells();
        assertEquals(0, ranking.rank(java.util.Arrays.copyOfRange(cells, 0, 10)), "the lowest ten cells rank 0");
        assertEquals(ranking.count() - 1, ranking.rank(java.util.Arrays.copyOfRange(cells, 18, 28)),
                "the highest ten cells rank last");
        Random random = new Random(11);
        for (int i = 0; i < 200_000; i++) {
            long rank = (long) (random.nextDouble() * ranking.count());
            int[] c = ranking.unrank(rank);
            assertEquals(rank, ranking.rank(c));
        }
        assertThrows(IllegalArgumentException.class, () -> ranking.unrank(ranking.count()));
        assertFalse(ranking.covers(new int[9]));
        assertFalse(ranking.covers(new int[] {Geometry.id(0, 1), cells[1], cells[2], cells[3], cells[4], cells[5],
            cells[6], cells[7], cells[8], cells[9]}), "a cell on row 1 is outside the region");
        assertThrows(IllegalArgumentException.class, () -> ranking.rank(new int[10]));
    }

    /** The builder's merge-based rank of a predecessor equals the rank of the sorted modified configuration. */
    @Test
    void rankReplacingEqualsTheRankOfTheModifiedConfiguration() {
        Ranking ranking = new Ranking(Rows.regionCells(1), 10);
        Random random = new Random(5);
        for (int i = 0; i < 200_000; i++) {
            long rank = (long) (random.nextDouble() * ranking.count());
            int[] idx = new int[10];
            ranking.unrankIndices(rank, idx);
            int pos = random.nextInt(10);
            int replacement = random.nextInt(ranking.cells().length);
            while (java.util.Arrays.binarySearch(idx, replacement) >= 0) {
                replacement = random.nextInt(ranking.cells().length);
            }
            int[] modified = idx.clone();
            modified[pos] = replacement;
            java.util.Arrays.sort(modified);
            assertEquals(ranking.rankIndices(modified), ranking.rankReplacing(idx, pos, replacement),
                    "rank " + rank + " pos " + pos + " -> " + replacement);
        }
        // the extremes: replacing the first by a smaller and the last by a larger index
        int[] idx = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        assertEquals(ranking.rankIndices(new int[] {0, 2, 3, 4, 5, 6, 7, 8, 9, 10}), ranking.rankReplacing(idx, 0, 0));
        assertEquals(ranking.rankIndices(new int[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 35}), ranking.rankReplacing(idx, 9, 35));
        assertEquals(ranking.rankIndices(new int[] {1, 2, 3, 4, 5, 6, 7, 8, 10, 35}), ranking.rankReplacing(idx, 8, 35));
        assertEquals(ranking.rankIndices(new int[] {2, 3, 4, 5, 6, 7, 8, 9, 10, 35}), ranking.rankReplacing(idx, 0, 35));
        assertEquals(0, ranking.indexOf(ranking.cells()[0]));
        assertEquals(-1, ranking.indexOf(Geometry.id(0, 0)), "the middle row is outside rows>=1");
    }

    @Test
    void rowsMapPlayerOneOntoPlayerZero() {
        Layout layout = Layout.of(2);
        for (int c = 0; c < Geometry.CELLS; c++) {
            int mapped = Rows.toPlayer0(1, c);
            assertEquals(layout.allowed(1, c), layout.allowed(0, mapped), "allowed cells correspond");
            assertEquals(Rows.row(1, c), Rows.row(0, mapped), "rows correspond");
            if (layout.allowed(1, c)) {
                assertEquals(layout.distance(1, c), layout.distance(0, mapped), "distances correspond");
            }
            assertEquals(c, Rows.toPlayer0(0, c));
        }
        int[] goal1 = Geometry.cornerCells(layout.goalCorner(1));
        int[] mapped = Rows.toPlayer0(1, goal1);
        int[] goal0 = Geometry.cornerCells(layout.goalCorner(0)).clone();
        java.util.Arrays.sort(goal0);
        assertArrayEquals(goal0, mapped);
    }
}
