package sternhalma.tornado;

import java.util.Arrays;
import java.util.SplittableRandom;

import sternhalma.nn.HexGrid;
import sternhalma.nn.NetworkConfig;
import sternhalma.nn.Parameters;
import sternhalma.nn.VectorNetwork;
import uk.ac.manchester.tornado.api.GridScheduler;
import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.common.TornadoDevice;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.enums.ProfilerMode;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * GFLOP/s of the hex convolution kernels on the device, for the block convolution of SMALL (32 to 32 channels)
 * and LARGE (64 to 64) at batch 1, 64 and 512, with every array resident on the device (copied once, before the
 * warm-up; the output stays there), and the Vector API CPU path ({@link VectorNetwork}, one thread) for comparison.
 *
 * <p>A main, not a test: run {@code ./gradlew --no-build-cache :nn-tornado:kernelBenchmark}. FLOPs count the
 * matrix work only, 2 per multiply-add, as docs/network.md "Cost per evaluation" does.
 */
public final class KernelsBenchmark {

    private static final int WARM_UP = 30;
    private static final long MEASURE_NANOS = 1_000_000_000L;
    private static final int MIN_RUNS = 20;

    private KernelsBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        TornadoSupport.Status status = TornadoSupport.status();
        System.out.println("status: " + status);
        if (!status.usable()) {
            return;
        }
        TornadoDevice device = TornadoSupport.defaultDevice();
        System.out.println("device: " + device.getDeviceName() + " / " + device.getDescription());
        System.out.println("| C | batch | kernel | kernel time median (ms) | GFLOP/s (kernel time) | wall median (ms)"
                + " | GFLOP/s (wall) |");
        System.out.println("|---|---|---|---|---|---|---|");
        for (int channels : new int[] {32, 64}) {
            for (int batch : new int[] {1, 64, 512}) {
                for (KernelsHexConvLayer.Variant variant : KernelsHexConvLayer.Variant.values()) {
                    deviceCase(variant, channels, batch, device);
                }
            }
        }
        System.out.println();
        System.out.println("| preset | batch | VectorNetwork evaluations/s | GFLOP/s (whole network, matrix work) |");
        System.out.println("|---|---|---|---|");
        for (NetworkConfig config : new NetworkConfig[] {NetworkConfig.small(2), NetworkConfig.large(2)}) {
            for (int batch : new int[] {1, 64, 512}) {
                cpuCase(config, batch);
            }
        }
        System.out.printf("load average: %.1f%n",
                java.lang.management.ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage());
    }

    private static void deviceCase(KernelsHexConvLayer.Variant variant, int channels, int batch,
            TornadoDevice device) throws Exception {
        SplittableRandom random = new SplittableRandom(1);
        FloatArray in = FloatArray.fromArray(KernelsChecks.activations(random, batch, channels));
        FloatArray weight = FloatArray.fromArray(KernelsChecks.uniform(random,
                channels * HexGrid.STENCIL * channels, KernelsChecks.convBound(channels)));
        FloatArray bias = FloatArray.fromArray(KernelsChecks.uniform(random, channels, 0.1));
        FloatArray res = new FloatArray(1);
        FloatArray out = new FloatArray(batch * HexGrid.ROWS * channels);
        IntArray gather = IntArray.fromArray(HexGrid.gatherTable());
        String name = "bench" + variant + channels + "x" + batch;
        TaskGraph graph = new TaskGraph(name)
                .transferToDevice(DataTransferMode.FIRST_EXECUTION, in, weight, bias, res, gather);
        GridScheduler grids = new GridScheduler();
        KernelsHexConvLayer.addTask(graph, grids, variant, "conv", in, gather, weight, bias, res, out, batch, channels,
                channels, false, true);
        graph.transferToHost(DataTransferMode.UNDER_DEMAND, out);
        try (TornadoExecutionPlan plan = new TornadoExecutionPlan(graph.snapshot())) {
            plan.withDevice(device).withProfiler(ProfilerMode.SILENT);
            if (!grids.keySet().isEmpty()) {
                plan.withGridScheduler(grids);
            }
            for (int i = 0; i < WARM_UP; i++) {
                plan.execute();
            }
            long[] wall = new long[10_000];
            long[] kernel = new long[wall.length];
            int runs = 0;
            long start = System.nanoTime();
            while (runs < wall.length && (runs < MIN_RUNS || System.nanoTime() - start < MEASURE_NANOS)) {
                long t = System.nanoTime();
                ExecutionProfile p = ExecutionProfile.of(plan.execute().getProfilerResult());
                wall[runs] = System.nanoTime() - t;
                kernel[runs] = p.kernelNanos();
                if (p.bytesToDevice() > 0 || p.compileNanos() > 0) {
                    throw new IllegalStateException("a warm run copied or compiled: " + p);
                }
                runs++;
            }
            double wallMs = median(wall, runs) / 1e6;
            double kernelMs = median(kernel, runs) / 1e6;
            double flops = 2.0 * KernelsHexConv.multiplyAdds(batch, channels, channels);
            System.out.printf("| %d | %d | %s | %.3f | %.1f | %.3f | %.1f |%n", channels, batch, variant, kernelMs,
                    flops / kernelMs / 1e6, wallMs, flops / wallMs / 1e6);
        }
    }

    private static void cpuCase(NetworkConfig config, int batch) {
        VectorNetwork network = new VectorNetwork(Parameters.init(config, 1));
        SplittableRandom random = new SplittableRandom(2);
        float[] input = new float[batch * HexGrid.ROWS * 7];
        for (int b = 0; b < batch; b++) {
            for (int i = 0; i < HexGrid.CELLS * 7; i++) {
                input[b * HexGrid.ROWS * 7 + i] = (float) random.nextDouble();
            }
        }
        float[] policy = new float[batch * HexGrid.CELLS * 2];
        float[] values = new float[batch * config.players()];
        long warmEnd = System.nanoTime() + 2_000_000_000L;
        while (System.nanoTime() < warmEnd) {
            network.forward(input, batch, policy, values);
        }
        int calls = 0;
        long start = System.nanoTime();
        long elapsed;
        do {
            network.forward(input, batch, policy, values);
            calls++;
            elapsed = System.nanoTime() - start;
        } while (elapsed < 2 * MEASURE_NANOS);
        double perSecond = (double) calls * batch / (elapsed / 1e9);
        System.out.printf("| %s | %d | %.0f | %.1f |%n", config.channels() == 32 ? "SMALL" : "LARGE", batch,
                perSecond, perSecond * 2.0 * matrixMultiplyAdds(config) / 1e9);
    }

    /** docs/network.md, "How the numbers were computed": the multiply-adds of one position. */
    private static long matrixMultiplyAdds(NetworkConfig c) {
        long n = HexGrid.CELLS;
        long ch = c.channels();
        return n * ch * 7 * 7 + c.blocks() * 2L * n * ch * 7 * ch + n * 2 * ch + (long) c.valueHidden() * ch
                + (long) c.players() * c.valueHidden();
    }

    private static double median(long[] values, int n) {
        long[] copy = Arrays.copyOf(values, n);
        Arrays.sort(copy);
        return copy[n / 2];
    }
}
