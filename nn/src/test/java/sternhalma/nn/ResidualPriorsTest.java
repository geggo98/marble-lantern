package sternhalma.nn;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import sternhalma.engine.Geometry;
import sternhalma.engine.Layout;
import sternhalma.engine.Move;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;

/**
 * The residual priors of {@link PlaneSet#CLASSIC1} (docs/network.md, "Classical planes and residual priors"): a
 * network with zero net weights is exactly the classical prior, {@code prior.beta} and {@code prior.k} scale it, every
 * forward pass agrees on it, and the trainer differentiates it exactly.
 */
class ResidualPriorsTest {

    private static NetworkConfig tiny(int players, PlaneSet planes) {
        return NetworkConfig.tiny(players).withPlanes(planes);
    }

    /** Parameters with every net weight and bias zero; only the two scalars are set. */
    private static Parameters zeroNet(NetworkConfig config, float beta, float k) {
        Parameters p = Parameters.uniform(config, 1, 0.5f);
        for (Tensor t : p.tensors().values()) {
            Arrays.fill(t.data(), 0f);
        }
        p.get(ResidualPriors.BETA).data()[0] = beta;
        p.get(ResidualPriors.K).data()[0] = k;
        return p;
    }

    private static List<Position> positions(int players, long seed) {
        List<Position> all = Playouts.positions(players, 3, 120, seed);
        List<Position> picked = new ArrayList<>();
        for (int i = 0; i < all.size(); i += 4) {
            if (all.get(i).legalMoves().length > 0) {
                picked.add(all.get(i));
            }
        }
        return picked;
    }

    private static double gain(Position p, int move) {
        Layout layout = p.layout();
        int mover = p.sideToMove();
        return layout.distance(mover, Move.from(move)) - layout.distance(mover, Move.to(move));
    }

    // ---- the classical prior ----

    @Test
    void aNetworkWithZeroWeightsIsExactlyTheClassicalPriorForEveryMoverAndForwardPass() {
        NetworkConfig config = tiny(2, PlaneSet.CLASSIC1);
        Parameters parameters = zeroNet(config, 1f, 0.5f);
        int checked = 0;
        for (ForwardPass pass : List.of(new Network(parameters), new VectorNetwork(parameters))) {
            Evaluator evaluator = new Evaluator(pass);
            for (Position p : positions(2, 5)) {
                Evaluation e = evaluator.evaluate(p);
                int[] moves = e.moves();
                double max = Double.NEGATIVE_INFINITY;
                for (int m : moves) {
                    max = Math.max(max, gain(p, m));
                }
                double sum = 0;
                for (int m : moves) {
                    sum += Math.exp(gain(p, m) - max);
                }
                for (int i = 0; i < moves.length; i++) {
                    assertEquals(Math.exp(gain(p, moves[i]) - max) / sum, e.priors()[i], 1e-6,
                            "softmax(gain), ply " + p.ply() + ", move " + Move.toString(moves[i]));
                }
                // the value: tanh(0.5 * logit) for the mover, the negative for the other player
                double logit = ClassicalPlanes.linearLogit(p);
                assertEquals(Math.tanh(0.5 * logit), e.values()[p.sideToMove()], 1e-6);
                assertEquals(-Math.tanh(0.5 * logit), e.values()[1 - p.sideToMove()], 1e-6);
                checked++;
            }
        }
        assertTrue(checked > 60, "positions checked: " + checked);
    }

    @Test
    void anUntrainedNetworkFromInitIsThePriorToo() {
        NetworkConfig config = tiny(2, PlaneSet.CLASSIC1);
        Parameters init = Parameters.init(config, 9);
        Parameters zero = zeroNet(config, 1f, 0.5f);
        assertEquals(1f, init.get(ResidualPriors.BETA).data()[0]);
        assertEquals(0.5f, init.get(ResidualPriors.K).data()[0]);
        for (String name : List.of("head_policy.weight", "head_value.dense2.weight")) {
            for (float v : init.get(name).data()) {
                assertEquals(0f, v, name);
            }
        }
        // the trunk is random, the output is not
        assertTrue(Arrays.stream(toDouble(init.get("conv_in.weight").data())).anyMatch(v -> v != 0));
        for (Position p : positions(2, 7)) {
            Evaluation a = new Evaluator(new Network(init)).evaluate(p);
            Evaluation b = new Evaluator(new Network(zero)).evaluate(p);
            assertArrayEquals(b.priors(), a.priors(), 1e-6f);
            assertArrayEquals(b.values(), a.values(), 1e-6f);
        }
    }

    private static double[] toDouble(float[] a) {
        double[] d = new double[a.length];
        for (int i = 0; i < a.length; i++) {
            d[i] = a[i];
        }
        return d;
    }

    @Test
    void initKeepsTheDrawsOfThePlanesOnlyVariantExceptForTheTwoOutputLayers() {
        Parameters withPriors = Parameters.init(tiny(2, PlaneSet.CLASSIC1), 3);
        Parameters planesOnly = Parameters.init(tiny(2, PlaneSet.CLASSIC1_PLANES), 3);
        for (Map.Entry<String, Tensor> e : planesOnly.tensors().entrySet()) {
            float[] other = withPriors.get(e.getKey()).data();
            if (e.getKey().equals("head_policy.weight") || e.getKey().equals("head_value.dense2.weight")) {
                assertTrue(Arrays.stream(toDouble(e.getValue().data())).anyMatch(v -> v != 0), "drawn: " + e.getKey());
                continue;
            }
            assertArrayEquals(e.getValue().data(), other, e.getKey());
        }
        assertEquals(2, withPriors.tensors().size() - planesOnly.tensors().size());
    }

    @Test
    void betaAndKScaleTheTwoTerms() {
        NetworkConfig config = tiny(2, PlaneSet.CLASSIC1);
        Position p = positions(2, 9).get(20);
        float[] input = new float[config.inputSize()];
        Encoder.encode(PlaneSet.CLASSIC1, p, input, 0);
        float[] base = new float[Encoder.POLICY_SIZE];
        float[] baseValues = new float[2];
        new Network(zeroNet(config, 1f, 0.5f)).forward(input, 1, base, baseValues);
        float[] doubled = new float[Encoder.POLICY_SIZE];
        float[] doubledValues = new float[2];
        new Network(zeroNet(config, 2f, 1f)).forward(input, 1, doubled, doubledValues);
        for (int i = 0; i < base.length; i++) {
            assertEquals(2 * base[i], doubled[i], 1e-6);
        }
        assertNotEquals(0f, baseValues[0]);
        assertEquals(Math.tanh(ClassicalPlanes.linearLogit(p)), doubledValues[0], 1e-5);
        float[] none = new float[Encoder.POLICY_SIZE];
        float[] noneValues = new float[2];
        new Network(zeroNet(config, 0f, 0f)).forward(input, 1, none, noneValues);
        assertArrayEquals(new float[Encoder.POLICY_SIZE], none);
        assertArrayEquals(new float[2], noneValues);
        // a negative beta prefers the moves that gain least
        float[] negative = new float[Encoder.POLICY_SIZE];
        new Network(zeroNet(config, -1f, 0.5f)).forward(input, 1, negative, new float[2]);
        for (int i = 0; i < base.length; i++) {
            assertEquals(-base[i], negative[i], 1e-6);
        }
    }

    @Test
    void thePriorLogitOfAMoveIsBetaTimesItsDistanceGainOnTopOfTheNetLogit() {
        NetworkConfig config = tiny(2, PlaneSet.CLASSIC1);
        Parameters random = Parameters.uniform(config, 21, 0.3f);
        Parameters off = Parameters.uniform(config, 21, 0.3f);
        off.get(ResidualPriors.BETA).data()[0] = 0f;
        float beta = random.get(ResidualPriors.BETA).data()[0];
        for (Position p : positions(2, 11).subList(0, 40)) {
            float[] input = new float[config.inputSize()];
            Encoder.encode(PlaneSet.CLASSIC1, p, input, 0);
            float[] with = new float[Encoder.POLICY_SIZE];
            float[] without = new float[Encoder.POLICY_SIZE];
            new Network(random).forward(input, 1, with, new float[2]);
            new Network(off).forward(input, 1, without, new float[2]);
            Orientation o = Orientation.of(p);
            for (int move : p.legalMoves()) {
                double delta = Encoder.moveLogit(o, with, 0, move) - Encoder.moveLogit(o, without, 0, move);
                assertEquals(beta * gain(p, move), delta, 1e-4, "ply " + p.ply() + " " + Move.toString(move));
            }
        }
    }

    // ---- the forward passes agree ----

    @Test
    void networkVectorNetworkAndDoubleNetworkAgreeWithPriorsForTwoAndThreePlayers() {
        for (int players = 2; players <= 3; players++) {
            for (PlaneSet planes : List.of(PlaneSet.CLASSIC1, PlaneSet.CLASSIC1_PLANES)) {
                NetworkConfig config = new NetworkConfig(8, 2, players, 16, planes);
                Parameters parameters = Parameters.uniform(config, 31 + players, 0.25f);
                Network reference = new Network(parameters);
                VectorNetwork vector = new VectorNetwork(parameters);
                DoubleNetwork exact = new DoubleNetwork(parameters);
                List<Position> ps = positions(players, 40 + players);
                int n = Math.min(ps.size(), 24);
                float[] input = new float[n * config.inputSize()];
                for (int b = 0; b < n; b++) {
                    Encoder.encode(planes, ps.get(b), input, b * config.inputSize());
                }
                float[] policyR = new float[n * Encoder.POLICY_SIZE];
                float[] valuesR = new float[n * players];
                reference.forward(input, n, policyR, valuesR);
                float[] policyV = new float[n * Encoder.POLICY_SIZE];
                float[] valuesV = new float[n * players];
                vector.forward(input, n, policyV, valuesV);
                for (int b = 0; b < n; b++) {
                    DoubleNetwork.Output out = exact.forward(input, b * config.inputSize());
                    for (int i = 0; i < Encoder.POLICY_SIZE; i++) {
                        double r = policyR[b * Encoder.POLICY_SIZE + i];
                        assertTrue(Network.agrees(policyV[b * Encoder.POLICY_SIZE + i], r), "vector policy " + i);
                        assertEquals(out.policy()[i], r, 1e-4 + 1e-4 * Math.abs(r), "double policy " + i);
                    }
                    for (int i = 0; i < players; i++) {
                        double r = valuesR[b * players + i];
                        assertTrue(Network.agrees(valuesV[b * players + i], r), "vector value " + i);
                        assertEquals(out.values()[i], r, 1e-5, "double value " + i);
                    }
                }
            }
        }
    }

    @Test
    void theEvaluationTermOfTheValueIsZeroForThreePlayersAndSignedForTwo() {
        // two players: the term is +e for the mover, -e for the other player
        NetworkConfig two = tiny(2, PlaneSet.CLASSIC1);
        Position p = positions(2, 13).get(15);
        float[] input = new float[two.inputSize()];
        Encoder.encode(PlaneSet.CLASSIC1, p, input, 0);
        float[] values = new float[2];
        new Network(zeroNet(two, 0f, 1f)).forward(input, 1, new float[Encoder.POLICY_SIZE], values);
        double e = ClassicalPlanes.linearLogit(p);
        assertEquals(Math.tanh(e), values[0], 1e-6);
        assertEquals(Math.tanh(-e), values[1], 1e-6);
        assertNotEquals(0.0, e);
        // three players: no linear evaluation, the value is the net's alone
        NetworkConfig three = tiny(3, PlaneSet.CLASSIC1);
        Position q = positions(3, 14).get(15);
        float[] in3 = new float[three.inputSize()];
        Encoder.encode(PlaneSet.CLASSIC1, q, in3, 0);
        float[] v3 = new float[3];
        new Network(zeroNet(three, 0f, 1f)).forward(in3, 1, new float[Encoder.POLICY_SIZE], v3);
        assertArrayEquals(new float[3], v3);
    }

    @Test
    void aNetworkOfOneSetRejectsTheInputOfAnother() {
        NetworkConfig classic = tiny(2, PlaneSet.CLASSIC1);
        Network network = new Network(Parameters.init(classic, 1));
        float[] plain = new float[Encoder.INPUT_SIZE];
        Encoder.encode(positions(2, 3).get(5), plain, 0);
        assertThrows(IllegalArgumentException.class,
                () -> network.forward(plain, 1, new float[Encoder.POLICY_SIZE], new float[2]));
        VectorNetwork vector = new VectorNetwork(Parameters.init(classic, 1));
        assertThrows(IllegalArgumentException.class,
                () -> vector.forward(plain, 1, new float[Encoder.POLICY_SIZE], new float[2]));
    }

    // ---- the trainer ----

    private static TrainingBatch batch(int players, PlaneSet planes, int samples, long seed) {
        List<Position> positions = Playouts.positions(players, 4, 40, seed);
        return TestBatches.fromPositions(players, planes, TestBatches.pick(positions, samples), seed);
    }

    @Test
    void theTrainerForwardEqualsNetworkBitForBitWithPriors() {
        for (int players = 2; players <= 3; players++) {
            NetworkConfig config = new NetworkConfig(4, 2, players, 8, PlaneSet.CLASSIC1);
            for (Parameters parameters : List.of(Parameters.init(config, 4), Parameters.uniform(config, 5, 0.3f))) {
                TrainingBatch batch = batch(players, PlaneSet.CLASSIC1, 6, 17 + players);
                ReferenceTrainer.Worker worker = new ReferenceTrainer.Worker(parameters, 1.0);
                Network network = new Network(parameters);
                for (int s = 0; s < batch.size(); s++) {
                    worker.forward(batch.input(), s * batch.inputSize());
                    float[] policy = new float[Encoder.POLICY_SIZE];
                    float[] values = new float[players];
                    network.forward(Arrays.copyOfRange(batch.input(), s * batch.inputSize(),
                            (s + 1) * batch.inputSize()), 1, policy, values);
                    assertArrayEquals(policy, worker.policy, "policy of sample " + s);
                    assertArrayEquals(values, worker.values, "values of sample " + s);
                }
            }
        }
    }

    /**
     * The sum over the finite differences of {@code prior.beta} and {@code prior.k} only, on the full classical network,
     * for the loss of a mixed-mover batch: the exact gradient of the two scalars (the whole-network check is in
     * {@code ReferenceTrainerTest}).
     */
    @Test
    void thePriorScalarsHaveTheExactGradientForTwoPlayersAndMixedMovers(TestReporter reporter) {
        NetworkConfig config = new NetworkConfig(4, 2, 2, 8, PlaneSet.CLASSIC1);
        Parameters parameters = Parameters.uniform(config, 61, 0.3f);
        TrainingBatch batch = TestBatches.plusCornerSample(batch(2, PlaneSet.CLASSIC1, 6, 63));
        ReferenceTrainer trainer = new ReferenceTrainer(parameters, 0.7);
        trainer.accumulate(batch, 4, 1);
        DoubleNetwork net = new DoubleNetwork(parameters);
        for (String name : List.of(ResidualPriors.BETA, ResidualPriors.K)) {
            double[] p = net.param(name);
            double theta = p[0];
            double h = 1e-6;
            p[0] = theta + h;
            double up = net.loss(batch, 0.7, new ArrayList<>());
            p[0] = theta - h;
            double down = net.loss(batch, 0.7, new ArrayList<>());
            p[0] = theta;
            double numeric = (up - down) / (2 * h);
            double analytic = trainer.gradients().get(name)[0];
            assertTrue(Math.abs(numeric) > 1e-3, name + " has a non-trivial gradient: " + numeric);
            assertEquals(numeric, analytic, 1e-4 * Math.abs(numeric), name);
            reporter.publishEntry(name, String.format(Locale.ROOT, "analytic %.6g, numeric %.6g", analytic, numeric));
        }
    }

    /**
     * Mirror symmetry of the priors in the trainer: with all convolution weights at zero the trunk is the same in every
     * cell, so the network is mirror-symmetric and a sample and its mirror image (planes from the expander with {@code
     * mirror}, moves mapped by {@code Geometry.mirror}) must give the same loss and the same gradient everywhere, the
     * two scalars included. A prior that used the wrong rows of the mirrored planes would differ.
     */
    @Test
    void aSampleAndItsMirrorGiveTheSameLossAndGradientForASymmetricNetwork() {
        NetworkConfig config = new NetworkConfig(4, 1, 2, 8, PlaneSet.CLASSIC1);
        Parameters parameters = Parameters.uniform(config, 71, 0.4f);
        for (Map.Entry<String, Tensor> e : parameters.tensors().entrySet()) {
            if (e.getKey().startsWith("conv_in.weight") || e.getKey().contains(".conv")) {
                if (e.getKey().endsWith(".weight")) {
                    Arrays.fill(e.getValue().data(), 0f);
                }
            }
        }
        List<Position> ps = TestBatches.pick(Playouts.positions(2, 4, 60, 73), 8);
        TrainingBatch plain = TestBatches.fromPositions(2, PlaneSet.CLASSIC1, ps, 75);
        TrainingBatch.Builder mirrored = new TrainingBatch.Builder(2, PlaneSet.CLASSIC1);
        for (int s = 0; s < plain.size(); s++) {
            Position p = ps.get(s);
            float[] input = new float[config.inputSize()];
            SampleExpander.expand(PlaneSet.CLASSIC1, 2, SampleExpanderTest.cellsOf(p), 0, p.ply(),
                    p.rules().moveLimit(), true, input, 0);
            int first = plain.moveOffsets()[s];
            int end = plain.moveOffsets()[s + 1];
            int[] from = new int[end - first];
            int[] to = new int[end - first];
            double[] target = new double[end - first];
            for (int m = first; m < end; m++) {
                from[m - first] = Geometry.mirror(plain.moveFrom()[m]);
                to[m - first] = Geometry.mirror(plain.moveTo()[m]);
                target[m - first] = plain.moveTarget()[m];
            }
            mirrored.add(input, from, to, target, Arrays.copyOfRange(plain.valueTarget(), 2 * s, 2 * s + 2),
                    plain.policyWeight()[s], plain.valueWeight()[s]);
        }
        ReferenceTrainer a = new ReferenceTrainer(parameters, 1.0);
        ReferenceTrainer b = new ReferenceTrainer(parameters, 1.0);
        ReferenceTrainer.Result ra = a.accumulate(plain, 3, 1);
        ReferenceTrainer.Result rb = b.accumulate(mirrored.build(), 3, 1);
        assertEquals(ra.loss(), rb.loss(), 1e-5 * Math.abs(ra.loss()));
        for (String name : List.of(ResidualPriors.BETA, ResidualPriors.K, "head_value.dense2.bias")) {
            double ga = a.gradients().get(name)[0];
            assertTrue(Math.abs(ga) > 1e-6, name + " is not trivially zero");
            assertEquals(ga, b.gradients().get(name)[0], 1e-6 * Math.abs(ga) + 1e-9, name);
        }
    }

    @Test
    void trainingMovesTheScalarsWithoutWeightDecayAndLowersTheLoss() {
        NetworkConfig config = tiny(2, PlaneSet.CLASSIC1);
        Parameters parameters = Parameters.init(config, 5);
        TrainingBatch batch = batch(2, PlaneSet.CLASSIC1, 8, 81);
        Optimizer optimizer = new Optimizer(parameters, Optimizer.Settings.adamw());
        ReferenceTrainer trainer = new ReferenceTrainer(parameters, 1.0);
        double first = 0;
        double last = 0;
        for (int step = 0; step < 40; step++) {
            trainer.zeroGradients();
            ReferenceTrainer.Result r = trainer.accumulate(batch, 4, 1);
            if (step == 0) {
                first = r.loss();
            }
            last = r.loss();
            optimizer.step(trainer.gradients(), 0.01);
        }
        assertTrue(last < 0.8 * first, "loss " + first + " -> " + last);
        assertNotEquals(ResidualPriors.INITIAL_BETA, parameters.get(ResidualPriors.BETA).data()[0]);
        assertNotEquals(ResidualPriors.INITIAL_K, parameters.get(ResidualPriors.K).data()[0]);
    }

    @Test
    void theTrainerRefusesABatchWithTheWrongNumberOfPlanes() {
        Parameters classic = Parameters.init(tiny(2, PlaneSet.CLASSIC1), 1);
        TrainingBatch plain = batch(2, PlaneSet.PLAIN, 2, 3);
        IllegalArgumentException narrow = assertThrows(IllegalArgumentException.class,
                () -> new ReferenceTrainer(classic, 1.0).accumulate(plain, 1, 1));
        assertTrue(narrow.getMessage().contains("7 planes per cell"), narrow.getMessage());
        Parameters plainNet = Parameters.init(tiny(2, PlaneSet.PLAIN), 1);
        TrainingBatch classicBatch = batch(2, PlaneSet.CLASSIC1, 2, 3);
        IllegalArgumentException wide = assertThrows(IllegalArgumentException.class,
                () -> new ReferenceTrainer(plainNet, 1.0).accumulate(classicBatch, 1, 1));
        assertTrue(wide.getMessage().contains("13 planes per cell"), wide.getMessage());
    }

    @Test
    void aBatchOfThePlanesHasTheirWidthAndSelectKeepsIt() {
        TrainingBatch classic = batch(2, PlaneSet.CLASSIC1, 4, 5);
        assertEquals(13, classic.inputPlanes());
        assertEquals(122 * 13, classic.inputSize());
        TrainingBatch picked = classic.select(new int[] {3, 1, 1});
        assertEquals(13, picked.inputPlanes());
        assertArrayEquals(Arrays.copyOfRange(classic.input(), 3 * classic.inputSize(), 4 * classic.inputSize()),
                Arrays.copyOfRange(picked.input(), 0, classic.inputSize()));
        assertEquals(7, batch(2, PlaneSet.PLAIN, 4, 5).inputPlanes());
        assertThrows(IllegalArgumentException.class, () -> new TrainingBatch.Builder(2, PlaneSet.CLASSIC1)
                .add(new float[Encoder.INPUT_SIZE], new int[] {0}, new int[] {1}, new double[] {1}, new float[2], 1f, 1f));
        // a width that is neither 7 nor 13 planes
        assertThrows(IllegalArgumentException.class, () -> new TrainingBatch(1, 2, new float[122 * 9], new int[] {0, 0},
                new int[0], new int[0], new float[0], new float[2], new float[] {0f}, new float[] {1f},
                new double[] {0}));
    }

    @Test
    void theEvaluatorEncodesWithThePlanesOfItsNetwork() {
        Position start = Position.initial(RuleSet.defaults(2));
        for (PlaneSet planes : List.of(PlaneSet.PLAIN, PlaneSet.CLASSIC1, PlaneSet.CLASSIC1_PLANES)) {
            Parameters parameters = Parameters.init(tiny(2, planes), 1);
            Evaluation e = new Evaluator(new Network(parameters)).evaluate(start);
            float sum = 0;
            for (float f : e.priors()) {
                sum += f;
            }
            assertEquals(1f, sum, 1e-5);
        }
    }
}
