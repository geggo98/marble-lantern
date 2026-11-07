package sternhalma.tornado;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import sternhalma.nn.NetworkConfig;
import sternhalma.nn.Parameters;
import sternhalma.nn.Tensor;
import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;

/**
 * The parameters of one network as TornadoVM arrays, for task graphs that keep them on the device.
 *
 * <p>Each tensor of {@link Parameters#shapes} becomes one {@link FloatArray} with the same name, shape and
 * row-major layout (docs/network.md, "Tensor layout"). The constructor copies the values; later changes to
 * the {@link Parameters} are not seen until {@link #reload()}.
 *
 * <p>How a plan uses it:
 *
 * <ol>
 * <li>{@link #declareOn(TaskGraph)} adds all arrays with {@link DataTransferMode#FIRST_EXECUTION}: they are
 * copied once, at the first execution of the plan, and stay on the device.</li>
 * <li>Before every {@code execute()}, the plan owner calls {@link Residency#beforeExecute()} on the
 * {@link Residency} it got from {@link #residency(TornadoExecutionPlan)}. After a {@link #reload()}, that
 * copies the new values to the device once; otherwise it does nothing.</li>
 * </ol>
 *
 * <p>One instance can serve several plans; each has its own {@link Residency}. Not thread-safe: no plan may
 * execute during {@link #reload()}.
 */
public final class OpsParameters {

    private final Parameters source;
    private final LinkedHashMap<String, FloatArray> arrays = new LinkedHashMap<>();
    private final FloatArray[] all;
    private long version;

    /**
     * Copies every tensor of {@code parameters} into a new {@link FloatArray}.
     *
     * @throws NullPointerException if parameters is null
     */
    public OpsParameters(Parameters parameters) {
        this.source = Objects.requireNonNull(parameters, "parameters");
        for (Map.Entry<String, Tensor> e : parameters.tensors().entrySet()) {
            arrays.put(e.getKey(), FloatArray.fromArray(e.getValue().data()));
        }
        this.all = arrays.values().toArray(new FloatArray[0]);
    }

    /** The size of the network. */
    public NetworkConfig config() {
        return source.config();
    }

    /**
     * The array of the tensor with the given name ({@link Parameters#shapes} names, for example
     * {@code head_policy.weight}). The array is owned by this object; callers must not write to it.
     *
     * @throws IllegalArgumentException if the config has no tensor of that name
     */
    public FloatArray get(String name) {
        FloatArray a = arrays.get(name);
        if (a == null) {
            throw new IllegalArgumentException("no tensor named " + name + " in " + config());
        }
        return a;
    }

    /** {@code head_policy.weight}, [2][C]. */
    public FloatArray policyWeight() {
        return get("head_policy.weight");
    }

    /** {@code head_policy.bias}, [2]. */
    public FloatArray policyBias() {
        return get("head_policy.bias");
    }

    /** {@code head_value.dense1.weight}, [H][C]. */
    public FloatArray dense1Weight() {
        return get("head_value.dense1.weight");
    }

    /** {@code head_value.dense1.bias}, [H]. */
    public FloatArray dense1Bias() {
        return get("head_value.dense1.bias");
    }

    /** {@code head_value.dense2.weight}, [P][H]. */
    public FloatArray dense2Weight() {
        return get("head_value.dense2.weight");
    }

    /** {@code head_value.dense2.bias}, [P]. */
    public FloatArray dense2Bias() {
        return get("head_value.dense2.bias");
    }

    /**
     * Adds every array to the graph with {@link DataTransferMode#FIRST_EXECUTION}. Call it before the tasks
     * that read the arrays.
     *
     * @return the graph
     */
    public TaskGraph declareOn(TaskGraph graph) {
        return graph.transferToDevice(DataTransferMode.FIRST_EXECUTION, (Object[]) all);
    }

    /**
     * Copies the current values of the {@link Parameters} given to the constructor into the arrays, for
     * example after an in-place training step. Plans see the new values at their next execution, through
     * {@link Residency#beforeExecute()}.
     */
    public void reload() {
        int i = 0;
        for (Tensor t : source.tensors().values()) {
            float[] data = t.data();
            FloatArray a = all[i++];
            OpsCopy.copy(data, 0, a, 0, data.length);
        }
        version++;
    }

    /** Number of {@link #reload()} calls so far. */
    public long version() {
        return version;
    }

    /**
     * A tracker for one plan whose graph went through {@link #declareOn}. Create it together with the plan,
     * before the plan's first execution.
     */
    public Residency residency(TornadoExecutionPlan plan) {
        return new Residency(Objects.requireNonNull(plan, "plan"));
    }

    /** Which version of the parameters one plan holds on its device. */
    public final class Residency {

        private final TornadoExecutionPlan plan;
        private long onDevice = -1;

        private Residency(TornadoExecutionPlan plan) {
            this.plan = plan;
        }

        /**
         * Call right before each {@code plan.execute()}. Before the first execution it only records the
         * version, because {@link DataTransferMode#FIRST_EXECUTION} copies the current values anyway. Later it
         * copies all arrays to the device if {@link #reload()} ran since the last copy.
         *
         * @return true if it copied the arrays
         */
        public boolean beforeExecute() {
            if (onDevice < 0) {
                onDevice = version;
                return false;
            }
            if (onDevice == version) {
                return false;
            }
            plan.transferToDevice((Object[]) all);
            onDevice = version;
            return true;
        }
    }
}
