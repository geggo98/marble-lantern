package sternhalma.tornado;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import sternhalma.nn.Encoder;
import sternhalma.nn.ForwardPass;
import sternhalma.nn.HexGrid;
import sternhalma.nn.NetworkConfig;
import sternhalma.nn.Parameters;
import sternhalma.nn.ResidualPriors;
import sternhalma.tornado.KernelsHexConvLayer.Variant;
import uk.ac.manchester.tornado.api.GridScheduler;
import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoProfilerResult;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.common.TornadoDevice;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.enums.ProfilerMode;
import uk.ac.manchester.tornado.api.exceptions.TornadoExecutionPlanException;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * The whole forward pass of docs/network.md, "Layers", on a TornadoVM device. It agrees with the reference
 * {@link sternhalma.nn.Network} within {@link sternhalma.nn.Network#agrees}; it sums in float, so it is not
 * bit-exact. Use it wherever a {@link ForwardPass} is taken, for example {@code new Evaluator(new
 * TornadoNetwork(parameters))}.
 *
 * <h2>Residual priors (classic1)</h2>
 *
 * <p>A network of {@link sternhalma.nn.PlaneSet#CLASSIC1} adds the classical priors to its outputs
 * ({@link ResidualPriors}). The device computes the raw outputs: the policy logits as they are, and the value head
 * without its tanh ({@link OpsKernels#valueHead} with {@code applyTanh} 0). {@link #forward} adds the priors on the
 * host, for each position, after the results are back: {@code beta * gain} to the logits through the plane
 * {@code DIST_OWN} of the input, and {@code k * sign * eval} to the pre-tanh value through the plane {@code EVAL},
 * then the tanh, with the code of {@link sternhalma.nn.VectorNetwork}. So no extra upload and no new kernel; the cost
 * is 121 multiply-adds and two tanh per position. {@code prior.beta} and {@code prior.k} are read from the
 * {@link Parameters} in the constructor and in {@link #reload()}. The planes {@code classic1-planes} and {@code plain}
 * need none of this and keep the tanh on the device.
 *
 * <h2>What stays on the device</h2>
 *
 * <p>One task graph holds every layer: {@code conv_in}, the two convolutions of each block (the second one adds
 * the residual and applies ReLU itself, {@link KernelsHexConv}), the policy head and the fused value head
 * ({@link OpsHeads#addTasks}). The parameters ({@link OpsParameters}) and the gather table are copied to the
 * device at the first execution of a plan and stay there; the three trunk activations never leave the device.
 * Per call, exactly two transfers happen: the encoded input to the device, and one buffer with the policy
 * logits and the values ({@link OpsKernels#outputSize}) back to the host. The plans run without TornadoVM's
 * profiler, which costs milliseconds per call; {@link #profiled} makes an instance with it.
 *
 * <h2>Batch sizes: one plan per power of two</h2>
 *
 * <p>TornadoVM bakes scalar kernel arguments, the batch size among them, into the compiled task
 * (docs/tornado.md), so a plan serves one batch size. A call with n positions uses the plan of the bucket
 * {@link #bucket}: the smallest power of two that is at least n, capped at {@link #maxBatch()}. The positions go
 * into the first n slots of the bucket's input; the other slots keep the input of an earlier call (or zeros) and
 * their outputs are not read. Positions are independent (docs/network.md, "Tensor layout"), so these slots
 * cannot change the results of the real ones. A batch above {@link #maxBatch()} is computed in chunks of
 * {@link #maxBatch()}. Plans are built on first use of their bucket; the first call of a bucket compiles its
 * kernels (measured once for a LARGE bucket: about 0.16 s; the first plan of a JVM also starts the runtime).
 *
 * <p>Why not one plan for the largest batch with an "active count": the active count would have to live in a
 * device array that every kernel reads, which none of the kernels does, and every call would still launch the
 * grid of the largest batch; at batch 1 that is up to 1,024 times the work. Why not one plan per exact batch
 * size: MCTS leaf batches vary in size, and every new size would compile again. Powers of two bound the plans to
 * {@code log2(maxBatch) + 1} and the wasted work to less than a factor of 2, and a caller who wants none of it
 * uses power-of-two batches.
 *
 * <h2>Kernels</h2>
 *
 * <p>The convolutions use {@link Variant#NAIVE} while bucket times channel count is at most {@link #NAIVE_UP_TO},
 * and {@link Variant#SIMDGROUP} above ({@link #variantFor}): SMALL uses NAIVE up to batch 4, LARGE up to batch 2.
 * docs/tornado.md, "Measured speed", has the measurement behind the rule.
 *
 * <h2>State and threads</h2>
 *
 * <p>Like {@link sternhalma.nn.VectorNetwork}, this class reads the {@link Parameters} only in the constructor
 * and in {@link #reload()}: after an in-place update, call {@link #reload()}, and every plan sends the new values
 * before its next execution. An instance is <b>not thread-safe</b>: its plans and host buffers are fields.
 * Misuse fails loudly: a call of {@link #forward}, {@link #reload()} or {@link #close()} that starts while
 * another one runs on the same instance throws {@link IllegalStateException} (an atomic busy flag), and so does
 * every call after {@link #close()}. {@link #close()} frees the device memory of all plans.
 */
public final class TornadoNetwork implements ForwardPass, AutoCloseable {

    /** {@link #maxBatch()} of the one-argument constructor. */
    public static final int DEFAULT_MAX_BATCH = 1024;

    /** Largest product of bucket and channel count whose convolutions use {@link Variant#NAIVE}. */
    public static final int NAIVE_UP_TO = 128;

    private static final AtomicInteger INSTANCES = new AtomicInteger();

    private final NetworkConfig config;
    private final OpsParameters parameters;
    /** The host parameters, for the two scalars of the residual priors. */
    private final Parameters source;
    /** Whether the plane set has residual priors: the device value head stops before the tanh, the host adds the priors. */
    private final boolean priors;
    private float priorBeta;
    private float priorK;
    private final TornadoDevice device;
    private final int maxBatch;
    /** The kernel of every convolution, or null to choose by bucket with {@link #variantFor}. */
    private final Variant forcedVariant;
    private final String name;
    private final IntArray gather = IntArray.fromArray(HexGrid.gatherTable());
    /** Read by the convolutions without residual instead of a residual; never read, as residual = 0. */
    private final FloatArray noResidual = new FloatArray(1);
    /** Index log2(bucket); the last entry may be a bucket of {@link #maxBatch} that is no power of two. */
    private final Plan[] plans;
    private final AtomicBoolean busy = new AtomicBoolean();
    /** Whether the plans run with the silent profiler; without it {@link #lastProfile()} is not available. */
    private final boolean profiled;
    private boolean closed;
    private TornadoProfilerResult lastResult;
    private long copyInNanos;
    private long executeNanos;
    private long copyOutNanos;
    private long priorNanos;
    private long calls;

    /**
     * A network on the default device ({@link TornadoSupport#defaultDevice()}) with batches of up to
     * {@link #DEFAULT_MAX_BATCH} per execution. Copies the current parameters.
     *
     * @throws NullPointerException  if parameters is null
     * @throws IllegalStateException if TornadoVM is not usable in this JVM
     */
    public TornadoNetwork(Parameters parameters) {
        this(parameters, TornadoSupport.defaultDevice(), DEFAULT_MAX_BATCH);
    }

    /**
     * Copies the current parameters; nothing runs on the device before the first {@link #forward}.
     *
     * @param device   the device that runs the kernels
     * @param maxBatch positions per execution at most, at least 1; larger batches are split
     * @throws NullPointerException     if an argument is null
     * @throws IllegalArgumentException if maxBatch is below 1, or so large that an activation [maxBatch][122][C]
     *                                  would not fit into an int-indexed array
     */
    public TornadoNetwork(Parameters parameters, TornadoDevice device, int maxBatch) {
        this(parameters, device, maxBatch, null, false);
    }

    /**
     * As {@link #TornadoNetwork(Parameters, TornadoDevice, int)}, but every plan runs with TornadoVM's silent
     * profiler, so that {@link #lastProfile()} reports compile time, kernel time and bytes moved. The profiler is
     * not free: it made a LARGE call at batch 1 take 8.4 ms instead of 1.6 ms (docs/tornado.md, "Measured
     * speed"). Use it for tests and measurements, not in a search.
     */
    public static TornadoNetwork profiled(Parameters parameters, TornadoDevice device, int maxBatch) {
        return new TornadoNetwork(parameters, device, maxBatch, null, true);
    }

    /**
     * As the public constructor; a non-null {@code variant} is used for every convolution, and {@code profiled}
     * false runs the plans without the profiler (tests, benchmark).
     */
    TornadoNetwork(Parameters parameters, TornadoDevice device, int maxBatch, Variant variant, boolean profiled) {
        Objects.requireNonNull(parameters, "parameters");
        this.device = Objects.requireNonNull(device, "device");
        DeviceBackend.requireLimits(device);
        if (variant == Variant.SIMDGROUP) {
            DeviceBackend.requireSimdgroup(device);
        }
        if (maxBatch < 1) {
            throw new IllegalArgumentException("maxBatch must be >= 1, got " + maxBatch);
        }
        this.config = parameters.config();
        this.source = parameters;
        this.priors = config.planes().residualPriors();
        readPriorScalars();
        // FloatArray sizes are ints: the largest buffer is an activation [maxBatch][122][C].
        if ((long) maxBatch * HexGrid.ROWS * config.channels() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("maxBatch " + maxBatch + " is too large for " + config.channels()
                    + " channels: an activation would exceed " + Integer.MAX_VALUE + " floats");
        }
        this.parameters = new OpsParameters(parameters);
        this.maxBatch = maxBatch;
        this.forcedVariant = variant;
        this.profiled = profiled;
        this.name = "network" + INSTANCES.incrementAndGet();
        this.plans = new Plan[slot(maxBatch) + 1];
    }

    @Override
    public NetworkConfig config() {
        return config;
    }

    /** False: the plans and host buffers are fields. A concurrent call throws IllegalStateException. */
    @Override
    public boolean threadSafe() {
        return false;
    }

    /** Positions per execution at most; {@link #forward} splits larger batches. */
    public int maxBatch() {
        return maxBatch;
    }

    /**
     * The batch size of the plan that computes {@code n} positions: the smallest power of two at least
     * {@code n}, but at most {@code maxBatch}.
     *
     * @throws IllegalArgumentException if n or maxBatch is below 1
     */
    public static int bucket(int n, int maxBatch) {
        if (n < 1 || maxBatch < 1) {
            throw new IllegalArgumentException("n and maxBatch must be >= 1, got " + n + ", " + maxBatch);
        }
        int power = Integer.highestOneBit(Math.min(n, maxBatch));
        int up = power == n ? n : power << 1;
        return Math.min(up, maxBatch);
    }

    /**
     * The convolution kernel this class uses for a bucket of a network with {@code channels} channels: NAIVE if
     * {@code bucket * channels <= NAIVE_UP_TO}, else SIMDGROUP. Measured on the whole pass (docs/tornado.md,
     * "Measured speed"): NAIVE is ahead for SMALL up to batch 8 and for LARGE up to batch 2, SIMDGROUP from
     * there on (by 1.7 times at LARGE batch 4). The rule gives SMALL batch 8 to SIMDGROUP, which was 1 to 7 %
     * slower there.
     */
    public static Variant variantFor(int bucket, int channels) {
        return (long) bucket * channels <= NAIVE_UP_TO ? Variant.NAIVE : Variant.SIMDGROUP;
    }

    /**
     * {@link #variantFor(int, int)} for a device: where the SIMD-group kernels do not run (any backend but Metal,
     * or {@link DeviceBackend#FORCE_GENERIC_PROPERTY} set) the large buckets use {@link Variant#TILED}, the
     * generic local-memory kernel. Not measured on CUDA or OpenCL yet (docs/tornado.md, "Backends").
     */
    public static Variant variantFor(int bucket, int channels, TornadoDevice device) {
        Variant v = variantFor(bucket, channels);
        return v == Variant.SIMDGROUP && !DeviceBackend.useSimdgroup(device) ? Variant.TILED : v;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The positions are computed on the device in chunks of at most {@link #maxBatch()}, each with the plan of
     * its {@link #bucket}. The first call of a bucket compiles its kernels.
     *
     * @throws IllegalStateException also after {@link #close()}
     */
    @Override
    public void forward(float[] input, int batch, float[] policyLogits, float[] values) {
        int players = config.players();
        if (batch < 1) {
            throw new IllegalArgumentException("batch must be >= 1, got " + batch);
        }
        requireLength("input", input, (long) batch * config.inputSize());
        requireLength("policyLogits", policyLogits, (long) batch * Encoder.POLICY_SIZE);
        requireLength("values", values, (long) batch * players);
        // Check every position before anything runs, as the reference rejects a dirty padding row too.
        for (int b = 0; b < batch; b++) {
            int pad = b * config.inputSize() + HexGrid.PADDING * config.inputPlanes();
            for (int plane = 0; plane < config.inputPlanes(); plane++) {
                if (input[pad + plane] != 0) {
                    throw new IllegalArgumentException("padding row of input " + b + " is not zero in plane "
                            + plane + ": " + input[pad + plane]);
                }
            }
        }
        enter("forward");
        try {
            for (int first = 0; first < batch; first += maxBatch) {
                int n = Math.min(maxBatch, batch - first);
                plan(bucket(n, maxBatch)).run(input, first, n, policyLogits, values);
                if (priors) {
                    long t = System.nanoTime();
                    addPriors(input, first, n, policyLogits, values);
                    priorNanos += System.nanoTime() - t;
                }
                calls++;
            }
        } finally {
            busy.set(false);
        }
    }

    /**
     * Copies the current values of the {@link Parameters} given to the constructor, for example after an
     * in-place training step. Every plan sends them to the device before its next execution.
     *
     * @throws IllegalStateException if another call on this instance is running, or after {@link #close()}
     */
    public void reload() {
        enter("reload");
        try {
            parameters.reload();
            readPriorScalars();
        } finally {
            busy.set(false);
        }
    }

    private void readPriorScalars() {
        if (priors) {
            priorBeta = source.get(ResidualPriors.BETA).data()[0];
            priorK = source.get(ResidualPriors.K).data()[0];
        }
    }

    /**
     * The priors of positions {@code first .. first + n - 1}, on the raw outputs of the device: the logits get
     * {@code beta * gain}, the value its term before the tanh, then the tanh (as in {@code VectorNetwork}).
     */
    private void addPriors(float[] input, int first, int n, float[] policyLogits, float[] values) {
        int players = config.players();
        int stride = config.inputPlanes();
        for (int b = first; b < first + n; b++) {
            int inputOffset = b * config.inputSize();
            ResidualPriors.addPolicy(priorBeta, input, inputOffset, stride, policyLogits, b * Encoder.POLICY_SIZE);
            for (int o = 0; o < players; o++) {
                float pre = values[b * players + o];
                pre += priorK * ResidualPriors.valueBase(input, inputOffset, stride, o);
                values[b * players + o] = (float) Math.tanh(pre);
            }
        }
    }

    /**
     * Cost of the last device execution (the last chunk of the last {@link #forward}).
     *
     * @throws IllegalStateException if this instance was not made by {@link #profiled}, or nothing has run yet
     */
    public ExecutionProfile lastProfile() {
        return ExecutionProfile.of(lastProfilerResult());
    }

    /** The TornadoVM profiler result of the last device execution, with every field (benchmark). */
    TornadoProfilerResult lastProfilerResult() {
        if (!profiled) {
            throw new IllegalStateException("this instance runs without the profiler");
        }
        if (lastResult == null) {
            throw new IllegalStateException("forward has not run yet");
        }
        return lastResult;
    }

    /**
     * Where the time of {@link #forward} went since the instance was made, for tools: calls (executions), the copy of the input
     * into the device buffer, the execution (kernels and both transfers, as TornadoVM runs them), the copy of the results out
     * of it and the host priors, in nanoseconds. Read it from the thread that calls {@link #forward}, or when no call runs.
     */
    public String timings() {
        return String.format(java.util.Locale.ROOT, "%d executions: copy in %.1f ms, execute %.1f ms, copy out %.1f ms,"
                + " host priors %.1f ms (totals)", calls, copyInNanos / 1e6, executeNanos / 1e6, copyOutNanos / 1e6,
                priorNanos / 1e6);
    }

    /** Number of plans built so far, one per bucket in use. */
    public int plansBuilt() {
        int n = 0;
        for (Plan p : plans) {
            if (p != null) {
                n++;
            }
        }
        return n;
    }

    /**
     * Frees the device memory of every plan. Later calls of {@link #forward} and {@link #reload()} throw
     * {@link IllegalStateException}; a second {@code close()} does nothing.
     *
     * @throws IllegalStateException if another call on this instance is running
     */
    @Override
    public void close() {
        if (!busy.compareAndSet(false, true)) {
            throw busyException("close");
        }
        try {
            if (closed) {
                return;
            }
            closed = true;
            IllegalStateException failure = null;
            for (int i = 0; i < plans.length; i++) {
                if (plans[i] != null) {
                    try {
                        plans[i].plan.close();
                    } catch (TornadoExecutionPlanException e) {
                        if (failure == null) {
                            failure = new IllegalStateException("could not release an execution plan", e);
                        } else {
                            failure.addSuppressed(e);
                        }
                    }
                    plans[i] = null;
                }
            }
            if (failure != null) {
                throw failure;
            }
        } finally {
            busy.set(false);
        }
    }

    /** Runs {@code action} while the instance is marked busy, as during a forward pass (tests only). */
    void whileBusy(Runnable action) {
        enter("whileBusy");
        try {
            action.run();
        } finally {
            busy.set(false);
        }
    }

    /**
     * Marks the instance busy, or throws if it is busy already or closed. The caller clears the flag when it is
     * done; a thread that gets the exception never touches the flag.
     */
    private void enter(String what) {
        if (!busy.compareAndSet(false, true)) {
            throw busyException(what);
        }
        if (closed) {
            busy.set(false);
            throw new IllegalStateException("TornadoNetwork is closed: " + what + " was called after close()");
        }
    }

    private static IllegalStateException busyException(String what) {
        return new IllegalStateException("TornadoNetwork is not thread-safe: " + what
                + " was called while another call on the same instance is running; use one instance per thread");
    }

    /** Index of a bucket in {@link #plans}: log2 for powers of two, the last slot for a capped bucket. */
    private static int slot(int bucket) {
        return 32 - Integer.numberOfLeadingZeros(bucket - 1);
    }

    private Plan plan(int bucket) {
        int slot = slot(bucket);
        Plan p = plans[slot];
        if (p == null) {
            p = new Plan(bucket, forcedVariant != null ? forcedVariant : variantFor(bucket, config.channels(), device));
            plans[slot] = p;
        }
        return p;
    }

    /** The task graph and buffers of one bucket. */
    private final class Plan {

        private final int batch;
        private final FloatArray input;
        private final FloatArray out;
        private final TornadoExecutionPlan plan;
        private final OpsParameters.Residency residency;

        Plan(int batch, Variant variant) {
            this.batch = batch;
            int c = config.channels();
            int activation = batch * HexGrid.ROWS * c;
            input = new FloatArray(batch * config.inputSize());
            input.init(0f);
            out = new FloatArray(OpsKernels.outputSize(batch, config.players()));
            FloatArray x = new FloatArray(activation);
            FloatArray t = new FloatArray(activation);
            FloatArray u = new FloatArray(activation);
            String graphName = name + "b" + batch;
            TaskGraph graph = parameters.declareOn(new TaskGraph(graphName))
                    .transferToDevice(DataTransferMode.FIRST_EXECUTION, gather, noResidual, x, t, u, out)
                    .transferToDevice(DataTransferMode.EVERY_EXECUTION, input);
            GridScheduler grids = new GridScheduler();
            KernelsHexConvLayer.addTask(graph, grids, variant, "conv_in", input, gather,
                    parameters.get("conv_in.weight"), parameters.get("conv_in.bias"), noResidual, x, batch,
                    config.inputPlanes(), c, false, true);
            // x is the block input; conv1 writes t, conv2 reads t and adds x into u; then u is the next x.
            for (int i = 0; i < config.blocks(); i++) {
                String block = "block" + i;
                KernelsHexConvLayer.addTask(graph, grids, variant, block + "_conv1", x, gather,
                        parameters.get(block + ".conv1.weight"), parameters.get(block + ".conv1.bias"), noResidual,
                        t, batch, c, c, false, true);
                KernelsHexConvLayer.addTask(graph, grids, variant, block + "_conv2", t, gather,
                        parameters.get(block + ".conv2.weight"), parameters.get(block + ".conv2.bias"), x, u, batch,
                        c, c, true, true);
                FloatArray swap = x;
                x = u;
                u = swap;
            }
            OpsHeads.addTasks(graph, parameters, x, out, batch, !priors)
                    .transferToHost(DataTransferMode.EVERY_EXECUTION, out);
            OpsHeads.schedule(grids, graphName, batch);
            TornadoExecutionPlan p = new TornadoExecutionPlan(graph.snapshot()).withDevice(device)
                    .withGridScheduler(grids);
            plan = profiled ? p.withProfiler(ProfilerMode.SILENT) : p;
            residency = parameters.residency(plan);
        }

        /** Computes positions {@code first .. first + n - 1} of the caller's arrays, n &lt;= batch. */
        void run(float[] in, int first, int n, float[] policyLogits, float[] values) {
            int players = config.players();
            long t0 = System.nanoTime();
            OpsCopy.copy(in, first * config.inputSize(), input, 0, n * config.inputSize());
            residency.beforeExecute();
            long t1 = System.nanoTime();
            TornadoProfilerResult result = plan.execute().getProfilerResult();
            if (profiled) {
                lastResult = result;
            }
            long t2 = System.nanoTime();
            OpsCopy.copy(out, 0, policyLogits, first * Encoder.POLICY_SIZE, n * Encoder.POLICY_SIZE);
            OpsCopy.copy(out, OpsKernels.valuesOffset(batch), values, first * players, n * players);
            copyInNanos += t1 - t0;
            executeNanos += t2 - t1;
            copyOutNanos += System.nanoTime() - t2;
        }
    }

    private static void requireLength(String name, float[] a, long length) {
        Objects.requireNonNull(a, name);
        if (a.length < length) {
            throw new IllegalArgumentException(name + " needs " + length + " floats, has " + a.length);
        }
    }
}
