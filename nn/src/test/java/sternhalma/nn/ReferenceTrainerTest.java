package sternhalma.nn;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import sternhalma.engine.Position;

/**
 * Tests of the whole CPU reference trainer (docs/training.md, "Tests of Part A", "Whole network").
 *
 * <p>The anchor is the finite-difference check of every parameter of TINY against a double-precision forward
 * pass ({@link DoubleNetwork}) that does not use {@link ReferenceOps}. The other tests pin the bookkeeping that
 * the finite differences cannot see: that a batch is the mean of its samples, that microbatches and threads do
 * not change the sum, that no state leaks from one sample to the next, and that broken numbers stop the run.
 */
class ReferenceTrainerTest {

    private static final double CV = 1.0;

    private static float tinyBound(NetworkConfig config) {
        return (float) Math.sqrt(6.0 / (7 * config.channels()));
    }

    private static TrainingBatch batch(int players, int samples, long seed) {
        return batch(players, PlaneSet.PLAIN, samples, seed);
    }

    private static TrainingBatch batch(int players, PlaneSet planes, int samples, long seed) {
        List<Position> positions = Playouts.positions(players, 4, 40, seed);
        return TestBatches.fromPositions(players, planes, TestBatches.pick(positions, samples), seed);
    }

    private static ReferenceTrainer.Gradients gradientsOf(Parameters parameters, TrainingBatch batch, double cv,
            int[] sizes, int threads) {
        ReferenceTrainer trainer = new ReferenceTrainer(parameters, cv);
        trainer.accumulate(batch, sizes, threads);
        return trainer.gradients();
    }

    // ------------------------------------------------------------------ forward

    /**
     * The trainer's forward pass equals {@code Network.forward} bit for bit (same {@code ReferenceOps} calls in
     * the same order). Catches a changed order of operations, a residual taken from the wrong slot, a stale
     * scratch buffer and a head that reads the wrong block output. Run on TINY and SMALL, 2 and 3 players, mixed
     * movers, init weights and weights with non-zero biases.
     */
    @Test
    void forwardEqualsNetworkBitForBit() {
        NetworkConfig[] configs = {NetworkConfig.tiny(2), NetworkConfig.tiny(3), NetworkConfig.small(2),
            new NetworkConfig(4, 0, 2, 8), new NetworkConfig(4, 3, 3, 8)};
        for (NetworkConfig config : configs) {
            for (Parameters parameters : List.of(Parameters.init(config, 4),
                    Parameters.uniform(config, 5, tinyBound(config)))) {
                TrainingBatch batch = batch(config.players(), 6, 77);
                Network network = new Network(parameters);
                ReferenceTrainer.Worker worker = new ReferenceTrainer.Worker(parameters, CV);
                int bits = 0;
                for (int s = 0; s < batch.size(); s++) {
                    float[] policy = new float[Encoder.POLICY_SIZE];
                    float[] values = new float[config.players()];
                    network.forward(Arrays.copyOfRange(batch.input(), s * Encoder.INPUT_SIZE,
                            (s + 1) * Encoder.INPUT_SIZE), 1, policy, values);
                    worker.forward(batch.input(), s * Encoder.INPUT_SIZE); // the worker is reused: stale data shows
                    assertArrayEquals(policy, worker.policy, config + ", policy, sample " + s);
                    assertArrayEquals(values, worker.values, config + ", values, sample " + s);
                    bits += policy.length + values.length;
                    for (float v : policy) {
                        assertTrue(Float.isFinite(v));
                    }
                }
                assertTrue(bits > 0);
            }
        }
    }

    // ------------------------------------------------------------------ gradient check

    /**
     * For every parameter of TINY, the analytic gradient equals the central difference of the double loss,
     * h = 1e-6 max(1, |theta|), bound |a - n| &lt;= 1e-3 |n| + 1e-5 max|n|. A coordinate is skipped if the
     * perturbation flips the sign of any ReLU pre-activation of any sample (kink guard); more than 5 % skipped
     * fails the test. Weights from {@link Parameters#uniform}, biases non-zero, 4 samples with legal moves,
     * non-trivial weights, both player counts.
     *
     * <p>This catches any wrong backward rule that the single-operation tests could miss in context: a wrong
     * order of the backward pass, a residual path that is dropped or doubled, a 1/121 that is missing in the
     * mean, a ReLU mask taken from the wrong tensor, a value gradient that uses y instead of v.
     */
    @Test
    void gradientMatchesFiniteDifferencesTwoPlayers(TestReporter reporter) {
        gradientCheck(NetworkConfig.tiny(2), reporter);
    }

    @Test
    void gradientMatchesFiniteDifferencesThreePlayers(TestReporter reporter) {
        gradientCheck(NetworkConfig.tiny(3), reporter);
    }

    /**
     * Blocks after the first: with B = 3 every block has its own weights, its own stored activations and its own
     * swap of the gradient buffers. TINY has one block and cannot show a bug that only acts from block 1 on
     * (a swap only in the first iteration, block 0's weights used for every block).
     */
    @Test
    void gradientMatchesFiniteDifferencesThreeBlocks(TestReporter reporter) {
        gradientCheck(new NetworkConfig(4, 3, 2, 8), reporter);
        gradientCheck(new NetworkConfig(4, 3, 3, 8), reporter);
    }

    /**
     * The classical planes and the residual priors (E11): every parameter of TINY, the two scalars included, for two and
     * three players, with and without the priors, and a deeper net. The input planes include the reachability, the
     * passed flag and the evaluation, so a gradient that mixes up the plane of the distance term shows.
     */
    @Test
    void gradientMatchesFiniteDifferencesWithClassicalPlanesAndPriors(TestReporter reporter) {
        gradientCheck(NetworkConfig.tiny(2).withPlanes(PlaneSet.CLASSIC1), reporter);
        gradientCheck(NetworkConfig.tiny(3).withPlanes(PlaneSet.CLASSIC1), reporter);
        gradientCheck(NetworkConfig.tiny(2).withPlanes(PlaneSet.CLASSIC1_PLANES), reporter);
        gradientCheck(new NetworkConfig(4, 3, 2, 8, PlaneSet.CLASSIC1), reporter);
    }

    private static void gradientCheck(NetworkConfig config, TestReporter reporter) {
        int players = config.players();
        Parameters parameters = Parameters.uniform(config, 40 + players, 0.3f);
        TrainingBatch batch = TestBatches.plusCornerSample(batch(players, config.planes(), 4, 99 + players));
        double cv = 0.7;
        ReferenceTrainer trainer = new ReferenceTrainer(parameters, cv);
        ReferenceTrainer.Result result = trainer.accumulate(batch, 5, 1);

        DoubleNetwork net = new DoubleNetwork(parameters);
        List<boolean[]> base = new ArrayList<>();
        double loss = net.loss(batch, cv, base);
        assertEquals(loss, result.loss(), 1e-5 * Math.abs(loss), "loss of the trainer against the double loss");

        // The double forward pass agrees with Network (so that the check measures the network, not a copy).
        Network network = new Network(parameters);
        double worstForward = 0;
        for (int s = 0; s < batch.size(); s++) {
            float[] policy = new float[Encoder.POLICY_SIZE];
            float[] values = new float[players];
            network.forward(Arrays.copyOfRange(batch.input(), s * batch.inputSize(), (s + 1) * batch.inputSize()),
                    1, policy, values);
            DoubleNetwork.Output out = net.forward(batch.input(), s * batch.inputSize());
            for (int i = 0; i < policy.length; i++) {
                worstForward = Math.max(worstForward, Math.abs(policy[i] - out.policy()[i]) / (1e-6 + 1e-5 * Math.abs(policy[i])));
            }
            for (int i = 0; i < players; i++) {
                worstForward = Math.max(worstForward, Math.abs(values[i] - out.values()[i]) / (1e-6 + 1e-5 * Math.abs(values[i])));
            }
        }
        assertTrue(worstForward <= 1, "double forward pass differs from Network: " + worstForward + " of the bound");

        Map<String, Tensor> tensors = parameters.tensors();
        Map<String, double[]> numeric = new java.util.LinkedHashMap<>();
        Map<String, boolean[]> skipped = new java.util.LinkedHashMap<>();
        double maxNumeric = 0;
        int total = 0;
        int skips = 0;
        for (String name : tensors.keySet()) {
            double[] p = net.param(name);
            double[] n = new double[p.length];
            boolean[] skip = new boolean[p.length];
            for (int i = 0; i < p.length; i++) {
                double theta = p[i];
                double h = 1e-6 * Math.max(1, Math.abs(theta));
                p[i] = theta + h;
                List<boolean[]> up = new ArrayList<>();
                double lossUp = net.loss(batch, cv, up);
                p[i] = theta - h;
                List<boolean[]> down = new ArrayList<>();
                double lossDown = net.loss(batch, cv, down);
                p[i] = theta;
                total++;
                boolean kink = false;
                for (int s = 0; s < base.size(); s++) {
                    kink |= !Arrays.equals(base.get(s), up.get(s)) || !Arrays.equals(base.get(s), down.get(s));
                }
                if (kink) {
                    skip[i] = true;
                    skips++;
                } else {
                    n[i] = (lossUp - lossDown) / (2 * h);
                    maxNumeric = Math.max(maxNumeric, Math.abs(n[i]));
                }
            }
            numeric.put(name, n);
            skipped.put(name, skip);
        }
        assertEquals(config.parameterCount(), total);
        assertTrue(skips <= 0.05 * total, skips + " of " + total + " coordinates skipped by the kink guard");
        assertTrue(maxNumeric > 1e-3, "the gradient is suspiciously small: " + maxNumeric);

        double worst = 0;
        int nonZero = 0;
        for (String name : tensors.keySet()) {
            double[] analytic = trainer.gradients().get(name);
            double[] n = numeric.get(name);
            for (int i = 0; i < n.length; i++) {
                if (skipped.get(name)[i]) {
                    continue;
                }
                double bound = 1e-3 * Math.abs(n[i]) + 1e-5 * maxNumeric;
                double error = Math.abs(analytic[i] - n[i]);
                worst = Math.max(worst, error / bound);
                assertTrue(error <= bound, name + "[" + i + "]: analytic " + analytic[i] + ", numeric " + n[i]
                        + ", bound " + bound);
                if (Math.abs(n[i]) > 1e-3 * maxNumeric) {
                    nonZero++;
                }
            }
        }
        assertTrue(nonZero > total / 4, "only " + nonZero + " of " + total + " gradients are large enough to test");
        reporter.publishEntry("finite differences, C=" + config.channels() + " B=" + config.blocks() + ", " + players + " players", String.format(Locale.ROOT,
                "%d parameters, %d skipped by the kink guard, largest error / bound %.3g, double forward vs "
                        + "Network %.3g of its bound", total, skips, worst, worstForward));
    }

    // ------------------------------------------------------------------ batches, microbatches, threads

    private static void assertGradientsClose(ReferenceTrainer.Gradients expected, ReferenceTrainer.Gradients actual,
            double relative, String what) {
        double max = 0;
        for (double[] g : expected.tensors().values()) {
            for (double v : g) {
                max = Math.max(max, Math.abs(v));
            }
        }
        assertTrue(max > 1e-6, "all gradients are zero in " + what);
        for (Map.Entry<String, double[]> e : expected.tensors().entrySet()) {
            double[] a = actual.get(e.getKey());
            for (int i = 0; i < a.length; i++) {
                double bound = relative * Math.abs(e.getValue()[i]) + 1e-12 * max;
                assertTrue(Math.abs(a[i] - e.getValue()[i]) <= bound, what + ": " + e.getKey() + "[" + i + "] expected "
                        + e.getValue()[i] + ", got " + a[i]);
            }
        }
    }

    /**
     * The gradient of a batch of 8 equals the mean of the 8 single-sample gradients (each computed as a batch
     * of 1), for a batch that mixes movers (and with it the canonical maps). Catches a missing or doubled 1/N,
     * state that leaks from one sample to the next, and a per-sample orientation that is applied wrongly.
     */
    @Test
    void batchIsTheMeanOfItsSamples() {
        for (int players : new int[] {2, 3}) {
            NetworkConfig config = NetworkConfig.tiny(players);
            Parameters parameters = Parameters.uniform(config, 8, tinyBound(config));
            TrainingBatch batch = batch(players, 8, 123);
            assertTrue(batch.size() == 8);
            TreeSet<Integer> movers = new TreeSet<>();
            for (Position p : TestBatches.pick(Playouts.positions(players, 4, 40, 123), 8)) {
                movers.add(p.sideToMove());
            }
            assertEquals(players, movers.size(), "the batch must mix all movers: " + movers);

            ReferenceTrainer.Gradients whole = gradientsOf(parameters, batch, CV, new int[] {8}, 1);
            ReferenceTrainer.Gradients mean = new ReferenceTrainer(parameters, CV).gradients();
            for (int s = 0; s < batch.size(); s++) {
                ReferenceTrainer.Gradients single = gradientsOf(parameters, TestBatches.single(batch, s), CV,
                        new int[] {1}, 1);
                for (Map.Entry<String, double[]> e : single.tensors().entrySet()) {
                    double[] m = mean.get(e.getKey());
                    for (int i = 0; i < m.length; i++) {
                        m[i] += e.getValue()[i] / batch.size();
                    }
                }
            }
            assertGradientsClose(mean, whole, 1e-6, players + " players, batch against mean of singles");
        }
    }

    /** Microbatches of 3 and 5 equal one batch of 8; so do microbatches of 1, uneven ones and threads. */
    @Test
    void microbatchesEqualOneBatch() {
        NetworkConfig config = new NetworkConfig(8, 3, 2, 32);
        Parameters parameters = Parameters.uniform(config, 9, tinyBound(config));
        TrainingBatch batch = batch(2, 8, 321);
        ReferenceTrainer.Gradients one = gradientsOf(parameters, batch, CV, new int[] {8}, 1);
        assertGradientsClose(one, gradientsOf(parameters, batch, CV, new int[] {3, 5}, 1), 1e-6, "3 + 5");
        assertGradientsClose(one, gradientsOf(parameters, batch, CV, new int[] {1, 1, 1, 1, 1, 1, 1, 1}, 1), 1e-6,
                "8 x 1");
        assertGradientsClose(one, gradientsOf(parameters, batch, CV, new int[] {5, 3}, 4), 1e-6, "5 + 3, 4 threads");
        ReferenceTrainer trainer = new ReferenceTrainer(parameters, CV);
        trainer.accumulate(batch, 3, 2);
        assertGradientsClose(one, trainer.gradients(), 1e-6, "equal microbatches of 3, the last one smaller");
    }

    /**
     * The result does not depend on the thread timing: the same microbatches give bit-identical gradients with 1,
     * 2 and 8 threads, run after run. Catches a sum in completion order.
     */
    @Test
    void threadsDoNotChangeTheBits() {
        NetworkConfig config = new NetworkConfig(8, 3, 3, 32);
        Parameters parameters = Parameters.uniform(config, 10, tinyBound(config));
        TrainingBatch batch = batch(3, 12, 555);
        int[] sizes = {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1}; // more microbatches than the window of 2 * threads
        ReferenceTrainer.Gradients reference = gradientsOf(parameters, batch, CV, sizes, 1);
        for (int threads : new int[] {2, 3, 8, 8}) {
            ReferenceTrainer.Gradients g = gradientsOf(parameters, batch, CV, sizes, threads);
            for (Map.Entry<String, double[]> e : reference.tensors().entrySet()) {
                assertArrayEquals(e.getValue(), g.get(e.getKey()), e.getKey() + ", " + threads + " threads");
            }
        }
    }

    /** Gradients accumulate over calls until {@code zeroGradients()}: two calls give exactly twice one call. */
    @Test
    void gradientsAccumulateOverCalls() {
        NetworkConfig config = NetworkConfig.tiny(2);
        Parameters parameters = Parameters.uniform(config, 11, tinyBound(config));
        TrainingBatch batch = batch(2, 4, 8);
        ReferenceTrainer once = new ReferenceTrainer(parameters, CV);
        once.accumulate(batch, 2, 1);
        ReferenceTrainer twice = new ReferenceTrainer(parameters, CV);
        twice.accumulate(batch, 2, 1);
        twice.accumulate(batch, 2, 1);
        for (Map.Entry<String, double[]> e : once.gradients().tensors().entrySet()) {
            double[] a = e.getValue();
            double[] b = twice.gradients().get(e.getKey());
            for (int i = 0; i < a.length; i++) {
                assertEquals(2 * a[i], b[i], 1e-12 * Math.abs(a[i]) + 1e-300, e.getKey() + "[" + i + "]");
            }
        }
        twice.zeroGradients();
        for (double[] g : twice.gradients().tensors().values()) {
            assertTrue(Arrays.stream(g).allMatch(v -> v == 0));
        }
    }

    /**
     * No state leaks between samples: a worker whose scratch buffers are all NaN, and a worker that has just
     * processed a different sample, give the gradient of a fresh worker. The padding rows of the stored
     * activations and of the gradient buffers are 0 afterwards.
     */
    @Test
    void scratchBuffersDoNotLeakAndPaddingRowsStayZero() {
        NetworkConfig config = NetworkConfig.tiny(2);
        Parameters parameters = Parameters.uniform(config, 12, tinyBound(config));
        TrainingBatch batch = batch(2, 4, 4242);
        ReferenceTrainer.Gradients expected = gradientsOf(parameters, TestBatches.single(batch, 2), CV, new int[] {1}, 1);

        ReferenceTrainer.Worker worker = new ReferenceTrainer.Worker(parameters, CV);
        worker.poison();
        ReferenceTrainer.Gradients g = new ReferenceTrainer(parameters, CV).gradients();
        double[] sums = new double[3];
        worker.sample(TestBatches.single(batch, 2), 0, g, sums);
        assertGradientsClose(expected, g, 1e-12, "poisoned scratch");
        assertPaddingRowsZero(worker, config);

        // Another sample first, then the same one again.
        ReferenceTrainer.Gradients g2 = new ReferenceTrainer(parameters, CV).gradients();
        worker.sample(TestBatches.single(batch, 0), 0, new ReferenceTrainer(parameters, CV).gradients(), new double[3]);
        worker.sample(TestBatches.single(batch, 2), 0, g2, new double[3]);
        assertGradientsClose(expected, g2, 1e-12, "reused worker");
        assertPaddingRowsZero(worker, config);
    }

    private static void assertPaddingRowsZero(ReferenceTrainer.Worker worker, NetworkConfig config) {
        int c = config.channels();
        int slots = 1 + 2 * config.blocks();
        for (int slot = 0; slot < slots; slot++) {
            for (int ch = 0; ch < c; ch++) {
                assertEquals(0f, worker.acts[(slot * HexGrid.ROWS + HexGrid.PADDING) * c + ch],
                        "stored activation, slot " + slot);
            }
        }
        for (double[] d : List.of(worker.dxCur, worker.dxNext, worker.dz2, worker.dt)) {
            for (int ch = 0; ch < c; ch++) {
                assertEquals(0.0, d[HexGrid.PADDING * c + ch], "gradient buffer padding row");
            }
        }
    }

    // ------------------------------------------------------------------ weights and the loss value

    /** c_v scales the value part; a sample with both weights 0 contributes no gradient. */
    @Test
    void weightsAndValueCoefficientScaleTheGradient() {
        NetworkConfig config = NetworkConfig.tiny(2);
        Parameters parameters = Parameters.uniform(config, 13, tinyBound(config));
        TrainingBatch full = batch(2, 4, 31);
        // Policy weights 0: only the value loss remains, and it scales with c_v.
        TrainingBatch valueOnly = new TrainingBatch(full.size(), 2, full.input(), full.moveOffsets(),
                full.moveFrom(), full.moveTo(), full.moveTarget(), full.valueTarget(), new float[full.size()],
                full.valueWeight(), full.targetEntropy());
        ReferenceTrainer.Gradients one = gradientsOf(parameters, valueOnly, 1.0, new int[] {4}, 1);
        ReferenceTrainer.Gradients two = gradientsOf(parameters, valueOnly, 2.0, new int[] {4}, 1);
        for (Map.Entry<String, double[]> e : one.tensors().entrySet()) {
            for (int i = 0; i < e.getValue().length; i++) {
                assertEquals(2 * e.getValue()[i], two.get(e.getKey())[i], 1e-12 * Math.abs(e.getValue()[i]) + 1e-15);
            }
        }
        assertTrue(Arrays.stream(one.get("head_value.dense2.bias")).anyMatch(v -> v != 0));
        assertTrue(Arrays.stream(one.get("head_policy.bias")).allMatch(v -> v == 0), "no policy loss, no policy bias gradient");

        TrainingBatch nothing = new TrainingBatch(full.size(), 2, full.input(), full.moveOffsets(), full.moveFrom(),
                full.moveTo(), full.moveTarget(), full.valueTarget(), new float[full.size()], new float[full.size()],
                full.targetEntropy());
        for (double[] g : gradientsOf(parameters, nothing, 1.0, new int[] {4}, 1).tensors().values()) {
            assertTrue(Arrays.stream(g).allMatch(v -> v == 0));
        }
    }

    /**
     * The reported terms: policy plus value equals the loss; KL is the policy loss minus the target entropy,
     * weighted, and is not negative.
     */
    @Test
    void resultReportsTheLossTerms() {
        NetworkConfig config = NetworkConfig.tiny(2);
        Parameters parameters = Parameters.uniform(config, 14, tinyBound(config));
        TrainingBatch batch = batch(2, 6, 17);
        ReferenceTrainer.Result r = new ReferenceTrainer(parameters, 0.5).accumulate(batch, 4, 1);
        assertEquals(r.policyLoss() + r.valueLoss(), r.loss(), 1e-15);
        double entropy = 0;
        for (int s = 0; s < batch.size(); s++) {
            entropy += batch.policyWeight()[s] * batch.targetEntropy()[s] / batch.size();
        }
        assertEquals(r.policyLoss() - entropy, r.kl(), 1e-9);
        assertTrue(r.kl() > 0);
        double expected = new DoubleNetwork(parameters).loss(batch, 0.5, new ArrayList<>());
        assertEquals(expected, r.loss(), 1e-5 * Math.abs(expected));
    }

    // ------------------------------------------------------------------ bad input and broken numbers

    @Test
    void nanAndInfinityStopTheRun() {
        NetworkConfig config = NetworkConfig.tiny(2);
        TrainingBatch batch = batch(2, 3, 5);
        Parameters nan = Parameters.uniform(config, 15, tinyBound(config));
        nan.get("block0.conv1.bias").data()[3] = Float.NaN;
        assertThrows(IllegalStateException.class, () -> new ReferenceTrainer(nan, CV).accumulate(batch, 3, 1));
        assertThrows(IllegalStateException.class, () -> new ReferenceTrainer(nan, CV).accumulate(batch, 1, 2));

        // Huge weights overflow float activations to infinity.
        Parameters huge = Parameters.uniform(config, 16, 1e30f);
        assertThrows(IllegalStateException.class, () -> new ReferenceTrainer(huge, CV).accumulate(batch, 3, 1));
    }

    @Test
    void rejectsMismatchedArguments() {
        NetworkConfig config = NetworkConfig.tiny(2);
        Parameters parameters = Parameters.init(config, 1);
        ReferenceTrainer trainer = new ReferenceTrainer(parameters, CV);
        TrainingBatch batch = batch(2, 4, 6);
        assertThrows(IllegalArgumentException.class, () -> trainer.accumulate(batch, new int[] {2, 1}, 1));
        assertThrows(IllegalArgumentException.class, () -> trainer.accumulate(batch, new int[] {4, 0}, 1));
        assertThrows(IllegalArgumentException.class, () -> trainer.accumulate(batch, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> trainer.accumulate(batch, 2, 0));
        assertThrows(IllegalArgumentException.class, () -> trainer.accumulate(batch(3, 4, 6), 2, 1));
        assertThrows(IllegalArgumentException.class, () -> new ReferenceTrainer(parameters, -1));
    }
}
