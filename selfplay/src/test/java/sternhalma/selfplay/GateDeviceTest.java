package sternhalma.selfplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sternhalma.nn.NetworkConfig;
import sternhalma.nn.Networks;
import sternhalma.nn.Parameters;
import sternhalma.nn.PlaneSet;
import sternhalma.nn.PooledEvaluator;
import sternhalma.selfplay.MctsMatch.PairLine;

/**
 * The corners of the device path of the matches and of the games that the larger tests do not pin down (docs/training.md, "As
 * implemented (block G2)", mutants): what happens to an aborted pair, the CPU time of a virtual thread, the boundaries of the
 * tails and the wiring of a stop in the loop.
 */
class GateDeviceTest {

    private static final Parameters CAND = Parameters.uniform(NetworkConfig.tiny(2).withPlanes(PlaneSet.CLASSIC1), 21, 0.5f);
    private static final Parameters BEST = Parameters.uniform(NetworkConfig.tiny(2).withPlanes(PlaneSet.CLASSIC1), 22, 0.5f);
    private static final String PLAYER = "mcts:sims=4,batch=1,cpuct=1.0,noise=0.25,temp=1,race=1";
    private static final long SEED = 3001;

    private static LoopGame.Settings settings() {
        return new LoopGame.Settings(5, 8, 1, 1.0, 0.25, 0.3, 1.0, 8, 4, 10, 1, Adjudication.Config.PRODUCTION);
    }

    private static PrintStream sink(ByteArrayOutputStream bytes) {
        return new PrintStream(bytes, true, StandardCharsets.UTF_8);
    }

    private static LoopGamesJob.Device device(CpuDevices devices, int inFlight) {
        return LoopGamesJob.Device.ofPool(() -> devices.open(List.of(CAND, BEST), 2, inFlight), "cpu-backed", 3);
    }

    private static Supplier<Job.Worker<PairLine>> devicePlay(LoopGamesJob.Device device) {
        return Gate.pairWorkers(SEED, 4, NetPlayers.onDevice(PLAYER, () -> device.pool().models().get(0)),
                NetPlayers.onDevice(PLAYER, () -> device.pool().models().get(1)));
    }

    private static Gate.PairJob job(Path dir, ByteArrayOutputStream err, int pairs, int shard, Supplier<Job.Worker<PairLine>> play) {
        Gate.PairJob job = new Gate.PairJob(dir, "vs-best", "gate", "identity", pairs, shard, 4, sink(err), play, LoopFixture.NEVER,
                "gate");
        job.progressSeconds = 0;
        return job;
    }

    /**
     * Pairs that end with {@link PooledEvaluator.Aborted} are discarded, not failed: no "item failed", no shard, no error exit
     * (the abort of the evaluator is what a stop does, and a stop is not an error).
     */
    @Test
    void anAbortedPairIsDiscardedAndNotPropagated(@TempDir Path tmp) throws Exception {
        CpuDevices devices = new CpuDevices(0);
        LoopGamesJob.Device device = device(devices, 4);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Gate.PairJob job = job(tmp, err, 4, 2, devicePlay(device));
        job.device(2, device::abort);
        device.abort();
        int code;
        try {
            code = assertTimeoutPreemptively(Duration.ofSeconds(60), job::run);
        } finally {
            device.close();
        }
        String log = err.toString(StandardCharsets.UTF_8);
        assertFalse(log.contains("item failed"), log);
        assertFalse(log.contains("Aborted"), log);
        assertTrue(code != Job.EXIT_ERROR, "an aborted pair is not an error, code " + code + "\n" + log);
        assertEquals(0, LoopFixture.count(tmp, ".*\\.jsonl"), "no shard of a discarded pair");
    }

    /** The pairs of a device are virtual threads, which have no CPU time: the lines say 0, never a negative time. */
    @Test
    void thePairsOfADeviceRecordNoNegativeCpuTime(@TempDir Path tmp) throws Exception {
        CpuDevices devices = new CpuDevices(0);
        LoopGamesJob.Device device = device(devices, 4);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Gate.PairJob job = job(tmp, err, 4, 2, devicePlay(device));
        job.device(2, device::abort);
        try {
            assertEquals(0, assertTimeoutPreemptively(Duration.ofSeconds(120), job::run), err.toString(StandardCharsets.UTF_8));
        } finally {
            device.close();
        }
        assertEquals(4, job.results().size());
        for (PairLine l : job.results()) {
            assertTrue(l.subjectCpuNanos() >= 0 && l.opponentCpuNanos() >= 0, "pair " + l.pair() + ": " + l.fields());
        }
        assertTrue(job.cpuNanos.get() >= 0, "the sum of the CPU times: " + job.cpuNanos.get());
    }

    /** A pair line with a negative time keeps all other fields and has only the times clamped (the unit of the guard). */
    @Test
    void aNegativeCpuTimeIsClampedAndTheRestOfTheLineIsKept(@TempDir Path tmp) throws Exception {
        java.util.function.IntFunction<PairLine> given = p -> new PairLine(p, 0.5, 1, 0, 1, 77, -1, 11, 12, 13, -1, 14, 15);
        Gate.PairJob job = job(tmp, new ByteArrayOutputStream(), 4, 4, () -> given::apply);
        assertEquals(0, job.run());
        PairLine l = job.results().get(3);
        assertEquals(new PairLine(3, 0.5, 1, 0, 1, 77, 0, 11, 12, 13, 0, 14, 15).fields(), l.fields());
        assertEquals(0, job.cpuNanos.get());
        java.util.function.IntFunction<PairLine> ok = p -> new PairLine(p, 0.5, 1, 0, 1, 77, 5, 11, 12, 13, 7, 14, 15);
        Gate.PairJob kept = job(tmp.resolve("kept"), new ByteArrayOutputStream(), 4, 4, () -> ok::apply);
        assertEquals(0, kept.run());
        assertEquals(12, kept.cpuNanos.get() / 4, "positive times are summed as they are");
    }

    /** The tail of a match plays with the CPU workers and the main pool with the device workers, and a missing CPU player is refused. */
    @Test
    void theTailOfAMatchUsesTheTailWorkersAndNotTheMainOnes(@TempDir Path tmp) throws Exception {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Supplier<Job.Worker<PairLine>> main = () -> pair -> LoopFixture.pairLine(pair, 1.0);
        Supplier<Job.Worker<PairLine>> tail = () -> pair -> LoopFixture.pairLine(pair, 0.0);
        Gate.PairJob job = job(tmp, err, 6, 3, main);
        job.tail(2, 1, tail);
        assertEquals(2, job.tailItems());
        assertEquals(0, job.run());
        List<Double> scores = job.results().stream().map(PairLine::score).toList();
        assertEquals(List.of(1.0, 1.0, 1.0, 1.0, 0.0, 0.0), scores);
        ThreadPoolExecutor pool = (ThreadPoolExecutor) job.newTailPool();
        try {
            assertEquals(1, pool.getCorePoolSize());
        } finally {
            pool.shutdown();
        }
        assertEquals(0, job("x", tmp, 2, tail, 0, 0).tailItems(), "no pairs, no tail");
        assertThrows(IllegalArgumentException.class, () -> job.tail(1, 0, tail));
        assertThrows(IllegalArgumentException.class, () -> job.tail(1, 1, null));
        assertThrows(IllegalArgumentException.class, () -> job.tail(-1, 1, tail));
    }

    private static Gate.PairJob job(String name, Path dir, int pairs, Supplier<Job.Worker<PairLine>> tail, int tailPairs,
            int threads) {
        Gate.PairJob job = job(dir.resolve(name), new ByteArrayOutputStream(), pairs, 2, tail);
        job.tail(tailPairs, threads, tail);
        return job;
    }

    /** Tail of games: the boundary of the sizes, the threads of the tail pool, and no tail without a device. */
    @Test
    void theTailOfGamesHasTheExactSizeAndThreads(@TempDir Path tmp) throws Exception {
        CpuDevices devices = new CpuDevices(0);
        LoopGamesJob.Device device = LoopGamesJob.Device.ofPool(() -> devices.open(List.of(CAND), 2, 8), "tail", 3);
        LoopGamesJob games = new LoopGamesJob(tmp.resolve("a"), 1, settings(), "sha", CAND, 6, 3, 8,
                sink(new ByteArrayOutputStream()), LoopFixture.NEVER, device);
        games.tail(3, 2);
        assertEquals(3, games.tailItems());
        ThreadPoolExecutor pool = (ThreadPoolExecutor) games.newTailPool();
        try {
            assertEquals(2, pool.getCorePoolSize());
        } finally {
            pool.shutdown();
        }
        games.tail(0, 0);
        assertEquals(0, games.tailItems());
        assertThrows(IllegalArgumentException.class, () -> games.tail(1, 0));
        assertThrows(IllegalArgumentException.class, () -> games.tail(-1, 1));
        games.tail(1, 1);
        assertEquals(1, games.tailItems());
        LoopGamesJob onCpu = new LoopGamesJob(tmp.resolve("b"), 1, settings(), "sha", CAND, 6, 3, 2,
                sink(new ByteArrayOutputStream()), LoopFixture.NEVER, null);
        onCpu.tail(3, 1);
        assertEquals(0, onCpu.tailItems(), "without a device there is no tail");
        device.close();
    }

    /**
     * Where the boundary is in a run: 4 games with a tail of 4 never touch the device, with a tail of 3 the one game that is left
     * does (so the tail is neither one game too short nor one too long).
     */
    @Test
    void aTailAsLongAsTheJobLeavesTheDeviceUntouchedAndOneLessDoesNot(@TempDir Path tmp) throws Exception {
        for (int tail : new int[] {4, 3}) {
            CpuDevices devices = new CpuDevices(0);
            LoopGamesJob.Device device = LoopGamesJob.Device.ofPool(() -> devices.open(List.of(CAND), 2, 8), "tail", 3);
            LoopGamesJob games = new LoopGamesJob(tmp.resolve("t" + tail), 1, settings(), "sha", CAND, 4, 2, 8,
                    sink(new ByteArrayOutputStream()), LoopFixture.NEVER, device);
            games.progressSeconds = 0;
            games.tail(tail, 1);
            try {
                assertEquals(0, assertTimeoutPreemptively(Duration.ofMinutes(3), games::run));
            } finally {
                device.close();
            }
            assertEquals(tail == 4, devices.batches.get() == 0, "tail " + tail + ": " + devices.batches.get() + " batches");
        }
    }

    // ---- the loop ----

    /** A stop in a match of the loop aborts the device (the pairs in flight are discarded), and the loop ends with 130. */
    @Test
    void aStopInAMatchOfTheLoopAbortsTheDevice(@TempDir Path tmp) throws Exception {
        Path init = LoopFixture.initNetwork(tmp.resolve("init.safetensors"), 3);
        CpuDevices devices = new CpuDevices(0);
        Loop.Devices before = Loop.devices;
        Loop.devices = devices;
        LoopFixture.Result r;
        try {
            List<String> args = new java.util.ArrayList<>(List.of("--init", init.toString()));
            args.addAll(LoopFixture.options("--generations", "1", "--gate-pairs", "8", "--pair-shard", "2", "--evaluator", "gpu",
                    "--games-in-flight", "4", "--threads", "3", "--promote-z", "0"));
            r = LoopFixture.runOrDump(Duration.ofMinutes(2),
                    () -> LoopFixture.loop(tmp.resolve("run"), LoopFixture.stopIn("gate", 1), args));
        } finally {
            Loop.devices = before;
        }
        assertEquals(130, r.code(), r.err());
        assertTrue(r.err().contains("gen 1 gate: evaluator gpu"), r.err());
        // the gate is the last service opened before the stop: a stop in the gate aborted it
        assertTrue(devices.services.get(devices.services.size() - 1).isAborted(), "the device of the gate was aborted: " + r.err());
        assertFalse(devices.services.get(0).isAborted(), "the device of the first match was not");
    }

    /**
     * The options of the tails: the thread count is capped so that one compute thread stays for the games on the device, the
     * games in flight of a match are capped by its pairs, and the baseline (a net against a rung) is a match on the device too.
     */
    @Test
    void theLoopLogsTheTailsAndTheFlightsOfTheMatches(@TempDir Path tmp) throws Exception {
        Path init = LoopFixture.initNetwork(tmp.resolve("init.safetensors"), 3);
        CpuDevices devices = new CpuDevices(0);
        Loop.Devices before = Loop.devices;
        Loop.devices = devices;
        LoopFixture.Result r;
        try {
            List<String> args = new java.util.ArrayList<>(List.of("--init", init.toString()));
            args.addAll(LoopFixture.options("--generations", "1", "--gate-pairs", "5", "--pair-shard", "2", "--baseline-pairs", "3",
                    "--evaluator", "gpu", "--games-in-flight", "64", "--threads", "2", "--tail-threads", "4", "--tail-pairs", "9",
                    "--promote-z", "0"));
            r = LoopFixture.runOrDump(Duration.ofMinutes(2), () -> LoopFixture.loop(tmp.resolve("run"), LoopFixture.NEVER, args));
        } finally {
            Loop.devices = before;
        }
        assertEquals(0, r.code(), r.err());
        assertTrue(r.err().contains("gen 1 gate: evaluator gpu (cpu-backed test device), 5 pairs in flight in 2 cohorts, 2 compute "
                + "threads, the last 5 pairs on 1 CPU threads"), r.err());
        assertTrue(r.err().contains("baseline of generation 1: evaluator gpu (cpu-backed test device), 3 pairs in flight"), r.err());
    }

    /** The matches close their devices, and a match on the device records the CPU time of the process (its threads have none). */
    @Test
    void theMatchesCloseTheirDevicesAndRecordTheProcessCpuTime(@TempDir Path tmp) throws Exception {
        Path init = LoopFixture.initNetwork(tmp.resolve("init.safetensors"), 3);
        CpuDevices devices = new CpuDevices(0);
        Loop.Devices before = Loop.devices;
        Loop.devices = devices;
        LoopFixture.Result r;
        try {
            List<String> args = new java.util.ArrayList<>(List.of("--init", init.toString()));
            args.addAll(LoopFixture.options("--generations", "1", "--gate-pairs", "4", "--pair-shard", "2", "--baseline-pairs", "2",
                    "--evaluator", "gpu", "--games-in-flight", "4", "--threads", "3", "--promote-z", "0"));
            r = LoopFixture.runOrDump(Duration.ofMinutes(2), () -> LoopFixture.loop(tmp.resolve("run"), LoopFixture.NEVER, args));
        } finally {
            Loop.devices = before;
        }
        assertEquals(0, r.code(), r.err());
        // the CPU time of a match on the device is the process time, since the virtual threads have none of their own
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"kind\"\\s*:\\s*\"vs-best\"[^\\n]*\"cpu_ns\"\\s*:\\s*(\\d+)")
                .matcher(Files.readString(tmp.resolve("run").resolve("metrics.jsonl")));
        assertTrue(m.find(), "a cost line of the gate");
        assertTrue(Long.parseLong(m.group(1)) > 0, "the gate cost line has the process CPU time: " + m.group(1));
        // every device is closed when its match is over
        assertTrue(devices.services.size() >= 4, "self-play, gate, baseline and strength: " + devices.services.size());
        for (PooledEvaluator service : devices.services) {
            assertThrows(IllegalStateException.class, () -> service.add(Networks.fastest(CAND), 1, 1), "the service is closed");
        }
    }

    /** {@code --match-evaluator cpu} keeps the matches on the CPU while the self-play uses the device. */
    @Test
    void aCpuMatchEvaluatorKeepsTheMatchesOffTheDevice(@TempDir Path tmp) throws Exception {
        Path init = LoopFixture.initNetwork(tmp.resolve("init.safetensors"), 3);
        CpuDevices devices = new CpuDevices(0);
        Loop.Devices before = Loop.devices;
        Loop.devices = devices;
        LoopFixture.Result r;
        try {
            List<String> args = new java.util.ArrayList<>(List.of("--init", init.toString()));
            args.addAll(LoopFixture.options("--generations", "1", "--gate-pairs", "4", "--pair-shard", "2", "--baseline-pairs", "2",
                    "--evaluator", "gpu", "--match-evaluator", "cpu", "--games-in-flight", "4", "--threads", "3", "--promote-z", "0"));
            r = LoopFixture.runOrDump(Duration.ofMinutes(2), () -> LoopFixture.loop(tmp.resolve("run"), LoopFixture.NEVER, args));
        } finally {
            Loop.devices = before;
        }
        assertEquals(0, r.code(), r.err());
        assertTrue(r.err().contains("gen 1 selfplay: evaluator gpu"), r.err());
        assertFalse(r.err().contains("gate: evaluator gpu"), r.err());
        assertFalse(r.err().contains("strength vs 100 nodes: evaluator gpu"), r.err());
        assertEquals(1, devices.opened.get(), "only the self-play opened a device");
    }

    /** {@code --match-evaluator gpu} puts the matches on the device although the self-play is on the CPU. */
    @Test
    void aDeviceMatchEvaluatorPutsTheMatchesOnTheDeviceWithCpuSelfPlay(@TempDir Path tmp) throws Exception {
        Path init = LoopFixture.initNetwork(tmp.resolve("init.safetensors"), 3);
        CpuDevices devices = new CpuDevices(0);
        Loop.Devices before = Loop.devices;
        Loop.devices = devices;
        LoopFixture.Result r;
        try {
            List<String> args = new java.util.ArrayList<>(List.of("--init", init.toString()));
            args.addAll(LoopFixture.options("--generations", "1", "--gate-pairs", "4", "--pair-shard", "2", "--baseline-pairs", "2",
                    "--match-evaluator", "gpu", "--games-in-flight", "4", "--threads", "3", "--promote-z", "0"));
            r = LoopFixture.runOrDump(Duration.ofMinutes(2), () -> LoopFixture.loop(tmp.resolve("run"), LoopFixture.NEVER, args));
        } finally {
            Loop.devices = before;
        }
        assertEquals(0, r.code(), r.err());
        assertTrue(r.err().contains("gen 1 gate: evaluator gpu"), r.err());
        assertFalse(r.err().contains("gen 1 selfplay: evaluator gpu"), r.err());
    }

    /** The carriers of the scheduler of the games are the properties of the pool (the first value in a JVM wins, so only the call is checked). */
    @Test
    void aVirtualPoolSetsTheCarriersOfTheScheduler() {
        Job.virtualPool(2, 3).close();
        assertEquals("3", System.getProperty("jdk.virtualThreadScheduler.parallelism"));
        assertEquals("3", System.getProperty("jdk.virtualThreadScheduler.maxPoolSize"));
    }

    @Test
    void theOptionsOfTheTailsAreChecked() {
        assertThrows(IllegalArgumentException.class, () -> new EvaluatorOptions(true, 8, 2, -1, 0, 1, "auto"));
        assertThrows(IllegalArgumentException.class, () -> new EvaluatorOptions(true, 8, 2, 0, -1, 1, "auto"));
        assertThrows(IllegalArgumentException.class, () -> new EvaluatorOptions(true, 8, 2, 0, 0, 0, "auto"));
        assertThrows(IllegalArgumentException.class, () -> new EvaluatorOptions(true, 8, 2, 0, 0, 65, "auto"));
        assertEquals(64, new EvaluatorOptions(true, 8, 2, 0, 0, 64, "auto").tailThreads());
        assertEquals(0, new EvaluatorOptions(true, 8, 2, 0, 0, 1, "auto").tailGames());
        assertThrows(IllegalArgumentException.class, () -> new EvaluatorOptions(true, 8, 2, 0, 0, 1, "tpu"));
    }

    /** {@code --match-evaluator}: cpu and gpu are what they say, auto follows {@code --evaluator}. */
    @Test
    void theMatchEvaluatorFollowsTheOptionOrTheEvaluator() {
        for (String evaluator : new String[] {"cpu", "gpu"}) {
            for (String matches : new String[] {"cpu", "gpu", "auto"}) {
                boolean expected = matches.equals("gpu") || matches.equals("auto") && evaluator.equals("gpu");
                assertEquals(expected, EvaluatorOptions.of(java.util.Map.of("evaluator", evaluator, "match-evaluator", matches))
                        .matchesOnDevice(), evaluator + "/" + matches);
            }
        }
        // the default follows the evaluator
        assertTrue(EvaluatorOptions.of(java.util.Map.of("evaluator", "gpu")).matchesOnDevice());
        assertFalse(EvaluatorOptions.of(java.util.Map.of()).matchesOnDevice());
        assertThrows(IllegalArgumentException.class, () -> EvaluatorOptions.of(java.util.Map.of("match-evaluator", "tpu")));
    }
}
