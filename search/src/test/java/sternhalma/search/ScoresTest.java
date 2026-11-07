package sternhalma.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The score bands that label consumers must respect (round-2 plan, label policy). */
class ScoresTest {

    @Test
    void bandsAreSeparatedAndSymmetric() {
        assertTrue(Scores.RACE_BASE < AlphaBeta.MATE_BOUND - 200 * 20, "room for 200 x 20 race units below mates");
        assertTrue(Scores.RACE_BASE > 2 * 2000, "above twice the aspiration cap of AlphaBeta");
        for (int sign : new int[] {1, -1}) {
            assertEquals(Scores.Kind.ORDINARY, Scores.kind(sign * (Scores.RACE_BASE - 1)));
            assertEquals(Scores.Kind.RACE, Scores.kind(sign * Scores.RACE_BASE));
            assertEquals(Scores.Kind.RACE, Scores.kind(sign * (AlphaBeta.MATE_BOUND - 1)));
            assertEquals(Scores.Kind.MATE, Scores.kind(sign * AlphaBeta.MATE_BOUND));
            assertEquals(Scores.Kind.MATE, Scores.kind(sign * (AlphaBeta.MATE - 3)));
        }
        assertEquals(Scores.Kind.ORDINARY, Scores.kind(0));
        // a move-limit leaf with distance value 0.6 lies in the race band, so label consumers ignore it as well
        assertEquals(Scores.Kind.RACE, Scores.kind((int) Math.round(0.6 * AlphaBeta.END_SCALE)));
    }

    @Test
    void lastResultAndClearSearchOfThePlayer() {
        AlphaBetaPlayer p = PlayerSpec.parse(Strategies.BEST_SPEC + ",nodes=500,tt=12").create();
        sternhalma.engine.Position pos = sternhalma.engine.Position.initial(sternhalma.engine.RuleSet.defaults(2));
        assertEquals(null, p.lastResult());
        int m = p.choose(pos);
        assertEquals(m, p.lastResult().move());
        assertTrue(p.lastResult().nodes() > 0);
        p.clearSearch();
        assertEquals(null, p.lastResult());
        assertEquals(m, p.choose(pos), "a cleared player searches the start position like a fresh one");
    }
}
