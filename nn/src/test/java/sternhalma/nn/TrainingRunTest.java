package sternhalma.nn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests of {@link TrainingRun}: checkpoint cadence, progress line and ETA, stop, clean-up, and the accounting of
 * the time that checkpoints and observers cost. The cadence and ETA tests inject a clock that only moves when a
 * test observer says so ({@link RunFixtures.FakeClock#ticking}): then the numbers are exact. The accounting test
 * with the real clock checks that the categories are consistent.
 */
class TrainingRunTest {

    private static final long SECOND = 1_000_000_000L;

    @TempDir
    Path dir;

    /** Runs {@code planned} steps from step 0 and reports the steps at which checkpoints were announced. */
    private TrainingRun.Summary run(long start, long planned, long everySteps, long everyNanos, int keep,
            RunFixtures.FakeClock fake, TrainingObserver ticker, RunFixtures.Captured out, long progressNanos,
            List<Long> checkpointSteps) throws IOException {
        Parameters p = Parameters.init(RunFixtures.TINY, 1);
        Optimizer o = new Optimizer(p, Optimizer.Settings.adamw());
        if (start > 0) {
            o = Optimizer.restore(p, Optimizer.Settings.adamw(), start, o.m(), o.v(), null);
        }
        TrainingObserver recorder = new TrainingObserver() {
            @Override
            public void onCheckpoint(Path path, long wallNanos, long bytes) {
                checkpointSteps.add(Checkpoint.stepOf(path));
            }
        };
        TrainingRun.Reporting reporting = new TrainingRun.Reporting(List.of(ticker, recorder), out.stream,
                progressNanos);
        return new TrainingRun(RunFixtures.setup(p, o, 0, planned),
                RunFixtures.checkpointing(dir, everySteps, everyNanos, keep), reporting, fake.clock(),
                RunFixtures.never()).run();
    }

    // ------------------------------------------------------------------ cadence

    @Test
    void checkpointsEveryNStepsAndAtTheEnd() throws IOException {
        RunFixtures.FakeClock fake = new RunFixtures.FakeClock();
        List<Long> steps = new ArrayList<>();
        TrainingRun.Summary s = run(0, 10, 3, Long.MAX_VALUE, 100, fake, fake.ticking(SECOND, 0, 0, 0),
                new RunFixtures.Captured(), 1000 * SECOND, steps);
        assertEquals(List.of(3L, 6L, 9L, 10L), steps);
        assertEquals(4, s.checkpointsWritten());
        assertEquals(10, s.endStep());
        assertFalse(s.aborted());
        assertEquals(List.of(3L, 6L, 9L, 10L), Checkpoint.list(dir).stream().map(Checkpoint::stepOf).toList());
    }

    @Test
    void checkpointsEveryTSecondsAndAtTheEnd() throws IOException {
        RunFixtures.FakeClock fake = new RunFixtures.FakeClock();
        List<Long> steps = new ArrayList<>();
        // One second per step; a checkpoint when 4 s have passed since the last one (or since the start).
        run(0, 10, 1000, 4 * SECOND, 100, fake, fake.ticking(SECOND, 0, 0, 0), new RunFixtures.Captured(),
                1000 * SECOND, steps);
        assertEquals(List.of(4L, 8L, 10L), steps);
    }

    @Test
    void whicheverComesFirstAndTheTimeRestartsAtEachCheckpoint() throws IOException {
        RunFixtures.FakeClock fake = new RunFixtures.FakeClock();
        List<Long> steps = new ArrayList<>();
        // Every 5 steps or every 3 s: time at 3; then 4, 5 (steps; only 2 s after step 3's checkpoint); time at 8; 10 (steps).
        run(0, 10, 5, 3 * SECOND, 100, fake, fake.ticking(SECOND, 0, 0, 0), new RunFixtures.Captured(),
                1000 * SECOND, steps);
        assertEquals(List.of(3L, 5L, 8L, 10L), steps);
    }

    /** Step cadence counts absolute steps, so a resumed run keeps writing step-00000010, step-00000015, ... */
    @Test
    void stepCadenceIsAbsoluteAfterAResume() throws IOException {
        RunFixtures.FakeClock fake = new RunFixtures.FakeClock();
        List<Long> steps = new ArrayList<>();
        run(7, 16, 5, Long.MAX_VALUE, 100, fake, fake.ticking(SECOND, 0, 0, 0), new RunFixtures.Captured(),
                1000 * SECOND, steps);
        assertEquals(List.of(10L, 15L, 16L), steps);
    }

    @Test
    void keepsTheLastNCheckpoints() throws IOException {
        RunFixtures.FakeClock fake = new RunFixtures.FakeClock();
        run(0, 6, 1, Long.MAX_VALUE, 2, fake, fake.ticking(SECOND, 0, 0, 0), new RunFixtures.Captured(),
                1000 * SECOND, new ArrayList<>());
        assertEquals(List.of(5L, 6L), Checkpoint.list(dir).stream().map(Checkpoint::stepOf).toList());
    }

    @Test
    void aFinishedRunWritesNoFurtherCheckpoint() throws IOException {
        RunFixtures.FakeClock fake = new RunFixtures.FakeClock();
        List<Long> steps = new ArrayList<>();
        TrainingRun.Summary s = run(10, 10, 5, Long.MAX_VALUE, 100, fake, fake.ticking(SECOND, 0, 0, 0),
                new RunFixtures.Captured(), 1000 * SECOND, steps);
        assertEquals(List.of(), steps);
        assertEquals(0, s.stepsDone());
        assertEquals(0, s.checkpointsWritten());
    }

    // ------------------------------------------------------------------ progress and ETA

    /**
     * The rate counts the steps of this process only (docs/training.md, "Progress"): a run that starts at step 100
     * and has done 10 steps in 10 s runs at 1.0 steps/s with 990 steps to go, ETA 16m30s. Counting the resumed
     * steps would give 11 steps/s and an ETA of 1m30s.
     */
    @Test
    void etaAfterAResumeUsesOnlyTheStepsOfThisProcess() throws IOException {
        RunFixtures.FakeClock fake = new RunFixtures.FakeClock();
        RunFixtures.Captured out = new RunFixtures.Captured();
        run(100, 120, 1000, Long.MAX_VALUE, 100, fake, fake.ticking(SECOND, 0, 0, 0), out, 10 * SECOND,
                new ArrayList<>());
        List<String> lines = out.lines();
        assertTrue(lines.get(0).matches(
                "gen 0 train: 110/120 steps {2}1\\.0 steps/s {2}ETA 10s {2}elapsed 10s {2}loss \\d+\\.\\d+ \\(kl \\d+\\.\\d+, value \\d+\\.\\d+\\)"),
                lines.get(0));
        // A longer plan, to see the minutes: 990 steps to go at 1 step/s.
        RunFixtures.FakeClock fake2 = new RunFixtures.FakeClock();
        RunFixtures.Captured out2 = new RunFixtures.Captured();
        Path other = dir.resolve("other");
        Files.createDirectory(other);
        Parameters p = Parameters.init(RunFixtures.TINY, 1);
        Optimizer o0 = new Optimizer(p, Optimizer.Settings.adamw());
        Optimizer o = Optimizer.restore(p, Optimizer.Settings.adamw(), 100, o0.m(), o0.v(), null);
        StopAfter stop = new StopAfter(10);
        TrainingRun.Reporting reporting = new TrainingRun.Reporting(List.of(fake2.ticking(SECOND, 0, 0, 0), stop),
                out2.stream, 10 * SECOND);
        new TrainingRun(RunFixtures.setup(p, o, 0, 1100), RunFixtures.checkpointing(other, 1000, Long.MAX_VALUE, 2),
                reporting, fake2.clock(), stop).run();
        String first = out2.lines().get(0);
        assertTrue(first.startsWith("gen 0 train: 110/1100 steps  1.0 steps/s  ETA 16m30s  elapsed 10s  loss "), first);
    }

    @Test
    void progressLinesAreOnePerIntervalWithoutCarriageReturns() throws IOException {
        RunFixtures.FakeClock fake = new RunFixtures.FakeClock();
        RunFixtures.Captured out = new RunFixtures.Captured();
        run(0, 10, 1000, Long.MAX_VALUE, 100, fake, fake.ticking(SECOND, 0, 0, 0), out, 4 * SECOND,
                new ArrayList<>());
        String all = out.bytes.toString();
        assertFalse(all.contains("\r"));
        List<String> lines = out.lines();
        // Progress after steps 4 and 8, the final progress line at step 10, then the summary line.
        assertEquals(4, lines.size(), lines.toString());
        assertTrue(lines.get(0).contains(" 4/10 steps"), lines.get(0));
        assertTrue(lines.get(1).contains(" 8/10 steps"), lines.get(1));
        assertTrue(lines.get(2).contains(" 10/10 steps") && lines.get(2).contains("ETA 0s"), lines.get(2));
        assertTrue(lines.get(3).contains("10 ok, 0 skip, 0 error, 0 cleanup") && lines.get(3).contains("nothing more to do"),
                lines.get(3));
    }

    @Test
    void theSummaryCountsResumedStepsAsSkip() throws IOException {
        RunFixtures.FakeClock fake = new RunFixtures.FakeClock();
        RunFixtures.Captured out = new RunFixtures.Captured();
        run(7, 10, 1000, Long.MAX_VALUE, 100, fake, fake.ticking(SECOND, 0, 0, 0), out, 100 * SECOND,
                new ArrayList<>());
        String summary = out.lines().get(out.lines().size() - 1);
        assertTrue(summary.contains("3 ok, 7 skip, 0 error, 0 cleanup"), summary);
    }

    // ------------------------------------------------------------------ stop and clean-up

    private static final class StopAfter implements TrainingObserver, java.util.function.BooleanSupplier {
        private final int steps;
        private final AtomicInteger done = new AtomicInteger();

        StopAfter(int steps) {
            this.steps = steps;
        }

        @Override
        public void onStep(long step, double loss, double klPolicy, double valueLoss, long wallNanos) {
            done.incrementAndGet();
        }

        @Override
        public boolean getAsBoolean() {
            return done.get() >= steps;
        }
    }

    @Test
    void stopBetweenStepsWritesACheckpointAndReportsTheAbort() throws IOException {
        Parameters p = Parameters.init(RunFixtures.TINY, 1);
        Optimizer o = new Optimizer(p, Optimizer.Settings.sgd());
        StopAfter stop = new StopAfter(3);
        RunFixtures.Captured out = new RunFixtures.Captured();
        TrainingRun.Summary s = new TrainingRun(RunFixtures.setup(p, o, 0, 10),
                RunFixtures.checkpointing(dir, 1000, Long.MAX_VALUE, 2),
                new TrainingRun.Reporting(List.of(stop), out.stream, 1000 * SECOND),
                new RunFixtures.FakeClock().clock(), stop).run();
        assertTrue(s.aborted());
        assertEquals(3, s.endStep());
        assertEquals(3, o.steps());
        assertEquals(List.of(3L), Checkpoint.list(dir).stream().map(Checkpoint::stepOf).toList());
        assertTrue(out.lines().get(out.lines().size() - 1).contains("aborted"), out.lines().toString());
    }

    @Test
    void leftoverTemporaryFilesAreRemovedAndCountedAsCleanup() throws IOException {
        Files.write(dir.resolve("step-00000004.safetensors.abcd.tmp"), new byte[] {1, 2});
        RunFixtures.FakeClock fake = new RunFixtures.FakeClock();
        RunFixtures.Captured out = new RunFixtures.Captured();
        TrainingRun.Summary s = run(0, 2, 1000, Long.MAX_VALUE, 2, fake, fake.ticking(SECOND, 0, 0, 0), out,
                1000 * SECOND, new ArrayList<>());
        assertEquals(1, s.cleanup());
        assertFalse(Files.exists(dir.resolve("step-00000004.safetensors.abcd.tmp")));
        assertTrue(out.lines().get(out.lines().size() - 1).contains("1 cleanup"), out.lines().toString());
    }

    @Test
    void anObserverExceptionStopsTheRun() {
        Parameters p = Parameters.init(RunFixtures.TINY, 1);
        Optimizer o = new Optimizer(p, Optimizer.Settings.sgd());
        TrainingObserver bad = new TrainingObserver() {
            @Override
            public void onStep(long step, double loss, double klPolicy, double valueLoss, long wallNanos) {
                throw new IllegalStateException("observer failed at step " + step);
            }
        };
        TrainingRun run = new TrainingRun(RunFixtures.setup(p, o, 0, 5),
                RunFixtures.checkpointing(dir, 1000, Long.MAX_VALUE, 2),
                new TrainingRun.Reporting(List.of(bad), null, 1000 * SECOND), TrainingRun.Clock.system(),
                RunFixtures.never());
        IllegalStateException e = assertThrows(IllegalStateException.class, run::run);
        assertEquals("observer failed at step 1", e.getMessage());
    }

    // ------------------------------------------------------------------ observer and checkpoint accounting

    /**
     * Observer time is measured as wall and as thread CPU, separately from the steps and the checkpoints. The
     * observers here advance the fake clocks by known amounts: 10 onStep calls of 1 s wall and 0.25 s CPU, and
     * 2 onCheckpoint calls of 3 s and 1 s: 16 s wall, 4.5 s CPU.
     */
    @Test
    void observerTimeIsCountedPerCategoryInWallAndCpu() throws IOException {
        RunFixtures.FakeClock fake = new RunFixtures.FakeClock();
        TrainingRun.Summary s = run(0, 10, 5, Long.MAX_VALUE, 100, fake,
                fake.ticking(SECOND, SECOND / 4, 3 * SECOND, SECOND), new RunFixtures.Captured(), 1000 * SECOND,
                new ArrayList<>());
        TrainingRun.Timing t = s.timing();
        assertEquals(10, t.steps());
        assertEquals(2, t.checkpoints());
        assertEquals(16 * SECOND, t.observer().wallNanos());
        assertEquals(4 * SECOND + SECOND / 2, t.observer().cpuNanos());
        assertEquals(16 * SECOND, t.loopWallNanos());
        assertEquals(0, t.step().wallNanos(), "no fake time passes inside the steps");
        assertEquals(0, t.checkpoint().wallNanos(), "no fake time passes inside the checkpoints");
        assertEquals(1.0, t.share(TrainingRun.Category.OBSERVER), 1e-12);
        assertEquals(0.0, t.share(TrainingRun.Category.CHECKPOINT), 1e-12);
        assertEquals(0.0, t.share(TrainingRun.Category.STEP), 1e-12);
    }

    @Test
    void sharesAreFractionsOfTheLoopWallTime() {
        TrainingRun.Timing t = new TrainingRun.Timing(10, 2, 1000, 100_000, 90_000,
                new TrainingRun.Spent(60_000, 55_000), new TrainingRun.Spent(25_000, 5_000),
                new TrainingRun.Spent(10_000, 1_000), new TrainingRun.Spent(1_000, 100));
        assertEquals(0.60, t.share(TrainingRun.Category.STEP), 1e-12);
        assertEquals(0.25, t.share(TrainingRun.Category.CHECKPOINT), 1e-12);
        assertEquals(0.10, t.share(TrainingRun.Category.OBSERVER), 1e-12);
        assertEquals(0.01, t.share(TrainingRun.Category.PROGRESS), 1e-12);
        assertEquals(4_000, t.unaccountedWallNanos());
        assertEquals(0.0, new TrainingRun.Timing(0, 0, 0, 0, 0, new TrainingRun.Spent(0, 0),
                new TrainingRun.Spent(0, 0), new TrainingRun.Spent(0, 0), new TrainingRun.Spent(0, 0))
                .share(TrainingRun.Category.STEP), "no division by zero before the first nanosecond");
    }

    /**
     * With the real clock: the checkpoint and step times that the observers receive add up to the totals in the
     * timing; the observer sleeps, so its wall time is large and its CPU time is not; the categories do not add up
     * to more than the loop.
     */
    @Test
    void realClockAccountingIsConsistent() throws IOException {
        List<Long> stepNanos = new ArrayList<>();
        List<Long> checkpointNanos = new ArrayList<>();
        List<Long> checkpointBytes = new ArrayList<>();
        TrainingObserver sleeper = new TrainingObserver() {
            @Override
            public void onStep(long step, double loss, double klPolicy, double valueLoss, long wallNanos) {
                stepNanos.add(wallNanos);
                sleep(20);
            }

            @Override
            public void onCheckpoint(Path path, long wallNanos, long bytes) {
                checkpointNanos.add(wallNanos);
                checkpointBytes.add(bytes);
                try {
                    assertEquals(Files.size(path), bytes);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
                sleep(20);
            }

            private void sleep(long millis) {
                try {
                    Thread.sleep(millis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
        };
        Parameters p = Parameters.init(RunFixtures.TINY, 1);
        Optimizer o = new Optimizer(p, Optimizer.Settings.adamw());
        TrainingRun.Summary s = new TrainingRun(RunFixtures.setup(p, o, 0, 8),
                RunFixtures.checkpointing(dir, 4, Long.MAX_VALUE, 5),
                new TrainingRun.Reporting(List.of(sleeper), null, 1000 * SECOND), TrainingRun.Clock.system(),
                RunFixtures.never()).run();
        TrainingRun.Timing t = s.timing();
        assertEquals(8, t.steps());
        assertEquals(2, t.checkpoints());
        assertEquals(stepNanos.stream().mapToLong(Long::longValue).sum(), t.step().wallNanos());
        assertEquals(checkpointNanos.stream().mapToLong(Long::longValue).sum(), t.checkpoint().wallNanos());
        assertEquals(checkpointBytes.stream().mapToLong(Long::longValue).sum(), t.checkpointBytes());
        assertTrue(t.step().wallNanos() > 0 && t.checkpoint().wallNanos() > 0);
        assertTrue(t.step().cpuNanos() > 0 && t.checkpoint().cpuNanos() > 0, "thread CPU time is measured");
        assertTrue(t.observer().wallNanos() >= 10 * 20_000_000L, "10 sleeps of 20 ms: " + t.observer());
        assertTrue(t.observer().cpuNanos() < t.observer().wallNanos() / 4, "sleeping costs wall time, not CPU: "
                + t.observer());
        long sum = t.step().wallNanos() + t.checkpoint().wallNanos() + t.observer().wallNanos()
                + t.progress().wallNanos();
        assertTrue(sum <= t.loopWallNanos(), sum + " > " + t.loopWallNanos());
        assertTrue(t.unaccountedWallNanos() >= 0);
        double shares = 0;
        for (TrainingRun.Category c : TrainingRun.Category.values()) {
            double share = t.share(c);
            assertTrue(share >= 0 && share <= 1, c + " " + share);
            shares += share;
        }
        assertTrue(shares <= 1.0 + 1e-12);
    }

    // ------------------------------------------------------------------ reviewer gaps

    /**
     * A checkpoint that takes longer than T: the timer restarts when the write is over, so the next time-based
     * checkpoint comes T of step time later. Stamping the time before the write would give a checkpoint at every
     * step after a slow one. The fake clock advances 10 s inside the checkpoint (the observer of the checkpoint
     * ticks before the stamp), T = 4 s, one second per step.
     */
    @Test
    void theTimeCadenceRestartsAfterTheWriteEvenIfTheWriteTookLongerThanT() throws IOException {
        RunFixtures.FakeClock fake = new RunFixtures.FakeClock();
        List<Long> steps = new ArrayList<>();
        run(0, 10, 1000, 4 * SECOND, 100, fake, fake.ticking(SECOND, 0, 10 * SECOND, 0), new RunFixtures.Captured(),
                1000 * SECOND, steps);
        assertEquals(List.of(4L, 8L, 10L), steps);
    }

    /** Loss, KL and value in a progress line are means over the steps since the previous line only. */
    @Test
    void progressMeansCoverOnlyTheStepsSinceThePreviousLine() throws IOException {
        RunFixtures.FakeClock fake = new RunFixtures.FakeClock();
        RunFixtures.Captured out = new RunFixtures.Captured();
        List<double[]> seen = new ArrayList<>(); // loss, value per step
        TrainingObserver recorder = new TrainingObserver() {
            @Override
            public void onStep(long step, double loss, double klPolicy, double valueLoss, long wallNanos) {
                seen.add(new double[] {loss, valueLoss});
            }
        };
        Parameters p = Parameters.init(RunFixtures.TINY, 1);
        Optimizer o = new Optimizer(p, Optimizer.Settings.adamw());
        new TrainingRun(RunFixtures.setup(p, o, 0, 10), RunFixtures.checkpointing(dir, 1000, Long.MAX_VALUE, 2),
                new TrainingRun.Reporting(List.of(recorder, fake.ticking(SECOND, 0, 0, 0)), out.stream, 4 * SECOND),
                fake.clock(), RunFixtures.never()).run();
        List<String> lines = out.lines();
        int[][] windows = {{0, 4}, {4, 8}, {8, 10}};
        boolean differsFromCumulative = false;
        for (int i = 0; i < 3; i++) {
            double loss = 0;
            double value = 0;
            double cumulative = 0;
            for (int s = windows[i][0]; s < windows[i][1]; s++) {
                loss += seen.get(s)[0];
                value += seen.get(s)[1];
            }
            for (int s = 0; s < windows[i][1]; s++) {
                cumulative += seen.get(s)[0];
            }
            int n = windows[i][1] - windows[i][0];
            String expected = String.format(java.util.Locale.ROOT, "loss %.2f (kl ", loss / n);
            assertTrue(lines.get(i).contains(expected), lines.get(i) + " should contain " + expected);
            assertTrue(lines.get(i).endsWith(String.format(java.util.Locale.ROOT, "value %.2f)", value / n)),
                    lines.get(i));
            differsFromCumulative |= !String.format(java.util.Locale.ROOT, "%.2f", cumulative / windows[i][1])
                    .equals(String.format(java.util.Locale.ROOT, "%.2f", loss / n));
        }
        assertTrue(differsFromCumulative, "the test must be able to tell window means from running means");
    }

    /** A clock with negative values (System.nanoTime may be negative) is as good as any other. */
    @Test
    void aNegativeClockWorks() throws IOException {
        RunFixtures.FakeClock fake = new RunFixtures.FakeClock();
        fake.wall.set(-100 * SECOND);
        fake.cpu.set(-50 * SECOND);
        TrainingRun.Summary s = run(0, 10, 5, Long.MAX_VALUE, 100, fake,
                fake.ticking(SECOND, SECOND / 4, 3 * SECOND, SECOND), new RunFixtures.Captured(), 1000 * SECOND,
                new ArrayList<>());
        assertEquals(16 * SECOND, s.timing().loopWallNanos());
        assertEquals(4 * SECOND + SECOND / 2, s.timing().loopCpuNanos());
        assertEquals(1.0, s.timing().share(TrainingRun.Category.OBSERVER), 1e-12);
    }

    /**
     * A monitor thread that polls {@code timing()} while the loop runs sees the loop thread's CPU time, not its
     * own: the poller spins, so its own CPU time is large and would show.
     */
    @Test
    void timingPolledFromAnotherThreadReportsTheLoopThreadsCpuTime() throws Exception {
        Parameters p = Parameters.init(RunFixtures.TINY, 1);
        Optimizer o = new Optimizer(p, Optimizer.Settings.adamw());
        TrainingObserver sleeper = new TrainingObserver() {
            @Override
            public void onStep(long step, double loss, double klPolicy, double valueLoss, long wallNanos) {
                try {
                    Thread.sleep(15);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        TrainingRun run = new TrainingRun(RunFixtures.setup(p, o, 0, 30),
                RunFixtures.checkpointing(dir, 10, Long.MAX_VALUE, 2),
                new TrainingRun.Reporting(List.of(sleeper), null, 1000 * SECOND), TrainingRun.Clock.system(),
                RunFixtures.never());
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<String> violation = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicLong polls = new java.util.concurrent.atomic.AtomicLong();
        long tolerance = 20_000_000L; // the latest interval is published when it ends
        Thread poller = new Thread(() -> {
            while (!done.get()) { // spins: this thread's own CPU time grows as fast as wall time
                TrainingRun.Timing t = run.timing();
                polls.incrementAndGet();
                long sum = t.step().cpuNanos() + t.checkpoint().cpuNanos() + t.observer().cpuNanos()
                        + t.progress().cpuNanos();
                if (t.loopCpuNanos() < 0 || t.loopCpuNanos() > t.loopWallNanos() + tolerance
                        || t.step().cpuNanos() > t.loopCpuNanos() + tolerance
                        || Math.abs(t.loopCpuNanos() - sum) > tolerance) {
                    violation.compareAndSet(null, t.toString());
                }
            }
        });
        poller.start();
        try {
            run.run();
        } finally {
            done.set(true);
            poller.join();
        }
        assertTrue(polls.get() > 100, "polled " + polls.get());
        assertEquals(null, violation.get());
    }
}
