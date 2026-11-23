package sternhalma.selfplay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sternhalma.nn.Checkpoint;
import sternhalma.nn.Parameters;
import sternhalma.nn.Tensor;

/**
 * Generation 0 end to end on TINY and synthetic teacher shards: artefacts, resume after an abort at every point of the
 * training steps, the idempotent second run, the lock, the configuration check and the cadence independence.
 */
class Gen0Test {

    static final int STEPS = 12;

    @TempDir
    static Path shared;
    static Path data;

    @BeforeAll
    static void data() throws IOException {
        data = Files.createDirectory(shared.resolve("data"));
        SyntheticShards.write(data, "samples-s1-0000.safetensors", 1, 0, 20, 8, 21);
        SyntheticShards.write(data, "samples-s1-0001.safetensors", 1, 20, 20, 8, 22);
    }

    record Result(int code, String err, String out) {
    }

    /** The options of every run here; the tests add cadences. */
    static List<String> base(String... more) {
        List<String> args = new ArrayList<>(List.of("--preset", "tiny", "--steps", Integer.toString(STEPS), "--batch", "8",
                "--microbatch", "4", "--threads", "2", "--warmup", "2", "--lr", "0.01", "--holdout", "0.25",
                "--eval-samples", "40", "--proxy-seconds", "0", "--elo-seconds", "0", "--checkpoint-steps", "4", "--progress-seconds", "0.01", "--seed", "5"));
        args.addAll(List.of(more));
        return args;
    }

    static Result gen0(java.util.function.BooleanSupplier stop, String command, String... args) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Gen0.run(Stream.concat(Stream.of(command), Arrays.stream(args)).toArray(String[]::new),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                stop);
        return new Result(code, err.toString(StandardCharsets.UTF_8), out.toString(StandardCharsets.UTF_8));
    }

    static Result train(Path run, java.util.function.BooleanSupplier stop, List<String> options) throws Exception {
        List<String> args = new ArrayList<>(List.of(data.toString(), run.toString()));
        args.addAll(options);
        return gen0(stop, "run", args.toArray(String[]::new));
    }

    static Result train(Path run, String... more) throws Exception {
        return train(run, () -> false, base(more));
    }

    /** Stops after {@code steps} training steps (the loop asks once before each step). */
    static java.util.function.BooleanSupplier stopAfter(int steps) {
        AtomicInteger asked = new AtomicInteger();
        return () -> asked.incrementAndGet() > steps;
    }

    static Path candidate(Path run) {
        return run.resolve("gen-0000").resolve("candidate.safetensors");
    }

    static Path trainDir(Path run) {
        return run.resolve("gen-0000").resolve("train");
    }

    static void assertSameNetwork(Path a, Path b) throws IOException {
        Parameters pa = Parameters.load(a);
        Parameters pb = Parameters.load(b);
        assertEquals(pa.config(), pb.config());
        for (Map.Entry<String, Tensor> e : pa.tensors().entrySet()) {
            assertArrayEquals(e.getValue().data(), pb.get(e.getKey()).data(), e.getKey());
        }
    }

    private static Path reference;

    /** The uninterrupted run that the others are compared with, made once. */
    static synchronized Path reference() throws Exception {
        if (reference == null) {
            reference = shared.resolve("reference");
            Result r = train(reference);
            assertEquals(0, r.code, r.err);
        }
        return reference;
    }

    @Test
    void aFreshRunWritesTheArtefactsAndEveryPhasePrintsTheEtaOfTheWholeRun() throws Exception {
        Path run = shared.resolve("fresh");
        Result r = train(run);
        assertEquals(0, r.code, r.err);
        assertTrue(Files.exists(run.resolve("run.json")));
        Parameters candidate = Parameters.load(candidate(run));
        assertEquals(Integer.toString(STEPS), candidate.extraMetadata().get("step"));
        // keep 2 checkpoints, the newest is the last step
        List<Path> checkpoints = Checkpoint.list(trainDir(run));
        assertEquals(List.of(8L, 12L), checkpoints.stream().map(Checkpoint::stepOf).toList());
        // load, train and finish each print the ETA of the whole run
        List<String> phases = r.err.lines().filter(l -> l.startsWith("gen 0 load:") || l.startsWith("gen 0 train: ")
                && l.contains(" in ") || l.startsWith("gen 0 finish:")).toList();
        assertEquals(3, phases.size(), r.err);
        for (String line : phases) {
            assertTrue(line.contains("whole run:"), line);
        }
        assertTrue(phases.get(0).contains(STEPS + " steps left, ETA unknown"), "no earlier rate yet: " + phases.get(0));
        assertTrue(phases.get(1).contains("nothing left to train"), phases.get(1));
        assertTrue(r.err.contains("gen 0 train: " + STEPS + " ok, 0 skip, 0 error, 0 cleanup; 0 left; nothing more to do"),
                r.err);
        // the proxies ran at the three checkpoints (4, 8, 12) and the monitor knows the loop
        assertEquals(3, r.err.lines().filter(l -> l.startsWith("gen 0 proxies at step")).count(), r.err);
        assertTrue(r.err.contains("yardstick 78.8%"));
        RunMonitor.Report report = RunMonitor.report(run);
        assertEquals(1, report.processes());
        assertEquals(STEPS, report.steps());
        assertEquals(3, report.row(RunMonitor.Category.STRENGTH, "proxy").count());
        assertEquals(3, report.row(RunMonitor.Category.CHECKPOINT, "").count());
        assertTrue(report.row(RunMonitor.Category.PROGRESS, "").count() >= 1, "the progress lines are counted");
        assertTrue(report.row(RunMonitor.Category.TRAIN, "").share() > 0);
        Result text = gen0(() -> false, "monitor-report", run.toString());
        assertEquals(0, text.code);
        assertTrue(text.out.contains("strength proxies") && text.out.contains("checkpoint"), text.out);
        ByteArrayOutputStream viaSelfPlay = new ByteArrayOutputStream();
        assertEquals(0, SelfPlay.run(new String[] {"monitor-report", run.toString()},
                new PrintStream(viaSelfPlay, true, StandardCharsets.UTF_8), System.err), "selfplay.sh monitor-report");
        assertEquals(text.out, viaSelfPlay.toString(StandardCharsets.UTF_8));
    }

    @Test
    void aSecondRunAfterSuccessDoesNothingAndExitsWithZero() throws Exception {
        Path run = shared.resolve("again");
        assertEquals(0, train(run).code);
        byte[] candidate = Files.readAllBytes(candidate(run));
        byte[] metrics = Files.readAllBytes(run.resolve("metrics.jsonl"));
        List<Path> before = Checkpoint.list(trainDir(run));
        Result second = train(run);
        assertEquals(0, second.code, second.err);
        assertTrue(second.err.contains("nothing more to do"), second.err);
        assertTrue(second.err.contains(STEPS + " skip"), second.err);
        assertFalse(second.err.contains("gen 0 load:"), "the data is not even loaded");
        assertArrayEquals(candidate, Files.readAllBytes(candidate(run)));
        assertArrayEquals(metrics, Files.readAllBytes(run.resolve("metrics.jsonl")), "no new monitor line");
        assertEquals(before, Checkpoint.list(trainDir(run)));
    }

    @Test
    void anAbortAtEveryPointOfTheStepsResumesToTheResultOfAnUninterruptedRun() throws Exception {
        Path expected = candidate(reference());
        for (int k : new int[] {0, 1, 3, 4, 5, 8, 11}) {
            Path run = shared.resolve("abort-" + k);
            Result first = train(run, stopAfter(k), base());
            assertEquals(130, first.code, "k=" + k + "\n" + first.err);
            assertFalse(Files.exists(candidate(run)), "no candidate after an abort at " + k);
            assertTrue(first.err.contains("gen 0 finish: aborted at step " + k), first.err);
            assertTrue(first.err.contains("run again to continue"), first.err);
            RunMonitor.Report earlier = k > 0 ? RunMonitor.report(run) : null;
            Result second = train(run);
            assertEquals(0, second.code, "k=" + k + "\n" + second.err);
            assertTrue(second.err.contains("gen 0 train: " + (STEPS - k) + " ok, " + k + " skip, 0 error"),
                    "k=" + k + ": " + second.err);
            assertSameNetwork(expected, candidate(run));
            if (k > 0) {
                assertTrue(second.err.contains("start at step " + k), second.err);
                double rate = earlier.steps() / (earlier.loopWallNanos() / 1e9);
                assertTrue(second.err.contains(String.format(java.util.Locale.ROOT, "at %.2f steps/s (rate of an earlier run)",
                        rate)), "the ETA before the first measurement uses the rate of the earlier run: " + second.err);
            }
        }
    }

    @Test
    void aKillWithoutAFinalCheckpointAndADamagedCheckpointAlsoResume() throws Exception {
        Path expected = candidate(reference());
        // as a kill -9: the run died at step 6 and the checkpoint of step 6 does not exist
        Path killed = shared.resolve("killed");
        assertEquals(130, train(killed, stopAfter(6), base()).code);
        Files.delete(Checkpoint.file(trainDir(killed), 6));
        Files.writeString(trainDir(killed).resolve("step-00000008.safetensors.1234.tmp"), "half");
        Files.writeString(killed.resolve("gen-0000").resolve("candidate.safetensors.tmp"), "half");
        Result r = train(killed);
        assertEquals(0, r.code, r.err);
        assertTrue(r.err.contains("start at step 4"), r.err);
        assertSameNetwork(expected, candidate(killed));
        assertTrue(r.err.contains("2 cleanup") || r.err.contains("1 cleanup"), "leftovers are counted: " + r.err);
        try (Stream<Path> s = Files.walk(killed)) {
            assertTrue(s.noneMatch(f -> f.getFileName().toString().endsWith(".tmp")));
        }

        // a damaged newest checkpoint is quarantined and the next older one is used
        Path damaged = shared.resolve("damaged");
        assertEquals(130, train(damaged, stopAfter(6), base()).code);
        Path newest = Checkpoint.file(trainDir(damaged), 6);
        byte[] bytes = Files.readAllBytes(newest);
        Files.write(newest, Arrays.copyOf(bytes, bytes.length / 2));
        Result d = train(damaged);
        assertEquals(0, d.code, d.err);
        assertTrue(d.err.contains("start at step 4"), d.err);
        assertTrue(d.err.contains("1 cleanup"), d.err);
        assertTrue(Files.exists(newest.resolveSibling(newest.getFileName() + ".corrupt")));
        assertSameNetwork(expected, candidate(damaged));
    }

    @Test
    void aCrashAfterTheLastStepButBeforeTheCandidateStillFinishes() throws Exception {
        Path expected = candidate(reference());
        Path run = shared.resolve("late");
        assertEquals(0, train(run).code);
        Files.delete(candidate(run));
        Result r = train(run);
        assertEquals(0, r.code, r.err);
        assertTrue(r.err.contains("start at step " + STEPS), r.err);
        assertTrue(r.err.contains("gen 0 train: 0 ok, " + STEPS + " skip"), r.err);
        assertSameNetwork(expected, candidate(run));
    }

    @Test
    void aSecondProcessExitsWithSeventyFiveAndStatusSaysSo() throws Exception {
        Path run = shared.resolve("locked");
        Files.createDirectories(run);
        try (FileChannel ch = FileChannel.open(run.resolve(".gen0.lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE); FileLock lock = ch.tryLock()) {
            assertTrue(lock != null);
            Files.writeString(run.resolve(".gen0.status"), "pid=" + ProcessHandle.current().pid() + " start=x\n");
            Result r = train(run);
            assertEquals(75, r.code, r.err);
            assertTrue(r.err.contains("running already"), r.err);
            assertFalse(Files.exists(run.resolve("run.json")), "nothing was started");
            Result status = gen0(() -> false, "run", "--status", run.toString());
            assertEquals(75, status.code);
            assertTrue(status.err.contains("running already") || status.out.contains("running already"), status.out);
        }
        Result free = gen0(() -> false, "run", "--status", run.toString());
        assertEquals(0, free.code);
        assertTrue(free.out.startsWith("not running"), free.out);
        assertEquals(0, train(run).code, "after the lock is gone the run proceeds");
    }

    @Test
    void anotherConfigurationOrOtherDataInTheSameRunDirectoryIsRefused() throws Exception {
        Path run = shared.resolve("mismatch");
        assertEquals(130, train(run, stopAfter(2), base()).code);
        String json = Files.readString(run.resolve("run.json"));
        Result other = train(run, "--steps", "14");
        assertEquals(1, other.code, other.err);
        assertTrue(other.err.contains("another configuration"), other.err);
        assertEquals(1, train(run, "--lr", "0.02").code);
        assertEquals(1, train(run, "--seed", "6").code);
        assertEquals(1, train(run, "--microbatch", "2").code);
        assertEquals(1, train(run, "--warmup", "3").code);
        assertEquals(1, train(run, "--augment", "0").code);
        assertEquals(1, train(run, "--preset", "small").code);
        assertEquals(1, train(run, "--value-coefficient", "0.5").code);
        assertEquals(1, train(run, "--batch", "16").code);
        assertEquals(1, train(run, "--holdout", "0.3").code);
        assertEquals(json, Files.readString(run.resolve("run.json")), "never rewritten");
        // other data: one more shard
        Path moreData = shared.resolve("data2");
        Files.createDirectories(moreData);
        for (Path f : Gen0.dataFiles(data)) {
            Files.copy(f, moreData.resolve(f.getFileName()));
        }
        SyntheticShards.write(moreData, "samples-s1-0002.safetensors", 1, 40, 10, 8, 23);
        List<String> args = new ArrayList<>(List.of(moreData.toString(), run.toString()));
        args.addAll(base());
        assertEquals(1, gen0(() -> false, "run", args.toArray(String[]::new)).code);
        // the right configuration still resumes
        assertEquals(0, train(run).code);
    }

    @Test
    void cadencesAndTheMonitorAreNotPartOfTheConfigurationAndChangeNoResult() throws Exception {
        Path other = shared.resolve("cadence");
        Result r = train(other, () -> false, base("--checkpoint-steps", "3", "--keep", "3", "--proxy-every", "2",
                "--eval-samples", "20", "--progress-seconds", "5", "--checkpoint-seconds", "1000", "--threads", "1"));
        assertEquals(0, r.code, r.err);
        Path ref = reference();
        assertEquals(Files.readString(ref.resolve("run.json")), Files.readString(other.resolve("run.json")));
        assertSameNetwork(candidate(ref), candidate(other));
        assertEquals(List.of(6L, 9L, 12L), Checkpoint.list(trainDir(other)).stream().map(Checkpoint::stepOf).toList());
        assertEquals(List.of("3", "6", "12"), r.err.lines().filter(l -> l.startsWith("gen 0 proxies at step"))
                .map(l -> l.split(" ")[5]).toList(),
                "proxies at the first checkpoint, every second one (6) and the last (12) of 3, 6, 9, 12");
        // the monitor off: no metrics, the same network, no proxies
        Path off = shared.resolve("monitor-off");
        Result o = train(off, () -> false, base("--monitor", "0"));
        assertEquals(0, o.code, o.err);
        assertFalse(Files.exists(off.resolve("metrics.jsonl")));
        assertFalse(o.err.contains("gen 0 proxies"));
        assertTrue(o.err.contains("--monitor 0 also switches off the held-out proxies and the Elo estimate"),
                "the note at the start says what the switch takes with it: " + o.err);
        assertFalse(r.err.contains("--monitor 0"), "no note when the monitor is on");
        assertSameNetwork(candidate(ref), candidate(off));
        // the run config hash in every checkpoint is that of run.json
        String sha = Gen0.sha256Hex(Files.readString(ref.resolve("run.json")).strip().getBytes(StandardCharsets.UTF_8));
        assertEquals(sha, Checkpoint.read(Checkpoint.list(trainDir(ref)).get(0)).runConfigSha256());
    }

    @Test
    void theProxiesWaitForTheirIntervalButNeverSkipTheLastCheckpoint() throws Exception {
        Path run = shared.resolve("proxy-seconds");
        Result r = train(run, () -> false, base("--proxy-seconds", "1000"));
        assertEquals(0, r.code, r.err);
        List<String> lines = r.err.lines().filter(l -> l.startsWith("gen 0 proxies at step")).toList();
        assertEquals(2, lines.size(), "the first checkpoint and the last one, not 4, 8: " + r.err);
        assertTrue(lines.get(0).startsWith("gen 0 proxies at step 4 "), lines.get(0));
        assertTrue(lines.get(1).startsWith("gen 0 proxies at step 12 "), lines.get(1));
    }

    @Test
    void theFirstCheckpointIsEvaluatedWhateverTheProxyCadence() throws Exception {
        // checkpoints at 2, 4, ..., 12; every third one would be 6 and 12 only
        Path run = shared.resolve("proxy-every-3");
        Result r = train(run, () -> false, base("--checkpoint-steps", "2", "--keep", "6", "--proxy-every", "3"));
        assertEquals(0, r.code, r.err);
        assertEquals(List.of("2", "6", "12"), r.err.lines().filter(l -> l.startsWith("gen 0 proxies at step"))
                .map(l -> l.split(" ")[5]).toList(), r.err);
    }

    @Test
    void temporaryFilesOfAnotherProcessSurviveTheStartCleanup() throws Exception {
        Path run = shared.resolve("tmp-age");
        Path strength = Files.createDirectories(run.resolve("gen-0000").resolve("strength"));
        Path young = Files.writeString(strength.resolve("step-00000004.jsonl.tmp"), "in flight");
        Path old = Files.writeString(strength.resolve("step-00000008.jsonl.tmp"), "left by a dead process");
        Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.from(java.time.Instant.now().minusSeconds(3600)));
        Path youngStatus = Files.writeString(run.resolve(".strength.status.tmp"), "in flight");
        Result r = train(run);
        assertEquals(0, r.code, r.err);
        assertTrue(Files.exists(young), "a young temporary file may belong to a running strength process");
        assertTrue(Files.exists(youngStatus));
        assertFalse(Files.exists(old), "an old one is a leftover");
        assertTrue(r.err.contains("1 cleanup"), r.err);
        // the helper on its own: nothing to remove, missing directory, a file that vanished
        assertEquals(0, Gen0.removeTemporaries(shared.resolve("no-such-dir"), java.time.Duration.ZERO));
        assertEquals(1, Gen0.removeTemporaries(strength, java.time.Duration.ZERO), "age zero takes the young one");
        assertEquals(0, Gen0.removeTemporaries(strength, java.time.Duration.ZERO));
    }

    @Test
    void anEloEstimateCutShortByAStopIsNotCountedAsAnEvent() throws Exception {
        Path run = shared.resolve("elo-stopped");
        // one ask per step (4), then the first pairs of the estimate at step 4 run, a later pair sees the stop
        Result r = train(run, stopAfter(6), base("--elo-every", "1", "--elo-pairs", "4", "--ladder", "1",
                "--ladder-spec", "ab:eval=distance"));
        assertEquals(130, r.code, r.err);
        RunMonitor.Report report = RunMonitor.report(run);
        assertEquals(0, report.row(RunMonitor.Category.STRENGTH, "elo-partial").count(),
                "the time of the cut-short estimate is booked, but as no event");
        assertTrue(report.row(RunMonitor.Category.STRENGTH, "elo-partial").wallNanos() > 0);
        assertEquals(null, report.row(RunMonitor.Category.STRENGTH, "elo"), "no finished estimate, no Elo cost line");
        assertEquals(0, r.err.lines().filter(l -> l.startsWith("gen 0 elo at step")).count(), r.err);
    }

    @Test
    void theEloEstimateRunsInTheLoopAtTheCheckpointsAndIsSkippedWhenDone() throws Exception {
        Path run = shared.resolve("elo");
        List<String> options = base("--elo-every", "1", "--elo-pairs", "2", "--ladder", "1,100", "--ladder-spec",
                "ab:eval=distance", "--elo-seed", "9");
        Result first = train(run, () -> false, options);
        assertEquals(0, first.code, first.err);
        Path strength = run.resolve("gen-0000").resolve("strength");
        try (Stream<Path> s = Files.list(strength)) {
            assertEquals(List.of("step-00000004.jsonl", "step-00000008.jsonl", "step-00000012.jsonl"),
                    s.map(f -> f.getFileName().toString()).sorted().toList());
        }
        assertTrue(StrengthEstimate.complete(strength.resolve("step-00000008.jsonl"), "ab:eval=distance",
                List.of(1, 100), 2, 9));
        RunMonitor.Report report = RunMonitor.report(run);
        assertEquals(3, report.row(RunMonitor.Category.STRENGTH, "elo").count());
        assertFalse(report.row(RunMonitor.Category.STRENGTH, "elo").separateProcess());
        assertEquals(6, first.err.lines().filter(l -> l.startsWith("gen 0 elo at step")).count(), first.err);
        // the estimates do not change the training result
        assertSameNetwork(candidate(reference()), candidate(run));

        // the run dies after the last step but before the candidate, without its last checkpoint: the redone steps
        // reach the checkpoint of step 12 again, and the finished estimate of that step is skipped
        byte[] twelve = Files.readAllBytes(strength.resolve("step-00000012.jsonl"));
        Files.delete(candidate(run));
        Files.delete(Checkpoint.file(trainDir(run), 12));
        Result second = train(run, () -> false, options);
        assertEquals(0, second.code, second.err);
        assertEquals(0, second.err.lines().filter(l -> l.startsWith("gen 0 elo at step")).count(), second.err);
        assertArrayEquals(twelve, Files.readAllBytes(strength.resolve("step-00000012.jsonl")));
        assertSameNetwork(candidate(reference()), candidate(run));
    }

    @Test
    void theSeparateStrengthStepWorksOnACopyOfACheckpointAndSkipsWhenDone() throws Exception {
        Path run = shared.resolve("separate");
        assertEquals(0, train(run).code);
        Path copyDir = Files.createDirectory(shared.resolve("copy"));
        Path copy = copyDir.resolve("some-name.safetensors");
        Files.copy(Checkpoint.file(trainDir(run), 8), copy);
        String[] args = {run.toString(), "--checkpoint", copy.toString(), "--elo-pairs", "2", "--ladder", "1",
                "--ladder-spec", "ab:eval=distance", "--elo-seed", "3", "--elo-threads", "2"};
        Result r = gen0(() -> false, "strength", args);
        assertEquals(0, r.code, r.err);
        assertTrue(r.out.contains("step 8 vs 1 nodes: Elo"), r.out);
        Path file = run.resolve("gen-0000").resolve("strength").resolve("step-00000008.jsonl");
        assertTrue(Files.exists(file));
        byte[] bytes = Files.readAllBytes(file);
        RunMonitor.Report report = RunMonitor.report(run);
        assertTrue(report.row(RunMonitor.Category.STRENGTH, "elo").separateProcess());
        // again: SKIP, exit 0, nothing rewritten
        Result again = gen0(() -> false, "strength", args);
        assertEquals(0, again.code, again.err);
        assertTrue(again.err.contains("1 skip") && again.err.contains("nothing more to do"), again.err);
        assertArrayEquals(bytes, Files.readAllBytes(file));
        // with no --checkpoint the newest one is used; a stop discards the estimate and exits 130
        Result newest = gen0(stopAfter(1), "strength", run.toString(), "--elo-pairs", "4", "--ladder", "1",
                "--ladder-spec", "ab:eval=distance", "--elo-seed", "3");
        assertEquals(130, newest.code, newest.err);
        // the cut-short estimate is no event of the Elo kind: its time is booked as partial, with count 0
        RunMonitor.Report afterStop = RunMonitor.report(run);
        assertEquals(1, afterStop.row(RunMonitor.Category.STRENGTH, "elo").count(), "only the finished estimate counts");
        assertEquals(0, afterStop.row(RunMonitor.Category.STRENGTH, "elo-partial").count());
        assertFalse(Files.exists(run.resolve("gen-0000").resolve("strength").resolve("step-00000012.jsonl")));
        // a second strength process exits with 75
        try (FileChannel ch = FileChannel.open(run.resolve(".strength.lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE); FileLock lock = ch.tryLock()) {
            assertTrue(lock != null);
            Result locked = gen0(() -> false, "strength", run.toString(), "--elo-pairs", "2", "--ladder", "1",
                    "--ladder-spec", "ab:eval=distance");
            assertEquals(75, locked.code, locked.err);
        }
        // a checkpoint of another run is refused
        Path stranger = shared.resolve("stranger");
        assertEquals(0, train(stranger, "--seed", "6").code);
        Result wrong = gen0(() -> false, "strength", run.toString(), "--checkpoint",
                Checkpoint.file(trainDir(stranger), 8).toString());
        assertEquals(1, wrong.code, wrong.err);
    }

    @Test
    void theStrengthStepCanEstimateAnMctsPlayerIntoAFileOfItsOwnWithoutTouchingTheRunsMetric() throws Exception {
        Path run = shared.resolve("mcts-player");
        assertEquals(0, train(run).code);
        String player = "mcts:sims=4,batch=2,race=0";
        String[] options = {"--elo-pairs", "2", "--ladder", "1", "--ladder-spec", "ab:eval=distance", "--elo-seed", "3",
                "--checkpoint", Checkpoint.file(trainDir(run), 8).toString()};
        List<String> args = new ArrayList<>(List.of(run.toString(), "--player", player));
        args.addAll(List.of(options));
        Result r = gen0(() -> false, "strength", args.toArray(String[]::new));
        assertEquals(0, r.code, r.err);
        assertTrue(r.out.contains("step 8 [" + player + "] vs 1 nodes: Elo"), r.out);
        Path strength = run.resolve("gen-0000").resolve("strength");
        assertFalse(Files.exists(strength.resolve("step-00000008.jsonl")), "the run's own estimate file is not written");
        Path file = StrengthEstimate.playerFile(strength, 8, player);
        assertTrue(Files.exists(file));
        assertTrue(StrengthEstimate.complete(file, "ab:eval=distance", List.of(1), 2, 3));
        assertEquals(null, RunMonitor.report(run).row(RunMonitor.Category.STRENGTH, "elo"), "no monitor line");
        // again: a skip
        Result again = gen0(() -> false, "strength", args.toArray(String[]::new));
        assertEquals(0, again.code, again.err);
        assertTrue(again.err.contains("1 skip"), again.err);
        // the default player still writes the usual file; "raw" is the default
        List<String> raw = new ArrayList<>(List.of(run.toString(), "--player", "raw"));
        raw.addAll(List.of(options));
        Result plain = gen0(() -> false, "strength", raw.toArray(String[]::new));
        assertEquals(0, plain.code, plain.err);
        assertTrue(Files.exists(strength.resolve("step-00000008.jsonl")));
        assertEquals(1, RunMonitor.report(run).row(RunMonitor.Category.STRENGTH, "elo").count());
        // something that is no player is a usage error
        List<String> bad = new ArrayList<>(List.of(run.toString(), "--player", "ab:nodes=5"));
        bad.addAll(List.of(options));
        assertEquals(2, gen0(() -> false, "strength", bad.toArray(String[]::new)).code);
    }

    @Test
    void theStrengthStepAcceptsAPlainNetworkFileAndRefusesOneOfAnotherRunOrWithoutMetadata() throws Exception {
        Path run = shared.resolve("plain-network");
        assertEquals(0, train(run).code);
        String[] options = {"--elo-pairs", "2", "--ladder", "1", "--ladder-spec", "ab:eval=distance", "--elo-seed", "3"};
        List<String> args = new ArrayList<>(List.of(run.toString(), "--checkpoint", candidate(run).toString()));
        args.addAll(List.of(options));
        Result r = gen0(() -> false, "strength", args.toArray(String[]::new));
        assertEquals(0, r.code, r.err);
        assertTrue(r.out.contains("step " + STEPS + " vs 1 nodes: Elo"), r.out + r.err);
        assertTrue(Files.exists(run.resolve("gen-0000").resolve("strength").resolve("step-00000012.jsonl")));
        // the same network as a checkpoint gives the same estimate (same step, so it is a skip now)
        assertEquals(StrengthEstimate.file(run.resolve("gen-0000").resolve("strength"), STEPS),
                run.resolve("gen-0000").resolve("strength").resolve("step-00000012.jsonl"));
        // a network file of another run, and one without the metadata that names run and step
        Path stranger = shared.resolve("plain-stranger");
        assertEquals(0, train(stranger, "--seed", "6").code);
        Result other = gen0(() -> false, "strength", run.toString(), "--checkpoint", candidate(stranger).toString());
        assertEquals(1, other.code, other.err);
        assertTrue(other.err.contains("another configuration"), other.err);
        Path bare = shared.resolve("bare.safetensors");
        Parameters.init(Parameters.load(candidate(run)).config(), 1).save(bare);
        Result none = gen0(() -> false, "strength", run.toString(), "--checkpoint", bare.toString());
        assertEquals(1, none.code, none.err);
        assertTrue(none.err.contains("run_config_sha256"), none.err);
        // not a network at all
        Path junk = Files.writeString(shared.resolve("junk.safetensors"), "not safetensors");
        Result bad = gen0(() -> false, "strength", run.toString(), "--checkpoint", junk.toString());
        assertEquals(1, bad.code, bad.err);
        assertTrue(bad.err.contains("neither a training checkpoint"), bad.err);
    }

    @Test
    void aLadderRungThatRepeatsRungOneIsRefused() throws Exception {
        Path run = shared.resolve("ladder-refused");
        assertEquals(0, train(run).code);
        Result r = gen0(() -> false, "strength", run.toString(), "--ladder", "1,5,20");
        assertEquals(2, r.code, r.err);
        assertTrue(r.err.contains("same moves as rung 1"), r.err);
        assertEquals(2, gen0(() -> false, "strength", run.toString(), "--ladder", "100,100").code);
        assertEquals(2, train(shared.resolve("ladder-run"), () -> false, base("--ladder", "50")).code);
    }

    @Test
    void theDefaultNumberOfProxySamplesIsTwentyFiveHundred() {
        assertEquals(2500, Gen0.cadence(Map.of()).evalSamples());
        assertEquals(40, Gen0.cadence(Map.of("eval-samples", "40")).evalSamples());
    }

    @Test
    void theProxiesStepEvaluatesACheckpointOrTheCandidateInItsOwnProcessLikeTheLoopDoes() throws Exception {
        Path run = shared.resolve("proxies-step");
        Result trained = train(run);
        assertEquals(0, trained.code, trained.err);
        // the loop evaluated step 12 on 40 samples; the separate step must give the same numbers
        String loopLine = trained.err.lines().filter(l -> l.startsWith("gen 0 proxies at step 12 ")).findFirst()
                .orElseThrow();
        java.util.regex.Pattern numbers = java.util.regex.Pattern.compile(
                "policy KL (?<kl>\\S+), top-1 (?<top1>\\S+)%, value loss (?<vl>\\S+), winner (?<w>\\S+)%");
        java.util.regex.Matcher loop = numbers.matcher(loopLine);
        assertTrue(loop.find(), loopLine);
        for (Path file : List.of(Checkpoint.file(trainDir(run), 12), candidate(run))) {
            Result r = gen0(() -> false, "proxies", run.toString(), "--data", data.toString(), "--checkpoint",
                    file.toString(), "--samples", "40", "--threads", "1");
            assertEquals(0, r.code, r.err);
            assertTrue(r.out.startsWith("step 12 proxies on 40 of "), r.out);
            java.util.regex.Matcher m = numbers.matcher(r.out);
            assertTrue(m.find(), r.out);
            for (String g : List.of("kl", "top1", "vl", "w")) {
                assertEquals(loop.group(g), m.group(g), g + " of " + file + ": " + r.out + " against " + loopLine);
            }
        }
        // no --checkpoint: the candidate; more samples than there are: all of them, and the count says so
        Result all = gen0(() -> false, "proxies", run.toString(), "--data", data.toString(), "--samples", "100000");
        assertEquals(0, all.code, all.err);
        java.util.regex.Matcher count = java.util.regex.Pattern.compile("on (\\d+) of (\\d+) held-out").matcher(all.out);
        assertTrue(count.find(), all.out);
        assertEquals(count.group(2), count.group(1), "capped at the size of the held-out set");
        assertTrue(Integer.parseInt(count.group(1)) > 40, all.out);
        assertTrue(all.out.contains("+-"), "the standard error of the winner prediction is printed: " + all.out);
        // it writes nothing
        assertFalse(Files.exists(run.resolve(".proxies.lock")));
    }

    @Test
    void theProxiesStepNeedsTheDataOfTheRunAndACheckpointOfIt() throws Exception {
        Path run = shared.resolve("proxies-errors");
        assertEquals(0, train(run).code);
        assertEquals(2, gen0(() -> false, "proxies", run.toString()).code, "--data is needed");
        Path other = Files.createDirectory(shared.resolve("other-data"));
        SyntheticShards.write(other, "samples-s9-0000.safetensors", 9, 0, 20, 8, 5);
        Result wrongData = gen0(() -> false, "proxies", run.toString(), "--data", other.toString());
        assertEquals(1, wrongData.code, wrongData.err);
        assertTrue(wrongData.err.contains("not the data of this run"), wrongData.err);
        Path stranger = shared.resolve("proxies-stranger");
        assertEquals(0, train(stranger, "--seed", "6").code);
        Result wrongNet = gen0(() -> false, "proxies", run.toString(), "--data", data.toString(), "--checkpoint",
                candidate(stranger).toString());
        assertEquals(1, wrongNet.code, wrongNet.err);
        assertEquals(1, gen0(() -> false, "proxies", shared.resolve("no-run").toString(), "--data", data.toString())
                .code);
    }

    @Test
    void usageAndDataErrors() throws Exception {
        assertEquals(2, gen0(() -> false, "frobnicate").code);
        assertEquals(2, gen0(() -> false, "run", data.toString()).code, "no run directory");
        assertEquals(2, train(shared.resolve("u1"), "--steps", "2").code, "steps must exceed the warm-up by 2");
        assertEquals(2, train(shared.resolve("u2"), "--preset", "huge").code);
        assertEquals(2, train(shared.resolve("u3"), "--holdout", "1.5").code);
        assertEquals(2, train(shared.resolve("u4"), "--checkpoint-steps", "0").code);
        Path empty = Files.createDirectory(shared.resolve("empty"));
        assertEquals(1, gen0(() -> false, "run", empty.toString(), shared.resolve("u5").toString()).code);
        // a split that leaves nothing held out
        Result tiny = train(shared.resolve("u6"), "--holdout", "0.0001");
        assertEquals(1, tiny.code, tiny.err);
        assertTrue(tiny.err.contains("held-out"), tiny.err);
        assertEquals(1, gen0(() -> false, "monitor-report", shared.resolve("no-such-run").toString()).code);
    }

    /**
     * {@code --preset large} builds the 64x6 network of docs/network.md ("Presets and parameter counts"), also for the 5-row board; the
     * LARGE 5-row run (docs/training.md, "Five-row LARGE net") trained its Gen0 with it.
     */
    @Test
    void thePresetLargeBuildsTheSixtyFourBySixNetworkAlsoOnFiveRows() throws Exception {
        Gen0.Config five = new Gen0.Config("large", 1, 10, 4, 2, 1e-3, 2, 1, true, 0.1, "x", 1, "classic1", "gpu",
                "equal", "5");
        sternhalma.nn.NetworkConfig net = five.network();
        assertEquals(64, net.channels(), "channels of LARGE");
        assertEquals(6, net.blocks(), "residual blocks of LARGE");
        assertEquals(2, net.players());
        assertEquals(sternhalma.nn.PlaneSet.CLASSIC1, net.planes());
        assertEquals(java.util.Set.of(sternhalma.engine.RuleSet.TriangleRows.FIVE), net.rows());
        assertTrue(five.json().contains("\"preset\":\"large\""), five.json());
        assertTrue(five.json().contains("\"rows\":5"), five.json());
        // end to end on the 4-row synthetic shards: the command line accepts the preset and writes a 64x6 candidate
        Path run = shared.resolve("large");
        Result r = train(run, "--preset", "large", "--steps", "4", "--checkpoint-steps", "2");
        assertEquals(0, r.code, r.err);
        sternhalma.nn.NetworkConfig written = Parameters.load(candidate(run)).config();
        assertEquals(64, written.channels(), "channels of the written candidate");
        assertEquals(6, written.blocks(), "blocks of the written candidate");
        // an unknown preset names all three
        Result huge = train(shared.resolve("huge"), "--preset", "huge");
        assertEquals(2, huge.code, huge.err);
        assertTrue(huge.err.contains("small, large or tiny"), huge.err);
    }

    @Test
    void theWholeRunEtaTextFollowsTheRate() {
        assertEquals("whole run: nothing left to train", Gen0.wholeRun(0, 5, "x"));
        assertEquals("whole run: 100 steps left, ETA unknown (no rate yet)", Gen0.wholeRun(100, 0, "x"));
        assertEquals("whole run: 100 steps left, ETA 40s at 2.50 steps/s (this run)", Gen0.wholeRun(100, 2.5, "this run"));
        assertEquals("whole run: 3600 steps left, ETA 2h00m00s at 0.50 steps/s (y)", Gen0.wholeRun(3600, 0.5, "y"));
        assertEquals("38s", Gen0.duration(38));
        assertEquals("1m29s", Gen0.duration(89));
        assertEquals("2h03m10s", Gen0.duration(7390));
    }

    @Test
    void theRunConfigurationDoesNotContainCadencesButTheDataFingerprint() throws Exception {
        String json = Files.readString(reference().resolve("run.json"));
        assertFalse(json.contains("checkpoint") || json.contains("threads") || json.contains("monitor"), json);
        assertTrue(json.contains("\"data_sha256\""), json);
        assertNotEquals(Gen0.dataFingerprint(Gen0.dataFiles(data)), Gen0.sha256Hex(new byte[0]));
    }

    /**
     * E11: a run with `--planes classic1` records the plane set in run.json (older run.json files have no such key and stay
     * valid), trains a network with the classical planes and priors, resumes to the bits of an uninterrupted run, reports
     * the same proxies in its own process, and cannot be resumed as a plain run.
     */
    @Test
    void aClassicalRunRecordsItsPlanesResumesAndReportsTheSameProxies() throws Exception {
        Path full = shared.resolve("classic-full");
        Result r = train(full, "--planes", "classic1");
        assertEquals(0, r.code, r.err);
        assertTrue(Files.readString(full.resolve("run.json")).contains("\"planes\":\"classic1\""));
        assertFalse(Files.readString(reference().resolve("run.json")).contains("planes"), "plain run.json is unchanged");
        Parameters candidate = Parameters.load(candidate(full));
        assertEquals(sternhalma.nn.PlaneSet.CLASSIC1, candidate.config().planes());
        assertTrue(candidate.tensors().containsKey(sternhalma.nn.ResidualPriors.BETA));
        assertNotEquals(sternhalma.nn.ResidualPriors.INITIAL_BETA, candidate.get(sternhalma.nn.ResidualPriors.BETA).data()[0],
                "the scalar was trained");
        // an abort in the middle and a resume give the same network
        Path resumed = shared.resolve("classic-resumed");
        Result stopped = train(resumed, stopAfter(5), base("--planes", "classic1"));
        assertEquals(130, stopped.code, stopped.err);
        assertEquals(0, train(resumed, "--planes", "classic1").code);
        assertSameNetwork(candidate(full), candidate(resumed));
        // the proxies step reads the planes of the network file for the held-out samples
        String loopLine = r.err.lines().filter(l -> l.startsWith("gen 0 proxies at step 12 ")).findFirst().orElseThrow();
        Result proxies = gen0(() -> false, "proxies", full.toString(), "--data", data.toString(), "--samples", "40",
                "--threads", "1");
        assertEquals(0, proxies.code, proxies.err);
        java.util.regex.Matcher loop = java.util.regex.Pattern.compile("policy KL (\\S+), top-1 (\\S+)%, value loss (\\S+)")
                .matcher(loopLine);
        java.util.regex.Matcher step = java.util.regex.Pattern.compile("policy KL (\\S+), top-1 (\\S+)%, value loss (\\S+)")
                .matcher(proxies.out);
        assertTrue(loop.find() && step.find(), loopLine + " / " + proxies.out);
        for (int g = 1; g <= 3; g++) {
            assertEquals(loop.group(g), step.group(g));
        }
        // the same directory with other planes is another configuration
        Result other = train(full, "--planes", "plain");
        assertEquals(1, other.code, other.err);
        assertEquals(2, train(shared.resolve("bad-planes"), "--planes", "classic2").code);
    }
}
