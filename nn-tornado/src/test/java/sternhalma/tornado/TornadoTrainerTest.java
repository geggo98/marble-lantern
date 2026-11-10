package sternhalma.tornado;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import sternhalma.nn.Encoder;
import sternhalma.nn.HexGrid;
import sternhalma.nn.NetworkConfig;
import sternhalma.nn.Parameters;
import sternhalma.nn.PlaneSet;
import sternhalma.nn.ReferenceLoss;
import sternhalma.nn.ReferenceTrainer;
import sternhalma.nn.ResidualPriors;
import sternhalma.nn.TrainingBatch;
import sternhalma.tornado.TrainTesting.Sample;
import sternhalma.tornado.TrainTesting.Worst;

/**
 * The training forward pass, the loss kernels and the head backward of {@link TornadoTrainer} (blocks B3, B4 and
 * B5 of docs/training.md, Part B) against the CPU reference trainer, with the tolerances of "Verification against
 * Part A". Every comparison reports its largest error as a fraction of its bound.
 *
 * <p>Each case runs the trainer once and keeps the results ({@link Fixture}), so that the tests of the three
 * blocks share one execution per case.
 */
class TornadoTrainerTest {

    private static List<Fixture> all;

    @BeforeEach
    void requireTornado() {
        TornadoAssumptions.assumeTornado();
    }

    @AfterAll
    static void closeAll() {
        if (all != null) {
            for (Fixture f : all) {
                f.trainer.close();
            }
            all = null;
        }
    }

    /** One network, one parameter set, one batch, the trainer's result and the reference's. */
    private static final class Fixture {
        final String name;
        final NetworkConfig config;
        final Parameters parameters;
        final TrainingBatch batch;
        final double cv;
        final TornadoTrainer trainer;
        final ReferenceTrainer.Result gpuResult;
        final ReferenceTrainer.Result refResult;
        final ReferenceTrainer reference;
        final Sample[] samples;

        Fixture(String name, NetworkConfig config, Parameters parameters, TrainingBatch batch, double cv) {
            this.name = name;
            this.config = config;
            this.parameters = parameters;
            this.batch = batch;
            this.cv = cv;
            this.trainer = new TornadoTrainer(parameters, batch.size(), batch.moveCount() + 7, cv,
                    TornadoSupport.defaultDevice());
            this.gpuResult = trainer.run(batch);
            this.reference = new ReferenceTrainer(parameters, cv);
            this.refResult = reference.accumulate(batch, batch.size(), 1);
            this.samples = new Sample[batch.size()];
            for (int s = 0; s < samples.length; s++) {
                samples[s] = TrainTesting.reference(parameters, batch, s, cv);
            }
        }
    }

    /** Networks, weights and batches: plain and classic1, 2 and 3 players, TINY and SMALL, odd channel counts. */
    private static synchronized List<Fixture> fixtures() {
        if (all != null) {
            return all;
        }
        List<Fixture> list = new ArrayList<>();
        NetworkConfig tiny2 = NetworkConfig.tiny(2);
        NetworkConfig tiny3 = NetworkConfig.tiny(3);
        NetworkConfig classicTiny2 = tiny2.withPlanes(PlaneSet.CLASSIC1);
        NetworkConfig classicTiny3 = tiny3.withPlanes(PlaneSet.CLASSIC1);
        NetworkConfig classicSmall2 = NetworkConfig.small(2).withPlanes(PlaneSet.CLASSIC1);
        NetworkConfig odd = new NetworkConfig(5, 2, 3, 7, PlaneSet.CLASSIC1);
        NetworkConfig planesOnly = new NetworkConfig(8, 2, 2, 8, PlaneSet.CLASSIC1_PLANES);
        list.add(new Fixture("tiny2-plain", tiny2, Parameters.uniform(tiny2, 11, TrainTesting.initBound(tiny2)),
                TrainTesting.batch(2, PlaneSet.PLAIN, 24, 3), 1.0));
        list.add(new Fixture("tiny3-plain-big", tiny3, Parameters.uniform(tiny3, 12, 2 * TrainTesting.initBound(tiny3)),
                TrainTesting.batch(3, PlaneSet.PLAIN, 20, 4), 0.75));
        list.add(new Fixture("tiny2-classic1", classicTiny2,
                TrainTesting.classicPrior(Parameters.uniform(classicTiny2, 13, TrainTesting.initBound(classicTiny2))),
                TrainTesting.batch(2, PlaneSet.CLASSIC1, 24, 5), 0.75));
        list.add(new Fixture("tiny3-classic1", classicTiny3,
                TrainTesting.classicPrior(Parameters.uniform(classicTiny3, 14, TrainTesting.initBound(classicTiny3))),
                TrainTesting.batch(3, PlaneSet.CLASSIC1, 21, 6), 1.0));
        list.add(new Fixture("small2-classic1", classicSmall2,
                TrainTesting.classicPrior(Parameters.uniform(classicSmall2, 15, TrainTesting.initBound(classicSmall2))),
                TrainTesting.batch(2, PlaneSet.CLASSIC1, 16, 7), 1.0));
        list.add(new Fixture("small2-classic1-init", classicSmall2, Parameters.init(classicSmall2, 16),
                TrainTesting.batch(2, PlaneSet.CLASSIC1, 16, 8), 1.0));
        list.add(new Fixture("small3-plain-init", NetworkConfig.small(3), Parameters.init(NetworkConfig.small(3), 17),
                TrainTesting.batch(3, PlaneSet.PLAIN, 12, 9), 1.0));
        list.add(new Fixture("odd-classic1", odd, TrainTesting.classicPrior(Parameters.uniform(odd, 18, TrainTesting.initBound(odd))),
                TrainTesting.batch(3, PlaneSet.CLASSIC1, 19, 10), 1.25));
        // A batch above 64 samples: the split-K chunks of the policy head gradient hold several samples, the last one fewer.
        list.add(new Fixture("tiny2-plain-130", tiny2, Parameters.uniform(tiny2, 20, TrainTesting.initBound(tiny2)),
                TrainTesting.batch(2, PlaneSet.PLAIN, 130, 22), 1.0));
        // 300 channels and 300 hidden units: more than the 256 work items of the value head, so every work item pools
        // whole channels and handles more than one hidden unit.
        NetworkConfig wide = new NetworkConfig(300, 0, 2, 300, PlaneSet.CLASSIC1);
        list.add(new Fixture("wide-300", wide, TrainTesting.classicPrior(Parameters.uniform(wide, 23, TrainTesting.initBound(wide))),
                TrainTesting.batch(2, PlaneSet.CLASSIC1, 6, 23), 1.0));
        list.add(new Fixture("tiny2-classic1-planes", planesOnly,
                Parameters.uniform(planesOnly, 19, TrainTesting.initBound(planesOnly)),
                TrainTesting.batch(2, PlaneSet.CLASSIC1, 17, 11), 1.0));
        all = list;
        return list;
    }

    // ------------------------------------------------------------------ B3: forward with the activation store

    /**
     * Every slot of the store (x_0, t_i, u_i), the policy logits (with the prior), pooled, hidden and values of
     * every sample against the reference forward pass. A wrong slot, a residual from the wrong slot, a head that
     * reads the padding row, a missing prior or a wrong sign of the value prior fails here. Row 121 of every slot
     * must be exactly 0.
     */
    @Test
    void forwardActivationsMatchTheReference() {
        for (Fixture f : fixtures()) {
            Worst slots = new Worst(f.name + " slots");
            Worst policy = new Worst(f.name + " policy");
            Worst pooled = new Worst(f.name + " pooled");
            Worst hidden = new Worst(f.name + " hidden");
            Worst values = new Worst(f.name + " values");
            int c = f.config.channels();
            int activation = HexGrid.ROWS * c;
            assertEquals(1 + 2 * f.config.blocks(), f.trainer.slots());
            for (int slot = 0; slot < f.trainer.slots(); slot++) {
                float[] gpu = f.trainer.download(f.trainer.activation(slot));
                for (int s = 0; s < f.batch.size(); s++) {
                    TrainTesting.forwardAgrees(slots, "slot " + slot + " sample " + s, f.samples[s].slots[slot], 0, gpu,
                            s * activation, activation);
                    for (int ch = 0; ch < c; ch++) {
                        assertEquals(0f, gpu[s * activation + HexGrid.PADDING * c + ch],
                                f.name + ": padding row of slot " + slot + " sample " + s);
                    }
                }
            }
            float[] gpuPolicy = f.trainer.download(f.trainer.buffer("policy"));
            float[] gpuPooled = f.trainer.download(f.trainer.buffer("pooled"));
            float[] gpuHidden = f.trainer.download(f.trainer.buffer("hidden"));
            float[] gpuValues = f.trainer.download(f.trainer.buffer("values"));
            for (int s = 0; s < f.batch.size(); s++) {
                Sample r = f.samples[s];
                TrainTesting.forwardAgrees(policy, "sample " + s, r.policy, 0, gpuPolicy, s * Encoder.POLICY_SIZE,
                        Encoder.POLICY_SIZE);
                TrainTesting.forwardAgrees(pooled, "sample " + s, r.pooled, 0, gpuPooled, s * c, c);
                TrainTesting.forwardAgrees(hidden, "sample " + s, r.hidden, 0, gpuHidden,
                        s * f.config.valueHidden(), f.config.valueHidden());
                TrainTesting.forwardAgrees(values, "sample " + s, r.values, 0, gpuValues, s * f.config.players(),
                        f.config.players());
            }
            System.out.println("TornadoTrainer forward vs reference, largest error / bound: " + slots + ", " + policy
                    + ", " + pooled + ", " + hidden + ", " + values);
        }
    }

    /**
     * The checks above are only worth something if the data is lively: the policy logits of a classic1 network
     * must differ between cells, the values must not all be saturated, and hidden must have positive and zero units.
     */
    @Test
    void theTestDataIsNotDegenerate() {
        for (Fixture f : fixtures()) {
            float[] values = f.trainer.download(f.trainer.buffer("values"));
            int unsaturated = 0;
            for (float v : values) {
                if (Math.abs(v) < 0.9f) {
                    unsaturated++;
                }
            }
            if (!f.name.contains("init")) {
                assertTrue(10 * unsaturated >= values.length, f.name + ": most values are saturated");
            }
            float[] hidden = f.trainer.download(f.trainer.buffer("hidden"));
            int positive = 0;
            for (float v : hidden) {
                if (v > 0) {
                    positive++;
                }
            }
            if (!f.name.contains("init")) {
                assertTrue(positive > 0 && positive < hidden.length, f.name + ": hidden is all or nothing: " + positive);
            }
            float[] policy = f.trainer.download(f.trainer.buffer("policy"));
            float lo = Float.POSITIVE_INFINITY;
            float hi = Float.NEGATIVE_INFINITY;
            for (float v : policy) {
                lo = Math.min(lo, v);
                hi = Math.max(hi, v);
            }
            assertTrue(hi - lo > 0.05f, f.name + ": policy logits barely differ: " + lo + " to " + hi);
        }
    }

    // ------------------------------------------------------------------ B4: loss kernels

    /**
     * The loss kernels on the logits and values that the device itself produced, against {@link ReferenceLoss} in
     * double: per-sample losses, dPolicy and dy. This isolates the float softmax from the error of the forward pass
     * (a logit that moves by its tolerance moves the softmax of a flat policy by more than the gradient bound allows
     * in extreme cases). It also checks the batch scaling: weights over N, c_v.
     */
    @Test
    void lossKernelsMatchTheReferenceOnTheirOwnInputs() {
        for (Fixture f : fixtures()) {
            checkLossOnOwnInputs(f);
        }
    }

    private static void checkLossOnOwnInputs(Fixture f) {
        int n = f.batch.size();
        int p = f.config.players();
        Worst lp = new Worst(f.name + " Lp");
        Worst lv = new Worst(f.name + " Lv");
        Worst dp = new Worst(f.name + " dPolicy");
        Worst dy = new Worst(f.name + " dy");
        float[] policy = f.trainer.download(f.trainer.buffer("policy"));
        float[] values = f.trainer.download(f.trainer.buffer("values"));
        float[] losses = f.trainer.download(f.trainer.buffer("losses"));
        float[] gpuDp = f.trainer.download(f.trainer.buffer("dPolicy"));
        float[] gpuDy = f.trainer.download(f.trainer.buffer("dy"));
        double[] refDp = new double[n * Encoder.POLICY_SIZE];
        double[] refDy = new double[n * p];
        double[] refLp = new double[n];
        double[] refLv = new double[n];
        for (int s = 0; s < n; s++) {
            float wp = f.batch.policyWeight()[s];
            float wv = f.batch.valueWeight()[s];
            if (wp > 0) {
                double[] row = new double[Encoder.POLICY_SIZE];
                refLp[s] = ReferenceLoss.policy(policy, s * Encoder.POLICY_SIZE, f.batch.moveFrom(), f.batch.moveTo(),
                        f.batch.moveTarget(), f.batch.moveOffsets()[s], f.batch.moveOffsets()[s + 1], (double) wp / n,
                        row, 0);
                System.arraycopy(row, 0, refDp, s * Encoder.POLICY_SIZE, row.length);
            }
            if (wv > 0) {
                double[] row = new double[p];
                refLv[s] = ReferenceLoss.value(values, s * p, f.batch.valueTarget(), s * p, p, f.cv * wv / n, row, 0);
                System.arraycopy(row, 0, refDy, s * p, p);
            }
        }
        float[] refLpF = new float[n];
        float[] refLvF = new float[n];
        float[] gpuLp = new float[n];
        float[] gpuLv = new float[n];
        for (int s = 0; s < n; s++) {
            refLpF[s] = (float) refLp[s];
            refLvF[s] = (float) refLv[s];
            gpuLp[s] = losses[2 * s];
            gpuLv[s] = losses[2 * s + 1];
        }
        TrainTesting.forwardAgrees(lp, "per-sample Lp", refLpF, 0, gpuLp, 0, n);
        TrainTesting.forwardAgrees(lv, "per-sample Lv", refLvF, 0, gpuLv, 0, n);
        TrainTesting.gradientAgrees(dp, "dPolicy", refDp, 0, gpuDp, 0, refDp.length, TrainTesting.maxAbs(refDp));
        TrainTesting.gradientAgrees(dy, "dy", refDy, 0, gpuDy, 0, refDy.length, TrainTesting.maxAbs(refDy));
        System.out.println("TornadoTrainer loss kernels on their own inputs, largest error / bound: " + lp + ", " + lv
                + ", " + dp + ", " + dy);
    }

    /**
     * The batch result (loss, policy term, value term, KL) against the reference trainer's, end to end. A missing
     * 1/N, a missing weight, c_v ignored, or a KL taken for samples without policy weight fails here.
     */
    @Test
    void theBatchLossMatchesTheReferenceTrainer() {
        for (Fixture f : fixtures()) {
            double[] gpu = {f.gpuResult.loss(), f.gpuResult.policyLoss(), f.gpuResult.valueLoss(), f.gpuResult.kl()};
            double[] ref = {f.refResult.loss(), f.refResult.policyLoss(), f.refResult.valueLoss(), f.refResult.kl()};
            String[] names = {"loss", "policyLoss", "valueLoss", "kl"};
            double worst = 0;
            for (int i = 0; i < 4; i++) {
                double bound = 1e-4 + 1e-3 * Math.abs(ref[i]);
                double error = Math.abs(gpu[i] - ref[i]);
                if (!(error <= bound)) {
                    fail(f.name + ", " + names[i] + ": expected " + ref[i] + ", got " + gpu[i]);
                }
                worst = Math.max(worst, error / bound);
            }
            assertTrue(f.refResult.policyLoss() > 0 && f.refResult.valueLoss() > 0, f.name + ": a loss term is 0");
            System.out.printf("TornadoTrainer batch loss %s: %.4f, largest error / bound %.4f%n", f.name,
                    f.refResult.loss(), worst);
        }
    }

    // ------------------------------------------------------------------ B5: head backward

    /**
     * dz1, dpooled and the gradient of the trunk output (dx_B, with the policy and the pooling part), per sample,
     * against the reference backward pass; row 121 of dx_B is exactly 0.
     */
    @Test
    void headInputGradientsMatchTheReference() {
        for (Fixture f : fixtures()) {
            int c = f.config.channels();
            int h = f.config.valueHidden();
            int n = f.batch.size();
            Worst dz1 = new Worst(f.name + " dz1");
            Worst dPooled = new Worst(f.name + " dpooled");
            Worst dx = new Worst(f.name + " dx_B");
            float[] gpuDz1 = f.trainer.download(f.trainer.buffer("dz1"));
            float[] gpuDPooled = f.trainer.download(f.trainer.buffer("dPooled"));
            float[] gpuDx = f.trainer.download(f.trainer.trunkGradient());
            double[] refDz1 = new double[n * h];
            double[] refDPooled = new double[n * c];
            double[] refDx = new double[n * HexGrid.ROWS * c];
            for (int s = 0; s < n; s++) {
                System.arraycopy(f.samples[s].dz1, 0, refDz1, s * h, h);
                System.arraycopy(f.samples[s].dPooled, 0, refDPooled, s * c, c);
                System.arraycopy(f.samples[s].dxTop, 0, refDx, s * HexGrid.ROWS * c, HexGrid.ROWS * c);
            }
            TrainTesting.gradientAgrees(dz1, "dz1", refDz1, 0, gpuDz1, 0, refDz1.length, TrainTesting.maxAbs(refDz1));
            TrainTesting.gradientAgrees(dPooled, "dpooled", refDPooled, 0, gpuDPooled, 0, refDPooled.length,
                    TrainTesting.maxAbs(refDPooled));
            TrainTesting.gradientAgrees(dx, "dx_B", refDx, 0, gpuDx, 0, refDx.length, TrainTesting.maxAbs(refDx));
            for (int s = 0; s < n; s++) {
                for (int ch = 0; ch < c; ch++) {
                    assertEquals(0f, gpuDx[(s * HexGrid.ROWS + HexGrid.PADDING) * c + ch],
                            f.name + ": padding row of dx_B, sample " + s);
                }
            }
            System.out.println("TornadoTrainer head input gradients, largest error / bound: " + dz1 + ", " + dPooled
                    + ", " + dx);
        }
    }

    /**
     * The weight and bias gradients of the heads and the gradients of {@code prior.beta} and {@code prior.k} against
     * the gradients of the reference trainer (the same tensors, summed over the batch in double).
     */
    @Test
    void headParameterGradientsMatchTheReferenceTrainer() {
        for (Fixture f : fixtures()) {
            List<String> names = new ArrayList<>(List.of("head_policy.weight", "head_policy.bias",
                    "head_value.dense1.weight", "head_value.dense1.bias", "head_value.dense2.weight",
                    "head_value.dense2.bias"));
            if (f.config.planes().residualPriors()) {
                names.add(ResidualPriors.BETA);
                names.add(ResidualPriors.K);
            }
            StringBuilder report = new StringBuilder();
            for (String name : names) {
                double[] ref = f.reference.gradients().get(name);
                float[] gpu = f.trainer.gradient(name);
                assertEquals(ref.length, gpu.length, name);
                Worst worst = new Worst(name);
                double scale = TrainTesting.maxAbs(ref);
                if (name.equals("head_policy.bias")) {
                    // The exact gradient is 0 (the softmax over f + t does not change if f or t gains a constant), so
                    // the reference holds rounding noise of 1e-9 and the bound of the tensor scale would be 1e-13.
                    // The float sum of 121 N terms has an error of a few ulps of the terms: scale it by their sum.
                    scale = 1e-2 * termSum(f);
                } else if (name.equals(ResidualPriors.K) && f.config.players() == 3) {
                    // The linear evaluation is defined for two players: the plane is 0 and so is the gradient.
                    assertEquals(0.0, scale, f.name);
                } else if (name.startsWith("head_value.dense1") && f.config.planes().residualPriors()
                        && f.name.endsWith("init")) {
                    // Parameters.init starts dense2 at zero for classic1, so nothing reaches dense1: both must be 0.
                    assertEquals(0.0, scale, f.name);
                } else {
                    assertTrue(scale > 0, f.name + ": the reference gradient of " + name + " is 0");
                }
                TrainTesting.gradientAgrees(worst, f.name, ref, 0, gpu, 0, ref.length, scale);
                report.append(worst).append("; ");
            }
            System.out.println("TornadoTrainer head gradients " + f.name + ", largest error / bound: " + report);
        }
    }

    /** The sum of the absolute values of all terms of the policy head gradient of a fixture, from the reference. */
    private static double termSum(Fixture f) {
        double sum = 0;
        for (Sample s : f.samples) {
            for (double d : s.dPolicy) {
                sum += Math.abs(d);
            }
        }
        return sum;
    }

    /** Names of the weight and bias tensors of all convolutions of a config, in network order. */
    static List<String> convolutionTensors(NetworkConfig config) {
        List<String> names = new ArrayList<>(List.of("conv_in.weight", "conv_in.bias"));
        for (int i = 0; i < config.blocks(); i++) {
            for (String conv : List.of("conv1", "conv2")) {
                names.add("block" + i + "." + conv + ".weight");
                names.add("block" + i + "." + conv + ".bias");
            }
        }
        return names;
    }

    /**
     * The weight and bias gradients of every convolution (B6, B7: input gradient by the gather form with the permuted
     * weight, residual path, masks, split-K weight gradient) against the gradients of the reference trainer, summed
     * over the batch in double.
     */
    @Test
    void convolutionGradientsMatchTheReferenceTrainer() {
        for (Fixture f : fixtures()) {
            StringBuilder report = new StringBuilder();
            double worstOverall = 0;
            for (String name : convolutionTensors(f.config)) {
                double[] ref = f.reference.gradients().get(name);
                float[] gpu = f.trainer.gradient(name);
                assertEquals(ref.length, gpu.length, name);
                double scale = TrainTesting.maxAbs(ref);
                if (f.name.equals("small2-classic1-init")) {
                    // Parameters.init starts both heads of a classic1 network at zero: nothing reaches the trunk.
                    assertEquals(0.0, scale, f.name + " " + name);
                } else {
                    assertTrue(scale > 0, f.name + ": the reference gradient of " + name + " is 0");
                }
                Worst worst = new Worst(name);
                TrainTesting.gradientAgrees(worst, f.name, ref, 0, gpu, 0, ref.length, scale);
                worstOverall = Math.max(worstOverall, worst.fraction);
                if (worst.fraction > 0.5) {
                    report.append(worst).append("; ");
                }
            }
            System.out.println("TornadoTrainer convolution gradients " + f.name + ", largest error / bound "
                    + String.format("%.4f", worstOverall) + (report.length() > 0 ? " (over 0.5: " + report + ")" : ""));
        }
    }

    // ------------------------------------------------------------------ edge cases

    /**
     * Single-move samples (loss 0, gradient exactly 0), policy weight 0, value weight 0, a sample without moves, and
     * moves on the extreme cells that share their from and to cells (every cell accumulates). All against the
     * reference, and the structural zeros exactly.
     */
    @Test
    void edgeSamples() {
        for (boolean classic : new boolean[] {false, true}) {
            PlaneSet planes = classic ? PlaneSet.CLASSIC1 : PlaneSet.PLAIN;
            NetworkConfig config = NetworkConfig.tiny(2).withPlanes(planes);
            Parameters p = Parameters.uniform(config, 41, 2 * TrainTesting.initBound(config));
            TrainingBatch batch = TrainTesting.edgeBatch(TrainTesting.batch(2, planes, 9, 12));
            try (TornadoTrainer trainer = new TornadoTrainer(p, batch.size(), 0.9, TornadoSupport.defaultDevice())) {
                ReferenceTrainer.Result gpu = trainer.run(batch);
                ReferenceTrainer reference = new ReferenceTrainer(p, 0.9);
                ReferenceTrainer.Result ref = reference.accumulate(batch, batch.size(), 1);
                assertEquals(ref.loss(), gpu.loss(), 1e-4 + 1e-3 * ref.loss());
                assertEquals(ref.kl(), gpu.kl(), 1e-4 + 1e-3 * Math.abs(ref.kl()));

                float[] dPolicy = trainer.download(trainer.buffer("dPolicy"));
                float[] dy = trainer.download(trainer.buffer("dy"));
                float[] losses = trainer.download(trainer.buffer("losses"));
                int players = 2;
                for (int i = 0; i < Encoder.POLICY_SIZE; i++) {
                    assertEquals(0f, dPolicy[i], "single move: dPolicy of sample 0");
                    assertEquals(0f, dPolicy[Encoder.POLICY_SIZE + i], "wp = 0: dPolicy of sample 1");
                    assertEquals(0f, dPolicy[3 * Encoder.POLICY_SIZE + i], "no moves: dPolicy of sample 3");
                }
                assertEquals(0f, losses[0], "single move: Lp");
                assertEquals(0f, losses[2], "wp = 0: Lp");
                assertEquals(0f, losses[6], "no moves: Lp");
                for (int i = 0; i < players; i++) {
                    assertEquals(0f, dy[2 * players + i], "wv = 0: dy of sample 2");
                }
                assertEquals(0f, losses[5], "wv = 0: Lv");
                // The extreme-cell sample: moves 0 -> 120, 120 -> 0, 0 -> 0, 120 -> 120, 60 -> 61 share cells.
                double[] refDp = new double[Encoder.POLICY_SIZE];
                TrainTesting.Sample sample = TrainTesting.reference(p, batch, 4, 0.9);
                System.arraycopy(sample.dPolicy, 0, refDp, 0, refDp.length);
                Worst worst = new Worst("edge sample 4 dPolicy");
                TrainTesting.gradientAgrees(worst, "sample 4", refDp, 0, dPolicy, 4 * Encoder.POLICY_SIZE,
                        Encoder.POLICY_SIZE, TrainTesting.maxAbs(refDp));
                assertTrue(TrainTesting.maxAbs(refDp) > 1e-3, "sample 4 has a visible gradient");
                for (String name : List.of("head_policy.weight", "head_value.dense1.weight",
                        "head_value.dense2.weight")) {
                    double[] r = reference.gradients().get(name);
                    TrainTesting.gradientAgrees(new Worst(name), planes.id(), r, 0, trainer.gradient(name), 0,
                            r.length, TrainTesting.maxAbs(r));
                }
                System.out.println("TornadoTrainer edge samples " + planes + ", largest error / bound: " + worst);
            }
        }
    }

    /**
     * Extreme logits: the policy head weights scaled by 500 give logits that differ by hundreds, so that most moves
     * have a probability that underflows to 0 in float. The softmax must stay finite and equal the double reference
     * on the device's own logits.
     */
    @Test
    void extremeLogits() {
        NetworkConfig config = NetworkConfig.tiny(2).withPlanes(PlaneSet.CLASSIC1);
        Parameters p = Parameters.uniform(config, 43, 2 * TrainTesting.initBound(config));
        float[] w = p.get("head_policy.weight").data();
        for (int i = 0; i < w.length; i++) {
            w[i] *= 500f;
        }
        TrainingBatch batch = TrainTesting.batch(2, PlaneSet.CLASSIC1, 16, 13);
        Fixture f = new Fixture("extreme", config, p, batch, 1.0);
        try {
            float[] policy = f.trainer.download(f.trainer.buffer("policy"));
            float lo = Float.POSITIVE_INFINITY;
            float hi = Float.NEGATIVE_INFINITY;
            for (float v : policy) {
                lo = Math.min(lo, v);
                hi = Math.max(hi, v);
            }
            assertTrue(hi - lo > 200f, "the logits are not extreme: " + lo + " to " + hi);
            checkLossOnOwnInputs(f);
        } finally {
            f.trainer.close();
        }
    }

    /**
     * The local barriers of the training value head hold. Without them, shapes whose writers and readers of a local
     * array lie in different SIMD groups give wrong results only sometimes (docs/tornado.md, "Findings and pitfalls" of the
     * heads): C = 1 with H = 1024, C = 2 with H = 512, C = 8 with H = 1024 and C = H = 1024, a batch of 256 and four runs
     * on one trainer. Every run must agree with the reference; the earlier tests use C and H below one SIMD group.
     */
    @Test
    void theValueHeadBarriersHoldUnderLoad() {
        int[][] shapes = {{1, 1024}, {2, 512}, {8, 1024}, {1024, 1024}};
        for (int[] shape : shapes) {
            NetworkConfig config = new NetworkConfig(shape[0], 0, 2, shape[1], PlaneSet.CLASSIC1);
            Parameters p = TrainTesting.classicPrior(Parameters.uniform(config, 51, TrainTesting.initBound(config)));
            TrainingBatch batch = TrainTesting.batch(2, PlaneSet.CLASSIC1, 256, 24);
            Sample[] reference = new Sample[batch.size()];
            for (int s = 0; s < reference.length; s++) {
                reference[s] = TrainTesting.reference(p, batch, s, 1.0);
            }
            try (TornadoTrainer trainer = new TornadoTrainer(p, batch.size(), 1.0, TornadoSupport.defaultDevice())) {
                for (int run = 0; run < 4; run++) {
                    trainer.run(batch);
                    float[] pooled = trainer.download(trainer.buffer("pooled"));
                    float[] hidden = trainer.download(trainer.buffer("hidden"));
                    float[] values = trainer.download(trainer.buffer("values"));
                    Worst worst = new Worst("C=" + shape[0] + " H=" + shape[1] + " run " + run);
                    for (int s = 0; s < reference.length; s++) {
                        TrainTesting.forwardAgrees(worst, "pooled " + s, reference[s].pooled, 0, pooled, s * shape[0],
                                shape[0]);
                        TrainTesting.forwardAgrees(worst, "hidden " + s, reference[s].hidden, 0, hidden, s * shape[1],
                                shape[1]);
                        TrainTesting.forwardAgrees(worst, "values " + s, reference[s].values, 0, values, s * 2, 2);
                    }
                    if (run == 3) {
                        System.out.println("TornadoTrainer value head barriers, largest error / bound: " + worst);
                    }
                }
            }
        }
    }

    /** NaN in a weight stops the run with the reference's exception; without sample weights nothing is read. */
    @Test
    void nanInTheParameters() {
        NetworkConfig config = NetworkConfig.tiny(2);
        Parameters p = Parameters.uniform(config, 44, TrainTesting.initBound(config));
        TrainingBatch batch = TrainTesting.batch(2, PlaneSet.PLAIN, 8, 14);
        p.get("conv_in.weight").data()[3] = Float.NaN;
        try (TornadoTrainer trainer = new TornadoTrainer(p, batch.size(), 1.0, TornadoSupport.defaultDevice())) {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> trainer.run(batch));
            assertTrue(e.getMessage().contains("is NaN"), e.getMessage());
            // The same network on a batch where no sample has a weight: the NaN logits and values are never used.
            float[] zero = new float[batch.size()];
            TrainingBatch unweighted = new TrainingBatch(batch.size(), batch.players(), batch.input(),
                    batch.moveOffsets(), batch.moveFrom(), batch.moveTo(), batch.moveTarget(), batch.valueTarget(),
                    zero, zero, batch.targetEntropy());
            ReferenceTrainer.Result r = trainer.run(unweighted);
            assertEquals(0.0, r.loss());
            assertEquals(0.0, r.kl());
            for (float v : trainer.download(trainer.buffer("dPolicy"))) {
                assertEquals(0f, v);
            }
            for (float v : trainer.gradient("head_value.dense2.bias")) {
                assertEquals(0f, v);
            }
        }
    }

    /** Two runs from the same state give bit-identical activations, losses, gradients and result. */
    @Test
    void repeatedRunsAreBitIdentical() {
        for (Fixture f : List.of(fixtures().get(2), fixtures().get(4))) {
            List<float[]> first = snapshot(f);
            ReferenceTrainer.Result again = f.trainer.run(f.batch);
            List<float[]> second = snapshot(f);
            assertEquals(f.gpuResult, again, f.name + ": result");
            for (int i = 0; i < first.size(); i++) {
                OpsTesting.assertSameBits(f.name + " array " + i, first.get(i), second.get(i));
            }
            // A new trainer on the same parameters, too (a different allocation, the same bits).
            try (TornadoTrainer other = new TornadoTrainer(f.parameters, f.batch.size(), f.batch.moveCount() + 7, f.cv,
                    TornadoSupport.defaultDevice())) {
                assertEquals(f.gpuResult, other.run(f.batch), f.name + ": result of a new trainer");
                OpsTesting.assertSameBits(f.name + " dx_B of a new trainer", first.get(first.size() - 1),
                        other.download(other.trunkGradient()));
                for (String name : List.of("head_policy.weight", "head_value.dense1.weight")) {
                    OpsTesting.assertSameBits(f.name + " " + name, f.trainer.gradient(name), other.gradient(name));
                }
            }
        }
    }

    private static List<float[]> snapshot(Fixture f) {
        List<float[]> all = new ArrayList<>();
        for (int slot = 0; slot < f.trainer.slots(); slot++) {
            all.add(f.trainer.download(f.trainer.activation(slot)));
        }
        for (String name : List.of("policy", "pooled", "hidden", "values", "dPolicy", "dy", "dz1", "dPooled",
                "losses")) {
            all.add(f.trainer.download(f.trainer.buffer(name)));
        }
        for (String name : List.of("head_policy.weight", "head_policy.bias", "head_value.dense1.weight",
                "head_value.dense2.weight", "prior.beta", "prior.k")) {
            all.add(f.trainer.gradient(name));
        }
        all.add(f.trainer.download(f.trainer.trunkGradient()));
        return all;
    }

    /** {@code reload()} sends changed host parameters to the device; results then equal the reference for them. */
    @Test
    void reloadUsesTheChangedParameters() {
        NetworkConfig config = NetworkConfig.tiny(2).withPlanes(PlaneSet.CLASSIC1);
        Parameters p = Parameters.uniform(config, 45, TrainTesting.initBound(config));
        TrainingBatch batch = TrainTesting.batch(2, PlaneSet.CLASSIC1, 12, 15);
        try (TornadoTrainer trainer = new TornadoTrainer(p, batch.size(), 1.0, TornadoSupport.defaultDevice())) {
            ReferenceTrainer.Result before = trainer.run(batch);
            float[] w = p.get("head_value.dense1.weight").data();
            for (int i = 0; i < w.length; i++) {
                w[i] *= -1.5f;
            }
            p.get(ResidualPriors.K).data()[0] = -0.7f;
            ReferenceTrainer.Result stale = trainer.run(batch);
            assertEquals(before, stale, "no reload, no change");
            trainer.reload();
            ReferenceTrainer.Result gpu = trainer.run(batch);
            ReferenceTrainer.Result ref = new ReferenceTrainer(p, 1.0).accumulate(batch, batch.size(), 1);
            assertTrue(Math.abs(gpu.valueLoss() - before.valueLoss()) > 1e-3, "the change must matter");
            assertEquals(ref.valueLoss(), gpu.valueLoss(), 1e-4 + 1e-3 * ref.valueLoss());
            assertEquals(ref.policyLoss(), gpu.policyLoss(), 1e-4 + 1e-3 * ref.policyLoss());
        }
    }

    /** The trainer refuses a batch that does not fit, and a closed trainer refuses everything. */
    @Test
    void misuseFailsLoudly() {
        NetworkConfig config = NetworkConfig.tiny(2).withPlanes(PlaneSet.CLASSIC1);
        Parameters p = Parameters.uniform(config, 46, TrainTesting.initBound(config));
        TrainingBatch batch = TrainTesting.batch(2, PlaneSet.CLASSIC1, 8, 16);
        TornadoTrainer trainer = new TornadoTrainer(p, 8, batch.moveCount(), 1.0, TornadoSupport.defaultDevice());
        assertThrows(IllegalArgumentException.class, () -> trainer.run(TrainTesting.batch(2, PlaneSet.CLASSIC1, 7, 16)));
        assertThrows(IllegalArgumentException.class, () -> trainer.run(TrainTesting.batch(2, PlaneSet.PLAIN, 8, 16)));
        assertThrows(IllegalArgumentException.class, () -> trainer.run(TrainTesting.batch(3, PlaneSet.CLASSIC1, 8, 16)));
        TornadoTrainer small = new TornadoTrainer(p, 8, batch.moveCount() - 1, 1.0, TornadoSupport.defaultDevice());
        assertThrows(IllegalArgumentException.class, () -> small.run(batch));
        assertThrows(IllegalStateException.class, () -> trainer.gradient("head_policy.bias"));
        trainer.run(batch);
        trainer.close();
        trainer.close();
        small.close();
        assertThrows(IllegalStateException.class, () -> trainer.run(batch));
        assertThrows(IllegalStateException.class, trainer::reload);
        assertThrows(IllegalArgumentException.class,
                () -> new TornadoTrainer(p, 0, 1.0, TornadoSupport.defaultDevice()));
        assertThrows(IllegalArgumentException.class,
                () -> new TornadoTrainer(p, 4, Double.NaN, TornadoSupport.defaultDevice()));
    }

    /** The batch is the only thing uploaded per run: a second batch on one trainer gives the second batch's result. */
    @Test
    void consecutiveBatchesDoNotLeak() {
        NetworkConfig config = NetworkConfig.tiny(3);
        Parameters p = Parameters.uniform(config, 47, TrainTesting.initBound(config));
        TrainingBatch a = TrainTesting.batch(3, PlaneSet.PLAIN, 10, 17);
        TrainingBatch b = TrainTesting.batch(3, PlaneSet.PLAIN, 10, 18);
        int moves = Math.max(a.moveCount(), b.moveCount());
        try (TornadoTrainer trainer = new TornadoTrainer(p, 10, moves, 1.0, TornadoSupport.defaultDevice())) {
            ReferenceTrainer.Result first = trainer.run(a);
            ReferenceTrainer.Result second = trainer.run(b);
            ReferenceTrainer.Result ref = new ReferenceTrainer(p, 1.0).accumulate(b, 10, 1);
            assertEquals(ref.loss(), second.loss(), 1e-4 + 1e-3 * ref.loss());
            assertEquals(first, trainer.run(a), "back to the first batch");
        }
    }
}
