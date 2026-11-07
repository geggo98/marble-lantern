package sternhalma.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

import sternhalma.engine.Geometry;
import sternhalma.engine.Layout;
import sternhalma.engine.Move;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.engine.RuleSet.LeaveGoal;

/**
 * Experiment E02: the table-driven {@link LinearEvaluator} must return bit-identical features and the same
 * integer score as the old cell-scanning code ({@link LinearEvaluatorReference}) on more than a million
 * positions, for both leaveGoal values. One rounding step in a different order would move a score by 1 and
 * change the search tree, so the features are compared as raw IEEE bits, not with a tolerance.
 *
 * <p>Scores are compared for the live weights, the frozen R1 weights and seeded random weights; random weights
 * make every feature matter, so an error in a feature that the fitted weights barely use is still caught.
 */
class LinearEvaluatorEquivalenceTest {

    private static final int POSITIONS_PER_RULE_SET = 520_000;

    @Test
    void featuresAndScoresAreBitIdenticalToTheOldCodeOnOverAMillionPositions() {
        long total = 0;
        long[] distinctScores = new long[1];
        for (LeaveGoal leaveGoal : LeaveGoal.values()) {
            total += run(RuleSet.defaults(2).withLeaveGoal(leaveGoal), 0xE02_1000L + leaveGoal.ordinal(),
                    POSITIONS_PER_RULE_SET, distinctScores);
        }
        // three players: the evaluator reads players 0 and 1 only, but player 2's pieces block steps and jumps
        total += run(RuleSet.defaults(3), 0xE02_1003L, 60_000, distinctScores);
        assertTrue(total >= 1_000_000, "only " + total + " positions");
        assertTrue(distinctScores[0] > total / 2, "too few positions with a nonzero score: " + distinctScores[0]);
    }

    private static long run(RuleSet rules, long seed, int target, long[] nonzero) {
        SplittableRandom random = new SplittableRandom(seed);
        double[][] weightSets = new double[5][];
        weightSets[0] = LinearWeights.WEIGHTS;
        weightSets[1] = LinearWeights.R1;
        for (int k = 2; k < weightSets.length; k++) {
            weightSets[k] = new double[LinearEvaluator.FEATURES];
            for (int i = 0; i < LinearEvaluator.FEATURES; i++) {
                weightSets[k][i] = (random.nextDouble() - 0.5) * (k == 4 ? 7.3 : 0.6);
            }
        }
        LinearEvaluator[] fast = new LinearEvaluator[weightSets.length];
        LinearEvaluatorReference[] old = new LinearEvaluatorReference[weightSets.length];
        for (int k = 0; k < weightSets.length; k++) {
            fast[k] = new LinearEvaluator(weightSets[k], LinearWeights.SCALE);
            old[k] = new LinearEvaluatorReference(weightSets[k], LinearWeights.SCALE);
        }
        double[] x = new double[LinearEvaluator.FEATURES];
        double[] y = new double[LinearEvaluator.FEATURES];
        long positions = 0;
        int[] buffer = new int[Position.MAX_MOVES];
        for (int game = 0; positions < target; game++) {
            int mode = game % 4; // random playout, greedy playout, two from a random scattered setup
            Position pos = mode >= 2 ? scattered(rules, random) : Position.initial(rules);
            double epsilon = mode == 0 ? 1.0 : mode == 1 ? 0.1 : 0.4;
            for (int ply = 0; ply < rules.moveLimit() && pos.outcome() == null; ply++) {
                if (pos.sideToMove() < 2) {
                    positions++;
                    LinearEvaluator.features(pos, x);
                    LinearEvaluatorReference.features(pos, y);
                    for (int i = 0; i < x.length; i++) {
                        if (Double.doubleToRawLongBits(x[i]) != Double.doubleToRawLongBits(y[i])) {
                            fail("feature " + LinearEvaluator.NAMES[i] + ": " + x[i] + " vs old " + y[i]
                                    + ", seed " + seed + " game " + game + " ply " + ply + "\n" + pos);
                        }
                    }
                    for (int k = 0; k < fast.length; k++) {
                        int expected = old[k].evaluate(pos);
                        assertEquals(expected, fast[k].evaluate(pos), "weights " + k + ", seed " + seed + " game "
                                + game + " ply " + ply + "\n" + pos);
                        if (k == 0 && expected != 0) {
                            nonzero[0]++;
                        }
                    }
                }
                int count = pos.generate(buffer);
                if (count == 0) {
                    break;
                }
                pos.make(pick(pos, buffer, count, random, epsilon));
            }
        }
        return positions;
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

    /** Every player's pieces on random allowed cells; sometimes fewer than 10, to cover partial setups. */
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
            pos.setSideToMove(random.nextInt(Math.min(rules.players(), 2)));
            if (pos.outcome() == null) {
                return pos;
            }
        }
    }
}
