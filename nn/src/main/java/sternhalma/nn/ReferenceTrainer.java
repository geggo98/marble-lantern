package sternhalma.nn;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The scalar CPU reference trainer, Part A of docs/training.md: loss and exact gradients of every parameter of
 * the network for a {@link TrainingBatch}. It is the oracle for the GPU trainer, as {@link Network} is the
 * oracle for the faster forward passes.
 *
 * <p>Per sample the trainer runs its own forward pass, built from {@link ReferenceOps} in the same order as
 * {@link Network} and keeping every activation that the backward pass needs, then the backward pass in the
 * order of docs/training.md, "Order of the backward pass". The gradients of the parameters are sums in
 * double, one array per tensor with the layout of the tensor, over all samples and microbatches that were
 * accumulated since {@link #zeroGradients()}. They are rounded to float only when an optimiser reads them.
 *
 * <p>The loss that the gradients belong to is the data term of docs/training.md, "Loss":
 * {@code (1/N) sum_s (wp_s Lp_s + c_v wv_s Lv_s)}, with N the size of the whole batch. The L2 term is not part
 * of it: the optimiser adds {@code lambda * theta} (SGD) or decays the weights (AdamW).
 *
 * <p>Microbatches run in parallel, each into its own gradient buffers; the buffers are added to the trainer's
 * in microbatch order, so the result does not depend on thread timing. The trainer reads the parameter arrays
 * on every call, like {@link Network}. Not thread-safe: one call at a time.
 */
public final class ReferenceTrainer {

    /**
     * Gradients of the parameters: one double array per tensor, in {@link Parameters#shapes} order, with the
     * layout of the tensor.
     */
    public static final class Gradients {
        private final Map<String, double[]> tensors = new LinkedHashMap<>();

        Gradients(NetworkConfig config) {
            for (Map.Entry<String, int[]> e : Parameters.shapes(config).entrySet()) {
                tensors.put(e.getKey(), new double[(int) Tensor.elementCount(e.getValue())]);
            }
        }

        /** The gradient array of a tensor; it is live, not a copy. */
        public double[] get(String name) {
            double[] g = tensors.get(name);
            if (g == null) {
                throw new IllegalArgumentException("no gradient named " + name);
            }
            return g;
        }

        /** All gradient arrays in parameter order; live, not copies. */
        public Map<String, double[]> tensors() {
            return java.util.Collections.unmodifiableMap(tensors);
        }

        void clear() {
            for (double[] g : tensors.values()) {
                Arrays.fill(g, 0);
            }
        }

        void add(Gradients other) {
            for (Map.Entry<String, double[]> e : tensors.entrySet()) {
                double[] a = e.getValue();
                double[] b = other.tensors.get(e.getKey());
                for (int i = 0; i < a.length; i++) {
                    a[i] += b[i];
                }
            }
        }

        /** The name of the first tensor with a non-finite element, or null. */
        String firstNonFinite() {
            for (Map.Entry<String, double[]> e : tensors.entrySet()) {
                for (double v : e.getValue()) {
                    if (!Double.isFinite(v)) {
                        return e.getKey();
                    }
                }
            }
            return null;
        }
    }

    /**
     * The terms of the batch loss that one call of {@code accumulate} covered, each already divided by N:
     * {@code loss = policyLoss + valueLoss}, and {@code kl} is {@code (1/N) sum wp_s (Lp_s - H_s)}.
     */
    public record Result(double loss, double policyLoss, double valueLoss, double kl) {
    }

    private final Parameters parameters;
    private final double valueCoefficient;
    private final Gradients gradients;

    /**
     * @param parameters       the parameters to differentiate; read on every call
     * @param valueCoefficient c_v of the loss, 1 by default in docs/training.md
     */
    public ReferenceTrainer(Parameters parameters, double valueCoefficient) {
        this.parameters = Objects.requireNonNull(parameters, "parameters");
        if (!(valueCoefficient >= 0) || !Double.isFinite(valueCoefficient)) {
            throw new IllegalArgumentException("valueCoefficient must be >= 0 and finite, got "
                    + valueCoefficient);
        }
        this.valueCoefficient = valueCoefficient;
        this.gradients = new Gradients(parameters.config());
    }

    public NetworkConfig config() {
        return parameters.config();
    }

    /** The accumulated gradients; live. */
    public Gradients gradients() {
        return gradients;
    }

    /** Sets all accumulated gradients to 0. */
    public void zeroGradients() {
        gradients.clear();
    }

    /** {@link #accumulate(TrainingBatch, int[], int)} with microbatches of {@code microbatch} samples. */
    public Result accumulate(TrainingBatch batch, int microbatch, int threads) {
        if (microbatch < 1) {
            throw new IllegalArgumentException("microbatch must be >= 1, got " + microbatch);
        }
        int count = (batch.size() + microbatch - 1) / microbatch;
        int[] sizes = new int[count];
        for (int i = 0; i < count; i++) {
            sizes[i] = Math.min(microbatch, batch.size() - i * microbatch);
        }
        return accumulate(batch, sizes, threads);
    }

    /**
     * Adds the gradient of the batch loss to the accumulated gradients. The samples are split into consecutive
     * microbatches of the given sizes; each microbatch is computed on its own, sample by sample in ascending
     * order, and the microbatch results are added in microbatch order. Every sample is divided by the size of
     * the whole batch.
     *
     * @param sizes   microbatch sizes, each &gt;= 1, summing to the batch size
     * @param threads number of threads, &gt;= 1; 1 runs in the calling thread
     * @throws IllegalArgumentException if the batch does not match the config, or sizes or threads are wrong
     * @throws IllegalStateException    if a loss or a gradient element is NaN or infinite; the accumulated
     *                                  gradients are undefined afterwards
     */
    public Result accumulate(TrainingBatch batch, int[] sizes, int threads) {
        Objects.requireNonNull(batch, "batch");
        if (batch.players() != config().players()) {
            throw new IllegalArgumentException("batch has " + batch.players() + " players, network "
                    + config().players());
        }
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be >= 1, got " + threads);
        }
        long total = 0;
        for (int size : sizes) {
            if (size < 1) {
                throw new IllegalArgumentException("microbatch size must be >= 1, got " + size);
            }
            total += size;
        }
        if (total != batch.size()) {
            throw new IllegalArgumentException("microbatch sizes sum to " + total + ", batch has " + batch.size());
        }
        int[] starts = new int[sizes.length];
        for (int i = 1; i < sizes.length; i++) {
            starts[i] = starts[i - 1] + sizes[i - 1];
        }
        double[] sums = new double[3];
        if (threads == 1 || sizes.length == 1) {
            for (int i = 0; i < sizes.length; i++) {
                merge(runMicrobatch(batch, starts[i], starts[i] + sizes[i]), sums);
            }
        } else {
            runParallel(batch, sizes, starts, threads, sums);
        }
        String bad = gradients.firstNonFinite();
        if (bad != null) {
            throw new IllegalStateException("gradient of " + bad + " is not finite");
        }
        return new Result(sums[0] + sums[1], sums[0], sums[1], sums[2]);
    }

    private void runParallel(TrainingBatch batch, int[] sizes, int[] starts, int threads, double[] sums) {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(threads, sizes.length));
        try {
            List<Future<MicrobatchResult>> futures = new ArrayList<>();
            // At most 2 * threads microbatch results exist at a time: when the window is full, the oldest one is
            // merged first. Merging always takes the oldest, so the order is the microbatch order.
            int window = 2 * threads;
            for (int i = 0; i < sizes.length; i++) {
                int from = starts[i];
                int to = starts[i] + sizes[i];
                futures.add(pool.submit(() -> runMicrobatch(batch, from, to)));
                if (futures.size() >= window) {
                    merge(futures.remove(0).get(), sums);
                }
            }
            while (!futures.isEmpty()) {
                merge(futures.remove(0).get(), sums);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException r) {
                throw r;
            }
            throw new IllegalStateException(e.getCause());
        } finally {
            pool.shutdownNow();
        }
    }

    private void merge(MicrobatchResult result, double[] sums) {
        gradients.add(result.gradients);
        for (int i = 0; i < sums.length; i++) {
            sums[i] += result.sums[i];
        }
    }

    private record MicrobatchResult(Gradients gradients, double[] sums) {
    }

    private MicrobatchResult runMicrobatch(TrainingBatch batch, int from, int to) {
        Gradients g = new Gradients(config());
        Worker worker = new Worker(parameters, valueCoefficient);
        double[] sums = new double[3];
        for (int s = from; s < to; s++) {
            worker.sample(batch, s, g, sums);
        }
        return new MicrobatchResult(g, sums);
    }

    /**
     * The state of one sample at a time: stored activations and the gradient buffers (docs/training.md, "What
     * the forward pass stores"). Package-private so that tests can look at the buffers.
     */
    static final class Worker {
        private final NetworkConfig config;
        private final double valueCoefficient;
        private final int c;
        private final int blocks;
        private final int players;
        private final int hiddenSize;
        private final int activation;

        // Parameters, by reference: an in-place update is visible at the next sample.
        private final float[] wIn;
        private final float[][] w1;
        private final float[][] w2;
        private final float[] wPolicy;
        private final float[] wDense1;
        private final float[] wDense2;
        private final Parameters parameters;

        /** Stored activations: slot 0 is x_0, slot 2i + 1 is t_i, slot 2i + 2 is u_i = x_{i+1}. */
        final float[] acts;
        final float[] policy = new float[Encoder.POLICY_SIZE];
        final float[] pooled;
        final float[] hidden;
        final float[] values;

        // Gradient buffers: dx of the current block output and input, dz of conv2, dt.
        double[] dxCur;
        double[] dxNext;
        final double[] dz2;
        final double[] dt;
        final double[] dPolicy = new double[Encoder.POLICY_SIZE];
        final double[] dPooled;
        final double[] dHidden;
        final double[] dy;

        Worker(Parameters parameters, double valueCoefficient) {
            this.parameters = parameters;
            this.config = parameters.config();
            this.valueCoefficient = valueCoefficient;
            this.c = config.channels();
            this.blocks = config.blocks();
            this.players = config.players();
            this.hiddenSize = config.valueHidden();
            this.activation = HexGrid.ROWS * c;
            this.wIn = data("conv_in.weight");
            this.w1 = new float[blocks][];
            this.w2 = new float[blocks][];
            for (int i = 0; i < blocks; i++) {
                w1[i] = data("block" + i + ".conv1.weight");
                w2[i] = data("block" + i + ".conv2.weight");
            }
            this.wPolicy = data("head_policy.weight");
            this.wDense1 = data("head_value.dense1.weight");
            this.wDense2 = data("head_value.dense2.weight");
            this.acts = new float[(1 + 2 * blocks) * activation];
            this.pooled = new float[c];
            this.hidden = new float[hiddenSize];
            this.values = new float[players];
            this.dxCur = new double[activation];
            this.dxNext = new double[activation];
            this.dz2 = new double[activation];
            this.dt = new double[activation];
            this.dPooled = new double[c];
            this.dHidden = new double[hiddenSize];
            this.dy = new double[players];
        }

        private float[] data(String name) {
            return parameters.get(name).data();
        }

        /** Test hook: fills every scratch buffer with NaN, so that a buffer that is read before it is written shows. */
        void poison() {
            Arrays.fill(acts, Float.NaN);
            Arrays.fill(policy, Float.NaN);
            Arrays.fill(pooled, Float.NaN);
            Arrays.fill(hidden, Float.NaN);
            Arrays.fill(values, Float.NaN);
            Arrays.fill(dxCur, Double.NaN);
            Arrays.fill(dxNext, Double.NaN);
            Arrays.fill(dz2, Double.NaN);
            Arrays.fill(dt, Double.NaN);
            Arrays.fill(dPolicy, Double.NaN);
            Arrays.fill(dPooled, Double.NaN);
            Arrays.fill(dHidden, Double.NaN);
            Arrays.fill(dy, Double.NaN);
        }

        /** The forward pass of {@link Network}, storing the activations. */
        void forward(float[] input, int inputOffset) {
            ReferenceOps.hexConv(input, inputOffset, Encoder.PLANES, wIn, data("conv_in.bias"), c, acts, 0);
            ReferenceOps.relu(acts, 0, activation);
            for (int i = 0; i < blocks; i++) {
                int x = 2 * i * activation;
                int t = x + activation;
                int u = t + activation;
                String block = "block" + i;
                ReferenceOps.hexConv(acts, x, c, w1[i], data(block + ".conv1.bias"), c, acts, t);
                ReferenceOps.relu(acts, t, activation);
                ReferenceOps.hexConv(acts, t, c, w2[i], data(block + ".conv2.bias"), c, acts, u);
                ReferenceOps.add(acts, u, acts, x, activation);
                ReferenceOps.relu(acts, u, activation);
            }
            int top = 2 * blocks * activation;
            ReferenceOps.pointwise(acts, top, HexGrid.CELLS, c, wPolicy, data("head_policy.bias"),
                    Encoder.POLICY_CHANNELS, policy, 0);
            ReferenceOps.meanOverCells(acts, top, c, pooled, 0);
            ReferenceOps.dense(pooled, 0, c, wDense1, data("head_value.dense1.bias"), hiddenSize, hidden, 0);
            ReferenceOps.relu(hidden, 0, hiddenSize);
            ReferenceOps.dense(hidden, 0, hiddenSize, wDense2, data("head_value.dense2.bias"), players, values, 0);
            ReferenceOps.tanh(values, 0, players);
        }

        /**
         * Forward and backward of one sample, adding its gradients to {@code g} and its loss terms (divided by
         * N) to {@code sums}: policy term, value term, KL.
         */
        void sample(TrainingBatch batch, int s, Gradients g, double[] sums) {
            int n = batch.size();
            forward(batch.input(), s * Encoder.INPUT_SIZE);

            // 1. Loss to outputs.
            Arrays.fill(dPolicy, 0);
            Arrays.fill(dy, 0);
            float wp = batch.policyWeight()[s];
            float wv = batch.valueWeight()[s];
            int first = batch.moveOffsets()[s];
            int end = batch.moveOffsets()[s + 1];
            if (wp > 0) {
                double lp = ReferenceLoss.policy(policy, 0, batch.moveFrom(), batch.moveTo(), batch.moveTarget(),
                        first, end, (double) wp / n, dPolicy, 0);
                requireFinite("policy loss", lp, s);
                sums[0] += wp * lp / n;
                sums[2] += wp * (lp - batch.targetEntropy()[s]) / n;
            }
            if (wv > 0) {
                double lv = ReferenceLoss.value(values, 0, batch.valueTarget(), s * players, players,
                        valueCoefficient * wv / n, dy, 0);
                requireFinite("value loss", lv, s);
                sums[1] += valueCoefficient * wv * lv / n;
            }
            backward(batch.input(), s * Encoder.INPUT_SIZE, g);
        }

        private static void requireFinite(String what, double value, int sample) {
            if (!Double.isFinite(value)) {
                throw new IllegalStateException(what + " of sample " + sample + " is " + value);
            }
        }

        /** The backward pass in the order of docs/training.md, given dPolicy and dy. */
        void backward(float[] input, int inputOffset, Gradients g) {
            int top = 2 * blocks * activation;

            // 2. head_value.dense2 (dy is the gradient of its pre-activation); 3. ReLU of dense1.
            Arrays.fill(dHidden, 0);
            ReferenceBackward.denseBackward(hidden, 0, hiddenSize, wDense2, players, dy, 0,
                    g.get("head_value.dense2.weight"), g.get("head_value.dense2.bias"), dHidden, 0);
            ReferenceBackward.reluBackward(hidden, 0, dHidden, 0, hiddenSize);

            // 4. head_value.dense1.
            Arrays.fill(dPooled, 0);
            ReferenceBackward.denseBackward(pooled, 0, c, wDense1, hiddenSize, dHidden, 0,
                    g.get("head_value.dense1.weight"), g.get("head_value.dense1.bias"), dPooled, 0);

            // 5. mean, then 6. policy head: both feed the gradient of the trunk output.
            Arrays.fill(dxCur, 0);
            ReferenceBackward.meanBackward(dPooled, 0, c, dxCur, 0);
            ReferenceBackward.pointwiseBackward(acts, top, HexGrid.CELLS, c, wPolicy, Encoder.POLICY_CHANNELS,
                    dPolicy, 0, g.get("head_policy.weight"), g.get("head_policy.bias"), dxCur, 0);

            // 7. Blocks, last to first. dxCur is dx_{i+1}.
            for (int i = blocks - 1; i >= 0; i--) {
                int x = 2 * i * activation;
                int t = x + activation;
                int u = t + activation;
                String block = "block" + i;
                ReferenceBackward.reluBackward(acts, u, dxCur, 0, activation); // dxCur is now dpre
                // The residual add sends dpre to conv2 (dz2) and, along the identity path, to dx_i.
                Arrays.fill(dz2, 0);
                Arrays.fill(dxNext, 0);
                ReferenceBackward.addBackward(dxCur, 0, dz2, 0, dxNext, 0, activation);
                Arrays.fill(dt, 0);
                ReferenceBackward.hexConvBackward(acts, t, c, w2[i], c, dz2, 0,
                        g.get(block + ".conv2.weight"), g.get(block + ".conv2.bias"), dt, 0);
                ReferenceBackward.reluBackward(acts, t, dt, 0, activation);
                ReferenceBackward.hexConvBackward(acts, x, c, w1[i], c, dt, 0,
                        g.get(block + ".conv1.weight"), g.get(block + ".conv1.bias"), dxNext, 0);
                double[] swap = dxCur;
                dxCur = dxNext;
                dxNext = swap;
            }

            // 8. conv_in: no input gradient.
            ReferenceBackward.reluBackward(acts, 0, dxCur, 0, activation);
            ReferenceBackward.hexConvBackward(input, inputOffset, Encoder.PLANES, wIn, c, dxCur, 0,
                    g.get("conv_in.weight"), g.get("conv_in.bias"), null, 0);
        }
    }
}
