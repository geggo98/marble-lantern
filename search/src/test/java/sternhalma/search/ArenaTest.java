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
    void theReportHasCpuTimePerMoveAndAnEqualCostSuggestion() {
        Arena.Report r = Arena.match(PlayerSpec.parse("ab:nodes=600"), PlayerSpec.parse("ab:nodes=300,eval=rows"),
                2, 1, 4, 2);
        assertTrue(GameRunner.measuresCpuTime());
        assertTrue(r.cpuMillisPerMoveA() > 0 && r.cpuMillisPerMoveB() > 0, r.toString());
        assertTrue(r.wallMillisPerMoveA() > 0 && r.nodesPerCpuSecondA() > 0 && r.nodesPerCpuSecondB() > 0);
        assertEquals(300 * r.nodesPerCpuSecondA() / r.nodesPerCpuSecondB(), r.equalCostNodesA(), 1e-6);
        assertTrue(r.toString().contains("CPU-ms/move"), r.toString());
        assertTrue(r.toString().contains("equal cost: nodes(A) = nodes(B) x nps(A)/nps(B)"), r.toString());
        assertEquals("", r.sprt());
    }

    @Test
    void theGameRunnerCountsMovesAndTimePerPlayer() {
        Position start = Arena.opening(RuleSet.defaults(2), 2, 0, 4);
        GameRunner.Result r = GameRunner.play(start, PlayerSpec.parse("ab:nodes=200").create(),
                new RandomPlayer(3));
        assertEquals(r.plies() - start.ply(), r.moves()[0] + r.moves()[1]);
        assertTrue(r.cpuNanos()[0] > 0 && r.wallNanos()[0] > 0, java.util.Arrays.toString(r.cpuNanos()));
        assertTrue(r.cpuMillisPerMove(0) > 0);
    }

    @Test
    void theSprtLogLikelihoodRatioFollowsTheNormalApproximation() {
        Sprt sprt = Sprt.parse("0,20");
        assertEquals(Math.log(0.05 / 0.95), sprt.lowerBound(), 1e-12);
        assertEquals(Math.log(0.95 / 0.05), sprt.upperBound(), 1e-12);
        double[] scores = {1.0, 0.5, 0.75, 0.5, 1.0, 0.25};
        double mean = Elo.mean(scores);
        double var = 0;
        for (double x : scores) {
            var += (x - mean) * (x - mean) / scores.length;
        }
        double s0 = 0.5;
        double s1 = Elo.expectedScore(20);
        assertEquals(scores.length * (s1 - s0) * (2 * mean - s0 - s1) / (2 * var), sprt.llr(scores, scores.length),
                1e-12);
        assertEquals(0.0, sprt.llr(new double[] {1, 1, 1}, 3), "no variance yet: no evidence");
        assertEquals(Sprt.Decision.ACCEPT_H1, sprt.decide(3.0));
        assertEquals(Sprt.Decision.ACCEPT_H0, sprt.decide(-3.0));
        assertEquals(Sprt.Decision.CONTINUE, sprt.decide(0.0));
        assertThrows(IllegalArgumentException.class, () -> Sprt.parse("20,0"));
        assertThrows(IllegalArgumentException.class, () -> Sprt.parse("5"));
    }

    @Test
    void theSprtStopsAClearMatchEarlyAndTheStopDoesNotDependOnThreads() {
        PlayerSpec strong = PlayerSpec.parse("ab:nodes=2000");
        PlayerSpec weak = PlayerSpec.parse("ab:nodes=100");
        Sprt sprt = Sprt.parse("0,150");
        Arena.Report one = Arena.match(strong, weak, 60, 1, 4, 1, sprt);
        Arena.Report four = Arena.match(strong, weak, 60, 1, 4, 4, sprt);
        assertTrue(one.pairs() < 60, one.toString());
        assertTrue(one.sprt().endsWith("H1 accepted"), one.sprt());
        assertEquals(one.pairs(), four.pairs());
        assertEquals(one.score(), four.score(), 0.0);
        assertTrue(one.toString().contains(one.sprt()));
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
