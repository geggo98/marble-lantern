package sternhalma.nn;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import com.sun.management.HotSpotDiagnosticMXBean;
import sternhalma.engine.Position;

/**
 * GFLOP/s of the Vector API forward pass ({@link VectorNetwork}) for SMALL and LARGE at batch 1 and 256, one thread;
 * the scalar path is left out (see {@link NetworkBenchmark} for the comparison). Not a JUnit test; run it with
 *
 * <pre>
 * ./gradlew --no-build-cache :nn:vectorBenchmark
 * ./gradlew --no-build-cache :nn:vectorBenchmark -PvectorBits=256 -PvectorSeconds=2
 * </pre>
 *
 * <p>Arguments: {@code --seconds N} per round (default 1; one warm-up of 3 rounds, then 5 rounds, median). The
 * species comes from {@link VectorSupport#BITS_PROPERTY}; the JVM flags ({@code -XX:UseAVX=N},
 * {@code -XX:MaxVectorSize=N}) decide what the preferred species is on x86. The header prints the species, the
 * flags the JVM runs with, and the machine. GFLOP/s counts the matrix work of docs/network.md, 2 FLOPs per
 * multiply-add, as {@link NetworkBenchmark} does.
 */
public final class VectorBenchmark {

    private static final int ROUNDS = 5;
    private static final int[] BATCHES = {1, 256};

    private static double checksum;

    private VectorBenchmark() {
    }

    public static void main(String[] args) {
        double seconds = 1.0;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--seconds") && i + 1 < args.length) {
                seconds = Double.parseDouble(args[++i]);
            } else {
                throw new IllegalArgumentException("usage: VectorBenchmark [--seconds N]");
            }
        }
        long roundNanos = (long) (seconds * 1e9);
        System.out.printf(Locale.ROOT, "java %s, %s %s, %d available cores, 1 thread used%n",
                System.getProperty("java.version"), System.getProperty("os.name"), System.getProperty("os.arch"),
                Runtime.getRuntime().availableProcessors());
        System.out.println("vector species: " + VectorSupport.describe());
        System.out.println("JVM flags: UseAVX=" + flag("UseAVX") + " MaxVectorSize=" + flag("MaxVectorSize")
                + " UseFMA=" + flag("UseFMA"));
        System.out.printf(Locale.ROOT, "system load average (1 min): %.1f%n",
                ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage());

        List<Position> positions = Playouts.positions(2, 4, 80, 2026);
        float[] input = new float[256 * Encoder.INPUT_SIZE];
        for (int b = 0; b < 256; b++) {
            Encoder.encode(positions.get(b * positions.size() / 256), input, b * Encoder.INPUT_SIZE);
        }
        System.out.println();
        System.out.println("| Preset | Batch | Evaluations/s (median) | min to max | GFLOP/s |");
        System.out.println("|---|---|---|---|---|");
        for (NetworkConfig config : List.of(NetworkConfig.small(2), NetworkConfig.large(2))) {
            String name = config.channels() == 32 ? "SMALL" : "LARGE";
            VectorNetwork network = new VectorNetwork(Parameters.init(config, 1));
            double flops = 2.0 * NetworkBenchmark.multiplyAdds(config);
            for (int batch : BATCHES) {
                double[] rate = measure(network, input, batch, roundNanos);
                System.out.println(String.format(Locale.ROOT, "| %s | %d | %,.0f | %,.0f to %,.0f | %.2f |", name,
                        batch, rate[1], rate[0], rate[2], rate[1] * flops / 1e9));
            }
        }
        System.out.printf(Locale.ROOT, "%nchecksum %.6g%n", checksum);
    }

    /** The value of a HotSpot flag, or {@code ?} where the JVM does not know it (flags of other architectures). */
    private static String flag(String name) {
        try {
            return ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class).getVMOption(name).getValue();
        } catch (RuntimeException e) {
            return "?";
        }
    }

    /** Evaluations per second: {min, median, max} over the rounds, after a warm-up of three rounds. */
    private static double[] measure(ForwardPass network, float[] input, int batch, long roundNanos) {
        float[] policy = new float[batch * Encoder.POLICY_SIZE];
        float[] values = new float[batch * network.config().players()];
        for (int w = 0; w < 3; w++) {
            run(network, input, batch, policy, values, roundNanos);
        }
        double[] rates = new double[ROUNDS];
        for (int r = 0; r < ROUNDS; r++) {
            rates[r] = run(network, input, batch, policy, values, roundNanos);
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
}
