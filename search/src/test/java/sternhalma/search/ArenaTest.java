package sternhalma.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;

class ArenaTest {

    @Test
    void eloIsZeroAtEvenScoreAndRoundTripsThroughTheExpectedScore() {
        assertEquals(0.0, Elo.fromScore(0.5), 1e-9);
        assertEquals(0.5, Elo.expectedScore(0.0), 1e-12);
        for (double elo : new double[] {-300, -50, 0, 120, 400}) {
            assertEquals(elo, Elo.fromScore(Elo.expectedScore(elo)), 1e-6);
        }
        assertEquals(Elo.fromScore(0.75), 400 * Math.log10(3), 1e-9);
        assertTrue(Elo.fromScore(1.0) < 1300, "a perfect score is clamped, not infinite");
    }

    @Test
    void confidenceShrinksWithMoreSamples() {
        double[] few = {0.0, 1.0, 0.5, 1.0, 0.0, 1.0, 0.5, 0.5};
        double[] many = new double[few.length * 16];
        for (int i = 0; i < many.length; i++) {
            many[i] = few[i % few.length];
        }
        double ciFew = Elo.confidence95(Elo.mean(few), Elo.standardError(few));
        double ciMany = Elo.confidence95(Elo.mean(many), Elo.standardError(many));
        assertTrue(ciMany < ciFew / 3, ciFew + " vs " + ciMany);
    }

    @Test
    void specsParseAndRejectUnknownOptions() {
        PlayerSpec spec = PlayerSpec.parse("ab:eval=rows,nodes=500,depth=6,tt=12");
        assertEquals("rows", spec.evaluator());
        assertEquals(500, spec.nodes());
        assertEquals(6, spec.depth());
        assertEquals(12, spec.tableBits());
        assertEquals("distance", PlayerSpec.parse("ab").evaluator());
        assertThrows(IllegalArgumentException.class, () -> PlayerSpec.parse("ab:colour=red"));
        assertThrows(IllegalArgumentException.class, () -> PlayerSpec.parse("mcts"));
        assertThrows(IllegalArgumentException.class, () -> PlayerSpec.parse("ab:eval=nope").create());
    }

    @Test
    void theNodeLimitStopsASearchButStillReturnsALegalMove() {
        Position pos = Position.initial(RuleSet.defaults(2));
        SearchResult limited = new AlphaBeta(new DistanceEvaluator()).search(pos, 30, Long.MAX_VALUE, 500);
        assertTrue(limited.depth() >= 1);
        assertTrue(limited.nodes() < 5_000, "the limit is checked per node, so the overshoot is small: " + limited);
        assertTrue(java.util.Arrays.stream(pos.legalMoves()).anyMatch(m -> m == limited.move()));
    }

    @Test
    void sameSeedGivesTheSameReportEvenWithSeveralThreads() {
        PlayerSpec a = PlayerSpec.parse("ab:nodes=300");
        PlayerSpec b = PlayerSpec.parse("ab:nodes=100,eval=rows");
        Arena.Report one = Arena.match(a, b, 6, 3, 4, 1);
        Arena.Report four = Arena.match(a, b, 6, 3, 4, 4);
        assertEquals(one.score(), four.score(), 0.0);
        assertEquals(one.aWins(), four.aWins());
        assertEquals(one.bWins(), four.bWins());
        assertEquals(one.averagePlies(), four.averagePlies(), 0.0);
    }

    @Test
    void moreNodesPlayBetter() {
        Arena.Report report = Arena.match(PlayerSpec.parse("ab:nodes=3000"), PlayerSpec.parse("ab:nodes=30"), 8, 1, 4, 4);
        assertTrue(report.score() > 0.7, report.toString());
        assertTrue(report.elo() > 0, report.toString());
    }

    @Test
    void theOpeningIsTheSameForBothColoursAndDiffersBetweenPairs() {
        RuleSet rules = RuleSet.defaults(2);
        assertEquals(Arena.opening(rules, 5, 0, 4), Arena.opening(rules, 5, 0, 4));
        assertEquals(4, Arena.opening(rules, 5, 0, 4).ply());
        long distinct = java.util.stream.IntStream.range(0, 20)
                .mapToObj(i -> Arena.opening(rules, 5, i, 4)).distinct().count();
        assertTrue(distinct > 10, "only " + distinct + " distinct openings among 20 pairs");
    }
}
