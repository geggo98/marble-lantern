package sternhalma.nn;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import sternhalma.engine.Position;

/**
 * Evaluations per second of the scalar {@link Network} and the {@link VectorNetwork}, single thread, for
 * SMALL and LARGE (2 players) at batch 1 and 64. Not a JUnit test; run it with
 *
 * <pre>
 * devenv shell -- ./gradlew --no-build-cache :nn:benchmark
 * </pre>
 *
 * <p>Method: the inputs are 64 encoded positions of seeded random games, the weights {@link Parameters#init}.
 * Each case first runs for {@link #WARMUP_NANOS} (so the JIT has compiled the hot loops), then
 * {@link #ROUNDS} rounds of at least {@link #ROUND_NANOS} each; the table shows the median round and the
 * slowest and fastest. A checksum of the outputs is printed so that the JIT cannot drop the work.
 * GFLOP/s counts the matrix work of docs/network.md ("Cost per evaluation"), 2 FLOPs per multiply-add;
 * the element-wise FLOPs (below 1 %) are left out.
 */
public final class NetworkBenchmark {

    private static final long WARMUP_NANOS = 3_000_000_000L;
    private static final long ROUND_NANOS = 1_000_000_000L;
    private static final int ROUNDS = 5;

    private static double checksum;

    private NetworkBenchmark() {
    }

    public static void main(String[] args) {
        System.out.printf(Locale.ROOT, "java %s, %s %s, %d float lanes (%d-bit vectors), %d available cores, "
                + "1 thread used%n", System.getProperty("java.version"), System.getProperty("os.name"),
                System.getProperty("os.arch"), VectorSupport.lanes(), VectorSupport.SPECIES.vectorBitSize(),
                Runtime.getRuntime().availableProcessors());
        printLoad("before");
        List<Position> positions = Playouts.positions(2, 4, 80, 2026);
        float[] input = new float[64 * Encoder.INPUT_SIZE];
        for (int b = 0; b < 64; b++) {
            Encoder.encode(positions.get(b * positions.size() / 64), input, b * Encoder.INPUT_SIZE);
        }

        System.out.println();
        System.out.println("| Preset | Batch | Path | Evaluations/s (median) | min to max | GFLOP/s | x scalar |");
        System.out.println("|---|---|---|---|---|---|---|");
        for (NetworkConfig config : List.of(NetworkConfig.small(2), NetworkConfig.large(2))) {
            Parameters parameters = Parameters.init(config, 1);
            String name = config.channels() == 32 ? "SMALL" : "LARGE";
            for (int batch : new int[] {1, 64}) {
                double[] scalar = measure(new Network(parameters), input, batch);
                double[] vector = measure(new VectorNetwork(parameters), input, batch);
                double flops = 2.0 * multiplyAdds(config);
                System.out.println(row(name, batch, "scalar", scalar, flops, 1.0));
                System.out.println(row(name, batch, "vector", vector, flops, vector[1] / scalar[1]));
            }
        }
        System.out.printf(Locale.ROOT, "%nchecksum %.6g%n", checksum);
        printLoad("after");
    }

    /** Other processes share the cores; a load average above the core count makes every number lower. */
    private static void printLoad(String when) {
        System.out.printf(Locale.ROOT, "system load average (1 min) %s: %.1f%n", when,
                ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage());
    }

    /** Evaluations per second: {min, median, max} over the rounds. */
    private static double[] measure(ForwardPass network, float[] input, int batch) {
        float[] policy = new float[batch * Encoder.POLICY_SIZE];
        float[] values = new float[batch * network.config().players()];
        run(network, input, batch, policy, values, WARMUP_NANOS);
        double[] rates = new double[ROUNDS];
        for (int r = 0; r < ROUNDS; r++) {
            rates[r] = run(network, input, batch, policy, values, ROUND_NANOS);
        }
        Arrays.sort(rates);
        return new double[] {rates[0], rates[ROUNDS / 2], rates[ROUNDS - 1]};
    }

    /** Runs forward passes for at least {@code nanos}; returns positions per second. */
    private static double run(ForwardPass network, float[] input, int batch, float[] policy, float[] values,
            long nanos) {
        long start = System.nanoTime();
        long calls = 0;
        long elapsed;
        do {
            network.forward(input, batch, policy, values);
            calls++;
            elapsed = System.nanoTime() - start;
        } while (elapsed < nanos);
        checksum += policy[(int) (calls % policy.length)] + values[0];
        return calls * (double) batch / (elapsed / 1e9);
    }

    /** Multiply-adds per position, the formula of docs/network.md, "How the numbers were computed". */
    static long multiplyAdds(NetworkConfig config) {
        long n = HexGrid.CELLS;
        long c = config.channels();
        long i = Encoder.PLANES;
        long h = config.valueHidden();
        return n * c * 7 * i + config.blocks() * 2 * n * c * 7 * c + n * 2 * c + h * c + config.players() * h;
    }

    private static String row(String preset, int batch, String path, double[] rate, double flops,
            double multiple) {
        return String.format(Locale.ROOT, "| %s | %d | %s | %,.0f | %,.0f to %,.0f | %.2f | %.1f |", preset, batch,
                path, rate[1], rate[0], rate[2], rate[1] * flops / 1e9, multiple);
    }
}
