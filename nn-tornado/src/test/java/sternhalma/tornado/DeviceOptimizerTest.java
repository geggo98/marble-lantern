package sternhalma.tornado;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import sternhalma.nn.NetworkConfig;
import sternhalma.nn.Optimizer;
import sternhalma.nn.Optimizer.Kind;
import sternhalma.nn.Optimizer.Settings;
import sternhalma.nn.Parameters;
import sternhalma.nn.PlaneSet;
import sternhalma.nn.ReferenceTrainer;
import sternhalma.nn.Tensor;
import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.TornadoExecutionResult;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.exceptions.TornadoExecutionPlanException;

/**
 * The optimiser kernels and {@link DeviceOptimizer} (block B1 of docs/training.md, Part B) against
 * {@link Optimizer}: AdamW and SGD with momentum over every tensor of TINY and SMALL, plain and classic1 (the two
 * prior scalars are tensors of one element), the update with the same float gradient as input.
 *
 * <p>The tolerance of docs/training.md, "Verification against Part A", for the update kernel is
 * {@code |gpu - ref| <= 1e-6 |ref| + 1e-9}. Every comparison reports its largest error as a fraction of the
 * bound. The CPU gradients come from a live {@link ReferenceTrainer.Gradients} that the test fills, so that a
 * gradient can have any value, tiny, huge or non-finite.
 */
class DeviceOptimizerTest {

    private static final AtomicInteger GRAPHS = new AtomicInteger();

    @BeforeEach
    void requireTornado() {
        TornadoAssumptions.assumeTornado();
    }

    private static final Settings ADAMW = new Settings(Kind.ADAMW, 0.9, 0.999, 1e-8, 0.05, 0.9, 0);
    private static final Settings SGD = new Settings(Kind.SGD, 0.9, 0.999, 1e-8, 0.01, 0.8, 0.02);

    /** A device optimiser with its own plan: gradients are an input of every execution. */
    private static final class Harness implements AutoCloseable {
        final Parameters source;
        final OpsParameters parameters;
        final DeviceTensors gradients;
        final DeviceOptimizer optimizer;
        final TornadoExecutionPlan plan;
        final OpsParameters.Residency residency;
        long steps;

        Harness(Parameters source, Settings settings) {
            this.source = source;
            this.parameters = new OpsParameters(source);
            this.gradients = new DeviceTensors(source.config());
            this.optimizer = new DeviceOptimizer(parameters, gradients, settings);
            TaskGraph graph = parameters.declareOn(new TaskGraph("optimizer" + GRAPHS.incrementAndGet()));
            gradients.declareOn(graph, DataTransferMode.EVERY_EXECUTION);
            optimizer.addTasks(graph);
            parameters.declareHostReadback(graph);
            this.plan = new TornadoExecutionPlan(graph.snapshot()).withDevice(TornadoSupport.defaultDevice());
            this.residency = parameters.residency(plan);
        }

        /** Runs one step with the gradients as they are, and returns the number of bad chunks. */
        int step(double lr) {
            optimizer.prepare(steps, lr);
            residency.beforeExecute();
            optimizer.beforeExecute(plan);
            TornadoExecutionResult result = plan.execute();
            result.transferToHost((Object[]) parameters.arrays());
            result.transferToHost((Object[]) optimizer.stateArrays());
            steps++;
            return optimizer.nonFinite();
        }

        /** The parameters on the device, as a new {@link Parameters}. */
        Parameters parameters() {
            Parameters copy = copyOf(source);
            parameters.storeInto(copy);
            return copy;
        }

        @Override
        public void close() {
            try {
                plan.close();
            } catch (TornadoExecutionPlanException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static Parameters copyOf(Parameters p) {
        Map<String, Tensor> tensors = new LinkedHashMap<>();
        for (Map.Entry<String, Tensor> e : p.tensors().entrySet()) {
            Tensor t = e.getValue();
            tensors.put(e.getKey(), new Tensor(t.name(), t.shape().clone(), t.data().clone()));
        }
        return Parameters.of(p.config(), tensors);
    }

    /**
     * Fills the gradients of the reference with values of widely different size: each element is
     * {@code gaussian * 10^e} with e uniform in [lowExp, highExp], rounded to float, and copies them (as floats) into
     * the device gradient arrays.
     */
    private static void randomGradients(ReferenceTrainer reference, Harness gpu, SplittableRandom random, double lowExp,
            double highExp) {
        for (Map.Entry<String, double[]> e : reference.gradients().tensors().entrySet()) {
            double[] g = e.getValue();
            float[] f = new float[g.length];
            for (int i = 0; i < g.length; i++) {
                double scale = Math.pow(10, random.nextDouble(lowExp, highExp));
                f[i] = (float) (gaussian(random) * scale);
                g[i] = f[i];
            }
            OpsCopy.copy(f, 0, gpu.gradients.get(e.getKey()), 0, f.length);
        }
    }

    private static double gaussian(SplittableRandom random) {
        double u = 1 - random.nextDouble();
        return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * random.nextDouble());
    }

    /** Asserts {@code |gpu - ref| <= 1e-6 |ref| + 1e-9} for every element; returns the largest error / bound. */
    private static double agrees(String what, float[] ref, float[] gpu) {
        return agrees(what, ref, gpu, 0);
    }

    /** The largest error as a fraction of the bound of docs/training.md, {@code 1e-6 |ref| + 1e-9}; may exceed 1. */
    private static double docFraction(float[] ref, float[] gpu) {
        double worst = 0;
        for (int i = 0; i < ref.length; i++) {
            worst = Math.max(worst, Math.abs((double) gpu[i] - ref[i]) / (1e-6 * Math.abs(ref[i]) + 1e-9));
        }
        return worst;
    }

    /** {@link #agrees(String, float[], float[])} with an absolute term added to the bound. */
    private static double agrees(String what, float[] ref, float[] gpu, double extra) {
        assertEquals(ref.length, gpu.length, what + ": length");
        double worst = 0;
        for (int i = 0; i < ref.length; i++) {
            double bound = 1e-6 * Math.abs(ref[i]) + 1e-9 + extra;
            double error = Math.abs((double) gpu[i] - ref[i]);
            if (!(error <= bound)) {
                fail(what + ", index " + i + ": expected " + ref[i] + ", got " + gpu[i] + ", bound " + bound);
            }
            worst = Math.max(worst, error / bound);
        }
        return worst;
    }

    private static List<NetworkConfig> configs() {
        return List.of(NetworkConfig.tiny(2), NetworkConfig.tiny(3).withPlanes(PlaneSet.CLASSIC1),
                NetworkConfig.small(2).withPlanes(PlaneSet.CLASSIC1), new NetworkConfig(5, 1, 3, 7));
    }

    /** The state of the optimiser: the bound of the update plus a scale term, as the gradients have (see the test). */
    private static double agreesState(String what, float[] ref, float[] gpu) {
        double scale = 0;
        for (float v : ref) {
            scale = Math.max(scale, Math.abs(v));
        }
        double worst = 0;
        for (int i = 0; i < ref.length; i++) {
            double bound = 1e-6 * Math.abs(ref[i]) + 1e-9 + 2e-7 * scale;
            double error = Math.abs((double) gpu[i] - ref[i]);
            if (!(error <= bound)) {
                fail(what + ", index " + i + ": expected " + ref[i] + ", got " + gpu[i] + ", bound " + bound);
            }
            worst = Math.max(worst, error / bound);
        }
        return worst;
    }

    /**
     * Sends the host state to the device: the parameters of {@code cpu} and the state of {@code optimizer}. After it a
     * step on the device starts from exactly the state of the host, so that the step's own error is measured.
     */
    private static void resync(Harness gpu, Parameters cpu, Optimizer optimizer) {
        for (Map.Entry<String, Tensor> e : cpu.tensors().entrySet()) {
            System.arraycopy(e.getValue().data(), 0, gpu.source.get(e.getKey()).data(), 0, e.getValue().size());
        }
        gpu.parameters.reload();
        gpu.optimizer.loadState(optimizer);
        gpu.steps = optimizer.steps();
    }

    /**
     * Six steps with a different learning rate each, gradients from 1e-4 to 1e-1 in size, for every tensor. With
     * {@code resync} the device starts every step from the host's state (the step's own error: parameters within
     * {@code 1e-6 |ref| + 1e-9}, the bound of docs/training.md, for SGD; AdamW needs {@code + 1e-5 lr}: the float
     * rounding of m is relative to its two terms, and the step {@code m / sqrt(v)} shows it as a fraction of lr); without it, the device runs on its own and the
     * errors of earlier steps add up (parameters within {@code 1e-6 |ref| + 1e-9 + 1e-3 sum of lr}, the sign-flip
     * term of the document scaled to the steps taken). The state m and v (or u) get
     * {@code 1e-6 |ref| + 1e-9 + 2e-7 max |ref|}: a state element that is the difference of two terms of the size of
     * the tensor's largest has the rounding error of those terms.
     */
    private static void runAgainstTheReference(Settings settings, String name, boolean resync) {
        for (NetworkConfig config : configs()) {
            Parameters start = Parameters.uniform(config, 7, 0.4f);
            Parameters cpu = copyOf(start);
            Optimizer optimizer = new Optimizer(cpu, settings);
            ReferenceTrainer gradients = new ReferenceTrainer(cpu, 1.0);
            SplittableRandom random = new SplittableRandom(config.channels() * 31L + config.players());
            double worstTheta = 0;
            double worstFirst = 0;
            double worstSecond = 0;
            double lrSum = 0;
            double docBound = 0;
            try (Harness gpu = new Harness(copyOf(start), settings)) {
                double[] rates = {0.0005, 0.002, 0.005, 0.01, 0.003, 0.0008};
                for (int step = 0; step < rates.length; step++) {
                    if (resync) {
                        resync(gpu, cpu, optimizer);
                    }
                    gradients.zeroGradients();
                    randomGradients(gradients, gpu, random, -4, -1);
                    optimizer.step(gradients.gradients(), rates[step]);
                    assertEquals(0, gpu.step(rates[step]), name + " " + config + ": a good step was flagged");
                    lrSum += rates[step];
                    Parameters device = gpu.parameters();
                    for (Map.Entry<String, Tensor> e : cpu.tensors().entrySet()) {
                        String what = name + " " + config + " step " + step + " " + e.getKey();
                        float[] ref = e.getValue().data();
                        float[] got = device.get(e.getKey()).data();
                        if (resync) {
                            docBound = Math.max(docBound, docFraction(ref, got));
                        }
                        double extra = resync ? (settings.kind() == Kind.ADAMW ? 1e-5 * rates[step] : 0)
                                : 1e-3 * lrSum;
                        worstTheta = Math.max(worstTheta, agrees(what, ref, got, extra));
                    }
                    Map<String, float[]> first = settings.kind() == Kind.ADAMW ? optimizer.m() : optimizer.u();
                    if (resync || step == rates.length - 1) {
                        for (Map.Entry<String, float[]> e : first.entrySet()) {
                            worstFirst = Math.max(worstFirst, agreesState(name + " first moment " + e.getKey(),
                                    e.getValue(), gpu.optimizer.firstState().get(e.getKey())));
                        }
                        if (settings.kind() == Kind.ADAMW) {
                            for (Map.Entry<String, float[]> e : optimizer.v().entrySet()) {
                                worstSecond = Math.max(worstSecond, agreesState(name + " second moment "
                                        + e.getKey(), e.getValue(), gpu.optimizer.secondState().get(e.getKey())));
                            }
                        }
                    }
                }
            }
            System.out.printf("DeviceOptimizer %s %s, 6 steps%s, largest error / bound: parameters %.4f, first %.4f,"
                    + " second %.4f; parameters / the bound of the document: %.2f%n", name, config,
                    resync ? " from the same state" : " free-running", worstTheta, worstFirst, worstSecond, docBound);
        }
    }

    /**
     * Six AdamW steps with a different learning rate each, gradients from 1e-4 to 1e-1 in size: parameters after
     * every step, m and v at the end, for every tensor (weights decay, biases do not). A wrong bias correction, a
     * decay on biases, a swapped beta or a state that is not carried over fails here.
     */
    @Test
    void adamwMatchesTheReference() {
        runAgainstTheReference(ADAMW, "AdamW", true);
        runAgainstTheReference(ADAMW, "AdamW", false);
    }

    /** Six SGD steps with momentum and L2 on weights only; the velocity at the end. */
    @Test
    void sgdMatchesTheReference() {
        runAgainstTheReference(SGD, "SGD", true);
        runAgainstTheReference(SGD, "SGD", false);
    }

    /**
     * The first AdamW step is {@code lr * sign(g)} for every element with {@code |g| >> eps}, so a gradient that is
     * only a little different gives a step that is the same or the opposite. docs/training.md: from the same state
     * and batch, the parameters agree within 2 lr for each element and within 1e-3 lr for elements with
     * {@code |g_ref| >= 1e-3 max |g_ref|}. Here the device gets the gradient of the reference plus a relative
     * perturbation of 1e-4 of the largest gradient, and the gradients span 1e-12 to 1e-1, so tiny ones flip sign.
     */
    @Test
    void aTinyGradientMayFlipItsSignWithinTwoLearningRates() {
        NetworkConfig config = NetworkConfig.small(2).withPlanes(PlaneSet.CLASSIC1);
        Parameters start = Parameters.uniform(config, 8, 0.4f);
        Parameters cpu = copyOf(start);
        Settings settings = new Settings(Kind.ADAMW, 0.9, 0.999, 1e-8, 0.0, 0.9, 0);
        Optimizer optimizer = new Optimizer(cpu, settings);
        ReferenceTrainer gradients = new ReferenceTrainer(cpu, 1.0);
        gradients.zeroGradients();
        SplittableRandom random = new SplittableRandom(99);
        double lr = 0.01;
        try (Harness gpu = new Harness(copyOf(start), settings)) {
            randomGradients(gradients, gpu, random, -12, -1);
            double max = 0;
            for (double[] g : gradients.gradients().tensors().values()) {
                max = Math.max(max, TrainTesting.maxAbs(g));
            }
            for (Map.Entry<String, double[]> e : gradients.gradients().tensors().entrySet()) {
                float[] noisy = gpu.gradients.get(e.getKey()).toHeapArray();
                for (int i = 0; i < noisy.length; i++) {
                    noisy[i] += (float) (random.nextDouble(-1e-4, 1e-4) * max);
                }
                OpsCopy.copy(noisy, 0, gpu.gradients.get(e.getKey()), 0, noisy.length);
            }
            optimizer.step(gradients.gradients(), lr);
            assertEquals(0, gpu.step(lr));
            Parameters device = gpu.parameters();
            int flipped = 0;
            int large = 0;
            double worstLarge = 0;
            double worstAll = 0;
            for (Map.Entry<String, Tensor> e : cpu.tensors().entrySet()) {
                double[] g = gradients.gradients().get(e.getKey());
                float[] ref = e.getValue().data();
                float[] got = device.get(e.getKey()).data();
                for (int i = 0; i < ref.length; i++) {
                    double diff = Math.abs((double) got[i] - ref[i]);
                    assertTrue(diff <= 2 * lr * (1 + 1e-5), e.getKey() + "[" + i + "]: " + diff + " is above 2 lr");
                    worstAll = Math.max(worstAll, diff / (2 * lr));
                    if (diff > 0.5 * lr) {
                        flipped++;
                    }
                    if (Math.abs(g[i]) >= 1e-3 * max) {
                        large++;
                        assertTrue(diff <= 1e-3 * lr, e.getKey() + "[" + i + "]: large gradient, difference " + diff);
                        worstLarge = Math.max(worstLarge, diff / (1e-3 * lr));
                    }
                }
            }
            assertTrue(flipped > 0, "the test must contain a flipped sign");
            assertTrue(large > 100, "the test must contain large gradients");
            System.out.printf("DeviceOptimizer sign flips: %d elements flipped; largest difference / 2 lr %.4f;"
                    + " large-gradient elements (%d) largest difference / (1e-3 lr) %.4f%n", flipped, worstAll,
                    large, worstLarge);
        }
    }

    /** A step with learning rate 0 leaves the parameters exactly as they are and still moves m and v. */
    @Test
    void zeroLearningRateKeepsTheParameters() {
        NetworkConfig config = NetworkConfig.tiny(2);
        Parameters start = Parameters.uniform(config, 9, 0.4f);
        Parameters cpu = copyOf(start);
        Optimizer optimizer = new Optimizer(cpu, ADAMW);
        ReferenceTrainer gradients = new ReferenceTrainer(cpu, 1.0);
        try (Harness gpu = new Harness(copyOf(start), ADAMW)) {
            randomGradients(gradients, gpu, new SplittableRandom(5), -3, -1);
            optimizer.step(gradients.gradients(), 0.0);
            assertEquals(0, gpu.step(0.0));
            Parameters device = gpu.parameters();
            for (Map.Entry<String, Tensor> e : start.tensors().entrySet()) {
                OpsTesting.assertSameBits(e.getKey(), e.getValue().data(), device.get(e.getKey()).data());
            }
            for (Map.Entry<String, float[]> e : optimizer.m().entrySet()) {
                agreesState("m " + e.getKey(), e.getValue(), gpu.optimizer.firstState().get(e.getKey()));
                assertTrue(TrainTesting.maxAbs(toDouble(e.getValue())) > 0);
            }
        }
    }

    private static double[] toDouble(float[] a) {
        double[] d = new double[a.length];
        for (int i = 0; i < a.length; i++) {
            d[i] = a[i];
        }
        return d;
    }

    /**
     * Huge but finite gradients: g * g overflows float, so v is infinite; the update is 0 and finite, as in the reference,
     * which must not be flagged. A gradient of 1e-30 gives a v that is below the smallest float: the step is the
     * reference's within the bound.
     */
    @Test
    void extremeButFiniteGradientsAgreeWithTheReference() {
        NetworkConfig config = NetworkConfig.tiny(2);
        for (Settings settings : List.of(ADAMW, SGD)) {
            Parameters start = Parameters.uniform(config, 10, 0.4f);
            Parameters cpu = copyOf(start);
            Optimizer optimizer = new Optimizer(cpu, settings);
            ReferenceTrainer gradients = new ReferenceTrainer(cpu, 1.0);
            try (Harness gpu = new Harness(copyOf(start), settings)) {
                randomGradients(gradients, gpu, new SplittableRandom(6), -3, -1);
                double[] huge = gradients.gradients().get("head_policy.bias");
                float[] hugeF = {1e30f, -2e25f};
                float[] tinyF = new float[8];
                Arrays.fill(tinyF, 1e-30f);
                huge[0] = hugeF[0];
                huge[1] = hugeF[1];
                OpsCopy.copy(hugeF, 0, gpu.gradients.get("head_policy.bias"), 0, 2);
                double[] tiny = gradients.gradients().get("conv_in.bias");
                for (int i = 0; i < 8; i++) {
                    tiny[i] = tinyF[i];
                }
                OpsCopy.copy(tinyF, 0, gpu.gradients.get("conv_in.bias"), 0, 8);
                optimizer.step(gradients.gradients(), 0.01);
                assertEquals(0, gpu.step(0.01), settings.kind() + ": finite gradients must not be flagged");
                Parameters device = gpu.parameters();
                for (Map.Entry<String, Tensor> e : cpu.tensors().entrySet()) {
                    agrees(settings.kind() + " " + e.getKey(), e.getValue().data(), device.get(e.getKey()).data());
                }
            }
        }
    }

    /**
     * A NaN or an infinity in one gradient element is flagged, wherever it sits: the first or the last element of
     * every tensor (the last element of a tensor whose size is not a multiple of the chunk, and the only element of
     * a prior scalar), and nothing else is flagged. A clean step after a bad one reports 0 again.
     */
    @Test
    void aNonFiniteGradientIsFlaggedInEveryTensor() {
        for (Settings settings : List.of(ADAMW, SGD)) {
            NetworkConfig config = NetworkConfig.tiny(2).withPlanes(PlaneSet.CLASSIC1);
            Parameters start = Parameters.uniform(config, 11, 0.4f);
            try (Harness gpu = new Harness(copyOf(start), settings)) {
                assertEquals(0, gpu.step(0.001), "all gradients 0");
                for (String name : gpu.gradients.names()) {
                    int n = gpu.gradients.get(name).getSize();
                    for (int index : new int[] {0, n - 1, n / 2}) {
                        for (float bad : new float[] {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
                            gpu.gradients.get(name).set(index, bad);
                            assertEquals(1, gpu.step(0.001), settings.kind() + " " + name + "[" + index + "] = " + bad);
                            gpu.gradients.get(name).set(index, 0f);
                            // The bad gradient put NaN or infinity into the parameter and the state: start over.
                            gpu.source.get(name).data()[index] = 0.1f;
                            gpu.parameters.reload();
                            gpu.optimizer.loadState(new Optimizer(start, settings));
                        }
                    }
                }
            }
        }
    }

    /**
     * A NaN parameter alone (all gradients 0) is flagged because the updated parameter is not finite, and so is a
     * parameter that the step pushes to infinity: a huge learning rate on a huge gradient overflows float.
     */
    @Test
    void aNonFiniteParameterIsFlagged() {
        NetworkConfig config = NetworkConfig.tiny(2);
        Parameters start = Parameters.uniform(config, 12, 0.4f);
        start.get("block0.conv2.weight").data()[7] = Float.NaN;
        try (Harness gpu = new Harness(start, ADAMW)) {
            assertEquals(1, gpu.step(0.001));
        }
        Parameters ok = Parameters.uniform(config, 12, 0.4f);
        try (Harness gpu = new Harness(ok, SGD)) {
            gpu.gradients.get("head_value.dense2.bias").set(0, 3e38f);
            assertEquals(1, gpu.step(100.0), "theta - lr * u overflows");
        }
    }

    /** Two harnesses on the same state and gradients give bit-identical parameters and state after 3 steps. */
    @Test
    void stepsAreBitIdentical() {
        NetworkConfig config = NetworkConfig.small(2).withPlanes(PlaneSet.CLASSIC1);
        Parameters start = Parameters.uniform(config, 13, 0.3f);
        ReferenceTrainer gradients = new ReferenceTrainer(copyOf(start), 1.0);
        Parameters[] results = new Parameters[2];
        List<Map<String, float[]>> states = new ArrayList<>();
        for (int run = 0; run < 2; run++) {
            try (Harness gpu = new Harness(copyOf(start), ADAMW)) {
                SplittableRandom random = new SplittableRandom(1234);
                for (int step = 0; step < 3; step++) {
                    randomGradients(gradients, gpu, random, -5, -1);
                    assertEquals(0, gpu.step(0.002));
                }
                results[run] = gpu.parameters();
                states.add(gpu.optimizer.secondState());
            }
        }
        for (Map.Entry<String, Tensor> e : results[0].tensors().entrySet()) {
            OpsTesting.assertSameBits(e.getKey(), e.getValue().data(), results[1].get(e.getKey()).data());
            OpsTesting.assertSameBits("v " + e.getKey(), states.get(0).get(e.getKey()), states.get(1).get(e.getKey()));
        }
    }

    /**
     * A checkpoint: the state of a host optimiser after three steps goes to the device, before the first execution
     * and after earlier ones, and the next step equals the host's fourth step. With a wrong step count the bias
     * corrections would differ; with the state not sent, m and v would restart at 0.
     */
    @Test
    void loadedStateContinuesLikeTheReference() {
        for (boolean afterExecution : new boolean[] {false, true}) {
            NetworkConfig config = NetworkConfig.tiny(3).withPlanes(PlaneSet.CLASSIC1);
            Parameters cpu = Parameters.uniform(config, 14, 0.4f);
            Optimizer optimizer = new Optimizer(cpu, ADAMW);
            ReferenceTrainer gradients = new ReferenceTrainer(cpu, 1.0);
            SplittableRandom random = new SplittableRandom(77);
            Parameters source = Parameters.uniform(config, 15, 0.4f);
            try (Harness gpu = new Harness(source, ADAMW)) {
                if (afterExecution) {
                    // Garbage state on the device first: the loaded state must replace it.
                    randomGradients(gradients, gpu, new SplittableRandom(3), -3, -1);
                    gpu.step(0.01);
                }
                for (int step = 0; step < 3; step++) {
                    gradients.zeroGradients();
                    randomGradients(gradients, gpu, random, -4, -1);
                    optimizer.step(gradients.gradients(), 0.004);
                }
                for (Map.Entry<String, Tensor> e : cpu.tensors().entrySet()) {
                    System.arraycopy(e.getValue().data(), 0, source.get(e.getKey()).data(), 0, e.getValue().size());
                }
                gpu.parameters.reload();
                gpu.optimizer.loadState(optimizer);
                gpu.steps = optimizer.steps();
                gradients.zeroGradients();
                randomGradients(gradients, gpu, random, -4, -1);
                optimizer.step(gradients.gradients(), 0.004);
                assertEquals(0, gpu.step(0.004));
                Parameters device = gpu.parameters();
                double worst = 0;
                for (Map.Entry<String, Tensor> e : cpu.tensors().entrySet()) {
                    worst = Math.max(worst, agrees("after load " + e.getKey(), e.getValue().data(),
                            device.get(e.getKey()).data()));
                }
                for (Map.Entry<String, float[]> e : optimizer.v().entrySet()) {
                    worst = Math.max(worst, agreesState("v after load " + e.getKey(), e.getValue(),
                            gpu.optimizer.secondState().get(e.getKey())));
                }
                System.out.printf("DeviceOptimizer loaded state (after an execution: %b), largest error / bound %.4f%n",
                        afterExecution, worst);
            }
        }
    }

    /** Bad arguments fail before anything runs. */
    @Test
    void misuseFailsLoudly() {
        NetworkConfig config = NetworkConfig.tiny(2);
        Parameters p = Parameters.uniform(config, 16, 0.4f);
        OpsParameters parameters = new OpsParameters(p);
        DeviceTensors gradients = new DeviceTensors(config);
        DeviceOptimizer optimizer = new DeviceOptimizer(parameters, gradients, ADAMW);
        assertThrows(IllegalArgumentException.class, () -> optimizer.prepare(-1, 0.1));
        assertThrows(IllegalArgumentException.class, () -> optimizer.prepare(0, -0.1));
        assertThrows(IllegalArgumentException.class, () -> optimizer.prepare(0, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> optimizer.prepare(0, Double.POSITIVE_INFINITY));
        assertThrows(IllegalStateException.class, () -> new DeviceOptimizer(parameters, gradients, SGD).secondState());
        assertThrows(IllegalArgumentException.class, () -> optimizer.loadState(new Optimizer(p, SGD)));
        assertThrows(IllegalArgumentException.class,
                () -> new DeviceOptimizer(parameters, new DeviceTensors(NetworkConfig.small(2)), ADAMW));
        assertThrows(IllegalArgumentException.class, () -> gradients.get("nonsense"));
        assertThrows(IllegalArgumentException.class, () -> gradients.unflatten(new float[3]));
        float[] flat = new float[gradients.flatten().length];
        flat[5] = 2f;
        gradients.unflatten(flat);
        assertEquals(2f, gradients.flatten()[5]);
        assertEquals(config.parameterCount(), flat.length);
    }
}
