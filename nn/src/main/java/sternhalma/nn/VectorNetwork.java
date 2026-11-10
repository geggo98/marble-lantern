package sternhalma.nn;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * The forward pass of docs/network.md, "Layers", with the Vector API ({@link VectorSupport#SPECIES}). It
 * agrees with the reference {@link Network} within {@link Network#agrees}; it is not bit-exact, because it
 * accumulates in float with fused multiply-adds.
 *
 * <h2>How it works</h2>
 *
 * <p><b>Padding instead of tail loops.</b> Let L be the lane count and T = 2 L the tile width. Inside this
 * class every activation has a row stride S = C rounded up to a multiple of T, and the hidden vector of the
 * value head has length H rounded up to a multiple of L. The extra channels ("tail lanes") have zero weights
 * and zero biases, so they are 0 after every layer, and the dot products of the heads run over whole vectors
 * only. The heads' padded weights are 0 as well, so a tail lane could not leak into an output even if it were
 * not 0 (a hand mutation test found that each of the two zero fills alone is enough). The convolutions read
 * only the real input channels of a row. The input of the network and its outputs keep the layout of
 * docs/network.md: the padding exists only in this class's own buffers and packed weights.
 *
 * <p><b>Hex convolution.</b> The weight [C_out][7 C_in] is repacked once into tiles of T output channels:
 * {@code packed[(tile * 7 C_in + j) * T + t] = weight[tile * T + t][j]}. For one tile and four cells at a
 * time, the inner loop runs over the input channels ch of one gather entry k: it loads two weight vectors
 * (contiguous, because j = k C_in + ch is the slow index of the tile), broadcasts the four input values
 * {@code in[gather(cell, k)][ch]} (contiguous in ch, channels last) and does eight fused multiply-adds into
 * eight accumulators, which hold the outputs (4 cells) x (T channels). No horizontal reduction is needed.
 * The four cells share every weight load. 121 = 4 * 30 + 1, so the last cell runs through a one-cell loop.
 * Bias, residual add and ReLU are applied when the accumulators are stored.
 *
 * <p><b>Heads.</b> The policy head and the two dense layers are short dot products along the padded,
 * contiguous channel dimension, with one horizontal reduction per output. They are below 0.1 % of the work.
 *
 * <p><b>State.</b> The constructor packs the weights and allocates every scratch buffer; {@link #forward}
 * allocates nothing. Unlike {@link Network}, this class reads the {@link Parameters} only in the constructor
 * and in {@link #reload}: after an in-place update of the parameters, call {@link #reload}. An instance is
 * <b>not thread-safe</b>, because its scratch buffers are fields; use one instance per thread. Misuse fails
 * loudly: a call of {@link #forward} or {@link #reload} that starts while another one runs on the same
 * instance throws {@link IllegalStateException} (an atomic busy flag, one compare-and-set per call), so
 * concurrent calls never give wrong values silently.
 */
public final class VectorNetwork implements ForwardPass {

    private static final VectorSpecies<Float> SPECIES = VectorSupport.SPECIES;
    private static final int LANES = SPECIES.length();
    /** Output channels per convolution tile: two vectors. */
    private static final int TILE = 2 * LANES;
    /** Cells per pass of the convolution's main loop. */
    private static final int CELL_BLOCK = 4;

    private static final int[] GATHER = HexGrid.gatherTable();

    private final Parameters parameters;
    private final NetworkConfig config;
    /** Row stride S of the internal activations: channels rounded up to a multiple of {@link #TILE}. */
    private final int stride;
    /** Length of the internal hidden vector: valueHidden rounded up to a multiple of {@link #LANES}. */
    private final int hiddenStride;

    // Packed weights; see the class comment. Biases are padded to the stride with zeros.
    private final float[] convInWeight;
    private final float[] convInBias;
    private final float[][] blockWeight;
    private final float[][] blockBias;
    private final float[] policyWeight;
    private final float[] policyBias;
    private final float[] dense1Weight;
    private final float[] dense1Bias;
    private final float[] dense2Weight;
    private final float[] dense2Bias;
    /** The scalars of the residual priors ({@link ResidualPriors}); 0 if the plane set has none. */
    private float priorBeta;
    private float priorK;

    /** True while a call of forward or reload runs; see {@link #enter}. */
    private final AtomicBoolean busy = new AtomicBoolean();

    // Scratch buffers, reused by every call.
    private final float[] x;
    private final float[] t;
    private final float[] u;
    private final float[] pooled;
    private final float[] hidden;

    /**
     * Packs the current parameters.
     *
     * @throws NullPointerException if parameters is null
     */
    public VectorNetwork(Parameters parameters) {
        this.parameters = Objects.requireNonNull(parameters, "parameters");
        this.config = parameters.config();
        int c = config.channels();
        stride = roundUp(c, TILE);
        hiddenStride = roundUp(config.valueHidden(), LANES);
        convInWeight = new float[stride * HexGrid.STENCIL * config.inputPlanes()];
        convInBias = new float[stride];
        blockWeight = new float[2 * config.blocks()][stride * HexGrid.STENCIL * c];
        blockBias = new float[2 * config.blocks()][stride];
        policyWeight = new float[Encoder.POLICY_CHANNELS * stride];
        policyBias = new float[Encoder.POLICY_CHANNELS];
        dense1Weight = new float[config.valueHidden() * stride];
        dense1Bias = new float[config.valueHidden()];
        dense2Weight = new float[config.players() * hiddenStride];
        dense2Bias = new float[config.players()];
        x = new float[HexGrid.ROWS * stride];
        t = new float[HexGrid.ROWS * stride];
        u = new float[HexGrid.ROWS * stride];
        pooled = new float[stride];
        hidden = new float[hiddenStride];
        reload();
    }

    @Override
    public NetworkConfig config() {
        return config;
    }

    /** False: the scratch buffers are fields. A concurrent call throws IllegalStateException. */
    @Override
    public boolean threadSafe() {
        return false;
    }

    /** The lane count L of the species in use; the tile width is 2 L. */
    public static int lanes() {
        return LANES;
    }

    /**
     * Packs the parameters again, so that in-place updates since the last packing take effect.
     *
     * @throws IllegalStateException if a call of {@link #forward} or {@link #reload} is running in another thread
     */
    public void reload() {
        enter("reload");
        try {
            pack();
        } finally {
            busy.set(false);
        }
    }

    /**
     * Marks the instance busy, or throws if it is busy already. The caller clears the flag when it is done; a
     * thread that gets the exception never touches the flag.
     */
    private void enter(String what) {
        if (!busy.compareAndSet(false, true)) {
            throw new IllegalStateException("VectorNetwork is not thread-safe: " + what
                    + " was called while another call on the same instance is running; use one instance per thread");
        }
    }

    private void pack() {
        int c = config.channels();
        packConv(data("conv_in.weight"), config.inputPlanes(), c, convInWeight);
        padRow(data("conv_in.bias"), 0, c, convInBias, 0);
        for (int i = 0; i < config.blocks(); i++) {
            for (int j = 0; j < 2; j++) {
                String conv = "block" + i + ".conv" + (j + 1);
                packConv(data(conv + ".weight"), c, c, blockWeight[2 * i + j]);
                padRow(data(conv + ".bias"), 0, c, blockBias[2 * i + j], 0);
            }
        }
        for (int o = 0; o < Encoder.POLICY_CHANNELS; o++) {
            padRow(data("head_policy.weight"), o * c, c, policyWeight, o * stride);
        }
        System.arraycopy(data("head_policy.bias"), 0, policyBias, 0, Encoder.POLICY_CHANNELS);
        int h = config.valueHidden();
        for (int o = 0; o < h; o++) {
            padRow(data("head_value.dense1.weight"), o * c, c, dense1Weight, o * stride);
        }
        System.arraycopy(data("head_value.dense1.bias"), 0, dense1Bias, 0, h);
        for (int o = 0; o < config.players(); o++) {
            padRow(data("head_value.dense2.weight"), o * h, h, dense2Weight, o * hiddenStride);
        }
        System.arraycopy(data("head_value.dense2.bias"), 0, dense2Bias, 0, config.players());
        if (config.planes().residualPriors()) {
            priorBeta = data(ResidualPriors.BETA)[0];
            priorK = data(ResidualPriors.K)[0];
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Positions are computed one after the other in this instance's scratch buffers; nothing is allocated.
     */
    @Override
    public void forward(float[] input, int batch, float[] policyLogits, float[] values) {
        int players = config.players();
        if (batch < 1) {
            throw new IllegalArgumentException("batch must be >= 1, got " + batch);
        }
        int planes = config.inputPlanes();
        int inputSize = config.inputSize();
        requireLength("input", input, (long) batch * inputSize);
        requireLength("policyLogits", policyLogits, (long) batch * Encoder.POLICY_SIZE);
        requireLength("values", values, (long) batch * players);
        // Check every position before writing anything, as the reference rejects a dirty padding row too.
        for (int b = 0; b < batch; b++) {
            int pad = b * inputSize + HexGrid.PADDING * planes;
            for (int plane = 0; plane < planes; plane++) {
                if (input[pad + plane] != 0) {
                    throw new IllegalArgumentException("padding row of input " + b + " is not zero in plane "
                            + plane + ": " + input[pad + plane]);
                }
            }
        }
        enter("forward");
        try {
            for (int b = 0; b < batch; b++) {
                forwardOne(input, b * inputSize, policyLogits, b * Encoder.POLICY_SIZE, values,
                        b * players);
            }
        } finally {
            busy.set(false);
        }
    }

    private void forwardOne(float[] input, int inputOffset, float[] policy, int policyOffset, float[] values,
            int valuesOffset) {
        int c = config.channels();
        float[] trunk = x;
        float[] mid = t;
        float[] next = u;
        conv(input, inputOffset, config.inputPlanes(), config.inputPlanes(), convInWeight, convInBias, trunk, null);
        for (int i = 0; i < config.blocks(); i++) {
            conv(trunk, 0, stride, c, blockWeight[2 * i], blockBias[2 * i], mid, null);
            conv(mid, 0, stride, c, blockWeight[2 * i + 1], blockBias[2 * i + 1], next, trunk);
            float[] swap = trunk;
            trunk = next;
            next = swap;
        }

        for (int cell = 0; cell < HexGrid.CELLS; cell++) {
            for (int o = 0; o < Encoder.POLICY_CHANNELS; o++) {
                policy[policyOffset + cell * Encoder.POLICY_CHANNELS + o] =
                        policyBias[o] + dot(trunk, cell * stride, policyWeight, o * stride, stride);
            }
        }
        boolean priors = config.planes().residualPriors();
        if (priors) {
            ResidualPriors.addPolicy(priorBeta, input, inputOffset, config.inputPlanes(), policy, policyOffset);
        }

        for (int v = 0; v < stride; v += LANES) {
            FloatVector sum = FloatVector.zero(SPECIES);
            for (int cell = 0; cell < HexGrid.CELLS; cell++) {
                sum = sum.add(FloatVector.fromArray(SPECIES, trunk, cell * stride + v));
            }
            sum.div(HexGrid.CELLS).intoArray(pooled, v);
        }
        // hidden[valueHidden..hiddenStride) is never written and stays 0 from the constructor.
        for (int o = 0; o < config.valueHidden(); o++) {
            hidden[o] = Math.max(dense1Bias[o] + dot(pooled, 0, dense1Weight, o * stride, stride), 0f);
        }
        for (int o = 0; o < config.players(); o++) {
            float pre = dense2Bias[o] + dot(hidden, 0, dense2Weight, o * hiddenStride, hiddenStride);
            if (priors) {
                pre += priorK * ResidualPriors.valueBase(input, inputOffset, config.inputPlanes(), o);
            }
            values[valuesOffset + o] = (float) Math.tanh(pre);
        }
    }

    /**
     * Hex convolution into an internal activation with stride {@link #stride}:
     * {@code out = ReLU(bias + conv(in) [+ residual])} for the 121 cell rows; tail lanes become 0.
     *
     * @param in         input activation, rows of {@code inStride} floats from {@code inOffset}; its padding
     *                   row must be zero (the gather reads it for off-board neighbours)
     * @param inChannels channels of {@code in} that take part (the first ones of each row)
     * @param weight     packed by {@link #packConv}
     * @param residual   null, or an activation with stride {@link #stride} that is added before the ReLU
     */
    private void conv(float[] in, int inOffset, int inStride, int inChannels, float[] weight, float[] bias,
            float[] out, float[] residual) {
        int width = HexGrid.STENCIL * inChannels;
        for (int tile = 0; tile < stride / TILE; tile++) {
            int o = tile * TILE;
            int tileBase = tile * width * TILE;
            FloatVector bias0 = FloatVector.fromArray(SPECIES, bias, o);
            FloatVector bias1 = FloatVector.fromArray(SPECIES, bias, o + LANES);
            int cell = 0;
            for (; cell + CELL_BLOCK <= HexGrid.CELLS; cell += CELL_BLOCK) {
                FloatVector a00 = bias0;
                FloatVector a01 = bias1;
                FloatVector a10 = bias0;
                FloatVector a11 = bias1;
                FloatVector a20 = bias0;
                FloatVector a21 = bias1;
                FloatVector a30 = bias0;
                FloatVector a31 = bias1;
                for (int k = 0; k < HexGrid.STENCIL; k++) {
                    int r0 = inOffset + GATHER[cell * HexGrid.STENCIL + k] * inStride;
                    int r1 = inOffset + GATHER[(cell + 1) * HexGrid.STENCIL + k] * inStride;
                    int r2 = inOffset + GATHER[(cell + 2) * HexGrid.STENCIL + k] * inStride;
                    int r3 = inOffset + GATHER[(cell + 3) * HexGrid.STENCIL + k] * inStride;
                    int w = tileBase + k * inChannels * TILE;
                    for (int ch = 0; ch < inChannels; ch++, w += TILE) {
                        FloatVector w0 = FloatVector.fromArray(SPECIES, weight, w);
                        FloatVector w1 = FloatVector.fromArray(SPECIES, weight, w + LANES);
                        FloatVector v0 = FloatVector.broadcast(SPECIES, in[r0 + ch]);
                        FloatVector v1 = FloatVector.broadcast(SPECIES, in[r1 + ch]);
                        FloatVector v2 = FloatVector.broadcast(SPECIES, in[r2 + ch]);
                        FloatVector v3 = FloatVector.broadcast(SPECIES, in[r3 + ch]);
                        a00 = w0.fma(v0, a00);
                        a01 = w1.fma(v0, a01);
                        a10 = w0.fma(v1, a10);
                        a11 = w1.fma(v1, a11);
                        a20 = w0.fma(v2, a20);
                        a21 = w1.fma(v2, a21);
                        a30 = w0.fma(v3, a30);
                        a31 = w1.fma(v3, a31);
                    }
                }
                store(out, residual, cell * stride + o, a00, a01);
                store(out, residual, (cell + 1) * stride + o, a10, a11);
                store(out, residual, (cell + 2) * stride + o, a20, a21);
                store(out, residual, (cell + 3) * stride + o, a30, a31);
            }
            for (; cell < HexGrid.CELLS; cell++) {
                FloatVector a0 = bias0;
                FloatVector a1 = bias1;
                for (int k = 0; k < HexGrid.STENCIL; k++) {
                    int r = inOffset + GATHER[cell * HexGrid.STENCIL + k] * inStride;
                    int w = tileBase + k * inChannels * TILE;
                    for (int ch = 0; ch < inChannels; ch++, w += TILE) {
                        FloatVector v = FloatVector.broadcast(SPECIES, in[r + ch]);
                        a0 = FloatVector.fromArray(SPECIES, weight, w).fma(v, a0);
                        a1 = FloatVector.fromArray(SPECIES, weight, w + LANES).fma(v, a1);
                    }
                }
                store(out, residual, cell * stride + o, a0, a1);
            }
        }
        // Row 121 of out is not written: no layer ever writes it, so it keeps the 0 of the allocation.
    }

    /** Stores one tile of a cell: adds the residual if there is one, applies ReLU. */
    private static void store(float[] out, float[] residual, int index, FloatVector a0, FloatVector a1) {
        if (residual != null) {
            a0 = a0.add(FloatVector.fromArray(SPECIES, residual, index));
            a1 = a1.add(FloatVector.fromArray(SPECIES, residual, index + LANES));
        }
        // FloatVector.max is lane-wise Math.max: NaN stays NaN and -0.0 becomes +0.0, as in ReferenceOps.relu.
        a0.max(0f).intoArray(out, index);
        a1.max(0f).intoArray(out, index + LANES);
    }

    /** Dot product of two ranges whose length is a multiple of {@link #LANES}. */
    private static float dot(float[] a, int aOffset, float[] b, int bOffset, int length) {
        FloatVector sum = FloatVector.zero(SPECIES);
        for (int i = 0; i < length; i += LANES) {
            sum = FloatVector.fromArray(SPECIES, a, aOffset + i)
                    .fma(FloatVector.fromArray(SPECIES, b, bOffset + i), sum);
        }
        return sum.reduceLanes(VectorOperators.ADD);
    }

    /**
     * Repacks a convolution weight [outChannels][7 inChannels] into tiles of {@link #TILE} output channels,
     * {@code packed[(tile * 7 inChannels + j) * TILE + t] = weight[(tile * TILE + t) * 7 inChannels + j]},
     * with zeros for the output channels from outChannels up to the stride.
     */
    private static void packConv(float[] weight, int inChannels, int outChannels, float[] packed) {
        int width = HexGrid.STENCIL * inChannels;
        int tiles = packed.length / (width * TILE);
        for (int tile = 0; tile < tiles; tile++) {
            for (int j = 0; j < width; j++) {
                for (int lane = 0; lane < TILE; lane++) {
                    int o = tile * TILE + lane;
                    packed[(tile * width + j) * TILE + lane] = o < outChannels ? weight[o * width + j] : 0f;
                }
            }
        }
    }

    /**
     * Copies one row of {@code length} floats into a padded row. The padding after it is never written by
     * anyone, so it keeps the 0 of the allocation.
     */
    private static void padRow(float[] src, int srcOffset, int length, float[] dst, int dstOffset) {
        System.arraycopy(src, srcOffset, dst, dstOffset, length);
    }

    private float[] data(String name) {
        return parameters.get(name).data();
    }

    private static int roundUp(int n, int multiple) {
        return (n + multiple - 1) / multiple * multiple;
    }

    private static void requireLength(String name, float[] a, long length) {
        Objects.requireNonNull(a, name);
        if (a.length < length) {
            throw new IllegalArgumentException(name + " needs " + length + " floats, has " + a.length);
        }
    }
}
