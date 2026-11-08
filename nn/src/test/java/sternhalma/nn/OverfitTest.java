package sternhalma.nn;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import sternhalma.engine.Move;
import sternhalma.engine.Position;

/**
 * Overfitting 8 positions (docs/training.md, "Optimiser and training"; work plan A5).
 *
 * <p>A teacher network of the same config produces the policy and value targets, so the targets are exactly
 * representable: the factorised move logit "from + to" cannot express every distribution over moves, but it can
 * express the teacher's. A student from {@link Parameters#init} with another seed trains with AdamW on the 8
 * positions until the policy KL and the value MSE meet the documented bounds. A trainer with a broken gradient,
 * a broken optimiser step or a mix-up of samples cannot reach them; a trainer that is merely slow shows up as
 * a step count near the limit.
 */
class OverfitTest {

    private static final int MAX_STEPS = 3000;
    private static final double KL_BOUND = 0.02;
    private static final double VALUE_MSE_BOUND = 1e-3;

    private record Teacher(TrainingBatch batch, double scale, double entropyRatio, double meanAbsValue) {
    }

    /** Teacher targets for the positions; the weight bound grows until the targets are peaked enough. */
    private static Teacher teacher(NetworkConfig config, List<Position> positions) {
        float initBound = (float) Math.sqrt(6.0 / (7 * config.channels()));
        for (double scale = 1.0; scale <= 4.0; scale += 0.05) {
            Parameters parameters = Parameters.uniform(config, 77, (float) (initBound * scale));
            Network network = new Network(parameters);
            TrainingBatch.Builder builder = new TrainingBatch.Builder(config.players());
            double entropy = 0;
            double logMoves = 0;
            double absValue = 0;
            for (Position position : positions) {
                float[] input = new float[Encoder.INPUT_SIZE];
                Encoder.encode(position, input, 0);
                float[] policy = new float[Encoder.POLICY_SIZE];
                float[] values = new float[config.players()];
                network.forward(input, 1, policy, values);
                Orientation orientation = Orientation.of(position);
                int[] moves = position.legalMoves();
                int[] from = new int[moves.length];
                int[] to = new int[moves.length];
                double[] logits = new double[moves.length];
                double max = Double.NEGATIVE_INFINITY;
                for (int m = 0; m < moves.length; m++) {
                    from[m] = orientation.toCanonical(Move.from(moves[m]));
                    to[m] = orientation.toCanonical(Move.to(moves[m]));
                    logits[m] = Encoder.moveLogit(orientation, policy, 0, moves[m]);
                    max = Math.max(max, logits[m]);
                }
                double[] target = new double[moves.length];
                double sum = 0;
                for (int m = 0; m < moves.length; m++) {
                    target[m] = Math.exp(logits[m] - max);
                    sum += target[m];
                }
                builder.add(input, from, to, target, values.clone(), 1f, 1f);
                logMoves += Math.log(moves.length);
                absValue += Arrays.stream(new double[] {values[0], values[1]}).map(Math::abs).average().orElse(0);
            }
            TrainingBatch batch = builder.build();
            entropy = Arrays.stream(batch.targetEntropy()).sum();
            double ratio = entropy / logMoves;
            if (ratio <= 0.6) {
                return new Teacher(batch, scale, ratio, absValue / positions.size());
            }
        }
        throw new AssertionError("no teacher scale gives targets with entropy at most 0.6 of the log move count");
    }

    @Test
    void studentFitsTheTeacherTargetsOfEightPositions(TestReporter reporter) {
        NetworkConfig config = NetworkConfig.tiny(2);
        List<Position> candidates = Playouts.positions(2, 8, 40, 2026);
        List<Position> positions = new ArrayList<>(TestBatches.pick(candidates, 8));
        Teacher teacher = teacher(config, positions);
        TrainingBatch batch = teacher.batch();
        assertTrue(teacher.meanAbsValue() > 0.05, "the value targets must not be all about 0: " + teacher.meanAbsValue());

        Parameters student = Parameters.init(config, 5);
        ReferenceTrainer trainer = new ReferenceTrainer(student, 1.0);
        Optimizer optimizer = new Optimizer(student, Optimizer.Settings.adamw());
        double firstKl = Double.NaN;
        double kl = Double.NaN;
        double valueMse = Double.NaN;
        int reached = -1;
        for (int step = 0; step < MAX_STEPS; step++) {
            trainer.zeroGradients();
            ReferenceTrainer.Result result = trainer.accumulate(batch, 4, 1);
            if (step == 0) {
                firstKl = result.kl();
            }
            kl = result.kl();
            valueMse = result.valueLoss();
            if (kl < KL_BOUND && valueMse < VALUE_MSE_BOUND) {
                reached = step;
                break;
            }
            optimizer.step(trainer.gradients(), 1e-2);
        }
        reporter.publishEntry("overfit", String.format(Locale.ROOT,
                "teacher scale %.1f x init bound, entropy/log(moves) %.3f, mean |value| %.3f; start KL %.3f; "
                        + "reached KL %.4f, value MSE %.2e after %d steps (limit %d)",
                teacher.scale(), teacher.entropyRatio(), teacher.meanAbsValue(), firstKl, kl, valueMse, reached,
                MAX_STEPS));
        assertTrue(firstKl > 0.1, "the start must be far from the targets, KL " + firstKl);
        assertTrue(reached >= 0, "no fit after " + MAX_STEPS + " steps: KL " + kl + ", value MSE " + valueMse);
    }
}
