package sternhalma.tornado;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;

import sternhalma.engine.Position;
import sternhalma.nn.Encoder;
import sternhalma.nn.Evaluation;
import sternhalma.nn.Evaluator;
import sternhalma.nn.ForwardPass;
import sternhalma.nn.Network;
import sternhalma.nn.NetworkConfig;
import sternhalma.nn.Parameters;
import sternhalma.nn.PlaneSet;
import sternhalma.nn.ResidualPriors;
import sternhalma.nn.VectorNetwork;

/**
 * {@link TornadoNetwork} with the residual priors of {@code classic1} (docs/tornado.md, "Residual priors on the host"):
 * the device returns the raw logits and the value before its tanh, the host adds {@code beta * gain} and
 * {@code k * sign * eval} and applies the tanh. Checked against the reference {@link Network} and against
 * {@link VectorNetwork} with the tolerance of {@link Network#agrees}, for SMALL and LARGE, plain and classic1, and batch
 * sizes from 1 to 512. The scalars are set away from their initial values (beta 0.7, k 1.3), and a test checks that the
 * priors matter in the outputs, so a prior that is added twice, not at all, or with the wrong sign cannot pass.
 */
class TornadoClassic1Test {

    private static final float BETA = 0.7f;
    private static final float K = 1.3f;

    private static Parameters weights(NetworkConfig config, long seed, float beta, float k) {
        Parameters p = Parameters.uniform(config, seed, (float) Math.sqrt(6.0 / (7 * config.channels())));
        if (config.planes().residualPriors()) {
            p.get(ResidualPriors.BETA).data()[0] = beta;
            p.get(ResidualPriors.K).data()[0] = k;
        }
        return p;
    }

    private static Parameters weights(NetworkConfig config, long seed) {
        return weights(config, seed, BETA, K);
    }

    /** Encoded positions of seeded random games; the positions of the batch are spread over the games. */
    private static float[] encode(PlaneSet planes, int batch, long seed) {
        List<Position> positions = Playouts.positions(2, 6, 150, seed);
        int size = planes.inputSize();
        float[] input = new float[batch * size];
        for (int b = 0; b < batch; b++) {
            Encoder.encode(planes, positions.get((int) ((long) b * 7919 % positions.size())), input, b * size);
        }
        return input;
    }

    private static float[][] run(ForwardPass network, float[] input, int batch) {
        float[] policy = new float[batch * Encoder.POLICY_SIZE];
        float[] values = new float[batch * network.config().players()];
        network.forward(input, batch, policy, values);
        return new float[][] {policy, values};
    }

    /** Checks the first {@code n} floats against the reference with {@link Network#agrees}; returns error / bound. */
    private static double ratio(float[] actual, float[] expected, int n, String what) {
        double worst = 0;
        for (int i = 0; i < n; i++) {
            double bound = Network.ABSOLUTE_TOLERANCE + Network.RELATIVE_TOLERANCE * Math.abs(expected[i]);
            if (!Network.agrees(actual[i], expected[i])) {
                fail(what + " " + i + ": device " + actual[i] + ", reference " + expected[i] + ", allowed " + bound);
            }
            worst = Math.max(worst, Math.abs(actual[i] - expected[i]) / bound);
        }
        return worst;
    }

    private static double maxDifference(float[] a, float[] b) {
        double m = 0;
        for (int i = 0; i < a.length; i++) {
            m = Math.max(m, Math.abs(a[i] - b[i]));
        }
        return m;
    }

    @Test
    void classic1AgreesWithTheReferenceAndTheVectorNetwork(TestReporter reporter) {
        TornadoAssumptions.assumeTornado();
        record Case(String name, NetworkConfig config, int[] batches) {
        }
        List<Case> cases = List.of(
                new Case("SMALL classic1", NetworkConfig.small(2).withPlanes(PlaneSet.CLASSIC1),
                        new int[] {1, 2, 3, 16, 64, 100, 512}),
                new Case("LARGE classic1", NetworkConfig.large(2).withPlanes(PlaneSet.CLASSIC1),
                        new int[] {1, 3, 64, 130, 512}),
                new Case("SMALL plain", NetworkConfig.small(2), new int[] {1, 64, 512}),
                new Case("LARGE plain", NetworkConfig.large(2), new int[] {1, 64}));
        for (Case c : cases) {
            Parameters parameters = weights(c.config(), 41);
            int largest = Arrays.stream(c.batches()).max().orElseThrow();
            float[] input = encode(c.config().planes(), largest, 5);
            float[][] reference = run(new Network(parameters), input, largest);
            float[][] vector = run(new VectorNetwork(parameters), input, largest);
            double worst = 0;
            try (TornadoNetwork device = new TornadoNetwork(parameters)) {
                for (int batch : c.batches()) {
                    float[][] out = run(device, input, batch);
                    worst = Math.max(worst, ratio(out[0], reference[0], batch * Encoder.POLICY_SIZE,
                            c.name() + " logit, batch " + batch));
                    worst = Math.max(worst, ratio(out[1], reference[1], batch * 2, c.name() + " value, batch " + batch));
                    ratio(out[0], vector[0], batch * Encoder.POLICY_SIZE, c.name() + " logit vs vector, batch " + batch);
                    ratio(out[1], vector[1], batch * 2, c.name() + " value vs vector, batch " + batch);
                }
            }
            reporter.publishEntry(c.name(), String.format("largest error / bound %.4f", worst));
        }
    }

    /** What the priors add is large against the tolerance, and exactly beta * 16 * d on "from" and "to" logits. */
    @Test
    void thePriorsMatterInTheOutputs() {
        TornadoAssumptions.assumeTornado();
        NetworkConfig config = NetworkConfig.small(2).withPlanes(PlaneSet.CLASSIC1);
        int batch = 32;
        float[] input = encode(config.planes(), batch, 3);
        float[][] with;
        float[][] without;
        try (TornadoNetwork device = new TornadoNetwork(weights(config, 7));
                TornadoNetwork bare = new TornadoNetwork(weights(config, 7, 0f, 0f))) {
            with = run(device, input, batch);
            without = run(bare, input, batch);
        }
        assertTrue(maxDifference(with[0], without[0]) > 1.0, "the distance prior must move the logits by more than 1");
        assertTrue(maxDifference(with[1], without[1]) > 0.05, "the evaluation prior must move the values");
        for (int b = 0; b < batch; b++) {
            for (int cell = 0; cell < 121; cell++) {
                float d = input[b * config.inputSize() + Encoder.index(config.inputPlanes(), cell, Encoder.DIST_OWN)]
                        * 16 * BETA;
                int at = b * Encoder.POLICY_SIZE + 2 * cell;
                assertEquals(d, with[0][at] - without[0][at], 1e-4, "from logit of cell " + cell + " in " + b);
                assertEquals(-d, with[0][at + 1] - without[0][at + 1], 1e-4, "to logit of cell " + cell + " in " + b);
            }
        }
    }

    @Test
    void reloadTakesTheNewPriorScalars() {
        TornadoAssumptions.assumeTornado();
        NetworkConfig config = NetworkConfig.tiny(2).withPlanes(PlaneSet.CLASSIC1);
        Parameters parameters = weights(config, 11);
        int batch = 8;
        float[] input = encode(config.planes(), batch, 4);
        try (TornadoNetwork device = new TornadoNetwork(parameters)) {
            float[][] first = run(device, input, batch);
            parameters.get(ResidualPriors.BETA).data()[0] = 1.9f;
            parameters.get(ResidualPriors.K).data()[0] = -0.4f;
            assertArrayEquals(first[0], run(device, input, batch)[0], "no effect before reload");
            device.reload();
            float[][] now = run(device, input, batch);
            float[][] expected = run(new Network(parameters), input, batch);
            ratio(now[0], expected[0], batch * Encoder.POLICY_SIZE, "logit after reload");
            ratio(now[1], expected[1], batch * 2, "value after reload");
            assertTrue(maxDifference(first[0], now[0]) > 0.5);
        }
    }

    @Test
    void theEvaluatorOfAClassic1DeviceNetworkAgreesWithTheCpuEvaluator() {
        TornadoAssumptions.assumeTornado();
        NetworkConfig config = NetworkConfig.small(2).withPlanes(PlaneSet.CLASSIC1);
        Parameters parameters = weights(config, 23);
        List<Position> all = Playouts.positions(2, 3, 80, 12);
        List<Position> positions = all.subList(0, Math.min(70, all.size()));
        Evaluator cpu = new Evaluator(new Network(parameters));
        try (TornadoNetwork device = new TornadoNetwork(parameters)) {
            List<Evaluation> got = new Evaluator(device).evaluate(positions);
            for (int i = 0; i < positions.size(); i++) {
                Evaluation expected = cpu.evaluate(positions.get(i));
                assertArrayEquals(expected.moves(), got.get(i).moves());
                for (int m = 0; m < expected.priors().length; m++) {
                    assertTrue(Network.agrees(got.get(i).priors()[m], expected.priors()[m]), "prior " + m + " of " + i);
                }
                for (int a = 0; a < 2; a++) {
                    assertTrue(Network.agrees(got.get(i).values()[a], expected.values()[a]), "value of " + i);
                }
            }
        }
    }

    /**
     * The result of a position does not depend on its slot or on the bucket: the same position in slot 0 of a batch of 1,
     * in slot 5 of 64 and in slot 300 of 512 (three buckets, NAIVE and SIMDGROUP kernels) gives the same bits. The
     * lockstep self-play relies on this for a game that does not depend on the company it is batched with.
     */
    @Test
    void theResultDoesNotDependOnTheSlotOrTheBucket(TestReporter reporter) {
        TornadoAssumptions.assumeTornado();
        for (NetworkConfig config : List.of(NetworkConfig.small(2).withPlanes(PlaneSet.CLASSIC1),
                NetworkConfig.large(2).withPlanes(PlaneSet.CLASSIC1))) {
            Parameters parameters = weights(config, 99);
            int size = config.inputSize();
            float[] big = encode(config.planes(), 512, 8);
            int[][] places = {{1, 0}, {64, 5}, {512, 300}, {37, 36}, {2, 1}, {130, 129}};
            double worst = 0;
            try (TornadoNetwork device = new TornadoNetwork(parameters)) {
                for (int probe : new int[] {3, 100, 301}) {
                    float[] pos = Arrays.copyOfRange(big, probe * size, (probe + 1) * size);
                    float[][] first = null;
                    for (int[] place : places) {
                        int batch = place[0];
                        int slot = place[1];
                        float[] in = Arrays.copyOf(big, batch * size);
                        System.arraycopy(pos, 0, in, slot * size, size);
                        float[][] out = run(device, in, batch);
                        float[][] mine = {
                            Arrays.copyOfRange(out[0], slot * Encoder.POLICY_SIZE, (slot + 1) * Encoder.POLICY_SIZE),
                            Arrays.copyOfRange(out[1], slot * 2, slot * 2 + 2)};
                        if (first == null) {
                            first = mine;
                        } else {
                            worst = Math.max(worst, Math.max(maxDifference(mine[0], first[0]),
                                    maxDifference(mine[1], first[1])));
                        }
                    }
                }
            }
            reporter.publishEntry(config.channels() + " channels", "largest difference between slots and buckets " + worst);
            assertEquals(0.0, worst, "the device result must not depend on the slot or the bucket");
        }
    }

    /** The host priors of a chunk use the positions of that chunk: maxBatch 4 with batch 11 (4 + 4 + 3), maxBatch 6 with 13. */
    @Test
    void theHostPriorsOfEveryChunkUseTheirOwnPositions() {
        TornadoAssumptions.assumeTornado();
        NetworkConfig config = NetworkConfig.tiny(2).withPlanes(PlaneSet.CLASSIC1);
        Parameters parameters = weights(config, 17);
        for (int[] shape : new int[][] {{4, 11}, {6, 13}}) {
            float[] input = encode(config.planes(), shape[1], 6);
            float[][] expected = run(new Network(parameters), input, shape[1]);
            try (TornadoNetwork device = new TornadoNetwork(parameters, TornadoSupport.defaultDevice(), shape[0])) {
                float[][] got = run(device, input, shape[1]);
                ratio(got[0], expected[0], shape[1] * Encoder.POLICY_SIZE, "logit, maxBatch " + shape[0]);
                ratio(got[1], expected[1], shape[1] * 2, "value, maxBatch " + shape[0]);
            }
        }
    }

    @Test
    void aDirtyPaddingRowIsRefusedWithPriors() {
        TornadoAssumptions.assumeTornado();
        NetworkConfig config = NetworkConfig.tiny(2).withPlanes(PlaneSet.CLASSIC1);
        float[] input = encode(config.planes(), 2, 1);
        input[config.inputSize() + 121 * config.inputPlanes() + 2] = 1f;
        try (TornadoNetwork device = new TornadoNetwork(weights(config, 3))) {
            assertThrows(IllegalArgumentException.class, () -> run(device, input, 2));
        }
    }
}
