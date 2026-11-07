package sternhalma.tornado;

import java.util.Arrays;
import java.util.SplittableRandom;

import sternhalma.nn.Encoder;
import sternhalma.nn.HexGrid;
import sternhalma.nn.NetworkConfig;
import sternhalma.nn.Parameters;
import sternhalma.nn.ReferenceOps;
import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.common.TornadoDevice;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.enums.ProfilerMode;
import uk.ac.manchester.tornado.api.exceptions.TornadoExecutionPlanException;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;

/**
 * Prints the cost of the Ops... kernels: {@link OpsHeads} (policy and value head, trunk streamed in) and
 * {@link OpsKernels#residualAddRelu} (data resident), for SMALL and LARGE at several batch sizes, against the
 * same work on the CPU with {@link ReferenceOps}. A main, not a test. Run it with the SDK's argfile from a
 * directory without jars: {@code java @$TORNADOVM_HOME/tornado-argfile -cp <classes and jars>
 * sternhalma.tornado.OpsProbe}.
 */
public final class OpsProbe {

    private static final int WARM_RUNS = 30;

    private OpsProbe() {
    }

    public static void main(String[] args) {
        TornadoSupport.Status status = TornadoSupport.status();
        System.out.println("status: " + status);
        if (!status.usable()) {
            return;
        }
        TornadoDevice device = TornadoSupport.defaultDevice();
        for (NetworkConfig config : new NetworkConfig[] {NetworkConfig.small(2), NetworkConfig.large(2)}) {
            for (int batch : new int[] {1, 64, 256, 1024}) {
                heads(config, batch, device);
                residual(config, batch, device);
            }
        }
    }

    private static void heads(NetworkConfig config, int batch, TornadoDevice device) {
        Parameters p = Parameters.init(config, 1);
        float[] trunk = OpsTesting.trunk(new SplittableRandom(1), batch, config.channels(), 0f);
        float[] logits = new float[batch * Encoder.POLICY_SIZE];
        float[] values = new float[batch * config.players()];
        try (OpsHeads heads = new OpsHeads(new OpsParameters(p), batch, device)) {
            heads.apply(trunk, logits, values);
            long[] wall = new long[WARM_RUNS];
            long[] kernel = new long[WARM_RUNS];
            for (int i = 0; i < WARM_RUNS; i++) {
                long t = System.nanoTime();
                heads.apply(trunk, logits, values);
                wall[i] = System.nanoTime() - t;
                kernel[i] = heads.lastProfile().kernelNanos();
            }
            long t = System.nanoTime();
            OpsTesting.policyReference(p, trunk, batch);
            OpsTesting.valueReference(p, trunk, batch);
            long cpu = System.nanoTime() - t;
            System.out.printf("heads    C=%d batch=%4d: warm median %.3f ms, kernels %.3f ms (both heads);"
                            + " CPU reference once %.3f ms%n",
                    config.channels(), batch, median(wall) / 1e6, median(kernel) / 1e6, cpu / 1e6);
        }
    }

    private static void residual(NetworkConfig config, int batch, TornadoDevice device) {
        int n = batch * HexGrid.ROWS * config.channels();
        FloatArray a = FloatArray.fromArray(OpsTesting.uniform(new SplittableRandom(2), n, 1));
        FloatArray b = FloatArray.fromArray(OpsTesting.uniform(new SplittableRandom(3), n, 1));
        FloatArray out = new FloatArray(n);
        TaskGraph graph = new TaskGraph("residual")
                .transferToDevice(DataTransferMode.FIRST_EXECUTION, a, b)
                .task("t", OpsKernels::residualAddRelu, a, b, out, batch, config.channels())
                .transferToHost(DataTransferMode.UNDER_DEMAND, out);
        try (TornadoExecutionPlan plan = new TornadoExecutionPlan(graph.snapshot()).withDevice(device)
                .withProfiler(ProfilerMode.SILENT)) {
            plan.execute();
            long[] kernel = new long[WARM_RUNS];
            for (int i = 0; i < WARM_RUNS; i++) {
                kernel[i] = plan.execute().getProfilerResult().getDeviceKernelTime();
            }
            System.out.printf("residual C=%d batch=%4d: kernel %.3f ms for %.1f MB read + written%n",
                    config.channels(), batch, median(kernel) / 1e6, 12.0 * n / 1e6);
        } catch (TornadoExecutionPlanException e) {
            throw new IllegalStateException(e);
        }
    }

    private static double median(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }
}
