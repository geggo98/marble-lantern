package sternhalma.tornado;

import java.util.concurrent.atomic.AtomicInteger;

import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.common.TornadoDevice;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.enums.ProfilerMode;
import uk.ac.manchester.tornado.api.exceptions.TornadoExecutionPlanException;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;

/**
 * One dense layer of fixed shape on a TornadoVM device; see {@link KernelsDense} for the computation and the
 * meaning of groups and rows.
 *
 * <p>Weight and bias go to the device once, at the first {@link #apply}; input and output move on every call.
 * The plan runs with the silent profiler ({@link #lastProfile()}). Not thread-safe. {@link #close()} frees the
 * device memory.
 */
public final class KernelsDenseLayer implements AutoCloseable {

    private static final AtomicInteger INSTANCES = new AtomicInteger();

    private final int inLength;
    private final FloatArray input;
    private final FloatArray output;
    private final TornadoExecutionPlan plan;
    private ExecutionProfile lastProfile;

    /**
     * @param weight      [outSize][inSize], copied
     * @param bias        [outSize], copied
     * @param groups      groups per call (the batch), at least 1
     * @param groupRows   rows used per group, at least 1
     * @param inGroupRows rows per group in the input, at least {@code groupRows}
     * @param inSize      at least 1
     * @param outSize     at least 1
     * @param activation  {@link KernelsDense#NONE}, {@link KernelsDense#RELU} or {@link KernelsDense#TANH}
     * @param device      the device that runs the kernel
     * @throws IllegalArgumentException if a size is out of range, an array has the wrong length or the
     *                                  activation is unknown
     */
    public KernelsDenseLayer(float[] weight, float[] bias, int groups, int groupRows, int inGroupRows, int inSize,
            int outSize, int activation, TornadoDevice device) {
        if (groups < 1 || groupRows < 1 || inGroupRows < groupRows || inSize < 1 || outSize < 1) {
            throw new IllegalArgumentException("bad sizes: groups=" + groups + " groupRows=" + groupRows
                    + " inGroupRows=" + inGroupRows + " inSize=" + inSize + " outSize=" + outSize);
        }
        if (activation != KernelsDense.NONE && activation != KernelsDense.RELU && activation != KernelsDense.TANH) {
            throw new IllegalArgumentException("unknown activation " + activation);
        }
        if (weight.length != outSize * inSize || bias.length != outSize) {
            throw new IllegalArgumentException("weight/bias: expected " + outSize * inSize + "/" + outSize
                    + " floats, got " + weight.length + "/" + bias.length);
        }
        this.inLength = groups * inGroupRows * inSize;
        FloatArray deviceWeight = FloatArray.fromArray(weight);
        FloatArray deviceBias = FloatArray.fromArray(bias);
        this.input = new FloatArray(inLength);
        this.output = new FloatArray(groups * groupRows * outSize);
        TaskGraph graph = new TaskGraph("dense" + INSTANCES.incrementAndGet())
                .transferToDevice(DataTransferMode.FIRST_EXECUTION, deviceWeight, deviceBias)
                .transferToDevice(DataTransferMode.EVERY_EXECUTION, input)
                .task("dense", KernelsDense::dense, input, deviceWeight, deviceBias, output, groups, groupRows,
                        inGroupRows, inSize, outSize, activation)
                .transferToHost(DataTransferMode.EVERY_EXECUTION, output);
        this.plan = new TornadoExecutionPlan(graph.snapshot()).withDevice(device).withProfiler(ProfilerMode.SILENT);
    }

    /**
     * @param in {@code groups * inGroupRows * inSize} floats
     * @return a new array of {@code groups * groupRows * outSize} floats
     * @throws IllegalArgumentException if {@code in} has the wrong length
     */
    public float[] apply(float[] in) {
        if (in.length != inLength) {
            throw new IllegalArgumentException("input: expected " + inLength + " floats, got " + in.length);
        }
        KernelsHexConvLayer.copy(in, input);
        lastProfile = ExecutionProfile.of(plan.execute().getProfilerResult());
        return output.toHeapArray();
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

    /** Frees the device memory of the plan. */
    @Override
    public void close() {
        try {
            plan.close();
        } catch (TornadoExecutionPlanException e) {
            throw new IllegalStateException("could not release the execution plan", e);
        }
    }
}
