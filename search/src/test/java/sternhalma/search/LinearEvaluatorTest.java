package sternhalma.search;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sternhalma.engine.Geometry;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;

class LinearEvaluatorTest {

    /** Random positions from random games, some of them long, so that pieces reach the goal. */
    private static List<Position> randomPositions(int count) {
        Random random = new Random(42);
        List<Position> result = new ArrayList<>();
        while (result.size() < count) {
            Position pos = Position.initial(RuleSet.defaults(2));
            int plies = random.nextInt(150);
            for (int i = 0; i < plies && pos.outcome() == null; i++) {
                int[] moves = pos.legalMoves();
                if (moves.length == 0) {
                    break;
                }
                pos.make(moves[random.nextInt(moves.length)]);
            }
            if (pos.outcome() == null) {
                result.add(pos);
            }
        }
        return result;
    }

    @Test
    void aColourSwappedMirrorImageScoresTheSame() {
        Evaluator linear = Evaluators.byName("linear");
        for (Position pos : randomPositions(400)) {
            Position image = pos.transformed(Geometry::playerFlip, true);
            assertEquals(linear.evaluate(pos), linear.evaluate(image), pos.toString());
        }
    }

    @Test
    void featuresAreAntisymmetricInThePlayersExceptTheTempoTerm() {
        double[] x = new double[LinearEvaluator.FEATURES];
        double[] y = new double[LinearEvaluator.FEATURES];
        for (Position pos : randomPositions(200)) {
            LinearEvaluator.features(pos, x);
            Position other = pos.transformed(c -> c, false).setSideToMove(1 - pos.sideToMove());
            LinearEvaluator.features(other, y);
            for (int i = 0; i < LinearEvaluator.FEATURES - 1; i++) {
                assertEquals(-x[i], y[i], 1e-9, LinearEvaluator.NAMES[i]);
            }
            assertEquals(1.0, x[LinearEvaluator.FEATURES - 1]);
        }
    }

    @Test
    void theStartPositionHasOnlyTheTempoTerm() {
        double[] x = new double[LinearEvaluator.FEATURES];
        LinearEvaluator.features(Position.initial(RuleSet.defaults(2)), x);
        double[] expected = new double[LinearEvaluator.FEATURES];
        expected[LinearEvaluator.FEATURES - 1] = 1.0;
        assertArrayEquals(expected, x, 1e-12);
    }

    @Test
    void withOnlyTheDistanceWeightItRanksLikeTheDistanceEvaluator() {
        double[] w = new double[LinearEvaluator.FEATURES];
        w[0] = -1.0;
        Evaluator linear = new LinearEvaluator(w, DistanceEvaluator.UNIT);
        Evaluator distance = new DistanceEvaluator();
        for (Position pos : randomPositions(100)) {
            assertEquals(distance.evaluate(pos), linear.evaluate(pos));
        }
    }

    @Test
    void theFittedEvaluatorPrefersBeingCloserToTheGoal() {
        // player 0 makes the move that gains the most distance; then player 1, to move, must be behind
        Position pos = Position.initial(RuleSet.defaults(2));
        int best = -1;
        int bestSum = Integer.MAX_VALUE;
        for (int m : pos.legalMoves()) {
            pos.make(m);
            if (pos.distanceSum(0) < bestSum) {
                bestSum = pos.distanceSum(0);
                best = m;
            }
            pos.unmake();
        }
        pos.make(best);
        Evaluator linear = Evaluators.byName("linear");
        Position before = Position.initial(RuleSet.defaults(2)).setSideToMove(1);
        assertTrue(linear.evaluate(pos) < linear.evaluate(before),
                "a move forward by the opponent must lower the score of the side to move");
    }

    @Test
    void weightsReadFromAFileMatchTheConstructor(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("w.txt");
        Files.writeString(file, "# comment\n50\n-1 0 0 0 0 0 0 0.5 0 0 0 0.25\n");
        double[] w = {-1, 0, 0, 0, 0, 0, 0, 0.5, 0, 0, 0, 0.25};
        Evaluator fromFile = Evaluators.byName("linear-file:" + file);
        Evaluator direct = new LinearEvaluator(w, 50);
        for (Position pos : randomPositions(50)) {
            assertEquals(direct.evaluate(pos), fromFile.evaluate(pos));
        }
    }

    @Test
    void theFitRecoversKnownWeightsOnSyntheticData() {
        Random random = new Random(7);
        double[] truth = new double[LinearEvaluator.FEATURES];
        truth[0] = -0.3;
        truth[1] = 0.2;
        truth[LinearEvaluator.FEATURES - 1] = 0.1;
        List<LinearTraining.Sample> data = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            double[] x = new double[LinearEvaluator.FEATURES];
            for (int j = 0; j < LinearEvaluator.FEATURES - 1; j++) {
                x[j] = random.nextGaussian() * 3;
            }
            x[LinearEvaluator.FEATURES - 1] = 1;
            double p = LinearTraining.sigmoid(LinearTraining.dot(truth, x));
            data.add(new LinearTraining.Sample(i, 0, random.nextDouble() < p ? 1.0 : 0.0, x));
        }
        double[] w = LinearTraining.logistic(data, LinearTraining.mask("all"), 1e-9);
        for (int j = 0; j < LinearEvaluator.FEATURES; j++) {
            assertEquals(truth[j], w[j], 0.05, LinearEvaluator.NAMES[j]);
        }
    }
}
