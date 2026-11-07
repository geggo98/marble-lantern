package sternhalma.nn;

import sternhalma.engine.Geometry;

/**
 * The scalar reference operations of the network (docs/network.md, "Layers", "Numeric conventions").
 *
 * <p>Every operation works on one position, on flat float arrays with an offset. Dot products accumulate in
 * double, in ascending index order, and round to float once per output element. These methods are the oracle
 * for the Vector API and TornadoVM kernels; they favour obvious correctness over speed.
 *
 * <p>Array layouts are those of docs/network.md: an activation with C channels is [{@link HexGrid#ROWS}][C]
 * channels last, a convolution weight is [C_out][7 * C_in] with element (o, k, ch) at
 * {@code o * 7 * C_in + k * C_in + ch}, and a dense weight is [N_out][N_in].
 */
public final class ReferenceOps {

    private ReferenceOps() {
    }

    /**
     * Hex convolution with bias, no activation: for each cell c &lt; 121 and output channel o,
     * {@code out[c][o] = bias[o] + sum_k sum_ch weight[o][k * inChannels + ch] * in[gather(c, k)][ch]}, and
     * {@code out[121][o] = 0}. {@code in} and {@code out} must not overlap. The sum starts with the bias and
     * adds the terms in ascending order of (k, ch), in double.
     *
     * <p>The gather row is computed here from {@code Geometry.neighbor}, not read from {@link HexGrid}, on
     * purpose: the kernels upload {@link HexGrid#gatherTable()}, so an oracle that used the same table could
     * not catch a bug in it.
     *
     * @throws IllegalArgumentException if row 121 of {@code in} is not zero, or an array is too short
     */
    public static void hexConv(float[] in, int inOffset, int inChannels, float[] weight, float[] bias,
            int outChannels, float[] out, int outOffset) {
        int width = HexGrid.STENCIL * inChannels;
        requireLength("in", in, inOffset, HexGrid.ROWS * inChannels);
        requireLength("weight", weight, 0, outChannels * width);
        requireLength("bias", bias, 0, outChannels);
        requireLength("out", out, outOffset, HexGrid.ROWS * outChannels);
        for (int ch = 0; ch < inChannels; ch++) {
            float pad = in[inOffset + HexGrid.PADDING * inChannels + ch];
            if (pad != 0) {
                throw new IllegalArgumentException("padding row of the input is not zero in channel " + ch
                        + ": " + pad);
            }
        }
        for (int c = 0; c < HexGrid.CELLS; c++) {
            for (int o = 0; o < outChannels; o++) {
                double sum = bias[o];
                for (int k = 0; k < HexGrid.STENCIL; k++) {
                    int row = gatherRow(c, k);
                    for (int ch = 0; ch < inChannels; ch++) {
                        sum += (double) weight[o * width + k * inChannels + ch]
                                * in[inOffset + row * inChannels + ch];
                    }
                }
                out[outOffset + c * outChannels + o] = (float) sum;
            }
        }
        for (int o = 0; o < outChannels; o++) {
            out[outOffset + HexGrid.PADDING * outChannels + o] = 0f;
        }
    }

    /** Gather entry k of a cell: the cell for k = 0, else its neighbour in direction k - 1 or the padding row. */
    private static int gatherRow(int cell, int k) {
        if (k == 0) {
            return cell;
        }
        int n = Geometry.neighbor(cell, k - 1);
        return n < 0 ? HexGrid.PADDING : n;
    }

    /**
     * Dense layer applied to each of the first {@code rows} rows (a 1x1 convolution):
     * {@code out[row][o] = bias[o] + sum_i weight[o][i] * in[row][i]}. Rows beyond {@code rows} are not
     * touched. {@code in} and {@code out} must not overlap. The sum starts with the bias and adds the terms in
     * ascending order of i, in double.
     *
     * @throws IllegalArgumentException if an array is too short
     */
    public static void pointwise(float[] in, int inOffset, int rows, int inChannels, float[] weight,
            float[] bias, int outChannels, float[] out, int outOffset) {
        requireLength("in", in, inOffset, rows * inChannels);
        requireLength("weight", weight, 0, outChannels * inChannels);
        requireLength("bias", bias, 0, outChannels);
        requireLength("out", out, outOffset, rows * outChannels);
        for (int row = 0; row < rows; row++) {
            for (int o = 0; o < outChannels; o++) {
                double sum = bias[o];
                for (int i = 0; i < inChannels; i++) {
                    sum += (double) weight[o * inChannels + i] * in[inOffset + row * inChannels + i];
                }
                out[outOffset + row * outChannels + o] = (float) sum;
            }
        }
    }

    /** A dense layer on one vector; the same as {@link #pointwise} with one row. */
    public static void dense(float[] in, int inOffset, int inSize, float[] weight, float[] bias, int outSize,
            float[] out, int outOffset) {
        pointwise(in, inOffset, 1, inSize, weight, bias, outSize, out, outOffset);
    }

    /** In place: {@code a[i] = max(a[i], 0)} for i in [offset, offset + length). */
    public static void relu(float[] a, int offset, int length) {
        for (int i = offset; i < offset + length; i++) {
            // Math.max keeps NaN (so a broken input is not hidden) and turns -0.0f into +0.0f.
            a[i] = Math.max(a[i], 0f);
        }
    }

    /** In place: {@code a[aOffset + i] += b[bOffset + i]} for i in [0, length). */
    public static void add(float[] a, int aOffset, float[] b, int bOffset, int length) {
        for (int i = 0; i < length; i++) {
            a[aOffset + i] += b[bOffset + i];
        }
    }

    /**
     * Mean over the 121 cell rows per channel, without the padding row: {@code out[ch] = (sum over c &lt; 121
     * of in[c][ch]) / 121}, summed in double.
     */
    public static void meanOverCells(float[] in, int inOffset, int channels, float[] out, int outOffset) {
        requireLength("in", in, inOffset, HexGrid.CELLS * channels);
        requireLength("out", out, outOffset, channels);
        for (int ch = 0; ch < channels; ch++) {
            double sum = 0;
            for (int c = 0; c < HexGrid.CELLS; c++) {
                sum += in[inOffset + c * channels + ch];
            }
            out[outOffset + ch] = (float) (sum / HexGrid.CELLS);
        }
    }

    /** In place: {@code a[i] = (float) Math.tanh(a[i])} for i in [offset, offset + length). */
    public static void tanh(float[] a, int offset, int length) {
        for (int i = offset; i < offset + length; i++) {
            a[i] = (float) Math.tanh(a[i]);
        }
    }

    private static void requireLength(String name, float[] a, int offset, int length) {
        if (offset < 0 || length < 0 || (long) offset + length > a.length) {
            throw new IllegalArgumentException(name + ": need " + length + " floats from offset " + offset
                    + ", array has " + a.length);
        }
    }
}
