package sternhalma.tornado;

import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;

/**
 * TornadoVM kernel of the dense layers of docs/network.md ("Layers"): the policy head (a 1x1 convolution on the
 * 121 cell rows, no activation), {@code head_value.dense1} (ReLU) and {@code head_value.dense2} (tanh).
 *
 * <p>The input is {@code groups} groups of {@code inGroupRows} rows of {@code inSize} floats; the kernel uses the
 * first {@code groupRows} rows of each group and writes {@code groups * groupRows} output rows of {@code outSize}
 * floats, contiguous. For every group g, row r &lt; groupRows and output o:
 *
 * <pre>
 * out[g * groupRows + r][o] = act(bias[o] + sum over i of weight[o][i] * in[g * inGroupRows + r][i])
 * </pre>
 *
 * The policy head is {@code groups = batch, groupRows = 121, inGroupRows = 122} (the padding row is skipped), a
 * dense layer on vectors is {@code groupRows = inGroupRows = 1}. {@code act} is {@link #NONE}, {@link #RELU} or
 * {@link #TANH}. Sums run in float in ascending order of i, so results agree with
 * {@link sternhalma.nn.ReferenceOps#pointwise} only within the tolerance of docs/network.md.
 *
 * <p>The layers are small (at most 2 x 64, 32 x 64 or 3 x 32 weights), so one work item per output element is
 * the whole design; only the trunk convolutions ({@link KernelsHexConv}) have a tiled kernel.
 */
public final class KernelsDense {

    /** No activation (policy head). */
    public static final int NONE = 0;
    /** ReLU, {@code max(v, 0)}, NaN stays NaN ({@code head_value.dense1}). */
    public static final int RELU = 1;
    /** tanh ({@code head_value.dense2}), {@code DeviceMath.tanh}: exactly +-1 when saturated, NaN stays NaN. */
    public static final int TANH = 2;

    private KernelsDense() {
    }

    /** The kernel: one work item per (output row, output); see the class comment. Runs as Java if called. */
    public static void dense(FloatArray in, FloatArray weight, FloatArray bias, FloatArray out, int groups,
            int groupRows, int inGroupRows, int inSize, int outSize, int activation) {
        for (@Parallel int r = 0; r < groups * groupRows; r++) {
            for (@Parallel int o = 0; o < outSize; o++) {
                int group = r / groupRows;
                int inBase = (r + group * (inGroupRows - groupRows)) * inSize;
                float sum = 0f;
                for (int i = 0; i < inSize; i++) {
                    sum += weight.get(o * inSize + i) * in.get(inBase + i);
                }
                float v = sum + bias.get(o);
                if (activation == RELU) {
                    v = DeviceMath.relu(v);
                } else if (activation == TANH) {
                    v = DeviceMath.tanh(v);
                }
                out.set(r * outSize + o, v);
            }
        }
    }

    /** Multiply-adds of one call: {@code groups * groupRows * outSize * inSize}. */
    public static long multiplyAdds(int groups, int groupRows, int inSize, int outSize) {
        return (long) groups * groupRows * outSize * inSize;
    }
}
