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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.search.AlphaBetaPlayer;
import sternhalma.search.PlayerSpec;
import sternhalma.search.Strategies;
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
        String[] reply = Web.analyse("", "", 200, null).split(" ");
        assertEquals(6, reply.length, Arrays.toString(reply));
        assertEquals("ok", reply[0]);
        assertEquals("0", reply[3]);
        int moves = reply[4].split(",").length;
        assertEquals(moves, reply[5].split(",").length);
        assertEquals(Position.initial(RuleSet.defaults(2)).legalMoves().length, moves);
        String one = Web.analyse("", reply[4].split(",")[2], 200, null);
        assertEquals("ok", one.split(" ")[0]);
        assertEquals(reply[5].split(",")[2], one.split(" ")[5]);
        assertTrue(Web.analyse("", "0", 200, null).startsWith("error illegal-move "));
        assertTrue(Web.analyse("1,x", "", 200, null).startsWith("error illegal-history "));
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
        assertTrue(Web.legalMoves(joined, "adjacent").startsWith("error illegal-history "));
        assertTrue(Web.legalMoves(joined, null).startsWith("error illegal-history "));
        assertTrue(Web.legalMoves(joined, "long").startsWith("ok "));
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
        assertEquals("ok", Web.newGame(500, 500, "long"));
        String[] choose = Web.choose("", "long").split(" ");
        assertEquals(8, choose.length, Arrays.toString(choose));
        assertEquals("ok", choose[0]);
        assertEquals("0", choose[7]);
        assertTrue(Web.choose("", "adjacent").startsWith("error internal "));
        int longCount = Web.legalMoves("", "long").substring(3).split(",").length;
        assertEquals(Position.initial(RuleSet.defaults(2).withJumpRule(LONG)).legalMoves().length, longCount);
        assertEquals(longCount, Web.analyse("", "", 200, "long").split(" ")[4].split(",").length);
        assertEquals(4, Web.bench(4, 300, "long").split(" ")[1].split(",").length);
        assertTrue(Web.newGame(100, 100, "diagonal").startsWith("error internal "));
        assertEquals("ok", Web.newGame(100, 100, ""));
        assertEquals(Web.legalMoves("", null), Web.legalMoves("", "adjacent"));
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
        assertEquals("ok", Web.newGame(1000, 1000, null));
        String legal = Web.legalMoves("", null);
        assertTrue(legal.startsWith("ok "), legal);
        assertEquals(Position.initial(RuleSet.defaults(2)).legalMoves().length, legal.substring(3).split(",").length);
        String[] choose = Web.choose("", null).split(" ");
        assertEquals(8, choose.length, Arrays.toString(choose));
        assertEquals("ok", choose[0]);
        assertEquals("0", choose[7]);
        String[] bench = Web.bench(4, 500, null).split(" ");
        assertEquals(5, bench.length);
        assertEquals(4, bench[1].split(",").length);
    }

    @Test
    void theExportedSurfaceNeverThrows() {
        Web.setTable(file, false);
        assertTrue(Web.legalMoves("1,x", null).startsWith("error illegal-history "), Web.legalMoves("1,x", null));
        assertTrue(Web.choose("999999", null).startsWith("error illegal-history "));
        assertTrue(Web.newGame(0, 5, null).startsWith("error internal "));
        assertTrue(Web.setTable(new byte[3], false).startsWith("error table-truncated "));
        assertTrue(Web.setTable(null, false).startsWith("error internal "), Web.setTable(null, false));
    }
}
