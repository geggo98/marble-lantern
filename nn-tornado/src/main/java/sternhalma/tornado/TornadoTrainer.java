package sternhalma.tornado;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import sternhalma.nn.Encoder;
import sternhalma.nn.HexGrid;
import sternhalma.nn.NetworkConfig;
import sternhalma.nn.Parameters;
import sternhalma.nn.ReferenceTrainer;
import sternhalma.nn.TrainingBatch;
import sternhalma.tornado.KernelsHexConvLayer.Variant;
import uk.ac.manchester.tornado.api.GridScheduler;
import uk.ac.manchester.tornado.api.KernelContext;
import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.TornadoExecutionResult;
import uk.ac.manchester.tornado.api.TornadoProfilerResult;
import uk.ac.manchester.tornado.api.common.TornadoDevice;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.enums.ProfilerMode;
import uk.ac.manchester.tornado.api.exceptions.TornadoExecutionPlanException;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * The GPU trainer of docs/training.md, Part B, as far as blocks B1 to B5 reach: for one fixed batch size, one task
 * graph runs the training forward pass (every layer's output goes to its own slot of the activation store), the
 * loss and the backward pass of both heads down to the gradient of the trunk output. The convolution backward
 * (B6, B7) and the optimiser step ({@link DeviceOptimizer#addTasks}) are added to the same graph later; this class
 * leaves their inputs and outputs in place.
 *
 * <p><b>Data.</b> The batch is a {@link TrainingBatch}: the input planes (7 or 13 per cell, computed on the host,
 * mirror applied) and the CSR moves are uploaded on every {@link #run}, about 6.5 MB at batch 1,024 with 13
 * planes. The parameters ({@link OpsParameters}), the gradients ({@link DeviceTensors}) and every activation stay
 * on the device. Per execution, one buffer of {@code 2 N} per-sample losses comes back; the host forms the
 * weighted sums in double, so {@link #run} returns the same {@link ReferenceTrainer.Result} as the CPU trainer.
 *
 * <p><b>Residual priors.</b> With {@code classic1}, the policy logits get {@code beta} times the distance gain and
 * the value pre-activation gets {@code k} times the signed linear evaluation, as in the reference, and the
 * gradients of {@code prior.beta} and {@code prior.k} are computed.
 *
 * <p><b>Interface for the convolution backward.</b> {@link #activation(int)} is the store: slot 0 is x_0, slot
 * {@code 2 i + 1} is t_i and slot {@code 2 i + 2} is u_i = x_{i+1} of block i, each [N][122][C] with row 121
 * zero. {@link #trunkGradient()} is dx_B = the gradient of the last slot, [N][122][C], row 121 zero (kernel 5). The
 * gradient arrays of all tensors are in {@link #gradients()}; the head tensors and the priors are written by
 * {@link #run}, the convolution gradients stay 0 until B6 and B7. A backward kernel writes (not adds) its
 * gradient, so gradient accumulation over microbatches is not available yet.
 *
 * <p><b>Readback.</b> Debug and test access ({@link #gradient}, {@link #fetch}) copies arrays from the device on
 * demand; a production step reads only the losses.
 *
 * <p>Not thread-safe. {@link #close()} frees the device memory.
 */
public final class TornadoTrainer implements AutoCloseable {

    /** Moves per sample that {@link #TornadoTrainer(Parameters, int, double, TornadoDevice)} makes room for. */
    public static final int DEFAULT_MOVES_PER_SAMPLE = 256;

    /** {@code stages} of the measurement constructor: only the convolutions of the forward pass. */
    static final int CONVOLUTIONS = 1;
    /** {@code stages}: the whole forward pass, with the heads. */
    static final int FORWARD = 2;
    /** {@code stages}: forward, losses and head backward. */
    static final int ALL_STAGES = 3;

    /** Chunks of samples of the split-K sums (docs/training.md, "Avoiding atomic adds"). */
    static final int SPLITS = 64;

    private static final AtomicInteger INSTANCES = new AtomicInteger();

    private final NetworkConfig config;
    private final int batch;
    private final int maxMoves;
    private final double valueCoefficient;
    private final boolean priors;
    private final boolean profiled;
    private final OpsParameters parameters;
    private final DeviceTensors gradients;
    private final IntArray gather = IntArray.fromArray(HexGrid.gatherTable());

    // Batch, uploaded on every execution.
    private final FloatArray input;
    private final IntArray offsets;
    private final IntArray from;
    private final IntArray to;
    private final FloatArray target;
    private final FloatArray z;
    private final FloatArray wp;
    private final FloatArray wv;

    // Activation store and head buffers, on the device.
    private final FloatArray[] acts;
    private final FloatArray policy;
    private final FloatArray pooled;
    private final FloatArray hidden;
    private final FloatArray values;
    private final FloatArray dPolicy;
    private final FloatArray dy;
    private final FloatArray dz1;
    private final FloatArray dPooled;
    private final FloatArray dx;
    private final FloatArray weightPartial;
    private final FloatArray priorPartial;
    private final FloatArray losses;
    private final FloatArray noResidual = new FloatArray(1);
    private final FloatArray noPrior = new FloatArray(1);

    /** Gradient tensors that a task of the graph writes. */
    private final Set<String> written = new HashSet<>();
    private final TornadoExecutionPlan plan;
    private final OpsParameters.Residency residency;
    private TornadoExecutionResult last;
    private TornadoProfilerResult lastProfile;
    private boolean closed;

    /**
     * A trainer for batches of exactly {@code batch} samples with room for {@link #DEFAULT_MOVES_PER_SAMPLE} moves
     * per sample, on {@code device}. Copies the current parameters; nothing runs before the first {@link #run}.
     *
     * @param valueCoefficient c_v of the loss, finite and at least 0
     */
    public TornadoTrainer(Parameters parameters, int batch, double valueCoefficient, TornadoDevice device) {
        this(parameters, batch, batch * DEFAULT_MOVES_PER_SAMPLE, valueCoefficient, device, null, false, ALL_STAGES);
    }

    /** {@link #TornadoTrainer(Parameters, int, double, TornadoDevice)} with an explicit capacity for moves. */
    public TornadoTrainer(Parameters parameters, int batch, int maxMoves, double valueCoefficient,
            TornadoDevice device) {
        this(parameters, batch, maxMoves, valueCoefficient, device, null, false, ALL_STAGES);
    }

    /**
     * As the public constructor; a non-null {@code variant} is used for every convolution, {@code profiled}
     * runs the plan with TornadoVM's silent profiler, and {@code stages} cuts the graph short for measurements by
     * difference: {@link #CONVOLUTIONS} only the convolutions, {@link #FORWARD} also the heads, {@link #ALL_STAGES}
     * everything. A cut graph computes no losses and gradients; {@link #run} returns zeros then.
     */
    TornadoTrainer(Parameters parameters, int batch, int maxMoves, double valueCoefficient, TornadoDevice device,
            Variant variant, boolean profiled, int stages) {
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(device, "device");
        if (batch < 1) {
            throw new IllegalArgumentException("batch must be >= 1, got " + batch);
        }
        if (maxMoves < 1) {
            throw new IllegalArgumentException("maxMoves must be >= 1, got " + maxMoves);
        }
        if (!(valueCoefficient >= 0) || !Double.isFinite(valueCoefficient)) {
            throw new IllegalArgumentException("valueCoefficient must be >= 0 and finite, got " + valueCoefficient);
        }
        this.config = parameters.config();
        int c = config.channels();
        if ((long) batch * HexGrid.ROWS * c > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("batch " + batch + " is too large for " + c
                    + " channels: an activation would exceed " + Integer.MAX_VALUE + " floats");
        }
        this.batch = batch;
        this.maxMoves = maxMoves;
        this.valueCoefficient = valueCoefficient;
        this.priors = config.planes().residualPriors();
        this.profiled = profiled;
        this.parameters = new OpsParameters(parameters);
        this.gradients = new DeviceTensors(config);

        int players = config.players();
        int hiddenSize = config.valueHidden();
        int planes = config.inputPlanes();
        int activation = batch * HexGrid.ROWS * c;
        input = new FloatArray(batch * config.inputSize());
        offsets = new IntArray(batch + 1);
        from = new IntArray(maxMoves);
        to = new IntArray(maxMoves);
        target = new FloatArray(maxMoves);
        z = new FloatArray(batch * players);
        wp = new FloatArray(batch);
        wv = new FloatArray(batch);
        acts = new FloatArray[1 + 2 * config.blocks()];
        for (int i = 0; i < acts.length; i++) {
            acts[i] = new FloatArray(activation);
        }
        policy = new FloatArray(batch * Encoder.POLICY_SIZE);
        pooled = new FloatArray(batch * c);
        hidden = new FloatArray(batch * hiddenSize);
        values = new FloatArray(batch * players);
        dPolicy = new FloatArray(batch * Encoder.POLICY_SIZE);
        dy = new FloatArray(batch * players);
        dz1 = new FloatArray(batch * hiddenSize);
        dPooled = new FloatArray(batch * c);
        dx = new FloatArray(activation);
        weightPartial = new FloatArray(splits() * 2 * (c + 1));
        priorPartial = new FloatArray(2 * batch);
        losses = new FloatArray(2 * batch);
        noPrior.init(0f);

        String graphName = "trainer" + INSTANCES.incrementAndGet();
        TaskGraph graph = this.parameters.declareOn(new TaskGraph(graphName));
        gradients.declareOn(graph, DataTransferMode.FIRST_EXECUTION);
        graph.transferToDevice(DataTransferMode.FIRST_EXECUTION, gather, noResidual, noPrior, policy, pooled, hidden,
                values, dPolicy, dy, dz1, dPooled, dx, weightPartial, priorPartial, losses)
                .transferToDevice(DataTransferMode.FIRST_EXECUTION, (Object[]) acts)
                .transferToDevice(DataTransferMode.EVERY_EXECUTION, input, offsets, from, to, target, z, wp, wv);
        GridScheduler grids = new GridScheduler();
        addForward(graph, grids, graphName, variant != null ? variant : TornadoNetwork.variantFor(batch, c), stages);
        if (stages >= ALL_STAGES) {
            addLossAndHeadBackward(graph, graphName, grids);
        }
        graph.transferToHost(DataTransferMode.EVERY_EXECUTION, losses);
        this.parameters.declareHostReadback(graph);
        graph.transferToHost(DataTransferMode.UNDER_DEMAND, (Object[]) acts)
                .transferToHost(DataTransferMode.UNDER_DEMAND, policy, pooled, hidden, values, dPolicy, dy, dz1, dPooled,
                        dx)
                .transferToHost(DataTransferMode.UNDER_DEMAND, writtenGradients());
        TornadoExecutionPlan p = new TornadoExecutionPlan(graph.snapshot()).withDevice(device)
                .withGridScheduler(grids);
        this.plan = profiled ? p.withProfiler(ProfilerMode.SILENT) : p;
        this.residency = this.parameters.residency(plan);
    }

    private Object[] writtenGradients() {
        return written.stream().map(gradients::get).toArray();
    }

    private int splits() {
        return Math.min(SPLITS, batch);
    }

    /** Kernel 2: the forward pass, every layer into its own slot (docs/training.md, Part B). */
    private void addForward(TaskGraph graph, GridScheduler grids, String graphName, Variant variant, int stages) {
        int c = config.channels();
        int planes = config.inputPlanes();
        KernelsHexConvLayer.addTask(graph, grids, variant, "conv_in", input, gather,
                parameters.get("conv_in.weight"), parameters.get("conv_in.bias"), noResidual, acts[0], batch,
                planes, c, false, true);
        for (int i = 0; i < config.blocks(); i++) {
            String block = "block" + i;
            FloatArray x = acts[2 * i];
            FloatArray t = acts[2 * i + 1];
            FloatArray u = acts[2 * i + 2];
            KernelsHexConvLayer.addTask(graph, grids, variant, block + "_conv1", x, gather,
                    parameters.get(block + ".conv1.weight"), parameters.get(block + ".conv1.bias"), noResidual, t,
                    batch, c, c, false, true);
            KernelsHexConvLayer.addTask(graph, grids, variant, block + "_conv2", t, gather,
                    parameters.get(block + ".conv2.weight"), parameters.get(block + ".conv2.bias"), x, u, batch, c,
                    c, true, true);
        }
        if (stages < FORWARD) {
            return;
        }
        FloatArray top = trunk();
        graph.task("policyHead", OpsKernels::policyHead, top, parameters.policyWeight(), parameters.policyBias(),
                policy, batch, c);
        FloatArray beta = priors ? parameters.get("prior.beta") : noPrior;
        FloatArray priorK = priors ? parameters.get("prior.k") : noPrior;
        if (priors) {
            graph.task("policyPrior", KernelsTrain::policyPrior, input, beta, policy, batch, planes);
        }
        graph.task("valueHead", KernelsTrain::valueHead, new KernelContext(), top, parameters.dense1Weight(),
                parameters.dense1Bias(), parameters.dense2Weight(), parameters.dense2Bias(), input, priorK, pooled,
                hidden, values, c, config.valueHidden(), config.players(), planes, priors ? 1 : 0);
        grids.addWorkerGrid(graphName + ".valueHead", OpsKernels.valueHeadGrid(batch));
    }

    /** Kernels 3, 4, 5 and 10: the losses, then the backward pass of both heads to dx_B. */
    private void addLossAndHeadBackward(TaskGraph graph, String graphName, GridScheduler grids) {
        int c = config.channels();
        int h = config.valueHidden();
        int p = config.players();
        graph.task("policyLoss", KernelsTrain::policyLoss, policy, offsets, from, to, target, wp, dPolicy, losses,
                batch);
        graph.task("valueLoss", KernelsTrain::valueLoss, values, z, wv, dy, losses, batch, p,
                (float) valueCoefficient);
        graph.task("valueHiddenGradient", KernelsTrain::valueHiddenGradient, dy, parameters.dense2Weight(), hidden,
                dz1, batch, h, p);
        graph.task("valuePooledGradient", KernelsTrain::valuePooledGradient, dz1, parameters.dense1Weight(),
                dPooled, batch, c, h);
        written.addAll(List.of("head_value.dense2.weight", "head_value.dense2.bias",
                "head_value.dense1.weight", "head_value.dense1.bias", "head_policy.weight", "head_policy.bias"));
        graph.task("dense2Gradient", KernelsTrain::denseGradient, dy, hidden,
                gradients.get("head_value.dense2.weight"), gradients.get("head_value.dense2.bias"), batch, p, h);
        graph.task("dense1Gradient", KernelsTrain::denseGradient, dz1, pooled,
                gradients.get("head_value.dense1.weight"), gradients.get("head_value.dense1.bias"), batch, h, c);
        graph.task("trunkGradient", KernelsTrain::trunkGradient, dPolicy, parameters.policyWeight(), dPooled, dx,
                batch, c);
        graph.task("policyWeightPartial", KernelsTrain::policyWeightPartial, trunk(), dPolicy, weightPartial, batch,
                c, splits());
        graph.task("policyWeightReduce", KernelsTrain::policyWeightReduce, weightPartial,
                gradients.get("head_policy.weight"), gradients.get("head_policy.bias"), c, splits());
        if (priors) {
            written.add("prior.beta");
            written.add("prior.k");
            graph.task("priorGradientPartial", KernelsTrain::priorGradientPartial, dPolicy, dy, input, priorPartial,
                    batch, config.inputPlanes(), p);
            graph.task("priorGradientReduce", KernelsTrain::priorGradientReduce, priorPartial,
                    gradients.get("prior.beta"), gradients.get("prior.k"), batch);
        }
    }

    public NetworkConfig config() {
        return config;
    }

    /** Samples per batch. */
    public int batch() {
        return batch;
    }

    /** Moves that all samples of a batch may have together. */
    public int maxMoves() {
        return maxMoves;
    }

    /** The parameters on the device. */
    OpsParameters parameters() {
        return parameters;
    }

    /** The gradient arrays on the device, in {@link Parameters#shapes} order: the head tensors and priors after {@link #run}. */
    DeviceTensors gradients() {
        return gradients;
    }

    /** The output of the last block, the input of both heads: the last slot of the store. */
    FloatArray trunk() {
        return acts[acts.length - 1];
    }

    /**
     * Slot {@code i} of the activation store: 0 is x_0, {@code 2 i + 1} is t_i and {@code 2 i + 2} is u_i of block i.
     * The array lives on the device; {@link #fetch} copies it to the host.
     */
    FloatArray activation(int slot) {
        return acts[slot];
    }

    /** Number of slots: {@code 1 + 2 * blocks}. */
    int slots() {
        return acts.length;
    }

    /** dx_B, the gradient of the trunk output, [N][122][C]. */
    FloatArray trunkGradient() {
        return dx;
    }

    /** The buffers of the heads, by name, for tests and for the later kernels. */
    FloatArray buffer(String name) {
        return switch (name) {
            case "policy" -> policy;
            case "pooled" -> pooled;
            case "hidden" -> hidden;
            case "values" -> values;
            case "dPolicy" -> dPolicy;
            case "dy" -> dy;
            case "dz1" -> dz1;
            case "dPooled" -> dPooled;
            case "dx" -> dx;
            case "losses" -> losses;
            default -> throw new IllegalArgumentException("no buffer named " + name);
        };
    }

    /**
     * Copies the current values of the {@link Parameters} given to the constructor to the device, for example
     * after the host changed them; the next {@link #run} uses them.
     */
    public void reload() {
        requireOpen();
        parameters.reload();
    }

    /**
     * Forward pass, losses and head backward for one batch.
     *
     * @return the terms of the batch loss, each already divided by N, as {@link ReferenceTrainer} reports them
     * @throws IllegalArgumentException if the batch does not match the trainer (size, players, planes, moves)
     * @throws IllegalStateException    if the loss of a weighted sample is NaN or infinite, or after {@link #close()}
     */
    public ReferenceTrainer.Result run(TrainingBatch trainingBatch) {
        requireOpen();
        Objects.requireNonNull(trainingBatch, "batch");
        upload(trainingBatch);
        residency.beforeExecute();
        last = plan.execute();
        if (profiled) {
            lastProfile = last.getProfilerResult();
        }
        return result(trainingBatch);
    }

    private void upload(TrainingBatch b) {
        if (b.size() != batch) {
            throw new IllegalArgumentException("batch has " + b.size() + " samples, the trainer " + batch);
        }
        if (b.players() != config.players()) {
            throw new IllegalArgumentException("batch has " + b.players() + " players, network " + config.players());
        }
        if (b.inputPlanes() != config.inputPlanes()) {
            throw new IllegalArgumentException("batch has " + b.inputPlanes() + " planes per cell, network "
                    + config.planes() + " needs " + config.inputPlanes());
        }
        if (b.moveCount() > maxMoves) {
            throw new IllegalArgumentException("batch has " + b.moveCount() + " moves, the trainer has room for "
                    + maxMoves);
        }
        OpsCopy.copy(b.input(), 0, input, 0, b.input().length);
        OpsCopy.copy(b.moveOffsets(), 0, offsets, 0, batch + 1);
        OpsCopy.copy(b.moveFrom(), 0, from, 0, b.moveCount());
        OpsCopy.copy(b.moveTo(), 0, to, 0, b.moveCount());
        OpsCopy.copy(b.moveTarget(), 0, target, 0, b.moveCount());
        OpsCopy.copy(b.valueTarget(), 0, z, 0, batch * config.players());
        OpsCopy.copy(b.policyWeight(), 0, wp, 0, batch);
        OpsCopy.copy(b.valueWeight(), 0, wv, 0, batch);
    }

    /** The weighted means of the per-sample losses, in double, as {@code ReferenceTrainer.Worker.sample}. */
    private ReferenceTrainer.Result result(TrainingBatch b) {
        float[] perSample = new float[2 * batch];
        OpsCopy.copy(losses, 0, perSample, 0, perSample.length);
        double policySum = 0;
        double valueSum = 0;
        double klSum = 0;
        for (int s = 0; s < batch; s++) {
            float weightP = b.policyWeight()[s];
            float weightV = b.valueWeight()[s];
            if (weightP > 0) {
                double lp = perSample[2 * s];
                requireFinite("policy loss", lp, s);
                policySum += weightP * lp / batch;
                klSum += weightP * (lp - b.targetEntropy()[s]) / batch;
            }
            if (weightV > 0) {
                double lv = perSample[2 * s + 1];
                requireFinite("value loss", lv, s);
                valueSum += valueCoefficient * weightV * lv / batch;
            }
        }
        return new ReferenceTrainer.Result(policySum + valueSum, policySum, valueSum, klSum);
    }

    private static void requireFinite(String what, double value, int sample) {
        if (!Double.isFinite(value)) {
            throw new IllegalStateException(what + " of sample " + sample + " is " + value);
        }
    }

    /**
     * Copies device arrays to the host, from the last {@link #run}.
     *
     * @throws IllegalStateException if {@link #run} has not run
     */
    void fetch(FloatArray... arrays) {
        if (last == null) {
            throw new IllegalStateException("run has not run yet");
        }
        last.transferToHost((Object[]) arrays);
    }

    /**
     * The gradient of a tensor as a new host array, after {@link #run}. A tensor that no kernel writes yet (the
     * convolutions, until B6 and B7) reads 0: TornadoVM has no device copy of an array that no task uses.
     */
    public float[] gradient(String name) {
        FloatArray g = gradients.get(name);
        if (last == null) {
            throw new IllegalStateException("run has not run yet");
        }
        if (written.contains(name)) {
            fetch(g);
        }
        return g.toHeapArray();
    }

    /** A buffer or a slot of the store as a new host array, after {@link #run}. */
    float[] download(FloatArray array) {
        fetch(array);
        return array.toHeapArray();
    }

    /** Copies the parameters from the device into {@code target}, which must have the config of this trainer. */
    public void downloadParameters(Parameters target) {
        requireOpen();
        if (last == null) {
            throw new IllegalStateException("run has not run yet");
        }
        last.transferToHost((Object[]) parameters.arrays());
        parameters.storeInto(target);
    }

    /**
     * The profiler result of the last {@link #run}, per task.
     *
     * @throws IllegalStateException if this trainer was not made for measurements, or nothing has run
     */
    TornadoProfilerResult lastProfilerResult() {
        if (!profiled || lastProfile == null) {
            throw new IllegalStateException("no profiler result: profiled=" + profiled);
        }
        return lastProfile;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("TornadoTrainer is closed");
        }
    }

    /** Frees the device memory; a second call does nothing. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            plan.close();
        } catch (TornadoExecutionPlanException e) {
            throw new IllegalStateException("could not release the execution plan", e);
        }
    }
}
