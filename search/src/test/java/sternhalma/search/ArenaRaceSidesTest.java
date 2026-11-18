package sternhalma.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;

import sternhalma.tablebase.RaceTable;

/** Each side of an arena match gets its own race table source ({@code --a-race-eval} and its three siblings). */
class ArenaRaceSidesTest {

    private static final int NODES = 3000;

    private static void needRows1() {
        assumeTrue(RaceTable.rows1IfCached() != null, "the rows>=1 table is not available (-Psternhalma.tablebase.dir)");
    }

    private static void assertSameResult(Arena.Report x, Arena.Report y) {
        assertEquals(x.score(), y.score(), 0.0);
        assertEquals(x.aWins(), y.aWins());
        assertEquals(x.bWins(), y.bWins());
        assertEquals(x.draws(), y.draws());
        assertEquals(x.averagePlies(), y.averagePlies(), 0.0);
        assertEquals(x.nodesPerMoveA(), y.nodesPerMoveA(), 0.0);
        assertEquals(x.nodesPerMoveB(), y.nodesPerMoveB(), 0.0);
    }

    @Test
    void noOptionsMeansNoSource() {
        assertNull(Arena.side(null, null));
        assertEquals("eval=rows2,play=exact", Arena.side("rows2", null).toString());
        assertEquals("eval=exact,play=maxk:1", Arena.side(null, "maxk:1").toString());
    }

    @Test
    void exactSourcesOnBothSidesGiveTheResultOfAMatchWithoutSources() {
        needRows1();
        PlayerSpec best = PlayerSpec.parse(Strategies.BEST_SPEC + ",nodes=" + NODES);
        Arena.Report plain = Arena.match(best, best, 6, 7, 4, 4);
        Arena.Report exact = Arena.match(best, best, RaceTableSource.of("exact"), RaceTableSource.of("exact"), 6, 7, 4, 4,
                null);
        assertSameResult(plain, exact);
    }

    @Test
    void theVariantOfSideAReachesOnlySideA() {
        needRows1();
        PlayerSpec best = PlayerSpec.parse(Strategies.BEST_SPEC + ",nodes=" + NODES);
        PlayerSpec browser = PlayerSpec.parse(Strategies.BROWSER_SPEC + ",nodes=" + NODES);
        // BEST over rows2 tables in both roles plays the browser spec (RaceTableSourceTest), so A=BEST+rows2 against
        // B=BEST must be the match A=browser against B=BEST; if the variant leaked to B, B would play the browser spec too
        Arena.Report viaSource = Arena.match(best, best, RaceTableSource.of("rows2"), null, 6, 7, 4, 4, null);
        Arena.Report viaSpec = Arena.match(browser, best, 6, 7, 4, 4);
        assertSameResult(viaSpec, viaSource);
        Arena.Report mirror = Arena.match(best, best, 6, 7, 4, 4);
        assertTrue(viaSource.score() != mirror.score() || viaSource.averagePlies() != mirror.averagePlies(),
                "the rows2 variant on side A must change the games");
        // and on side B the same variant leaves A alone
        Arena.Report onB = Arena.match(best, best, null, RaceTableSource.of("rows2"), 6, 7, 4, 4, null);
        assertEquals(Arena.match(best, browser, 6, 7, 4, 4).aWins(), onB.aWins());
    }
}
