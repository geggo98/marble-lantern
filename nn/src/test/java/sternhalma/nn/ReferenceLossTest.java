package sternhalma.nn;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import sternhalma.engine.Move;
import sternhalma.engine.Position;

/**
 * Tests of the policy and value losses (docs/training.md, "Tests of Part A", "Policy loss bugs", "Value loss
 * bugs"). Each test names the bug it must catch.
 */
class ReferenceLossTest {

    private static float[] policy(Random random, double spread) {
        float[] p = new float[Encoder.POLICY_SIZE];
        for (int i = 0; i < p.length; i++) {
            p[i] = (float) ((random.nextDouble() * 2 - 1) * spread);
        }
        return p;
    }

    /** logZ - l_m for one-hot targets and the loss, computed here from the formulas, independent of the code. */
    private static double expectedLoss(double[] logits, double[] target) {
        double max = Double.NEGATIVE_INFINITY;
        for (double l : logits) {
            max = Math.max(max, l);
        }
        double sum = 0;
        for (double l : logits) {
            sum += Math.exp(l - max);
        }
        double logZ = max + Math.log(sum);
        double loss = 0;
        for (int m = 0; m < logits.length; m++) {
            if (target[m] > 0) {
                loss += target[m] * (logZ - logits[m]);
            }
        }
        return loss;
    }

    private static double[] softmax(double[] logits) {
        double max = Arrays.stream(logits).max().getAsDouble();
        double sum = Arrays.stream(logits).map(l -> Math.exp(l - max)).sum();
        return Arrays.stream(logits).map(l -> Math.exp(l - max) / sum).toArray();
    }

    /**
     * Two moves with distinct from and to cells and distinct logit values for from and to. The gradient must
     * land in the "from" channel of the start cell and the "to" channel of the end cell (swapping the channels
     * fails), and nowhere else.
     */
    @Test
    void handComputedLossAndGradient() {
        float[] policy = new float[Encoder.POLICY_SIZE];
        policy[Encoder.policyIndex(10, Encoder.FROM)] = 1.0f;
        policy[Encoder.policyIndex(20, Encoder.TO)] = 2.0f;
        policy[Encoder.policyIndex(11, Encoder.FROM)] = 0.5f;
        policy[Encoder.policyIndex(21, Encoder.TO)] = -1.0f;
        // Swapped channels would read these instead and give other logits.
        policy[Encoder.policyIndex(10, Encoder.TO)] = 7.0f;
        policy[Encoder.policyIndex(20, Encoder.FROM)] = -5.0f;
        int[] from = {10, 11};
        int[] to = {20, 21};
        float[] target = {0.7f, 0.3f};
        double[] l = {3.0, -0.5};
        double scale = 0.25;
        double[] d = new double[Encoder.POLICY_SIZE];
        double loss = ReferenceLoss.policy(policy, 0, from, to, target, 0, 2, scale, d, 0);
        assertEquals(expectedLoss(l, new double[] {0.7f, 0.3f}), loss, 1e-12);
        double[] p = softmax(l);
        double[] expected = new double[Encoder.POLICY_SIZE];
        expected[Encoder.policyIndex(10, Encoder.FROM)] = scale * (p[0] - 0.7f);
        expected[Encoder.policyIndex(20, Encoder.TO)] = scale * (p[0] - 0.7f);
        expected[Encoder.policyIndex(11, Encoder.FROM)] = scale * (p[1] - 0.3f);
        expected[Encoder.policyIndex(21, Encoder.TO)] = scale * (p[1] - 0.3f);
        assertArrayEquals(expected, d, 1e-12);
    }

    /**
     * Moves that share a "from" cell or a "to" cell: the gradients add up. An overwrite instead of an add gives
     * the gradient of the last move only.
     */
    @Test
    void movesThatShareACellAccumulate() {
        Random random = new Random(1);
        float[] policy = policy(random, 1.0);
        int[] from = {10, 10, 11};
        int[] to = {20, 21, 20};
        float[] target = {0.5f, 0.25f, 0.25f};
        double[] d = new double[Encoder.POLICY_SIZE];
        ReferenceLoss.policy(policy, 0, from, to, target, 0, 3, 1.0, d, 0);
        double[] l = new double[3];
        for (int m = 0; m < 3; m++) {
            l[m] = (double) policy[2 * from[m]] + policy[2 * to[m] + 1];
        }
        double[] p = softmax(l);
        double[] g = {p[0] - 0.5, p[1] - 0.25, p[2] - 0.25};
        assertEquals(g[0] + g[1], d[2 * 10], 1e-12, "df of the shared from cell");
        assertEquals(g[2], d[2 * 11], 1e-12);
        assertEquals(g[0] + g[2], d[2 * 20 + 1], 1e-12, "dt of the shared to cell");
        assertEquals(g[1], d[2 * 21 + 1], 1e-12);
        // The gradient of a softmax cross-entropy with targets summing to 1 sums to 0 in each channel.
        assertEquals(0.0, d[2 * 10] + d[2 * 11], 1e-12);
        assertEquals(0.0, d[2 * 20 + 1] + d[2 * 21 + 1], 1e-12);
    }

    /**
     * Logits 1e4 apart with the first move not the largest. Subtracting the first logit instead of the maximum
     * overflows exp to infinity and the loss becomes NaN or infinite.
     */
    @Test
    void softmaxSubtractsTheMaximum() {
        float[] policy = new float[Encoder.POLICY_SIZE];
        policy[Encoder.policyIndex(10, Encoder.FROM)] = 0f;
        policy[Encoder.policyIndex(20, Encoder.TO)] = 0f;
        policy[Encoder.policyIndex(11, Encoder.FROM)] = 5000f;
        policy[Encoder.policyIndex(21, Encoder.TO)] = 5000f;
        double[] d = new double[Encoder.POLICY_SIZE];
        double loss = ReferenceLoss.policy(policy, 0, new int[] {10, 11}, new int[] {20, 21}, new float[] {0.5f, 0.5f},
                0, 2, 1.0, d, 0);
        assertEquals(5000.0, loss, 1e-9); // 0.5 * 1e4 + 0.5 * 0
        assertEquals(-0.5, d[Encoder.policyIndex(10, Encoder.FROM)], 1e-12);
        assertEquals(0.5, d[Encoder.policyIndex(11, Encoder.FROM)], 1e-12);
    }

    /** Targets with zeros: no NaN, and the zero terms contribute nothing, also where the probability is 0. */
    @Test
    void zeroTargetsGiveNoNan() {
        float[] policy = new float[Encoder.POLICY_SIZE];
        policy[Encoder.policyIndex(11, Encoder.FROM)] = 1e4f; // p of the zero-target move underflows to 0
        double[] d = new double[Encoder.POLICY_SIZE];
        double loss = ReferenceLoss.policy(policy, 0, new int[] {10, 11, 12}, new int[] {20, 21, 22},
                new float[] {1f, 0f, 0f}, 0, 3, 1.0, d, 0);
        assertEquals(1e4, loss, 1e-6);
        assertTrue(Double.isFinite(loss));
        for (double v : d) {
            assertTrue(Double.isFinite(v));
        }
        assertEquals(-1.0, d[Encoder.policyIndex(10, Encoder.FROM)], 1e-12);
        assertEquals(1.0, d[Encoder.policyIndex(11, Encoder.FROM)], 1e-12);
    }

    /** KL = Lp - H is 0 and the gradient is 0 when the target equals the softmax of the logits. */
    @Test
    void klIsZeroWhenTheTargetIsTheSoftmax() {
        Random random = new Random(2);
        float[] policy = policy(random, 2.0);
        int[] from = {3, 4, 5, 6, 7};
        int[] to = {30, 31, 32, 33, 34};
        double[] l = new double[5];
        for (int m = 0; m < 5; m++) {
            l[m] = (double) policy[2 * from[m]] + policy[2 * to[m] + 1];
        }
        double[] p = softmax(l);
        float[] target = new float[5];
        for (int m = 0; m < 5; m++) {
            target[m] = (float) p[m];
        }
        double[] d = new double[Encoder.POLICY_SIZE];
        double loss = ReferenceLoss.policy(policy, 0, from, to, target, 0, 5, 1.0, d, 0);
        double h = ReferenceLoss.entropy(target, 0, 5);
        assertEquals(0.0, loss - h, 1e-6, "KL");
        for (double v : d) {
            assertEquals(0.0, v, 1e-6);
        }
        // KL is never negative for a valid target.
        float[] other = {0.1f, 0.2f, 0.3f, 0.2f, 0.2f};
        double kl = ReferenceLoss.policy(policy, 0, from, to, other, 0, 5, 1.0, null, 0)
                - ReferenceLoss.entropy(other, 0, 5);
        assertTrue(kl > 0);
    }

    /**
     * Central differences in double, h = 1e-4. The perturbed float logit is rounded to float and the
     * denominator is the difference of the two rounded logits. Several moves share cells, so a wrong sum shows.
     */
    @Test
    void policyGradientMatchesFiniteDifferences() {
        Random random = new Random(3);
        float[] policy = policy(random, 1.5);
        int[] from = {5, 5, 6, 7, 8, 9};
        int[] to = {40, 41, 40, 42, 43, 41};
        float[] target = {0.3f, 0.1f, 0f, 0.2f, 0.25f, 0.15f};
        double scale = 0.37;
        double[] d = new double[Encoder.POLICY_SIZE];
        ReferenceLoss.policy(policy, 0, from, to, target, 0, 6, scale, d, 0);
        int checked = 0;
        for (int i = 0; i < Encoder.POLICY_SIZE; i++) {
            float lo = (float) (policy[i] - 1e-4);
            float hi = (float) (policy[i] + 1e-4);
            float[] a = policy.clone();
            a[i] = hi;
            float[] b = policy.clone();
            b[i] = lo;
            double numeric = scale * (ReferenceLoss.policy(a, 0, from, to, target, 0, 6, 1, null, 0)
                    - ReferenceLoss.policy(b, 0, from, to, target, 0, 6, 1, null, 0)) / ((double) hi - lo);
            assertEquals(numeric, d[i], 1e-6 + 1e-4 * Math.abs(d[i]), "policy element " + i);
            if (numeric != 0) {
                checked++;
            }
        }
        assertEquals(9, checked, "five from and four to cells carry a gradient, got " + checked + " non-zero gradients");
    }

    /** The move logit of the loss is the one of {@link Encoder#moveLogit}, with the canonical cells of the mover. */
    @Test
    void lossAgreesWithEncoderMoveLogit() {
        for (int players : new int[] {2, 3}) {
            for (Position position : TestBatches.pick(Playouts.positions(players, 3, 60, 17), 12)) {
                int[] moves = position.legalMoves();
                Orientation orientation = Orientation.of(position);
                float[] policy = policy(new Random(moves.length), 1.0);
                int[] from = new int[moves.length];
                int[] to = new int[moves.length];
                double[] logits = new double[moves.length];
                for (int m = 0; m < moves.length; m++) {
                    from[m] = orientation.toCanonical(Move.from(moves[m]));
                    to[m] = orientation.toCanonical(Move.to(moves[m]));
                    logits[m] = Encoder.moveLogit(orientation, policy, 0, moves[m]);
                }
                for (int hot : new int[] {0, moves.length - 1}) {
                    float[] target = new float[moves.length];
                    target[hot] = 1f;
                    double[] t = new double[moves.length];
                    t[hot] = 1;
                    double loss = ReferenceLoss.policy(policy, 0, from, to, target, 0, moves.length, 1.0, null, 0);
                    assertEquals(expectedLoss(logits, t), loss, 1e-9, players + " players, mover "
                            + position.sideToMove());
                }
            }
        }
    }

    // ------------------------------------------------------------------ value loss

    @Test
    void valueLossAndGradientByHand() {
        float[] v = {0.5f, -0.25f};
        float[] z = {1f, -1f};
        double[] dy = new double[2];
        double loss = ReferenceLoss.value(v, 0, z, 0, 2, 1.0, dy, 0);
        assertEquals(0.40625, loss, 1e-12);
        assertEquals(-0.5 * 0.75, dy[0], 1e-12); // (2/2) (v - z) (1 - v^2)
        assertEquals(0.75 * (1 - 0.0625), dy[1], 1e-12);
        // Three players: the factor is 2/3, and the scale multiplies.
        double[] dy3 = new double[3];
        double loss3 = ReferenceLoss.value(new float[] {0.5f, 0f, -0.5f}, 0, new float[] {1f, -0.5f, -0.5f}, 0, 3,
                0.2, dy3, 0);
        assertEquals((0.25 + 0.25 + 0) / 3, loss3, 1e-12);
        assertEquals(0.2 * (2.0 / 3) * -0.5 * 0.75, dy3[0], 1e-12);
        assertEquals(0.2 * (2.0 / 3) * 0.5 * 1.0, dy3[1], 1e-12);
        assertEquals(0.0, dy3[2], 1e-12);
    }

    /**
     * Central differences of the loss with respect to the pre-activation y, with v = tanh(y) in double. The
     * gradient must contain the derivative of tanh as 1 - v^2 of the output, not 1 - y^2 (5 % apart at
     * y = 0.5) and not 1 - v.
     */
    @Test
    void valueGradientMatchesFiniteDifferences() {
        for (int players : new int[] {2, 3}) {
            double[] y = new double[players];
            float[] z = new float[players];
            Random random = new Random(players);
            for (int i = 0; i < players; i++) {
                y[i] = 0.5 + 0.4 * i + 0.1 * random.nextDouble();
                z[i] = (float) (random.nextDouble() * 2 - 1);
            }
            float[] v = new float[players];
            for (int i = 0; i < players; i++) {
                v[i] = (float) Math.tanh(y[i]);
            }
            double scale = 0.6;
            double[] dy = new double[players];
            ReferenceLoss.value(v, 0, z, 0, players, scale, dy, 0);
            for (int i = 0; i < players; i++) {
                double[] up = y.clone();
                double[] down = y.clone();
                up[i] += 1e-5;
                down[i] -= 1e-5;
                double numeric = scale * (doubleLoss(up, z) - doubleLoss(down, z)) / 2e-5;
                assertEquals(numeric, dy[i], 1e-6 + 1e-4 * Math.abs(numeric), players + " players, output " + i);
            }
        }
    }

    private static double doubleLoss(double[] y, float[] z) {
        double sum = 0;
        for (int i = 0; i < y.length; i++) {
            double d = Math.tanh(y[i]) - z[i];
            sum += d * d;
        }
        return sum / y.length;
    }

    /**
     * The value targets are in relative order, mover first: for mover 1 and mover 2 of three players the
     * absolute vector is rotated. An absolute order gives the wrong player the +1.
     */
    @Test
    void valueTargetsAreRelativeToTheMover() {
        double[] absolute = {1.0, -0.5, -0.5}; // player 0 won
        assertArrayEquals(new float[] {1f, -0.5f, -0.5f}, TrainingBatch.relativeValues(absolute, 0));
        assertArrayEquals(new float[] {-0.5f, -0.5f, 1f}, TrainingBatch.relativeValues(absolute, 1));
        assertArrayEquals(new float[] {-0.5f, 1f, -0.5f}, TrainingBatch.relativeValues(absolute, 2));
        double[] two = {1.0, -1.0};
        assertArrayEquals(new float[] {-1f, 1f}, TrainingBatch.relativeValues(two, 1));

        // A network that predicts "the mover wins" has no loss when the mover won, for every mover.
        List<Position> positions = Playouts.positions(3, 2, 30, 5);
        for (Position position : positions) {
            int mover = position.sideToMove();
            double[] winnerIsMover = new double[3];
            Arrays.fill(winnerIsMover, -0.5);
            winnerIsMover[mover] = 1;
            float[] z = TrainingBatch.relativeValues(winnerIsMover, mover);
            assertEquals(0.0, ReferenceLoss.value(new float[] {1f, -0.5f, -0.5f}, 0, z, 0, 3, 1.0, null, 0), 1e-12,
                    "mover " + mover);
        }
    }
}
