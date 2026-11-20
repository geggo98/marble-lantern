package sternhalma.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import sternhalma.engine.Geometry;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.engine.RuleSet.ForeignCamps;

/**
 * The search under the foreign-corner switch (docs/rules.md, "Foreign corners"): race play, the race evaluation and the
 * incremental evaluation are off unless the corners are closed, and the players choose legal moves of the rule.
 */
class ForeignCampsSearchTest {

    private static RuleSet rules(int players, ForeignCamps camps) {
        return RuleSet.defaults(players).withForeignCamps(camps);
    }

    /** Both players in their goals: a passed position for the race components. */
    private static Position passed(ForeignCamps camps) {
        Position pos = Position.empty(rules(2, camps));
        for (int c : Geometry.cornerCells(pos.layout().goalCorner(0))) {
            pos.put(0, c);
        }
        for (int c : Geometry.cornerCells(pos.layout().goalCorner(1))) {
            pos.put(1, c);
        }
        return pos;
    }

    @Test
    void raceComponentsApplyOnlyToClosedCorners() {
        for (ForeignCamps camps : ForeignCamps.values()) {
            boolean closed = camps == ForeignCamps.CLOSED;
            assertEquals(closed, RaceAdjudication.applicable(rules(2, camps)), camps.name());
            assertEquals(closed, RaceEvaluator.rulesApply(rules(2, camps)), camps.name());
            assertEquals(closed, RaceAdjudication.passed(passed(camps)), camps.name());
        }
        // RaceMove looks at the rules before it touches the table, so a missing table is fine for the other rules
        assertEquals(-1, new RaceMove(null, 1).choose(passed(ForeignCamps.OPEN)));
        assertEquals(-1, new RaceMove(null, 1).choose(passed(ForeignCamps.PASS_THROUGH)));
    }

    @Test
    void incrementalEvaluationOnlyForClosedCorners() {
        LinearEvaluator evaluator = new LinearEvaluator(LinearWeights.WEIGHTS, LinearWeights.SCALE);
        for (ForeignCamps camps : ForeignCamps.values()) {
            assertEquals(camps == ForeignCamps.CLOSED, evaluator.incremental().reset(Position.initial(rules(2, camps))),
                    camps.name());
        }
    }

    /** A piece on a foreign cell has a distance between 1 and the largest home distance, so the evaluation stays sane. */
    @Test
    void playersChooseLegalMovesUnderEveryRule() {
        for (ForeignCamps camps : ForeignCamps.values()) {
            for (int players = 2; players <= 3; players++) {
                Position pos = Position.initial(rules(players, camps));
                AlphaBetaPlayer two = players == 2 ? PlayerSpec.parse("ab:eval=linear-r3,nodes=400").create() : null;
                ThreePlayerPlayer three = players == 3 ? ThreePlayerSpec.parse("ab3:nodes=300,tt=12").create() : null;
                for (int ply = 0; ply < 24 && pos.outcome() == null; ply++) {
                    int[] legal = pos.legalMoves();
                    int move = two != null ? two.choose(pos) : three.choose(pos);
                    assertTrue(Arrays.stream(legal).anyMatch(m -> m == move), camps + " " + players + "p ply " + ply);
                    pos.make(move);
                }
            }
        }
    }
}
