package sternhalma.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import sternhalma.engine.Corner;
import sternhalma.engine.Geometry;
import sternhalma.engine.Move;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;

class SearchOptionsTest {

    private static final Evaluator EVALUATOR = new DistanceEvaluator();

    private static final SearchOptions PVS = new SearchOptions(true, false, false, 0, 0, false, false);
    private static final SearchOptions KILLERS = new SearchOptions(false, true, false, 0, 0, false, false);
    private static final SearchOptions HISTORY = new SearchOptions(false, false, true, 0, 0, false, false);
    private static final SearchOptions ASPIRATION = new SearchOptions(false, false, false, 0, 50, false, false);
    private static final SearchOptions NARROW_ASPIRATION =
            new SearchOptions(false, false, false, 0, 1, false, false);
    private static final SearchOptions TABLE = new SearchOptions(false, false, false, 0, 0, true, true);
    private static final SearchOptions ALL_EXACT = new SearchOptions(true, true, true, 0, 10, true, true);

    /** Options that must not change the value of a fixed-depth search. */
    private static final List<SearchOptions> EXACT_OPTIONS =
            List.of(PVS, KILLERS, HISTORY, ASPIRATION, NARROW_ASPIRATION, TABLE, ALL_EXACT);

    private static int cell(int q, int r) {
        return Geometry.id(q, r);
    }

    /** Plain negamax without pruning, the oracle for the pruned search. */
    private static int plain(Position pos, int depth, int height) {
        if (pos.outcome() != null) {
            return AlphaBeta.terminalScore(pos, height);
        }
        if (depth == 0) {
            return EVALUATOR.evaluate(pos);
        }
        int[] moves = pos.legalMoves();
        if (moves.length == 0) {
            return AlphaBeta.terminalScore(pos, height);
        }
        int best = Integer.MIN_VALUE;
        for (int m : moves) {
            pos.make(m);
            best = Math.max(best, -plain(pos, depth - 1, height + 1));
            pos.unmake();
        }
        return best;
    }

    private static Position randomPosition(long seed, int plies) {
        Random random = new Random(seed);
        Position pos = Position.initial(RuleSet.defaults(2));
        for (int i = 0; i < plies; i++) {
            int[] moves = pos.legalMoves();
            pos.make(moves[random.nextInt(moves.length)]);
        }
        return pos;
    }

    /** Player 0 owns 9 goal cells and has its tenth piece at {@code (q, r)}; player 1 is far away. */
    private static Position nearlyWon(int q, int r) {
        Position pos = Position.empty(RuleSet.defaults(2));
        for (int c : Geometry.cornerCells(Corner.R_POS.ordinal())) {
            if (c != cell(-1, 5)) {
                pos.put(0, c);
            }
        }
        return pos.put(0, q, r).put(1, 4, -8);
    }

    @Test
    void exactOptionsGiveTheSameValueAsPlainNegamax() {
        for (long seed = 1; seed <= 6; seed++) {
            Position pos = randomPosition(seed, 8 + (int) seed);
            for (int depth = 1; depth <= 3; depth++) {
                int expected = plain(pos.copy(), depth, 0);
                for (SearchOptions o : EXACT_OPTIONS) {
                    String what = o.describe() + ", seed " + seed + " depth " + depth;
                    assertEquals(expected, new AlphaBeta(EVALUATOR, 10, false, o).search(pos, depth).score(),
                            "no table, " + what);
                    assertEquals(expected, new AlphaBeta(EVALUATOR, 16, true, o).search(pos, depth).score(),
                            "table, " + what);
                }
            }
        }
    }

    @Test
    void exactOptionsGiveTheSameValueAsPlainNegamaxInAnEndgame() {
        Position pos = nearlyWon(0, 2);
        for (int depth = 1; depth <= 4; depth++) {
            int expected = plain(pos.copy(), depth, 0);
            for (SearchOptions o : EXACT_OPTIONS) {
                assertEquals(expected, new AlphaBeta(EVALUATOR, 16, true, o).search(pos, depth).score(),
                        o.describe() + ", depth " + depth);
            }
        }
    }

    @Test
    void lateMoveReductionsStillFindTheWinAndReturnLegalMoves() {
        for (int lmr = 1; lmr <= 3; lmr++) {
            SearchOptions o = new SearchOptions(true, true, true, lmr, 50, true, true);
            SearchResult win = new AlphaBeta(EVALUATOR, 16, true, o).search(nearlyWon(0, 4), 6);
            assertEquals(Move.of(cell(0, 4), cell(-1, 5)), win.move());
            assertEquals(AlphaBeta.MATE - 1, win.score());
            for (long seed = 1; seed <= 4; seed++) {
                Position pos = randomPosition(seed, 20);
                SearchResult r = new AlphaBeta(EVALUATOR, 16, true, o).search(pos, 30, Long.MAX_VALUE, 20_000);
                assertTrue(r.depth() >= 1);
                assertTrue(Arrays.stream(pos.legalMoves()).anyMatch(m -> m == r.move()), r.toString());
            }
        }
    }

    @Test
    void partialIterationsChangeNothingWithoutALimitAndStillGiveLegalMovesWithOne() {
        SearchOptions partial = new SearchOptions(false, false, false, 0, 0, false, false, true);
        SearchOptions partialAll = new SearchOptions(true, true, true, 0, 50, true, true, true);
        for (long seed = 1; seed <= 4; seed++) {
            Position pos = randomPosition(seed, 20);
            assertEquals(new AlphaBeta(EVALUATOR).search(pos, 3),
                    new AlphaBeta(EVALUATOR, 20, true, partial).search(pos, 3));
            for (SearchOptions o : List.of(partial, partialAll)) {
                SearchResult r = new AlphaBeta(EVALUATOR, 16, true, o).search(pos, 30, Long.MAX_VALUE, 20_000);
                assertFalse(r.complete());
                assertTrue(Arrays.stream(pos.legalMoves()).anyMatch(m -> m == r.move()), r.toString());
            }
        }
    }

    @Test
    void mateScoresAreStoredRelativeToTheNode() {
        for (int height = 0; height < 40; height += 7) {
            for (int score : new int[] {0, 1234, -1234, AlphaBeta.MATE - 3, -(AlphaBeta.MATE - 5)}) {
                assertEquals(score, AlphaBeta.fromTable(AlphaBeta.toTable(score, height), height));
            }
        }
        // a win found 3 plies below a node at height 2 is stored as a win in 3 from that node
        assertEquals(AlphaBeta.MATE - 3, AlphaBeta.toTable(AlphaBeta.MATE - 5, 2));
        assertEquals(-(AlphaBeta.MATE - 3), AlphaBeta.toTable(-(AlphaBeta.MATE - 5), 2));
        // and read back at height 0 as a win in 3 from there
        assertEquals(AlphaBeta.MATE - 3, AlphaBeta.fromTable(AlphaBeta.MATE - 3, 0));
    }

    @Test
    void aReusedTableReportsTheSameMateDistanceAsAFreshOne() {
        SearchOptions o = new SearchOptions(false, false, false, 0, 0, true, true);
        AlphaBeta reused = new AlphaBeta(EVALUATOR, 16, true, o);
        Position pos = nearlyWon(0, 2);
        SearchResult first = reused.search(pos, 6);
        assertTrue(first.score() >= AlphaBeta.MATE_BOUND, first.toString());
        pos.make(first.move());
        pos.make(new AlphaBeta(EVALUATOR).search(pos, 1).move());
        int fresh = new AlphaBeta(EVALUATOR, 16, true, o).search(pos, 6).score();
        assertEquals(fresh, reused.search(pos, 6).score());
    }

    @Test
    void specsParseTheSearchOptions() {
        assertEquals(SearchOptions.DEFAULT, PlayerSpec.parse("ab:eval=distance,nodes=20000").options());
        assertTrue(PlayerSpec.parse("ab").options().isDefault());
        SearchOptions o = PlayerSpec.parse("ab:pvs=1,killers=1,history=1,lmr=2,asp=1,ttrep=1,mate=1,partial=1").options();
        assertEquals(new SearchOptions(true, true, true, 2, SearchOptions.DEFAULT_ASPIRATION, true, true, true), o);
        assertEquals(30, PlayerSpec.parse("ab:asp=30").options().aspiration());
        assertFalse(PlayerSpec.parse("ab:pvs=0").options().pvs());
        assertThrows(IllegalArgumentException.class, () -> PlayerSpec.parse("ab:pvs=2"));
        assertThrows(IllegalArgumentException.class, () -> PlayerSpec.parse("ab:lmr=4"));
        assertTrue(PlayerSpec.parse("ab:pvs=1,lmr=1").create().name().contains("pvs,lmr=1"));
    }
}
