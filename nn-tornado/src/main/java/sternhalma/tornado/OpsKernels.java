package sternhalma.tornado;

import sternhalma.nn.Encoder;
import sternhalma.nn.HexGrid;
import uk.ac.manchester.tornado.api.KernelContext;
import uk.ac.manchester.tornado.api.WorkerGrid;
import uk.ac.manchester.tornado.api.WorkerGrid1D;
import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;

/**
 * TornadoVM kernels for the layers after the convolutions (docs/network.md, "Layers", rows 3 to 7): the
 * residual add with ReLU, the policy head and the fused value head. The convolutions are in {@code Kernels...}.
 *
 * <p>All arrays use the layouts of docs/network.md, "Tensor layout", with a leading batch dimension: a trunk
 * activation is [batch][{@link HexGrid#ROWS}][channels], channels last. The heads write into one output
 * buffer per batch, laid out as {@link #outputSize}: first the policy logits [batch][121][2], then the values
 * [batch][players]. The host copies both parts with one transfer.
 *
 * <p>Sums run in float, in ascending index order inside one work item; the pooling sum is split into
 * {@link #VALUE_GROUP} / channels partial sums (see {@link #valueHead}). Called directly from Java, the
 * {@code @Parallel} kernels run as plain Java; {@link #valueHead} needs a TornadoVM grid.
 *
 * <p>Scalar arguments are fixed when a task is defined (docs/tornado.md), so a task graph is built for one
 * batch size and one network size.
 */
public final class OpsKernels {

    /**
     * Work-group size of {@link #valueHead}: one work-group of this many work items per position. It must be
     * set as the local size of the task's {@code WorkerGrid1D} (see {@link #valueHeadGrid}).
     */
    public static final int VALUE_GROUP = 256;

    /**
     * Largest channel count and hidden width {@link #valueHead} accepts: its local arrays have this size.
     * Equals {@code NetworkConfig.MAX_CHANNELS} and {@code NetworkConfig.MAX_VALUE_HIDDEN}.
     */
    public static final int MAX_WIDTH = 1024;

    private static final int ROWS = HexGrid.ROWS;
    private static final int CELLS = HexGrid.CELLS;
    private static final int PADDING = HexGrid.PADDING;
    private static final int POLICY_SIZE = Encoder.POLICY_SIZE;
    private static final int POLICY_CHANNELS = Encoder.POLICY_CHANNELS;

    private OpsKernels() {
    }

    /** Floats of the head output buffer of a batch: {@code batch * (121 * 2 + players)}. */
    public static int outputSize(int batch, int players) {
        return batch * (POLICY_SIZE + players);
    }

    /** Index of the first value in the head output buffer: {@code batch * 121 * 2}. */
    public static int valuesOffset(int batch) {
        return batch * POLICY_SIZE;
    }

    /**
     * The grid {@link #valueHead} needs: {@code batch} work-groups of {@link #VALUE_GROUP} work items.
     * Register it with {@code new GridScheduler(graphName + "." + taskName, grid)} and pass the scheduler to
     * {@code TornadoExecutionPlan.withGridScheduler}. Without it TornadoVM runs the kernel on its own default
     * grid, and the values are wrong without any error (measured: {@code OpsHeadsTest} fails on the values).
     * A scheduler that names no task of the plan fails at the first execution with
     * {@code TornadoRuntimeException: GridScheduler Name not registered in any task-graph}.
     */
    public static WorkerGrid valueHeadGrid(int batch) {
        WorkerGrid grid = new WorkerGrid1D(batch * VALUE_GROUP);
        grid.setLocalWork(VALUE_GROUP, 1, 1);
        return grid;
    }

    /**
     * Residual add and ReLU of block conv2 (docs/network.md, layer 3), element-wise over
     * [batch][122][channels]: {@code out = max(conv + skip, 0)} on the cell rows, and {@code out = 0} on the
     * padding row 121, whatever the inputs hold there. NaN stays NaN. {@code out} may be the same array as
     * {@code conv} or {@code skip} (each element is read and written by one work item only).
     *
     * @param conv     the output of conv2 with bias, before the add
     * @param skip     the block input x
     * @param out      the block output
     * @param batch    number of positions; the arrays hold at least {@code batch * 122 * channels} floats
     * @param channels channel count C
     */
    public static void residualAddRelu(FloatArray conv, FloatArray skip, FloatArray out, int batch, int channels) {
        for (@Parallel int i = 0; i < batch * ROWS * channels; i++) {
            int row = (i / channels) % ROWS;
            float v = conv.get(i) + skip.get(i);
            // DeviceMath.relu keeps NaN, like Math.max in ReferenceOps.relu (Metal's fmax would drop it).
            out.set(i, row == PADDING ? 0f : DeviceMath.relu(v));
        }
    }

    /**
     * Policy head (docs/network.md, layer 4): a 1x1 convolution from C channels to the "from" and "to" logit
     * of each of the 121 cells, with bias, no activation:
     * {@code out[b][cell][k] = bias[k] + sum over ch of weight[k][ch] * trunk[b][cell][ch]}. The padding row of
     * the trunk is not read. One work item per (position, logit).
     *
     * @param trunk    [batch][122][channels], the output of the last block
     * @param weight   {@code head_policy.weight}, [2][channels]
     * @param bias     {@code head_policy.bias}, [2]
     * @param out      the head output buffer; the logits go to its first {@code batch * 242} floats
     * @param batch    number of positions
     * @param channels channel count C
     */
    public static void policyHead(FloatArray trunk, FloatArray weight, FloatArray bias, FloatArray out, int batch,
            int channels) {
        for (@Parallel int b = 0; b < batch; b++) {
            for (@Parallel int j = 0; j < POLICY_SIZE; j++) {
                int cell = j / POLICY_CHANNELS;
                int k = j - cell * POLICY_CHANNELS;
                int in = b * ROWS * channels + cell * channels;
                int w = k * channels;
                float sum = bias.get(k);
                for (int ch = 0; ch < channels; ch++) {
                    sum += weight.get(w + ch) * trunk.get(in + ch);
                }
                out.set(b * POLICY_SIZE + j, sum);
            }
        }
    }

    /**
     * Value head (docs/network.md, layers 5 to 7), fused into one kernel with one work-group of
     * {@link #VALUE_GROUP} work items per position (work-group index = position):
     *
     * <ol>
     * <li>Mean over the 121 cell rows per channel, without the padding row. With C &lt;= {@link #VALUE_GROUP},
     * {@code parts = VALUE_GROUP / C} work items share a channel: work item t sums channel {@code t % C} over
     * the cells {@code t / C}, {@code t / C + parts}, ... (neighbouring work items read neighbouring floats),
     * and then one work item per channel adds the {@code parts} partial sums in local memory and divides by
     * 121. With C &gt; {@link #VALUE_GROUP}, each work item sums whole channels.</li>
     * <li>Dense C to H with bias and ReLU (NaN stays NaN), one work item per hidden unit.</li>
     * <li>Dense H to P with bias and tanh ({@code DeviceMath.tanh}: exactly +-1 when saturated, NaN stays NaN),
     * one work item per player, written to
     * {@code out[outOffset + b * players + p]}.</li>
     * </ol>
     *
     * <p>Local barriers separate the steps; every work item reaches every barrier.
     *
     * @param context   the TornadoVM kernel context; the grid must be {@code batch * VALUE_GROUP} work items in
     *                  work-groups of {@link #VALUE_GROUP}
     * @param trunk     [batch][122][channels], the output of the last block
     * @param w1        {@code head_value.dense1.weight}, [hidden][channels]
     * @param b1        {@code head_value.dense1.bias}, [hidden]
     * @param w2        {@code head_value.dense2.weight}, [players][hidden]
     * @param b2        {@code head_value.dense2.bias}, [players]
     * @param out       the head output buffer
     * @param outOffset index of the value of position 0, player 0 in {@code out} ({@link #valuesOffset})
     * @param channels  channel count C, 1 to {@link #MAX_WIDTH}
     * @param hidden    hidden width H, 1 to {@link #MAX_WIDTH}
     * @param players   number of players P
     */
    public static void valueHead(KernelContext context, FloatArray trunk, FloatArray w1, FloatArray b1,
            FloatArray w2, FloatArray b2, FloatArray out, int outOffset, int channels, int hidden, int players) {
        float[] partial = context.allocateFloatLocalArray(VALUE_GROUP);
        float[] pooled = context.allocateFloatLocalArray(MAX_WIDTH);
        float[] act = context.allocateFloatLocalArray(MAX_WIDTH);
        int t = context.localIdx;
        int b = context.groupIdx;
        int base = b * ROWS * channels;

        // 1. Mean over the cells: partial sums, then one sum per channel.
        int parts = channels >= VALUE_GROUP ? 1 : VALUE_GROUP / channels;
        for (int slot = t; slot < parts * channels; slot += VALUE_GROUP) {
            int ch = slot % channels;
            int part = slot / channels;
            float sum = 0f;
            for (int cell = part; cell < CELLS; cell += parts) {
                sum += trunk.get(base + cell * channels + ch);
            }
            if (parts == 1) {
                pooled[ch] = sum / CELLS;
            } else {
                partial[slot] = sum;
            }
        }
        context.localBarrier();
        if (parts > 1) {
            for (int ch = t; ch < channels; ch += VALUE_GROUP) {
                float sum = 0f;
                for (int part = 0; part < parts; part++) {
                    sum += partial[part * channels + ch];
                }
                pooled[ch] = sum / CELLS;
            }
        }
        context.localBarrier();

        // 2. Dense C -> H, bias, ReLU.
        for (int j = t; j < hidden; j += VALUE_GROUP) {
            float sum = b1.get(j);
            for (int ch = 0; ch < channels; ch++) {
                sum += w1.get(j * channels + ch) * pooled[ch];
            }
            act[j] = DeviceMath.relu(sum);
        }
        context.localBarrier();

        // 3. Dense H -> P, bias, tanh.
        for (int p = t; p < players; p += VALUE_GROUP) {
            float sum = b2.get(p);
            for (int j = 0; j < hidden; j++) {
                sum += w2.get(p * hidden + j) * act[j];
            }
            out.set(outOffset + b * players + p, DeviceMath.tanh(sum));
        }
    }
}
