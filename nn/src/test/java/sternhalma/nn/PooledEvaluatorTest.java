package sternhalma.nn;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import sternhalma.engine.Position;

/**
 * {@link PooledEvaluator} with CPU backends (no device needed): every game gets the result of its own position, whatever
 * the batch, the slot, the cohort and the model; a batch does not wait for games that are not running; the compute permits
 * bound the CPU use; failures and close do not leave a game waiting. The positions are seeded playouts, the reference is the
 * backend called directly, one position at a time (the CPU {@link Network} gives the same bits for a position in any
 * batch, which is the property that the device network is tested for in nn-tornado).
 */
class PooledEvaluatorTest {

    private static final NetworkConfig CONFIG = NetworkConfig.tiny(2);

    private static Parameters weights(long seed) {
        return Parameters.uniform(CONFIG, seed, (float) Math.sqrt(6.0 / (7 * CONFIG.channels())));
    }

    private static float[] encode(Position position) {
        float[] input = new float[CONFIG.inputSize()];
        Encoder.encode(CONFIG.planes(), position, input, 0);
        return input;
    }

    /** The outputs of {@code network} for one position, called directly. */
    private static float[][] direct(ForwardPass network, float[] input) {
        float[] policy = new float[Encoder.POLICY_SIZE];
        float[] values = new float[2];
        network.forward(input, 1, policy, values);
        return new float[][] {policy, values};
    }

    private static float[][] viaClient(PooledEvaluator.Client client, float[] input) {
        float[] policy = new float[Encoder.POLICY_SIZE];
        float[] values = new float[2];
        client.forward(input, 1, policy, values);
        return new float[][] {policy, values};
    }

    /** A backend that records every batch it computes. */
    private static class Recording implements ForwardPass {
        final ForwardPass inner;
        final List<Integer> sizes = Collections.synchronizedList(new ArrayList<>());
        final AtomicReference<Thread> thread = new AtomicReference<>();
        volatile boolean sameThread = true;

        Recording(ForwardPass inner) {
            this.inner = inner;
        }

        @Override
        public NetworkConfig config() {
            return inner.config();
        }

        @Override
        public boolean threadSafe() {
            return false;
        }

        @Override
        public void forward(float[] input, int batch, float[] policyLogits, float[] values) {
            if (!thread.compareAndSet(null, Thread.currentThread()) && thread.get() != Thread.currentThread()) {
                sameThread = false;
            }
            sizes.add(batch);
            inner.forward(input, batch, policyLogits, values);
        }
    }

    /** Runs {@code games} client threads, each asking for its positions in turn; returns every result. */
    private static List<List<float[][]>> play(PooledEvaluator.Model model, List<List<Position>> perGame) throws Exception {
        int games = perGame.size();
        List<PooledEvaluator.Client> clients = new ArrayList<>();
        for (int i = 0; i < games; i++) {
            clients.add(model.newClient());
        }
        try (ExecutorService pool = Executors.newFixedThreadPool(games)) {
            List<Future<List<float[][]>>> futures = new ArrayList<>();
            for (int i = 0; i < games; i++) {
                PooledEvaluator.Client client = clients.get(i);
                List<Position> mine = perGame.get(i);
                futures.add(pool.submit(() -> {
                    client.begin();
                    try {
                        List<float[][]> out = new ArrayList<>();
                        for (Position p : mine) {
                            out.add(viaClient(client, encode(p)));
                        }
                        return out;
                    } finally {
                        client.end();
                    }
                }));
            }
            List<List<float[][]>> results = new ArrayList<>();
            for (Future<List<float[][]>> f : futures) {
                results.add(f.get());
            }
            return results;
        }
    }

    private static List<List<Position>> split(int games, int each, long seed) {
        List<Position> all = Playouts.positions(2, 12, 90, seed);
        List<List<Position>> result = new ArrayList<>();
        for (int g = 0; g < games; g++) {
            List<Position> mine = new ArrayList<>();
            for (int i = 0; i < each; i++) {
                mine.add(all.get((g * 31 + i * 17) % all.size()));
            }
            result.add(mine);
        }
        return result;
    }

    @Test
    void everyGameGetsTheResultOfItsOwnPositionForAnyNumberOfGamesAndCohorts() throws Exception {
        Network network = new Network(weights(1));
        for (int cohorts : new int[] {1, 2, 3}) {
            for (int games : new int[] {1, 7, 40}) {
                Recording backend = new Recording(network);
                try (PooledEvaluator service = new PooledEvaluator(4)) {
                    PooledEvaluator.Model model = service.add(backend, cohorts, 64);
                    List<List<Position>> perGame = split(games, 6, games);
                    List<List<float[][]>> got = assertTimeoutPreemptively(Duration.ofSeconds(60),
                            () -> play(model, perGame));
                    for (int g = 0; g < games; g++) {
                        for (int i = 0; i < 6; i++) {
                            float[][] expected = direct(network, encode(perGame.get(g).get(i)));
                            assertArrayEquals(expected[0], got.get(g).get(i)[0],
                                    "logits of game " + g + " position " + i + " (" + games + " games, " + cohorts
                                            + " cohorts)");
                            assertArrayEquals(expected[1], got.get(g).get(i)[1], "values of game " + g);
                        }
                    }
                    assertTrue(backend.sameThread, "the backend is called by one thread only");
                    int total = backend.sizes.stream().mapToInt(Integer::intValue).sum();
                    assertEquals(games * 6, total, "every position is computed once");
                    assertTrue(backend.sizes.stream().allMatch(n -> n >= 1 && n <= Math.max(1, games)));
                }
            }
        }
    }

    @Test
    void positionsOfOneBatchAreNotSwappedOrStale() throws Exception {
        // Two games that ask for very different positions alternately: a swapped slot or a result of the batch before
        // would show as the other game's logits.
        Network network = new Network(weights(2));
        List<Position> all = Playouts.positions(2, 4, 100, 3);
        Position a = all.get(5);
        Position b = all.get(all.size() - 3);
        float[][] ea = direct(network, encode(a));
        float[][] eb = direct(network, encode(b));
        assertFalse(Arrays.equals(ea[0], eb[0]), "the two positions must differ for this test to mean anything");
        try (PooledEvaluator service = new PooledEvaluator(2)) {
            PooledEvaluator.Model model = service.add(network, 1, 8);
            List<List<float[][]>> got = assertTimeoutPreemptively(Duration.ofSeconds(60),
                    () -> play(model, List.of(List.of(a, b, a, b), List.of(b, a, b, a))));
            for (int i = 0; i < 4; i++) {
                assertArrayEquals(i % 2 == 0 ? ea[0] : eb[0], got.get(0).get(i)[0], "game 0, call " + i);
                assertArrayEquals(i % 2 == 0 ? eb[0] : ea[0], got.get(1).get(i)[0], "game 1, call " + i);
                assertArrayEquals(i % 2 == 0 ? ea[1] : eb[1], got.get(0).get(i)[1], "game 0 value, call " + i);
            }
        }
    }

    @Test
    void oneDeviceThreadServesTwoNetworksAndEachGameGetsItsOwn() throws Exception {
        Network first = new Network(weights(10));
        Network second = new Network(weights(11));
        Recording r1 = new Recording(first);
        Recording r2 = new Recording(second);
        try (PooledEvaluator service = new PooledEvaluator(4)) {
            PooledEvaluator.Model m1 = service.add(r1, 2, 32);
            PooledEvaluator.Model m2 = service.add(r2, 2, 32);
            List<List<Position>> perGame = split(10, 5, 5);
            ExecutorService both = Executors.newFixedThreadPool(2);
            try {
                Future<List<List<float[][]>>> f1 = both.submit(() -> play(m1, perGame));
                Future<List<List<float[][]>>> f2 = both.submit(() -> play(m2, perGame));
                List<List<float[][]>> got1 = f1.get(60, java.util.concurrent.TimeUnit.SECONDS);
                List<List<float[][]>> got2 = f2.get(60, java.util.concurrent.TimeUnit.SECONDS);
                for (int g = 0; g < 10; g++) {
                    for (int i = 0; i < 5; i++) {
                        float[] in = encode(perGame.get(g).get(i));
                        assertArrayEquals(direct(first, in)[0], got1.get(g).get(i)[0], "model 1");
                        assertArrayEquals(direct(second, in)[0], got2.get(g).get(i)[0], "model 2");
                    }
                }
            } finally {
                both.shutdownNow();
            }
            assertTrue(r1.sameThread && r2.sameThread);
            assertTrue(r1.thread.get() == r2.thread.get(), "one thread computes both networks");
        }
    }

    /**
     * The batch is complete when the games that run have published; it does not wait for the quiet time or the longest wait
     * (an hour here: the test would time out), neither for 7 games nor, after 3 of them have ended, for the 4 that are left.
     */
    @Test
    void aBatchDoesNotWaitForGamesThatAreNotRunning() throws Exception {
        Network network = new Network(weights(4));
        Recording backend = new Recording(network);
        long hour = Duration.ofHours(1).toNanos();
        try (PooledEvaluator service = new PooledEvaluator(8, hour, hour)) {
            PooledEvaluator.Model model = service.add(backend, 1, 16);
            List<Position> positions = Playouts.positions(2, 2, 40, 9);
            List<PooledEvaluator.Client> clients = new ArrayList<>();
            for (int i = 0; i < 7; i++) {
                clients.add(model.newClient());
                clients.get(i).begin();
            }
            CountDownLatch gate = new CountDownLatch(1);
            try (ExecutorService pool = Executors.newFixedThreadPool(7)) {
                List<Future<?>> futures = new ArrayList<>();
                for (int i = 0; i < 7; i++) {
                    PooledEvaluator.Client client = clients.get(i);
                    Position p = positions.get(i + 1);
                    int rounds = i < 3 ? 1 : 3;
                    futures.add(pool.submit(() -> {
                        gate.await();
                        for (int r = 0; r < rounds; r++) {
                            viaClient(client, encode(p));
                        }
                        client.end();
                        return null;
                    }));
                }
                gate.countDown();
                assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
                    for (Future<?> f : futures) {
                        f.get();
                    }
                });
            }
            assertEquals(List.of(7, 4, 4), backend.sizes, "7 games, then the 4 that are left, twice");
        }
    }

    /** A game that ends after the others have published completes their batch (hour timers: nothing else can). */
    @Test
    void aGameThatEndsCompletesTheBatchOfTheOthers() throws Exception {
        Network network = new Network(weights(16));
        long hour = Duration.ofHours(1).toNanos();
        try (PooledEvaluator service = new PooledEvaluator(8, hour, hour)) {
            PooledEvaluator.Model model = service.add(network, 1, 8);
            PooledEvaluator.Client waiting = model.newClient();
            PooledEvaluator.Client leaving = model.newClient();
            waiting.begin();
            leaving.begin();
            ExecutorService pool = Executors.newFixedThreadPool(1);
            try {
                Position p = Playouts.positions(2, 1, 5, 1).get(2);
                Future<?> f = pool.submit(() -> viaClient(waiting, encode(p)));
                Thread.sleep(200); // published, and waiting for the game that is still running
                assertFalse(f.isDone());
                leaving.end();
                assertTimeoutPreemptively(Duration.ofSeconds(30), () -> f.get());
            } finally {
                pool.shutdownNow();
                waiting.end();
            }
        }
    }

    /** A partial batch is taken when nothing arrives for the quiet time, even if more games are active. */
    @Test
    void anIdleDeviceTakesAPartialBatchAfterTheQuietTime() throws Exception {
        Network network = new Network(weights(5));
        Recording backend = new Recording(network);
        try (PooledEvaluator service = new PooledEvaluator(4, 2_000_000L, 5_000_000_000L)) {
            PooledEvaluator.Model model = service.add(backend, 1, 16);
            PooledEvaluator.Client busy = model.newClient();
            PooledEvaluator.Client waiting = model.newClient();
            busy.begin(); // active, but never publishes
            waiting.begin();
            float[][] got = assertTimeoutPreemptively(Duration.ofSeconds(30),
                    () -> viaClient(waiting, encode(Playouts.positions(2, 1, 5, 1).get(2))));
            assertEquals(List.of(1), backend.sizes);
            assertArrayEquals(direct(network, encode(Playouts.positions(2, 1, 5, 1).get(2)))[0], got[0]);
            waiting.end();
            busy.end();
        }
    }

    /** While cohort 0 is on the device, cohort 1 fills its batch and is computed right after. */
    @Test
    void theOtherCohortFillsWhileOneIsComputed() throws Exception {
        Network network = new Network(weights(6));
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Recording backend = new Recording(network) {
            @Override
            public void forward(float[] input, int batch, float[] policyLogits, float[] values) {
                if (sizes.isEmpty()) {
                    inside.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                super.forward(input, batch, policyLogits, values);
            }
        };
        long hour = Duration.ofHours(1).toNanos(); // only a full cohort is computed, so that the sizes are a fact
        try (PooledEvaluator service = new PooledEvaluator(8, hour, hour)) {
            PooledEvaluator.Model model = service.add(backend, 2, 16);
            List<Position> positions = Playouts.positions(2, 2, 40, 9);
            PooledEvaluator.Client a = model.newClient(); // cohort 0
            PooledEvaluator.Client b = model.newClient(); // cohort 1
            PooledEvaluator.Client c = model.newClient(); // cohort 0
            ExecutorService pool = Executors.newFixedThreadPool(3);
            try {
                a.begin();
                b.begin();
                c.begin();
                Future<?> fa = pool.submit(() -> viaClient(a, encode(positions.get(3))));
                Future<?> fc = pool.submit(() -> viaClient(c, encode(positions.get(4))));
                assertTrue(inside.await(30, java.util.concurrent.TimeUnit.SECONDS), "cohort 0 is on the device");
                Future<?> fb = pool.submit(() -> viaClient(b, encode(positions.get(5))));
                // b's batch is full while the device is busy; it must be computed right after the first one
                Thread.sleep(50);
                assertTrue(backend.sizes.isEmpty(), "the first batch is still running");
                release.countDown();
                fa.get(30, java.util.concurrent.TimeUnit.SECONDS);
                fb.get(30, java.util.concurrent.TimeUnit.SECONDS);
                fc.get(30, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(List.of(2, 1), backend.sizes);
            } finally {
                pool.shutdownNow();
                a.end();
                b.end();
                c.end();
            }
        }
    }

    @Test
    void thePermitsBoundTheGamesThatComputeAtTheSameTime() throws Exception {
        Network network = new Network(weights(7));
        int permits = 3;
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger most = new AtomicInteger();
        try (PooledEvaluator service = new PooledEvaluator(permits)) {
            PooledEvaluator.Model model = service.add(network, 2, 64);
            int games = 24;
            List<Position> positions = Playouts.positions(2, 2, 50, 2);
            try (ExecutorService pool = Executors.newFixedThreadPool(games)) {
                List<Future<?>> futures = new ArrayList<>();
                for (int g = 0; g < games; g++) {
                    PooledEvaluator.Client client = model.newClient();
                    Position p = positions.get(g % positions.size());
                    futures.add(pool.submit(() -> {
                        client.begin();
                        try {
                            for (int i = 0; i < 20; i++) {
                                int now = inside.incrementAndGet();
                                most.accumulateAndGet(now, Math::max);
                                Thread.sleep(1); // the game's own computing
                                inside.decrementAndGet();
                                viaClient(client, encode(p));
                            }
                        } finally {
                            client.end();
                        }
                        return null;
                    }));
                }
                assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
                    for (Future<?> f : futures) {
                        f.get();
                    }
                });
            }
        }
        assertTrue(most.get() <= permits, "at most " + permits + " games compute at once, saw " + most.get());
        assertTrue(most.get() >= 2, "and they do run in parallel, saw " + most.get());
    }

    @Test
    void aFailingBackendFailsTheGamesThatWaitAndEveryLaterCall() throws Exception {
        Network network = new Network(weights(8));
        ForwardPass broken = new Recording(network) {
            @Override
            public void forward(float[] input, int batch, float[] policyLogits, float[] values) {
                throw new IllegalStateException("device lost");
            }
        };
        PooledEvaluator service = new PooledEvaluator(2);
        try {
            PooledEvaluator.Model model = service.add(broken, 1, 4);
            PooledEvaluator.Client client = model.newClient();
            client.begin();
            Position p = Playouts.positions(2, 1, 5, 1).get(1);
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> viaClient(client, encode(p)));
            assertTrue(e.getMessage().contains("device lost"), e.getMessage());
            assertThrows(IllegalStateException.class, () -> viaClient(client, encode(p)));
            client.end();
        } finally {
            service.close();
        }
    }

    /** Without permits (computeThreads 0) the games are virtual threads, many more than carriers; the results are the same. */
    @Test
    void virtualThreadsNeedNoPermits() throws Exception {
        Network network = new Network(weights(15));
        int games = 64;
        List<List<Position>> perGame = split(games, 5, 77);
        try (PooledEvaluator service = new PooledEvaluator(0);
                ExecutorService pool = Executors.newFixedThreadPool(games, Thread.ofVirtual().factory())) {
            PooledEvaluator.Model model = service.add(network, 2, 64);
            List<Future<List<float[][]>>> futures = new ArrayList<>();
            for (int g = 0; g < games; g++) {
                PooledEvaluator.Client client = model.newClient();
                List<Position> mine = perGame.get(g);
                futures.add(pool.submit(() -> {
                    client.begin();
                    try {
                        List<float[][]> out = new ArrayList<>();
                        for (Position p : mine) {
                            out.add(viaClient(client, encode(p)));
                        }
                        return out;
                    } finally {
                        client.end();
                    }
                }));
            }
            assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
                for (int g = 0; g < games; g++) {
                    List<float[][]> got = futures.get(g).get();
                    for (int i = 0; i < 5; i++) {
                        assertArrayEquals(direct(network, encode(perGame.get(g).get(i)))[0], got.get(i)[0]);
                    }
                }
            });
        }
    }

    /** A stop request: games that wait for a batch or for a slot get an Aborted at once, later calls too, no failure. */
    @Test
    void abortEndsTheGamesThatWaitWithoutAFailure() throws Exception {
        Network network = new Network(weights(14));
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Recording backend = new Recording(network) {
            @Override
            public void forward(float[] input, int batch, float[] policyLogits, float[] values) {
                inside.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                super.forward(input, batch, policyLogits, values);
            }
        };
        try (PooledEvaluator service = new PooledEvaluator(8)) {
            PooledEvaluator.Model model = service.add(backend, 1, 8);
            List<Position> positions = Playouts.positions(2, 2, 40, 9);
            PooledEvaluator.Client onDevice = model.newClient();
            PooledEvaluator.Client queued = model.newClient();
            onDevice.begin();
            queued.begin();
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<?> first = pool.submit(() -> viaClient(onDevice, encode(positions.get(3))));
                assertTrue(inside.await(30, java.util.concurrent.TimeUnit.SECONDS));
                // the cohort is executing: this game waits for a slot
                Future<?> second = pool.submit(() -> viaClient(queued, encode(positions.get(4))));
                Thread.sleep(50);
                service.abort();
                release.countDown();
                for (Future<?> f : List.of(first, second)) {
                    java.util.concurrent.ExecutionException e = assertThrows(java.util.concurrent.ExecutionException.class,
                            () -> f.get(30, java.util.concurrent.TimeUnit.SECONDS));
                    assertTrue(e.getCause() instanceof PooledEvaluator.Aborted, String.valueOf(e.getCause()));
                }
                assertThrows(PooledEvaluator.Aborted.class,
                        () -> viaClient(onDevice, encode(positions.get(5))), "later calls too");
            } finally {
                pool.shutdownNow();
                onDevice.end();
                queued.end();
            }
            assertThrows(PooledEvaluator.Aborted.class, () -> model.newClient().begin());
        }
    }

    @Test
    void closeClosesTheBackendsOnTheDeviceThreadAndLaterCallsFail() {
        Network network = new Network(weights(9));
        AtomicReference<Thread> closedBy = new AtomicReference<>();
        AtomicReference<Thread> usedBy = new AtomicReference<>();
        class Closing extends Recording implements AutoCloseable {
            Closing() {
                super(network);
            }

            @Override
            public void forward(float[] input, int batch, float[] policyLogits, float[] values) {
                usedBy.set(Thread.currentThread());
                super.forward(input, batch, policyLogits, values);
            }

            @Override
            public void close() {
                closedBy.set(Thread.currentThread());
            }
        }
        PooledEvaluator service = new PooledEvaluator(2);
        PooledEvaluator.Model model = service.add(new Closing(), 1, 4);
        PooledEvaluator.Client client = model.newClient();
        client.begin();
        viaClient(client, encode(Playouts.positions(2, 1, 5, 1).get(1)));
        client.end();
        service.close();
        service.close(); // twice: nothing
        assertEquals(usedBy.get(), closedBy.get(), "closed by the thread that computed");
        assertThrows(IllegalStateException.class, () -> client.begin());
        assertThrows(IllegalStateException.class, () -> service.add(network, 1, 4));
    }

    @Test
    void misuseIsRefused() {
        Network network = new Network(weights(12));
        try (PooledEvaluator service = new PooledEvaluator(2)) {
            PooledEvaluator.Model model = service.add(network, 1, 4);
            PooledEvaluator.Client client = model.newClient();
            float[] in = new float[CONFIG.inputSize()];
            float[] policy = new float[Encoder.POLICY_SIZE];
            float[] values = new float[2];
            assertThrows(IllegalStateException.class, () -> client.forward(in, 1, policy, values), "before begin");
            assertThrows(IllegalStateException.class, client::end, "end without begin");
            client.begin();
            assertThrows(IllegalStateException.class, client::begin, "begin twice");
            assertThrows(IllegalArgumentException.class, () -> client.forward(in, 0, policy, values));
            assertThrows(IllegalArgumentException.class, () -> client.forward(new float[3 * CONFIG.inputSize()], 5, policy, values));
            assertThrows(IllegalArgumentException.class, () -> client.forward(in, 1, new float[3], values));
            client.end();
            assertThrows(IllegalArgumentException.class, () -> new PooledEvaluator(-1));
            assertThrows(IllegalArgumentException.class, () -> service.add(network, 0, 4));
            assertThrows(IllegalArgumentException.class, () -> service.add(network, 1, 0));
        }
    }

    @Test
    void warmupRunsTheRequestedSizesOnTheDeviceThreadAndTheStatsCount() throws Exception {
        Network network = new Network(weights(13));
        Recording backend = new Recording(network);
        try (PooledEvaluator service = new PooledEvaluator(2)) {
            PooledEvaluator.Model model = service.add(backend, 1, 32);
            service.warmup(model, 1, 8, 32);
            assertEquals(List.of(1, 8, 32), backend.sizes);
            assertEquals(0, service.stats().batches(), "a warm-up is not counted as work");
            List<List<float[][]>> got = play(model, split(5, 4, 1));
            assertEquals(5, got.size());
            PooledEvaluator.Stats stats = service.stats();
            assertEquals(20, stats.positions());
            assertTrue(stats.batches() >= 4 && stats.batches() <= 20, "batches " + stats.batches());
            assertEquals(stats.batches(), Arrays.stream(stats.sizeHistogram()).sum());
            assertTrue(stats.meanBatch() >= 1 && stats.meanBatch() <= 5);
        }
    }
}
