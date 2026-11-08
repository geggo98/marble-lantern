package sternhalma.nn;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import sternhalma.engine.Position;

/** Shared set-up of the checkpoint, sampler and training-run tests. */
final class RunFixtures {

    static final NetworkConfig TINY = NetworkConfig.tiny(2);
    static final String SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    static final long SEED = 20261108L;

    private static TrainingBatch pool;

    private RunFixtures() {
    }

    /** 16 samples from seeded playouts; built once. */
    static synchronized TrainingBatch pool() {
        if (pool == null) {
            List<Position> positions = TestBatches.pick(Playouts.positions(2, 6, 60, 31), 16);
            pool = TestBatches.fromPositions(2, positions, 5);
        }
        return pool;
    }

    static Optimizer.Settings settings(Optimizer.Kind kind) {
        return kind == Optimizer.Kind.ADAMW ? Optimizer.Settings.adamw() : Optimizer.Settings.sgd();
    }

    /** Warm-up of 4 steps, so that a run of 10 steps is not in the flat start of the usual 200. */
    static LearningRateSchedule schedule() {
        return new LearningRateSchedule(1e-2, 4, 100, 0.1, 4, List.of());
    }

    static TrainingRun.Setup setup(Parameters parameters, Optimizer optimizer, int generation, long plannedSteps) {
        return new TrainingRun.Setup(parameters, optimizer, schedule(), pool(), 4, 2, 2, 1.0, SEED, generation,
                plannedSteps);
    }

    static TrainingRun.Checkpointing checkpointing(java.nio.file.Path dir, long everySteps, long everyNanos, int keep) {
        return new TrainingRun.Checkpointing(dir, everySteps, everyNanos, keep, SHA);
    }

    /** A clock that only moves when a test advances it; the CPU clock likewise. */
    static final class FakeClock {
        final AtomicLong wall = new AtomicLong();
        final AtomicLong cpu = new AtomicLong();

        TrainingRun.Clock clock() {
            return new TrainingRun.Clock(wall::get, cpu::get);
        }

        TrainingObserver ticking(long stepWall, long stepCpu, long checkpointWall, long checkpointCpu) {
            return new TrainingObserver() {
                @Override
                public void onStep(long step, double loss, double klPolicy, double valueLoss, long wallNanos) {
                    wall.addAndGet(stepWall);
                    cpu.addAndGet(stepCpu);
                }

                @Override
                public void onCheckpoint(java.nio.file.Path path, long wallNanos, long bytes) {
                    wall.addAndGet(checkpointWall);
                    cpu.addAndGet(checkpointCpu);
                }
            };
        }
    }

    /** A stream that keeps what is printed, for the progress lines. */
    static final class Captured {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final PrintStream stream = new PrintStream(bytes, true, StandardCharsets.UTF_8);

        List<String> lines() {
            String text = bytes.toString(StandardCharsets.UTF_8);
            return text.isEmpty() ? List.of() : List.of(text.split("\n", -1)).subList(0, text.split("\n", -1).length - 1);
        }
    }

    static BooleanSupplier never() {
        return () -> false;
    }
}
