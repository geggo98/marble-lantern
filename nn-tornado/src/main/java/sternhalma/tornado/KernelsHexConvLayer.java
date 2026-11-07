package sternhalma.tornado;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.atomic.AtomicInteger;

import sternhalma.nn.HexGrid;
import uk.ac.manchester.tornado.api.GridScheduler;
import uk.ac.manchester.tornado.api.KernelContext;
import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.common.TornadoDevice;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.enums.ProfilerMode;
import uk.ac.manchester.tornado.api.exceptions.TornadoExecutionPlanException;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * One hex convolution layer (gather, matrix multiplication, bias, optional residual add, optional ReLU) of fixed
 * shape and batch size on a TornadoVM device; see {@link KernelsHexConv} for the computation.
 *
 * <p>The weight, the bias and the gather table go to the device once, at the first {@link #apply}, and stay
 * there; later changes to the caller's arrays are not seen. Input, residual and output move on every call. The
 * first call compiles the kernel. The plan runs with the silent profiler, so {@link #lastProfile()} reports the
 * cost of each call. Not thread-safe. {@link #close()} frees the device memory.
 */
public final class KernelsHexConvLayer implements AutoCloseable {

    /** Which kernel computes the layer. */
    public enum Variant {
        /** {@link KernelsHexConv#naive}: one work item per output element. */
        NAIVE,
        /** {@link KernelsHexConv#tiled}: local-memory tiles, a register micro tile per work item. */
        TILED,
        /** {@link KernelsHexConv#simdgroup}: local-memory tiles multiplied on the Metal SIMD-group matrix units. */
        SIMDGROUP
    }

    private static final AtomicInteger INSTANCES = new AtomicInteger();

    private final int inChannels;
    private final int outChannels;
    private final int batch;
    private final boolean residual;
    private final FloatArray input;
    private final FloatArray residualInput;
    private final FloatArray output;
    private final TornadoExecutionPlan plan;
    private ExecutionProfile lastProfile;

    /**
     * @param variant     the kernel
     * @param weight      [outChannels][7 * inChannels], copied
     * @param bias        [outChannels], copied
     * @param inChannels  at least 1
     * @param outChannels at least 1
     * @param batch       activations per {@link #apply} call, at least 1
     * @param residual    whether {@link #apply(float[], float[])} adds a residual before the activation
     * @param relu        whether the layer ends with ReLU
     * @param device      the device that runs the kernel
     * @throws IllegalArgumentException if a size is below 1 or an array has the wrong length
     */
    public KernelsHexConvLayer(Variant variant, float[] weight, float[] bias, int inChannels, int outChannels,
            int batch, boolean residual, boolean relu, TornadoDevice device) {
        this(variant, weight, bias, inChannels, outChannels, batch, residual, relu, device, HexGrid.gatherTable(),
                0f);
    }

    /**
     * As the public constructor, with a gather table of the caller's choice (tests use a corrupted one) and the
     * value the device output buffer holds before the first call (tests use NaN to see unwritten elements).
     */
    KernelsHexConvLayer(Variant variant, float[] weight, float[] bias, int inChannels, int outChannels, int batch,
            boolean residual, boolean relu, TornadoDevice device, int[] gatherTable, float outputFill) {
        if (inChannels < 1 || outChannels < 1 || batch < 1) {
            throw new IllegalArgumentException("sizes must be positive: inChannels=" + inChannels
                    + " outChannels=" + outChannels + " batch=" + batch);
        }
        requireLength("weight", weight, outChannels * HexGrid.STENCIL * inChannels);
        requireLength("bias", bias, outChannels);
        requireLength("gather table", gatherTable, HexGrid.CELLS * HexGrid.STENCIL);
        this.inChannels = inChannels;
        this.outChannels = outChannels;
        this.batch = batch;
        this.residual = residual;
        FloatArray deviceWeight = FloatArray.fromArray(weight);
        FloatArray deviceBias = FloatArray.fromArray(bias);
        IntArray gather = IntArray.fromArray(gatherTable);
        this.input = new FloatArray(batch * HexGrid.ROWS * inChannels);
        this.residualInput = new FloatArray(residual ? batch * HexGrid.ROWS * outChannels : 1);
        this.output = new FloatArray(batch * HexGrid.ROWS * outChannels);
        output.init(outputFill);
        String name = "hexconv" + INSTANCES.incrementAndGet();
        TaskGraph graph = new TaskGraph(name)
                .transferToDevice(DataTransferMode.FIRST_EXECUTION, deviceWeight, deviceBias, gather, output)
                .transferToDevice(residual ? DataTransferMode.EVERY_EXECUTION : DataTransferMode.FIRST_EXECUTION,
                        residualInput)
                .transferToDevice(DataTransferMode.EVERY_EXECUTION, input);
        GridScheduler grids = new GridScheduler();
        addTask(graph, grids, variant, "conv", input, gather, deviceWeight, deviceBias, residualInput, output, batch,
                inChannels, outChannels, residual, relu);
        graph.transferToHost(DataTransferMode.EVERY_EXECUTION, output);
        TornadoExecutionPlan p = new TornadoExecutionPlan(graph.snapshot());
        if (!grids.keySet().isEmpty()) {
            p.withGridScheduler(grids);
        }
        this.plan = p.withDevice(device).withProfiler(ProfilerMode.SILENT);
    }

    /**
     * Adds the task {@code taskName} that runs {@code variant} on the given device arrays to {@code graph}, and its
     * launch grid, if the variant needs one, to {@code grids} (key {@code graphName.taskName}). The caller declares
     * the transfers. A forward pass that chains layers in one task graph can use this.
     */
    static void addTask(TaskGraph graph, GridScheduler grids, Variant variant, String taskName, FloatArray in,
            IntArray gather, FloatArray weight, FloatArray bias, FloatArray res, FloatArray out, int batch,
            int inChannels, int outChannels, boolean residual, boolean relu) {
        int residualFlag = residual ? 1 : 0;
        int reluFlag = relu ? 1 : 0;
        String key = graph.getTaskGraphName() + "." + taskName;
        switch (variant) {
            case NAIVE -> graph.task(taskName, KernelsHexConv::naive, in, gather, weight, bias, res, out, batch,
                    inChannels, outChannels, residualFlag, reluFlag);
            case TILED -> {
                graph.task(taskName, KernelsHexConv::tiled, new KernelContext(), in, gather, weight, bias, res, out,
                        batch, inChannels, outChannels, residualFlag, reluFlag);
                grids.addWorkerGrid(key, KernelsHexConv.tiledGrid(batch, outChannels));
            }
            case SIMDGROUP -> {
                graph.task(taskName, KernelsHexConv::simdgroup, new KernelContext(), in, gather, weight, bias, res,
                        out, batch, inChannels, outChannels, residualFlag, reluFlag);
                grids.addWorkerGrid(key, KernelsHexConv.simdgroupGrid(batch, outChannels));
            }
        }
    }

    /**
     * Applies a layer built without residual.
     *
     * @param in [batch][122][inChannels]; row 121 of each activation must be zero
     * @return a new array [batch][122][outChannels] with row 121 zero
     * @throws IllegalStateException    if the layer was built with a residual
     * @throws IllegalArgumentException if {@code in} has the wrong length or a non-zero padding row
     */
    public float[] apply(float[] in) {
        if (residual) {
            throw new IllegalStateException("this layer adds a residual; call apply(in, residual)");
        }
        return run(in, null);
    }

    /**
     * Applies a layer built with residual.
     *
     * @param in  [batch][122][inChannels]; row 121 of each activation must be zero
     * @param res [batch][122][outChannels], added before the activation
     * @return a new array [batch][122][outChannels] with row 121 zero
     * @throws IllegalStateException    if the layer was built without a residual
     * @throws IllegalArgumentException if an array has the wrong length or {@code in} a non-zero padding row
     */
    public float[] apply(float[] in, float[] res) {
        if (!residual) {
            throw new IllegalStateException("this layer adds no residual; call apply(in)");
        }
        requireLength("residual", res, batch * HexGrid.ROWS * outChannels);
        return run(in, res);
    }

    private float[] run(float[] in, float[] res) {
        requireLength("input", in, batch * HexGrid.ROWS * inChannels);
        for (int b = 0; b < batch; b++) {
            int padding = (b * HexGrid.ROWS + HexGrid.PADDING) * inChannels;
            for (int ch = 0; ch < inChannels; ch++) {
                if (in[padding + ch] != 0f) {
                    throw new IllegalArgumentException("padding row of input " + b + " is not zero in channel "
                            + ch + ": " + in[padding + ch]);
                }
            }
        }
        copy(in, input);
        if (res != null) {
            copy(res, residualInput);
        }
        lastProfile = ExecutionProfile.of(plan.execute().getProfilerResult());
        return output.toHeapArray();
    }

    /** Copies a heap array into an off-heap array of the same length. */
    static void copy(float[] from, FloatArray to) {
        MemorySegment.copy(from, 0, to.getSegment(), ValueLayout.JAVA_FLOAT, 0, from.length);
    }

    /**
     * Cost of the last {@link #apply} call.
     *
     * @throws IllegalStateException if {@code apply} has not run yet
     */
    public ExecutionProfile lastProfile() {
        if (lastProfile == null) {
            throw new IllegalStateException("apply has not run yet");
        }
        return lastProfile;
    }

    /** Activations per {@link #apply} call. */
    public int batch() {
        return batch;
    }

    /** Frees the device memory of the plan. */
    @Override
    public void close() {
        try {
            plan.close();
        } catch (TornadoExecutionPlanException e) {
            throw new IllegalStateException("could not release the execution plan", e);
        }
    }

    private static void requireLength(String name, float[] a, int length) {
        if (a.length != length) {
            throw new IllegalArgumentException(name + ": expected " + length + " floats, got " + a.length);
        }
    }

    private static void requireLength(String name, int[] a, int length) {
        if (a.length != length) {
            throw new IllegalArgumentException(name + ": expected " + length + " entries, got " + a.length);
        }
    }
}
