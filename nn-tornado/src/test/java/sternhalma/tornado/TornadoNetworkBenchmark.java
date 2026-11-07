package sternhalma.tornado;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import sternhalma.engine.Position;
import sternhalma.nn.Encoder;
import sternhalma.nn.ForwardPass;
import sternhalma.nn.Network;
import sternhalma.nn.NetworkConfig;
import sternhalma.nn.Parameters;
import sternhalma.nn.VectorNetwork;
import sternhalma.tornado.KernelsHexConvLayer.Variant;

/**
 * Evaluations per second of the whole forward pass in one process: the scalar reference {@link Network} and the
 * {@link VectorNetwork} on one CPU thread, and {@link TornadoNetwork} on the device, for TINY, SMALL and LARGE
 * (2 players) at batch 1, 16, 64, 256 and 1024. Not a JUnit test; run it with
 *
 * <pre>
 * devenv shell -- ./gradlew --no-build-cache :nn-tornado:benchmark
 * </pre>
 *
 * <p>Method: the inputs are 1,024 encoded positions of seeded random games, the weights {@link Parameters#init}.
 * Every case runs {@code forward} for at least {@link #WARMUP_NANOS} and {@link #WARMUP_CALLS} calls (the JIT
 * compiles the CPU loops; the device compiles the bucket's kernels), then {@link #ROUNDS} rounds of at least
 * {@link #ROUND_NANOS} and one call each; the table shows the median round and the slowest and fastest. The time
 * of a device call is the wall time of {@code forward}: it includes copying the input into the off-heap buffer,
 * the transfer to the device, all kernels, the transfer back and the copy into the caller's arrays. The rates
 * come from an instance without the profiler, as a search would use it; the kernel time comes from a second,
 * {@link TornadoNetwork#profiled} instance, whose wall time is printed too, because the profiler is not free.
 *
 * <p>The CPU paths compute one position after the other, so their rate does not depend on the batch (measured in
 * docs/network.md). A CPU case whose single call would take longer than {@link #CPU_CALL_LIMIT_NANOS}, judged
 * from the rate at the smaller batches, is therefore not run, and the table says so.
 *
 * <p>A second table compares the convolution kernels of the whole pass at small batches, which is where
 * {@link TornadoNetwork#variantFor} comes from. Arguments {@code speed} or {@code kernels} run one table only
 * ({@code --args=kernels} for the Gradle task).
 */
public final class TornadoNetworkBenchmark {

    private static final long WARMUP_NANOS = 2_000_000_000L;
    private static final int WARMUP_CALLS = 10;
    private static final long ROUND_NANOS = 1_000_000_000L;
    private static final int ROUNDS = 3;
    private static final long CPU_CALL_LIMIT_NANOS = 3_000_000_000L;
    private static final int[] BATCHES = {1, 16, 64, 256, 1024};

    private static double checksum;

    private TornadoNetworkBenchmark() {
    }

    /** One measured case: evaluations per second {min, median, max}. */
    private record Rate(double min, double median, double max) {
    }

    /** Medians over {@link #PROFILED_CALLS} calls of a profiled instance: wall time and summed kernel time. */
    private record Profiled(double wallMillis, double kernelMillis) {
    }

    private static final int PROFILED_CALLS = 20;

    public static void main(String[] args) {
        TornadoSupport.Status status = TornadoSupport.status();
        System.out.println("TornadoVM: " + status);
        if (!status.usable()) {
            return;
        }
        System.out.printf(Locale.ROOT, "java %s, %s %s, %d available cores, CPU paths use 1 thread%n",
                System.getProperty("java.version"), System.getProperty("os.name"), System.getProperty("os.arch"),
                Runtime.getRuntime().availableProcessors());
        printLoad("before");
        int largest = BATCHES[BATCHES.length - 1];
        float[] input = input(largest);
        List<String> sections = args.length == 0 ? List.of("speed", "kernels") : List.of(args);
        if (sections.contains("speed")) {
            speed(input);
        }
        if (sections.contains("kernels")) {
            kernels(input);
        }
        System.out.printf(Locale.ROOT, "%nchecksum %.6g%n", checksum);
        printLoad("after");
    }

    /** The main table: every path, preset and batch, and where the GPU overtakes one CPU thread. */
    private static void speed(float[] input) {

        System.out.println();
        System.out.println("| Preset | Batch | scalar CPU eval/s | Vector API CPU eval/s | GPU eval/s (min to max)"
                + " | GPU ms per call | GPU kernel ms (profiled) | GPU ms per call (profiled) | GPU / Vector |");
        System.out.println("|---|---|---|---|---|---|---|---|---|");
        List<String> crossovers = new ArrayList<>();
        for (NetworkConfig config : List.of(NetworkConfig.tiny(2), NetworkConfig.small(2), NetworkConfig.large(2))) {
            String name = name(config);
            Parameters parameters = Parameters.init(config, 1);
            double scalarRate = 0;
            double vectorRate = 0;
            Integer crossover = null;
            try (TornadoNetwork gpu = new TornadoNetwork(parameters);
                    TornadoNetwork profiledGpu = TornadoNetwork.profiled(parameters, TornadoSupport.defaultDevice(),
                            TornadoNetwork.DEFAULT_MAX_BATCH)) {
                for (int batch : BATCHES) {
                    Rate scalar = cpuAffordable(scalarRate, batch) ? measure(new Network(parameters), input, batch)
                            : null;
                    Rate vector = cpuAffordable(vectorRate, batch)
                            ? measure(new VectorNetwork(parameters), input, batch) : null;
                    scalarRate = scalar != null ? scalar.median : scalarRate;
                    vectorRate = vector != null ? vector.median : vectorRate;
                    Rate device = measure(gpu, input, batch);
                    Profiled profile = profile(profiledGpu, input, batch);
                    double cpu = vector != null ? vector.median : vectorRate;
                    if (crossover == null && device.median > cpu) {
                        crossover = batch;
                    }
                    System.out.printf(Locale.ROOT,
                            "| %s | %d | %s | %s | %,.0f (%,.0f to %,.0f) | %.3f | %.3f | %.3f | %.2f |%n", name, batch,
                            cell(scalar, scalarRate), cell(vector, vectorRate), device.median, device.min, device.max,
                            1000.0 * batch / device.median, profile.kernelMillis, profile.wallMillis,
                            device.median / cpu);
                }
            }
            crossovers.add(name + ": " + (crossover == null ? "GPU never faster than one Vector API thread"
                    : "GPU faster than one Vector API thread from batch " + crossover
                            + " on (of the batches measured)"));
        }
        System.out.println();
        crossovers.forEach(System.out::println);
    }

    /** The kernel choice table: the whole pass with every convolution on one kernel. */
    private static void kernels(float[] input) {
        System.out.println();
        System.out.println("Kernel choice: GPU eval/s (median) of the whole pass with every convolution on one kernel");
        System.out.println("| Preset | Batch | NAIVE | TILED | SIMDGROUP |");
        System.out.println("|---|---|---|---|---|");
        for (NetworkConfig config : List.of(NetworkConfig.small(2), NetworkConfig.large(2))) {
            Parameters parameters = Parameters.init(config, 1);
            for (int batch : new int[] {1, 2, 4, 8, 16, 32, 64}) {
                StringBuilder row = new StringBuilder(String.format(Locale.ROOT, "| %s | %d |", name(config), batch));
                for (Variant variant : Variant.values()) {
                    try (TornadoNetwork gpu = new TornadoNetwork(parameters, TornadoSupport.defaultDevice(), 1024,
                            variant, false)) {
                        row.append(String.format(Locale.ROOT, " %,.0f |", measure(gpu, input, batch).median));
                    }
                }
                System.out.println(row);
            }
        }
    }

    private static String name(NetworkConfig config) {
        return switch (config.channels()) {
            case 8 -> "TINY";
            case 32 -> "SMALL";
            default -> "LARGE";
        };
    }

    /** True if one call at this batch takes at most {@link #CPU_CALL_LIMIT_NANOS} at the rate measured so far. */
    private static boolean cpuAffordable(double rateSoFar, int batch) {
        return rateSoFar == 0 || batch / rateSoFar * 1e9 <= CPU_CALL_LIMIT_NANOS;
    }

    private static String cell(Rate rate, double earlier) {
        if (rate == null) {
            return String.format(Locale.ROOT, "not run (about %,.0f, flat in batch)", earlier);
        }
        return String.format(Locale.ROOT, "%,.0f", rate.median);
    }

    /** {@code batch} encoded positions of seeded random games, two players. */
    private static float[] input(int batch) {
        List<Position> positions = Playouts.positions(2, 12, 200, 2026);
        float[] input = new float[batch * Encoder.INPUT_SIZE];
        for (int b = 0; b < batch; b++) {
            Encoder.encode(positions.get((int) ((long) b * positions.size() / batch)), input, b * Encoder.INPUT_SIZE);
        }
        return input;
    }

    private static void printLoad(String when) {
        System.out.printf(Locale.ROOT, "system load average (1 min) %s: %.1f%n", when,
                ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage());
    }

    private static Rate measure(ForwardPass network, float[] input, int batch) {
        float[] policy = new float[batch * Encoder.POLICY_SIZE];
        float[] values = new float[batch * network.config().players()];
        long start = System.nanoTime();
        int calls = 0;
        while (calls < WARMUP_CALLS || System.nanoTime() - start < WARMUP_NANOS) {
            network.forward(input, batch, policy, values);
            calls++;
            if (!(network instanceof TornadoNetwork) && System.nanoTime() - start > WARMUP_NANOS) {
                break; // a slow CPU call: the time limit is enough warm-up
            }
        }
        double[] rates = new double[ROUNDS];
        for (int r = 0; r < ROUNDS; r++) {
            long begin = System.nanoTime();
            long n = 0;
            long elapsed;
            do {
                network.forward(input, batch, policy, values);
                n++;
                elapsed = System.nanoTime() - begin;
            } while (elapsed < ROUND_NANOS);
            rates[r] = n * (double) batch / (elapsed / 1e9);
        }
        checksum += policy[(int) (System.nanoTime() % policy.length)] + values[0];
        Arrays.sort(rates);
        return new Rate(rates[0], rates[ROUNDS / 2], rates[ROUNDS - 1]);
    }

    /** Wall and kernel time of a profiled instance, after {@link #WARMUP_CALLS} calls. */
    private static Profiled profile(TornadoNetwork network, float[] input, int batch) {
        float[] policy = new float[batch * Encoder.POLICY_SIZE];
        float[] values = new float[batch * network.config().players()];
        for (int i = 0; i < WARMUP_CALLS; i++) {
            network.forward(input, batch, policy, values);
        }
        double[] wall = new double[PROFILED_CALLS];
        double[] kernel = new double[PROFILED_CALLS];
        for (int i = 0; i < PROFILED_CALLS; i++) {
            long begin = System.nanoTime();
            network.forward(input, batch, policy, values);
            wall[i] = (System.nanoTime() - begin) / 1e6;
            kernel[i] = network.lastProfile().kernelNanos() / 1e6;
        }
        Arrays.sort(wall);
        Arrays.sort(kernel);
        return new Profiled(wall[PROFILED_CALLS / 2], kernel[PROFILED_CALLS / 2]);
    }
}
