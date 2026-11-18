package sternhalma.tablebase;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sternhalma.engine.Geometry;

/** The measurement seam of the rows&gt;=1 table: block sets, the restricted and observed variants, the baseline estimator. */
class RowOneToolsTest {

    private static RaceTable rows1() {
        RaceTable t = RaceTable.rows1IfCached();
        assumeTrue(t != null, "the rows>=1 table is not available (-Psternhalma.tablebase.dir)");
        return t;
    }

    private static List<int[]> sample(RaceTable t, int n, long seed) {
        Random random = new Random(seed);
        List<int[]> out = new ArrayList<>();
        while (out.size() < n) {
            out.add(t.rankingOf().unrank(Math.floorMod(random.nextLong(), t.states())));
        }
        return out;
    }

    @Test
    void blockArithmeticMatchesTheDocumentedCount() {
        assertEquals(3879, RowOneBlocks.rows1BlockCount());
        assertEquals(0, RowOneBlocks.blockOf(65_535));
        assertEquals(1, RowOneBlocks.blockOf(65_536));
        assertEquals(3878, RowOneBlocks.blockOf(254_186_855L));
    }

    @Test
    void aBlockSetFileRoundTripsAndAnEmptyOrWrongFileIsRejected(@TempDir Path dir) throws IOException {
        BitSet set = new BitSet();
        set.set(0);
        set.set(17);
        set.set(3878);
        Path file = dir.resolve("s.txt");
        RowOneBlocks.write(file, set, "test");
        assertEquals(set, RowOneBlocks.read(file));
        Files.writeString(dir.resolve("empty.txt"), "# nothing\n");
        assertThrows(IOException.class, () -> RowOneBlocks.read(dir.resolve("empty.txt")));
        Files.writeString(dir.resolve("big.txt"), "3879\n");
        assertThrows(IOException.class, () -> RowOneBlocks.read(dir.resolve("big.txt")));
    }

    @Test
    void anObservedTableReturnsTheTablesOwnValuesAndReportsEveryRead() {
        RaceTable rows1 = rows1();
        RaceTable rows2 = RaceTable.rows2();
        long[] reads = {0};
        long[] mismatches = {0};
        LookupObserver observer = (role, cells, rank, v1, v2, est) -> {
            reads[0]++;
            if (rank != rows1.rankingOf().rank(cells) || v1 != rows1.entry(rank)
                    || v2 != (rows2.rankingOf().covers(cells) ? rows2.entry(rows2.rankingOf().rank(cells)) : 255)) {
                mismatches[0]++;
            }
        };
        ObservedRows1Counts observed = new ObservedRows1Counts(rows1, rows2, observer, LookupObserver.LEAF);
        for (int[] cells : sample(rows1, 20_000, 5)) {
            assertEquals(rows1.count(cells), observed.count(cells));
            assertEquals(rows1.covers(cells), observed.covers(cells));
        }
        assertEquals(40_000, reads[0]);
        assertEquals(0, mismatches[0]);
        // a configuration with a stone behind row 1 is not covered and is not a read
        int[] behind = rows1.rankingOf().unrank(0).clone();
        behind[0] = firstCellInRow(0);
        long before = reads[0];
        assertEquals(-1, observed.count(behind));
        assertEquals(before, reads[0]);
    }

    private static int firstCellInRow(int row) {
        for (int c = 0; c < Geometry.CELLS; c++) {
            if (Geometry.r(c) == row) {
                return c;
            }
        }
        throw new IllegalStateException();
    }

    @Test
    void aFullBlockSetIsTheExactTableAndAnEmptyOneIsRows2() {
        RaceTable rows1 = rows1();
        RaceTable rows2 = RaceTable.rows2();
        BitSet all = new BitSet();
        all.set(0, RowOneBlocks.rows1BlockCount());
        BlockRestrictedCounts full = new BlockRestrictedCounts(rows1, rows2, all);
        BlockRestrictedCounts none = new BlockRestrictedCounts(rows1, rows2, new BitSet());
        int withRowOne = 0;
        for (int[] cells : sample(rows1, 20_000, 6)) {
            assertEquals(rows1.count(cells), full.count(cells));
            assertEquals(rows2.count(cells), none.count(cells), "outside the set: as if rows1 did not exist");
            if (PushEstimator.stonesInRow1(cells) > 0) {
                withRowOne++;
                assertEquals(-1, none.count(cells));
            }
        }
        assertTrue(withRowOne > 1000, "the sample must contain configurations with a stone in row 1: " + withRowOne);
    }

    @Test
    void aPartialSetServesExactlyTheBlocksInIt() {
        RaceTable rows1 = rows1();
        RaceTable rows2 = RaceTable.rows2();
        BitSet half = new BitSet();
        for (int b = 0; b < RowOneBlocks.rows1BlockCount(); b += 2) {
            half.set(b);
        }
        BlockRestrictedCounts counts = new BlockRestrictedCounts(rows1, rows2, half);
        int inside = 0;
        int outside = 0;
        for (int[] cells : sample(rows1, 20_000, 7)) {
            long rank = rows1.rankingOf().rank(cells);
            if (half.get(RowOneBlocks.blockOf(rank))) {
                inside++;
                assertEquals(rows1.count(cells), counts.count(cells));
            } else {
                outside++;
                assertEquals(rows2.count(cells), counts.count(cells));
            }
        }
        assertTrue(inside > 5000 && outside > 5000, inside + " inside, " + outside + " outside");
    }

    @Test
    void theBaselineEstimatorPushesRowOneStonesAndCountsOneMoveEach() {
        RaceTable rows2 = RaceTable.rows2();
        PushEstimator plain = new PushEstimator(rows2, new int[PushEstimator.CLASSES]);
        // k = 0: the rows>=2 count itself
        int[] covered = rows2.rankingOf().unrank(123_456);
        assertEquals(rows2.count(covered), plain.estimate(covered));
        // k = 1: one move plus the count of the pushed configuration; the stone steps back to its own cell on row 2
        boolean checked = false;
        for (int i = 0; i < 5000 && !checked; i++) {
            int[] original = rows2.rankingOf().unrank(i * 977L);
            for (int j = 0; j < original.length && !checked; j++) {
                if (Geometry.r(original[j]) != 2) {
                    continue;
                }
                for (int c = 0; c < Geometry.CELLS; c++) {
                    if (Geometry.r(c) == 1 && Geometry.q(c) == Geometry.q(original[j]) && Rows.allowed(0, c)) {
                        int[] moved = original.clone();
                        moved[j] = c;
                        assertEquals(1, PushEstimator.stonesInRow1(moved));
                        assertEquals(1 + rows2.count(original), plain.estimate(moved));
                        checked = true;
                        break;
                    }
                }
            }
        }
        assertTrue(checked, "no configuration with a row-2 stone above a free row-1 cell found");
        // the correction is added per k and the result is never negative
        int[] corr = new int[PushEstimator.CLASSES];
        corr[0] = -1_000;
        assertEquals(0, new PushEstimator(rows2, corr).estimate(covered));
        corr[0] = 3;
        assertEquals(rows2.count(covered) + 3, new PushEstimator(rows2, corr).estimate(covered));
        assertThrows(IllegalArgumentException.class, () -> new PushEstimator(rows2, new int[3]));
    }

    private static boolean contains(int[] a, int v) {
        for (int x : a) {
            if (x == v) {
                return true;
            }
        }
        return false;
    }

    @Test
    void theFitIsDeterministicAndTheFittedEstimatorBeatsThePlainOneOnItsOwnSample() {
        RaceTable rows1 = rows1();
        RaceTable rows2 = RaceTable.rows2();
        int[] a = PushEstimator.fit(rows1, rows2, 30_000, 3);
        int[] b = PushEstimator.fit(rows1, rows2, 30_000, 3);
        assertArrayEquals(a, b);
        PushEstimator plain = new PushEstimator(rows2, new int[PushEstimator.CLASSES]);
        PushEstimator fitted = new PushEstimator(rows2, a);
        long plainError = 0;
        long fittedError = 0;
        long n = 0;
        for (int[] cells : sample(rows1, 30_000, 3)) {
            int truth = rows1.count(cells);
            int p = plain.estimate(cells);
            int f = fitted.estimate(cells);
            if (truth < 0 || p < 0 || f < 0) {
                continue;
            }
            n++;
            plainError += Math.abs(p - truth);
            fittedError += Math.abs(f - truth);
        }
        assertTrue(n > 10_000, "usable samples: " + n);
        assertTrue(fittedError <= plainError, "fitted " + fittedError + " plain " + plainError);
    }
}
