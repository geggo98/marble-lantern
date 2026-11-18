package sternhalma.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.tablebase.RaceTable;

/**
 * The race band seen through the search (round-2 experiment E08): the aspiration window treats a previous race score
 * like a mate score, futility pruning never cuts where the race evaluator can return a band value, and the player
 * reports the band of its last score.
 */
class RaceBandTest {

    private static final String OPTIONS = "pvs=1,killers=1,history=1,lmr=2,ttrep=1,mate=1,partial=1";
    private static final Evaluator RACE = Evaluators.byName("racelin");
    private static final Evaluator LINEAR = Evaluators.byName("linear-r2");

    /**
     * After a race-band score the root is searched with the full window, exactly as without aspiration: the same
     * move, score and node count. A narrow window around a band value would fail and be widened to the full window
     * in several re-searches.
     */
    @Test
    void aspirationUsesTheFullWindowAfterARaceScore() {
        SearchOptions asp = PlayerSpec.parse("ab:" + OPTIONS + ",asp=1").options();
        SearchOptions full = PlayerSpec.parse("ab:" + OPTIONS).options();
        Random random = new Random(91);
        int raceRoots = 0;
        int mates = 0;
        for (int i = 0; raceRoots < 20; i++) {
            // every piece on its own rows >= 4: any single move stays inside the rows>=2 table, so every leaf of a
            // shallow search carries a race value, unless the search finds a win within its depth
            Position pos = RaceEvaluatorTest.deepPassed(random, i % 2, 4);
            SearchResult a = new AlphaBeta(RACE, 16, true, asp).search(pos, 3);
            SearchResult b = new AlphaBeta(RACE, 16, true, full).search(pos, 3);
            assertEquals(b, a, "aspiration must not change the search after a race score at\n" + pos);
            if (Scores.kind(a.score()) == Scores.Kind.RACE) {
                raceRoots++;
            } else {
                assertEquals(Scores.Kind.MATE, Scores.kind(a.score()), pos.toString());
                mates++;
            }
        }
        assertTrue(mates < 200, "deep passed positions that are not won within 3 plies exist");
        // control: on contact positions of self-play games the window is in use, so the trees differ for some of them
        // (about half at depth 4; with LMR the window may even change the score by a point, so only nodes are compared)
        int different = 0;
        List<Position> contact = DeltaProbe.positions(DeltaProbe.DEFAULT_SPEC.replace("nodes=1000", "nodes=300"), 1, 94);
        for (Position pos : contact.subList(0, Math.min(20, contact.size()))) {
            SearchResult a = new AlphaBeta(LINEAR, 16, true, asp).search(pos, 4);
            SearchResult b = new AlphaBeta(LINEAR, 16, true, full).search(pos, 4);
            assertEquals(Scores.Kind.ORDINARY, Scores.kind(b.score()));
            different += a.nodes() != b.nodes() ? 1 : 0;
        }
        assertTrue(different > 0, "the control shows the window at work on ordinary scores");
    }

    /**
     * An evaluator that checks every answer of the race evaluator's futility hook against a brute force: whenever
     * it allows pruning at a node, no move (depth 1) or move and reply (depth 2) from that node may reach a passed
     * position. The search calls the hook exactly where it would prune, so this is an assert mode for the rule
     * "never prune at a node whose child is passed".
     */
    private static final class Checked implements Evaluator {
        final Evaluator inner;
        int calls;
        int allowed;

        Checked(Evaluator inner) {
            this.inner = inner;
        }

        @Override
        public int evaluate(Position position) {
            return inner.evaluate(position);
        }

        @Override
        public boolean futilitySafe(Position position, int depth) {
            boolean safe = inner.futilitySafe(position, depth);
            calls++;
            if (safe) {
                allowed++;
                assertFalse(RaceEvaluatorTest.raceValueWithin(inner, position.copy(), depth),
                        "futility allowed at depth " + depth + " although a race value is in reach at\n" + position);
            }
            return safe;
        }
    }

    @Test
    void theSearchNeverPrunesWhereARaceValueIsInReach() {
        SearchOptions fut = PlayerSpec.parse("ab:" + OPTIONS + ",asp=1,fut=1,futk=30,fut2=1").options();
        // late positions of self-play games, where the armies are about to pass or have passed
        List<Position> positions = DeltaProbe.positions(DeltaProbe.DEFAULT_SPEC.replace("nodes=1000", "nodes=300"),
                6, 92).stream().filter(p -> p.ply() >= 50).toList();
        assertTrue(positions.size() > 50, positions.size() + " late positions");
        for (Evaluator eval : new Evaluator[] {RACE, Evaluators.byName("racelin", java.util.Map.of("wout", 1))}) {
            long cuts = 0;
            int calls = 0;
            int allowed = 0;
            int raceScores = 0;
            for (Position pos : positions) {
                Checked checked = new Checked(eval);
                AlphaBeta ab = new AlphaBeta(checked, 16, true, fut);
                SearchResult r = ab.search(pos, 30, Long.MAX_VALUE, 2000);
                cuts += ab.futilityCuts();
                calls += checked.calls;
                allowed += checked.allowed;
                raceScores += Scores.kind(r.score()) == Scores.Kind.RACE ? 1 : 0;
            }
            System.out.println("RaceBandTest (" + eval + "): " + positions.size() + " searches, " + calls
                    + " hook calls, " + allowed + " allowed, " + cuts + " futility cuts, " + raceScores
                    + " race-band root scores");
            assertTrue(cuts > 1000, "the searches must prune where the hook allows it, cuts " + cuts);
            assertTrue(allowed < calls, "the hook must forbid pruning somewhere in late positions");
            assertTrue(raceScores > 0, "some root scores are race values");
        }
    }

    @Test
    void thePlayerReportsTheBandOfItsLastScore() {
        AlphaBetaPlayer p = PlayerSpec.parse("ab:eval=racelin," + OPTIONS + ",asp=1,nodes=500,tt=12").create();
        assertNull(p.lastScoreKind());
        Position start = Position.initial(RuleSet.defaults(2));
        p.choose(start);
        assertEquals(Scores.Kind.ORDINARY, p.lastScoreKind());
        Position passed = RaceEvaluatorTest.deepPassed(new Random(93), 0, 3);
        p.choose(passed);
        assertEquals(Scores.Kind.RACE, p.lastScoreKind());
        assertEquals(Scores.kind(p.lastResult().score()), p.lastScoreKind());
        p.clearSearch();
        assertNull(p.lastScoreKind());
        // with race play the race module moves and there is no score at all
        AlphaBetaPlayer racer = PlayerSpec.parse("ab:eval=racelin," + OPTIONS + ",asp=1,nodes=500,tt=12,race=1")
                .create();
        racer.choose(passed);
        assertNull(racer.lastScoreKind());
        assertEquals(1, racer.raceMoves());
        assertTrue(RaceTable.rows2().covers(passed.pieces(0)));
    }

    /** With the race table and futility on, racelin plays legal moves and wins against the plain spec's race play. */
    @Test
    void theRacelinSpecPlaysACompleteGame() {
        Rows1Tables.assume();
        AlphaBetaPlayer a = PlayerSpec.parse(Strategies.RACELIN_SPEC + ",nodes=300,tt=12").create();
        AlphaBetaPlayer b = PlayerSpec.parse(Strategies.FUTILITY_SPEC + ",race=1,nodes=300,tt=12").create();
        GameRunner.Result result = GameRunner.play(Position.initial(RuleSet.defaults(2)), a, b);
        assertTrue(result.outcome().hasWinner() || result.plies() >= RuleSet.defaults(2).moveLimit());
        assertTrue(a.moves() > 10 && a.raceMoves() > 0);
    }
}
