package sternhalma.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import sternhalma.engine.Geometry;
import sternhalma.engine.Layout;
import sternhalma.engine.Move;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.engine.RuleSet.LeaveGoal;
import sternhalma.tablebase.RaceTable;
import sternhalma.tablebase.Ranking;
import sternhalma.tablebase.Rows;

/**
 * Race play and the solitaire with the optional rows&gt;=1 table (E09). Skipped with a message when the table is not
 * in the cache; the tests never build it. The checks mirror {@code RaceMoveTest} and {@code LowerBoundAdmissibilityTest}
 * for the default table: legal moves only, the count falls by one per table move, the player flip, a won race is
 * finished within the count, the parity-assignment bound never exceeds a count, and the beam ordered by the extended
 * table still finds the proven 27-move solitaire and never fewer.
 */
class RaceTableRows1Test {

    private static final RuleSet RULES = RuleSet.defaults(2);

    static RaceTable rows1() {
        RaceTable t = RaceTable.rows1IfCached();
        assumeTrue(t != null, "the rows>=1 table is not in the cache; build it once with "
                + "./gradlew --no-build-cache :tablebase:buildTable -Prows=1");
        return t;
    }

    @Test
    void everyTableMoveIsLegalAndTheExtendedTableCoversMorePassedPositions() {
        RaceTable rows1 = rows1();
        RaceTable rows2 = RaceTable.rows2();
        Random random = new Random(31);
        RaceMove withRows1 = new RaceMove(rows1, 1);
        RaceMove withRows2 = new RaceMove(rows2, 1);
        int positions = 0;
        int rows1Only = 0;
        for (int i = 0; i < 600; i++) {
            Position pos = RaceMoveTest.randomPassed(random, random.nextInt(2), -2, 1);
            if (pos.outcome() != null || pos.legalMoves().length == 0) {
                continue;
            }
            positions++;
            long t1 = withRows1.tableMoves();
            long t2 = withRows2.tableMoves();
            int m1 = withRows1.choose(pos);
            int m2 = withRows2.choose(pos);
            if (m1 >= 0) {
                assertTrue(RaceMoveTest.legal(pos, m1), "illegal race move " + Move.toString(m1) + " in\n" + pos);
            }
            if (m2 >= 0) {
                assertTrue(RaceMoveTest.legal(pos, m2));
            }
            boolean table1 = withRows1.tableMoves() > t1;
            boolean table2 = withRows2.tableMoves() > t2;
            assertTrue(table1 || !table2, "a configuration the default table covers is covered by the extended one");
            if (table1 && !table2) {
                rows1Only++;
            }
        }
        System.out.printf(Locale.ROOT, "rows>=1 table moves %d, rows>=2 table moves %d on %d passed positions (%d only with "
                + "rows>=1)%n", withRows1.tableMoves(), withRows2.tableMoves(), positions, rows1Only);
        assertTrue(positions > 400);
        assertTrue(withRows1.tableMoves() > withRows2.tableMoves() + 50, "the extended table plays more table moves");
        assertTrue(rows1Only > 50);
        assertEquals(1, withRows1.tableMinRow());
    }

    @Test
    void theTableMoveStrictlyReducesTheExtendedCountWhereTheConfigurationIsCovered() {
        RaceTable table = rows1();
        Random random = new Random(32);
        RaceMove rm = new RaceMove(table, 1);
        int checked = 0;
        int rows2Uncovered = 0;
        for (int i = 0; i < 400; i++) {
            Position pos = RaceMoveTest.randomPassed(random, 0, 0, 0); // player 0 on rows >= 1
            if (pos.outcome() != null) {
                continue;
            }
            int[] mine = pos.pieces(0);
            assertTrue(table.covers(mine), "every configuration on rows >= 1 is covered");
            if (!RaceTable.rows2().covers(mine)) {
                rows2Uncovered++;
            }
            int before = table.count(mine);
            int m = rm.choose(pos);
            assertTrue(m >= 0);
            pos.make(m);
            int after = table.count(pos.pieces(0));
            assertTrue(after >= 0, "the chosen move stays in the region");
            assertTrue(after <= before - 1, "count " + before + " -> " + after);
            checked++;
        }
        assertTrue(checked > 300, checked + " covered positions");
        assertTrue(rows2Uncovered > 200, rows2Uncovered + " of them are outside the default table");
        assertEquals(checked, rm.tableMoves());
    }

    @Test
    void racePlayWithTheExtendedTableIsSymmetricUnderThePlayerFlip() {
        RaceTable table = rows1();
        Random random = new Random(34);
        Layout layout = Layout.of(2);
        int checked = 0;
        for (int i = 0; i < 300; i++) {
            Position pos = RaceMoveTest.randomPassed(random, 0, -1, 0);
            if (pos.outcome() != null || !table.covers(pos.pieces(0))) {
                continue;
            }
            Position mirror = pos.transformed(Geometry::playerFlip, true);
            RaceMove forZero = new RaceMove(table, 1);
            RaceMove forOne = new RaceMove(table, 1);
            int m0 = forZero.choose(pos);
            int m1 = forOne.choose(mirror);
            assertTrue(m0 >= 0 && RaceMoveTest.legal(pos, m0));
            assertTrue(m1 >= 0 && RaceMoveTest.legal(mirror, m1), "player 1 plays a legal race move");
            assertEquals(1, forZero.tableMoves());
            assertEquals(1, forOne.tableMoves(), "player 1 reaches the extended table through the flip");
            Position after0 = pos.copy();
            after0.make(m0);
            Position after1 = mirror.copy();
            after1.make(m1);
            assertEquals(table.count(after0.pieces(0)), table.count(Rows.toPlayer0(1, after1.pieces(1))));
            assertEquals(layout.distance(0, Move.from(m0)) - layout.distance(0, Move.to(m0)),
                    layout.distance(1, Move.from(m1)) - layout.distance(1, Move.to(m1)));
            checked++;
        }
        assertTrue(checked > 100, checked + " mirrored positions");
    }

    /**
     * A random position with player 0 anywhere on rows &gt;= 1 (its own orientation) and player 1 on rows -4 to -2:
     * outside player 0's region and outside its own goal, with room to shuffle without entering either.
     */
    static Position farPassed(Random random) {
        Layout layout = Layout.of(2);
        Position pos = Position.empty(RULES);
        for (int p = 0; p < 2; p++) {
            int placed = 0;
            while (placed < Position.PIECES) {
                int c = random.nextInt(Geometry.CELLS);
                boolean side = p == 0 ? Geometry.r(c) >= 1 : Geometry.r(c) <= -2 && Geometry.r(c) >= -4;
                if (layout.allowed(p, c) && side && pos.owner(c) < 0) {
                    pos.put(p, c);
                    placed++;
                }
            }
        }
        return pos;
    }

    /**
     * Against an opponent that never enters the region (and never wins), race play from a covered configuration wins
     * in exactly the count: every table move lowers the count by one. (A random opponent that wanders into row 1
     * blocks a cell the table assumed free and costs a tempo; that is the documented over-count of a region table,
     * not a defect.)
     */
    @Test
    void raceMovesWinInExactlyTheExtendedCountAgainstAnOpponentOutsideTheRegion() {
        RaceTable table = rows1();
        Random random = new Random(33);
        int games = 0;
        int longRaces = 0;
        for (int i = 0; i < 60; i++) {
            Position pos = farPassed(random);
            int[] mine = pos.pieces(0);
            if (pos.outcome() != null) {
                continue;
            }
            int count = table.count(mine);
            assertTrue(count >= 0);
            if (count >= 12) {
                longRaces++;
            }
            RaceMove racer = new RaceMove(table, 1);
            int moves = 0;
            while (pos.outcome() == null && moves < 40) {
                if (pos.sideToMove() == 0) {
                    for (int c : pos.pieces(1)) {
                        assertTrue(Geometry.r(c) <= 0, "the opponent stays outside the region");
                    }
                    int m = racer.choose(pos);
                    assertTrue(m >= 0 && RaceMoveTest.legal(pos, m));
                    pos.make(m);
                    moves++;
                } else {
                    int pick = -1;
                    int fallback = -1;
                    for (int m : pos.legalMoves()) {
                        int r = Geometry.r(Move.to(m));
                        if (r > 0 || r < -4) {
                            continue; // stays outside player 0's region and outside its own goal
                        }
                        if (r < Geometry.r(Move.from(m))) {
                            pick = m; // away from player 0
                            break;
                        }
                        fallback = m; // sideways
                    }
                    assertTrue(pick >= 0 || fallback >= 0, "the far opponent always has a move on rows -4 to 0\n" + pos);
                    pos.make(pick >= 0 ? pick : fallback);
                }
            }
            assertTrue(pos.outcome() != null && pos.outcome().hasWinner() && pos.outcome().winner() == 0, pos.toString());
            assertEquals(count, moves, "race play finishes in exactly the count");
            games++;
        }
        assertTrue(games > 40);
        assertTrue(longRaces > 5, longRaces + " races of 12 moves or more");
    }

    /** The parity-assignment bound of the loser must not exceed any count (a necessary condition, as for rows&gt;=2). */
    @Test
    void theLowerBoundNeverExceedsTheExtendedCountOnTwoMillionSampledStates() throws Exception {
        RaceTable table = rows1();
        Ranking ranking = table.rankingOf();
        int threads = 4;
        long samples = 2_000_000;
        List<Future<long[]>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                int worker = t;
                futures.add(pool.submit(() -> {
                    Solitaire sol = new Solitaire(0, LeaveGoal.FORBIDDEN, null);
                    Random random = new Random(35 + worker);
                    long violations = 0;
                    long tight = 0;
                    for (long i = 0; i < samples / threads; i++) {
                        long rank = (long) (random.nextDouble() * ranking.count());
                        int[] cells = ranking.unrank(rank);
                        int lb = sol.lowerBound(cells);
                        int count = table.entry(rank);
                        if (lb > count) {
                            violations++;
                        } else if (lb == count) {
                            tight++;
                        }
                    }
                    return new long[] {violations, tight};
                }));
            }
            long violations = 0;
            long tight = 0;
            for (Future<long[]> f : futures) {
                long[] r = f.get();
                violations += r[0];
                tight += r[1];
            }
            System.out.printf(Locale.ROOT, "lower bound vs rows>=1 count on %d sampled states: %d violations, tight on %d%n",
                    samples, violations, tight);
            assertEquals(0, violations, "lower bound above the table count");
            assertTrue(tight > 0);
        }
    }

    /** [Bell2009]: 27 moves are optimal; the beam ordered by the extended table finds them and never fewer. */
    @Test
    void theBeamOrderedByTheExtendedTableFindsTheProvenTwentySevenMoveSolitaireAndNeverFewer() {
        RaceTable table = rows1();
        for (int player = 0; player < 2; player++) {
            Solitaire sol = new Solitaire(player, LeaveGoal.FORBIDDEN, table);
            int[] start = Position.initial(RULES).pieces(player);
            Solitaire.Plan plan = sol.plan(start, 20_000, 40, Integer.MIN_VALUE);
            assertNotNull(plan, "no plan found");
            assertTrue(plan.moves() >= 27, "found " + plan.moves() + " moves, below the proven optimum");
            assertEquals(27, plan.moves(), "the beam of width 20000 finds the optimum with the extended table too");
            if (player == 0) {
                RaceAdjudication.verify(Position.initial(RULES), 0, -100, plan, true);
            }
        }
    }
}
