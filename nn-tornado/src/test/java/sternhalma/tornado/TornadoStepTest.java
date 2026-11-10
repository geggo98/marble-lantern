package sternhalma.tornado;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sternhalma.nn.Checkpoint;
import sternhalma.nn.Encoder;
import sternhalma.nn.LearningRateSchedule;
import sternhalma.nn.NetworkConfig;
import sternhalma.nn.Optimizer;
import sternhalma.nn.Parameters;
import sternhalma.nn.PlaneSet;
import sternhalma.nn.ReferenceTrainer;
import sternhalma.nn.Tensor;
import sternhalma.nn.TrainingBatch;
import sternhalma.nn.TrainingRun;

/**
 * Whole optimiser steps of {@link TornadoTrainer} (block B8 of docs/training.md, Part B) against
 * {@link ReferenceTrainer} and {@link Optimizer}: one step from the same state, steps that follow each other, 20
 * steps on the same batches, gradient accumulation over microbatches, the learning rate and bias corrections of
 * every step, bit-identical runs, resuming from a checkpoint, and the finite check.
 */
class TornadoStepTest {

    private static final Optimizer.Settings ADAMW = Optimizer.Settings.adamw();

    @BeforeEach
    void requireTornado() {
        TornadoAssumptions.assumeTornado();
    }

    /** A network, its start parameters and the number of samples per batch. */
    private record Case(String name, NetworkConfig config, Parameters start, PlaneSet planes, int samples,
            double cv) {

        TrainingBatch batch(long seed) {
            return TrainTesting.batch(config.players(), planes, samples, seed);
        }
    }

    private static List<Case> cases() {
        NetworkConfig tiny2 = NetworkConfig.tiny(2);
        NetworkConfig classic2 = NetworkConfig.tiny(2).withPlanes(PlaneSet.CLASSIC1);
        NetworkConfig classic3 = NetworkConfig.tiny(3).withPlanes(PlaneSet.CLASSIC1);
        NetworkConfig small = NetworkConfig.small(2).withPlanes(PlaneSet.CLASSIC1);
        NetworkConfig small3 = NetworkConfig.small(3);
        return List.of(
                new Case("tiny2-plain", tiny2, Parameters.uniform(tiny2, 101, TrainTesting.initBound(tiny2)),
                        PlaneSet.PLAIN, 32, 1.0),
                new Case("tiny2-classic1", classic2,
                        TrainTesting.classicPrior(Parameters.uniform(classic2, 102, TrainTesting.initBound(classic2))),
                        PlaneSet.CLASSIC1, 40, 0.75),
                new Case("tiny3-classic1", classic3,
                        TrainTesting.classicPrior(Parameters.uniform(classic3, 103, TrainTesting.initBound(classic3))),
                        PlaneSet.CLASSIC1, 24, 1.0),
                new Case("small2-classic1", small,
                        TrainTesting.classicPrior(Parameters.uniform(small, 104, TrainTesting.initBound(small))),
                        PlaneSet.CLASSIC1, 32, 1.0),
                new Case("small3-plain", small3, Parameters.uniform(small3, 105, TrainTesting.initBound(small3)),
                        PlaneSet.PLAIN, 16, 1.0));
    }

    /** The CPU side: parameters, optimiser and trainer. */
    private static final class Cpu {
        final Parameters parameters;
        final Optimizer optimizer;
        final ReferenceTrainer trainer;

        Cpu(Parameters start, double cv) {
            parameters = TrainTesting.copyOf(start);
            optimizer = new Optimizer(parameters, ADAMW);
            trainer = new ReferenceTrainer(parameters, cv);
        }

        ReferenceTrainer.Result step(TrainingBatch batch, double lr) {
            trainer.zeroGradients();
            ReferenceTrainer.Result r = trainer.accumulate(batch, batch.size(), 1);
            optimizer.step(trainer.gradients(), lr);
            return r;
        }
    }

    /**
     * The parameters after a step against the reference after the same step from the same state: no element differs
     * by more than {@code 2 lr} (a gradient near zero may flip the sign of its Adam step), and the elements whose
     * reference gradient is at least 1e-3 of the largest of the tensor differ by at most {@code 0.01 lr} (plus 1e-6
     * relative).
     * Returns the largest difference of those elements as a fraction of {@code 0.01 lr}.
     */
    private static double parametersAgree(String what, Parameters ref, Parameters gpu,
            ReferenceTrainer.Gradients gradients, double lr) {
        double worst = 0;
        for (Map.Entry<String, Tensor> e : ref.tensors().entrySet()) {
            String name = e.getKey();
            float[] r = e.getValue().data();
            float[] g = gpu.get(name).data();
            double[] gradient = gradients.get(name);
            // The policy bias has the exact gradient 0 (a constant added to every logit changes nothing); the
            // reference holds rounding noise, and an Adam step follows the sign of noise: only the 2 lr bound applies.
            double big = name.equals("head_policy.bias") ? Double.POSITIVE_INFINITY
                    : 1e-3 * TrainTesting.maxAbs(gradient);
            for (int i = 0; i < r.length; i++) {
                double diff = Math.abs((double) g[i] - r[i]);
                if (!(diff <= 2 * lr + 1e-6 * Math.abs(r[i]))) {
                    fail(what + ": " + name + "[" + i + "] reference " + r[i] + ", device " + g[i] + ", lr " + lr);
                }
                if (Math.abs(gradient[i]) >= big && big > 0) {
                    double bound = 0.01 * lr + 1e-6 * Math.abs(r[i]);
                    if (!(diff <= bound)) {
                        fail(what + ": " + name + "[" + i + "] has gradient " + gradient[i] + ", reference " + r[i]
                                + ", device " + g[i] + ", bound " + bound);
                    }
                    worst = Math.max(worst, diff / (0.01 * lr));
                }
            }
        }
        return worst;
    }

    /** State arrays against the reference: {@code 1e-6 |ref| + 1e-9 + 2e-7 max |ref|}, as the optimiser test. */
    private static void stateAgrees(String what, Map<String, float[]> ref, Map<String, float[]> gpu) {
        for (Map.Entry<String, float[]> e : ref.entrySet()) {
            if (e.getKey().equals("head_policy.bias")) {
                continue; // exact gradient 0: both sides hold rounding noise
            }
            float[] r = e.getValue();
            float[] g = gpu.get(e.getKey());
            double max = 0;
            for (float v : r) {
                max = Math.max(max, Math.abs(v));
            }
            for (int i = 0; i < r.length; i++) {
                double bound = 1e-6 * Math.abs(r[i]) + 1e-9 + 2e-7 * max;
                // The state comes from the device's own gradient, which differs from the reference by up to 1e-3.
                bound += 2e-3 * Math.abs(r[i]) + 1e-4 * max;
                if (!(Math.abs((double) g[i] - r[i]) <= bound)) {
                    fail(what + ": " + e.getKey() + "[" + i + "] reference " + r[i] + ", device " + g[i]);
                }
            }
        }
    }

    /**
     * One step from the same state, over and over: before every step the device trainer is built from the CPU's
     * parameters and optimiser state (a state with m, v and a step count above 0 from the second step on), the
     * learning rate doubles every step, and both take the same batch. The device parameters agree with the CPU
     * ones, the state agrees, the step count is the CPU's, the loss terms agree.
     */
    @Test
    void oneStepFromTheSameStateAfterOneStep() {
        for (Case c : cases()) {
            Cpu cpu = new Cpu(c.start(), c.cv());
            double lr = 1e-3;
            StringBuilder report = new StringBuilder();
            for (int k = 0; k < 4; k++) {
                TrainingBatch batch = c.batch(300 + k);
                Parameters hostParameters = TrainTesting.copyOf(cpu.parameters);
                Optimizer hostOptimizer = TrainTesting.copyOf(cpu.optimizer, hostParameters);
                ReferenceTrainer.Result gpuResult;
                try (TornadoTrainer trainer = new TornadoTrainer(hostParameters, hostOptimizer, batch.size(),
                        batch.size(), batch.moveCount() + 3, c.cv(), TornadoSupport.defaultDevice())) {
                    gpuResult = trainer.step(batch, lr);
                    assertEquals(cpu.optimizer.steps() + 1, trainer.steps(), "steps() counts from the optimiser");
                    trainer.sync();
                }
                ReferenceTrainer.Result refResult = cpu.step(batch, lr);
                assertEquals(cpu.optimizer.steps(), hostOptimizer.steps(), c.name() + ": step count");
                assertEquals(refResult.loss(), gpuResult.loss(), 1e-4 + 1e-3 * refResult.loss(), c.name() + ": loss");
                assertEquals(refResult.valueLoss(), gpuResult.valueLoss(), 1e-4 + 1e-3 * refResult.valueLoss());
                assertEquals(refResult.kl(), gpuResult.kl(), 1e-4 + 1e-3 * Math.abs(refResult.kl()));
                double worst = parametersAgree(c.name() + " step " + (k + 1), cpu.parameters, hostParameters,
                        cpu.trainer.gradients(), lr);
                stateAgrees(c.name() + " m", cpu.optimizer.m(), hostOptimizer.m());
                stateAgrees(c.name() + " v", cpu.optimizer.v(), hostOptimizer.v());
                report.append(String.format("%.3f ", worst));
                lr *= 2;
            }
            System.out.println("TornadoTrainer one step from the same state " + c.name()
                    + ", largest difference / (0.01 lr) over steps 1 to 4: " + report);
        }
    }

    /**
     * 20 steps on the same four batches, free-running: the losses of the two trainers agree within 1 % at every step
     * (docs/training.md, "Verification against Part A"), and the loss goes down.
     */
    @Test
    void twentyStepsOnTheSameBatchesGiveTheSameLosses() {
        for (Case c : cases()) {
            Cpu cpu = new Cpu(c.start(), c.cv());
            Parameters hostParameters = TrainTesting.copyOf(c.start());
            Optimizer hostOptimizer = new Optimizer(hostParameters, ADAMW);
            double worst = 0;
            double first = 0;
            double lastLoss = 0;
            try (TornadoTrainer trainer = new TornadoTrainer(hostParameters, hostOptimizer, c.samples(), c.samples(),
                    c.samples() * 256, c.cv(), TornadoSupport.defaultDevice())) {
                for (int k = 0; k < 20; k++) {
                    TrainingBatch batch = c.batch(400 + k % 4);
                    double lr = 2e-3;
                    ReferenceTrainer.Result gpu = trainer.step(batch, lr);
                    ReferenceTrainer.Result ref = cpu.step(batch, lr);
                    double error = Math.abs(gpu.loss() - ref.loss()) / (0.01 * ref.loss());
                    assertTrue(error <= 1, c.name() + " step " + k + ": loss " + gpu.loss() + " against " + ref.loss());
                    worst = Math.max(worst, error);
                    if (k == 0) {
                        first = ref.loss();
                    }
                    lastLoss = ref.loss();
                }
                trainer.sync();
            }
            assertEquals(20, hostOptimizer.steps());
            assertTrue(lastLoss < first, c.name() + ": the loss did not go down, " + first + " to " + lastLoss);
            System.out.printf("TornadoTrainer 20 steps %s: loss %.4f to %.4f, largest loss difference / 1 %% = %.3f%n",
                    c.name(), first, lastLoss, worst);
        }
    }

    /**
     * Gradient accumulation: one step of a batch of 64 samples as 1, 2 and 4 microbatches gives the CPU's parameters
     * and loss, and the sums agree with each other.
     */
    @Test
    void microbatchesAddUpToTheWholeBatch() {
        NetworkConfig config = NetworkConfig.tiny(2).withPlanes(PlaneSet.CLASSIC1);
        Parameters start = TrainTesting.classicPrior(
                Parameters.uniform(config, 111, TrainTesting.initBound(config)));
        TrainingBatch batch = TrainTesting.batch(2, PlaneSet.CLASSIC1, 64, 77);
        Cpu cpu = new Cpu(start, 1.0);
        ReferenceTrainer.Result ref = cpu.step(batch, 1e-3);
        double previous = Double.NaN;
        for (int micro : new int[] {64, 32, 16}) {
            Parameters host = TrainTesting.copyOf(start);
            Optimizer opt = new Optimizer(host, ADAMW);
            ReferenceTrainer.Result gpu;
            try (TornadoTrainer trainer = new TornadoTrainer(host, opt, 64, micro, batch.moveCount() + 3, 1.0,
                    TornadoSupport.defaultDevice())) {
                gpu = trainer.step(batch, 1e-3);
                trainer.sync();
            }
            assertEquals(ref.loss(), gpu.loss(), 1e-4 + 1e-3 * ref.loss(), "loss of " + micro);
            assertEquals(ref.kl(), gpu.kl(), 1e-4 + 1e-3 * Math.abs(ref.kl()), "kl of " + micro);
            double worst = parametersAgree("microbatch " + micro, cpu.parameters, host, cpu.trainer.gradients(), 1e-3);
            assertEquals(1, opt.steps());
            System.out.println("TornadoTrainer microbatches of " + micro + " in 64: loss " + gpu.loss()
                    + ", largest parameter difference / (0.01 lr) " + String.format("%.3f", worst));
            previous = gpu.loss();
        }
        assertFalse(Double.isNaN(previous));
        // The batch size must divide by the microbatch, and the batch has to have that size.
        Parameters host = TrainTesting.copyOf(start);
        Optimizer opt = new Optimizer(host, ADAMW);
        assertThrows(IllegalArgumentException.class, () -> new TornadoTrainer(host, opt, 64, 24, 1.0,
                TornadoSupport.defaultDevice()));
        try (TornadoTrainer trainer = new TornadoTrainer(host, opt, 64, 32, batch.moveCount() + 3, 1.0,
                TornadoSupport.defaultDevice())) {
            assertThrows(IllegalArgumentException.class, () -> trainer.step(TrainTesting.batch(2, PlaneSet.CLASSIC1, 32, 5), 1e-3));
            assertThrows(IllegalStateException.class, () -> trainer.run(batch));
        }
    }

    /** Two device runs from the same state give the same bits: parameters and optimiser state after 3 steps. */
    @Test
    void stepsAreBitIdentical() {
        for (Case c : cases()) {
            Parameters[] hosts = new Parameters[2];
            Optimizer[] opts = new Optimizer[2];
            for (int run = 0; run < 2; run++) {
                hosts[run] = TrainTesting.copyOf(c.start());
                opts[run] = new Optimizer(hosts[run], ADAMW);
                try (TornadoTrainer trainer = new TornadoTrainer(hosts[run], opts[run], c.samples(), c.samples(),
                        c.samples() * 256, c.cv(), TornadoSupport.defaultDevice())) {
                    for (int k = 0; k < 3; k++) {
                        trainer.step(c.batch(500 + k), 1e-3 * (k + 1));
                    }
                    trainer.sync();
                }
            }
            for (Map.Entry<String, Tensor> e : hosts[0].tensors().entrySet()) {
                OpsTesting.assertSameBits(c.name() + " " + e.getKey(), e.getValue().data(),
                        hosts[1].get(e.getKey()).data());
                OpsTesting.assertSameBits(c.name() + " m." + e.getKey(), opts[0].m().get(e.getKey()),
                        opts[1].m().get(e.getKey()));
                OpsTesting.assertSameBits(c.name() + " v." + e.getKey(), opts[0].v().get(e.getKey()),
                        opts[1].v().get(e.getKey()));
            }
        }
    }

    /**
     * A run that is stopped and resumed from a checkpoint file gives the same bits as one that was not stopped: the
     * state that {@code sync()} wrote and the next trainer loaded is the whole state.
     */
    @Test
    void resumingFromACheckpointIsBitIdentical(@TempDir Path dir) throws IOException {
        Case c = cases().get(1);
        String sha = "0".repeat(64);
        Parameters straight = TrainTesting.copyOf(c.start());
        Optimizer straightOpt = new Optimizer(straight, ADAMW);
        try (TornadoTrainer trainer = new TornadoTrainer(straight, straightOpt, c.samples(), c.samples(),
                c.samples() * 256, c.cv(), TornadoSupport.defaultDevice())) {
            for (int k = 0; k < 5; k++) {
                trainer.step(c.batch(600 + k), 1e-3);
            }
            trainer.sync();
        }
        Parameters first = TrainTesting.copyOf(c.start());
        Optimizer firstOpt = new Optimizer(first, ADAMW);
        Path file = dir.resolve("checkpoint.safetensors");
        try (TornadoTrainer trainer = new TornadoTrainer(first, firstOpt, c.samples(), c.samples(),
                c.samples() * 256, c.cv(), TornadoSupport.defaultDevice())) {
            for (int k = 0; k < 2; k++) {
                trainer.step(c.batch(600 + k), 1e-3);
            }
            trainer.sync();
            Checkpoint.write(file, first, firstOpt, 0, sha);
        }
        Checkpoint.Loaded loaded = Checkpoint.read(file);
        assertEquals(2, loaded.optimizer().steps());
        try (TornadoTrainer trainer = new TornadoTrainer(loaded.parameters(), loaded.optimizer(), c.samples(),
                c.samples(), c.samples() * 256, c.cv(), TornadoSupport.defaultDevice())) {
            for (int k = 2; k < 5; k++) {
                trainer.step(c.batch(600 + k), 1e-3);
            }
            trainer.sync();
        }
        assertEquals(5, loaded.optimizer().steps());
        for (Map.Entry<String, Tensor> e : straight.tensors().entrySet()) {
            OpsTesting.assertSameBits("resumed " + e.getKey(), e.getValue().data(),
                    loaded.parameters().get(e.getKey()).data());
            OpsTesting.assertSameBits("resumed m." + e.getKey(), straightOpt.m().get(e.getKey()),
                    loaded.optimizer().m().get(e.getKey()));
            OpsTesting.assertSameBits("resumed v." + e.getKey(), straightOpt.v().get(e.getKey()),
                    loaded.optimizer().v().get(e.getKey()));
        }
    }

    /**
     * The update runs in the same execution as the backward pass, so a bad step has changed the parameters when the
     * host sees the flag: {@code step} must throw. A batch without weights has the loss 0, a NaN weight makes the
     * convolution gradients NaN (0 times NaN) while the loss stays finite, so only the finite flag of the update can
     * tell. A NaN loss is caught on the host.
     */
    @Test
    void aNonFiniteStepThrowsAfterTheUpdateRan() {
        NetworkConfig config = NetworkConfig.tiny(2);
        Parameters start = Parameters.uniform(config, 121, TrainTesting.initBound(config));
        start.get("block0.conv1.weight").data()[3] = Float.NaN;
        TrainingBatch base = TrainTesting.batch(2, PlaneSet.PLAIN, 8, 9);
        float[] zero = new float[8];
        TrainingBatch noWeights = new TrainingBatch(8, 2, base.input(), base.moveOffsets(), base.moveFrom(),
                base.moveTo(), base.moveTarget(), base.valueTarget(), zero, zero, base.targetEntropy());
        Parameters host = TrainTesting.copyOf(start);
        Optimizer opt = new Optimizer(host, ADAMW);
        try (TornadoTrainer trainer = new TornadoTrainer(host, opt, 8, 8, base.moveCount() + 3, 1.0,
                TornadoSupport.defaultDevice())) {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> trainer.step(noWeights, 1e-3));
            assertTrue(e.getMessage().contains("not finite"), e.getMessage());
            assertTrue(e.getMessage().contains("update ran"), e.getMessage());
            trainer.sync();
            boolean bad = false;
            for (Tensor t : host.tensors().values()) {
                for (float v : t.data()) {
                    bad |= !Float.isFinite(v);
                }
            }
            assertTrue(bad, "the update ran: a NaN gradient made a parameter NaN");
        }
        Parameters host2 = TrainTesting.copyOf(start);
        Optimizer opt2 = new Optimizer(host2, ADAMW);
        try (TornadoTrainer trainer = new TornadoTrainer(host2, opt2, 8, 8, base.moveCount() + 3, 1.0,
                TornadoSupport.defaultDevice())) {
            assertThrows(IllegalStateException.class, () -> trainer.step(base, 1e-3));
        }
        // A sound start does not throw, and the step count of the host optimiser advances.
        Parameters sound = TrainTesting.copyOf(Parameters.uniform(config, 122, TrainTesting.initBound(config)));
        Optimizer soundOpt = new Optimizer(sound, ADAMW);
        try (TornadoTrainer trainer = new TornadoTrainer(sound, soundOpt, 8, 8, base.moveCount() + 3, 1.0,
                TornadoSupport.defaultDevice())) {
            trainer.step(base, 1e-3);
            assertEquals(1, soundOpt.steps());
        }
    }

    /** {@code run} computes gradients without updating, whatever an optimiser is attached; {@code step} needs one. */
    @Test
    void runDoesNotUpdateAndStepNeedsAnOptimizer() {
        NetworkConfig config = NetworkConfig.tiny(2);
        Parameters start = Parameters.uniform(config, 131, TrainTesting.initBound(config));
        TrainingBatch batch = TrainTesting.batch(2, PlaneSet.PLAIN, 8, 11);
        Parameters host = TrainTesting.copyOf(start);
        Optimizer opt = new Optimizer(host, ADAMW);
        try (TornadoTrainer trainer = new TornadoTrainer(host, opt, 8, 8, batch.moveCount() + 3, 1.0,
                TornadoSupport.defaultDevice())) {
            trainer.run(batch);
            trainer.run(batch);
            trainer.sync();
            for (Map.Entry<String, Tensor> e : start.tensors().entrySet()) {
                OpsTesting.assertSameBits("run leaves " + e.getKey(), e.getValue().data(), host.get(e.getKey()).data());
            }
            assertEquals(0, opt.steps());
        }
        try (TornadoTrainer trainer = new TornadoTrainer(start, 8, batch.moveCount() + 3, 1.0,
                TornadoSupport.defaultDevice())) {
            assertThrows(IllegalStateException.class, () -> trainer.step(batch, 1e-3));
            assertThrows(IllegalStateException.class, trainer::sync);
        }
    }

    /**
     * The two follow-ups of the review of block B1 to B5: a sample with exactly two moves (a kernel that treats two
     * moves like one gets a zero gradient), and prior.beta with all of the gradient on one corner cell (a kernel that
     * leaves a cell out of the sum over cells gets the wrong scalar).
     */
    @Test
    void twoMovesAndCornerCellsOfThePrior() {
        NetworkConfig config = NetworkConfig.tiny(2).withPlanes(PlaneSet.CLASSIC1);
        Parameters p = TrainTesting.classicPrior(Parameters.uniform(config, 141, TrainTesting.initBound(config)));
        TrainingBatch base = TrainTesting.batch(2, PlaneSet.CLASSIC1, 6, 14);
        // Sample 0 with two moves, as edgeBatch makes it; the gradient of the head weight must be that of the reference.
        TrainingBatch two = TrainTesting.twoMoveBatch(base);
        try (TornadoTrainer trainer = new TornadoTrainer(p, two.size(), 1.0, TornadoSupport.defaultDevice())) {
            trainer.run(two);
            float[] dPolicy = trainer.download(trainer.buffer("dPolicy"));
            double sum = 0;
            for (int i = 0; i < Encoder.POLICY_SIZE; i++) {
                sum += Math.abs(dPolicy[i]);
            }
            assertTrue(sum > 1e-4, "two moves with different targets and logits have a gradient, got " + sum);
            ReferenceTrainer reference = new ReferenceTrainer(p, 1.0);
            reference.accumulate(two, two.size(), 1);
            for (String name : List.of("head_policy.weight", "block0.conv1.weight", "prior.beta")) {
                double[] r = reference.gradients().get(name);
                TrainTesting.gradientAgrees(new TrainTesting.Worst(name), "two moves", r, 0, trainer.gradient(name), 0,
                        r.length, TrainTesting.maxAbs(r));
            }
        }
        for (int cell : new int[] {0, 60, 120}) {
            TrainingBatch corner = TrainTesting.cornerBatch(base, cell);
            try (TornadoTrainer trainer = new TornadoTrainer(p, corner.size(), 1.0, TornadoSupport.defaultDevice())) {
                trainer.run(corner);
                ReferenceTrainer reference = new ReferenceTrainer(p, 1.0);
                reference.accumulate(corner, corner.size(), 1);
                double[] r = reference.gradients().get("prior.beta");
                float[] g = trainer.gradient("prior.beta");
                assertTrue(Math.abs(r[0]) > 1e-2, "cell " + cell + ": the beta gradient is large, got " + r[0]);
                assertEquals(r[0], g[0], 2e-3 * Math.abs(r[0]), "prior.beta with the gradient on cell " + cell);
            }
        }
    }

    /** The convolution gradients of a step cut into microbatches equal those of one pass (bits differ, values agree). */
    @Test
    void accumulatedConvolutionGradientsAgreeWithOnePass() {
        NetworkConfig config = NetworkConfig.tiny(2);
        Parameters p = Parameters.uniform(config, 151, TrainTesting.initBound(config));
        TrainingBatch batch = TrainTesting.batch(2, PlaneSet.PLAIN, 48, 21);
        ReferenceTrainer reference = new ReferenceTrainer(p, 1.0);
        reference.accumulate(batch, 48, 1);
        // Two microbatches, run without the update: run() is for one microbatch, so build the sum by hand.
        Parameters host = TrainTesting.copyOf(p);
        Optimizer opt = new Optimizer(host, ADAMW);
        try (TornadoTrainer trainer = new TornadoTrainer(host, opt, 48, 16, batch.moveCount() + 3, 1.0,
                TornadoSupport.defaultDevice())) {
            trainer.step(batch, 0.0);
            // With lr 0 the update leaves the parameters alone, and the gradients of the last execution are the sums.
            for (String name : TornadoTrainerTest.convolutionTensors(config)) {
                double[] r = reference.gradients().get(name);
                float[] g = trainer.gradient(name);
                TrainTesting.gradientAgrees(new TrainTesting.Worst(name), "3 microbatches", r, 0, g, 0, r.length,
                        TrainTesting.maxAbs(r));
            }
            trainer.sync();
            for (Map.Entry<String, Tensor> e : p.tensors().entrySet()) {
                OpsTesting.assertSameBits("lr 0 leaves " + e.getKey(), e.getValue().data(), host.get(e.getKey()).data());
            }
        }
    }

    /**
     * {@link TrainingRun} with the device trainer: a bad step (finite loss, NaN gradient, so only the flag of the update
     * sees it) ends the run with an exception and no checkpoint of that step; a sound run writes checkpoints that hold
     * the device's parameters and state, and ends close to the CPU run.
     */
    @Test
    void aTrainingRunWithTheDeviceTrainerStopsOnTheFiniteFlagAndCheckpointsTheDeviceState(@TempDir Path dir)
            throws IOException {
        NetworkConfig config = NetworkConfig.tiny(2);
        Parameters bad = Parameters.uniform(config, 161, TrainTesting.initBound(config));
        bad.get("block0.conv1.weight").data()[3] = Float.NaN;
        TrainingBatch base = TrainTesting.batch(2, PlaneSet.PLAIN, 8, 9);
        float[] zero = new float[8];
        TrainingBatch noWeights = new TrainingBatch(8, 2, base.input(), base.moveOffsets(), base.moveFrom(),
                base.moveTo(), base.moveTarget(), base.valueTarget(), zero, zero, base.targetEntropy());
        Optimizer badOptimizer = new Optimizer(bad, ADAMW);
        LearningRateSchedule schedule = new LearningRateSchedule(1e-3, 1, 10, 0.1, 1, List.of());
        TrainingRun.Reporting quiet = new TrainingRun.Reporting(List.of(), null, 1_000_000_000L);
        TrainingRun.Checkpointing badCheckpoints = new TrainingRun.Checkpointing(dir.resolve("bad"), 1, Long.MAX_VALUE, 5,
                "0".repeat(64));
        try (TornadoTrainer trainer = new TornadoTrainer(bad, badOptimizer, 8, 8, base.moveCount() * 8, 1.0,
                TornadoSupport.defaultDevice())) {
            TrainingRun run = new TrainingRun(new TrainingRun.Setup(bad, badOptimizer, schedule, noWeights, 8, 8, 1, 1.0,
                    1L, 0, 5), badCheckpoints, quiet, TrainingRun.Clock.system(), () -> false, trainer);
            assertThrows(IllegalStateException.class, run::run);
        }
        assertTrue(Checkpoint.list(dir.resolve("bad")).isEmpty(), "no checkpoint of the bad step");

        // a sound run: the device trainer and the CPU trainer through the same TrainingRun
        Parameters[] finals = new Parameters[2];
        int[] written = new int[2];
        for (int kind = 0; kind < 2; kind++) {
            Parameters p = Parameters.uniform(config, 162, TrainTesting.initBound(config));
            Optimizer o = new Optimizer(p, ADAMW);
            TrainingBatch pool = TrainTesting.batch(2, PlaneSet.PLAIN, 32, 17);
            Path ck = dir.resolve("run" + kind);
            TrainingRun.Setup setup = new TrainingRun.Setup(p, o, schedule, pool, 16, 16, 1, 1.0, 5L, 0, 12);
            TrainingRun.Checkpointing checkpointing = new TrainingRun.Checkpointing(ck, 5, Long.MAX_VALUE, 5,
                    "0".repeat(64));
            if (kind == 0) {
                try (TornadoTrainer trainer = new TornadoTrainer(p, o, 16, 16, pool.moveCount() * 16, 1.0,
                        TornadoSupport.defaultDevice())) {
                    new TrainingRun(setup, checkpointing, quiet, TrainingRun.Clock.system(), () -> false, trainer).run();
                }
            } else {
                new TrainingRun(setup, checkpointing, quiet, TrainingRun.Clock.system(), () -> false).run();
            }
            Checkpoint.Loaded last = Checkpoint.read(Checkpoint.file(ck, 12));
            assertEquals(12, last.optimizer().steps());
            finals[kind] = last.parameters();
            written[kind] = Checkpoint.list(ck).size();
            for (Map.Entry<String, Tensor> e : p.tensors().entrySet()) {
                OpsTesting.assertSameBits("the host copy after the run is the checkpoint: " + e.getKey(),
                        e.getValue().data(), last.parameters().get(e.getKey()).data());
            }
        }
        assertEquals(written[1], written[0], "the same checkpoints");
        double worst = 0;
        for (Map.Entry<String, Tensor> e : finals[1].tensors().entrySet()) {
            float[] cpu = e.getValue().data();
            float[] gpu = finals[0].get(e.getKey()).data();
            for (int i = 0; i < cpu.length; i++) {
                worst = Math.max(worst, Math.abs(cpu[i] - gpu[i]));
            }
        }
        // 12 steps at lr <= 1e-3: every parameter moved by at most 12 lr; the two trainers differ by a fraction of that
        assertTrue(worst < 12e-3, "largest parameter difference after 12 steps " + worst);
        System.out.printf("TornadoTrainer in TrainingRun, 12 steps: largest parameter difference to the CPU run %.2e%n", worst);
    }
}
