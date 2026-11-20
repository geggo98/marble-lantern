package sternhalma.search;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import sternhalma.engine.Geometry;
import sternhalma.engine.Layout;
import sternhalma.engine.Move;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.engine.RuleSet.ForeignCamps;
import sternhalma.engine.RuleSet.JumpRule;
import sternhalma.engine.RuleSet.TriangleRows;
import sternhalma.tablebase.RaceCounts;

/**
 * The search and the evaluation on the 5-row board (15-cell houses, docs/rules.md "Board size"): whole games for both
 * player counts, both jump rules and all foreign-corner rules; the tables of the linear evaluator against the
 * houses; the race code, which knows the 10-cell houses only, is never entered; the specs and players that name the
 * board size; and the size of the linear scores against the race band.
 */
class TriangleRowsSearchTest {

    private static RuleSet five(int players) {
        return RuleSet.defaults(players, TriangleRows.FIVE);
    }

    // ---- whole games ----

    /** Plays a game with the given players to its end (at most 250 plies); every move must be legal. */
    private static Position play(Position start, Player[] players) {
        Position pos = start.copy();
        while (pos.outcome() == null && pos.legalMoves().length > 0) {
            int move = players[pos.sideToMove()].choose(pos);
            assertTrue(Arrays.stream(pos.legalMoves()).anyMatch(m -> m == move),
                    "illegal move " + Move.toString(move) + " at ply " + pos.ply() + " by " + players[pos.sideToMove()].name());
            pos.make(move);
        }
        return pos;
    }

    @Test
    void fullGamesOfTwoPlayersUnderEveryRule() {
        for (JumpRule jump : JumpRule.values()) {
            for (ForeignCamps camps : ForeignCamps.values()) {
                String spec = "ab:eval=linear-r3,rows=5,jump=" + jump.name().toLowerCase() + ",nodes=300,tt=12";
                RuleSet rules = five(2).withJumpRule(jump).withForeignCamps(camps);
                String what = jump + " " + camps;
                Position end = play(Position.initial(rules),
                        new Player[] {PlayerSpec.parse(spec).create(), PlayerSpec.parse(spec).create()});
                assertTrue(end.ply() > 0 && end.ply() <= 250, what + ": " + end.ply());
                assertTrue(end.outcome() != null || end.legalMoves().length == 0, what);
                assertEquals(15, end.pieces(0).length, what);
                assertEquals(15, end.pieces(1).length, what);
            }
        }
    }

    @Test
    void fullGamesOfThreePlayersUnderEveryRule() {
        for (JumpRule jump : JumpRule.values()) {
            for (ForeignCamps camps : ForeignCamps.values()) {
                String spec = "ab3:rows=5,jump=" + jump.name().toLowerCase() + ",nodes=200,tt=12";
                RuleSet rules = five(3).withJumpRule(jump).withForeignCamps(camps);
                String what = jump + " " + camps;
                Position end = play(Position.initial(rules), new Player[] {ThreePlayerSpec.parse(spec).create(),
                        ThreePlayerSpec.parse(spec).create(), ThreePlayerSpec.parse(spec).create()});
                assertTrue(end.ply() > 0 && end.ply() <= 375, what + ": " + end.ply());
                for (int p = 0; p < 3; p++) {
                    assertEquals(15, end.pieces(p).length, what);
                }
            }
        }
    }

    /** A game that is played to a win: the row evaluator at a wide budget wins against a random player. */
    @Test
    void aSearchPlayerWinsOnFiveRows() {
        String spec = "ab:eval=rows,rows=5,nodes=1000,tt=14";
        Position end = play(Position.initial(five(2)), new Player[] {PlayerSpec.parse(spec).create(), new RandomPlayer(1)});
        assertTrue(end.outcome().hasWinner());
        assertEquals(0, end.outcome().winner());
        assertEquals(15, end.goalOccupied(0));
    }

    // ---- the tables of the linear evaluator ----

    @Test
    void inGoalAndInStartCountAllFifteenHouseCells() {
        for (int players = 2; players <= 3; players++) {
            Layout layout = Layout.of(players, TriangleRows.FIVE);
            int li = LinearEvaluator.layoutIndex(five(players));
            for (int p = 0; p < players; p++) {
                int goal = 0;
                int start = 0;
                for (int c = 0; c < Geometry.CELLS; c++) {
                    byte kind = LinearEvaluator.KIND[li][p][c];
                    assertEquals(layout.inGoal(p, c) ? LinearEvaluator.IN_GOAL
                            : layout.inStart(p, c) ? LinearEvaluator.IN_START : 0, kind, players + "p player " + p + " cell " + c);
                    goal += kind == LinearEvaluator.IN_GOAL ? 1 : 0;
                    start += kind == LinearEvaluator.IN_START ? 1 : 0;
                    assertEquals(layout.distance(p, c), LinearEvaluator.DIST[li][p][c]);
                }
                assertEquals(15, goal, players + "p player " + p);
                // the two shared vertex cells of a 3-player start house are goal cells of a neighbour: still start cells
                assertEquals(15, start, players + "p player " + p);
            }
        }
    }

    @Test
    void theFourRowTablesAreTheOnesBefore() {
        for (int players = 2; players <= 3; players++) {
            int li = LinearEvaluator.layoutIndex(RuleSet.defaults(players));
            assertEquals(players - 2, li);
            Layout layout = Layout.of(players);
            for (int p = 0; p < players; p++) {
                for (int c = 0; c < Geometry.CELLS; c++) {
                    boolean goal = Geometry.cornerOf(c) == layout.goalCorner(p);
                    boolean start = Geometry.cornerOf(c) == layout.startCorner(p);
                    assertEquals(goal ? LinearEvaluator.IN_GOAL : start ? LinearEvaluator.IN_START : 0,
                            LinearEvaluator.KIND[li][p][c]);
                }
            }
        }
    }

    /** The side sums against a brute-force count from the layout: distance sums, house membership, steps, jumps. */
    @Test
    void sideSumsOnFiveRowsAgreeWithTheLayout() {
        for (int players = 2; players <= 3; players++) {
            for (long seed = 0; seed < 6; seed++) {
                Position pos = playedPosition(five(players).withJumpRule(JumpRule.ADJACENT), seed, 40 + (int) seed * 25);
                Layout layout = pos.layout();
                int li = LinearEvaluator.layoutIndex(pos.rules());
                for (int p = 0; p < players; p++) {
                    int[] s = new int[LinearEvaluator.SIDE_SUMS];
                    LinearEvaluator.side(pos, li, p, s);
                    int dsum = 0, dmax = 0, dsq = 0, goal = 0, start = 0, steps = 0, jumps = 0;
                    for (int c : pos.pieces(p)) {
                        int d = layout.distance(p, c);
                        dsum += d;
                        dsq += d * d;
                        dmax = Math.max(dmax, d);
                        goal += layout.inGoal(p, c) ? 1 : 0;
                        start += !layout.inGoal(p, c) && layout.inStart(p, c) ? 1 : 0;
                        for (int dir = 0; dir < Geometry.DIRECTIONS; dir++) {
                            int a = Geometry.neighbor(c, dir);
                            if (a < 0) {
                                continue;
                            }
                            if (pos.owner(a) < 0) {
                                steps += layout.allowed(p, a) && layout.distance(p, a) < d ? 1 : 0;
                            } else {
                                int land = Geometry.jumpLanding(c, dir);
                                jumps += land >= 0 && pos.owner(land) < 0 && layout.allowed(p, land)
                                        && layout.distance(p, land) < d ? 1 : 0;
                            }
                        }
                    }
                    String what = players + "p seed " + seed + " player " + p;
                    assertEquals(15, s[LinearEvaluator.COUNT], what);
                    assertEquals(dsum, s[LinearEvaluator.DSUM], what);
                    assertEquals(dmax, s[LinearEvaluator.DMAX], what);
                    assertEquals(dsq, s[LinearEvaluator.DSQ], what);
                    assertEquals(goal, s[LinearEvaluator.GOAL], what);
                    assertEquals(start, s[LinearEvaluator.START], what);
                    assertEquals(steps, s[LinearEvaluator.STEPS], what);
                    assertEquals(jumps, s[LinearEvaluator.JUMPS], what);
                }
            }
        }
    }

    /** All 15 pieces in the goal house count as 15 goal pieces (a 10-cell count would say 10). */
    @Test
    void aFilledHouseHasFifteenGoalPieces() {
        Position pos = Position.empty(five(2));
        for (int c : pos.layout().goalCells(0)) {
            pos.put(0, c);
        }
        for (int c : pos.layout().goalCells(1)) {
            pos.put(1, c);
        }
        int[] s = new int[LinearEvaluator.SIDE_SUMS];
        LinearEvaluator.side(pos, LinearEvaluator.layoutIndex(pos.rules()), 0, s);
        assertEquals(15, s[LinearEvaluator.GOAL]);
        assertEquals(0, s[LinearEvaluator.DSUM]);
    }

    @Test
    void threePlayerSearchReadsTheFiveRowTables() {
        // the root player's distance gain of a move is read from the layout's tables
        Position pos = Position.initial(five(3));
        ThreePlayerSearch search = new ThreePlayerSearch(LinearWeights.ROUND2, 12);
        search.setPositionForEvaluation(pos);
        int[] scores = search.evaluateAll(pos);
        assertEquals(3, scores.length);
        // the start position is symmetric: all three players score the same
        assertEquals(scores[0], scores[1]);
        assertEquals(scores[1], scores[2]);
        SearchResult result = search.search(pos, 3, Long.MAX_VALUE, 3000);
        assertTrue(pos.legalMoves().length > 0);
        assertTrue(Arrays.stream(pos.legalMoves()).anyMatch(m -> m == result.move()));
    }

    // ---- the pruning bound and the incremental state ----

    @Test
    void futilityPruningIsOffNearAWinOfAFifteenCellHouse() {
        Evaluator eval = Evaluators.byName("linear-r2");
        // 12 of 15 own pieces in the goal: a goal needs 15 - 2 = 13 pieces to be "near" at depth 2
        Position twelve = withGoalPieces(12);
        assertTrue(AlphaBeta.futilityAllowed(twelve, 2, 0, eval));
        assertTrue(AlphaBeta.futilityAllowed(twelve, 1, 0, eval));
        Position thirteen = withGoalPieces(13);
        assertFalse(AlphaBeta.futilityAllowed(thirteen, 2, 0, eval));
        assertTrue(AlphaBeta.futilityAllowed(thirteen, 1, 0, eval));
        assertFalse(AlphaBeta.futilityAllowed(withGoalPieces(14), 1, 0, eval));
    }

    /** Player 0 has {@code n} pieces in its goal house and the rest in its start house. */
    private static Position withGoalPieces(int n) {
        Position pos = Position.empty(five(2));
        int[] goal = pos.layout().goalCells(0);
        int[] start = pos.layout().startCells(0);
        for (int i = 0; i < 15; i++) {
            pos.put(0, i < n ? goal[i] : start[i - n]);
        }
        // player 1: 15 pieces on free cells of the middle rows (its own start house is player 0's goal)
        for (int c = 0, put = 0; put < 15 && c < Geometry.CELLS; c++) {
            if (pos.owner(c) < 0 && pos.layout().allowed(1, c) && !pos.layout().inGoal(0, c) && !pos.layout().inStart(0, c) && Math.abs(Geometry.r(c)) <= 2) {
                pos.put(1, c);
                put++;
            }
        }
        return pos;
    }

    @Test
    void incrementalEvaluationIsOffOnFiveRows() {
        LinearEvaluator evaluator = new LinearEvaluator(LinearWeights.WEIGHTS, LinearWeights.SCALE);
        assertFalse(evaluator.incremental().reset(Position.initial(five(2))));
        // also with 10 pieces per side, where the piece count alone would not refuse it
        assertFalse(evaluator.incremental().reset(passedWithTenPieces(five(2))));
        assertTrue(evaluator.incremental().reset(passedWithTenPieces(RuleSet.defaults(2))));
        assertTrue(evaluator.incremental().reset(Position.initial(RuleSet.defaults(2))));
    }

    // ---- race code is never entered ----

    /** A race table that counts every question; it covers everything, so any use would show. */
    private static final class SpyTable implements RaceCounts {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public boolean covers(int[] ownCells) {
            calls.incrementAndGet();
            return true;
        }

        @Override
        public int count(int[] ownCells) {
            calls.incrementAndGet();
            return 3;
        }
    }

    /** Both players with 10 pieces in the 10-cell corners of their goals: passed, with a 4-row race table's piece count. */
    private static Position passedWithTenPieces(RuleSet rules) {
        Position pos = Position.empty(rules);
        for (int p = 0; p < 2; p++) {
            for (int c : Geometry.cornerCells(pos.layout().goalCorner(p))) {
                pos.put(p, c);
            }
        }
        return pos;
    }

    @Test
    void theRaceCodeIsNeverEnteredOnFiveRows() {
        RuleSet four = RuleSet.defaults(2);
        // positive control: the same position under 4 rows does enter the race code
        SpyTable control = new SpyTable();
        assertTrue(RaceAdjudication.applicable(four));
        assertTrue(RaceEvaluator.rulesApply(four));
        assertTrue(RaceAdjudication.passed(passedWithTenPieces(four)));
        new RaceEvaluator(new DistanceEvaluator(), control).evaluate(passedWithTenPieces(four));
        assertTrue(control.calls.get() > 0, "the control must reach the table");

        RuleSet five = five(2);
        SpyTable spy = new SpyTable();
        Position pos = passedWithTenPieces(five);
        assertFalse(RaceAdjudication.applicable(five));
        assertFalse(RaceEvaluator.rulesApply(five));
        assertFalse(RaceAdjudication.passed(pos));
        assertNull(new RaceAdjudication(spy, 4).adjudicate(pos));
        assertEquals(-1, new RaceMove(spy, 1).choose(pos));
        assertEquals(-1, new RaceMove(spy, 2).choose(pos));
        RaceEvaluator evaluator = new RaceEvaluator(new DistanceEvaluator(), spy);
        assertEquals(new DistanceEvaluator().evaluate(pos), evaluator.evaluate(pos));
        assertTrue(evaluator.futilitySafe(pos, 3));
        assertEquals(0, spy.calls.get(), "the race table was asked on a 5-row position");
    }

    @Test
    void theStageFourEvaluatorRefusesFiveRows() {
        Evaluator stage4 = Evaluators.byName("stage4");
        assertThrows(UnsupportedOperationException.class, () -> stage4.evaluate(Position.initial(five(2))));
        stage4.evaluate(Position.initial(RuleSet.defaults(2)));
    }

    // ---- specs and players ----

    @Test
    void aSpecWithRowsFiveRejectsRaceAndRaceEvaluators() {
        for (String bad : new String[] {"ab:rows=5,race=1", "ab:rows=5,race=2", "ab:rows=5,race=1,racerows=1",
                "ab:eval=racelin,rows=5", "ab:eval=racelin1,rows=5", "ab:eval=racelin-r3,rows=5", "ab:eval=race-r3,rows=5",
                "ab:eval=stage4,rows=5"}) {
            if (bad.contains("race-r3")) {
                continue; // not a registered name; the parse of the evaluator is not the point
            }
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> PlayerSpec.parse(bad), bad);
            assertTrue(e.getMessage().contains("rows=5"), bad + ": " + e.getMessage());
        }
        assertThrows(IllegalArgumentException.class, () -> PlayerSpec.parse("ab:rows=6"));
        assertThrows(IllegalArgumentException.class, () -> PlayerSpec.parse("ab3:rows=x"));
        // the same options are fine on the 4-row board, and the plain evaluators fine on 5 rows
        assertEquals(TriangleRows.FOUR, PlayerSpec.parse("ab:eval=racelin,race=1").rows());
        assertEquals(TriangleRows.FOUR, PlayerSpec.parse("ab:rows=4").rows());
        assertEquals(TriangleRows.FIVE, PlayerSpec.parse("ab:eval=linear-r3,rows=5").rows());
        assertEquals(TriangleRows.FIVE, ThreePlayerSpec.parse("ab3:rows=5").rows());
        assertEquals(TriangleRows.FOUR, ThreePlayerSpec.parse("ab3:").rows());
    }

    @Test
    void theBrowserSpecsForFiveRowsParseAndCreate() {
        for (String spec : new String[] {Strategies.BROWSER_ROWS5_SPEC, Strategies.BROWSER_ROWS5_LONG_SPEC}) {
            PlayerSpec parsed = PlayerSpec.parse(spec);
            assertEquals(TriangleRows.FIVE, parsed.rows());
            assertEquals(0, parsed.race());
            assertFalse(Evaluators.usesRaceTable(parsed.evaluator()));
            assertNotNull(parsed.create());
        }
        assertEquals(JumpRule.LONG, PlayerSpec.parse(Strategies.BROWSER_ROWS5_LONG_SPEC).jumpRule());
        for (String spec : new String[] {Strategies.BROWSER_3P_ROWS5_SPEC, Strategies.BROWSER_3P_ROWS5_LONG_SPEC}) {
            assertEquals(TriangleRows.FIVE, ThreePlayerSpec.parse(spec).rows());
        }
        assertEquals(JumpRule.LONG, ThreePlayerSpec.parse(Strategies.BROWSER_3P_ROWS5_LONG_SPEC).jumpRule());
        // the 4-row browser specs are unchanged
        assertEquals(TriangleRows.FOUR, PlayerSpec.parse(Strategies.BROWSER_SPEC).rows());
        assertEquals(TriangleRows.FOUR, ThreePlayerSpec.parse(Strategies.BROWSER_3P_SPEC).rows());
    }

    @Test
    void aPlayerRefusesAPositionOfAnotherBoardSize() {
        Position four2 = Position.initial(RuleSet.defaults(2));
        Position five2 = Position.initial(five(2));
        Position four3 = Position.initial(RuleSet.defaults(3));
        Position five3 = Position.initial(five(3));
        Player ab4 = PlayerSpec.parse("ab:eval=distance,nodes=50,tt=10").create();
        Player ab5 = PlayerSpec.parse("ab:eval=distance,rows=5,nodes=50,tt=10").create();
        assertThrows(IllegalArgumentException.class, () -> ab4.choose(five2));
        assertThrows(IllegalArgumentException.class, () -> ab5.choose(four2));
        assertTrue(ab4.choose(four2) >= 0);
        assertTrue(ab5.choose(five2) >= 0);
        assertTrue(ab5.name().contains("rows=5"), ab5.name());
        assertFalse(ab4.name().contains("rows"), ab4.name());
        ThreePlayerPlayer t4 = ThreePlayerSpec.parse("ab3:nodes=50,tt=10").create();
        ThreePlayerPlayer t5 = ThreePlayerSpec.parse("ab3:rows=5,nodes=50,tt=10").create();
        assertThrows(IllegalArgumentException.class, () -> t4.choose(five3));
        assertThrows(IllegalArgumentException.class, () -> t5.choose(four3));
        assertTrue(t4.choose(four3) >= 0);
        assertTrue(t5.choose(five3) >= 0);
        assertTrue(t5.name().contains("rows=5"), t5.name());
        assertFalse(t4.name().contains("rows"), t4.name());
        // race play cannot be given to a 5-row player directly either
        assertThrows(IllegalArgumentException.class, () -> new AlphaBetaPlayer(new DistanceEvaluator(), 3, Long.MAX_VALUE, 50,
                10, "d", SearchOptions.DEFAULT, new RaceMove(null, 1), JumpRule.ADJACENT, TriangleRows.FIVE));
    }

    // ---- the size of the linear scores ----

    /**
     * The race band starts at {@link Scores#RACE_BASE}; futility pruning and the aspiration window switch off for
     * scores beyond it without any message. The linear evaluators must therefore stay far below it on 5 rows, where
     * dsumPhase grows with the square of the piece count. Measured 2026-11-20 over 2 and 3 players, 40 playouts
     * (random, greedy and mixed), every evaluator of the registry: the largest |score| is printed by this test and
     * asserted below {@value #SCORE_LIMIT}.
     */
    private static final int SCORE_LIMIT = 2000;

    @Test
    void linearScoresStayBelowTheRaceBandOnFiveRows() {
        String[] names = {"linear", "linear-r1", "linear-r2", "linear-r3"};
        int largest = 0;
        for (int players = 2; players <= 3; players++) {
            for (String name : names) {
                if (players == 3 && name.equals("linear-r3")) {
                    continue; // the gain features are two-player only
                }
                Evaluator eval = players == 2 ? Evaluators.byName(name) : null;
                ThreePlayerSearch three = players == 3 ? new ThreePlayerSearch(weightsOf(name), 12) : null;
                for (int g = 0; g < 40; g++) {
                    RuleSet rules = five(players).withJumpRule(g % 2 == 0 ? JumpRule.ADJACENT : JumpRule.LONG);
                    Random random = new Random(1000 + g);
                    Position pos = Position.initial(rules);
                    double greed = g % 4 < 2 ? 0.0 : g % 4 == 2 ? 0.5 : 0.95;
                    while (pos.outcome() == null && pos.legalMoves().length > 0) {
                        int[] moves = pos.legalMoves();
                        pos.make(random.nextDouble() < greed ? bestGain(pos, moves, random) : moves[random.nextInt(moves.length)]);
                        if (pos.outcome() == null && pos.legalMoves().length > 0) {
                            if (eval != null) {
                                largest = Math.max(largest, Math.abs(eval.evaluate(pos)));
                            } else {
                                three.setPositionForEvaluation(pos);
                                for (int score : three.evaluateAll(pos)) {
                                    largest = Math.max(largest, Math.abs(score));
                                }
                            }
                        }
                    }
                }
            }
        }
        System.out.println("largest |linear score| on 5 rows: " + largest + " (RACE_BASE " + Scores.RACE_BASE + ")");
        assertTrue(largest < SCORE_LIMIT, "largest |score| " + largest);
        assertTrue(SCORE_LIMIT < Scores.RACE_BASE);
    }

    // ---- helpers ----

    private static double[] weightsOf(String name) {
        return switch (name) {
            case "linear-r2" -> LinearWeights.ROUND2;
            case "linear-r1" -> LinearWeights.R1;
            default -> LinearWeights.WEIGHTS;
        };
    }

    private static Position playedPosition(RuleSet rules, long seed, int plies) {
        Random random = new Random(seed);
        Position pos = Position.initial(rules);
        for (int i = 0; i < plies && pos.outcome() == null; i++) {
            int[] moves = pos.legalMoves();
            if (moves.length == 0) {
                break;
            }
            pos.make(random.nextDouble() < 0.6 ? bestGain(pos, moves, random) : moves[random.nextInt(moves.length)]);
        }
        return pos;
    }

    private static int bestGain(Position pos, int[] moves, Random random) {
        Layout layout = pos.layout();
        int player = pos.sideToMove();
        int best = Integer.MIN_VALUE;
        int chosen = moves[0];
        int ties = 0;
        for (int m : moves) {
            int gain = layout.distance(player, Move.from(m)) - layout.distance(player, Move.to(m));
            if (gain > best) {
                best = gain;
                chosen = m;
                ties = 1;
            } else if (gain == best && random.nextInt(++ties) == 0) {
                chosen = m;
            }
        }
        return chosen;
    }
}
