package sternhalma.nn;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The optimisers of docs/training.md, "Optimiser": SGD with momentum and AdamW. They update the float
 * parameter arrays of a {@link Parameters} in place.
 *
 * <p>All arithmetic is in double. The gradient is rounded to float when it is read ("rounded to float only
 * when an optimiser reads them"), and the state (m and v, or the velocity u) and the parameter are rounded to
 * float when they are stored. The next step starts from the stored float values, so the state in a checkpoint
 * is the whole state: a resumed run continues bit for bit.
 *
 * <p>The step count {@code t} is the number of completed steps; the update of a step uses {@code t + 1}, so
 * the bias correction of AdamW starts at {@code 1 - beta^1}. The learning rate is an argument of each step,
 * from {@link LearningRateSchedule}.
 *
 * <p>Not thread-safe.
 */
public final class Optimizer {

    /** The optimiser; {@link #id} is the value of the checkpoint metadata key {@code optimizer}. */
    public enum Kind {
        SGD("sgd"),
        ADAMW("adamw");

        private final String id;

        Kind(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        /** The kind with this id, or null. */
        public static Kind ofId(String id) {
            for (Kind k : values()) {
                if (k.id.equals(id)) {
                    return k;
                }
            }
            return null;
        }
    }

    /**
     * The hyperparameters. A kind uses only its own: AdamW uses beta1, beta2, eps and weightDecay; SGD uses
     * momentum and l2. All six are stored in a checkpoint, so that the record is complete for either kind.
     *
     * @param weightDecay AdamW decay, multiplied by the learning rate; applies to {@code .weight} tensors only
     * @param momentum    SGD momentum mu
     * @param l2          SGD L2 coefficient lambda, added to the gradient of {@code .weight} tensors only
     */
    public record Settings(Kind kind, double beta1, double beta2, double eps, double weightDecay, double momentum,
            double l2) {

        public Settings {
            Objects.requireNonNull(kind, "kind");
            requireRange("beta1", beta1, 0, 1, false);
            requireRange("beta2", beta2, 0, 1, false);
            if (!(eps > 0) || !Double.isFinite(eps)) {
                throw new IllegalArgumentException("eps must be positive and finite, got " + eps);
            }
            requireRange("weightDecay", weightDecay, 0, Double.MAX_VALUE, true);
            requireRange("momentum", momentum, 0, 1, false);
            requireRange("l2", l2, 0, Double.MAX_VALUE, true);
        }

        /** AdamW with the defaults of docs/training.md: beta1 0.9, beta2 0.999, eps 1e-8, wd 0.01. */
        public static Settings adamw() {
            return new Settings(Kind.ADAMW, 0.9, 0.999, 1e-8, 0.01, 0.9, 0);
        }

        /** SGD with the defaults of docs/training.md: momentum 0.9, lambda 1e-4. */
        public static Settings sgd() {
            return new Settings(Kind.SGD, 0.9, 0.999, 1e-8, 0.01, 0.9, 1e-4);
        }

        private static void requireRange(String name, double v, double low, double high, boolean highInclusive) {
            boolean ok = v >= low && (highInclusive ? v <= high : v < high) && Double.isFinite(v);
            if (!ok) {
                throw new IllegalArgumentException(name + " must be in [" + low + ", " + high
                        + (highInclusive ? "]" : ")") + ", got " + v);
            }
        }
    }

    private final Parameters parameters;
    private final Settings settings;
    private final LinkedHashMap<String, float[]> first;   // AdamW m, or SGD velocity u
    private final LinkedHashMap<String, float[]> second;  // AdamW v, or null
    private long steps;

    /** An optimiser with zero state at step 0. */
    public Optimizer(Parameters parameters, Settings settings) {
        this(parameters, settings, 0, zeros(parameters), settings.kind() == Kind.ADAMW ? zeros(parameters) : null);
    }

    private Optimizer(Parameters parameters, Settings settings, long steps, LinkedHashMap<String, float[]> first,
            LinkedHashMap<String, float[]> second) {
        this.parameters = Objects.requireNonNull(parameters, "parameters");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.steps = steps;
        this.first = first;
        this.second = second;
    }

    /**
     * An optimiser with given state, for example from a checkpoint. The arrays are used, not copied.
     *
     * @param m AdamW first moment, one array per parameter tensor; null for SGD
     * @param v AdamW second moment; null for SGD
     * @param u SGD velocity; null for AdamW
     * @throws IllegalArgumentException if steps is negative, the state of the other kind is given, or a tensor of
     *                                  the state is missing or has the wrong length
     */
    public static Optimizer restore(Parameters parameters, Settings settings, long steps, Map<String, float[]> m,
            Map<String, float[]> v, Map<String, float[]> u) {
        if (steps < 0) {
            throw new IllegalArgumentException("steps must be >= 0, got " + steps);
        }
        boolean adamw = settings.kind() == Kind.ADAMW;
        if (adamw && (m == null || v == null || u != null)) {
            throw new IllegalArgumentException("AdamW needs m and v and has no u");
        }
        if (!adamw && (u == null || m != null || v != null)) {
            throw new IllegalArgumentException("SGD needs u and has no m and v");
        }
        LinkedHashMap<String, float[]> first = checked(parameters, adamw ? m : u, adamw ? "m" : "u");
        LinkedHashMap<String, float[]> second = adamw ? checked(parameters, v, "v") : null;
        return new Optimizer(parameters, settings, steps, first, second);
    }

    /** The parameters that this optimiser updates. */
    public Parameters parameters() {
        return parameters;
    }

    public Settings settings() {
        return settings;
    }

    /**
     * Number of completed steps. Within a generation this is also the learning-rate schedule index and the step of
     * the batch sampler, so each generation needs a fresh optimiser.
     */

    public long steps() {
        return steps;
    }

    /** AdamW first moment, per tensor, live. */
    public Map<String, float[]> m() {
        return view(Kind.ADAMW, first);
    }

    /** AdamW second moment, per tensor, live. */
    public Map<String, float[]> v() {
        return view(Kind.ADAMW, second);
    }

    /** SGD velocity, per tensor, live. */
    public Map<String, float[]> u() {
        return view(Kind.SGD, first);
    }

    private Map<String, float[]> view(Kind required, Map<String, float[]> state) {
        if (settings.kind() != required) {
            throw new IllegalStateException("this optimiser is " + settings.kind().id() + ", not "
                    + required.id());
        }
        return Collections.unmodifiableMap(state);
    }

    /**
     * One update of all parameters with the given gradients, then step count + 1.
     *
     * @param lr the learning rate of this step, finite and &gt;= 0
     * @throws IllegalArgumentException if lr is not finite or negative; nothing changes then
     * @throws IllegalStateException    if a parameter becomes NaN or infinite; the parameters are undefined
     *                                  afterwards
     */
    public void step(ReferenceTrainer.Gradients gradients, double lr) {
        if (!(lr >= 0) || !Double.isFinite(lr)) {
            throw new IllegalArgumentException("lr must be >= 0 and finite, got " + lr);
        }
        long t = steps + 1;
        double correction1 = 1 - StrictMath.pow(settings.beta1(), t);
        double correction2 = 1 - StrictMath.pow(settings.beta2(), t);
        for (Map.Entry<String, Tensor> e : parameters.tensors().entrySet()) {
            String name = e.getKey();
            float[] theta = e.getValue().data();
            double[] g = gradients.get(name);
            boolean weight = isWeight(name);
            if (settings.kind() == Kind.ADAMW) {
                adamw(theta, g, first.get(name), second.get(name), weight, lr, correction1, correction2);
            } else {
                sgd(theta, g, first.get(name), weight, lr);
            }
            for (float x : theta) {
                if (!Float.isFinite(x)) {
                    throw new IllegalStateException("parameter " + name + " is not finite after step " + t);
                }
            }
        }
        steps = t;
    }

    /** theta = theta - lr * (mhat / (sqrt(vhat) + eps) + wd * theta * isWeight). */
    private void adamw(float[] theta, double[] g, float[] m, float[] v, boolean weight, double lr,
            double correction1, double correction2) {
        double beta1 = settings.beta1();
        double beta2 = settings.beta2();
        double decay = weight ? settings.weightDecay() : 0;
        for (int i = 0; i < theta.length; i++) {
            double gi = (float) g[i];
            m[i] = (float) (beta1 * m[i] + (1 - beta1) * gi);
            v[i] = (float) (beta2 * v[i] + (1 - beta2) * gi * gi);
            double mhat = m[i] / correction1;
            double vhat = v[i] / correction2;
            double ti = theta[i];
            theta[i] = (float) (ti - lr * (mhat / (Math.sqrt(vhat) + settings.eps()) + decay * ti));
        }
    }

    /** u = mu * u + (g + lambda * theta * isWeight); theta = theta - lr * u. */
    private void sgd(float[] theta, double[] g, float[] u, boolean weight, double lr) {
        double l2 = weight ? settings.l2() : 0;
        for (int i = 0; i < theta.length; i++) {
            double ti = theta[i];
            double gi = (float) g[i];
            u[i] = (float) (settings.momentum() * u[i] + (gi + l2 * ti));
            theta[i] = (float) (ti - lr * u[i]);
        }
    }

    /** True for tensors whose name ends in {@code .weight}: they get weight decay or L2; biases do not. */
    static boolean isWeight(String name) {
        return name.endsWith(".weight");
    }

    private static LinkedHashMap<String, float[]> zeros(Parameters parameters) {
        LinkedHashMap<String, float[]> state = new LinkedHashMap<>();
        for (Map.Entry<String, Tensor> e : parameters.tensors().entrySet()) {
            state.put(e.getKey(), new float[e.getValue().size()]);
        }
        return state;
    }

    private static LinkedHashMap<String, float[]> checked(Parameters parameters, Map<String, float[]> state,
            String what) {
        LinkedHashMap<String, float[]> result = new LinkedHashMap<>();
        for (Map.Entry<String, Tensor> e : parameters.tensors().entrySet()) {
            float[] a = state.get(e.getKey());
            if (a == null) {
                throw new IllegalArgumentException(what + " has no tensor " + e.getKey());
            }
            if (a.length != e.getValue().size()) {
                throw new IllegalArgumentException(what + "." + e.getKey() + " has " + a.length
                        + " elements, expected " + e.getValue().size());
            }
            result.put(e.getKey(), a);
        }
        if (state.size() != result.size()) {
            throw new IllegalArgumentException(what + " has " + (state.size() - result.size())
                    + " tensors that are not parameters");
        }
        return result;
    }
}
