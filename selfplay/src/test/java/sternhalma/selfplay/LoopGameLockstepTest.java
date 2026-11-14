package sternhalma.selfplay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sternhalma.nn.NetworkConfig;
import sternhalma.nn.Networks;
import sternhalma.nn.Parameters;
import sternhalma.nn.PlaneSet;
import sternhalma.nn.PooledEvaluator;
import sternhalma.tornado.GpuEvaluators;

/**
 * The self-play with games in flight that take their evaluations from one {@link PooledEvaluator} (docs/training.md, "As
 * implemented (block G1)"), with a CPU forward pass as the backend, so that the tests run without a device: a game is the
 * same whatever the number of games in flight, the compute permits, the cohorts and the thread timing; the job writes the same
 * shards as the CPU path (but for the key {@code evaluator}); the end of a generation refills and does not idle; a stop
 * discards the games in flight and the restart gives the same shards; a resume with the other evaluator keeps the shards.
 */
class LoopGameLockstepTest {

    private static final Parameters NET = Parameters.uniform(NetworkConfig.tiny(2).withPlanes(PlaneSet.CLASSIC1), 21, 0.5f);

    private static LoopGame.Settings settings() {
        return new LoopGame.Settings(5, 8, 1, 1.0, 0.25, 0.3, 1.0, 8, 4, 10, 1, Adjudication.Config.PRODUCTION);
    }

    /** A device with a CPU backend; the Vector API pass gives a position the same bits in any batch. */
    private static LoopGamesJob.Device device(int computeThreads, int cohorts, int capacity) {
        return new LoopGamesJob.Device(() -> {
            PooledEvaluator service = new PooledEvaluator(0); // the job runs the games on virtual threads
            PooledEvaluator.Model model = service.add(Networks.fastest(NET), cohorts, capacity);
            return new GpuEvaluators.Session(service, model);
        }, "cpu-backed test device", computeThreads);
    }

    private static void assertSameGame(GameRecord expected, GameRecord actual, String what) {
        assertEquals(expected.id(), actual.id(), what);
        assertArrayEquals(expected.moves(), actual.moves(), what + ": moves");
        assertArrayEquals(expected.scores(), actual.scores(), what + ": scores");
        assertEquals(expected.label(), actual.label(), what + ": label");
        assertEquals(expected.labelPly(), actual.labelPly(), what + ": label ply");
        assertArrayEquals(expected.labelValues(), actual.labelValues(), what + ": label values");
        assertEquals(expected.nodes(), actual.nodes(), what + ": nodes");
        GameRecord.Samples e = expected.samples();
        GameRecord.Samples a = actual.samples();
        assertArrayEquals(e.cells(), a.cells(), what + ": cells");
        assertArrayEquals(e.moveCounts(), a.moveCounts(), what + ": move counts");
        assertArrayEquals(e.moveTarget(), a.moveTarget(), what + ": policy targets");
        assertArrayEquals(e.played(), a.played(), what + ": played");
        assertArrayEquals(e.value(), a.value(), what + ": values");
        assertArrayEquals(e.flags(), a.flags(), what + ": flags");
    }

    /** {@code games} ids played by {@code inFlight} threads that take the next id when they finish one. */
    private static List<GameRecord> playPooled(int games, int inFlight, int permits, int cohorts) throws Exception {
        // permits 0: virtual threads, as the job runs them; else platform threads bounded by the permits
        try (PooledEvaluator service = new PooledEvaluator(permits);
                ExecutorService pool = permits == 0 ? Executors.newFixedThreadPool(inFlight, Thread.ofVirtual().factory())
                        : Executors.newFixedThreadPool(inFlight)) {
            PooledEvaluator.Model model = service.add(Networks.fastest(NET), cohorts, Math.max(1, inFlight));
            AtomicInteger next = new AtomicInteger();
            List<Future<List<GameRecord>>> futures = new ArrayList<>();
            for (int t = 0; t < inFlight; t++) {
                futures.add(pool.submit(() -> {
                    PooledEvaluator.Client client = model.newClient();
                    LoopGame game = new LoopGame(settings(), client);
                    List<GameRecord> mine = new ArrayList<>();
                    for (int id = next.getAndIncrement(); id < games; id = next.getAndIncrement()) {
                        client.begin();
                        try {
                            mine.add(game.play(id));
                        } finally {
                            client.end();
                        }
                    }
                    return mine;
                }));
            }
            GameRecord[] byId = new GameRecord[games];
            for (Future<List<GameRecord>> f : futures) {
                for (GameRecord r : f.get()) {
                    byId[r.id()] = r;
                }
            }
            return List.of(byId);
        }
    }

    /**
     * Games 0 to 13 on the CPU path and with 1, 7 and 14 games in flight, 1, 3 and 8 compute permits or virtual threads (0
     * permits), 1 and 2 cohorts: the
     * same moves, scores, labels and samples, bit for bit. A game's moves depend on its own seed and the network, never on
     * the batch composition or on which thread was quicker.
     */
    @Test
    void aGameDoesNotDependOnTheGamesInFlightThePermitsTheCohortsOrTheTiming() throws Exception {
        int games = 14;
        List<GameRecord> expected = new ArrayList<>();
        LoopGame cpu = new LoopGame(settings(), Networks.fastest(NET));
        for (int id = 0; id < games; id++) {
            expected.add(cpu.play(id));
        }
        assertTrue(expected.stream().mapToInt(r -> r.moves().length).sum() > 300, "the games are not trivial");
        int[][] shapes = {{1, 1, 1}, {7, 3, 2}, {14, 1, 2}, {14, 3, 1}, {7, 8, 2}, {7, 0, 2}, {14, 0, 1}, {1, 0, 1}};
        for (int[] shape : shapes) {
            List<GameRecord> got = assertTimeoutPreemptively(Duration.ofMinutes(3),
                    () -> playPooled(games, shape[0], shape[1], shape[2]));
            for (int id = 0; id < games; id++) {
                assertSameGame(expected.get(id), got.get(id), "game " + id + " with " + shape[0] + " in flight, "
                        + shape[1] + " permits (0: virtual threads), " + shape[2] + " cohorts");
            }
        }
    }

    /**
     * The visit counts of a search with the pooled network equal those of the sequential search with the same CPU network
     * on fixed positions (batch-1 simulations: no virtual loss, so the lockstep changes nothing), also while other games
     * hold the device.
     */
    @Test
    void theVisitCountsEqualThoseOfTheSequentialSearchOnFixedPositions() throws Exception {
        sternhalma.search.MctsOptions options = settings().options().withNoise(0);
        List<sternhalma.engine.Position> positions = MctsBench.positions(12);
        List<sternhalma.search.MctsResult> expected = new ArrayList<>();
        for (int i = 0; i < positions.size(); i++) {
            expected.add(new sternhalma.search.Mcts(Networks.fastest(NET), options, 100 + i).search(positions.get(i)));
        }
        try (PooledEvaluator service = new PooledEvaluator(3); ExecutorService pool = Executors.newFixedThreadPool(6)) {
            PooledEvaluator.Model model = service.add(Networks.fastest(NET), 2, 8);
            List<Future<sternhalma.search.MctsResult>> futures = new ArrayList<>();
            for (int i = 0; i < positions.size(); i++) {
                int at = i;
                PooledEvaluator.Client client = model.newClient();
                futures.add(pool.submit(() -> {
                    client.begin();
                    try {
                        return new sternhalma.search.Mcts(client, options, 100 + at).search(positions.get(at));
                    } finally {
                        client.end();
                    }
                }));
            }
            for (int i = 0; i < positions.size(); i++) {
                sternhalma.search.MctsResult got = futures.get(i).get(120, java.util.concurrent.TimeUnit.SECONDS);
                assertArrayEquals(expected.get(i).visits(), got.visits(), "visits of position " + i);
                assertArrayEquals(expected.get(i).values(), got.values(), "values of position " + i);
                assertEquals(expected.get(i).move(), got.move());
            }
        }
    }

    // ---- the job ----

    private record Run(int code, String err, int done) {
    }

    /** The device of the last {@link #job} that had one (to ask whether a stop aborted it). */
    private static volatile LoopGamesJob.Device lastDevice;

    private static Run job(Path dir, int games, int shardGames, int threads, LoopGamesJob.Device device, Loop.StopHook hook)
            throws Exception {
        lastDevice = device;
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream stream = new PrintStream(err, true, StandardCharsets.UTF_8);
        LoopGamesJob job = new LoopGamesJob(dir, 1, settings(), "sha-of-the-network", NET, games, shardGames, threads, stream,
                hook, device);
        job.label = "gen 1 selfplay";
        job.progressSeconds = 0.05;
        try {
            int code = job.run();
            return new Run(code, err.toString(StandardCharsets.UTF_8), job.doneItems);
        } finally {
            if (device != null) {
                device.close();
            }
        }
    }

    /** The tensors of every shard of a directory (the CPU times differ between runs), by file name. */
    private static Map<String, Object> shards(Path dir, boolean withMetadata) throws IOException {
        Map<String, Object> out = new java.util.TreeMap<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path f : s.filter(p -> p.getFileName().toString().endsWith(".safetensors")).sorted().toList()) {
                var c = sternhalma.selfplay.SampleFile.read(f);
                for (var e : c.tensors().entrySet()) {
                    if (!e.getKey().equals("game_cpu_us")) {
                        out.put(f.getFileName() + ":" + e.getKey(), e.getValue().data());
                    }
                }
                Map<String, String> metadata = new java.util.TreeMap<>(c.metadata());
                if (!withMetadata) {
                    metadata.remove("evaluator");
                }
                out.put(f.getFileName() + ":metadata", metadata.toString());
            }
        }
        return out;
    }

    private static void assertSameShards(Path expected, Path actual) throws IOException {
        Map<String, Object> a = shards(expected, false);
        Map<String, Object> b = shards(actual, false);
        assertEquals(a.keySet(), b.keySet());
        for (String key : a.keySet()) {
            assertTrue(java.util.Objects.deepEquals(a.get(key), b.get(key)), "differs: " + key);
        }
    }

    private static String evaluatorOf(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            Path first = s.filter(p -> p.getFileName().toString().endsWith(".safetensors")).sorted().findFirst().orElseThrow();
            return sternhalma.selfplay.SampleFile.read(first).metadata().get("evaluator");
        }
    }

    /**
     * 11 games in shards of 4 with 5 games in flight (the tail has fewer games than the batch, and the shard borders do not
     * match the flights): the shards are those of the CPU path, and the metadata names the evaluator.
     */
    @Test
    void theJobWritesTheShardsOfTheCpuPath(@TempDir Path tmp) throws Exception {
        Run cpu = job(tmp.resolve("cpu"), 11, 4, 2, null, LoopFixture.NEVER);
        assertEquals(0, cpu.code(), cpu.err());
        Run gpu = assertTimeoutPreemptively(Duration.ofMinutes(3),
                () -> job(tmp.resolve("gpu"), 11, 4, 5, device(2, 2, 8), LoopFixture.NEVER));
        assertEquals(0, gpu.code(), gpu.err());
        assertEquals(11, gpu.done());
        assertSameShards(tmp.resolve("cpu"), tmp.resolve("gpu"));
        assertEquals("cpu", evaluatorOf(tmp.resolve("cpu")));
        assertEquals("gpu", evaluatorOf(tmp.resolve("gpu")));
        assertTrue(gpu.err().contains("batch "), "the progress line shows the mean batch: " + gpu.err());
    }

    /**
     * The tail: with 3 games left of 12 and 8 in flight, the last games are alone on the device (the pool threads of finished
     * games hold no slot, and a batch does not wait for them). A hang here is a batch that waits for games that are gone.
     */
    @Test
    void theEndOfAGenerationDoesNotWaitForGamesThatAreNotThere(@TempDir Path tmp) throws Exception {
        long hour = Duration.ofHours(1).toNanos();
        LoopGamesJob.Device device = new LoopGamesJob.Device(() -> {
            PooledEvaluator service = new PooledEvaluator(4, hour, hour); // no timer can rescue a batch
            PooledEvaluator.Model model = service.add(Networks.fastest(NET), 2, 8);
            return new GpuEvaluators.Session(service, model);
        }, "no timers", 4);
        Run run = assertTimeoutPreemptively(Duration.ofSeconds(90), () -> job(tmp.resolve("tail"), 12, 3, 8, device, LoopFixture.NEVER));
        assertEquals(0, run.code(), run.err());
        assertEquals(12, run.done());
    }

    /**
     * A stop after 3 finished games discards the games in flight (no shard of an unfinished game, no temporary file), ends
     * with 130, and the restart writes the shards of an uninterrupted run.
     */
    @Test
    void aStopDiscardsTheGamesInFlightAndTheRestartGivesTheSameShards(@TempDir Path tmp) throws Exception {
        Run reference = job(tmp.resolve("reference"), 12, 3, 2, null, LoopFixture.NEVER);
        assertEquals(0, reference.code(), reference.err());
        Path dir = tmp.resolve("stopped");
        Run first = assertTimeoutPreemptively(Duration.ofMinutes(3),
                () -> job(dir, 12, 3, 8, device(2, 2, 8), (phase, done) -> done >= 3));
        assertEquals(130, first.code(), first.err());
        assertTrue(first.err().contains("aborted"), first.err());
        assertTrue(lastDevice.aborted(), "a stop aborts the evaluator: the games in flight are discarded, not finished");
        try (Stream<Path> s = Files.list(dir)) {
            assertTrue(s.noneMatch(f -> f.getFileName().toString().contains(".tmp.")), "no half-written file");
        }
        Run second = assertTimeoutPreemptively(Duration.ofMinutes(3),
                () -> job(dir, 12, 3, 8, device(2, 2, 8), LoopFixture.NEVER));
        assertEquals(0, second.code(), second.err());
        assertSameShards(tmp.resolve("reference"), dir);
    }

    /**
     * A stop while the games are in flight aborts the evaluator and the job ends at once: the backend is slow (40 ms a batch), so
     * the eight games would need minutes; after the stop the backend computes at most the batch that was on the device. Without
     * the abort the shutdown waits for the games to finish (the shutdown hook gives them 60 s, a test cannot).
     */
    @Test
    void aStopAbortsTheGamesAlreadyInFlightAndTheShutdownDoesNotWaitForThem(@TempDir Path tmp) throws Exception {
        CpuDevices slow = new CpuDevices(40);
        LoopGamesJob.Device device = LoopGamesJob.Device.ofPool(() -> slow.open(List.of(NET), 2, 8), "slow", 4);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        LoopGamesJob job = new LoopGamesJob(tmp, 1, settings(), "sha-of-the-network", NET, 8, 8, 8,
                new PrintStream(err, true, StandardCharsets.UTF_8), LoopFixture.NEVER, device);
        job.progressSeconds = 0.05;
        Thread stopper = new Thread(() -> {
            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while (slow.batches.get() < 10 && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    return;
                }
            }
            job.requestStop();
        });
        stopper.start();
        long t0 = System.nanoTime();
        int code;
        try {
            code = assertTimeoutPreemptively(Duration.ofSeconds(60), job::run);
        } finally {
            device.close();
        }
        double seconds = (System.nanoTime() - t0) / 1e9;
        assertEquals(130, code, err.toString(StandardCharsets.UTF_8));
        assertTrue(device.aborted());
        assertTrue(slow.batches.get() < 60, "the games stopped computing: " + slow.batches.get() + " batches");
        assertTrue(seconds < 20, "no waiting for the games: " + seconds + " s");
        try (Stream<Path> s = Files.list(tmp)) {
            assertTrue(s.noneMatch(f -> f.getFileName().toString().endsWith(".safetensors")), "no shard of unfinished games");
        }
    }

    /**
     * The last games of a generation on the CPU in a tail pool: the shards equal those of the CPU path, and the tail games did
     * run in the tail pool (the device sees fewer positions than with every game on it).
     */
    @Test
    void theTailGamesOnTheCpuGiveTheShardsOfTheCpuPath(@TempDir Path tmp) throws Exception {
        Run cpu = job(tmp.resolve("cpu"), 11, 4, 2, null, LoopFixture.NEVER);
        assertEquals(0, cpu.code(), cpu.err());
        CpuDevices devices = new CpuDevices(0);
        LoopGamesJob.Device device = LoopGamesJob.Device.ofPool(() -> devices.open(List.of(NET), 2, 8), "tail", 3);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        LoopGamesJob tailed = new LoopGamesJob(tmp.resolve("tail"), 1, settings(), "sha-of-the-network", NET, 11, 4, 8,
                new PrintStream(err, true, StandardCharsets.UTF_8), LoopFixture.NEVER, device);
        tailed.progressSeconds = 0.05;
        tailed.tail(3, 1);
        int code;
        try {
            code = assertTimeoutPreemptively(Duration.ofMinutes(3), tailed::run);
        } finally {
            device.close();
        }
        assertEquals(0, code, err.toString(StandardCharsets.UTF_8));
        assertEquals(11, tailed.doneItems);
        assertSameShards(tmp.resolve("cpu"), tmp.resolve("tail"));
        // all games on the device for comparison: more batches
        CpuDevices all = new CpuDevices(0);
        Run every = assertTimeoutPreemptively(Duration.ofMinutes(3), () -> job(tmp.resolve("all"), 11, 4, 8,
                LoopGamesJob.Device.ofPool(() -> all.open(List.of(NET), 2, 8), "all", 3), LoopFixture.NEVER));
        assertEquals(0, every.code(), every.err());
        assertTrue(devices.batches.get() < all.batches.get(),
                "the tail games did not use the device: " + devices.batches.get() + " against " + all.batches.get() + " batches");
        assertSameShards(tmp.resolve("all"), tmp.resolve("tail"));
    }

    /**
     * Another evaluator on a resume is allowed: the shards of the CPU path are kept (and the log says so), the rest comes from
     * the device; the settings are the same, so the run is not refused.
     */
    @Test
    void aResumeWithTheOtherEvaluatorKeepsTheShardsAndSaysSo(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("mixed");
        Run first = job(dir, 8, 2, 2, null, (phase, done) -> done >= 3);
        assertEquals(130, first.code(), first.err());
        long before;
        try (Stream<Path> s = Files.list(dir)) {
            before = s.filter(p -> p.getFileName().toString().endsWith(".safetensors")).count();
        }
        assertTrue(before >= 1 && before < 4, "some shards, not all: " + before);
        Run second = assertTimeoutPreemptively(Duration.ofMinutes(3), () -> job(dir, 8, 2, 4, device(2, 2, 4), LoopFixture.NEVER));
        assertEquals(0, second.code(), second.err());
        assertTrue(second.err().contains("was written by the cpu evaluator, this run uses the gpu one: kept"), second.err());
        assertFalse(second.err().contains("another"), second.err());
        // and a third run, with either, has nothing to do
        Run third = job(dir, 8, 2, 2, null, LoopFixture.NEVER);
        assertEquals(0, third.code(), third.err());
        assertEquals(0, third.done());
        assertTrue(third.err().contains("was written by the gpu evaluator"), third.err());
    }

    @Test
    void theOptionsAreValidatedAndNotPartOfTheConfiguration() {
        EvaluatorOptions defaults = EvaluatorOptions.of(Map.of());
        assertFalse(defaults.gpu());
        assertEquals(256, defaults.gamesInFlight());
        assertEquals(2, defaults.cohorts());
        EvaluatorOptions gpu = EvaluatorOptions.of(Map.of("evaluator", "gpu", "games-in-flight", "128", "eval-cohorts", "1"));
        assertTrue(gpu.gpu());
        assertEquals(128, gpu.gamesInFlight());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> EvaluatorOptions.of(Map.of("evaluator", "tpu")));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> EvaluatorOptions.of(Map.of("games-in-flight", "0")));
        // run.json does not name the evaluator: the hash of a run is the same with either
        Map<String, String> base = Map.of("seed", "3");
        Map<String, String> withGpu = Map.of("seed", "3", "evaluator", "gpu", "games-in-flight", "64", "eval-cohorts", "1");
        assertEquals(LoopConfig.of(base, "x", "", 0).sha256(), LoopConfig.of(withGpu, "x", "", 0).sha256());
        assertFalse(LoopConfig.of(withGpu, "x", "", 0).json().contains("evaluator"));
    }
}
