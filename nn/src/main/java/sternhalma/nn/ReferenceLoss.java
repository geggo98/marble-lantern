package sternhalma.nn;

/**
 * The policy and value losses of one sample with their gradients (docs/training.md, "Loss", "Loss to network
 * outputs"). The losses are computed in double from the float outputs of the network.
 *
 * <p>The gradient methods <b>add</b> to their output arrays, like {@link ReferenceBackward}; the caller zeroes
 * them. The factor that turns a per-sample loss into a term of the batch loss (the sample weight over the batch
 * size N) is an argument, so that the same code serves a batch of any size and any microbatching.
 */
public final class ReferenceLoss {

    private ReferenceLoss() {
    }

    /**
     * Cross-entropy of the masked softmax over the legal moves of one sample, and its gradient.
     *
     * <p>With {@code l_m = f[from_m] + t[to_m]} in double, {@code logZ = max + log(sum exp(l_m - max))} and
     * {@code Lp = sum over pi_m > 0 of pi_m (logZ - l_m)}. The gradient of {@code scale * Lp} with respect to
     * the policy output is added to {@code dPolicy}: {@code g_m = scale (p_m - pi_m)} goes to
     * {@code dPolicy[2 from_m]} and {@code dPolicy[2 to_m + 1]}. Moves that share a cell accumulate.
     *
     * @param policy   policy output of the sample, [121][2], from offset {@code policyOffset}
     * @param from     canonical start cells of all moves of the batch (CSR arrays of {@link TrainingBatch})
     * @param to       canonical end cells
     * @param target   normalised targets pi_m
     * @param first    index of the first move of the sample
     * @param end      index after the last move of the sample
     * @param scale    {@code wp_s / N}
     * @param dPolicy  gradient of the policy output, [121][2], from offset {@code dOffset}; may be null if only
     *                 the loss is wanted
     * @return {@code Lp}; 0 for a sample without moves
     */
    public static double policy(float[] policy, int policyOffset, int[] from, int[] to, float[] target,
            int first, int end, double scale, double[] dPolicy, int dOffset) {
        if (first == end) {
            return 0;
        }
        double max = Double.NEGATIVE_INFINITY;
        for (int m = first; m < end; m++) {
            max = Math.max(max, logit(policy, policyOffset, from[m], to[m]));
        }
        double sum = 0;
        for (int m = first; m < end; m++) {
            sum += Math.exp(logit(policy, policyOffset, from[m], to[m]) - max);
        }
        double logZ = max + Math.log(sum);
        double loss = 0;
        for (int m = first; m < end; m++) {
            double l = logit(policy, policyOffset, from[m], to[m]);
            if (target[m] > 0) {
                loss += target[m] * (logZ - l);
            }
            if (dPolicy != null) {
                double g = scale * (Math.exp(l - logZ) - target[m]);
                dPolicy[dOffset + Encoder.policyIndex(from[m], Encoder.FROM)] += g;
                dPolicy[dOffset + Encoder.policyIndex(to[m], Encoder.TO)] += g;
            }
        }
        return loss;
    }

    /** {@code H = -sum over pi_m > 0 of pi_m log pi_m} of the targets {@code target[first .. end)}. */
    public static double entropy(float[] target, int first, int end) {
        double h = 0;
        for (int m = first; m < end; m++) {
            if (target[m] > 0) {
                h -= target[m] * Math.log(target[m]);
            }
        }
        return h;
    }

    /** The move logit in double, as {@link Encoder#moveLogit} for canonical cells. */
    private static double logit(float[] policy, int offset, int fromCell, int toCell) {
        return (double) policy[offset + Encoder.policyIndex(fromCell, Encoder.FROM)]
                + policy[offset + Encoder.policyIndex(toCell, Encoder.TO)];
    }

    /**
     * Mean squared error of the value head over the players of one sample, and the gradient with respect to
     * the pre-activations y of the output layer: {@code Lv = (1/P) sum (v_i - z_i)^2} and
     * {@code dy_i += scale (2/P) (v_i - z_i) (1 - v_i^2)}, with the float outputs v_i of tanh.
     *
     * @param values  float outputs v of the value head, from {@code vOffset}
     * @param target  targets z in relative player order, from {@code zOffset}
     * @param players P
     * @param scale   {@code c_v wv_s / N}
     * @param dy      gradient of the pre-activations, from {@code dyOffset}; may be null
     * @return {@code Lv}
     */
    public static double value(float[] values, int vOffset, float[] target, int zOffset, int players,
            double scale, double[] dy, int dyOffset) {
        double loss = 0;
        for (int i = 0; i < players; i++) {
            double v = values[vOffset + i];
            double diff = v - target[zOffset + i];
            loss += diff * diff;
            if (dy != null) {
                dy[dyOffset + i] += scale * (2.0 / players) * diff * (1 - v * v);
            }
        }
        return loss / players;
    }
}
