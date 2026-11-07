package sternhalma.tornado;

import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.common.TornadoDevice;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.enums.ProfilerMode;
import uk.ac.manchester.tornado.api.exceptions.TornadoExecutionPlanException;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;

/**
 * A fixed weight matrix applied to a batch of input vectors on a TornadoVM device:
 * {@code out[b][r] = sum over c of weights[r][c] * in[b][c]}, all arrays row-major.
 *
 * <p>The weights go to the device once, at the first execution, and stay there; later changes to
 * the caller's weight array are not seen. Inputs go to the device and outputs come back on every
 * {@link #apply} call. This is the data flow a batched forward pass needs: weights resident,
 * positions streamed.
 *
 * <p>The first {@code apply} compiles the kernel (hundreds of milliseconds); later calls reuse it.
 * The plan runs with the silent TornadoVM profiler, so {@link #lastProfile()} reports the cost of
 * each call. Not thread-safe. {@link #close()} frees the device memory.
 */
public final class BatchedMatMul implements AutoCloseable {

    private final int rows;
    private final int cols;
    private final int batch;
    private final FloatArray inputs;
    private final FloatArray outputs;
    private final TornadoExecutionPlan plan;
    private ExecutionProfile lastProfile;

    /**
     * @param weights {@code rows * cols} floats, row-major; copied
     * @param rows    output length per batch entry, at least 1
     * @param cols    input length per batch entry, at least 1
     * @param batch   number of input vectors per {@link #apply} call, at least 1
     * @param device  the device that runs the kernel
     * @throws IllegalArgumentException if a size is below 1 or {@code weights} has the wrong length
     */
    public BatchedMatMul(float[] weights, int rows, int cols, int batch, TornadoDevice device) {
        if (rows < 1 || cols < 1 || batch < 1) {
            throw new IllegalArgumentException("sizes must be positive: rows=" + rows + " cols=" + cols
                    + " batch=" + batch);
        }
        if (weights.length != rows * cols) {
            throw new IllegalArgumentException("weights: expected " + rows * cols + " floats, got " + weights.length);
        }
        this.rows = rows;
        this.cols = cols;
        this.batch = batch;
        FloatArray deviceWeights = FloatArray.fromArray(weights);
        this.inputs = new FloatArray(batch * cols);
        this.outputs = new FloatArray(batch * rows);
        TaskGraph graph = new TaskGraph("matmul")
                .transferToDevice(DataTransferMode.FIRST_EXECUTION, deviceWeights)
                .transferToDevice(DataTransferMode.EVERY_EXECUTION, inputs)
                .task("multiply", BatchedMatMul::multiply, deviceWeights, inputs, outputs, rows, cols, batch)
                .transferToHost(DataTransferMode.EVERY_EXECUTION, outputs);
        this.plan = new TornadoExecutionPlan(graph.snapshot()).withDevice(device).withProfiler(ProfilerMode.SILENT);
    }

    /**
     * The kernel: one work item per (batch entry, output row); the inner sum runs in ascending
     * column order. Called directly, it runs as plain Java.
     */
    public static void multiply(FloatArray weights, FloatArray in, FloatArray out, int rows, int cols, int batch) {
        for (@Parallel int b = 0; b < batch; b++) {
            for (@Parallel int r = 0; r < rows; r++) {
                float sum = 0f;
                for (int c = 0; c < cols; c++) {
                    sum += weights.get(r * cols + c) * in.get(b * cols + c);
                }
                out.set(b * rows + r, sum);
            }
        }
    }

    /**
     * Multiplies the batch on the device.
     *
     * @param in {@code batch * cols} floats, row-major
     * @return a new array of {@code batch * rows} floats, row-major
     * @throws IllegalArgumentException if {@code in} has the wrong length
     */
    public float[] apply(float[] in) {
        if (in.length != batch * cols) {
            throw new IllegalArgumentException("input: expected " + batch * cols + " floats, got " + in.length);
        }
        for (int i = 0; i < in.length; i++) {
            inputs.set(i, in[i]);
        }
        lastProfile = ExecutionProfile.of(plan.execute().getProfilerResult());
        return outputs.toHeapArray();
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

    /** Output length per batch entry. */
    public int rows() {
        return rows;
    }

    /** Input length per batch entry. */
    public int cols() {
        return cols;
    }

    /** Number of input vectors per {@link #apply} call. */
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
}
