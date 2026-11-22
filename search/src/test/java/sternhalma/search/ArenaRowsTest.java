package sternhalma.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The arena plays the board size of its specs: the 5-row ladder sanity check of the 5-row network plan (phase 2,
 * {@code ab:6400} against {@code ab:1600} on 5 rows) runs through it. Without the size the games started on 4 rows and
 * a {@code rows=5} player refused the first position.
 */
class ArenaRowsTest {

    @Test
    void rowsFiveSpecsPlayFiveRowGames() {
        PlayerSpec a = PlayerSpec.parse(Strategies.BROWSER_ROWS5_SPEC + ",nodes=60");
        PlayerSpec b = PlayerSpec.parse(Strategies.BROWSER_ROWS5_SPEC + ",nodes=30");
        Arena.Report r = Arena.match(a, b, 2, 5, 4, 2, null);
        assertEquals(4, r.aWins() + r.draws() + r.bWins());
        // 15 pieces each: longer than any 4-row game of these players would be on average (median 92 to 99 plies)
        assertTrue(r.averagePlies() > 100, "average plies " + r.averagePlies());
    }

    @Test
    void sidesOfDifferentBoardSizesAreRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Arena.match(PlayerSpec.parse(Strategies.BROWSER_ROWS5_SPEC + ",nodes=30"),
                        PlayerSpec.parse("ab:eval=linear-r3,nodes=30"), 2, 5, 4, 1, null));
        assertTrue(e.getMessage().contains("board sizes"), e.getMessage());
    }
}
