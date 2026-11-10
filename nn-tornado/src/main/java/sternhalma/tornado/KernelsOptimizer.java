package sternhalma.tornado;

import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.math.TornadoMath;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;

/**
 * TornadoVM kernels of the optimiser step (docs/training.md, Part B kernel 11): AdamW and SGD with momentum over
 * one parameter tensor, in float, with the arithmetic of {@code sternhalma.nn.Optimizer}.
 *
 * <p>A work item updates {@code chunk} consecutive elements, one after the other, and writes one flag for the
 * chunk: 1 if one of its gradients or updated parameters is NaN or infinite, else 0. Every element is read and
 * written by one work item, so there are no atomic adds, and the flags are the finite check of the step without
 * an extra pass. {@link #sumFlags} adds them for the host: a step is sound if the sum is 0. A bad element is
 * <b>not</b> skipped: its tensor is updated like any other, and the parameters are undefined after a bad step,
 * as after {@code Optimizer.step} threw (the loop stops the run).
 *
 * <p>The values that change from step to step (learning rate, bias corrections) are not kernel arguments,
 * because scalar arguments are fixed when a task is defined; they are read from the {@code hyper} array, which
 * the host sends before every execution. Its layout is the {@code HYPER_*} constants.
 */
public final class KernelsOptimizer {

    /** Index of the learning rate in {@code hyper}. */
    public static final int HYPER_LR = 0;
    /** Index of beta1. */
    public static final int HYPER_BETA1 = 1;
    /** Index of beta2. */
    public static final int HYPER_BETA2 = 2;
    /** Index of eps. */
    public static final int HYPER_EPS = 3;
    /** Index of the AdamW weight decay. */
    public static final int HYPER_WEIGHT_DECAY = 4;
    /** Index of the SGD momentum. */
    public static final int HYPER_MOMENTUM = 5;
    /** Index of the SGD L2 coefficient. */
    public static final int HYPER_L2 = 6;
    /** Index of {@code 1 - beta1^t}. */
    public static final int HYPER_CORRECTION1 = 7;
    /** Index of {@code 1 - beta2^t}. */
    public static final int HYPER_CORRECTION2 = 8;
    /** Index of {@code 1 - beta1}, computed in double on the host: {@code 1f - beta1} in float is 1.3e-5 off for 0.999. */
    public static final int HYPER_ONE_MINUS_BETA1 = 9;
    /** Index of {@code 1 - beta2}, computed in double on the host. */
    public static final int HYPER_ONE_MINUS_BETA2 = 10;
    /** Length of the {@code hyper} array. */
    public static final int HYPER_SIZE = 11;

    private KernelsOptimizer() {
    }

    /**
     * AdamW on one tensor, in place: {@code m = b1 m + (1 - b1) g}, {@code v = b2 v + (1 - b2) g^2},
     * {@code theta = theta - lr (m / c1 / (sqrt(v / c2) + eps) + wd theta isWeight)}.
     *
     * @param weight 1 for a {@code .weight} tensor (it gets the decay), 0 for a bias
     * @param chunk  elements per work item
     * @param flags  one flag per chunk, from {@code flagBase}
     */
    public static void adamw(FloatArray theta, FloatArray grad, FloatArray m, FloatArray v, FloatArray hyper,
            FloatArray flags, int n, int chunk, int flagBase, int weight) {
        float lr = hyper.get(HYPER_LR);
        float beta1 = hyper.get(HYPER_BETA1);
        float beta2 = hyper.get(HYPER_BETA2);
        float oneMinusBeta1 = hyper.get(HYPER_ONE_MINUS_BETA1);
        float oneMinusBeta2 = hyper.get(HYPER_ONE_MINUS_BETA2);
        float eps = hyper.get(HYPER_EPS);
        float decay = weight == 1 ? hyper.get(HYPER_WEIGHT_DECAY) : 0f;
        float correction1 = hyper.get(HYPER_CORRECTION1);
        float correction2 = hyper.get(HYPER_CORRECTION2);
        for (@Parallel int c = 0; c < (n + chunk - 1) / chunk; c++) {
            int last = (c + 1) * chunk;
            if (last > n) {
                last = n;
            }
            float bad = 0f;
            for (int i = c * chunk; i < last; i++) {
                float g = grad.get(i);
                float mi = beta1 * m.get(i) + oneMinusBeta1 * g;
                float vi = beta2 * v.get(i) + oneMinusBeta2 * g * g;
                float mhat = mi / correction1;
                float vhat = vi / correction2;
                float ti = theta.get(i);
                float updated = ti - lr * (mhat / (TornadoMath.sqrt(vhat) + eps) + decay * ti);
                m.set(i, mi);
                v.set(i, vi);
                theta.set(i, updated);
                if (DeviceMath.notFinite(g) || DeviceMath.notFinite(updated)) {
                    bad = 1f;
                }
            }
            flags.set(flagBase + c, bad);
        }
    }

    /**
     * SGD with momentum on one tensor, in place: {@code u = mu u + (g + l2 theta isWeight)},
     * {@code theta = theta - lr u}. {@code u} is the velocity.
     */
    public static void sgd(FloatArray theta, FloatArray grad, FloatArray u, FloatArray hyper, FloatArray flags,
            int n, int chunk, int flagBase, int weight) {
        float lr = hyper.get(HYPER_LR);
        float momentum = hyper.get(HYPER_MOMENTUM);
        float l2 = weight == 1 ? hyper.get(HYPER_L2) : 0f;
        for (@Parallel int c = 0; c < (n + chunk - 1) / chunk; c++) {
            int last = (c + 1) * chunk;
            if (last > n) {
                last = n;
            }
            float bad = 0f;
            for (int i = c * chunk; i < last; i++) {
                float g = grad.get(i);
                float ti = theta.get(i);
                float ui = momentum * u.get(i) + (g + l2 * ti);
                float updated = ti - lr * ui;
                u.set(i, ui);
                theta.set(i, updated);
                if (DeviceMath.notFinite(g) || DeviceMath.notFinite(updated)) {
                    bad = 1f;
                }
            }
            flags.set(flagBase + c, bad);
        }
    }

    /**
     * Adds the flags of all tensors for the host: work item g sums the flags g, g + groups, g + 2 groups, ... into
     * {@code status[g]}. The host adds the {@code groups} values; 0 means that no element was bad.
     */
    public static void sumFlags(FloatArray flags, FloatArray status, int count, int groups) {
        for (@Parallel int g = 0; g < groups; g++) {
            float sum = 0f;
            for (int i = g; i < count; i += groups) {
                sum += flags.get(i);
            }
            status.set(g, sum);
        }
    }
}
