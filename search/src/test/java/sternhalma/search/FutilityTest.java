package sternhalma.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;

/**
 * Futility pruning (round-2 plan E06): options and spec keys, the exclusions of
 * {@link AlphaBeta#futilityAllowed}, that the root is never pruned, and the bound property: where the futility
 * bound holds for every move, pruning at depth 1 changes no value above alpha and keeps every fail low a fail
 * low. The win and defence cases are in {@link WinInOneTest}.
 */
class FutilityTest {

    private static final String OPTIONS = "pvs=1,killers=1,history=1,lmr=2,asp=1,ttrep=1,mate=1,partial=1";
    private static final int MARGIN = 20;
    private static final int GAIN = 30;
    private static final SearchOptions FUT =
            PlayerSpec.parse("ab:" + OPTIONS + ",fut=" + MARGIN + ",futk=" + GAIN + ",fut2=40").options();
    private static final SearchOptions OFF = PlayerSpec.parse("ab:" + OPTIONS).options();
    private static final Evaluator EVAL = Evaluators.byName("linear-r2");

    private static Position randomPosition(RuleSet rules, long seed, int plies) {
        Random random = new Random(seed);
        Position pos = Position.initial(rules);
        for (int i = 0; i < plies; i++) {
            int[] moves = pos.legalMoves();
            pos.make(moves[random.nextInt(moves.length)]);
        }
        return pos;
    }

    @Test
    void specsParseTheFutilityKeysAndOffMeansTheOldOptions() {
        SearchOptions o = PlayerSpec.parse("ab:fut=20,futk=25,fut2=40").options();
        assertEquals(20, o.futility());
        assertEquals(25, o.futilityGain());
        assertEquals(40, o.futility2());
        assertTrue(o.futilityOn());
        assertEquals("fut=20,fut2=40,futk=25", o.describe());
        assertEquals(SearchOptions.DEFAULT_FUTILITY_GAIN, PlayerSpec.parse("ab:fut=1").options().futilityGain());
        // off: equal to the options without the keys, whatever futk says, and the player name is unchanged
        assertEquals(SearchOptions.DEFAULT, PlayerSpec.parse("ab:fut=0,futk=20,fut2=0").options());
        assertEquals(OFF, PlayerSpec.parse("ab:" + OPTIONS + ",fut=0").options());
        assertEquals(PlayerSpec.parse("ab:" + OPTIONS).create().name(),
                PlayerSpec.parse("ab:" + OPTIONS + ",fut=0,fut2=0").create().name());
        assertTrue(PlayerSpec.parse("ab:fut=1").create().name().contains("fut=1,futk=30"));
        assertThrows(IllegalArgumentException.class, () -> PlayerSpec.parse("ab:fut=-1"));
        assertThrows(IllegalArgumentException.class, () -> PlayerSpec.parse("ab:fut=1,futk=-1"));
    }

    @Test
    void theContractCoversTheFutilitySpecAndBestSpecIsUnchanged() {
        assertTrue(Strategies.registered().stream().anyMatch(s -> s.name().startsWith(Strategies.FUTILITY_SPEC + ",")));
        assertTrue(PlayerSpec.parse(Strategies.FUTILITY_SPEC).options().futilityOn());
        assertFalse(PlayerSpec.parse(Strategies.BEST_SPEC).options().futilityOn());
    }

    @Test
    void theExclusionsTurnFutilityOff() {
        RuleSet rules = RuleSet.defaults(2);
        Position pos = randomPosition(rules, 1, 20);
        assertTrue(AlphaBeta.futilityAllowed(pos, 1, 0, EVAL));
        assertTrue(AlphaBeta.futilityAllowed(pos, 2, -4999, EVAL));
        // alpha a mate score or beyond the ordinary band
        assertFalse(AlphaBeta.futilityAllowed(pos, 1, AlphaBeta.MATE - 5, EVAL));
        assertFalse(AlphaBeta.futilityAllowed(pos, 1, -(AlphaBeta.MATE - 5), EVAL));
        assertFalse(AlphaBeta.futilityAllowed(pos, 1, AlphaBeta.FUTILITY_SCORE_LIMIT, EVAL));
        assertFalse(AlphaBeta.futilityAllowed(pos, 1, -AlphaBeta.FUTILITY_SCORE_LIMIT, EVAL));
        // the evaluator's race-band hook
        Evaluator unsafe = new Evaluator() {
            @Override
            public int evaluate(Position position) {
                return EVAL.evaluate(position);
            }

            @Override
            public boolean futilitySafe(Position position, int depth) {
                return false;
            }
        };
        assertFalse(AlphaBeta.futilityAllowed(pos, 1, 0, unsafe));
        // the move limit within depth plies
        Position late = randomPosition(rules.withMoveLimit(30), 1, 28);
        assertTrue(AlphaBeta.futilityAllowed(late, 1, 0, EVAL));
        assertFalse(AlphaBeta.futilityAllowed(late, 2, 0, EVAL));
        Position last = randomPosition(rules.withMoveLimit(30), 1, 29);
        assertFalse(AlphaBeta.futilityAllowed(last, 1, 0, EVAL));
        // repetition draws are scored by distance, outside the bound
        Position repetition = randomPosition(rules.withRepetition(RuleSet.Repetition.REPETITION_IS_DRAW), 1, 20);
        assertFalse(AlphaBeta.futilityAllowed(repetition, 1, 0, EVAL));
        // near-win thresholds: a goal with CORNER_SIZE - depth pieces, either side
        assertFalse(AlphaBeta.futilityAllowed(WinInOneTest.winInOne(9), 1, 0, EVAL));
        assertTrue(AlphaBeta.futilityAllowed(WinInOneTest.winInOne(8), 1, 0, EVAL));
        assertFalse(AlphaBeta.futilityAllowed(WinInOneTest.winInOne(8), 2, 0, EVAL));
        assertTrue(AlphaBeta.futilityAllowed(WinInOneTest.winInOne(7), 2, 0, EVAL));
        assertFalse(AlphaBeta.futilityAllowed(WinInOneTest.threat(9), 1, 0, EVAL));
        assertTrue(AlphaBeta.futilityAllowed(WinInOneTest.threat(7), 2, 0, EVAL));
    }

    /** The same exclusions seen through the search: an excluded node prunes nothing, an allowed one does. */
    @Test
    void excludedNodesPruneNothingInTheSearch() {
        RuleSet limited = RuleSet.defaults(2).withMoveLimit(30);
        Position late = randomPosition(limited, 3, 28);
        int alpha = EVAL.evaluate(late) + 400;
        AlphaBeta ab = new AlphaBeta(EVAL, 16, true, FUT);
        assertTrue(ab.searchNode(late, 2, alpha, alpha + 1) <= alpha);
        // only the depth-1 nodes below may prune (one ply before the limit they may not either: ply 29 + 1 = 30)
        assertEquals(0, ab.futilityCuts());
        Position early = randomPosition(RuleSet.defaults(2), 3, 28);
        ab = new AlphaBeta(EVAL, 16, true, FUT);
        int alphaEarly = EVAL.evaluate(early) + 400;
        ab.searchNode(early, 2, alphaEarly, alphaEarly + 1);
        assertTrue(ab.futilityCuts() > 0);
    }

    /** searchRoot never prunes: a depth-1 search, which has only the root as interior node, is unchanged. */
    @Test
    void theRootIsNeverPruned() {
        for (long seed = 1; seed <= 10; seed++) {
            Position pos = randomPosition(RuleSet.defaults(2), seed, 10 + (int) seed);
            assertEquals(new AlphaBeta(EVAL, 16, true, OFF).search(pos, 1),
                    new AlphaBeta(EVAL, 16, true, FUT).search(pos, 1));
        }
    }

    /** With futility off the new code path is inert: identical results, nodes and depths at a node limit. */
    @Test
    void futilityOffSearchesTheSameTree() {
        SearchOptions explicitOff = OFF.withFutility(0, 17, 0);
        assertEquals(OFF, explicitOff);
        for (long seed = 1; seed <= 4; seed++) {
            Position pos = randomPosition(RuleSet.defaults(2), seed, 20);
            AlphaBeta a = new AlphaBeta(EVAL, 16, true, OFF);
            AlphaBeta b = new AlphaBeta(EVAL, 16, true, explicitOff);
            assertEquals(a.search(pos, 30, Long.MAX_VALUE, 5000), b.search(pos, 30, Long.MAX_VALUE, 5000));
            assertEquals(0, b.futilityCuts());
        }
    }

    /** The value of a depth-1 node at height 1 by plain enumeration (children at height 2). */
    private static int plainDepth1(Position pos) {
        int best = Integer.MIN_VALUE;
        for (int m : pos.legalMoves()) {
            pos.make(m);
            int v = pos.outcome() != null || pos.legalMoves().length == 0
                    ? -AlphaBeta.terminalScore(pos, 2) : -EVAL.evaluate(pos);
            pos.unmake();
            best = Math.max(best, v);
        }
        return best;
    }

    /** Whether value(move) &lt;= eval(node) + futk x gain + margin holds for every move of the node. */
    private static boolean boundHolds(Position pos) {
        int stand = EVAL.evaluate(pos);
        for (int m : pos.legalMoves()) {
            int g = DeltaProbe.gain(pos, m);
            pos.make(m);
            int v = pos.outcome() != null || pos.legalMoves().length == 0
                    ? -AlphaBeta.terminalScore(pos, 2) : -EVAL.evaluate(pos);
            pos.unmake();
            if (v > stand + GAIN * g + MARGIN) {
                return false;
            }
        }
        return true;
    }

    /**
     * The bound property (plan E06): on positions where the bound holds for every move, the depth-1 result with
     * futility equals the plain value whenever that value is above alpha, and is at most alpha otherwise. Positions
     * that violate the bound are counted and printed, not asserted.
     */
    @Test
    void whereTheBoundHoldsPruningKeepsEveryValueAboveAlpha() {
        List<Position> positions = DeltaProbe.positions(DeltaProbe.DEFAULT_SPEC.replace("nodes=1000", "nodes=300"),
                8, 77);
        int checked = 0;
        int violated = 0;
        long cuts = 0;
        for (Position pos : positions) {
            if (checked == 200) {
                break;
            }
            if (!boundHolds(pos)) {
                violated++;
                continue;
            }
            checked++;
            int v = plainDepth1(pos);
            int stand = EVAL.evaluate(pos);
            for (int alpha : new int[] {v - 100, v - 20, v - 1, v, v + 1, v + 20, stand, stand + 60, stand + 200}) {
                AlphaBeta ab = new AlphaBeta(EVAL, 10, false, FUT);
                int r = ab.searchNode(pos, 1, alpha, AlphaBeta.MATE + 1);
                cuts += ab.futilityCuts();
                if (v > alpha) {
                    assertEquals(v, r, "alpha " + alpha + " at " + pos);
                } else {
                    assertTrue(r <= alpha, "fail low must stay a fail low: alpha " + alpha + ", got " + r);
                }
            }
        }
        System.out.println("FutilityTest bound property: " + checked + " positions checked, " + violated
                + " skipped because the bound (futk=" + GAIN + ", margin " + MARGIN + ") fails for some move, "
                + cuts + " cuts");
        assertEquals(200, checked, "enough positions where the bound holds");
        assertTrue(cuts > 100, "the test must exercise the pruning, cuts " + cuts);
    }
}
