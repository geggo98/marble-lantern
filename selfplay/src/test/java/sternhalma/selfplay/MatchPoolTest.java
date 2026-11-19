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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sternhalma.engine.RuleSet;
import sternhalma.nn.NetworkConfig;
import sternhalma.nn.Networks;
import sternhalma.nn.Parameters;
import sternhalma.nn.PlaneSet;
import sternhalma.nn.PooledEvaluator;
import sternhalma.search.Player;
import sternhalma.selfplay.MctsMatch.PairLine;

/**
 * The matches of the loop on the pooled evaluator (docs/training.md, "As implemented (block G2)"), with CPU backends so that the
 * tests need no device: a pair played with both networks on one device thread equals the pair played on the CPU, whatever the
 * number of pairs in flight and cohorts; a job with a device and a tail writes the shards of the CPU job; a stop discards the
 * pairs in flight at once; the loop with {@code --evaluator gpu} decides like the loop on the CPU.
 */
class MatchPoolTest {

    private static final Parameters CAND = Parameters.uniform(NetworkConfig.tiny(2).withPlanes(PlaneSet.CLASSIC1), 21, 0.5f);
    private static final Parameters BEST = Parameters.uniform(NetworkConfig.tiny(2).withPlanes(PlaneSet.CLASSIC1), 22, 0.5f);
    /** The evaluation player of a loop, with noise and sampling so that the games differ from pair to pair. */
    private static final String PLAYER = "mcts:sims=6,batch=1,cpuct=1.0,noise=0.25,temp=1,race=1";
    private static final long SEED = 3001;

    private static Map<String, Object> withoutCpu(PairLine line) {
        Map<String, Object> f = new TreeMap<>(line.fields());
        f.remove("s_cpu_ns");
        f.remove("o_cpu_ns");
        return f;
    }

    private static List<Map<String, Object>> cpuPairs(int pairs) throws Exception {
        Job.Worker<PairLine> cpu = Gate.pairWorkers(SEED, 4, NetPlayers.factory(PLAYER, CAND), NetPlayers.factory(PLAYER, BEST))
                .get();
        List<Map<String, Object>> expected = new ArrayList<>();
        for (int p = 0; p < pairs; p++) {
            expected.add(withoutCpu(cpu.run(p)));
        }
        return expected;
    }

    /** The pairs 0 to {@code pairs - 1} with both networks in one pooled evaluator, {@code inFlight} pairs at a time. */
    private static List<Map<String, Object>> devicePairs(int pairs, int inFlight, int carriers, int cohorts) throws Exception {
        try (PooledEvaluator service = new PooledEvaluator(0); ExecutorService pool = Job.virtualPool(inFlight, carriers)) {
            PooledEvaluator.Model candidate = service.add(Networks.fastest(CAND), cohorts, inFlight);
            PooledEvaluator.Model best = service.add(Networks.fastest(BEST), cohorts, inFlight);
            Supplier<Job.Worker<PairLine>> play = Gate.pairWorkers(SEED, 4, NetPlayers.onDevice(PLAYER, () -> candidate),
                    NetPlayers.onDevice(PLAYER, () -> best));
            ThreadLocal<Job.Worker<PairLine>> local = ThreadLocal.withInitial(play);
            List<Future<PairLine>> futures = new ArrayList<>();
            for (int p = 0; p < pairs; p++) {
                int pair = p;
                futures.add(pool.submit(() -> local.get().run(pair)));
            }
            List<Map<String, Object>> got = new ArrayList<>();
            for (Future<PairLine> f : futures) {
                got.add(withoutCpu(f.get()));
            }
            return got;
        }
    }

    /**
     * Pairs of the candidate against the best network, both on the device: the same pair lines as on the CPU for 1, 3 and 8
     * pairs in flight, 1 and 2 cohorts and 1 and 4 carriers: a pair depends on its seeds and the two networks, not on what else
     * is in the batch.
     */
    @Test
    void aPairWithBothNetworksOnTheDeviceEqualsThePairOnTheCpu() throws Exception {
        int pairs = 8;
        List<Map<String, Object>> expected = cpuPairs(pairs);
        long plies = expected.stream().mapToLong(m -> ((Number) m.get("plies")).longValue()).sum();
        assertTrue(plies > 200, "the pairs are not trivial: " + plies + " plies");
        long searched = expected.stream().mapToLong(m -> ((Number) m.get("s_sims")).longValue()).sum();
        assertTrue(searched > 100, "and the subject searches: " + searched + " simulations");
        for (int[] shape : new int[][] {{1, 1, 1}, {3, 4, 2}, {8, 1, 1}, {8, 4, 2}}) {
            List<Map<String, Object>> got = assertTimeoutPreemptively(Duration.ofMinutes(3),
                    () -> devicePairs(pairs, shape[0], shape[1], shape[2]));
            assertEquals(expected, got, shape[0] + " pairs in flight, " + shape[1] + " carriers, " + shape[2] + " cohorts");
        }
    }

    // ---- the job ----

    private static Gate.PairJob job(Path dir, int pairs, int shard, int threads, Supplier<Job.Worker<PairLine>> play,
            Loop.StopHook hook) {
        Gate.PairJob job = new Gate.PairJob(dir, "vs-best", "gate", "identity", pairs, shard, threads,
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8), play, hook, "gate");
        job.progressSeconds = 0;
        return job;
    }

    private static Supplier<Job.Worker<PairLine>> cpuPlay() {
        return Gate.pairWorkers(SEED, 4, NetPlayers.factory(PLAYER, CAND), NetPlayers.factory(PLAYER, BEST));
    }

    private static LoopGamesJob.Device device(CpuDevices devices, int inFlight, int cohorts, int carriers) {
        return LoopGamesJob.Device.ofPool(() -> devices.open(List.of(CAND, BEST), cohorts, inFlight), "cpu-backed", carriers);
    }

    private static Supplier<Job.Worker<PairLine>> devicePlay(LoopGamesJob.Device device) {
        return Gate.pairWorkers(SEED, 4, NetPlayers.onDevice(PLAYER, () -> device.pool().models().get(0)),
                NetPlayers.onDevice(PLAYER, () -> device.pool().models().get(1)));
    }

    private static List<Map<String, Object>> results(Gate.PairJob job) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PairLine l : job.results()) {
            out.add(withoutCpu(l));
        }
        return out;
    }

    /**
     * 10 pairs in shards of 3, 6 in flight on the device and the last 3 on the CPU: the shards hold the pairs of the CPU job in
     * pair order, the tail pairs ran on platform threads of the tail pool and the others on the device.
     */
    @Test
    void aMatchOnTheDeviceWithATailWritesTheShardsOfTheCpuJob(@TempDir Path tmp) throws Exception {
        Gate.PairJob reference = job(tmp.resolve("cpu"), 10, 3, 2, cpuPlay(), LoopFixture.NEVER);
        assertEquals(0, reference.run());
        CpuDevices devices = new CpuDevices(0);
        LoopGamesJob.Device device = device(devices, 6, 2, 3);
        Map<Integer, String> where = new ConcurrentHashMap<>();
        Supplier<Job.Worker<PairLine>> onDevice = devicePlay(device);
        Supplier<Job.Worker<PairLine>> main = () -> {
            Job.Worker<PairLine> w = onDevice.get();
            return pair -> {
                where.put(pair, "device:" + Thread.currentThread().getName());
                return w.run(pair);
            };
        };
        Supplier<Job.Worker<PairLine>> cpu = cpuPlay();
        Supplier<Job.Worker<PairLine>> tail = () -> {
            Job.Worker<PairLine> w = cpu.get();
            return pair -> {
                where.put(pair, "cpu:" + Thread.currentThread().getName());
                return w.run(pair);
            };
        };
        Gate.PairJob onPool = job(tmp.resolve("gpu"), 10, 3, 6, main, LoopFixture.NEVER);
        onPool.device(3, device::abort);
        onPool.tail(3, 1, tail);
        int code;
        try {
            code = assertTimeoutPreemptively(Duration.ofMinutes(3), onPool::run);
        } finally {
            device.close();
        }
        assertEquals(0, code);
        assertEquals(results(reference), results(onPool));
        for (int pair = 0; pair < 10; pair++) {
            String thread = where.get(pair);
            if (pair >= 7) {
                assertTrue(thread.startsWith("cpu:tail-pair-"), "pair " + pair + " is in the tail: " + thread);
            } else {
                assertTrue(thread.startsWith("device:game-"), "pair " + pair + " is on the device: " + thread);
            }
        }
        assertTrue(devices.batches.get() > 0, "the device computed");
        assertEquals(1, devices.opened.get());
        assertEquals(2, devices.lastNetworks, "both networks share one service");
    }

    /**
     * A stop while pairs are in flight: the device aborts, the job ends at once with 130 (the backend is slow: the pairs would
     * need minutes), no shard of an unfinished pair, no temporary file, and the restart gives the pairs of an uninterrupted job.
     */
    @Test
    void aStopDiscardsThePairsInFlightAtOnce(@TempDir Path tmp) throws Exception {
        Gate.PairJob reference = job(tmp.resolve("cpu"), 8, 2, 2, cpuPlay(), LoopFixture.NEVER);
        assertEquals(0, reference.run());
        CpuDevices slow = new CpuDevices(40);
        LoopGamesJob.Device device = device(slow, 8, 2, 4);
        Gate.PairJob running = job(tmp.resolve("stopped"), 8, 2, 8, devicePlay(device), LoopFixture.NEVER);
        running.device(4, device::abort);
        Thread stopper = new Thread(() -> {
            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while (slow.batches.get() < 10 && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    return;
                }
            }
            running.requestStop();
        });
        stopper.start();
        long t0 = System.nanoTime();
        int code;
        try {
            code = assertTimeoutPreemptively(Duration.ofSeconds(60), running::run);
        } finally {
            device.close();
        }
        double seconds = (System.nanoTime() - t0) / 1e9;
        int atStop = slow.batches.get();
        assertEquals(130, code);
        assertTrue(device.aborted(), "a stop aborts the evaluator");
        assertTrue(atStop < 60, "the backend stopped soon after the stop: " + atStop + " batches");
        assertTrue(seconds < 20, "the stop does not wait for the pairs: " + seconds + " s");
        try (Stream<Path> s = Files.list(tmp.resolve("stopped"))) {
            assertTrue(s.noneMatch(f -> f.getFileName().toString().contains(".tmp.")), "no half-written file");
        }
        Gate.PairJob again = job(tmp.resolve("stopped"), 8, 2, 2, cpuPlay(), LoopFixture.NEVER);
        assertEquals(0, again.run());
        assertEquals(results(reference), results(again));
    }

    // ---- the players ----

    @Test
    void onlyAnMctsPlayerWithoutNetAndWithBatchOneRunsOnTheDevice() {
        assertTrue(NetPlayers.runsOnDevice("mcts:sims=5,batch=1"));
        assertTrue(NetPlayers.runsOnDevice("mcts"));
        assertFalse(NetPlayers.runsOnDevice("mcts:sims=5,batch=4"));
        assertFalse(NetPlayers.runsOnDevice("mcts:net=x.safetensors,sims=5"));
        assertFalse(NetPlayers.runsOnDevice("raw"));
        assertThrows(IllegalArgumentException.class, () -> NetPlayers.onDevice("raw", () -> null));
        assertThrows(IllegalArgumentException.class, () -> NetPlayers.onDevice("mcts:batch=4", () -> null));
    }

    /**
     * A device player is active in its cohort only while it chooses a move: between its moves the client is not begun (so a
     * batch does not wait for the games that think elsewhere), and it begins and ends once per move.
     */
    @Test
    void aDevicePlayerBeginsAndEndsOncePerMove() throws Exception {
        try (PooledEvaluator service = new PooledEvaluator(0)) {
            PooledEvaluator.Model model = service.add(Networks.fastest(CAND), 1, 4);
            Player player = NetPlayers.onDevice(PLAYER, () -> model).apply(5);
            sternhalma.engine.Position start = StrengthEstimate.opening(RuleSet.defaults(2), SEED, 0, 4);
            assertTrue(player instanceof NetPlayers.Scoped);
            int move = player.choose(start);
            assertTrue(move >= 0);
            // the client is not begun any more: begin() works again (it throws "begin() twice" if end() was missed)
            NetPlayers.Scoped scoped = (NetPlayers.Scoped) player;
            scoped.client().begin();
            scoped.client().end();
            assertEquals(player.name(), NetPlayers.unwrap(player).name());
            assertTrue(NetPlayers.unwrap(player) instanceof sternhalma.search.MctsPlayer);
        }
    }

    // ---- the loop ----

    private static List<String> loopOptions(Path init, String... more) {
        List<String> args = new ArrayList<>(List.of("--init", init.toString()));
        args.addAll(LoopFixture.options("--generations", "2", "--gate-pairs", "8", "--pair-shard", "3", "--baseline-pairs", "4",
                "--strength-pairs", "4", "--promote-z", "0"));
        args.addAll(List.of(more));
        return args;
    }

    private static Map<String, Object> comparable(Path run) throws Exception {
        Map<String, Object> m = new TreeMap<>(LoopTest.results(run));
        m.replaceAll((k, v) -> k.endsWith(":metadata") ? v.toString().replaceAll("evaluator=(cpu|gpu)(, )?", "") : v);
        return m;
    }

    /**
     * The whole loop, self-play, gate, baseline and strength, with {@code --evaluator gpu} on a CPU-backed device with a tail of
     * games and of pairs: the same samples, candidate, pairs and decisions as the loop on the CPU (the neutrality check in
     * small; on the real device the floats differ in the last bits and the statistics only agree within that noise).
     */
    @Test
    void theLoopOnTheDeviceDecidesLikeTheLoopOnTheCpu(@TempDir Path tmp) throws Exception {
        Path init = LoopFixture.initNetwork(tmp.resolve("init.safetensors"), 3);
        LoopFixture.Result cpu = LoopFixture.loop(tmp.resolve("cpu"), LoopFixture.NEVER, loopOptions(init));
        assertEquals(0, cpu.code(), cpu.err());
        CpuDevices devices = new CpuDevices(0);
        Loop.Devices before = Loop.devices;
        Loop.devices = devices;
        LoopFixture.Result gpu;
        try {
            gpu = LoopFixture.runOrDump(Duration.ofMinutes(2), () -> LoopFixture.loop(tmp.resolve("gpu"), LoopFixture.NEVER,
                    loopOptions(init, "--evaluator", "gpu", "--games-in-flight", "4", "--eval-cohorts", "2", "--tail-games", "1",
                            "--tail-pairs", "2", "--tail-threads", "1", "--threads", "3")));
        } finally {
            Loop.devices = before;
        }
        assertEquals(0, gpu.code(), gpu.err());
        Map<String, Object> expected = comparable(tmp.resolve("cpu"));
        Map<String, Object> actual = comparable(tmp.resolve("gpu"));
        assertEquals(expected.keySet(), actual.keySet(), "the same files");
        for (String key : expected.keySet()) {
            assertTrue(java.util.Objects.deepEquals(expected.get(key), actual.get(key)), "differs: " + key);
        }
        assertTrue(gpu.err().contains("gen 1 gate: evaluator gpu (cpu-backed test device)"), gpu.err());
        assertTrue(gpu.err().contains("pairs in flight in 2 cohorts, 3 compute threads, the last 2 pairs on 1 CPU threads"), gpu.err());
        assertTrue(gpu.err().contains("strength vs 100 nodes: evaluator gpu"), gpu.err());
        assertTrue(gpu.err().contains("the last 1 games on 1 CPU threads"), gpu.err());
        assertTrue(devices.opened.get() >= 3, "self-play, gate and strength opened a service: " + devices.opened.get());
        assertFalse(Files.readString(tmp.resolve("gpu").resolve("run.json")).contains("evaluator"));
        assertFalse(Files.readString(tmp.resolve("gpu").resolve("run.json")).contains("tail"));
        LoopTest.assertNoTemporaries(tmp.resolve("gpu"));
    }

    /** A player that the device cannot run (a batch above 1) keeps the matches on the CPU, with a message. */
    @Test
    void aPlayerThatCannotRunOnTheDeviceKeepsTheMatchesOnTheCpu(@TempDir Path tmp) throws Exception {
        Path init = LoopFixture.initNetwork(tmp.resolve("init.safetensors"), 3);
        CpuDevices devices = new CpuDevices(0);
        Loop.Devices before = Loop.devices;
        Loop.devices = devices;
        LoopFixture.Result r;
        try {
            r = LoopFixture.runOrDump(Duration.ofMinutes(2), () -> LoopFixture.loop(tmp.resolve("run"), LoopFixture.NEVER,
                    loopOptions(init, "--generations", "1", "--mcts-batch", "2", "--evaluator", "gpu", "--games-in-flight", "4")));
        } finally {
            Loop.devices = before;
        }
        // the self-play still takes the device (batch 2 fits the capacity of 4 games in 2 cohorts); the matches do not
        assertTrue(r.err().contains("cannot run on the device"), r.err());
        assertFalse(r.err().contains("gate: evaluator gpu"), r.err());
    }
}
