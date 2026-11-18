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
        assertEquals("ok", Web.newGame(1000, 1000));
        String legal = Web.legalMoves("");
        assertTrue(legal.startsWith("ok "), legal);
        assertEquals(Position.initial(RuleSet.defaults(2)).legalMoves().length, legal.substring(3).split(",").length);
        String[] choose = Web.choose("").split(" ");
        assertEquals(8, choose.length, Arrays.toString(choose));
        assertEquals("ok", choose[0]);
        assertEquals("0", choose[7]);
        String[] bench = Web.bench(4, 500).split(" ");
        assertEquals(5, bench.length);
        assertEquals(4, bench[1].split(",").length);
    }

    @Test
    void theExportedSurfaceNeverThrows() {
        Web.setTable(file, false);
        assertTrue(Web.legalMoves("1,x").startsWith("error illegal-history "), Web.legalMoves("1,x"));
        assertTrue(Web.choose("999999").startsWith("error illegal-history "));
        assertTrue(Web.newGame(0, 5).startsWith("error internal "));
        assertTrue(Web.setTable(new byte[3], false).startsWith("error table-truncated "));
        assertTrue(Web.setTable(null, false).startsWith("error internal "), Web.setTable(null, false));
    }
}
