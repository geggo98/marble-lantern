package sternhalma.nn;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The seam between {@link TrainingRun} and a trainer on another device ({@link StepTrainer}, docs/training.md, "As
 * implemented (block B6-B10)"): the run calls {@code sync} before every checkpoint and at the end, the CPU trainer
 * through the seam equals the run's default, and an exception of a step ends the run with no checkpoint of that step.
 */
class StepTrainerTest {

    private static TrainingRun.Reporting quiet() {
        return new TrainingRun.Reporting(List.of(), null, 1_000_000_000L);
    }

    private static Parameters start() {
        return Parameters.uniform(RunFixtures.TINY, 5, 0.3f);
    }

    /** A CPU trainer that records what the run asks of it. */
    private static final class Recording implements StepTrainer {
        final List<String> events = new ArrayList<>();
        final StepTrainer inner;
        final Optimizer optimizer;
        final long failAtStep;

        Recording(Parameters p, Optimizer o, long failAtStep) {
            this.inner = StepTrainer.cpu(p, o, 1.0, 2, 1);
            this.optimizer = o;
            this.failAtStep = failAtStep;
        }

        @Override
        public ReferenceTrainer.Result step(TrainingBatch batch, double lr) {
            if (optimizer.steps() + 1 == failAtStep) {
                throw new IllegalStateException("not finite in step " + failAtStep);
            }
            events.add("step");
            return inner.step(batch, lr);
        }

        @Override
        public void sync() {
            events.add("sync");
            inner.sync();
        }

        @Override
        public void close() {
            events.add("close");
        }
    }

    @Test
    void theRunSyncsBeforeEveryCheckpointAndTheDefaultIsTheCpuTrainer(@TempDir Path dir) throws IOException {
        Parameters viaSeam = start();
        Optimizer seamOptimizer = new Optimizer(viaSeam, Optimizer.Settings.adamw());
        Recording recording = new Recording(viaSeam, seamOptimizer, 0);
        TrainingRun.Setup setup = RunFixtures.setup(viaSeam, seamOptimizer, 1, 7);
        new TrainingRun(setup, RunFixtures.checkpointing(dir.resolve("seam"), 3, Long.MAX_VALUE, 5), quiet(),
                new RunFixtures.FakeClock().clock(), () -> false, recording).run();
        // checkpoints at steps 3 and 6, and the final one at 7: each preceded by a sync, no sync without a checkpoint
        assertEquals(List.of("step", "step", "step", "sync", "step", "step", "step", "sync", "step", "sync"),
                recording.events);

        Parameters byDefault = start();
        Optimizer defaultOptimizer = new Optimizer(byDefault, Optimizer.Settings.adamw());
        new TrainingRun(RunFixtures.setup(byDefault, defaultOptimizer, 1, 7),
                RunFixtures.checkpointing(dir.resolve("default"), 3, Long.MAX_VALUE, 5), quiet(),
                new RunFixtures.FakeClock().clock(), () -> false).run();
        for (Map.Entry<String, Tensor> e : byDefault.tensors().entrySet()) {
            assertArrayEquals(e.getValue().data(), viaSeam.get(e.getKey()).data(), e.getKey());
        }
        assertEquals(7, seamOptimizer.steps());
    }

    @Test
    void anExceptionOfAStepEndsTheRunWithoutACheckpointOfThatStep(@TempDir Path dir) throws IOException {
        Parameters p = start();
        Optimizer o = new Optimizer(p, Optimizer.Settings.adamw());
        Recording recording = new Recording(p, o, 5);
        TrainingRun run = new TrainingRun(RunFixtures.setup(p, o, 1, 9), RunFixtures.checkpointing(dir, 2, Long.MAX_VALUE, 5),
                quiet(), new RunFixtures.FakeClock().clock(), () -> false, recording);
        IllegalStateException e = assertThrows(IllegalStateException.class, run::run);
        assertTrue(e.getMessage().contains("not finite"), e.getMessage());
        List<Long> steps = Checkpoint.list(dir).stream().map(Checkpoint::stepOf).toList();
        assertEquals(List.of(2L, 4L), steps, "the checkpoints before the bad step stay, none after it");
        assertFalse(recording.events.contains("close"), "the caller closes the trainer, not the run");
    }
}
