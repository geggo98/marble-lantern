package sternhalma.nn;

import java.util.Objects;

/**
 * The scalar CPU reference forward pass (docs/network.md, "Layers"), built from {@link ReferenceOps}.
 *
 * <p>This class is the test oracle for every faster implementation. A faster path agrees with it if every
 * policy logit and every value satisfies {@code |fast - ref| <= 1e-4 + 1e-3 * |ref|}.
 *
 * <p>The network reads the parameter arrays on every call, so in-place updates of the {@link Parameters}
 * take effect at the next call. A call allocates its own scratch buffers; concurrent calls are safe as long
 * as no thread modifies the parameters at the same time.
 */
public final class Network implements ForwardPass {

    /** Relative tolerance of docs/network.md, "Numeric conventions". */
    public static final double RELATIVE_TOLERANCE = 1e-3;
    /** Absolute tolerance of docs/network.md, "Numeric conventions". */
    public static final double ABSOLUTE_TOLERANCE = 1e-4;

    private final Parameters parameters;

    /**
     * @throws NullPointerException if parameters is null
     */
    public Network(Parameters parameters) {
        this.parameters = Objects.requireNonNull(parameters, "parameters");
    }

    @Override
    public NetworkConfig config() {
        return parameters.config();
    }

    /** True: every call allocates its own scratch buffers. */
    @Override
    public boolean threadSafe() {
        return true;
    }

    /** Whether a fast-path result agrees with the reference value {@code ref} (docs/network.md). */
    public static boolean agrees(double fast, double ref) {
        return Math.abs(fast - ref) <= ABSOLUTE_TOLERANCE + RELATIVE_TOLERANCE * Math.abs(ref);
    }

    /**
     * Forward pass for a batch of encoded positions. Each position is computed on its own, in batch order,
     * with fresh scratch buffers.
     *
     * @param input        {@code batch * config().inputSize()} floats from {@link Encoder#encode}; row 121 of
     *                     each position must be zero
     * @param batch        number of positions, &gt;= 1
     * @param policyLogits output, {@code batch * Encoder.POLICY_SIZE} floats: [batch][121][2] with "from" in
     *                     channel 0 and "to" in channel 1, canonical cells
     * @param values       output, {@code batch * players} floats: [batch][P], relative players (mover first),
     *                     each in [-1, 1]
     * @throws IllegalArgumentException if batch is less than 1, an array is shorter than stated, or row 121 of
     *                                  an input is not zero
     */
    @Override
    public void forward(float[] input, int batch, float[] policyLogits, float[] values) {
        int players = config().players();
        if (batch < 1) {
            throw new IllegalArgumentException("batch must be >= 1, got " + batch);
        }
        requireLength("input", input, (long) batch * config().inputSize());
        requireLength("policyLogits", policyLogits, (long) batch * Encoder.POLICY_SIZE);
        requireLength("values", values, (long) batch * players);
        for (int b = 0; b < batch; b++) {
            forwardOne(input, b * config().inputSize(), policyLogits, b * Encoder.POLICY_SIZE, values,
                    b * players);
        }
    }

    /** One position: the layer table of docs/network.md, top to bottom. */
    private void forwardOne(float[] input, int inputOffset, float[] policy, int policyOffset, float[] values,
            int valuesOffset) {
        NetworkConfig config = config();
        int c = config.channels();
        int h = config.valueHidden();
        int p = config.players();
        int activation = HexGrid.ROWS * c;

        // x: the trunk activation; t, u: scratch for the two convolutions of a block.
        float[] x = new float[activation];
        float[] t = new float[activation];
        float[] u = new float[activation];

        // 1. conv_in, bias, ReLU. Row 121 is written as 0 and ReLU keeps it 0.
        ReferenceOps.hexConv(input, inputOffset, config.inputPlanes(), data("conv_in.weight"), data("conv_in.bias"),
                c, x, 0);
        ReferenceOps.relu(x, 0, activation);

        // 2. and 3. residual blocks: x = ReLU(conv2(ReLU(conv1(x))) + x).
        for (int i = 0; i < config.blocks(); i++) {
            String block = "block" + i;
            ReferenceOps.hexConv(x, 0, c, data(block + ".conv1.weight"), data(block + ".conv1.bias"), c, t, 0);
            ReferenceOps.relu(t, 0, activation);
            ReferenceOps.hexConv(t, 0, c, data(block + ".conv2.weight"), data(block + ".conv2.bias"), c, u, 0);
            ReferenceOps.add(u, 0, x, 0, activation);
            ReferenceOps.relu(u, 0, activation);
            float[] swap = x;
            x = u;
            u = swap;
        }

        // 4. policy head: 1x1 convolution on the 121 cell rows, no activation.
        ReferenceOps.pointwise(x, 0, HexGrid.CELLS, c, data("head_policy.weight"), data("head_policy.bias"),
                Encoder.POLICY_CHANNELS, policy, policyOffset);
        boolean priors = config.planes().residualPriors();
        if (priors) {
            ResidualPriors.addPolicy(data(ResidualPriors.BETA)[0], input, inputOffset, config.inputPlanes(), policy,
                    policyOffset);
        }

        // 5. to 7. value head: mean over cells, dense + ReLU, dense + tanh.
        float[] pooled = new float[c];
        ReferenceOps.meanOverCells(x, 0, c, pooled, 0);
        float[] hidden = new float[h];
        ReferenceOps.dense(pooled, 0, c, data("head_value.dense1.weight"), data("head_value.dense1.bias"), h,
                hidden, 0);
        ReferenceOps.relu(hidden, 0, h);
        ReferenceOps.dense(hidden, 0, h, data("head_value.dense2.weight"), data("head_value.dense2.bias"), p,
                values, valuesOffset);
        if (priors) {
            // The linear evaluation enters before the tanh, so that k = 0.5 and a zero network give tanh(logit / 2).
            float k = data(ResidualPriors.K)[0];
            for (int i = 0; i < p; i++) {
                values[valuesOffset + i] += k * ResidualPriors.valueBase(input, inputOffset, config.inputPlanes(), i);
            }
        }
        ReferenceOps.tanh(values, valuesOffset, p);
    }

    private float[] data(String name) {
        return parameters.get(name).data();
    }

    private static void requireLength(String name, float[] a, long length) {
        Objects.requireNonNull(a, name);
        if (a.length < length) {
            throw new IllegalArgumentException(name + " needs " + length + " floats, has " + a.length);
        }
    }
}
