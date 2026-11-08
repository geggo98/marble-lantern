package sternhalma.nn;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import sternhalma.engine.Position;

/**
 * Cost of checkpoints (AdamW state, so three times the parameters) for TINY and SMALL, and the share of a 500-step
 * SMALL run that goes into checkpoints and observers. Not a JUnit test; run it with
 *
 * <pre>
 * ./gradlew --no-build-cache :nn:checkpointBenchmark
 * </pre>
 *
 * <p>Method: the write time is the median of 21 writes of one checkpoint to a fresh temporary directory (the
 * first write is discarded as warm-up); the run is {@link TrainingRun} with batch 16, one thread, AdamW, a
 * checkpoint every 100 steps (keep 2) and an observer that does nothing, with the system clock.
 */
public final class CheckpointBenchmark {

    private static final int STEPS = 500;

    private CheckpointBenchmark() {
    }

    public static void main(String[] args) throws IOException {
        Path dir = Files.createTempDirectory("checkpoint-benchmark");
        System.out.printf(Locale.ROOT, "java %s, %s %s%n", System.getProperty("java.version"),
                System.getProperty("os.name"), System.getProperty("os.arch"));
        for (NetworkConfig config : List.of(NetworkConfig.tiny(2), NetworkConfig.small(2))) {
            Parameters p = Parameters.init(config, 1);
            Optimizer o = new Optimizer(p, Optimizer.Settings.adamw());
            Path file = dir.resolve("probe.safetensors");
            double[] ms = new double[21];
            long bytes = 0;
            for (int i = 0; i < ms.length; i++) {
                long t0 = System.nanoTime();
                bytes = Checkpoint.write(file, p, o, 0, "0".repeat(64));
                ms[i] = (System.nanoTime() - t0) / 1e6;
            }
            double[] rest = Arrays.copyOfRange(ms, 1, ms.length);
            Arrays.sort(rest);
            System.out.printf(Locale.ROOT, "%s: %d parameters, checkpoint %d bytes, write median %.2f ms (%.2f to %.2f)%n",
                    config.channels() == 8 ? "TINY" : "SMALL", config.parameterCount(), bytes, rest[rest.length / 2],
                    rest[0], rest[rest.length - 1]);
            Checkpoint.read(file);
            long t0 = System.nanoTime();
            Checkpoint.read(file);
            System.out.printf(Locale.ROOT, "      read %.2f ms%n", (System.nanoTime() - t0) / 1e6);
        }

        List<Position> positions = TestBatches.pick(Playouts.positions(2, 6, 60, 31), 16);
        TrainingBatch pool = TestBatches.fromPositions(2, positions, 5);
        NetworkConfig small = NetworkConfig.small(2);
        Parameters p = Parameters.init(small, 1);
        Optimizer o = new Optimizer(p, Optimizer.Settings.adamw());
        LearningRateSchedule schedule = LearningRateSchedule.of(1e-3, 10_000, List.of());
        TrainingRun.Setup setup = new TrainingRun.Setup(p, o, schedule, pool, 16, 16, 1, 1.0, 1L, 0, STEPS);
        TrainingRun run = new TrainingRun(setup,
                new TrainingRun.Checkpointing(dir.resolve("run"), 100, Long.MAX_VALUE, 2, "0".repeat(64)),
                new TrainingRun.Reporting(List.of(new TrainingObserver() { }), System.err, 10_000_000_000L),
                TrainingRun.Clock.system(), () -> false);
        TrainingRun.Summary s = run.run();
        TrainingRun.Timing t = s.timing();
        System.out.printf(Locale.ROOT, "SMALL run of %d steps: loop %.1f s, %d checkpoints, %d bytes written%n", STEPS,
                t.loopWallNanos() / 1e9, t.checkpoints(), t.checkpointBytes());
        for (TrainingRun.Category c : TrainingRun.Category.values()) {
            TrainingRun.Spent spent = t.spent(c);
            System.out.printf(Locale.ROOT, "  %-10s wall %9.3f ms (%.4f %%)  cpu %9.3f ms (%.4f %%)%n", c,
                    spent.wallNanos() / 1e6, 100 * t.share(c), spent.cpuNanos() / 1e6, 100 * t.cpuShare(c));
        }
        System.out.printf(Locale.ROOT, "  unaccounted wall %.3f ms%n", t.unaccountedWallNanos() / 1e6);
    }
}
