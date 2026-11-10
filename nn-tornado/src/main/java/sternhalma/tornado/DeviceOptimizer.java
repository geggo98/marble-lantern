package sternhalma.tornado;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import sternhalma.nn.Optimizer;
import sternhalma.nn.Optimizer.Kind;
import sternhalma.nn.Optimizer.Settings;
import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;

/**
 * The optimiser of {@code sternhalma.nn.Optimizer} on the device (docs/training.md, Part B kernel 11): the state
 * (AdamW's m and v, or SGD's velocity), the per-step scalars and the finite check, as tasks of a graph that
 * also holds the parameters ({@link OpsParameters}) and the gradients ({@link DeviceTensors}).
 *
 * <p>Parameters, gradients and state stay on the device across steps. Per execution only the eleven floats of
 * {@code hyper} go in ({@link #prepare}) and the 64 floats of {@code status} come back ({@link #nonFinite}).
 * One task per tensor runs {@link KernelsOptimizer#adamw} or {@link KernelsOptimizer#sgd}; one more adds the
 * finite flags. A trainer adds these tasks behind its backward kernels with {@link #addTasks}; a test builds
 * a graph of its own, with the gradients as an input.
 *
 * <p>The step count and the learning rate are the caller's: {@link #prepare} takes the number of completed steps
 * and the rate of this step and computes the bias corrections in double, as {@code Optimizer.step} does.
 * Not thread-safe.
 */
public final class DeviceOptimizer {

    /** Elements that one work item of an update kernel handles, one after the other. */
    static final int CHUNK = 16;
    /** Work items of {@link KernelsOptimizer#sumFlags}; also the length of {@code status}. */
    static final int GROUPS = 64;

    private final Settings settings;
    private final OpsParameters parameters;
    private final DeviceTensors gradients;
    private final DeviceTensors first;
    private final DeviceTensors second;
    private final FloatArray hyper = new FloatArray(KernelsOptimizer.HYPER_SIZE);
    private final FloatArray flags;
    private final FloatArray status = new FloatArray(GROUPS);
    private final int flagCount;
    private boolean executed;
    private boolean stateDirty;

    /**
     * @param parameters the parameters on the device; updated in place by the tasks
     * @param gradients  the gradients on the device, read by the tasks
     * @param settings   the optimiser and its hyperparameters
     * @throws IllegalArgumentException if parameters and gradients are for different configs
     */
    public DeviceOptimizer(OpsParameters parameters, DeviceTensors gradients, Settings settings) {
        this.parameters = Objects.requireNonNull(parameters, "parameters");
        this.gradients = Objects.requireNonNull(gradients, "gradients");
        this.settings = Objects.requireNonNull(settings, "settings");
        if (!parameters.config().equals(gradients.config())) {
            throw new IllegalArgumentException("parameters are for " + parameters.config() + ", gradients for "
                    + gradients.config());
        }
        this.first = new DeviceTensors(parameters.config());
        this.second = settings.kind() == Kind.ADAMW ? new DeviceTensors(parameters.config()) : null;
        int chunks = 0;
        for (FloatArray a : gradients.arrays()) {
            chunks += (a.getSize() + CHUNK - 1) / CHUNK;
        }
        this.flagCount = chunks;
        this.flags = new FloatArray(chunks);
        flags.init(0f);
        status.init(0f);
        hyper.init(0f);
    }

    public Settings settings() {
        return settings;
    }

    /**
     * Adds the update tasks to {@code graph}: the state and the flags are declared on the device, the {@code hyper}
     * array is sent before every execution, {@code status} comes back after every execution, and the parameters,
     * the first moment and the second moment can be read back on demand ({@code TornadoExecutionResult.transferToHost}
     * with {@link #stateArrays()} and {@link OpsParameters#declareHostReadback}). The graph must declare the
     * parameters and the gradients, and the tasks run in the order of the graph: put them after the kernels that
     * write the gradients.
     *
     * @return the graph
     */
    public TaskGraph addTasks(TaskGraph graph) {
        first.declareOn(graph, DataTransferMode.FIRST_EXECUTION);
        if (second != null) {
            second.declareOn(graph, DataTransferMode.FIRST_EXECUTION);
        }
        graph.transferToDevice(DataTransferMode.FIRST_EXECUTION, flags, status)
                .transferToDevice(DataTransferMode.EVERY_EXECUTION, hyper);
        String[] names = gradients.names();
        int flagBase = 0;
        for (String name : names) {
            FloatArray theta = parameters.get(name);
            FloatArray grad = gradients.get(name);
            int n = theta.getSize();
            int weight = isWeight(name) ? 1 : 0;
            if (settings.kind() == Kind.ADAMW) {
                graph.task("opt_" + name, KernelsOptimizer::adamw, theta, grad, first.get(name), second.get(name),
                        hyper, flags, n, CHUNK, flagBase, weight);
            } else {
                graph.task("opt_" + name, KernelsOptimizer::sgd, theta, grad, first.get(name), hyper, flags, n,
                        CHUNK, flagBase, weight);
            }
            flagBase += (n + CHUNK - 1) / CHUNK;
        }
        graph.task("opt_flags", KernelsOptimizer::sumFlags, flags, status, flagCount, GROUPS);
        graph.transferToHost(DataTransferMode.EVERY_EXECUTION, status);
        graph.transferToHost(DataTransferMode.UNDER_DEMAND, (Object[]) stateArrays());
        return graph;
    }

    /**
     * Sets the scalars of the next execution, with {@code Optimizer.step}'s arithmetic: {@code t = steps + 1} and the
     * bias corrections {@code 1 - beta^t} in double.
     *
     * @param completedSteps the number of steps done so far, at least 0
     * @param lr             the learning rate of this step, finite and at least 0
     * @throws IllegalArgumentException if a value is out of range
     */
    public void prepare(long completedSteps, double lr) {
        prepare(completedSteps, lr, true);
    }

    /**
     * {@link #prepare(long, double)} with the update switch: with {@code apply} false the tasks of the next execution
     * leave parameters and state alone (a microbatch that is not the last of its batch).
     */
    public void prepare(long completedSteps, double lr, boolean apply) {
        if (completedSteps < 0) {
            throw new IllegalArgumentException("completedSteps must be >= 0, got " + completedSteps);
        }
        if (!(lr >= 0) || !Double.isFinite(lr)) {
            throw new IllegalArgumentException("lr must be >= 0 and finite, got " + lr);
        }
        long t = completedSteps + 1;
        hyper.set(KernelsOptimizer.HYPER_LR, (float) lr);
        hyper.set(KernelsOptimizer.HYPER_BETA1, (float) settings.beta1());
        hyper.set(KernelsOptimizer.HYPER_BETA2, (float) settings.beta2());
        hyper.set(KernelsOptimizer.HYPER_ONE_MINUS_BETA1, (float) (1 - settings.beta1()));
        hyper.set(KernelsOptimizer.HYPER_ONE_MINUS_BETA2, (float) (1 - settings.beta2()));
        hyper.set(KernelsOptimizer.HYPER_EPS, (float) settings.eps());
        hyper.set(KernelsOptimizer.HYPER_WEIGHT_DECAY, (float) settings.weightDecay());
        hyper.set(KernelsOptimizer.HYPER_MOMENTUM, (float) settings.momentum());
        hyper.set(KernelsOptimizer.HYPER_L2, (float) settings.l2());
        hyper.set(KernelsOptimizer.HYPER_CORRECTION1, (float) (1 - StrictMath.pow(settings.beta1(), t)));
        hyper.set(KernelsOptimizer.HYPER_CORRECTION2, (float) (1 - StrictMath.pow(settings.beta2(), t)));
        hyper.set(KernelsOptimizer.HYPER_APPLY, apply ? 1f : 0f);
    }

    /**
     * The number of chunks with a NaN or infinite gradient or updated parameter in the last execution; 0 means
     * the step was sound. Valid after the execution that ran the tasks.
     */
    public int nonFinite() {
        float sum = 0f;
        for (int g = 0; g < GROUPS; g++) {
            sum += status.get(g);
        }
        return (int) sum;
    }

    /**
     * Call before every {@code plan.execute()} of a plan that holds these tasks. After {@link #loadState}, it
     * sends the state to the device once, if the plan has run already (before its first execution the
     * {@code FIRST_EXECUTION} transfer does it).
     */
    public void beforeExecute(TornadoExecutionPlan plan) {
        if (executed && stateDirty) {
            plan.transferToDevice((Object[]) stateArrays());
        }
        stateDirty = false;
        executed = true;
    }

    /**
     * Copies the state of a host optimiser (a checkpoint) into the device arrays. The next {@link #beforeExecute}
     * sends it to the device.
     *
     * @throws IllegalArgumentException if the optimiser is of another kind or its state does not fit
     */
    public void loadState(Optimizer optimizer) {
        if (optimizer.settings().kind() != settings.kind()) {
            throw new IllegalArgumentException("optimiser is " + optimizer.settings().kind().id() + ", not "
                    + settings.kind().id());
        }
        if (settings.kind() == Kind.ADAMW) {
            first.set(optimizer.m());
            second.set(optimizer.v());
        } else {
            first.set(optimizer.u());
        }
        stateDirty = true;
    }

    /** The state arrays on the host side: first moment (or velocity), then second moment. */
    FloatArray[] stateArrays() {
        FloatArray[] a = first.arrays();
        if (second == null) {
            return a;
        }
        FloatArray[] b = second.arrays();
        FloatArray[] both = new FloatArray[a.length + b.length];
        System.arraycopy(a, 0, both, 0, a.length);
        System.arraycopy(b, 0, both, a.length, b.length);
        return both;
    }

    /**
     * Copies the state, which must have been transferred to the host, into the live arrays of a host optimiser (a
     * checkpoint is written from it). The step count is the caller's.
     *
     * @throws IllegalArgumentException if the optimiser is of another kind
     */
    void storeInto(Optimizer optimizer) {
        if (optimizer.settings().kind() != settings.kind()) {
            throw new IllegalArgumentException("optimiser is " + optimizer.settings().kind().id() + ", not "
                    + settings.kind().id());
        }
        store(first, settings.kind() == Kind.ADAMW ? optimizer.m() : optimizer.u());
        if (second != null) {
            store(second, optimizer.v());
        }
    }

    private static void store(DeviceTensors from, Map<String, float[]> to) {
        for (String name : from.names()) {
            float[] target = to.get(name);
            OpsCopy.copy(from.get(name), 0, target, 0, target.length);
        }
    }

    /** AdamW's m, or SGD's velocity, as host arrays (valid after the state was transferred to the host). */
    public Map<String, float[]> firstState() {
        return copy(first);
    }

    /**
     * AdamW's v as host arrays (valid after the state was transferred to the host).
     *
     * @throws IllegalStateException for SGD
     */
    public Map<String, float[]> secondState() {
        if (second == null) {
            throw new IllegalStateException("SGD has no second moment");
        }
        return copy(second);
    }

    /** True for tensors whose name ends in {@code .weight}: the rule of {@code Optimizer}. */
    static boolean isWeight(String name) {
        return name.endsWith(".weight");
    }

    private static Map<String, float[]> copy(DeviceTensors tensors) {
        Map<String, float[]> m = new LinkedHashMap<>();
        for (String name : tensors.names()) {
            m.put(name, tensors.copyOf(name));
        }
        return m;
    }
}
