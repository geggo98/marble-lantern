package sternhalma.nn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Resume equivalence (docs/training.md, "Checkpoints"): 10 steps in one run give the same parameters, bit for bit,
 * as 5 steps, a checkpoint, a fresh trainer that loads the checkpoint, and 5 more steps. The runs use microbatches
 * and two threads, a schedule with a warm-up of 4 steps (so that the rate changes between steps 3 and 5), and the
 * seeded sampler, for both optimisers and for generations 0 and 1.
 *
 * <p>This catches: optimiser state that is not saved or not restored (m, v, u), a step count that restarts at 0
 * (wrong bias correction and wrong learning rate), a sampler that depends on how many batches were drawn in this
 * process, a parameter array that is rounded or reordered on the way, and a checkpoint that changes the run.
 */
class ResumeEquivalenceTest {

    @TempDir
    Path dir;

    private static void assertBitIdentical(Parameters expected, Parameters actual, String context) {
        for (Map.Entry<String, Tensor> e : expected.tensors().entrySet()) {
            float[] a = e.getValue().data();
            float[] b = actual.get(e.getKey()).data();
            assertTrue(Arrays.equals(a, b), context + ": " + e.getKey() + " differs");
        }
    }

    private static void assertBitIdentical(Optimizer expected, Optimizer actual, String context) {
        assertEquals(expected.steps(), actual.steps());
        Map<String, Map<String, float[]>> a = expected.settings().kind() == Optimizer.Kind.ADAMW
                ? Map.of("m", expected.m(), "v", expected.v()) : Map.of("u", expected.u());
        Map<String, Map<String, float[]>> b = actual.settings().kind() == Optimizer.Kind.ADAMW
                ? Map.of("m", actual.m(), "v", actual.v()) : Map.of("u", actual.u());
        for (String kind : a.keySet()) {
            for (String name : a.get(kind).keySet()) {
                assertTrue(Arrays.equals(a.get(kind).get(name), b.get(kind).get(name)),
                        context + ": optim." + kind + "." + name + " differs");
            }
        }
    }

    private TrainingRun.Summary runTo(Parameters p, Optimizer o, int generation, long planned, Path checkpoints)
            throws IOException {
        return new TrainingRun(RunFixtures.setup(p, o, generation, planned),
                RunFixtures.checkpointing(checkpoints, 5, Long.MAX_VALUE, 3),
                new TrainingRun.Reporting(List.of(), null, Long.MAX_VALUE), TrainingRun.Clock.system(),
                RunFixtures.never()).run();
    }

    @Test
    void tenStepsEqualFiveStepsACheckpointAFreshTrainerAndFiveMoreSteps() throws IOException {
        for (Optimizer.Kind kind : Optimizer.Kind.values()) {
            for (int generation : new int[] {0, 1}) {
                String context = kind + ", generation " + generation;
                Path a = Files.createDirectory(dir.resolve("a-" + kind + generation));
                Path b = Files.createDirectory(dir.resolve("b-" + kind + generation));

                Parameters whole = Parameters.init(RunFixtures.TINY, 3);
                Optimizer wholeOptimizer = new Optimizer(whole, RunFixtures.settings(kind));
                runTo(whole, wholeOptimizer, generation, 10, a);

                Parameters first = Parameters.init(RunFixtures.TINY, 3);
                Optimizer firstOptimizer = new Optimizer(first, RunFixtures.settings(kind));
                runTo(first, firstOptimizer, generation, 5, b);
                assertEquals(5, firstOptimizer.steps());

                // A fresh trainer: nothing of the first run is used except the file.
                Checkpoint.Recovery recovery = Checkpoint.recover(b);
                assertNotNull(recovery.loaded(), context);
                assertEquals(5, recovery.loaded().step());
                assertEquals(generation, recovery.loaded().generation());
                Parameters resumed = recovery.loaded().parameters();
                Optimizer resumedOptimizer = recovery.loaded().optimizer();
                runTo(resumed, resumedOptimizer, generation, 10, b);

                assertBitIdentical(whole, resumed, context);
                assertBitIdentical(wholeOptimizer, resumedOptimizer, context);
                assertEquals(List.of(5L, 10L), Checkpoint.list(b).stream().map(Checkpoint::stepOf).toList());
            }
        }
    }

    /** The control: without the optimiser state the runs differ, so the test above has teeth. */
    @Test
    void aResumeWithoutTheOptimiserStateDiffers() throws IOException {
        Path b = Files.createDirectory(dir.resolve("b"));
        Parameters whole = Parameters.init(RunFixtures.TINY, 3);
        runTo(whole, new Optimizer(whole, RunFixtures.settings(Optimizer.Kind.ADAMW)), 0, 10,
                Files.createDirectory(dir.resolve("a")));
        Parameters first = Parameters.init(RunFixtures.TINY, 3);
        runTo(first, new Optimizer(first, RunFixtures.settings(Optimizer.Kind.ADAMW)), 0, 5, b);
        Checkpoint.Loaded loaded = Checkpoint.recover(b).loaded();
        Optimizer fresh = Optimizer.restore(loaded.parameters(), loaded.optimizer().settings(), 5,
                new Optimizer(loaded.parameters(), loaded.optimizer().settings()).m(),
                new Optimizer(loaded.parameters(), loaded.optimizer().settings()).v(), null);
        runTo(loaded.parameters(), fresh, 0, 10, b);
        boolean differs = false;
        for (Map.Entry<String, Tensor> e : whole.tensors().entrySet()) {
            differs |= !Arrays.equals(e.getValue().data(), loaded.parameters().get(e.getKey()).data());
        }
        assertTrue(differs);
    }
}
