package sternhalma.web;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import sternhalma.engine.Outcome;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.search.AlphaBetaPlayer;
import sternhalma.search.Evaluators;
import sternhalma.search.LinearEvaluator;
import sternhalma.search.PlayerSpec;
import sternhalma.search.SearchResult;
import sternhalma.search.Strategies;
import sternhalma.search.ThreePlayerPlayer;
import sternhalma.search.ThreePlayerSpec;
import sternhalma.tablebase.RaceTable;

/** The facade on the JVM: the same code that TeaVM compiles. Expected values come from the JVM engine and from the benchmark plan. */
class WebFacadeTest {
    private static byte[] file;

    @BeforeAll
    static void readTable() throws IOException {
        file = Files.readAllBytes(Path.of(System.getProperty("sternhalma.web.rows2")));
    }

    private static WebCore ready() {
        WebCore core = new WebCore();
        core.setTable(file, false);
        return core;
    }

    private static WebFailure fails(byte[] table, boolean verify) {
        return assertThrows(WebFailure.class, () -> new WebCore().setTable(table, verify));
    }

    @Test
    void benchGameHasThePlannedFingerprint() {
        // plan section 8: 40 plies at 20,000 nodes; the value was measured on the JVM, native C++ and both TeaVM targets
        WebCore.Bench bench = ready().bench(40, 20_000);
        long checksum = 0;
        for (int move : bench.moves()) {
            checksum = checksum * 31 + move;
        }
        assertEquals(-1986550544382302149L, checksum);
        assertEquals(800_000L, Arrays.stream(bench.nodes()).sum());
        assertEquals(0, bench.rows1Requests());
    }

    @Test
    void movesEqualThoseOfThePlayerWithTheSharedTable() {
        // oracle: the ordinary JVM player, which loads the table through RaceTable.rows2()
        AlphaBetaPlayer reference = PlayerSpec.parse(Strategies.BROWSER_SPEC + ",nodes=2000").create();
        Position position = Position.initial(RuleSet.defaults(2));
        AlphaBetaPlayer other = PlayerSpec.parse(Strategies.BROWSER_SPEC + ",nodes=2000").create();
        int[] expected = new int[8];
        for (int i = 0; i < expected.length; i++) {
            expected[i] = (i % 2 == 0 ? reference : other).choose(position);
            position.make(expected[i]);
        }
        assertArrayEquals(expected, ready().bench(8, 2000).moves());
    }

    @Test
    void chooseReplaysTheHistoryAndReportsTheSearch() {
        WebCore core = ready();
        core.newGame(2000, 2000);
        int[] history = Arrays.copyOf(core.bench(5, 2000).moves(), 5);
        WebCore.Choice choice = core.choose(history);
        Position position = Position.initial(RuleSet.defaults(2));
        for (int move : history) {
            position.make(move);
        }
        assertTrue(Arrays.stream(position.legalMoves()).anyMatch(m -> m == choice.move()));
        assertFalse(choice.raceMove());
        assertTrue(choice.nodes() > 0 && choice.nodes() <= 2000 + 64, "nodes " + choice.nodes());
        assertTrue(choice.depth() > 0);
        assertEquals(0, choice.rows1Requests());
    }

    @Test
    void aRaceMoveCostsNoNodesAndCarriesNoScore() {
        // self-play with a small budget until the race module takes over a passed position (it does so within the move limit)
        WebCore core = ready();
        core.newGame(200, 200);
        java.util.List<Integer> history = new java.util.ArrayList<>();
        WebCore.Choice race = null;
        for (int ply = 0; ply < 200 && race == null; ply++) {
            WebCore.Choice c = core.choose(history.stream().mapToInt(Integer::intValue).toArray());
            if (c.raceMove()) {
                race = c;
            }
            history.add(c.move());
        }
        assertTrue(race != null, "no race move in 200 plies");
        assertEquals(0, race.nodes());
        assertEquals(0, race.depth());
        assertEquals(0, race.score());
    }

    @Test
    void legalMovesOfTheStartPositionMatchTheEngine() {
        int[] expected = Position.initial(RuleSet.defaults(2)).legalMoves();
        assertArrayEquals(expected, ready().legalMoves(new int[0]));
    }

    @Test
    void chooseWithoutNewGameUsesTheDefaultBudget() {
        WebCore.Choice choice = ready().choose(new int[0]);
        assertTrue(choice.nodes() > 2000, "nodes " + choice.nodes());
    }

    @Test
    void anIllegalMoveInTheHistoryIsRejectedWithItsPly() {
        WebCore core = ready();
        int legal = Position.initial(RuleSet.defaults(2)).legalMoves()[0];
        WebFailure f = assertThrows(WebFailure.class, () -> core.legalMoves(new int[] {legal, legal}));
        assertEquals("illegal-history", f.code());
        assertTrue(f.getMessage().contains("ply 1"), f.getMessage());
        assertEquals("illegal-history", assertThrows(WebFailure.class, () -> core.choose(new int[] {0})).code());
    }

    @Test
    void aRowsOneRequestFailsLoudlyAndIsCounted() {
        WebCore core = ready();
        // BEST_SPEC reads the rows>=1 table; the facade has only rows>=2
        WebFailure f = assertThrows(WebFailure.class, () -> core.player(Strategies.BEST_SPEC + ",nodes=100"));
        assertEquals("rows1-requested", f.code());
        assertEquals(1, core.rows1Requests());
    }

    @Test
    void theBrowserSpecNeverAsksForRowsOne() {
        WebCore core = ready();
        core.bench(10, 500);
        assertEquals(0, core.rows1Requests());
    }

    @Test
    void useBeforeTheTableIsInstalledIsAnError() {
        assertEquals("internal", assertThrows(WebFailure.class, () -> new WebCore().newGame(1000, 1000)).code());
    }

    // ---- analysis ----

    private static final int MATE = 1_000_000;

    /** A finished game: self-play with a small budget until the side to move has no legal move; the last move wins. */
    private static int[] finishedGame(WebCore core) {
        core.newGame(300, 300);
        java.util.List<Integer> history = new java.util.ArrayList<>();
        while (core.legalMoves(history.stream().mapToInt(Integer::intValue).toArray()).length > 0) {
            history.add(core.choose(history.stream().mapToInt(Integer::intValue).toArray()).move());
        }
        return history.stream().mapToInt(Integer::intValue).toArray();
    }

    @Test
    void analyseScoresAllLegalMovesInOrderAndCountsTheBudgetPerMove() {
        WebCore core = ready();
        WebCore.Analysis a = core.analyse(new int[0], null, 500);
        assertArrayEquals(core.legalMoves(new int[0]), a.moves());
        assertEquals(a.moves().length, a.scores().length);
        assertTrue(a.nodes() > a.moves().length * 100L && a.nodes() <= a.moves().length * 564L, "nodes " + a.nodes());
        assertEquals(0, a.rows1Requests());
    }

    @Test
    void anAnalysisScoreIsTheNegatedSearchScoreOfTheChildPosition() {
        // oracle: an independent player with the analysis spec searches the position after the move; its score is from the
        // opponent, so the mover's score is its negation (mate scores one ply nearer to 0: see WebCore#analyse)
        WebCore core = ready();
        int[] history = Arrays.copyOf(finishedGame(core), 14);
        WebCore.Analysis a = core.analyse(history, null, 800);
        Position position = Position.initial(RuleSet.defaults(2));
        for (int move : history) {
            position.make(move);
        }
        for (int i = 0; i < a.moves().length; i += 5) {
            AlphaBetaPlayer oracle = core.player(WebCore.ANALYSIS_SPEC + ",nodes=800");
            Position child = position.copy();
            child.make(a.moves()[i]);
            oracle.choose(child);
            assertEquals(-oracle.lastResult().score(), a.scores()[i], "move " + a.moves()[i]);
        }
    }

    @Test
    void aSubsetKeepsTheOrderAndTheScoresOfTheFullList() {
        WebCore core = ready();
        int[] history = Arrays.copyOf(finishedGame(core), 10);
        WebCore.Analysis all = core.analyse(history, null, 600);
        int[] picks = {all.moves()[3], all.moves()[0]};
        WebCore.Analysis some = core.analyse(history, picks, 600);
        assertArrayEquals(picks, some.moves());
        assertArrayEquals(new int[] {all.scores()[3], all.scores()[0]}, some.scores());
        core.analyse(history, picks, 200); // another budget replaces the analysis player
        assertArrayEquals(all.scores(), core.analyse(history, null, 600).scores());
    }

    @Test
    void theMoveThatWinsAtOnceScoresMateMinusOneAndTheMoveThatAllowsItMinusMateMinusTwo() {
        WebCore core = ready();
        int[] game = finishedGame(core);
        int n = game.length;
        WebCore.Analysis atWin = core.analyse(Arrays.copyOf(game, n - 1), null, 400);
        int at = Arrays.stream(atWin.moves()).boxed().toList().indexOf(game[n - 1]);
        assertEquals(MATE - 1, atWin.scores()[at]);
        assertEquals(1, Arrays.stream(atWin.scores()).filter(x -> x == MATE - 1).count());
        for (int i = 0; i < atWin.scores().length; i++) {
            if (i != at) {
                assertTrue(atWin.scores()[i] < MATE - 1);
            }
        }
        WebCore.Analysis atLoss = core.analyse(Arrays.copyOf(game, n - 2), new int[] {game[n - 2]}, 400);
        assertEquals(-(MATE - 2), atLoss.scores()[0]);
    }

    @Test
    void analysisLeavesTheGamePlayersAndTheirResultsAsTheyWere() {
        // the benchmark fingerprint, with an analysis between every two moves
        WebCore core = ready();
        core.newGame(20_000, 20_000);
        int[] history = new int[40];
        long checksum = 0;
        long nodes = 0;
        for (int ply = 0; ply < 40; ply++) {
            WebCore.Choice c = core.choose(Arrays.copyOf(history, ply));
            history[ply] = c.move();
            nodes += c.nodes();
            checksum = checksum * 31 + c.move();
            core.analyse(Arrays.copyOf(history, ply + 1), null, 300 + 100 * (ply % 3));
        }
        assertEquals(-1986550544382302149L, checksum);
        assertEquals(800_000L, nodes);
    }

    @Test
    void analyseRejectsABadMoveABadBudgetAndAFinishedGame() {
        WebCore core = ready();
        int[] legal = core.legalMoves(new int[0]);
        assertEquals("illegal-move", assertThrows(WebFailure.class, () -> core.analyse(new int[0], new int[] {legal[0], 0}, 100)).code());
        assertEquals("internal", assertThrows(WebFailure.class, () -> core.analyse(new int[0], null, 0)).code());
        int[] game = finishedGame(core);
        assertEquals("illegal-history", assertThrows(WebFailure.class, () -> core.analyse(game, null, 100)).code());
    }

    @Test
    void theExportedAnalyseAnswersInTheDocumentedFormat() {
        Web.setTable(file, false);
        String[] reply = Web.analyse("", "", 200, null, null, null, null, null).split(" ");
        assertEquals(6, reply.length, Arrays.toString(reply));
        assertEquals("ok", reply[0]);
        assertEquals("0", reply[3]);
        int moves = reply[4].split(",").length;
        assertEquals(moves, reply[5].split(",").length);
        assertEquals(Position.initial(RuleSet.defaults(2)).legalMoves().length, moves);
        String one = Web.analyse("", reply[4].split(",")[2], 200, null, null, null, null, null);
        assertEquals("ok", one.split(" ")[0]);
        assertEquals(reply[5].split(",")[2], one.split(" ")[5]);
        assertTrue(Web.analyse("", "0", 200, null, null, null, null, null).startsWith("error illegal-move "));
        assertTrue(Web.analyse("1,x", "", 200, null, null, null, null, null).startsWith("error illegal-history "));
    }

    // ---- long hop ----

    private static final RuleSet.JumpRule LONG = RuleSet.JumpRule.LONG;

    /** A core without a table: the long hop needs none. */
    private static WebCore readyWithoutTable() {
        return new WebCore();
    }

    /** Self-play of a long-hop game over {@code plies} plies. */
    private static int[] longGame(WebCore core, int plies, int nodes) {
        core.newGame(nodes, nodes, LONG);
        int[] history = new int[plies];
        for (int ply = 0; ply < plies; ply++) {
            WebCore.Choice c = core.choose(Arrays.copyOf(history, ply), LONG);
            history[ply] = c.move();
            assertFalse(c.raceMove());
            assertEquals(0, c.rows1Requests());
        }
        return history;
    }

    @Test
    void aLongHopGameOf40PliesPlaysLegalMovesAndReplays() {
        WebCore core = readyWithoutTable();
        int[] history = longGame(core, 40, 1500);
        Position position = Position.initial(RuleSet.defaults(2).withJumpRule(LONG));
        for (int ply = 0; ply < 40; ply++) {
            int move = history[ply];
            assertTrue(Arrays.stream(position.legalMoves()).anyMatch(m -> m == move), "ply " + ply);
            assertArrayEquals(position.legalMoves(), core.legalMoves(Arrays.copyOf(history, ply), LONG));
            position.make(move);
        }
        // oracle: the same game by independent players of the long spec
        AlphaBetaPlayer[] pair = {PlayerSpec.parse(Strategies.BROWSER_LONG_SPEC + ",nodes=1500").create(),
            PlayerSpec.parse(Strategies.BROWSER_LONG_SPEC + ",nodes=1500").create()};
        Position oracle = Position.initial(RuleSet.defaults(2).withJumpRule(LONG));
        for (int ply = 0; ply < 40; ply++) {
            int move = pair[ply % 2].choose(oracle);
            assertEquals(move, history[ply], "ply " + ply);
            oracle.make(move);
        }
    }

    @Test
    void aLongHopGameWorksAlsoWithTheTableInstalled() {
        WebCore withTable = ready();
        assertArrayEquals(longGame(readyWithoutTable(), 12, 800), longGame(withTable, 12, 800));
        assertEquals(0, withTable.rows1Requests());
    }

    @Test
    void aLongHopMoveInAnAdjacentGameIsAnIllegalHistory() {
        WebCore core = ready();
        int[] history = longGame(core, 40, 800);
        Position adjacent = Position.initial(RuleSet.defaults(2));
        int bad = -1;
        for (int ply = 0; ply < history.length && bad < 0; ply++) {
            int move = history[ply];
            if (Arrays.stream(adjacent.legalMoves()).anyMatch(m -> m == move)) {
                adjacent.make(move);
            } else {
                bad = ply;
            }
        }
        assertTrue(bad >= 0, "the long-hop game never used a long hop in 40 plies");
        int[] prefix = Arrays.copyOf(history, bad + 1);
        WebFailure f = assertThrows(WebFailure.class, () -> core.legalMoves(prefix));
        assertEquals("illegal-history", f.code());
        assertTrue(f.getMessage().contains("ply " + bad), f.getMessage());
        assertEquals("illegal-history", assertThrows(WebFailure.class, () -> core.analyse(prefix, null, 100)).code());
        assertEquals("illegal-history", assertThrows(WebFailure.class, () -> core.choose(prefix, RuleSet.JumpRule.ADJACENT)).code());
        String joined = Arrays.stream(prefix).mapToObj(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        assertTrue(Web.legalMoves(joined, "adjacent", null, null, null, null).startsWith("error illegal-history "));
        assertTrue(Web.legalMoves(joined, null, null, null, null, null).startsWith("error illegal-history "));
        assertTrue(Web.legalMoves(joined, "long", null, null, null, null).startsWith("ok "));
    }

    @Test
    void longHopAnalysisScoresAreConsistentAndDeterministic() {
        WebCore core = readyWithoutTable();
        int[] history = longGame(core, 14, 600);
        WebCore.Analysis a = core.analyse(history, null, 800, LONG);
        assertArrayEquals(core.legalMoves(history, LONG), a.moves());
        Position position = Position.initial(RuleSet.defaults(2).withJumpRule(LONG));
        for (int move : history) {
            position.make(move);
        }
        // oracle: an independent player with the long analysis spec on the child position, negated
        for (int i = 0; i < a.moves().length; i += 4) {
            AlphaBetaPlayer oracle = PlayerSpec.parse(WebCore.ANALYSIS_LONG_SPEC + ",nodes=800").create();
            Position child = position.copy();
            child.make(a.moves()[i]);
            oracle.choose(child);
            int expected = -oracle.lastResult().score();
            assertEquals(expected, a.scores()[i], "move " + a.moves()[i]);
        }
        // determinism: after another budget, and in a fresh core
        core.analyse(history, null, 300, LONG);
        assertArrayEquals(a.scores(), core.analyse(history, null, 800, LONG).scores());
        assertArrayEquals(a.scores(), readyWithoutTable().analyse(history, null, 800, LONG).scores());
        // a subset keeps order and scores
        int[] picks = {a.moves()[2], a.moves()[0]};
        assertArrayEquals(new int[] {a.scores()[2], a.scores()[0]}, core.analyse(history, picks, 800, LONG).scores());
        // an adjacent analysis in between (with a table) does not change it
        WebCore both = ready();
        both.analyse(new int[0], null, 200);
        assertArrayEquals(a.scores(), both.analyse(history, null, 800, LONG).scores());
        both.analyse(new int[0], null, 200);
        assertArrayEquals(a.scores(), both.analyse(history, null, 800, LONG).scores());
    }

    @Test
    void theAnalysisSpecOfTheLongHopHasNoRacePartsAndTheAdjacentOneIsUnchanged() {
        assertTrue(WebCore.ANALYSIS_LONG_SPEC.contains("jump=long"));
        assertFalse(WebCore.ANALYSIS_LONG_SPEC.contains("race"));
        assertEquals(Strategies.BROWSER_SPEC.replace(",race=1,racerows=2", "") + ",tt=16", WebCore.ANALYSIS_SPEC);
    }

    @Test
    void anAdjacentGameStillNeedsTheTableAndOneGamePlaysOneRule() {
        assertEquals("internal", assertThrows(WebFailure.class, () -> new WebCore().newGame(100, 100)).code());
        assertEquals("internal", assertThrows(WebFailure.class, () -> new WebCore().analyse(new int[0], null, 100)).code());
        WebCore core = ready();
        core.newGame(300, 300, LONG);
        assertEquals("internal", assertThrows(WebFailure.class, () -> core.choose(new int[0], RuleSet.JumpRule.ADJACENT)).code());
        core.newGame(300, 300);
        core.choose(new int[0]);
    }

    @Test
    void theExportedSurfaceTakesTheJumpRule() {
        Web.setTable(file, false);
        assertEquals("ok", Web.newGame(500, 500, "long", null, 0, null, null, null));
        String[] choose = Web.choose("", "long", null, null, null, null).split(" ");
        assertEquals(8, choose.length, Arrays.toString(choose));
        assertEquals("ok", choose[0]);
        assertEquals("0", choose[7]);
        assertTrue(Web.choose("", "adjacent", null, null, null, null).startsWith("error internal "));
        int longCount = Web.legalMoves("", "long", null, null, null, null).substring(3).split(",").length;
        assertEquals(Position.initial(RuleSet.defaults(2).withJumpRule(LONG)).legalMoves().length, longCount);
        assertEquals(longCount, Web.analyse("", "", 200, "long", null, null, null, null).split(" ")[4].split(",").length);
        assertEquals(4, Web.bench(4, 300, "long", null, null, null, null).split(" ")[1].split(",").length);
        assertTrue(Web.newGame(100, 100, "diagonal", null, 0, null, null, null).startsWith("error internal "));
        assertEquals("ok", Web.newGame(100, 100, "", null, 0, null, null, null));
        assertEquals(Web.legalMoves("", null, null, null, null, null), Web.legalMoves("", "adjacent", null, null, null, null));
    }

    @Test
    void theAdjacentFingerprintIsUnchangedByLongGamesOnTheSameCore() {
        WebCore core = ready();
        longGame(core, 6, 300);
        WebCore.Bench bench = core.bench(40, 20_000);
        long checksum = 0;
        for (int move : bench.moves()) {
            checksum = checksum * 31 + move;
        }
        assertEquals(-1986550544382302149L, checksum);
        assertEquals(800_000L, Arrays.stream(bench.nodes()).sum());
    }

    // ---- three players ----

    private static final RuleSet.JumpRule ADJ = RuleSet.JumpRule.ADJACENT;

    /** Self-play of a three-player game over at most {@code plies} plies; stops early when the game is over. */
    private static int[] threeGame(WebCore core, RuleSet.JumpRule jump, int plies, int nodes) {
        core.newGame3(nodes, nodes, nodes, jump);
        int[] history = new int[plies];
        for (int ply = 0; ply < plies; ply++) {
            int[] before = Arrays.copyOf(history, ply);
            if (core.outcome(before, jump, 3) != null) {
                return before;
            }
            WebCore.Choice c = core.choose(before, jump, 3);
            history[ply] = c.move();
            assertFalse(c.raceMove());
            assertTrue(c.nodes() > 0, "ply " + ply);
        }
        return history;
    }

    private static String csv(int[] moves) {
        return Arrays.stream(moves).mapToObj(Integer::toString).collect(java.util.stream.Collectors.joining(","));
    }

    @Test
    void aThreePlayerGameOf60PliesPlaysLegalMovesOnBothRulesWithoutATable() {
        for (RuleSet.JumpRule jump : RuleSet.JumpRule.values()) {
            WebCore core = readyWithoutTable();
            int[] history = threeGame(core, jump, 60, 400);
            assertEquals(60, history.length);
            Position position = Position.initial(RuleSet.defaults(3).withJumpRule(jump));
            for (int ply = 0; ply < 60; ply++) {
                int move = history[ply];
                assertTrue(Arrays.stream(position.legalMoves()).anyMatch(m -> m == move), jump + " ply " + ply);
                assertArrayEquals(position.legalMoves(), core.legalMoves(Arrays.copyOf(history, ply), jump, 3));
                position.make(move);
            }
            assertEquals(0, core.rows1Requests());
        }
    }

    @Test
    void theThreePlayerMovesEqualThoseOfIndependentPlayersOfTheBrowserSpec() {
        // oracle: three independent players of the merged Java spec, one per seat
        for (RuleSet.JumpRule jump : RuleSet.JumpRule.values()) {
            String spec = (jump == LONG ? Strategies.BROWSER_3P_LONG_SPEC : Strategies.BROWSER_3P_SPEC)
                    .replace("nodes=20000", "nodes=300");
            int[] history = threeGame(readyWithoutTable(), jump, 30, 300);
            ThreePlayerPlayer[] seats = {ThreePlayerSpec.parse(spec).create(), ThreePlayerSpec.parse(spec).create(),
                ThreePlayerSpec.parse(spec).create()};
            Position oracle = Position.initial(RuleSet.defaults(3).withJumpRule(jump));
            for (int ply = 0; ply < 30; ply++) {
                int move = seats[ply % 3].choose(oracle);
                assertEquals(move, history[ply], jump + " ply " + ply);
                oracle.make(move);
            }
        }
    }

    @Test
    void aThreePlayerGameIsDeterministicAndIgnoresAnInstalledTable() {
        int[] first = threeGame(readyWithoutTable(), ADJ, 24, 300);
        assertArrayEquals(first, threeGame(readyWithoutTable(), ADJ, 24, 300));
        WebCore withTable = ready();
        assertArrayEquals(first, threeGame(withTable, ADJ, 24, 300));
        assertEquals(0, withTable.rows1Requests());
    }

    @Test
    void theSeatsOfAThreePlayerGameHaveTheirOwnBudgets() {
        WebCore core = readyWithoutTable();
        core.newGame3(100, 1000, 100, ADJ);
        int[] history = new int[0];
        long[] max = new long[3];
        for (int ply = 0; ply < 9; ply++) {
            WebCore.Choice c = core.choose(history, ADJ, 3);
            max[ply % 3] = Math.max(max[ply % 3], c.nodes());
            history = Arrays.copyOf(history, ply + 1);
            history[ply] = c.move();
        }
        assertTrue(max[0] < 400 && max[2] < 400, Arrays.toString(max));
        assertTrue(max[1] > 600, Arrays.toString(max));
    }

    @Test
    void aThreePlayerHistoryWithAnIllegalMoveIsRejectedWithItsPly() {
        WebCore core = readyWithoutTable();
        int[] history = threeGame(core, ADJ, 6, 100);
        history[4] = 999_999;
        WebFailure failure = assertThrows(WebFailure.class, () -> core.choose(history, ADJ, 3));
        assertEquals("illegal-history", failure.code());
        assertTrue(failure.getMessage().contains("ply 4"), failure.getMessage());
        assertEquals("illegal-history", assertThrows(WebFailure.class, () -> core.outcome(history, ADJ, 3)).code());
        assertTrue(Web.legalMoves("1,x", null, "3", null, null, null).startsWith("error illegal-history "));
    }

    @Test
    void theGameKeepsItsPlayerCount() {
        WebCore core = ready();
        core.newGame(100, 100, ADJ);
        WebFailure two = assertThrows(WebFailure.class, () -> core.choose(new int[0], ADJ, 3));
        assertEquals("internal", two.code());
        assertTrue(two.getMessage().contains("2 players"), two.getMessage());
        core.newGame3(100, 100, 100, ADJ);
        WebFailure three = assertThrows(WebFailure.class, () -> core.choose(new int[0], ADJ, 2));
        assertEquals("internal", three.code());
        assertTrue(three.getMessage().contains("3 players"), three.getMessage());
        core.newGame(100, 100, ADJ);
        assertTrue(core.choose(new int[0], ADJ, 2).move() >= 0);
    }

    @Test
    void chooseWithoutNewGameStartsAThreePlayerGameWithTheDefaultBudget() {
        WebCore.Choice c = readyWithoutTable().choose(new int[0], ADJ, 3);
        assertTrue(c.nodes() > 0 && c.nodes() <= WebCore.DEFAULT_NODES * 2L, "nodes " + c.nodes());
    }

    @Test
    void analysisOfThreePlayersScoresEveryMoveLikeTheSearchPlayerAndNeedsNoTable() {
        for (RuleSet.JumpRule jump : RuleSet.JumpRule.values()) {
            WebCore core = readyWithoutTable();
            int[] history = threeGame(core, jump, 20, 200);
            Position position = Position.initial(RuleSet.defaults(3).withJumpRule(jump));
            for (int move : history) {
                position.make(move);
            }
            WebCore.Analysis a = core.analyse(history, null, 700, jump, 3);
            assertArrayEquals(position.legalMoves(), a.moves());
            assertEquals(a.moves().length, a.scores().length);
            ThreePlayerPlayer oracle = ThreePlayerSpec.parse((jump == LONG ? Strategies.BROWSER_3P_LONG_SPEC : Strategies.BROWSER_3P_SPEC)
                    .replace("nodes=20000", "nodes=700")).create();
            long nodes = 0;
            for (int i = 0; i < a.moves().length; i++) {
                SearchResult r = oracle.analyseMove(position, a.moves()[i]);
                assertEquals(r.score(), a.scores()[i], jump + " move " + a.moves()[i]);
                nodes += r.nodes();
            }
            assertEquals(nodes, a.nodes());
            assertEquals(0, core.rows1Requests());
            // a subset in another order, another budget in between, and a running game do not change the scores
            int[] picks = {a.moves()[2], a.moves()[0]};
            core.analyse(history, picks, 100, jump, 3);
            assertArrayEquals(new int[] {a.scores()[2], a.scores()[0]}, core.analyse(history, picks, 700, jump, 3).scores());
            core.newGame3(50, 50, 50, jump);
            assertArrayEquals(a.scores(), core.analyse(history, null, 700, jump, 3).scores());
        }
    }

    @Test
    void analysisOfThreePlayersRejectsABadMoveABadBudgetAndAFinishedGame() {
        WebCore core = readyWithoutTable();
        int[] legal = core.legalMoves(new int[0], ADJ, 3);
        assertEquals("illegal-move", assertThrows(WebFailure.class, () -> core.analyse(new int[0], new int[] {legal[0], 0}, 100, ADJ, 3)).code());
        assertEquals("internal", assertThrows(WebFailure.class, () -> core.analyse(new int[0], null, 0, ADJ, 3)).code());
        int[] game = threeGame(core, ADJ, 2000, 100);
        assertTrue(core.outcome(game, ADJ, 3) != null);
        assertEquals("illegal-history", assertThrows(WebFailure.class, () -> core.analyse(game, null, 100, ADJ, 3)).code());
        assertTrue(Web.analyse("", "0", 100, null, "3", null, null, null).startsWith("error illegal-move "));
    }

    @Test
    void theThreePlayerFacadeReplyHasTheFieldsOfTheTwoPlayerReply() {
        String[] reply = Web.analyse("", "", 200, "long", "3", null, null, null).split(" ");
        assertEquals("ok", reply[0]);
        int[] legal = readyWithoutTable().legalMoves(new int[0], LONG, 3);
        assertEquals(legal.length, reply[4].split(",").length);
        assertEquals(legal.length, reply[5].split(",").length);
        assertTrue(Long.parseLong(reply[1]) > 0);
    }

    @Test
    void evaluate3IsTheStaticVectorPerAbsolutePlayer() {
        for (RuleSet.JumpRule jump : RuleSet.JumpRule.values()) {
            WebCore core = readyWithoutTable();
            int[] start = core.evaluate3(new int[0], jump);
            assertEquals(3, start.length);
            assertEquals(start[0], start[1]);
            assertEquals(start[1], start[2]);
            int[] history = threeGame(core, jump, 40, 200);
            Position position = Position.initial(RuleSet.defaults(3).withJumpRule(jump));
            for (int move : history) {
                position.make(move);
            }
            ThreePlayerPlayer oracle = ThreePlayerSpec.parse(jump == LONG ? Strategies.BROWSER_3P_LONG_SPEC : Strategies.BROWSER_3P_SPEC).create();
            assertArrayEquals(oracle.evaluation(position), core.evaluate3(history, jump));
            int[] e = core.evaluate3(history, jump);
            assertEquals("ok " + e[0] + " " + e[1] + " " + e[2], Web.evaluate3(csv(history), jump == LONG ? "long" : "adjacent", null, null, null));
        }
        assertTrue(Web.evaluate3("1,x", null, null, null, null).startsWith("error illegal-history "));
    }

    @Test
    void aBadPlayerCountIsAnInternalError() {
        assertEquals(2, WebCore.parsePlayers(null));
        assertEquals(2, WebCore.parsePlayers(""));
        assertEquals(2, WebCore.parsePlayers("2"));
        assertEquals(3, WebCore.parsePlayers("3"));
        assertTrue(Web.legalMoves("", null, "4", null, null, null).startsWith("error internal "));
        assertTrue(Web.outcome("", null, "three", null, null, null).startsWith("error internal "));
    }

    @Test
    void aThreePlayerBenchPlaysAllSeatsAndNeedsNoTable() {
        WebCore.Bench bench = readyWithoutTable().bench(9, 200, ADJ, 3);
        assertEquals(9, bench.moves().length);
        Position oracle = Position.initial(RuleSet.defaults(3));
        for (int move : bench.moves()) {
            assertTrue(Arrays.stream(oracle.legalMoves()).anyMatch(m -> m == move));
            oracle.make(move);
        }
        assertArrayEquals(bench.moves(), readyWithoutTable().bench(9, 200, ADJ, 3).moves());
    }

    @Test
    void theOutcomeIsOngoingAtTheStart() {
        WebCore core = readyWithoutTable();
        assertEquals(null, core.outcome(new int[0], ADJ, 3));
        assertEquals(null, core.outcome(new int[0], ADJ, 2));
        assertEquals("ok ongoing", Web.outcome("", null, "3", null, null, null));
        assertEquals("ok ongoing", Web.outcome("", "long", "2", null, null, null));
    }

    @Test
    void aThreePlayerGameThatEndsInAWinNamesTheWinnersSide() {
        // the engine game with 40 nodes per move ends in a win (measured: ply 132, side 2); the oracle is Position.outcome()
        WebCore core = readyWithoutTable();
        int[] full = threeGame(core, ADJ, 300, 40);
        Position oracle = Position.initial(RuleSet.defaults(3));
        for (int move : full) {
            oracle.make(move);
        }
        Outcome expected = oracle.outcome();
        assertTrue(expected.hasWinner(), "expected a win, got " + expected);
        assertEquals(expected, core.outcome(full, ADJ, 3));
        assertEquals("ok win " + expected.winner(), Web.outcome(csv(full), null, "3", null, null, null));
        // a finished game has no moves and cannot be searched
        assertEquals(0, core.legalMoves(full, ADJ, 3).length);
        assertEquals("illegal-history", assertThrows(WebFailure.class, () -> core.choose(full, ADJ, 3)).code());
        // one ply earlier it goes on
        assertEquals(null, core.outcome(Arrays.copyOf(full, full.length - 1), ADJ, 3));
    }

    @Test
    void atTheMoveLimitTheOutcomeIsADrawEvenThoughTheRulesScoreByDistance() {
        // random legal moves with a fixed seed do not win within 300 plies
        Position oracle = Position.initial(RuleSet.defaults(3));
        java.util.Random random = new java.util.Random(7);
        int[] history = new int[300];
        for (int ply = 0; ply < 300; ply++) {
            int[] legal = oracle.legalMoves();
            history[ply] = legal[random.nextInt(legal.length)];
            oracle.make(history[ply]);
        }
        Outcome expected = oracle.outcome();
        assertEquals(Outcome.Reason.MOVE_LIMIT, expected.reason());
        assertEquals(-1, expected.winner());
        WebCore core = readyWithoutTable();
        assertEquals(expected, core.outcome(history, ADJ, 3));
        assertEquals("ok draw", Web.outcome(csv(history), null, "3", null, null, null));
        assertEquals(null, core.outcome(Arrays.copyOf(history, 299), ADJ, 3));
    }

    @Test
    void aWinIsReportedWithItsSideForTwoPlayers() {
        // oracle: Position.outcome() on a self-played two-player game with the long hop; find a game that ends in a win
        WebCore core = ready();
        Position oracle = Position.initial(RuleSet.defaults(2).withJumpRule(LONG));
        core.newGame(60, 60, LONG);
        int[] moves = new int[200];
        int n = 0;
        while (n < 200 && oracle.outcome() == null) {
            int move = core.choose(Arrays.copyOf(moves, n), LONG).move();
            moves[n++] = move;
            oracle.make(move);
        }
        int[] game = Arrays.copyOf(moves, n);
        Outcome expected = oracle.outcome();
        assertEquals(expected, core.outcome(game, LONG, 2));
        assertEquals(expected.hasWinner() ? "ok win " + expected.winner() : "ok draw", Web.outcome(csv(game), "long", null, null, null, null));
    }

    @Test
    void theExportedSurfaceTakesThePlayerCount() {
        assertEquals("ok", Web.newGame(150, 150, "adjacent", "3", 150, null, null, null));
        String[] choose = Web.choose("", "adjacent", "3", null, null, null).split(" ");
        assertEquals(8, choose.length, Arrays.toString(choose));
        assertEquals("ok", choose[0]);
        assertEquals("0", choose[6]);
        int count = Web.legalMoves("", "adjacent", "3", null, null, null).substring(3).split(",").length;
        assertEquals(Position.initial(RuleSet.defaults(3)).legalMoves().length, count);
        assertEquals("ok ongoing", Web.outcome("", "adjacent", "3", null, null, null));
        assertEquals("ok ongoing", Web.outcome("", null, null, null, null, null));
        assertEquals(3, Web.bench(3, 100, "long", "3", null, null, null).split(" ")[1].split(",").length);
        // a missing nodes2 is nodes1; a two-player call is not disturbed by it
        assertEquals("ok", Web.newGame(100, 100, "long", "3", 0, null, null, null));
        assertEquals("ok", Web.newGame(100, 100, "long", "2", 777, null, null, null));
        assertTrue(Web.choose("", "long", "3", null, null, null).startsWith("error internal "));
    }

    // ---- corrupt tables ----

    @Test
    void aTruncatedTableIsTruncated() {
        for (int cut : new int[] {10, 100, file.length - 1}) {
            assertEquals("table-truncated", fails(Arrays.copyOf(file, cut), false).code(), "cut at " + cut);
        }
    }

    @Test
    void trailingBytesAreRejected() {
        assertEquals("table-trailing", fails(Arrays.copyOf(file, file.length + 1), false).code());
    }

    @Test
    void aWrongMagicOrVersionIsRejected() {
        byte[] badMagic = file.clone();
        badMagic[0] ^= 1;
        assertEquals("table-magic", fails(badMagic, false).code());
        byte[] badVersion = file.clone();
        badVersion[7] ^= 1; // low byte of the big endian version
        assertEquals("table-magic", fails(badVersion, false).code());
        assertEquals("table-truncated", fails(new byte[0], false).code());
    }

    @Test
    void aFlippedBodyByteIsCaughtOnlyWithVerify() {
        byte[] bad = file.clone();
        bad[bad.length - 1000] ^= 0x55;
        assertEquals("table-checksum", fails(bad, true).code());
        new WebCore().setTable(bad, false); // the page's SHA-256 of the manifest is then the check; the engine does not look
        new WebCore().setTable(file, true);
    }

    @Test
    void theInstalledTableEqualsTheSharedOne() {
        assertEquals(RaceTable.rows2().states(), new WebCore().setTable(file, true));
    }

    // ---- the string surface TeaVM exports ----

    @Test
    void theExportedSurfaceAnswersInTheDocumentedFormat() {
        assertEquals("ok " + RaceTable.rows2().states(), Web.setTable(file, true));
        assertEquals("ok", Web.newGame(1000, 1000, null, null, 0, null, null, null));
        String legal = Web.legalMoves("", null, null, null, null, null);
        assertTrue(legal.startsWith("ok "), legal);
        assertEquals(Position.initial(RuleSet.defaults(2)).legalMoves().length, legal.substring(3).split(",").length);
        String[] choose = Web.choose("", null, null, null, null, null).split(" ");
        assertEquals(8, choose.length, Arrays.toString(choose));
        assertEquals("ok", choose[0]);
        assertEquals("0", choose[7]);
        String[] bench = Web.bench(4, 500, null, null, null, null, null).split(" ");
        assertEquals(5, bench.length);
        assertEquals(4, bench[1].split(",").length);
    }

    @Test
    void theExportedSurfaceNeverThrows() {
        Web.setTable(file, false);
        assertTrue(Web.legalMoves("1,x", null, null, null, null, null).startsWith("error illegal-history "), Web.legalMoves("1,x", null, null, null, null, null));
        assertTrue(Web.choose("999999", null, null, null, null, null).startsWith("error illegal-history "));
        assertTrue(Web.newGame(0, 5, null, null, 0, null, null, null).startsWith("error internal "));
        assertTrue(Web.setTable(new byte[3], false).startsWith("error table-truncated "));
        assertTrue(Web.setTable(null, false).startsWith("error internal "), Web.setTable(null, false));
    }

    // ---- the evaluation features (live V6 of the Info pages) ----

    /**
     * The position of web/src/about/data/eval-mid.json: 30 plies of ab:eval=distance,nodes=2000 against itself
     * (ExplainerDataTest, Fixtures.mid()), as encoded moves (from | to &lt;&lt; 8) from that file's history.
     */
    private static final int[] MID = {
        4099, 26227, 7440, 22886, 10496, 19832, 4360, 16973, 13057, 14453, 15633, 26480, 17949, 23159, 20777,
        20071, 23603, 20596, 30033, 17497, 23366, 15182, 3844, 12868, 4613, 12088, 7174, 9519, 6919, 6693,
    };

    /** The 13 feature values of eval-mid.json (rounded there to four decimals), in the order of GAIN_NAMES. */
    private static final double[] MID_FEATURES = {-9, 0, -7.1, 1, -2, 2, -6, 2, 3, -3, -12.51, 1, 0};

    private static WebCore.Features classicFeatures(WebCore core, int[] history) {
        return core.features(history, RuleSet.JumpRule.ADJACENT, 2, RuleSet.Ranking.FIRST_WINNER_ONLY,
                RuleSet.ForeignCamps.CLOSED, RuleSet.TriangleRows.FOUR);
    }

    @Test
    void theFeaturesOfTheMidPositionEqualTheExplainerGolden() {
        WebCore.Features f = classicFeatures(new WebCore(), MID); // no table: a static evaluation needs none
        assertEquals(13, f.values().length);
        for (int i = 0; i < 13; i++) {
            assertEquals(MID_FEATURES[i], f.values()[i], 1e-4, "feature " + LinearEvaluator.GAIN_NAMES[i]);
        }
        assertEquals(179, f.score(), "linear-r3 score at ply 30 (eval-mid.json meta.score)");
        assertEquals(0, f.sideToMove());
        // per-piece distances of eval-mid.json, sides[].pieces: {cell, distance}
        int[][] mover = {{2, 12}, {9, 10}, {15, 9}, {18, 9}, {27, 8}, {28, 8}, {61, 5}, {91, 2}, {92, 2}, {117, 0}};
        int[][] opponent = {{26, 2}, {50, 4}, {59, 5}, {66, 6}, {80, 7}, {90, 8}, {111, 10}, {113, 10}, {114, 10}, {118, 12}};
        for (int[] p : mover) {
            assertEquals(p[1], f.distance()[0][p[0]], "distance of player 0 at cell " + p[0]);
        }
        for (int[] p : opponent) {
            assertEquals(p[1], f.distance()[1][p[0]], "distance of player 1 at cell " + p[0]);
        }
        assertEquals(121, f.distance()[0].length);
        assertEquals(121, f.distance()[1].length);
    }

    @Test
    void theFeaturesAfterAnOddPlyAreThoseOfPlayerOneToMove() {
        int[] history = Arrays.copyOf(MID, 29);
        Position position = Position.initial(RuleSet.defaults(2));
        for (int move : history) {
            position.make(move);
        }
        double[] expected = new double[13];
        LinearEvaluator.features(position, expected, LinearEvaluator.GainMode.BOTH);
        WebCore.Features f = classicFeatures(new WebCore(), history);
        assertEquals(1, f.sideToMove());
        assertArrayEquals(expected, f.values());
        assertEquals(Evaluators.byName("linear-r3").evaluate(position), f.score());
        for (int cell = 0; cell < 121; cell++) {
            assertEquals(position.layout().distance(1, cell), f.distance()[1][cell], "player 1, cell " + cell);
            assertEquals(position.layout().distance(0, cell), f.distance()[0][cell], "player 0, cell " + cell);
        }
    }

    @Test
    void featuresBetweenTheMovesLeaveTheBenchmarkFingerprintAsItWas() {
        WebCore core = ready();
        core.newGame(20_000, 20_000);
        int[] history = new int[40];
        long checksum = 0;
        long nodes = 0;
        for (int ply = 0; ply < 40; ply++) {
            classicFeatures(core, Arrays.copyOf(history, ply));
            WebCore.Choice c = core.choose(Arrays.copyOf(history, ply));
            history[ply] = c.move();
            nodes += c.nodes();
            checksum = checksum * 31 + c.move();
        }
        assertEquals(-1986550544382302149L, checksum);
        assertEquals(800_000L, nodes);
    }

    /**
     * The 5-row classic game: the features of the engine's own 5-row position (15 pieces, 15-cell houses), the score of
     * linear-r3 on it and the distances of the 5-row layout, along a seeded random playout. The expected values come
     * from the engine, not from the facade.
     */
    @Test
    void theFeaturesOnFiveRowsAreThoseOfTheFiveRowPosition() {
        WebCore core = new WebCore();
        RuleSet rules = RuleSet.defaults(2, RuleSet.TriangleRows.FIVE);
        Position position = Position.initial(rules);
        java.util.List<Integer> history = new java.util.ArrayList<>();
        long state = 7;
        for (int ply = 0; ply < 160 && position.outcome() == null; ply++) {
            int[] h = history.stream().mapToInt(Integer::intValue).toArray();
            double[] expected = new double[13];
            LinearEvaluator.features(position, expected, LinearEvaluator.GainMode.BOTH);
            WebCore.Features f = core.features(h, RuleSet.JumpRule.ADJACENT, 2, RuleSet.Ranking.FIRST_WINNER_ONLY,
                    RuleSet.ForeignCamps.CLOSED, RuleSet.TriangleRows.FIVE);
            assertArrayEquals(expected, f.values(), "ply " + ply);
            assertEquals(Evaluators.byName("linear-r3").evaluate(position), f.score(), "ply " + ply);
            assertEquals(position.sideToMove(), f.sideToMove());
            for (int p = 0; p < 2; p++) {
                for (int cell = 0; cell < 121; cell++) {
                    assertEquals(position.layout().distance(p, cell), f.distance()[p][cell], "player " + p + ", cell " + cell);
                }
            }
            int[] legal = position.legalMoves();
            state = state * 6364136223846793005L + 1442695040888963407L;
            int move = legal[(int) ((state >>> 33) % legal.length)];
            position.make(move);
            history.add(move);
        }
        assertEquals(15, position.pieceCount(0));
        assertTrue(history.size() >= 100, "the playout reached the middle game: " + history.size() + " plies");
    }

    @Test
    void featuresAreForTheClassicGameOnly() {
        WebCore core = new WebCore();
        RuleSet.Ranking first = RuleSet.Ranking.FIRST_WINNER_ONLY;
        RuleSet.ForeignCamps closed = RuleSet.ForeignCamps.CLOSED;
        RuleSet.TriangleRows four = RuleSet.TriangleRows.FOUR;
        RuleSet.JumpRule adjacent = RuleSet.JumpRule.ADJACENT;
        assertEquals("unsupported", assertThrows(WebFailure.class,
                () -> core.features(new int[0], RuleSet.JumpRule.LONG, 2, first, closed, four)).code());
        assertEquals("unsupported", assertThrows(WebFailure.class,
                () -> core.features(new int[0], adjacent, 3, first, closed, four)).code());
        assertEquals("unsupported", assertThrows(WebFailure.class,
                () -> core.features(new int[0], adjacent, 2, first, RuleSet.ForeignCamps.OPEN, four)).code());
        assertEquals("unsupported", assertThrows(WebFailure.class,
                () -> core.features(new int[0], adjacent, 2, first, RuleSet.ForeignCamps.PASS_THROUGH, four)).code());
        RuleSet.TriangleRows five = RuleSet.TriangleRows.FIVE;
        assertEquals("unsupported", assertThrows(WebFailure.class,
                () -> core.features(new int[0], RuleSet.JumpRule.LONG, 2, first, closed, five)).code());
        assertEquals("unsupported", assertThrows(WebFailure.class,
                () -> core.features(new int[0], adjacent, 3, first, closed, five)).code());
        assertEquals("unsupported", assertThrows(WebFailure.class,
                () -> core.features(new int[0], adjacent, 2, first, RuleSet.ForeignCamps.OPEN, five)).code());
        assertEquals("illegal-history", assertThrows(WebFailure.class,
                () -> core.features(new int[] {MID[1]}, adjacent, 2, first, closed, four)).code());
        // the ranking rule plays the same two-player game
        assertArrayEquals(classicFeatures(core, MID).values(),
                core.features(MID, adjacent, 2, RuleSet.Ranking.FULL_RANKING, closed, four).values());
    }

    @Test
    void theExportedFeaturesAnswerInTheDocumentedFormat() {
        String history = Arrays.stream(MID).mapToObj(Integer::toString).collect(Collectors.joining(","));
        String[] reply = Web.features(history, null, null, null, null, null).split(" ");
        assertEquals(6, reply.length, Arrays.toString(reply));
        assertEquals("ok", reply[0]);
        WebCore.Features f = classicFeatures(new WebCore(), MID);
        String[] values = reply[1].split(",");
        assertEquals(13, values.length);
        for (int i = 0; i < 13; i++) {
            assertEquals(f.values()[i], Double.parseDouble(values[i]), 0.0, "feature " + i); // round trip, exact
        }
        assertEquals("179", reply[2]);
        assertEquals("0", reply[3]);
        for (int p = 0; p < 2; p++) {
            String[] d = reply[4 + p].split(",");
            assertEquals(121, d.length);
            for (int cell = 0; cell < 121; cell++) {
                assertEquals(f.distance()[p][cell], Integer.parseInt(d[cell]), "player " + p + ", cell " + cell);
            }
        }
        assertTrue(Web.features("", "long", null, null, null, null).startsWith("error unsupported "));
        assertTrue(Web.features("", null, "3", null, null, null).startsWith("error unsupported "));
        assertTrue(Web.features("", null, null, null, "open", null).startsWith("error unsupported "));
        assertTrue(Web.features("", null, null, null, null, "5").startsWith("ok "), "the 5-row classic game is explained as well");
        assertTrue(Web.features("", "long", null, null, null, "5").startsWith("error unsupported "));
        assertTrue(Web.features("1,x", null, null, null, null, null).startsWith("error illegal-history "));
    }
}
