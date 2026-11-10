package sternhalma.search;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import java.util.Set;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

import sternhalma.engine.Geometry;
import sternhalma.engine.Layout;
import sternhalma.engine.Move;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.engine.RuleSet.LeaveGoal;
import sternhalma.nn.ClassicalPlanes;
import sternhalma.nn.Evaluation;
import sternhalma.nn.Evaluator;
import sternhalma.nn.ForwardPass;
import sternhalma.nn.Network;
import sternhalma.nn.NetworkConfig;
import sternhalma.nn.Networks;
import sternhalma.nn.Parameters;
import sternhalma.nn.PlaneSet;
import sternhalma.search.contract.PositionGenerator;
import sternhalma.search.contract.StrategyChecks;

/**
 * The classical planes and priors of {@code nn} (E11) against the classical code they copy: the linear evaluation, the
 * passed flag, and the network that starts as the classical prior inside the MCTS.
 *
 * <p>{@code nn} cannot depend on {@code search}, so it carries its own copy of the 12 features of {@link LinearEvaluator}
 * and of {@link RaceAdjudication#passed}; these tests pin the copies to the originals on many positions.
 */
class ClassicalPriorsTest {

    /** Seeded positions: random playouts, greedy playouts, and random scattered setups with fewer than 10 pieces too. */
    private interface PositionSink {
        void accept(Position position);
    }

    private static void forEachPosition(RuleSet rules, long seed, int target, PositionSink sink) {
        SplittableRandom random = new SplittableRandom(seed);
        int[] buffer = new int[Position.MAX_MOVES];
        int count = 0;
        for (int game = 0; count < target; game++) {
            Position pos = game % 4 >= 2 ? scattered(rules, random) : Position.initial(rules);
            double epsilon = game % 4 == 0 ? 1.0 : game % 4 == 1 ? 0.1 : 0.4;
            for (int ply = 0; ply < rules.moveLimit() && pos.outcome() == null; ply++) {
                count++;
                sink.accept(pos);
                int n = pos.generate(buffer);
                if (n == 0) {
                    break;
                }
                pos.make(pick(pos, buffer, n, random, epsilon));
            }
        }
    }

    private static int pick(Position pos, int[] moves, int count, SplittableRandom random, double epsilon) {
        if (random.nextDouble() < epsilon) {
            return moves[random.nextInt(count)];
        }
        Layout layout = pos.layout();
        int p = pos.sideToMove();
        int best = Integer.MIN_VALUE;
        int choice = moves[0];
        for (int i = 0; i < count; i++) {
            int gain = layout.distance(p, Move.from(moves[i])) - layout.distance(p, Move.to(moves[i]));
            if (gain > best) {
                best = gain;
                choice = moves[i];
            }
        }
        return choice;
    }

    private static Position scattered(RuleSet rules, SplittableRandom random) {
        while (true) {
            Position pos = Position.empty(rules);
            Layout layout = pos.layout();
            for (int p = 0; p < rules.players(); p++) {
                int pieces = random.nextInt(8) == 0 ? 1 + random.nextInt(Position.PIECES) : Position.PIECES;
                int guard = 0;
                while (pieces > 0 && guard++ < 10_000) {
                    int cell = random.nextInt(Geometry.CELLS);
                    if (pos.owner(cell) < 0 && layout.allowed(p, cell)) {
                        pos.put(p, cell);
                        pieces--;
                    }
                }
            }
            if (pos.pieceCount(0) > 0 && pos.pieceCount(1) > 0) {
                return pos.setSideToMove(random.nextInt(rules.players()));
            }
        }
    }

    @Test
    void theWeightsAreThoseOfLinearR2() {
        assertArrayEquals(LinearWeights.ROUND2, ClassicalPlanes.linearWeights());
    }

    @Test
    void theLinearLogitIsTheWeightedFeatureSumOfLinearEvaluatorBitForBit() {
        double[] features = new double[LinearEvaluator.FEATURES];
        LinearEvaluator evaluator = new LinearEvaluator(LinearWeights.ROUND2, LinearWeights.SCALE);
        long[] counts = new long[2];
        for (LeaveGoal leaveGoal : LeaveGoal.values()) {
            forEachPosition(RuleSet.defaults(2).withLeaveGoal(leaveGoal), 0xE11_0000L + leaveGoal.ordinal(), 60_000, pos -> {
                LinearEvaluator.features(pos, features);
                double sum = 0;
                for (int i = 0; i < LinearEvaluator.FEATURES; i++) {
                    sum += LinearWeights.ROUND2[i] * features[i];
                }
                double logit = ClassicalPlanes.linearLogit(pos);
                assertEquals(Double.doubleToRawLongBits(sum), Double.doubleToRawLongBits(logit), pos.toString());
                assertEquals(evaluator.evaluate(pos), Math.round(LinearWeights.SCALE * logit), pos.toString());
                counts[0]++;
                if (logit != 0) {
                    counts[1]++;
                }
            });
        }
        assertTrue(counts[0] >= 120_000);
        assertTrue(counts[1] > counts[0] / 2, "too few positions with a nonzero evaluation: " + counts[1]);
    }

    @Test
    void thePassedFlagIsTheOneOfTheRaceCriterion() {
        long[] counts = new long[2];
        forEachPosition(RuleSet.defaults(2), 0xE11_0100L, 80_000, pos -> {
            boolean passed = RaceAdjudication.passed(pos);
            assertEquals(passed, ClassicalPlanes.passed(pos), pos.toString());
            counts[0]++;
            if (passed) {
                counts[1]++;
            }
        });
        // scattered setups with 10 pieces each pass often enough to tell the two answers apart
        assertTrue(counts[1] > 50, "passed positions seen: " + counts[1] + " of " + counts[0]);
        assertTrue(counts[0] - counts[1] > 50);
    }

    @Test
    void aZeroNetworkGivesTheValueOfTheLinearEvaluatorAndTheDistanceGainPrior() {
        NetworkConfig config = NetworkConfig.tiny(2).withPlanes(PlaneSet.CLASSIC1);
        Parameters init = Parameters.init(config, 1); // output layers at zero: the classical prior
        Evaluator evaluator = new Evaluator(new Network(init));
        LinearEvaluator linear = new LinearEvaluator(LinearWeights.ROUND2, LinearWeights.SCALE);
        int[] checked = new int[1];
        forEachPosition(RuleSet.defaults(2), 0xE11_0200L, 300, pos -> {
            if (pos.pieceCount(0) == 0 || pos.pieceCount(1) == 0 || pos.legalMoves().length == 0) {
                return;
            }
            Evaluation e = evaluator.evaluate(pos);
            // tanh(score / 200) up to the rounding of the integer score (half a unit of 0.5 / 100)
            double expected = Math.tanh(0.5 * linear.evaluate(pos) / LinearWeights.SCALE);
            assertEquals(expected, e.values()[pos.sideToMove()], 0.003, pos.toString());
            assertEquals(-expected, e.values()[1 - pos.sideToMove()], 0.003);
            // the prior: softmax of the distance gain
            Layout layout = pos.layout();
            int mover = pos.sideToMove();
            double max = -1e9;
            for (int m : e.moves()) {
                max = Math.max(max, layout.distance(mover, Move.from(m)) - layout.distance(mover, Move.to(m)));
            }
            double sum = 0;
            for (int m : e.moves()) {
                sum += Math.exp(layout.distance(mover, Move.from(m)) - layout.distance(mover, Move.to(m)) - max);
            }
            for (int i = 0; i < e.moves().length; i++) {
                int m = e.moves()[i];
                double gain = layout.distance(mover, Move.from(m)) - layout.distance(mover, Move.to(m));
                assertEquals(Math.exp(gain - max) / sum, e.priors()[i], 1e-5);
            }
            checked[0]++;
        });
        assertTrue(checked[0] > 100);
    }

    @Test
    void anUntrainedClassicalNetworkPlaysMctsLegallyAndNeverLosesToARandomPlayer() {
        for (PlaneSet planes : new PlaneSet[] {PlaneSet.CLASSIC1, PlaneSet.CLASSIC1_PLANES}) {
            NetworkConfig config = NetworkConfig.tiny(2).withPlanes(planes);
            Parameters parameters = Parameters.init(config, 3);
            ForwardPass net = Networks.fastest(parameters);
            MctsOptions options = MctsOptions.defaults().withSims(planes.residualPriors() ? 100 : 24).withBatch(1);
            Strategy strategy = new Strategy("mcts(" + planes + ")", Set.of(2), planes.residualPriors(),
                    seed -> MctsPlayer.create(net, options, seed, "mcts"));
            Random random = new Random(0xE11L);
            StrategyChecks.assertCleanSelfPlay(strategy, PositionGenerator.played(RuleSet.defaults(2), random.nextLong(),
                    random.nextInt(60), 0.5), random.nextLong());
            if (planes.residualPriors()) {
                // the priors alone are a player: the classical prior and value, no training
                for (int i = 0; i < 6; i++) {
                    StrategyChecks.assertBeatsRandom(strategy, PositionGenerator.played(RuleSet.defaults(2),
                            random.nextLong(), random.nextInt(5), 0.0), i % 2 == 0, random.nextLong());
                }
            }
        }
    }
}
