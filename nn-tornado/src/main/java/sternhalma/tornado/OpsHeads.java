package sternhalma.tornado;

import java.util.Objects;

import sternhalma.nn.Encoder;
import sternhalma.nn.HexGrid;
import sternhalma.nn.NetworkConfig;
import uk.ac.manchester.tornado.api.GridScheduler;
import uk.ac.manchester.tornado.api.KernelContext;
import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.common.TornadoDevice;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.enums.ProfilerMode;
import uk.ac.manchester.tornado.api.exceptions.TornadoExecutionPlanException;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;

/**
 * The policy head and the value head of a network on a TornadoVM device, for a fixed batch size: from the
 * trunk activation [batch][122][C] (the output of the last block) to the policy logits [batch][121][2] and the
 * values [batch][P] of docs/network.md, "Layers", rows 4 to 7.
 *
 * <p>Both heads write into one output buffer ({@link OpsKernels#outputSize}), so one transfer brings all
 * results back to the host. The legal-move softmax stays on the host ({@code Evaluator}).
 *
 * <p>For a full forward pass, add the head tasks to the trunk's own graph with {@link #addTasks} and
 * {@link #schedule}, so that the trunk activation never leaves the device; this class uses exactly these two
 * methods for its own plan. Its own plan streams the trunk activation in on every call, which is useful for
 * tests and measurements.
 *
 * <p>The parameters stay on the device (see {@link OpsParameters}); after {@link OpsParameters#reload()} the
 * next {@link #apply} uses the new values. Not thread-safe. {@link #close()} frees the device memory.
 */
public final class OpsHeads implements AutoCloseable {

    /** Name of the task graph of this class's own plan. */
    public static final String GRAPH = "heads";
    /** Task name of the policy head inside a graph built by {@link #addTasks}. */
    public static final String POLICY_TASK = "policyHead";
    /** Task name of the value head inside a graph built by {@link #addTasks}. */
    public static final String VALUE_TASK = "valueHead";

    private final NetworkConfig config;
    private final int batch;
    private final FloatArray trunk;
    private final FloatArray out;
    private final TornadoExecutionPlan plan;
    private final OpsParameters.Residency residency;
    private ExecutionProfile lastProfile;

    /**
     * @param parameters the network's parameters on the device
     * @param batch      positions per {@link #apply} call, at least 1
     * @param device     the device that runs the kernels
     * @throws IllegalArgumentException if batch is below 1
     */
    public OpsHeads(OpsParameters parameters, int batch, TornadoDevice device) {
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(device, "device");
        if (batch < 1) {
            throw new IllegalArgumentException("batch must be >= 1, got " + batch);
        }
        this.config = parameters.config();
        this.batch = batch;
        this.trunk = new FloatArray(batch * HexGrid.ROWS * config.channels());
        this.out = new FloatArray(OpsKernels.outputSize(batch, config.players()));
        TaskGraph graph = parameters.declareOn(new TaskGraph(GRAPH))
                .transferToDevice(DataTransferMode.EVERY_EXECUTION, trunk);
        addTasks(graph, parameters, trunk, out, batch).transferToHost(DataTransferMode.EVERY_EXECUTION, out);
        GridScheduler grids = new GridScheduler();
        schedule(grids, GRAPH, batch);
        this.plan = new TornadoExecutionPlan(graph.snapshot()).withDevice(device).withGridScheduler(grids)
                .withProfiler(ProfilerMode.SILENT);
        this.residency = parameters.residency(plan);
    }

    /**
     * Adds the policy head task {@value #POLICY_TASK} and the value head task {@value #VALUE_TASK} to a graph.
     * The graph must already declare the parameters ({@link OpsParameters#declareOn}) and must make
     * {@code trunk} available on the device; the caller transfers {@code out} to the host. The value head needs
     * its grid: call {@link #schedule} with the graph's name.
     *
     * @param trunk [batch][122][C], the output of the last block
     * @param out   {@link OpsKernels#outputSize} floats: logits [batch][121][2], then values [batch][P]
     * @return the graph
     * @throws IllegalArgumentException if an array is too short
     */
    public static TaskGraph addTasks(TaskGraph graph, OpsParameters parameters, FloatArray trunk, FloatArray out,
            int batch) {
        NetworkConfig config = parameters.config();
        int c = config.channels();
        int p = config.players();
        if (trunk.getSize() < batch * HexGrid.ROWS * c) {
            throw new IllegalArgumentException("trunk needs " + batch * HexGrid.ROWS * c + " floats, has "
                    + trunk.getSize());
        }
        if (out.getSize() < OpsKernels.outputSize(batch, p)) {
            throw new IllegalArgumentException("out needs " + OpsKernels.outputSize(batch, p) + " floats, has "
                    + out.getSize());
        }
        return graph
                .task(POLICY_TASK, OpsKernels::policyHead, trunk, parameters.policyWeight(),
                        parameters.policyBias(), out, batch, c)
                .task(VALUE_TASK, OpsKernels::valueHead, new KernelContext(), trunk, parameters.dense1Weight(),
                        parameters.dense1Bias(), parameters.dense2Weight(), parameters.dense2Bias(), out,
                        OpsKernels.valuesOffset(batch), c, config.valueHidden(), p);
    }

    /** Registers the grid of the value head task of graph {@code graphName} for {@code batch} positions. */
    public static void schedule(GridScheduler grids, String graphName, int batch) {
        grids.addWorkerGrid(graphName + "." + VALUE_TASK, OpsKernels.valueHeadGrid(batch));
    }

    /**
     * Splits a head output buffer into the arrays of {@code ForwardPass.forward}: the first
     * {@code batch * 242} floats into {@code policyLogits}, the next {@code batch * players} into
     * {@code values}.
     *
     * @throws IndexOutOfBoundsException if an array is too short
     */
    public static void unpack(FloatArray out, int batch, int players, float[] policyLogits, float[] values) {
        OpsCopy.copy(out, 0, policyLogits, 0, batch * Encoder.POLICY_SIZE);
        OpsCopy.copy(out, OpsKernels.valuesOffset(batch), values, 0, batch * players);
    }

    /**
     * Runs both heads on the device.
     *
     * @param trunkActivation {@code batch * 122 * C} floats, [batch][122][C]
     * @param policyLogits    output, at least {@code batch * 242} floats
     * @param values          output, at least {@code batch * P} floats, each in [-1, 1]
     * @throws IllegalArgumentException if an array has the wrong length
     */
    public void apply(float[] trunkActivation, float[] policyLogits, float[] values) {
        if (trunkActivation.length != trunk.getSize()) {
            throw new IllegalArgumentException("trunk: expected " + trunk.getSize() + " floats, got "
                    + trunkActivation.length);
        }
        if (policyLogits.length < batch * Encoder.POLICY_SIZE || values.length < batch * config.players()) {
            throw new IllegalArgumentException("outputs too short: policyLogits " + policyLogits.length
                    + ", values " + values.length + " for batch " + batch);
        }
        OpsCopy.copy(trunkActivation, 0, trunk, 0, trunkActivation.length);
        residency.beforeExecute();
        lastProfile = ExecutionProfile.of(plan.execute().getProfilerResult());
        unpack(out, batch, config.players(), policyLogits, values);
    }

    /**
     * Cost of the last {@link #apply} call.
     *
     * @throws IllegalStateException if {@code apply} has not run yet
     */
    public ExecutionProfile lastProfile() {
        if (lastProfile == null) {
            throw new IllegalStateException("apply has not run yet");
        }
        return lastProfile;
    }

    /** Positions per {@link #apply} call. */
    public int batch() {
        return batch;
    }

    /** Frees the device memory of the plan. */
    @Override
    public void close() {
        try {
            plan.close();
        } catch (TornadoExecutionPlanException e) {
            throw new IllegalStateException("could not release the execution plan", e);
        }
    }
}
