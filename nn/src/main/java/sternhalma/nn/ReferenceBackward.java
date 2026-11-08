package sternhalma.nn;

/**
 * The backward rules of the network operations (docs/training.md, "Gradients", "Operations").
 *
 * <p>Notation as in the document: {@code dX} is the gradient of the loss with respect to {@code X}. The
 * activations and weights are the float arrays of the forward pass; the gradients are {@code double} arrays
 * with the same layout. Every method <b>adds</b> to its gradient outputs ({@code +=}), never overwrites: the
 * caller zeroes a buffer once and several operations may feed the same one (the trunk output gets the mean and
 * the policy head). The one exception is row 121, the padding row: an operation that owns a whole activation
 * gradient sets that row to 0 and never adds to it, because rows 121 of the forward activations are constants
 * and no gradient flows through them.
 *
 * <p>These are the oracle for the TornadoVM kernels of Part B. They favour the obvious transcription of the
 * forward sum over speed. The hex convolution input gradient is the scatter form; {@link
 * #permutedWeights} and {@link #hexConvInputGradientGather} are the gather form that Part B uses, kept here
 * so that a test can compare the two.
 */
public final class ReferenceBackward {

    /**
     * The involution of the stencil index: {@code PI[k]} is the entry of the neighbour cell that points back to
     * the cell. Entry 0 is the cell itself; entries 1 + d and 1 + (d ^ 1) are opposite directions.
     */
    static final int[] PI = {0, 2, 1, 4, 3, 6, 5};

    private ReferenceBackward() {
    }

    /** In place: {@code d[i] = out[i] > 0 ? d[i] : 0}. Uses the stored ReLU output; at 0 the derivative is 0. */
    public static void reluBackward(float[] out, int outOffset, double[] d, int dOffset, int length) {
        for (int i = 0; i < length; i++) {
            if (!(out[outOffset + i] > 0)) {
                d[dOffset + i] = 0;
            }
        }
    }

    /** In place: {@code d[i] *= 1 - v[i]^2} for the float outputs v of tanh (not the pre-activations). */
    public static void tanhBackward(float[] v, int vOffset, double[] d, int dOffset, int length) {
        for (int i = 0; i < length; i++) {
            double value = v[vOffset + i];
            d[dOffset + i] *= 1 - value * value;
        }
    }

    /**
     * Residual add {@code out = a + b}: both inputs receive the output gradient,
     * {@code dA += dOut} and {@code dB += dOut}.
     */
    public static void addBackward(double[] dOut, int dOutOffset, double[] dA, int aOffset, double[] dB,
            int bOffset, int length) {
        for (int i = 0; i < length; i++) {
            dA[aOffset + i] += dOut[dOutOffset + i];
            dB[bOffset + i] += dOut[dOutOffset + i];
        }
    }

    /**
     * Backward of {@link ReferenceOps#pointwise} on the first {@code rows} rows (the 1x1 convolution):
     * {@code dW[o][i] += sum_r dOut[r][o] in[r][i]}, {@code db[o] += sum_r dOut[r][o]},
     * {@code dIn[r][i] += sum_o W[o][i] dOut[r][o]}. Rows beyond {@code rows} are not touched.
     *
     * @param dIn gradient of the input, or null if it is not needed
     */
    public static void pointwiseBackward(float[] in, int inOffset, int rows, int inChannels, float[] weight,
            int outChannels, double[] dOut, int dOutOffset, double[] dWeight, double[] dBias, double[] dIn,
            int dInOffset) {
        for (int r = 0; r < rows; r++) {
            for (int o = 0; o < outChannels; o++) {
                double g = dOut[dOutOffset + r * outChannels + o];
                dBias[o] += g;
                for (int i = 0; i < inChannels; i++) {
                    dWeight[o * inChannels + i] += g * in[inOffset + r * inChannels + i];
                    if (dIn != null) {
                        dIn[dInOffset + r * inChannels + i] += weight[o * inChannels + i] * g;
                    }
                }
            }
        }
    }

    /** Backward of {@link ReferenceOps#dense}: {@link #pointwiseBackward} with one row. */
    public static void denseBackward(float[] in, int inOffset, int inSize, float[] weight, int outSize,
            double[] dOut, int dOutOffset, double[] dWeight, double[] dBias, double[] dIn, int dInOffset) {
        pointwiseBackward(in, inOffset, 1, inSize, weight, outSize, dOut, dOutOffset, dWeight, dBias, dIn,
                dInOffset);
    }

    /**
     * Backward of {@link ReferenceOps#meanOverCells}: {@code dIn[c][ch] += dOut[ch] / 121} for the 121 cell
     * rows. Row 121 of {@code dIn} is set to 0.
     */
    public static void meanBackward(double[] dOut, int dOutOffset, int channels, double[] dIn, int dInOffset) {
        for (int c = 0; c < HexGrid.CELLS; c++) {
            for (int ch = 0; ch < channels; ch++) {
                dIn[dInOffset + c * channels + ch] += dOut[dOutOffset + ch] / HexGrid.CELLS;
            }
        }
        clearPadding(dIn, dInOffset, channels);
    }

    /**
     * Backward of {@link ReferenceOps#hexConv}, scatter form: loop over the cells c and the stencil entries k
     * and add into row g(c, k) of the input gradient. Row 121 of {@code dz} is ignored (it is 0); the
     * contributions to row 121 of the input are dropped, and row 121 of {@code dIn} is set to 0.
     *
     * <pre>
     * db[o]              += sum_c dz[c][o]
     * dW[o][k*Cin + ch]  += sum_c dz[c][o] in[g(c,k)][ch]
     * dIn[g(c,k)][ch]    += sum_o dz[c][o] W[o][k*Cin + ch]
     * </pre>
     *
     * <p>The gather row comes from {@code Geometry.neighbor} (as in {@link ReferenceOps#hexConv}), not from the
     * {@link HexGrid} table. Terms with {@code dz == 0} are skipped: they add 0, and about half of the
     * gradients behind a ReLU are 0.
     *
     * @param dIn gradient of the input, or null if it is not needed (the input planes of {@code conv_in})
     */
    public static void hexConvBackward(float[] in, int inOffset, int inChannels, float[] weight,
            int outChannels, double[] dz, int dzOffset, double[] dWeight, double[] dBias, double[] dIn,
            int dInOffset) {
        int width = HexGrid.STENCIL * inChannels;
        for (int c = 0; c < HexGrid.CELLS; c++) {
            for (int o = 0; o < outChannels; o++) {
                dBias[o] += dz[dzOffset + c * outChannels + o];
            }
            for (int k = 0; k < HexGrid.STENCIL; k++) {
                int row = ReferenceOps.gatherRow(c, k);
                boolean scatter = dIn != null && row != HexGrid.PADDING;
                for (int o = 0; o < outChannels; o++) {
                    double g = dz[dzOffset + c * outChannels + o];
                    if (g == 0) {
                        continue;
                    }
                    int w = o * width + k * inChannels;
                    for (int ch = 0; ch < inChannels; ch++) {
                        dWeight[w + ch] += g * in[inOffset + row * inChannels + ch];
                        if (scatter) {
                            dIn[dInOffset + row * inChannels + ch] += g * weight[w + ch];
                        }
                    }
                }
            }
        }
        if (dIn != null) {
            clearPadding(dIn, dInOffset, inChannels);
        }
    }

    /**
     * The permuted and transposed weight of the gather form (docs/training.md, "Hex convolution"):
     * {@code W'[ch][k'*Cout + o] = W[o][pi(k')*Cin + ch]}, shape [Cin][7 * Cout].
     */
    public static float[] permutedWeights(float[] weight, int inChannels, int outChannels) {
        int width = HexGrid.STENCIL * inChannels;
        int widthOut = HexGrid.STENCIL * outChannels;
        float[] result = new float[inChannels * widthOut];
        for (int ch = 0; ch < inChannels; ch++) {
            for (int k = 0; k < HexGrid.STENCIL; k++) {
                for (int o = 0; o < outChannels; o++) {
                    result[ch * widthOut + k * outChannels + o] = weight[o * width + PI[k] * inChannels + ch];
                }
            }
        }
        return result;
    }

    /**
     * The input gradient of the hex convolution as a gather (docs/training.md, "Hex convolution"): a forward
     * hex convolution of {@code dz} with {@link #permutedWeights} and zero bias,
     * {@code dIn[r][ch] = sum_k' sum_o dz[g(r,k')][o] W'[ch][k'*Cout + o]}, using the {@link HexGrid} table.
     * Overwrites rows 0 to 120 of {@code dIn} and sets row 121 to 0; row 121 of {@code dz} is read as 0.
     */
    public static void hexConvInputGradientGather(double[] dz, int dzOffset, int outChannels,
            float[] permutedWeight, int inChannels, double[] dIn, int dInOffset) {
        int widthOut = HexGrid.STENCIL * outChannels;
        for (int r = 0; r < HexGrid.CELLS; r++) {
            for (int ch = 0; ch < inChannels; ch++) {
                double sum = 0;
                for (int k = 0; k < HexGrid.STENCIL; k++) {
                    int row = HexGrid.gather(r, k);
                    if (row == HexGrid.PADDING) {
                        continue;
                    }
                    for (int o = 0; o < outChannels; o++) {
                        sum += dz[dzOffset + row * outChannels + o]
                                * permutedWeight[ch * widthOut + k * outChannels + o];
                    }
                }
                dIn[dInOffset + r * inChannels + ch] = sum;
            }
        }
        clearPadding(dIn, dInOffset, inChannels);
    }

    private static void clearPadding(double[] a, int offset, int channels) {
        for (int ch = 0; ch < channels; ch++) {
            a[offset + HexGrid.PADDING * channels + ch] = 0;
        }
    }
}
