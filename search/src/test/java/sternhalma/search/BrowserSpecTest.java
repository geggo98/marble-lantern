package sternhalma.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import sternhalma.engine.Position;
import sternhalma.tablebase.RaceTable;

/**
 * {@link Strategies#BROWSER_SPEC} is the player of the offline browser build: it must never ask for the rows&gt;=1
 * table (242 MB, minutes to rebuild), neither when it is created nor while it searches and plays race moves.
 */
class BrowserSpecTest {

    private static final String BROWSER_1000 = Strategies.BROWSER_SPEC + ",nodes=1000";

    @Test
    void theSpecReadsTheRows2TableOnly() {
        PlayerSpec spec = PlayerSpec.parse(Strategies.BROWSER_SPEC);
        assertEquals("racelin-r3", spec.evaluator());
        assertEquals(1, spec.race());
        assertEquals(2, spec.raceRows());
        assertTrue(Evaluators.names().contains("racelin-r3"));
        assertTrue(Strategies.registered().stream().anyMatch(s -> s.name().startsWith("ab:eval=racelin-r3,")),
                "the strategy contract must cover the browser spec");
    }

    @Test
    void aShortGameNeverRequestsRows1() {
        int before = RaceTable.rows1Requests();
        Fingerprint.Part game = Fingerprint.game(BROWSER_1000, 1000);
        assertFalse(game.lines().isEmpty());
        // positions in the middle of a race, where the table decides: the evaluator and the race move both look up
        Fingerprint.positions(BROWSER_1000);
        assertEquals(before, RaceTable.rows1Requests(), "BROWSER_SPEC asked for the rows>=1 table");
    }

    @Test
    void theGuardCountsRealRows1Requests() {
        // proves the counter above can fail: a BEST_SPEC player asks for rows>=1 as soon as it is created
        int before = RaceTable.rows1Requests();
        PlayerSpec.parse(Strategies.BEST_SPEC + ",nodes=10").create();
        assertTrue(RaceTable.rows1Requests() > before);
    }

    /**
     * The recorded tree of BROWSER_SPEC at 20000 nodes, analogous to BestSpecFingerprintTest. Recorded 2026-11-17 by
     * running {@code :search:fingerprint} twice (identical output). BROWSER_SPEC is a new spec, so no existing constant
     * moved. Re-record only with a written reason, as for the BEST constants.
     */
    @Test
    void browserSpecSearchesTheRecordedTree() {
        String spec = Strategies.BROWSER_SPEC + ",nodes=20000";
        Fingerprint.Part p = Fingerprint.positions(spec);
        assertEquals(5143429675982717449L, p.checksum(), () -> String.join("\n", p.lines()));
        Fingerprint.Part g1 = Fingerprint.game(spec, Fingerprint.GAME_NODES[0]);
        assertEquals(-4846771179482559087L, g1.checksum(), () -> String.join("\n", g1.lines()));
        Fingerprint.Part g5 = Fingerprint.game(spec, Fingerprint.GAME_NODES[1]);
        assertEquals(5086002913037729825L, g5.checksum(), () -> String.join("\n", g5.lines()));
    }

    @Test
    void theBrowserEvaluatorDiffersFromBestOnlyInTheTable() {
        assumeTrue(RaceTable.rows1IfCached() != null, "the rows>=1 table is not available (-Psternhalma.tablebase.dir)");
        // the start position is outside both tables' reach, so every race evaluator returns its base value there
        Position start = Position.initial(ReferencePositions.rules());
        Evaluator browser = Evaluators.byName("racelin-r3");
        Evaluator best = Evaluators.byName("racelin1-r3");
        Evaluator linear = Evaluators.byName("linear-r3");
        assertEquals(linear.evaluate(start), browser.evaluate(start));
        assertEquals(linear.evaluate(start), best.evaluate(start));
        // positions of a played game, up to the race: there the table decides, and rows>=1 and rows>=2 give other values.
        // racelin-r3 is BEST's evaluator over the rows>=2 table, and it is not the one over rows>=1.
        Evaluator bestOverRows2 = Evaluators.byName("racelin1-r3", Map.of(), RaceTableSource.of("rows2"));
        Position pos = Position.initial(ReferencePositions.rules());
        int differing = 0;
        for (int move : RaceTableSourceTest.game(Strategies.BEST_SPEC, RaceTableSource.DEFAULT)) {
            pos.make(move);
            assertEquals(bestOverRows2.evaluate(pos), browser.evaluate(pos), "ply " + pos.ply());
            differing += best.evaluate(pos) != browser.evaluate(pos) ? 1 : 0;
        }
        assertTrue(differing > 0, "no position of the game separates the rows>=1 from the rows>=2 evaluator");
    }
}
