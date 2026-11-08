package sternhalma.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Map;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

import sternhalma.engine.Geometry;
import sternhalma.engine.Layout;
import sternhalma.engine.Move;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.engine.RuleSet.LeaveGoal;

/**
 * Experiment E13: the incremental evaluation ({@link EvalState}) must return the same integer as the full
 * {@link Evaluator#evaluate}, bit for bit, and keep sums equal to the full sums, on more than a million positions
 * along random, greedy and scattered-start playouts under both leaveGoal values. Every position is also left through
 * a probe move that is taken back, so the stack discipline of {@code unmake} is covered. The full evaluation is the
 * oracle: the same evaluators, called through {@link Evaluator#evaluate}, never see the state.
 *
 * <p>The evaluators are the live weights without {@code bestGain}, random weights with {@code bestGain} in both
 * modes (random weights make every feature count), the frozen {@code linear-r3} and its race wrapper
 * {@code racelin1-r3} (BEST_SPEC's evaluator), whose race values must pass through unchanged.
 */
class IncrementalEvaluationTest {

    private static final int POSITIONS_PER_RULE_SET = 520_000;

    private static Evaluator[] evaluators(SplittableRandom random) {
        double[] weights13 = new double[LinearEvaluator.GAIN_FEATURES];
        double[] weights13b = new double[LinearEvaluator.GAIN_FEATURES];
        for (int i = 0; i < weights13.length; i++) {
            weights13[i] = (random.nextDouble() - 0.5) * 0.7;
            weights13b[i] = (random.nextDouble() - 0.5) * 7.3;
        }
        return new Evaluator[] {
            new LinearEvaluator(LinearWeights.WEIGHTS, LinearWeights.SCALE),
            new LinearEvaluator(weights13, LinearWeights.SCALE, LinearEvaluator.GainMode.BOTH),
            new LinearEvaluator(weights13b, LinearWeights.SCALE, LinearEvaluator.GainMode.MOVER),
            Evaluators.byName("linear-r3", Map.of()),
            Evaluators.byName("racelin1-r3", Map.of()),
        };
    }

    @Test
    void incrementalEqualsFullOnOverAMillionPositionsUnderBothLeaveGoalValues() {
        long total = 0;
        for (LeaveGoal leaveGoal : LeaveGoal.values()) {
            total += run(RuleSet.defaults(2).withLeaveGoal(leaveGoal), 0xE13_1000L + leaveGoal.ordinal(),
                    POSITIONS_PER_RULE_SET);
        }
        assertTrue(total >= 1_000_000, "only " + total + " positions");
    }

    private static long run(RuleSet rules, long seed, int target) {
        SplittableRandom random = new SplittableRandom(seed);
        Evaluator[] full = evaluators(random);
        EvalState[] states = new EvalState[full.length];
        Position[] copies = new Position[full.length];
        for (int k = 0; k < full.length; k++) {
            states[k] = full[k].incremental();
            assertNotNull(states[k], "evaluator " + k + " has no incremental state");
        }
        long positions = 0;
        int[] buffer = new int[Position.MAX_MOVES];
        for (int game = 0; positions < target; game++) {
            int mode = game % 4; // random playout, greedy playout, two from a random scattered setup
            Position pos = mode >= 2 ? scattered(rules, random) : Position.initial(rules);
            double epsilon = mode == 0 ? 1.0 : mode == 1 ? 0.1 : 0.4;
            for (int k = 0; k < full.length; k++) {
                copies[k] = pos.copy();
                assertTrue(states[k].reset(copies[k]));
            }
            for (int ply = 0; ply < rules.moveLimit() && pos.outcome() == null; ply++) {
                String where = "seed " + seed + " game " + game + " ply " + ply;
                positions++;
                check(full, states, copies, where);
                int count = pos.generate(buffer);
                if (count == 0) {
                    break;
                }
                // a probe: play a move, look, take it back, look again
                int probe = buffer[random.nextInt(count)];
                for (int k = 0; k < full.length; k++) {
                    states[k].make(copies[k], probe);
                }
                check(full, states, copies, where + " after probe " + Move.toString(probe));
                for (int k = 0; k < full.length; k++) {
                    states[k].unmake(copies[k]);
                }
                check(full, states, copies, where + " after taking the probe back");
                int move = pick(pos, buffer, count, random, epsilon);
                pos.make(move);
                for (int k = 0; k < full.length; k++) {
                    states[k].make(copies[k], move);
                }
            }
        }
        return positions;
    }

    private static void check(Evaluator[] full, EvalState[] states, Position[] copies, String where) {
        for (int k = 0; k < full.length; k++) {
            states[k].verify(copies[k]);
            int expected = full[k].evaluate(copies[k]);
            int actual = states[k].evaluate(copies[k]);
            if (expected != actual) {
                fail("evaluator " + k + ": incremental " + actual + " against full " + expected + ", " + where + "\n"
                        + copies[k]);
            }
        }
    }

    @Test
    void evaluatorsWithoutAStateStayUnchanged() {
        assertNull(Evaluators.byName("distance", Map.of()).incremental());
        assertNull(Evaluators.byName("stage4", Map.of()).incremental());
        assertNull(new RaceEvaluator(p -> 0, null).incremental());
    }

    @Test
    void threePlayersAndOversizedSetupsAreRefusedSoTheSearchEvaluatesFromScratch() {
        EvalState state = new LinearEvaluator().incremental();
        assertFalse(state.reset(Position.initial(RuleSet.defaults(3))));
        Position crowded = Position.empty(RuleSet.defaults(2));
        Layout layout = crowded.layout();
        int placed = 0;
        for (int c = 0; c < Geometry.CELLS && placed <= Position.PIECES; c++) {
            if (layout.allowed(0, c)) {
                crowded.put(0, c);
                placed++;
            }
        }
        assertFalse(state.reset(crowded));
        assertTrue(state.reset(Position.initial(RuleSet.defaults(2))));
    }

    /** The assert mode runs a whole search with every evaluation compared; the search itself is unchanged. */
    @Test
    void assertModeSearchesTheSameTreeAsTheFullEvaluation() {
        PlayerSpec spec = PlayerSpec.parse(Strategies.BEST_SPEC);
        Evaluator evaluator = Evaluators.byName(spec.evaluator(), spec.weights());
        Evaluator hidden = new Evaluator() {
            @Override
            public int evaluate(Position position) {
                return evaluator.evaluate(position);
            }

            @Override
            public boolean futilitySafe(Position position, int depth) {
                return evaluator.futilitySafe(position, depth);
            }
        };
        assertNull(hidden.incremental());
        int compared = 0;
        for (Position pos : ReferencePositions.positions()) {
            AlphaBeta checked = new AlphaBeta(Evaluators.byName(spec.evaluator(), spec.weights()), spec.tableBits(),
                    true, spec.options(), true);
            AlphaBeta plain = new AlphaBeta(hidden, spec.tableBits(), true, spec.options());
            SearchResult a = checked.search(pos, 30, Long.MAX_VALUE, 3000);
            SearchResult b = plain.search(pos, 30, Long.MAX_VALUE, 3000);
            assertEquals(b.move(), a.move());
            assertEquals(b.score(), a.score());
            assertEquals(b.nodes(), a.nodes());
            assertEquals(b.depth(), a.depth());
            compared++;
        }
        assertEquals(ReferencePositions.COUNT, compared);
    }

    private static int pick(Position pos, int[] moves, int count, SplittableRandom random, double epsilon) {
        if (random.nextDouble() < epsilon) {
            return moves[random.nextInt(count)];
        }
        Layout layout = pos.layout();
        int p = pos.sideToMove();
        int best = Integer.MIN_VALUE;
        int choice = moves[0];
        int ties = 0;
        for (int i = 0; i < count; i++) {
            int m = moves[i];
            int gain = layout.distance(p, Move.from(m)) - layout.distance(p, Move.to(m));
            if (gain > best) {
                best = gain;
                choice = m;
                ties = 1;
            } else if (gain == best && random.nextInt(++ties) == 0) {
                choice = m;
            }
        }
        return choice;
    }

    /** Both players' pieces on random allowed cells; sometimes fewer than 10, to cover partial setups. */
    private static Position scattered(RuleSet rules, SplittableRandom random) {
        while (true) {
            Position pos = Position.empty(rules);
            Layout layout = pos.layout();
            for (int p = 0; p < rules.players(); p++) {
                int pieces = random.nextInt(8) == 0 ? 1 + random.nextInt(Position.PIECES) : Position.PIECES;
                while (pieces > 0) {
                    int c = random.nextInt(Geometry.CELLS);
                    if (pos.owner(c) < 0 && layout.allowed(p, c)) {
                        pos.put(p, c);
                        pieces--;
                    }
                }
            }
            pos.setSideToMove(random.nextInt(2));
            if (pos.outcome() == null) {
                return pos;
            }
        }
    }
}
