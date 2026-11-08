package sternhalma.nn;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import sternhalma.engine.Position;

/**
 * Training samples per second of the {@link ReferenceTrainer}, one thread, for TINY and SMALL (2 players). Not
 * a JUnit test; run it with
 *
 * <pre>
 * devenv shell -- ./gradlew --no-build-cache :nn:trainerBenchmark
 * </pre>
 *
 * <p>Method: one batch of 16 encoded positions of seeded random games (about 50 legal moves each), weights from
 * {@link Parameters#init}. Each case runs for {@link #WARMUP_NANOS}, then {@link #ROUNDS} rounds of at least
 * {@link #ROUND_NANOS}; the table shows the median round and the slowest and fastest. A sample is one forward
 * and backward pass with the loss. Gradients are zeroed between calls.
 */
public final class TrainerBenchmark {

    private static final long WARMUP_NANOS = 3_000_000_000L;
    private static final long ROUND_NANOS = 2_000_000_000L;
    private static final int ROUNDS = 5;
    private static final int BATCH = 16;

    private static double checksum;

    private TrainerBenchmark() {
    }

    public static void main(String[] args) {
        System.out.printf(Locale.ROOT, "java %s, %s %s, %d available cores, 1 thread used%n",
                System.getProperty("java.version"), System.getProperty("os.name"), System.getProperty("os.arch"),
                Runtime.getRuntime().availableProcessors());
        printLoad("before");
        List<Position> positions = TestBatches.pick(Playouts.positions(2, 4, 80, 2026), BATCH);
        TrainingBatch batch = TestBatches.fromPositions(2, positions, 7);
        System.out.printf(Locale.ROOT, "batch of %d samples, %.1f legal moves per sample%n%n", batch.size(),
                (double) batch.moveCount() / batch.size());
        System.out.println("| Preset | Samples/s (median of 5) | min to max | MFLOP/s (training, matrix work) |");
        System.out.println("|---|---|---|---|");
        for (NetworkConfig config : List.of(NetworkConfig.tiny(2), NetworkConfig.small(2))) {
            ReferenceTrainer trainer = new ReferenceTrainer(Parameters.init(config, 1), 1.0);
            double[] rates = measure(trainer, batch);
            String name = config.channels() == 8 ? "TINY" : "SMALL";
            double mflop = 2.0 * trainingMultiplyAdds(config) / 1e6;
            System.out.printf(Locale.ROOT, "| %s | %.1f | %.1f to %.1f | %.0f |%n", name, rates[1], rates[0], rates[2],
                    rates[1] * mflop);
        }
        System.out.println("\n(checksum " + checksum + ")");
        printLoad("after");
    }

    /** Returns {min, median, max} samples per second. */
    private static double[] measure(ReferenceTrainer trainer, TrainingBatch batch) {
        run(trainer, batch, WARMUP_NANOS);
        double[] rates = new double[ROUNDS];
        for (int i = 0; i < ROUNDS; i++) {
            rates[i] = run(trainer, batch, ROUND_NANOS);
        }
        Arrays.sort(rates);
        return new double[] {rates[0], rates[ROUNDS / 2], rates[ROUNDS - 1]};
    }

    private static double run(ReferenceTrainer trainer, TrainingBatch batch, long nanos) {
        long start = System.nanoTime();
        long samples = 0;
        long now;
        do {
            trainer.zeroGradients();
            trainer.accumulate(batch, batch.size(), 1);
            samples += batch.size();
            now = System.nanoTime();
        } while (now - start < nanos);
        checksum += trainer.gradients().get("conv_in.bias")[0];
        return samples / ((now - start) / 1e9);
    }

    /** Training multiply-adds per sample as in docs/training.md, "How the numbers were computed" (P = 2). */
    private static double trainingMultiplyAdds(NetworkConfig config) {
        double n = HexGrid.CELLS;
        double c = config.channels();
        double forward = n * c * 7 * Encoder.PLANES + config.blocks() * 2 * n * c * 7 * c + n * 2 * c
                + config.valueHidden() * c + config.players() * config.valueHidden();
        return forward + forward + (forward - n * c * 7 * Encoder.PLANES);
    }

    private static void printLoad(String when) {
        System.out.printf(Locale.ROOT, "1-minute load average %s: %.1f%n", when,
                ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage());
    }
}
