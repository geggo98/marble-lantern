package sternhalma.tornado;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import sternhalma.nn.Encoder;
import sternhalma.nn.HexGrid;
import sternhalma.nn.NetworkConfig;
import sternhalma.nn.Optimizer;
import sternhalma.nn.Parameters;
import sternhalma.nn.ReferenceTrainer;
import sternhalma.nn.StepTrainer;
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
 * The GPU trainer of docs/training.md, Part B: for one fixed batch size, one task graph runs the training forward
 * pass (every layer's output goes to its own slot of the activation store), the losses, the backward pass of both
 * heads and of the convolutions (blocks B6 and B7: the gather form with the permuted weight for the input gradient,
 * split-K partial sums with a fixed-order reduction for the weight gradient) and, if the trainer was built with an
 * {@link Optimizer}, the update ({@link DeviceOptimizer#addTasks}). {@link #step} is one optimiser step on the device
 * (block B8); {@link #run} computes the gradients of one batch without the update.
 *
 * <p><b>Data.</b> The batch is a {@link TrainingBatch}: the input planes (7 or 13 per cell, computed on the host,
 * mirror applied) and the CSR moves are uploaded on every execution, about 6.5 MB at batch 1,024 with 13 planes. The
 * parameters ({@link OpsParameters}), the gradients ({@link DeviceTensors}), the optimiser state and every activation
 * stay on the device. Per execution, one buffer of {@code 2 N} per-sample losses and 64 finite flags come back; the
 * host forms the weighted sums in double, so the result is the same {@link ReferenceTrainer.Result} as the CPU
 * trainer's.
 *
 * <p><b>Steps and microbatches.</b> {@link #TornadoTrainer(Parameters, Optimizer, int, int, double, TornadoDevice)}
 * builds the graph for {@code microbatch} samples and runs a batch of {@code batch} samples as several executions: the
 * loss kernels divide by the size of the whole batch, the gradient kernels add the microbatches up (a flag in the
 * {@code control} array: replace for the first, add for the others), and the update runs in the execution of the last.
 * The learning rate and the Adam bias corrections of a step are computed in double on the host and sent in the
 * {@code hyper} array of {@link DeviceOptimizer}. The update runs even if a gradient is NaN: {@link #step} then throws
 * {@link IllegalStateException}, and the parameters on the device are undefined (the training loop stops the run).
 * {@link #sync()} copies parameters and optimiser state back into the {@link Parameters} and the {@link Optimizer}
 * that the constructor got, which is how a checkpoint is written; a trainer built from a checkpoint continues
 * bit for bit.
 *
 * <p><b>Residual priors.</b> With {@code classic1}, the policy logits get {@code beta} times the distance gain and
 * the value pre-activation gets {@code k} times the signed linear evaluation, as in the reference, and the
 * gradients of {@code prior.beta} and {@code prior.k} are computed.
 *
 * <p><b>Buffers for tests.</b> {@link #activation(int)} is the store: slot 0 is x_0, slot {@code 2 i + 1} is t_i and
 * slot {@code 2 i + 2} is u_i = x_{i+1} of block i, each [N][122][C] with row 121 zero. {@link #trunkGradient()} is
 * dx_B = the gradient of the last slot (kernel 5), left alone by the convolution backward. The gradient arrays of
 * all tensors are in {@link #gradients()}.
 *
 * <p><b>Readback.</b> Debug and test access ({@link #gradient}, {@link #fetch}) copies arrays from the device on
 * demand; a production step reads only the losses and the flags.
 *
 * <p>Not thread-safe. {@link #close()} frees the device memory.
 */
public final class TornadoTrainer implements StepTrainer {

    /** Moves per sample that {@link #TornadoTrainer(Parameters, int, double, TornadoDevice)} makes room for. */
    public static final int DEFAULT_MOVES_PER_SAMPLE = 256;

    /** {@code stages} of the measurement constructor: only the convolutions of the forward pass. */
    static final int CONVOLUTIONS = 1;
    /** {@code stages}: the whole forward pass, with the heads. */
    static final int FORWARD = 2;
    /** {@code stages}: forward, losses and head backward. */
    static final int HEADS_BACKWARD = 3;
    /** {@code stages}: everything: the convolution backward and, with an optimiser, the update. */
    static final int ALL_STAGES = 4;

    /** Chunks of samples of the split-K sums of the heads (docs/training.md, "Avoiding atomic adds"). */
    static final int SPLITS = 64;

    /** Chunks of rows of the split-K weight gradient of the convolutions; the system property is for tuning runs. */
    static final int CONV_SPLITS = Integer.getInteger("sternhalma.tornado.convSplits", 32);

    private static final AtomicInteger INSTANCES = new AtomicInteger();

    private final NetworkConfig config;
    private final int batch;
    private final int totalBatch;
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

    // Convolution backward: dx_B is {@code dx}; dpre, the next dx and dt alternate in these.
    private final FloatArray dpre;
    private final FloatArray dxNext;
    private final FloatArray dt;
    private final FloatArray wPrime;
    private final FloatArray zeroBias;
    private final FloatArray convPartial;
    /** {@code control[0]}: 1 adds the gradients of this microbatch to those in the arrays, 0 replaces them. */
    private final FloatArray control = new FloatArray(1);
    private final int convSplits;
    private final DeviceOptimizer optimizer;
    private final Optimizer hostOptimizer;
    private final Parameters hostParameters;
    private long steps;
    private long uploadNanos;
    private long executeNanos;
    private long resultNanos;

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

    /**
     * A trainer that runs whole steps ({@link #step}): the gradients of a batch of {@code batch} samples, computed
     * in microbatches of {@code microbatch} samples with the gradients added up on the device, and then the update
     * of the optimiser. The parameters and the optimiser state (m, v or the velocity, and the step count) are
     * copied to the device now; {@link #sync()} copies them back into {@code parameters} and {@code optimizer}.
     *
     * @param optimizer  belongs to {@code parameters}; AdamW or SGD with its settings and state
     * @param batch      samples per step, a multiple of {@code microbatch}
     * @param microbatch samples per device execution (the size the task graph is built for)
     * @throws IllegalArgumentException if the sizes do not fit or the optimiser belongs to other parameters
     */
    public TornadoTrainer(Parameters parameters, Optimizer optimizer, int batch, int microbatch,
            double valueCoefficient, TornadoDevice device) {
        this(parameters, optimizer, batch, microbatch, microbatch * DEFAULT_MOVES_PER_SAMPLE, valueCoefficient,
                device, null, false);
    }

    /** {@link #TornadoTrainer(Parameters, Optimizer, int, int, double, TornadoDevice)} with room for {@code maxMoves}. */
    public TornadoTrainer(Parameters parameters, Optimizer optimizer, int batch, int microbatch, int maxMoves,
            double valueCoefficient, TornadoDevice device) {
        this(parameters, optimizer, batch, microbatch, maxMoves, valueCoefficient, device, null, false);
    }

    TornadoTrainer(Parameters parameters, Optimizer optimizer, int batch, int microbatch, int maxMoves,
            double valueCoefficient, TornadoDevice device, Variant variant, boolean profiled) {
        this(parameters, optimizer, microbatch, batch, maxMoves, valueCoefficient, device, variant, profiled,
                ALL_STAGES);
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
        this(parameters, null, batch, batch, maxMoves, valueCoefficient, device, variant, profiled, stages);
    }

    private TornadoTrainer(Parameters parameters, Optimizer optimizer, int batch, int totalBatch, int maxMoves,
            double valueCoefficient, TornadoDevice device, Variant variant, boolean profiled, int stages) {
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(device, "device");
        if (batch < 1) {
            throw new IllegalArgumentException("batch must be >= 1, got " + batch);
        }
        if (totalBatch < batch || totalBatch % batch != 0) {
            throw new IllegalArgumentException("the batch (" + totalBatch + ") must be a multiple of the microbatch ("
                    + batch + ")");
        }
        if (optimizer != null && optimizer.parameters() != parameters) {
            throw new IllegalArgumentException("the optimizer belongs to other parameters");
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
        this.totalBatch = totalBatch;
        this.maxMoves = maxMoves;
        this.valueCoefficient = valueCoefficient;
        this.priors = config.planes().residualPriors();
        this.profiled = profiled;
        this.parameters = new OpsParameters(parameters);
        this.gradients = new DeviceTensors(config);
        this.hostParameters = parameters;
        this.hostOptimizer = optimizer;
        this.convSplits = Math.max(1, Math.min(CONV_SPLITS, batch * HexGrid.ROWS));
        if (optimizer != null) {
            this.optimizer = new DeviceOptimizer(this.parameters, gradients, optimizer.settings());
            this.optimizer.loadState(optimizer);
            this.steps = optimizer.steps();
        } else {
            this.optimizer = null;
        }

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
        dpre = new FloatArray(activation);
        dxNext = new FloatArray(activation);
        dt = new FloatArray(activation);
        wPrime = new FloatArray(KernelsHexConv.STENCIL * c * c);
        zeroBias = new FloatArray(c);
        zeroBias.init(0f);
        int widest = Math.max(c * (KernelsHexConv.STENCIL * c + 1), c * (KernelsHexConv.STENCIL * planes + 1));
        convPartial = new FloatArray(convSplits * widest);
        control.init(0f);
        noPrior.init(0f);

        String graphName = "trainer" + INSTANCES.incrementAndGet();
        TaskGraph graph = this.parameters.declareOn(new TaskGraph(graphName));
        gradients.declareOn(graph, DataTransferMode.FIRST_EXECUTION);
        graph.transferToDevice(DataTransferMode.FIRST_EXECUTION, gather, noResidual, noPrior, policy, pooled, hidden,
                values, dPolicy, dy, dz1, dPooled, dx, weightPartial, priorPartial, losses, dpre, dxNext, dt, wPrime,
                zeroBias, convPartial)
                .transferToDevice(DataTransferMode.FIRST_EXECUTION, (Object[]) acts)
                .transferToDevice(DataTransferMode.EVERY_EXECUTION, input, offsets, from, to, target, z, wp, wv, control);
        GridScheduler grids = new GridScheduler();
        addForward(graph, grids, graphName, variant != null ? variant : TornadoNetwork.variantFor(batch, c), stages);
        Variant convVariant = variant != null ? variant : TornadoNetwork.variantFor(batch, c);
        if (stages >= HEADS_BACKWARD) {
            addLossAndHeadBackward(graph, graphName, grids);
        }
        if (stages >= ALL_STAGES) {
            addConvBackward(graph, grids, convVariant);
            if (this.optimizer != null) {
                this.optimizer.addTasks(graph);
            }
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
                batch, totalBatch);
        graph.task("valueLoss", KernelsTrain::valueLoss, values, z, wv, dy, losses, batch, p,
                (float) valueCoefficient, totalBatch);
        graph.task("valueHiddenGradient", KernelsTrain::valueHiddenGradient, dy, parameters.dense2Weight(), hidden,
                dz1, batch, h, p);
        graph.task("valuePooledGradient", KernelsTrain::valuePooledGradient, dz1, parameters.dense1Weight(),
                dPooled, batch, c, h);
        written.addAll(List.of("head_value.dense2.weight", "head_value.dense2.bias",
                "head_value.dense1.weight", "head_value.dense1.bias", "head_policy.weight", "head_policy.bias"));
        graph.task("dense2Gradient", KernelsTrain::denseGradient, dy, hidden,
                gradients.get("head_value.dense2.weight"), gradients.get("head_value.dense2.bias"), control, batch, p,
                h);
        graph.task("dense1Gradient", KernelsTrain::denseGradient, dz1, pooled,
                gradients.get("head_value.dense1.weight"), gradients.get("head_value.dense1.bias"), control, batch, h,
                c);
        graph.task("trunkGradient", KernelsTrain::trunkGradient, dPolicy, parameters.policyWeight(), dPooled, dx,
                batch, c);
        graph.task("policyWeightPartial", KernelsTrain::policyWeightPartial, trunk(), dPolicy, weightPartial, batch,
                c, splits());
        graph.task("policyWeightReduce", KernelsTrain::policyWeightReduce, weightPartial,
                gradients.get("head_policy.weight"), gradients.get("head_policy.bias"), control, c, splits());
        if (priors) {
            written.add("prior.beta");
            written.add("prior.k");
            graph.task("priorGradientPartial", KernelsTrain::priorGradientPartial, dPolicy, dy, input, priorPartial,
                    batch, config.inputPlanes(), p);
            graph.task("priorGradientReduce", KernelsTrain::priorGradientReduce, priorPartial,
                    gradients.get("prior.beta"), gradients.get("prior.k"), control, batch);
        }
    }

    /**
     * Kernels 6 to 9: the backward pass of the trunk, block by block from the last (docs/training.md, "Order of the
     * backward pass", step 7 and 8). {@code cur} holds dx_{i+1} ({@code dx} for the last block, then
     * {@code dxNext}); the mask gives dpre (in {@code dpre}), the convolution backward of conv2 gives dt, the mask of
     * t_i gives dz_1, and the convolution backward of conv1 plus dpre is dx_i (in {@code dxNext}). Every convolution
     * writes (or adds) its weight and bias gradient (split-K, then the ordered reduction); the input gradient is the
     * forward convolution kernel of dz with the permuted weight.
     */
    private void addConvBackward(TaskGraph graph, GridScheduler grids, Variant variant) {
        int c = config.channels();
        int planes = config.inputPlanes();
        int rows = batch * HexGrid.ROWS;
        int n = rows * c;
        FloatArray cur = dx;
        for (int i = config.blocks() - 1; i >= 0; i--) {
            String block = "block" + i;
            FloatArray x = acts[2 * i];
            FloatArray t = acts[2 * i + 1];
            FloatArray u = acts[2 * i + 2];
            graph.task(block + "_maskResidual", KernelsConvBackward::maskInto, cur, u, dpre, n);
            // conv2: dz = dpre, input t_i
            convWeightGradient(graph, grids, variant, block + "_conv2", dpre, t, block + ".conv2", c, c, rows);
            graph.task(block + "_conv2_permute", KernelsConvBackward::permuteWeight,
                    parameters.get(block + ".conv2.weight"), wPrime, c, c);
            KernelsHexConvLayer.addTask(graph, grids, variant, block + "_conv2_din", dpre, gather, wPrime, zeroBias,
                    noResidual, dt, batch, c, c, false, false);
            graph.task(block + "_maskT", KernelsConvBackward::maskGradient, dt, t, n);
            // conv1: dz = dz_1 (in dt), input x_i; dx_i = din + dpre
            convWeightGradient(graph, grids, variant, block + "_conv1", dt, x, block + ".conv1", c, c, rows);
            graph.task(block + "_conv1_permute", KernelsConvBackward::permuteWeight,
                    parameters.get(block + ".conv1.weight"), wPrime, c, c);
            KernelsHexConvLayer.addTask(graph, grids, variant, block + "_conv1_din", dt, gather, wPrime, zeroBias,
                    dpre, dxNext, batch, c, c, true, false);
            // dxNext is read by the mask at the start of the next block only, before the convolution writes it again
            cur = dxNext;
        }
        if (cur == dx) {
            // no blocks: dx_B is dx_0 and must stay what trunkGradient wrote
            graph.task("conv_in_mask", KernelsConvBackward::maskInto, cur, acts[0], dpre, n);
            cur = dpre;
        } else {
            graph.task("conv_in_mask", KernelsConvBackward::maskGradient, cur, acts[0], n);
        }
        convWeightGradient(graph, grids, variant, "conv_in", cur, input, "conv_in", planes, c, rows);
    }

    /** The split-K weight and bias gradient of one convolution into {@code name.weight} and {@code name.bias}. */
    private void convWeightGradient(TaskGraph graph, GridScheduler grids, Variant variant, String task,
            FloatArray dz, FloatArray in, String name, int inChannels, int outChannels, int rows) {
        written.add(name + ".weight");
        written.add(name + ".bias");
        if (variant == Variant.SIMDGROUP) {
            graph.task(task + "_wpartial", KernelsConvBackward::weightPartialSimdgroup, new KernelContext(), dz, in,
                    gather, convPartial, rows, inChannels, outChannels, convSplits);
            grids.addWorkerGrid(graph.getTaskGraphName() + "." + task + "_wpartial",
                    KernelsConvBackward.weightGrid(inChannels, outChannels, convSplits));
        } else {
            graph.task(task + "_wpartial", KernelsConvBackward::weightPartial, dz, in, gather, convPartial, rows,
                    inChannels, outChannels, convSplits);
        }
        graph.task(task + "_wreduce", KernelsConvBackward::weightReduce, convPartial,
                gradients.get(name + ".weight"), gradients.get(name + ".bias"), control, inChannels, outChannels,
                convSplits);
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
        if (totalBatch != batch) {
            throw new IllegalStateException("this trainer runs whole steps of " + totalBatch + " samples: use step");
        }
        return execute(trainingBatch, false, false);
    }

    /**
     * One optimiser step: the gradients of {@code whole}, then the update with learning rate {@code lr}. The batch
     * is cut into microbatches of the device size; the loss kernels divide by the size of the whole batch, the
     * gradient kernels add the microbatches up on the device, and the update runs with the last microbatch, in
     * the same task graph. The step count of the host {@link Optimizer} goes up by one; the host parameters and
     * the host state are stale until {@link #sync()}.
     *
     * @return the terms of the batch loss, each divided by N, summed over the microbatches
     * @throws IllegalStateException if the trainer was built without an optimiser, or if a loss is NaN or infinite,
     *                               or a gradient or an updated parameter is (the finite flag of the update): the
     *                               update ran anyway, so the parameters are undefined and the run must stop
     */
    @Override
    public ReferenceTrainer.Result step(TrainingBatch whole, double lr) {
        requireOpen();
        Objects.requireNonNull(whole, "batch");
        if (optimizer == null) {
            throw new IllegalStateException("this trainer has no optimiser");
        }
        if (whole.size() != totalBatch) {
            throw new IllegalArgumentException("batch has " + whole.size() + " samples, the trainer " + totalBatch);
        }
        int micros = totalBatch / batch;
        double policy = 0;
        double value = 0;
        double kl = 0;
        for (int m = 0; m < micros; m++) {
            TrainingBatch part = micros == 1 ? whole : slice(whole, m * batch);
            boolean lastPart = m == micros - 1;
            optimizer.prepare(steps, lr, lastPart);
            ReferenceTrainer.Result r = execute(part, m > 0, lastPart);
            policy += r.policyLoss();
            value += r.valueLoss();
            kl += r.kl();
        }
        steps++;
        hostOptimizer.completeSteps(steps);
        return new ReferenceTrainer.Result(policy + value, policy, value, kl);
    }

    private TrainingBatch slice(TrainingBatch whole, int start) {
        int[] indices = new int[batch];
        for (int i = 0; i < batch; i++) {
            indices[i] = start + i;
        }
        return whole.select(indices);
    }

    /**
     * Uploads, executes and reads the losses. {@code accumulate}: add the gradients to those of the earlier
     * microbatches; {@code update}: the update tasks run in this execution, and their finite flag is checked.
     */
    private ReferenceTrainer.Result execute(TrainingBatch trainingBatch, boolean accumulate, boolean update) {
        long t0 = System.nanoTime();
        upload(trainingBatch);
        control.set(0, accumulate ? 1f : 0f);
        if (optimizer != null) {
            if (!update) {
                optimizer.prepare(steps, 0, false);
            }
            optimizer.beforeExecute(plan);
        }
        residency.beforeExecute();
        long t1 = System.nanoTime();
        last = plan.execute();
        long t2 = System.nanoTime();
        uploadNanos = t1 - t0;
        executeNanos = t2 - t1;
        if (profiled) {
            lastProfile = last.getProfilerResult();
        }
        if (update && optimizer.nonFinite() > 0) {
            throw new IllegalStateException("a gradient or an updated parameter is not finite in step " + (steps + 1)
                    + " (" + optimizer.nonFinite() + " chunks); the update ran, the parameters are undefined");
        }
        ReferenceTrainer.Result result = result(trainingBatch);
        resultNanos = System.nanoTime() - t2;
        return result;
    }

    /**
     * Bytes of the arrays that live on the device: parameters, gradients, optimiser state, the activation store, the
     * buffers of both backward passes and the batch (the profile of the memory use of docs/training.md, "Memory").
     */
    long deviceBytes() {
        long floats = 0;
        for (FloatArray[] group : new FloatArray[][] {parameters.arrays(), gradients.arrays(), acts,
                {input, target, z, wp, wv, policy, pooled, hidden, values, dPolicy, dy, dz1, dPooled, dx,
                        weightPartial, priorPartial, losses, dpre, dxNext, dt, wPrime, zeroBias, convPartial}}) {
            for (FloatArray a : group) {
                floats += a.getSize();
            }
        }
        if (optimizer != null) {
            for (FloatArray a : optimizer.stateArrays()) {
                floats += a.getSize();
            }
        }
        return 4 * floats + 4L * (offsets.getSize() + from.getSize() + to.getSize() + gather.getSize());
    }

    /**
     * Wall time of the last execution in nanoseconds: the host side before it (copy of the batch into the device
     * arrays, the optimiser scalars), {@code plan.execute()} (the transfer of the batch, all kernels, the copy-back
     * of the losses) and the host sums of the losses afterwards.
     */
    long[] lastTimes() {
        return new long[] {uploadNanos, executeNanos, resultNanos};
    }

    /**
     * Copies the parameters, and the state of the optimiser with its step count, from the device into the
     * {@link Parameters} and the {@link Optimizer} of the constructor.
     *
     * @throws IllegalStateException if nothing has run yet or the trainer has no optimiser
     */
    @Override
    public void sync() {
        requireOpen();
        if (optimizer == null) {
            throw new IllegalStateException("this trainer has no optimiser");
        }
        if (last == null) {
            return;
        }
        last.transferToHost((Object[]) parameters.arrays());
        last.transferToHost((Object[]) optimizer.stateArrays());
        parameters.storeInto(hostParameters);
        optimizer.storeInto(hostOptimizer);
        hostOptimizer.completeSteps(steps);
    }

    /** Steps done by {@link #step}, counted from the step count of the optimiser given to the constructor. */
    public long steps() {
        return steps;
    }

    /** The update's state on the device. */
    DeviceOptimizer deviceOptimizer() {
        return optimizer;
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
                policySum += weightP * lp / totalBatch;
                klSum += weightP * (lp - b.targetEntropy()[s]) / totalBatch;
            }
            if (weightV > 0) {
                double lv = perSample[2 * s + 1];
                requireFinite("value loss", lv, s);
                valueSum += valueCoefficient * weightV * lv / totalBatch;
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
