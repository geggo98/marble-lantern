package sternhalma.nn;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * The training loop of the CPU reference trainer with checkpoints, progress lines and a measurement hook: every
 * training run goes through it, so that every run can be observed by a {@link TrainingObserver} and the time
 * that checkpoints and observers cost is known (docs/training.md, "As implemented (block 2b)").
 *
 * <p>One step: the learning rate of {@link LearningRateSchedule}, the batch of {@link BatchSampler}, the gradients
 * of {@link ReferenceTrainer}, the update of {@link Optimizer}. After it: the observers, then a checkpoint if one
 * is due, then a progress line if one is due. A checkpoint is due when the number of completed steps is a multiple
 * of {@code everySteps}, or when {@code everyNanos} have passed since the last checkpoint (or since the start),
 * whichever comes first; a last one is written when the run ends with steps that no checkpoint covers, also after a
 * stop. Old checkpoints beyond {@code keep} are deleted.
 *
 * <p>The progress line follows docs/training.md, "Progress": at most one per interval, on the given stream, with
 * no carriage return. The rate counts only the steps done by this run (not the steps that a checkpoint
 * restored), so the ETA after a restart is right.
 *
 * <p>The wall time and the thread CPU time of four categories are measured separately and are available as
 * {@link Timing}: the steps (the calling thread only; worker threads of {@code threads &gt; 1} are not included in
 * the CPU time), the checkpoints (write and clean-up), the observers, and the progress lines. A monitor reads the
 * shares of {@link Timing#share}. The time is measured with {@link Clock}; tests inject a clock.
 *
 * <p>One thread runs the loop. {@link #timing()} may be called from another thread; the CPU time it reports is
 * the loop thread's, as published at the end of its latest measured interval.
 *
 * <p>Failure: an exception from an observer, a step or a checkpoint write ends the run at once, with no final
 * checkpoint; the next run resumes from the latest checkpoint that reads. Checkpoints are not synced to disk
 * (no fsync): a power loss may lose or damage the newest file, and {@link Checkpoint#recover} then falls back to
 * an older one.
 *
 * <p>Each generation needs a fresh {@link Optimizer}: its step count is at the same time the Adam t, the index of
 * the learning-rate schedule and the step of the batch sampler, all counted from 0 within a generation. A caller
 * (the training loop of A10) must not carry the optimiser state or step count from one generation into the next.
 */
public final class TrainingRun {

    /** The categories of {@link Timing}. */
    public enum Category {
        STEP, CHECKPOINT, OBSERVER, PROGRESS
    }

    /**
     * The clocks of a run. {@code wallNanos} is a monotonic clock like {@link System#nanoTime}; {@code cpuNanos}
     * is the CPU time of the calling thread.
     */
    public record Clock(LongSupplier wallNanos, LongSupplier cpuNanos) {

        public Clock {
            Objects.requireNonNull(wallNanos, "wallNanos");
            Objects.requireNonNull(cpuNanos, "cpuNanos");
        }

        /** {@link System#nanoTime} and the thread CPU time (0 where the JVM cannot measure it). */
        public static Clock system() {
            ThreadMXBean threads = ManagementFactory.getThreadMXBean();
            LongSupplier cpu = threads.isCurrentThreadCpuTimeSupported() ? threads::getCurrentThreadCpuTime : () -> 0L;
            return new Clock(System::nanoTime, cpu);
        }
    }

    /**
     * What is trained.
     *
     * @param optimizer         must belong to {@code parameters}; its step count is the step the run starts at
     *                          (a resumed run) and must be 0 for the first run of a generation: use a fresh
     *                          optimiser per generation
     * @param pool              the samples that the batches are drawn from
     * @param batchSize         samples per step
     * @param microbatch        samples per microbatch of the trainer
     * @param threads           threads of the trainer
     * @param valueCoefficient  c_v
     * @param runSeed           seed of the batch sampler
     * @param generation        generation of the run
     * @param plannedSteps      the run ends when the optimiser has completed this many steps in total
     */
    public record Setup(Parameters parameters, Optimizer optimizer, LearningRateSchedule schedule,
            TrainingBatch pool, int batchSize, int microbatch, int threads, double valueCoefficient, long runSeed,
            int generation, long plannedSteps) {

        public Setup {
            Objects.requireNonNull(parameters, "parameters");
            Objects.requireNonNull(optimizer, "optimizer");
            Objects.requireNonNull(schedule, "schedule");
            Objects.requireNonNull(pool, "pool");
            if (optimizer.parameters() != parameters) {
                throw new IllegalArgumentException("the optimizer belongs to other parameters");
            }
            if (pool.players() != parameters.config().players()) {
                throw new IllegalArgumentException("pool has " + pool.players() + " players, network "
                        + parameters.config().players());
            }
            if (batchSize < 1 || microbatch < 1 || threads < 1) {
                throw new IllegalArgumentException("batchSize, microbatch and threads must be >= 1");
            }
            if (generation < 0) {
                throw new IllegalArgumentException("generation must be >= 0, got " + generation);
            }
            if (plannedSteps < optimizer.steps()) {
                throw new IllegalArgumentException("plannedSteps (" + plannedSteps + ") is below the completed steps ("
                        + optimizer.steps() + ")");
            }
        }
    }

    /**
     * When and where checkpoints are written.
     *
     * @param directory       checkpoint directory; created if missing
     * @param everySteps      a checkpoint when the completed steps are a multiple of this
     * @param everyNanos      or when this much wall time has passed since the last checkpoint
     * @param keep            checkpoints to keep
     * @param runConfigSha256 stored in every checkpoint
     */
    public record Checkpointing(Path directory, long everySteps, long everyNanos, int keep, String runConfigSha256) {

        public Checkpointing {
            Objects.requireNonNull(directory, "directory");
            if (everySteps < 1 || everyNanos < 1 || keep < 1) {
                throw new IllegalArgumentException("everySteps, everyNanos and keep must be >= 1");
            }
            Checkpoint.requireSha(runConfigSha256);
        }
    }

    /**
     * Who is told what.
     *
     * @param observers          called in this order
     * @param progress           stream for the progress and summary lines; null for none
     * @param progressEveryNanos wall time between progress lines
     */
    public record Reporting(List<TrainingObserver> observers, PrintStream progress, long progressEveryNanos) {

        public Reporting {
            observers = List.copyOf(observers);
            if (progressEveryNanos < 1) {
                throw new IllegalArgumentException("progressEveryNanos must be >= 1, got " + progressEveryNanos);
            }
        }
    }

    /** Time spent in one category. */
    public record Spent(long wallNanos, long cpuNanos) {
    }

    /**
     * The time of a run so far, by category. {@code loopWallNanos} and {@code loopCpuNanos} cover the whole loop
     * from its start; what no category claims (the sampler is part of the step, so this is the loop's own
     * bookkeeping and the checks for stop and cadence) is {@link #unaccountedWallNanos()}.
     */
    public record Timing(long steps, long checkpoints, long checkpointBytes, long loopWallNanos, long loopCpuNanos,
            Spent step, Spent checkpoint, Spent observer, Spent progress) {

        public Spent spent(Category category) {
            return switch (category) {
                case STEP -> step;
                case CHECKPOINT -> checkpoint;
                case OBSERVER -> observer;
                case PROGRESS -> progress;
            };
        }

        /** Wall time of the category as a fraction of the loop's wall time; 0 while that is 0. */
        public double share(Category category) {
            return loopWallNanos > 0 ? (double) spent(category).wallNanos() / loopWallNanos : 0;
        }

        /** CPU time of the category as a fraction of the loop thread's CPU time; 0 while that is 0. */
        public double cpuShare(Category category) {
            return loopCpuNanos > 0 ? (double) spent(category).cpuNanos() / loopCpuNanos : 0;
        }

        public long unaccountedWallNanos() {
            return loopWallNanos - step.wallNanos() - checkpoint.wallNanos() - observer.wallNanos()
                    - progress.wallNanos();
        }
    }

    /**
     * The end of a run.
     *
     * @param startStep        completed steps when the run began (the steps that a checkpoint restored)
     * @param endStep          completed steps at the end
     * @param aborted          true if the stop supplier ended the run before the planned steps
     * @param checkpointsWritten checkpoints written by this run
     * @param cleanup          leftover temporary files removed at the start
     */
    public record Summary(long startStep, long endStep, long plannedSteps, boolean aborted, int checkpointsWritten,
            int cleanup, Timing timing) {

        /** Steps done by this run. */
        public long stepsDone() {
            return endStep - startStep;
        }
    }

    /** Accumulates wall and CPU time of one category. One writer, any readers. */
    private final class Meter {
        private volatile long wall;
        private volatile long cpu;
        private long wall0;
        private long cpu0;

        void begin() {
            wall0 = clock.wallNanos().getAsLong();
            cpu0 = clock.cpuNanos().getAsLong();
        }

        /** Ends the interval and returns its wall time. */
        long end() {
            long dw = clock.wallNanos().getAsLong() - wall0;
            long cpuNow = clock.cpuNanos().getAsLong();
            long dc = cpuNow - cpu0;
            wall += dw;
            cpu += dc;
            loopCpuNow = cpuNow;
            return dw;
        }

        Spent spent() {
            return new Spent(wall, cpu);
        }
    }

    private final Setup setup;
    private final Checkpointing checkpointing;
    private final Reporting reporting;
    private final Clock clock;
    private final BooleanSupplier stop;
    private final ReferenceTrainer trainer;

    private final Meter stepMeter = new Meter();
    private final Meter checkpointMeter = new Meter();
    private final Meter observerMeter = new Meter();
    private final Meter progressMeter = new Meter();

    private volatile boolean started;
    private volatile boolean finished;
    private volatile long loopWallStart;
    private volatile long loopCpuStart;
    private volatile long loopWallEnd;
    private volatile long loopCpuEnd;
    /** CPU time of the loop thread at the end of its latest measured interval; read by other threads. */
    private volatile long loopCpuNow;
    private volatile long stepsDone;
    private volatile long checkpointsWritten;
    private volatile long checkpointBytes;

    /**
     * @param stop asked between steps; true ends the run after the current step with a checkpoint
     */
    public TrainingRun(Setup setup, Checkpointing checkpointing, Reporting reporting, Clock clock,
            BooleanSupplier stop) {
        this.setup = Objects.requireNonNull(setup, "setup");
        this.checkpointing = Objects.requireNonNull(checkpointing, "checkpointing");
        this.reporting = Objects.requireNonNull(reporting, "reporting");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.stop = Objects.requireNonNull(stop, "stop");
        this.trainer = new ReferenceTrainer(setup.parameters(), setup.valueCoefficient());
    }

    /** The time so far; after {@link #run} the final figures. */
    public Timing timing() {
        if (!started) {
            return new Timing(0, 0, 0, 0, 0, stepMeter.spent(), checkpointMeter.spent(), observerMeter.spent(),
                    progressMeter.spent());
        }
        boolean done = finished;
        long wall = (done ? loopWallEnd : clock.wallNanos().getAsLong()) - loopWallStart;
        // Never the calling thread's CPU time: a monitor thread would measure itself.
        long cpu = (done ? loopCpuEnd : loopCpuNow) - loopCpuStart;
        return new Timing(stepsDone, checkpointsWritten, checkpointBytes, wall, cpu, stepMeter.spent(),
                checkpointMeter.spent(), observerMeter.spent(), progressMeter.spent());
    }

    /**
     * Runs until the planned steps are done or the stop supplier says so.
     *
     * @throws IOException if a checkpoint cannot be written
     */
    public Summary run() throws IOException {
        Optimizer optimizer = setup.optimizer();
        Files.createDirectories(checkpointing.directory());
        int cleanup = Checkpoint.removeTemporaries(checkpointing.directory());
        long start = optimizer.steps();
        long planned = setup.plannedSteps();

        long wall0 = clock.wallNanos().getAsLong();
        loopCpuStart = clock.cpuNanos().getAsLong();
        loopCpuNow = loopCpuStart;
        loopWallStart = wall0;
        started = true;
        long lastCheckpointStep = start;
        long lastCheckpointWall = wall0;
        long lastProgressWall = wall0;
        long lastProgressStep = start;
        double sumLoss = 0;
        double sumKl = 0;
        double sumValue = 0;
        int sinceProgress = 0;
        boolean aborted = false;

        while (optimizer.steps() < planned) {
            if (stop.getAsBoolean()) {
                aborted = true;
                break;
            }
            stepMeter.begin();
            long index = optimizer.steps();
            double lr = setup.schedule().rate(setup.generation(), index);
            TrainingBatch batch = BatchSampler.batch(setup.pool(), setup.runSeed(), setup.generation(), index,
                    setup.batchSize());
            trainer.zeroGradients();
            ReferenceTrainer.Result result = trainer.accumulate(batch, setup.microbatch(), setup.threads());
            optimizer.step(trainer.gradients(), lr);
            long stepNanos = stepMeter.end();
            long done = optimizer.steps();
            stepsDone = done - start;
            sumLoss += result.loss();
            sumKl += result.kl();
            sumValue += result.valueLoss();
            sinceProgress++;

            observerMeter.begin();
            for (TrainingObserver o : reporting.observers()) {
                o.onStep(done, result.loss(), result.kl(), result.valueLoss(), stepNanos);
            }
            observerMeter.end();

            long now = clock.wallNanos().getAsLong();
            if (done % checkpointing.everySteps() == 0 || now - lastCheckpointWall >= checkpointing.everyNanos()) {
                checkpoint(done);
                lastCheckpointStep = done;
                lastCheckpointWall = clock.wallNanos().getAsLong();
            }

            now = clock.wallNanos().getAsLong();
            if (reporting.progress() != null && now - lastProgressWall >= reporting.progressEveryNanos()) {
                progressMeter.begin();
                printProgress(done - start, done, planned, now - wall0, sumLoss / sinceProgress,
                        sumKl / sinceProgress, sumValue / sinceProgress);
                progressMeter.end();
                lastProgressWall = clock.wallNanos().getAsLong();
                lastProgressStep = done;
                sumLoss = sumKl = sumValue = 0;
                sinceProgress = 0;
            }
        }

        if (optimizer.steps() > lastCheckpointStep) {
            checkpoint(optimizer.steps());
        }
        if (reporting.progress() != null) {
            progressMeter.begin();
            long done = optimizer.steps();
            if (sinceProgress > 0 && done != lastProgressStep) {
                printProgress(done - start, done, planned, clock.wallNanos().getAsLong() - wall0,
                        sumLoss / sinceProgress, sumKl / sinceProgress, sumValue / sinceProgress);
            }
            reporting.progress().println(summaryLine(start, done, planned, cleanup, aborted));
            progressMeter.end();
        }
        loopCpuEnd = clock.cpuNanos().getAsLong();
        loopWallEnd = clock.wallNanos().getAsLong();
        finished = true;
        return new Summary(start, optimizer.steps(), planned, aborted, (int) checkpointsWritten, cleanup, timing());
    }

    private void checkpoint(long step) throws IOException {
        Path file = Checkpoint.file(checkpointing.directory(), step);
        checkpointMeter.begin();
        long bytes = Checkpoint.write(file, setup.parameters(), setup.optimizer(), setup.generation(),
                checkpointing.runConfigSha256());
        Checkpoint.keepLast(checkpointing.directory(), checkpointing.keep());
        long nanos = checkpointMeter.end();
        checkpointsWritten++;
        checkpointBytes += bytes;
        observerMeter.begin();
        for (TrainingObserver o : reporting.observers()) {
            o.onCheckpoint(file, nanos, bytes);
        }
        observerMeter.end();
    }

    private void printProgress(long stepsThisRun, long done, long planned, long elapsedNanos, double loss, double kl,
            double value) {
        double seconds = elapsedNanos / 1e9;
        double rate = seconds > 0 ? stepsThisRun / seconds : 0;
        String eta = rate > 0 ? duration(Math.round((planned - done) / rate)) : "?";
        String rateText = rate >= 1 ? String.format(Locale.ROOT, "%.1f", rate) : String.format(Locale.ROOT, "%.2f", rate);
        reporting.progress().println(String.format(Locale.ROOT,
                "gen %d train: %d/%d steps  %s steps/s  ETA %s  elapsed %s  loss %.2f (kl %.2f, value %.2f)",
                setup.generation(), done, planned, rateText, eta, duration(Math.round(seconds)), loss, kl, value));
    }

    private String summaryLine(long start, long done, long planned, int cleanup, boolean aborted) {
        long left = planned - done;
        return String.format(Locale.ROOT, "gen %d train: %d ok, %d skip, 0 error, %d cleanup; %d left%s",
                setup.generation(), done - start, start, cleanup, left,
                aborted ? "; aborted" : left == 0 ? "; nothing more to do" : "");
    }

    /** {@code 38s}, {@code 1m29s}, {@code 2h03m10s}. */
    static String duration(long seconds) {
        if (seconds < 60) {
            return seconds + "s";
        }
        if (seconds < 3600) {
            return String.format(Locale.ROOT, "%dm%02ds", seconds / 60, seconds % 60);
        }
        return String.format(Locale.ROOT, "%dh%02dm%02ds", seconds / 3600, seconds % 3600 / 60, seconds % 60);
    }
}
