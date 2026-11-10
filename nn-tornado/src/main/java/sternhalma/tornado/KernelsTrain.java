package sternhalma.tornado;

import sternhalma.nn.ClassicalPlanes;
import sternhalma.nn.Encoder;
import sternhalma.nn.HexGrid;
import uk.ac.manchester.tornado.api.KernelContext;
import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.math.TornadoMath;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * TornadoVM kernels of one training step besides the convolutions (docs/training.md, Part B, "Kernels of one
 * training step", kernels 2 to 5 and 10): the training variant of the value head, the policy prior, the policy
 * and value losses with their gradients, the backward pass of both heads down to the gradient of the trunk
 * output, and the weight gradients of the head layers and of the two prior scalars.
 *
 * <p>Layouts are those of docs/network.md, "Tensor layout", with a leading batch dimension: a trunk activation
 * is [batch][122][C]; policy outputs and their gradients are [batch][121][2]; the sample arrays follow
 * {@code TrainingBatch} (moves in CSR form, canonical cells, mirror already applied on the host).
 *
 * <p>Every output element is written by exactly one work item, and every sum runs in float in a fixed order
 * (ascending sample, then cell), so a step gives the same bits on every run: no atomic adds. Where a sum has
 * too many terms for one work item (the policy head weight: 121 cells of every sample), it is split into
 * chunks of samples, and a second kernel adds the chunks in order. Called directly from Java, the
 * {@code @Parallel} kernels run as plain Java; {@link #valueHead} needs a TornadoVM grid.
 *
 * <p>Scalar arguments are fixed when a task is defined, so a task graph serves one batch size.
 */
public final class KernelsTrain {

    private static final int ROWS = HexGrid.ROWS;
    private static final int CELLS = HexGrid.CELLS;
    private static final int POLICY_SIZE = Encoder.POLICY_SIZE;

    /** Work-group size of {@link #valueHead}; the same as {@link OpsKernels#VALUE_GROUP}. */
    static final int VALUE_GROUP = OpsKernels.VALUE_GROUP;
    static final int MAX_WIDTH = OpsKernels.MAX_WIDTH;

    private KernelsTrain() {
    }

    /**
     * Adds the distance prior to the policy logits of the head, after {@link OpsKernels#policyHead}
     * (docs/network.md, "Classical planes and residual priors"): {@code policy[c][FROM] += beta * 16 * d(c)} and
     * {@code policy[c][TO] -= beta * 16 * d(c)} with d the plane {@link Encoder#DIST_OWN}. One work item per
     * (position, cell).
     *
     * @param input  [batch][122][planes], planes = 13
     * @param beta   {@code prior.beta}, one element
     * @param policy [batch][121][2], updated in place
     */
    public static void policyPrior(FloatArray input, FloatArray beta, FloatArray policy, int batch, int planes) {
        for (@Parallel int b = 0; b < batch; b++) {
            for (@Parallel int cell = 0; cell < CELLS; cell++) {
                float scale = beta.get(0) * ClassicalPlanes.DIST_SCALE;
                float term = scale * input.get((b * ROWS + cell) * planes + Encoder.DIST_OWN);
                int j = b * POLICY_SIZE + cell * 2;
                policy.set(j, policy.get(j) + term);
                policy.set(j + 1, policy.get(j + 1) - term);
            }
        }
    }

    /**
     * The value head of {@link OpsKernels#valueHead} for training: the same three steps in one work-group of
     * {@link #VALUE_GROUP} work items per position, but it also stores the pooled vector and the hidden
     * activation (the inputs of the weight gradients), and with {@code priors = 1} it adds
     * {@code prior.k * sign_i * e} to the pre-activation of player i before the tanh, with e the plane
     * {@link Encoder#EVAL} at row 0 and sign +1, -1, 0 for relative players 0, 1, 2
     * ({@code ResidualPriors.valueBase}).
     *
     * @param pooledOut [batch][C]
     * @param hiddenOut [batch][H], after the ReLU
     * @param valuesOut [batch][P], the tanh outputs
     * @param priorK    {@code prior.k}, one element; not read without priors
     * @param input     [batch][122][planes]; not read without priors
     */
    public static void valueHead(KernelContext context, FloatArray trunk, FloatArray w1, FloatArray b1,
            FloatArray w2, FloatArray b2, FloatArray input, FloatArray priorK, FloatArray pooledOut,
            FloatArray hiddenOut, FloatArray valuesOut, int channels, int hidden, int players, int planes,
            int priors) {
        float[] partial = context.allocateFloatLocalArray(VALUE_GROUP);
        float[] pooled = context.allocateFloatLocalArray(MAX_WIDTH);
        float[] act = context.allocateFloatLocalArray(MAX_WIDTH);
        int t = context.localIdx;
        int b = context.groupIdx;
        int base = b * ROWS * channels;

        // 1. Mean over the cells: partial sums, then one sum per channel (as OpsKernels.valueHead).
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
        for (int ch = t; ch < channels; ch += VALUE_GROUP) {
            pooledOut.set(b * channels + ch, pooled[ch]);
        }

        // 2. Dense C -> H, bias, ReLU.
        for (int j = t; j < hidden; j += VALUE_GROUP) {
            float sum = b1.get(j);
            for (int ch = 0; ch < channels; ch++) {
                sum += w1.get(j * channels + ch) * pooled[ch];
            }
            float a = DeviceMath.relu(sum);
            act[j] = a;
            hiddenOut.set(b * hidden + j, a);
        }
        context.localBarrier();

        // 3. Dense H -> P, bias, prior, tanh.
        for (int p = t; p < players; p += VALUE_GROUP) {
            float sum = b2.get(p);
            for (int j = 0; j < hidden; j++) {
                sum += w2.get(p * hidden + j) * act[j];
            }
            if (priors == 1) {
                float sign = p == 0 ? 1f : (p == 1 ? -1f : 0f);
                sum += priorK.get(0) * (sign * input.get(b * ROWS * planes + Encoder.EVAL));
            }
            valuesOut.set(b * players + p, DeviceMath.tanh(sum));
        }
    }

    /**
     * Masked softmax cross-entropy of every sample and its gradient (docs/training.md, "Policy loss" and "Loss to
     * network outputs"), in float. One work item per sample loops over the moves of the sample in order: the
     * maximum of the move logits {@code l_m = f[from_m] + t[to_m]}, the sum of {@code exp(l_m - max)}, then
     * {@code Lp = sum over pi_m > 0 of pi_m (logZ - l_m)} and {@code g_m = wp / N (p_m - pi_m)}, added to
     * {@code dPolicy[2 from_m]} and {@code dPolicy[2 to_m + 1]}. Moves that share a cell accumulate in the one
     * work item, so no atomic add is needed. The row of {@code dPolicy} of the sample is zeroed first.
     *
     * <p>A sample with {@code wp = 0}, without moves or with a single move gets {@code Lp = 0} and a zero gradient,
     * whatever its logits hold (the single move because Metal's {@code exp} and {@code log} are accurate to about
     * 2e-6 only, {@code DeviceExpLogTest}, which would leave a gradient of 1e-7 instead of exactly 0). {@code lossOut[2 s]} receives the unweighted {@code Lp}; the host forms the weighted mean.
     *
     * @param policy  [batch][121][2], logits with the prior
     * @param offsets [batch + 1], CSR offsets of the moves
     * @param from    canonical start cells of the moves
     * @param to      canonical end cells
     * @param target  normalised targets pi_m
     * @param wp      policy weights wp_s
     * @param dPolicy [batch][121][2], written
     * @param lossOut [batch][2], written at the even indices
     */
    public static void policyLoss(FloatArray policy, IntArray offsets, IntArray from, IntArray to,
            FloatArray target, FloatArray wp, FloatArray dPolicy, FloatArray lossOut, int batch) {
        for (@Parallel int s = 0; s < batch; s++) {
            int base = s * POLICY_SIZE;
            int first = offsets.get(s);
            int end = offsets.get(s + 1);
            float weight = wp.get(s);
            for (int i = 0; i < POLICY_SIZE; i++) {
                dPolicy.set(base + i, 0f);
            }
            float loss = 0f;
            // One legal move: Lp = 0 and a zero gradient, exactly (docs/training.md); Metal's exp and log are off by ~1e-6.
            if (weight > 0f && end - first > 1) {
                float max = policy.get(base + 2 * from.get(first)) + policy.get(base + 2 * to.get(first) + 1);
                for (int m = first + 1; m < end; m++) {
                    float l = policy.get(base + 2 * from.get(m)) + policy.get(base + 2 * to.get(m) + 1);
                    if (l > max) {
                        max = l;
                    }
                }
                float sum = 0f;
                for (int m = first; m < end; m++) {
                    float l = policy.get(base + 2 * from.get(m)) + policy.get(base + 2 * to.get(m) + 1);
                    sum += TornadoMath.exp(l - max);
                }
                float logZ = max + TornadoMath.log(sum);
                float scale = weight / batch;
                for (int m = first; m < end; m++) {
                    int f = base + 2 * from.get(m);
                    int t = base + 2 * to.get(m) + 1;
                    float l = policy.get(f) + policy.get(t);
                    float pi = target.get(m);
                    if (pi > 0f) {
                        loss += pi * (logZ - l);
                    }
                    float g = scale * (TornadoMath.exp(l - logZ) - pi);
                    dPolicy.set(f, dPolicy.get(f) + g);
                    dPolicy.set(t, dPolicy.get(t) + g);
                }
            }
            lossOut.set(2 * s, loss);
        }
    }

    /**
     * Value loss of every sample and the gradient of its pre-activations (docs/training.md, "Value loss"):
     * {@code Lv = (1/P) sum (v_i - z_i)^2} and
     * {@code dy_i = cv wv / N (2 / P) (v_i - z_i) (1 - v_i^2)}, with the float outputs v_i. A sample with
     * {@code wv = 0} gets {@code Lv = 0} and {@code dy = 0}. One work item per sample; {@code lossOut[2 s + 1]}
     * receives the unweighted {@code Lv}.
     *
     * @param values  [batch][P]
     * @param z       [batch][P], targets in relative player order
     * @param wv      value weights
     * @param dy      [batch][P], written
     * @param lossOut [batch][2], written at the odd indices
     */
    public static void valueLoss(FloatArray values, FloatArray z, FloatArray wv, FloatArray dy, FloatArray lossOut,
            int batch, int players, float valueCoefficient) {
        for (@Parallel int s = 0; s < batch; s++) {
            float weight = wv.get(s);
            float loss = 0f;
            float scale = valueCoefficient * weight / batch;
            for (int p = 0; p < players; p++) {
                float g = 0f;
                if (weight > 0f) {
                    float v = values.get(s * players + p);
                    float diff = v - z.get(s * players + p);
                    loss += diff * diff;
                    g = scale * (2f / players) * diff * (1f - v * v);
                }
                dy.set(s * players + p, g);
            }
            lossOut.set(2 * s + 1, loss / players);
        }
    }

    /**
     * Gradient of the pre-activation of {@code dense1} (docs/training.md, order steps 2 and 3):
     * {@code dz1[b][j] = (hidden[b][j] > 0) * sum over p of W2[p][j] dy[b][p]}. The ReLU rule uses the stored
     * output; NaN gives 0. One work item per (position, hidden unit).
     *
     * @param w2 {@code head_value.dense2.weight}, [P][H]
     */
    public static void valueHiddenGradient(FloatArray dy, FloatArray w2, FloatArray hiddenAct, FloatArray dz1,
            int batch, int hidden, int players) {
        for (@Parallel int i = 0; i < batch * hidden; i++) {
            int b = i / hidden;
            int j = i - b * hidden;
            float sum = 0f;
            for (int p = 0; p < players; p++) {
                sum += w2.get(p * hidden + j) * dy.get(b * players + p);
            }
            dz1.set(i, hiddenAct.get(i) > 0f ? sum : 0f);
        }
    }

    /**
     * Gradient of the pooled vector (order step 4): {@code dpooled[b][ch] = sum over j of W1[j][ch] dz1[b][j]}.
     * One work item per (position, channel).
     *
     * @param w1 {@code head_value.dense1.weight}, [H][C]
     */
    public static void valuePooledGradient(FloatArray dz1, FloatArray w1, FloatArray dPooled, int batch,
            int channels, int hidden) {
        for (@Parallel int i = 0; i < batch * channels; i++) {
            int b = i / channels;
            int ch = i - b * channels;
            float sum = 0f;
            for (int j = 0; j < hidden; j++) {
                sum += w1.get(j * channels + ch) * dz1.get(b * hidden + j);
            }
            dPooled.set(i, sum);
        }
    }

    /**
     * Gradient of the trunk output from both heads (docs/training.md, kernel 5, order steps 5 and 6):
     * {@code dx[b][c][ch] = dpooled[b][ch] / 121 + sum over k of Wp[k][ch] dPolicy[b][c][k]} for the 121 cell
     * rows, 0 for the padding row. One work item per element of {@code dx}.
     *
     * @param wPolicy {@code head_policy.weight}, [2][C]
     * @param dx      [batch][122][C], written
     */
    public static void trunkGradient(FloatArray dPolicy, FloatArray wPolicy, FloatArray dPooled, FloatArray dx,
            int batch, int channels) {
        for (@Parallel int i = 0; i < batch * ROWS * channels; i++) {
            int ch = i % channels;
            int rest = i / channels;
            int row = rest % ROWS;
            int b = rest / ROWS;
            float v = 0f;
            if (row < CELLS) {
                v = dPooled.get(b * channels + ch) / CELLS;
                int d = b * POLICY_SIZE + row * 2;
                v += wPolicy.get(ch) * dPolicy.get(d);
                v += wPolicy.get(channels + ch) * dPolicy.get(d + 1);
            }
            dx.set(i, v);
        }
    }

    /**
     * Weight and bias gradient of a dense layer on vectors: {@code dW[r][j] = sum over b of a[b][r] x[b][j]} and
     * {@code db[r] = sum over b of a[b][r]}, sums in ascending sample order. One work item per element of
     * {@code dW} and per bias (the column {@code j = cols} is the bias).
     *
     * @param a  [batch][rows], the gradient of the pre-activation
     * @param x  [batch][cols], the input of the layer
     * @param dW [rows][cols], written
     * @param db [rows], written
     */
    public static void denseGradient(FloatArray a, FloatArray x, FloatArray dW, FloatArray db, int batch,
            int rows, int cols) {
        for (@Parallel int r = 0; r < rows; r++) {
            for (@Parallel int j = 0; j <= cols; j++) {
                float sum = 0f;
                if (j < cols) {
                    for (int b = 0; b < batch; b++) {
                        sum += a.get(b * rows + r) * x.get(b * cols + j);
                    }
                    dW.set(r * cols + j, sum);
                } else {
                    for (int b = 0; b < batch; b++) {
                        sum += a.get(b * rows + r);
                    }
                    db.set(r, sum);
                }
            }
        }
    }

    /**
     * Split-K partial sums of the policy head gradient (docs/training.md, kernel 7 for the 1x1 convolution): the
     * samples are cut into {@code splits} consecutive chunks of {@code ceil(batch / splits)}; work item
     * {@code (chunk, k, ch)} sums over the samples of its chunk and the 121 cells
     * {@code dPolicy[s][c][k] * trunk[s][c][ch]} (for {@code ch = channels}: {@code dPolicy[s][c][k]}, the bias).
     *
     * @param partial [splits][2][channels + 1], written
     */
    public static void policyWeightPartial(FloatArray trunk, FloatArray dPolicy, FloatArray partial, int batch,
            int channels, int splits) {
        int width = channels + 1;
        int per = (batch + splits - 1) / splits;
        for (@Parallel int i = 0; i < splits * 2 * width; i++) {
            int chunk = i / (2 * width);
            int rest = i - chunk * 2 * width;
            int k = rest / width;
            int ch = rest - k * width;
            int last = (chunk + 1) * per;
            if (last > batch) {
                last = batch;
            }
            float sum = 0f;
            for (int s = chunk * per; s < last; s++) {
                for (int cell = 0; cell < CELLS; cell++) {
                    float g = dPolicy.get(s * POLICY_SIZE + cell * 2 + k);
                    if (ch < channels) {
                        sum += g * trunk.get((s * ROWS + cell) * channels + ch);
                    } else {
                        sum += g;
                    }
                }
            }
            partial.set(i, sum);
        }
    }

    /**
     * Adds the chunks of {@link #policyWeightPartial} in ascending order into {@code dW} [2][C] and {@code db}
     * [2]. One work item per (k, ch) with the bias column.
     */
    public static void policyWeightReduce(FloatArray partial, FloatArray dW, FloatArray db, int channels,
            int splits) {
        int width = channels + 1;
        for (@Parallel int i = 0; i < 2 * width; i++) {
            float sum = 0f;
            for (int chunk = 0; chunk < splits; chunk++) {
                sum += partial.get(chunk * 2 * width + i);
            }
            int k = i / width;
            int ch = i - k * width;
            if (ch < channels) {
                dW.set(k * channels + ch, sum);
            } else {
                db.set(k, sum);
            }
        }
    }

    /**
     * Per-sample terms of the gradients of {@code prior.beta} and {@code prior.k} (docs/network.md, "Classical
     * planes and residual priors"): {@code partial[2 s] = sum over cells of (dP[c][FROM] - dP[c][TO]) * 16 * d(c)}
     * and {@code partial[2 s + 1] = sum over i of dy_i * sign_i * e}. One work item per sample.
     *
     * @param dPolicy the output gradient of the policy, with the prior included
     * @param dy      the gradient of the value pre-activation
     * @param partial [batch][2], written
     */
    public static void priorGradientPartial(FloatArray dPolicy, FloatArray dy, FloatArray input,
            FloatArray partial, int batch, int planes, int players) {
        for (@Parallel int s = 0; s < batch; s++) {
            float db = 0f;
            for (int cell = 0; cell < CELLS; cell++) {
                float d = input.get((s * ROWS + cell) * planes + Encoder.DIST_OWN);
                int j = s * POLICY_SIZE + cell * 2;
                db += (dPolicy.get(j) - dPolicy.get(j + 1)) * ClassicalPlanes.DIST_SCALE * d;
            }
            float e = input.get(s * ROWS * planes + Encoder.EVAL);
            float dk = dy.get(s * players) * e;
            dk += dy.get(s * players + 1) * -e;
            partial.set(2 * s, db);
            partial.set(2 * s + 1, dk);
        }
    }

    /** Adds the per-sample terms in ascending order: {@code dBeta[0]} and {@code dK[0]}. Two work items. */
    public static void priorGradientReduce(FloatArray partial, FloatArray dBeta, FloatArray dK, int batch) {
        for (@Parallel int i = 0; i < 2; i++) {
            float sum = 0f;
            for (int s = 0; s < batch; s++) {
                sum += partial.get(2 * s + i);
            }
            if (i == 0) {
                dBeta.set(0, sum);
            } else {
                dK.set(0, sum);
            }
        }
    }
}
