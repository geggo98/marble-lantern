package sternhalma.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.tablebase.LookupObserver;
import sternhalma.tablebase.ObservedRows1Counts;
import sternhalma.tablebase.RaceTable;
import sternhalma.tablebase.RowOneBlocks;

/**
 * The seam between the evaluator or race play and the race table ({@link RaceTableSource}). The central claim is
 * fidelity: a variant that is supposed to behave like another configuration plays the same game move for move, so a
 * difference in an experiment comes from the table and from nothing else.
 */
class RaceTableSourceTest {

    private static final int NODES = 1000;
    private static final int PLIES = 160;

    private static void needRows1() {
        assumeTrue(RaceTable.rows1IfCached() != null, "the rows>=1 table is not available (-Psternhalma.tablebase.dir)");
    }

    /** Both sides play {@code spec} built over {@code source}, from the initial position, up to {@code PLIES} plies. */
    static List<Integer> game(String spec, RaceTableSource source) {
        PlayerSpec parsed = PlayerSpec.parse(spec + ",nodes=" + NODES);
        Player[] players = {parsed.create(source), parsed.create(source)};
        Position pos = Position.initial(RuleSet.defaults(2));
        List<Integer> moves = new ArrayList<>();
        while (pos.outcome() == null && moves.size() < PLIES) {
            int[] legal = pos.legalMoves();
            if (legal.length == 0) {
                break;
            }
            int m = players[pos.sideToMove()].choose(pos);
            moves.add(m);
            pos.make(m);
        }
        return moves;
    }

    @Test
    void theDefaultSourceHandsOutTheSharedExactTables() {
        needRows1();
        assertSame(RaceTable.rows1(), RaceTableSource.DEFAULT.evaluator(1).counts());
        assertSame(RaceTable.rows1(), RaceTableSource.DEFAULT.racePlay(1).counts());
        assertSame(RaceTable.rows2(), RaceTableSource.DEFAULT.evaluator(2).counts());
        assertEquals(1, RaceTableSource.DEFAULT.evaluator(1).minRow());
        assertEquals(2, RaceTableSource.DEFAULT.racePlay(2).minRow());
        // with no system property the ambient source is the default one
        assertSame(RaceTableSource.DEFAULT, RaceTableSource.ambient());
    }

    @Test
    void anObservedExactTableLeavesTheGameUnchanged() {
        needRows1();
        long[] reads = {0};
        LookupObserver count = (role, cells, rank, v1, v2, est) -> reads[0]++;
        RaceTableSource observed = RaceTableSource.ofTables(
                rows -> rows == 1 ? new RaceTableSource.Table(new ObservedRows1Counts(RaceTable.rows1(), RaceTable.rows2(),
                        count, LookupObserver.LEAF), 1) : RaceTableSource.exact(rows),
                rows -> rows == 1 ? new RaceTableSource.Table(new ObservedRows1Counts(RaceTable.rows1(), RaceTable.rows2(),
                        count, LookupObserver.RACEPLAY), 1) : RaceTableSource.exact(rows));
        List<Integer> plain = game(Strategies.BEST_SPEC, RaceTableSource.DEFAULT);
        List<Integer> seen = game(Strategies.BEST_SPEC, observed);
        assertEquals(plain, seen);
        assertTrue(reads[0] > 0, "the game must reach positions where the table is read");
        assertTrue(plain.size() > 60, "the game is too short to be meaningful: " + plain.size());
    }

    @Test
    void rows2InPlaceOfRows1IsTheBrowserPlayer() {
        needRows1();
        // BEST with the table swapped in both roles is the browser spec: same evaluation, same region, same moves
        assertEquals(game(Strategies.BROWSER_SPEC, RaceTableSource.DEFAULT),
                game(Strategies.BEST_SPEC, RaceTableSource.of("rows2")));
    }

    @Test
    void aFullBlockSetIsBestAndAnEmptyOneHasTheRows2Values(@TempDir Path dir) throws IOException {
        needRows1();
        BitSet all = new BitSet();
        all.set(0, RowOneBlocks.rows1BlockCount());
        Path file = dir.resolve("all.txt");
        RowOneBlocks.write(file, all, "every block");
        assertEquals(game(Strategies.BEST_SPEC, RaceTableSource.DEFAULT),
                game(Strategies.BEST_SPEC, RaceTableSource.of("blocks:" + file)));
        // the values, not only the moves: sampled configurations of the rows>=1 table
        RaceTable rows1 = RaceTable.rows1();
        RaceTable rows2 = RaceTable.rows2();
        java.util.Random random = new java.util.Random(77);
        List<int[]> cells = new ArrayList<>();
        for (int i = 0; i < 4000; i++) {
            cells.add(rows1.rankingOf().unrank(Math.floorMod(random.nextLong(), rows1.states())));
        }
        int block = RowOneBlocks.blockOf(rows1.rankingOf().rank(cells.get(0)));
        BitSet one = new BitSet();
        one.set(block);
        BitSet none = new BitSet();
        Path singleFile = dir.resolve("one.txt");
        RowOneBlocks.write(singleFile, one, "one block");
        // a block file with no block is refused when it is read, so the empty set is built directly
        RowOneBlocks.write(dir.resolve("none.txt"), none, "no block");
        assertThrows(java.io.UncheckedIOException.class,
                () -> RaceTableSource.of("blocks:" + dir.resolve("none.txt")).evaluator(1));
        var full = RaceTableSource.of("blocks:" + file).evaluator(1).counts();
        var single = RaceTableSource.of("blocks:" + singleFile).evaluator(1).counts();
        var empty = new sternhalma.tablebase.BlockRestrictedCounts(rows1, rows2, none);
        int inBlock = 0;
        int valuesDiffer = 0;
        for (int[] c : cells) {
            boolean served = RowOneBlocks.blockOf(rows1.rankingOf().rank(c)) == block;
            int exact = rows1.count(c);
            assertEquals(exact, full.count(c), "a full set serves every entry");
            assertEquals(rows2.count(c), empty.count(c), "an empty set is the rows>=2 table");
            assertEquals(served ? exact : rows2.count(c), single.count(c), "a single block serves only its own entries");
            inBlock += served ? 1 : 0;
            valuesDiffer += exact != rows2.count(c) ? 1 : 0;
        }
        assertTrue(inBlock >= 1, "the chosen block is read by at least one sample");
        assertTrue(valuesDiffer > 100, "rows>=1 and rows>=2 must differ on the samples, else the test proves nothing: " + valuesDiffer);
        // the game stays playable with a single block
        assertTrue(game(Strategies.BEST_SPEC, RaceTableSource.of("blocks:" + singleFile)).size() > 0);
    }

    @Test
    void theRolesCanBeSwappedOneAtATime() {
        needRows1();
        RaceTableSource evaluatorOnly = RaceTableSource.of("rows2", "exact");
        assertSame(RaceTable.rows2(), evaluatorOnly.evaluator(1).counts());
        assertSame(RaceTable.rows1(), evaluatorOnly.racePlay(1).counts());
        assertEquals(2, evaluatorOnly.evaluator(1).minRow());
        RaceTableSource playOnly = RaceTableSource.of("exact", "rows2");
        assertSame(RaceTable.rows1(), playOnly.evaluator(1).counts());
        assertSame(RaceTable.rows2(), playOnly.racePlay(1).counts());
        // a request for rows>=2 is never changed
        assertSame(RaceTable.rows2(), RaceTableSource.of("estimate").evaluator(2).counts());
    }

    @Test
    void theEstimatorVariantNeverReadsRows1AndPlaysLegalMoves() {
        int before = RaceTable.rows1Requests();
        List<Integer> moves = game(Strategies.BEST_SPEC, RaceTableSource.of("estimate:0,0,0,0,0,0,0,0,0"));
        assertTrue(moves.size() > 0);
        assertEquals(before, RaceTable.rows1Requests(), "the estimator variant asked for the rows>=1 table");
    }

    @Test
    void theEstimatorVariantAddsItsCorrectionPerStonesInRow1AndDiffersFromTheExactTable() {
        needRows1();
        RaceTable rows1 = RaceTable.rows1();
        var zero = RaceTableSource.of("estimate:0,0,0,0,0,0,0,0,0").evaluator(1).counts();
        var shifted = RaceTableSource.of("estimate:0,15,15,15,15,15,15,15,15").evaluator(1).counts();
        java.util.Random random = new java.util.Random(11);
        int withStone = 0;
        int without = 0;
        for (int i = 0; i < 5000; i++) {
            int[] cells = rows1.rankingOf().unrank(Math.floorMod(random.nextLong(), rows1.states()));
            int e0 = zero.count(cells);
            if (e0 < 0) {
                continue;
            }
            if (sternhalma.tablebase.PushEstimator.stonesInRow1(cells) == 0) {
                without++;
                assertEquals(e0, shifted.count(cells));
            } else {
                withStone++;
                assertEquals(e0 + 15, shifted.count(cells));
            }
        }
        assertTrue(withStone > 1000 && without > 100, withStone + " with a stone in row 1, " + without + " without");
        // and a game with the estimate is not the game with the exact table: the seam really replaces the table
        assertNotEquals(game(Strategies.BEST_SPEC, RaceTableSource.DEFAULT),
                game(Strategies.BEST_SPEC, RaceTableSource.of("estimate")));
    }

    @Test
    void maxkServesTheExactTableUpToNStonesInRowOneAndTheFallbackAbove() {
        needRows1();
        RaceTable rows1 = RaceTable.rows1();
        var rows2Fallback = RaceTableSource.of("maxk:1:rows2").evaluator(1).counts();
        var estimate = RaceTableSource.of("maxk:1").evaluator(1).counts();
        var plain = RaceTableSource.of("estimate").evaluator(1).counts();
        java.util.Random random = new java.util.Random(21);
        int low = 0;
        int high = 0;
        for (int i = 0; i < 20_000; i++) {
            long rank = Math.floorMod(random.nextLong(), rows1.states());
            int exact = rows1.entry(rank);
            if (exact == LookupObserver.UNKNOWN) {
                continue;
            }
            int[] cells = rows1.rankingOf().unrank(rank);
            if (sternhalma.tablebase.PushEstimator.stonesInRow1(cells) <= 1) {
                low++;
                assertEquals(exact, estimate.count(cells));
                assertEquals(exact, rows2Fallback.count(cells));
            } else {
                high++;
                assertEquals(plain.count(cells), estimate.count(cells), "above N the estimator answers");
                assertEquals(RaceTable.rows2().count(cells), rows2Fallback.count(cells), "above N rows2 answers");
            }
        }
        assertTrue(low > 1000 && high > 1000, low + " low, " + high + " high");
        assertTrue(game(Strategies.BEST_SPEC, RaceTableSource.of("maxk:1")).size() > 0);
    }

    @Test
    void estimate2UsesThePatternCorrectionAndNeverReadsRows1() {
        needRows1();
        // the sample cells need the rows>=1 ranking; the check starts after they exist, so it counts only what the variant asks for
        RaceTable rows1 = RaceTable.rows1();
        java.util.Random random = new java.util.Random(31);
        List<int[]> cells = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            cells.add(rows1.rankingOf().unrank(Math.floorMod(random.nextLong(), rows1.states())));
        }
        int before = RaceTable.rows1Requests();
        var counts = RaceTableSource.of("estimate2").evaluator(1).counts();
        var reference = new sternhalma.tablebase.PatternEstimator(RaceTable.rows2(),
                sternhalma.tablebase.PatternEstimator.FITTED);
        var plain = RaceTableSource.of("estimate").evaluator(1).counts();
        int differs = 0;
        for (int[] c : cells) {
            assertEquals(reference.estimate(c), counts.count(c));
            differs += counts.count(c) != plain.count(c) ? 1 : 0;
        }
        assertTrue(game(Strategies.BEST_SPEC, RaceTableSource.of("estimate2")).size() > 0);
        assertEquals(before, RaceTable.rows1Requests(), "estimate2 asked for the rows>=1 table");
        assertTrue(differs > 500, "estimate2 must differ from the per-k estimate, differs " + differs);
        assertEquals(256, sternhalma.tablebase.PatternEstimator.FITTED.length);
        assertThrows(IllegalArgumentException.class, () -> RaceTableSource.of("estimate2:1"));
    }

    @Test
    void theTextOfAVariantAndThePlayerLabelNameWhatIsPlayed() {
        needRows1();
        assertEquals("maxk:2", RaceTableSource.Variant.parse("maxk:2").toString());
        assertEquals("estimate [0, 0, 0, 0, 0, 0, 0, 0, 0]", RaceTableSource.Variant.parse("estimate").toString());
        String error = assertThrows(IllegalArgumentException.class, () -> RaceTableSource.Variant.parse("rows3")).getMessage();
        assertTrue(error.contains("estimate2"), error);
        // under a variant source the label still says that the player races over the rows>=1 region
        String name = PlayerSpec.parse(Strategies.BEST_SPEC + ",nodes=10").create(RaceTableSource.of("estimate2")).name();
        assertTrue(name.contains("racerows=1"), name);
        String browser = PlayerSpec.parse(Strategies.BEST_SPEC + ",nodes=10").create(RaceTableSource.of("rows2")).name();
        assertTrue(!browser.contains("racerows=1"), browser);
    }

    @Test
    void maxkTextIsValidated() {
        assertThrows(IllegalArgumentException.class, () -> RaceTableSource.of("maxk"));
        assertThrows(IllegalArgumentException.class, () -> RaceTableSource.of("maxk:x"));
        assertThrows(IllegalArgumentException.class, () -> RaceTableSource.of("maxk:-1"));
        assertThrows(IllegalArgumentException.class, () -> RaceTableSource.of("maxk:9"));
        assertThrows(IllegalArgumentException.class, () -> RaceTableSource.of("maxk:1:blocks"));
        assertThrows(IllegalArgumentException.class, () -> RaceTableSource.of("maxk:1:rows2:x"));
    }

    @Test
    void badVariantsAreErrorsNotSilentFallbacks() {
        assertThrows(IllegalArgumentException.class, () -> RaceTableSource.of("rows3"));
        assertThrows(IllegalArgumentException.class, () -> RaceTableSource.of("blocks"));
        assertThrows(IllegalArgumentException.class, () -> RaceTableSource.of("estimate:1,2"));
        assertThrows(IllegalArgumentException.class, () -> RaceTableSource.of("exact:1"));
        // a missing block file surfaces when the table is asked for
        RaceTableSource missing = RaceTableSource.of("blocks:/nonexistent/blocks.txt");
        assertThrows(RuntimeException.class, () -> missing.evaluator(1));
    }
}
