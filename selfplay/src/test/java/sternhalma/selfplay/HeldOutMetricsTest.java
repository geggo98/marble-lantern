package sternhalma.selfplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sternhalma.nn.Encoder;
import sternhalma.nn.Network;
import sternhalma.nn.NetworkConfig;
import sternhalma.nn.Parameters;
import sternhalma.nn.ReferenceLoss;
import sternhalma.nn.TrainingBatch;
import sternhalma.selfplay.HeldOutMetrics.Metrics;

/** The proxies against small cases computed by hand (and outside the code, with a script). */
class HeldOutMetricsTest {

    /**
     * Four samples, two moves each except the last: (0 to 1) and (2 to 3). The move logit is
     * {@code policy[2 from] + policy[2 to + 1]}.
     *
     * <pre>
     *   s0: logits (1, 0)     target (.75, .25)  wp 1   KL 0.00092654  top-1 agrees       value (.5,-.5) vs (1,-1)  wv 1
     *   s1: opening ply       wp 0                                                         value (.2,.4) vs (0,0)    wv 1
     *   s2: logits (2, .5)    target (.1, .9)    wp 2   KL 1.2263303   top-1 disagrees    value (-.6,.6) vs (-1,1)  wv .5
     *   s3: no moves          wp 0                                                         value (.3,.3) vs (1,-1)   wv 2
     * </pre>
     * Weighted KL (1 * 0.00092654 + 2 * 1.2263303) / 3 = 0.81786238; top-1 (1 * 1 + 2 * 0) / 3; the value MSEs are
     * 0.25, 0.1, 0.16 and 1.09, so the loss is (0.25 + 0.1 + 0.5 * 0.16 + 2 * 1.09) / 4.5 = 0.58.
     */
    private static TrainingBatch batch() {
        int n = 4;
        float[] target = {.75f, .25f, .5f, .5f, .1f, .9f};
        int[] offsets = {0, 2, 4, 6, 6};
        int[] from = {0, 2, 0, 2, 0, 2};
        int[] to = {1, 3, 1, 3, 1, 3};
        double[] entropy = new double[n];
        for (int s = 0; s < n; s++) {
            entropy[s] = ReferenceLoss.entropy(target, offsets[s], offsets[s + 1]);
        }
        return new TrainingBatch(n, 2, new float[n * Encoder.INPUT_SIZE], offsets, from, to, target,
                new float[] {1, -1, 0, 0, -1, 1, 1, -1}, new float[] {1, 0, 2, 0}, new float[] {1, 1, .5f, 2}, entropy);
    }

    private static float[] policy() {
        float[] p = new float[4 * Encoder.POLICY_SIZE];
        p[0 * Encoder.POLICY_SIZE + 0] = 1.0f; // s0 from cell 0
        p[2 * Encoder.POLICY_SIZE + 0] = 2.0f; // s2 from cell 0
        p[2 * Encoder.POLICY_SIZE + 4] = 0.5f; // s2 from cell 2
        p[1 * Encoder.POLICY_SIZE + 4] = 3.0f; // s1 prefers its second move; it has no policy weight
        return p;
    }

    private static final float[] VALUES = {.5f, -.5f, .2f, .4f, -.6f, .6f, .3f, .3f};

    @Test
    void theProxiesEqualTheHandComputedValues() {
        HeldOutMetrics.Accumulator acc = new HeldOutMetrics.Accumulator();
        acc.add(batch(), policy(), VALUES, new int[] {1, -1, 0, 1});
        Metrics m = acc.metrics();
        assertEquals(4, m.samples());
        assertEquals(2, m.policySamples(), "the opening ply and the sample without moves do not count");
        assertEquals(0.8178623840273409, m.klPolicy(), 1e-6);
        assertEquals(1.0 / 3, m.top1(), 1e-12);
        assertEquals(0.58, m.valueLoss(), 1e-6);
        assertEquals(3, m.winnerSamples(), "a result of 0 is not counted");
        assertEquals(2.0 / 3, m.winnerAccuracy(), 1e-12);
    }

    @Test
    void theWinnerSignIsTheSignOfMoverMinusOpponentAndATieIsWrong() {
        // s0: v = (.5, -.5), result +1 -> right. s1: v = (.2, .4), result -1 -> right. s3: tie -> wrong.
        HeldOutMetrics.Accumulator right = new HeldOutMetrics.Accumulator();
        right.add(batch(), policy(), VALUES, new int[] {1, -1, 0, 0});
        assertEquals(1.0, right.metrics().winnerAccuracy(), 1e-12);
        HeldOutMetrics.Accumulator flipped = new HeldOutMetrics.Accumulator();
        flipped.add(batch(), policy(), VALUES, new int[] {-1, 1, 0, 0});
        assertEquals(0.0, flipped.metrics().winnerAccuracy(), 1e-12);
        HeldOutMetrics.Accumulator tie = new HeldOutMetrics.Accumulator();
        tie.add(batch(), policy(), VALUES, new int[] {0, 0, 0, 1});
        assertEquals(0.0, tie.metrics().winnerAccuracy(), 1e-12);
        HeldOutMetrics.Accumulator none = new HeldOutMetrics.Accumulator();
        none.add(batch(), policy(), VALUES, new int[] {0, 0, 0, 0});
        assertTrue(Double.isNaN(none.metrics().winnerAccuracy()), "no decisive sample, no accuracy");
    }

    @Test
    void aPerfectNetworkHasZeroKlAndFullAgreement() {
        // logits equal to log of the targets of s0 -> KL 0
        float[] p = new float[4 * Encoder.POLICY_SIZE];
        p[0] = (float) Math.log(.75);
        p[4] = (float) Math.log(.25);
        HeldOutMetrics.Accumulator acc = new HeldOutMetrics.Accumulator();
        TrainingBatch b = batch();
        // restrict to s0 only by weighting: use the policy of s2 as the exact one too
        p[2 * Encoder.POLICY_SIZE] = (float) Math.log(.1);
        p[2 * Encoder.POLICY_SIZE + 4] = (float) Math.log(.9);
        acc.add(b, p, VALUES, new int[4]);
        Metrics m = acc.metrics();
        assertEquals(0.0, m.klPolicy(), 1e-6);
        assertEquals(1.0, m.top1(), 1e-12);
    }

    @Test
    void evaluateUsesAFixedEvenlySpacedSubsetAndIsRepeatable(@TempDir Path dir) throws IOException {
        Path file = SyntheticShards.write(dir, "samples-s1-0000.safetensors", 1, 0, 30, 10, 77);
        ReplayBuffer buffer = new ReplayBuffer(2, Integer.MAX_VALUE, false);
        buffer.addGeneration(0, List.of(file), (m, id) -> true);
        Network network = new Network(Parameters.init(NetworkConfig.tiny(2), 3));
        Metrics a = HeldOutMetrics.evaluate(network, buffer, 70, 1);
        Metrics b = HeldOutMetrics.evaluate(network, buffer, 70, 1);
        assertEquals(a, b);
        assertEquals(a, HeldOutMetrics.evaluate(network, buffer, 70, 3), "the thread count changes nothing");
        assertEquals(70, a.samples());
        assertTrue(a.klPolicy() > 0 && Double.isFinite(a.klPolicy()));
        assertTrue(a.top1() >= 0 && a.top1() <= 1);
        assertTrue(a.valueLoss() > 0);
        assertTrue(a.winnerSamples() > 0 && a.winnerSamples() <= 70);
        assertEquals(300, HeldOutMetrics.evaluate(network, buffer, 5000, 1).samples(), "capped at the buffer size");
        assertThrows(IllegalArgumentException.class, () -> HeldOutMetrics.evaluate(network, buffer, 0, 1));
    }

    @Test
    void evaluateTakesTheEvenlySpacedSamplesAndNoOthers(@TempDir Path dir) throws IOException {
        Path file = SyntheticShards.write(dir, "samples-s1-0000.safetensors", 1, 0, 30, 10, 77);
        ReplayBuffer buffer = new ReplayBuffer(2, Integer.MAX_VALUE, false);
        buffer.addGeneration(0, List.of(file), (m, id) -> true);
        Network network = new Network(Parameters.init(NetworkConfig.tiny(2), 3));
        int size = buffer.size();
        for (int n : new int[] {70, 33, 5}) {
            // independent of the code under test: sample i of n is number floor(i * size / n)
            int[] indices = new int[n];
            int[] results = new int[n];
            for (int i = 0; i < n; i++) {
                indices[i] = (int) ((long) i * size / n);
                results[i] = buffer.result(indices[i]);
            }
            assertTrue(indices[n - 1] >= size - size / n - 1, "the subset reaches the end of the buffer");
            TrainingBatch batch = buffer.batchOf(indices, new boolean[n]);
            float[] policy = new float[n * Encoder.POLICY_SIZE];
            float[] values = new float[n * 2];
            network.forward(batch.input(), n, policy, values);
            HeldOutMetrics.Accumulator acc = new HeldOutMetrics.Accumulator();
            acc.add(batch, policy, values, results);
            Metrics expected = acc.metrics();
            Metrics actual = HeldOutMetrics.evaluate(network, buffer, n, 1);
            assertEquals(n, actual.samples());
            assertEquals(expected.policySamples(), actual.policySamples(), "n = " + n);
            assertEquals(expected.klPolicy(), actual.klPolicy(), 1e-6, "n = " + n);
            assertEquals(expected.top1(), actual.top1(), 1e-9, "n = " + n);
            assertEquals(expected.valueLoss(), actual.valueLoss(), 1e-6, "n = " + n);
            assertEquals(expected.winnerSamples(), actual.winnerSamples(), "n = " + n);
            assertEquals(expected.winnerAccuracy(), actual.winnerAccuracy(), 1e-9, "n = " + n);
        }
        // the first samples of the buffer are not the evenly spaced ones: the two choices differ in the metrics
        int[] first = new int[70];
        for (int i = 0; i < first.length; i++) {
            first[i] = i;
        }
        TrainingBatch head = buffer.batchOf(first, new boolean[70]);
        float[] policy = new float[70 * Encoder.POLICY_SIZE];
        float[] values = new float[70 * 2];
        network.forward(head.input(), 70, policy, values);
        HeldOutMetrics.Accumulator acc = new HeldOutMetrics.Accumulator();
        acc.add(head, policy, values, new int[70]);
        assertTrue(Math.abs(acc.metrics().valueLoss() - HeldOutMetrics.evaluate(network, buffer, 70, 1).valueLoss()) > 1e-9,
                "the subset is not the head of the buffer");
    }

    @Test
    void aTieOfTheTargetsTakesTheFirstMoveAsTheTeacherChoice() {
        // one sample, two moves (0 to 1) and (2 to 3) with equal targets; the logit is policy[2 from] + policy[2 to + 1]
        for (int favoured = 0; favoured < 2; favoured++) {
            TrainingBatch b = new TrainingBatch(1, 2, new float[Encoder.INPUT_SIZE], new int[] {0, 2}, new int[] {0, 2},
                    new int[] {1, 3}, new float[] {.5f, .5f}, new float[] {0, 0}, new float[] {1}, new float[] {1},
                    new double[] {Math.log(2)});
            float[] p = new float[Encoder.POLICY_SIZE];
            p[Encoder.policyIndex(favoured == 0 ? 0 : 2, Encoder.FROM)] = 1f;
            HeldOutMetrics.Accumulator acc = new HeldOutMetrics.Accumulator();
            acc.add(b, p, new float[] {0, 0}, new int[1]);
            assertEquals(favoured == 0 ? 1.0 : 0.0, acc.metrics().top1(), 1e-12,
                    "the first of two equal targets is the teacher's move; the network prefers move " + favoured);
        }
    }
}
