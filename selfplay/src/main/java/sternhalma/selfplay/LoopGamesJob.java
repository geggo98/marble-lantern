package sternhalma.selfplay;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import sternhalma.engine.RuleSet;
import sternhalma.nn.Networks;
import sternhalma.nn.Parameters;
import sternhalma.nn.PooledEvaluator;
import sternhalma.tornado.GpuEvaluators;

/**
 * The self-play of one generation as a {@link Job}: item = game id, shard = {@code samples-s<seed>-<shard>.safetensors}
 * in the generation's {@code samples/} directory. A shard is complete when it reads, was written with these settings
 * for this network, and holds exactly its games; a shard that does not read is renamed to {@code *.corrupt} and
 * redone, a shard of other settings or another network stops the run (the shard is the work of another run).
 *
 * <p><b>The evaluator.</b> With a {@link Device} the games run in many threads (games in flight) and take their evaluations
 * from one {@link PooledEvaluator} (docs/training.md, "As implemented (block G1)"); without it, one game per thread with its own
 * CPU forward pass. The games in flight are virtual threads on a scheduler with as many carriers as compute threads (a parked
 * game costs nothing; with platform threads and a permit, every evaluation costs two context switches and the permit's
 * herd: measured at a third of the throughput). A virtual thread has no CPU time of its own, so the games of a device
 * record 0 in {@code game_cpu_us}, and {@link #cpuNanos()} is the CPU time of the process. The evaluator is not part of the settings that a resume compares: the two give the same games up to
 * the float differences of the two forward passes. Every shard records its evaluator in the metadata key {@code evaluator}
 * ({@code cpu} or {@code gpu}); a shard of the other one is kept and reported, not redone. A stop request aborts the games in
 * flight on a device (they are discarded) instead of waiting for them, since with hundreds of games in flight a game takes
 * a minute.
 */
final class LoopGamesJob extends Job<GameRecord> {

    private final LoopGame.Settings settings;
    private final String networkSha256;
    private final Parameters network;
    private final Loop.StopHook hook;
    private final int generation;
    private final Device device;
    private static final com.sun.management.OperatingSystemMXBean PROCESS =
            (com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean();
    private final long processCpuAtStart = PROCESS.getProcessCpuTime();
    private final AtomicLong cpu = new AtomicLong();
    private final AtomicLong plies = new AtomicLong();
    private final AtomicLong games = new AtomicLong();
    private final AtomicLong adjudicated = new AtomicLong();
    private final AtomicLong sims = new AtomicLong();

    /**
     * The evaluator service of a generation's self-play on a device, opened when the first game starts: a resume that finds
     * every shard complete never opens the device. The job aborts it on a stop request; the caller closes it.
     */
    static final class Device implements AutoCloseable {

        private final Supplier<GpuEvaluators.Session> opener;
        private final String description;
        private final int computeThreads;
        private final ReentrantLock lock = new ReentrantLock();
        private GpuEvaluators.Session session;
        private boolean aborted;
        private boolean closed;

        /**
         * @param opener      opens the service (compiles the plans of the network)
         * @param description what the log calls it, for example the device
         * @param computeThreads carriers of the scheduler of the games, which are virtual threads (the first value in a JVM wins)
         */
        Device(Supplier<GpuEvaluators.Session> opener, String description, int computeThreads) {
            this.opener = opener;
            this.description = description;
            this.computeThreads = computeThreads;
        }

        String description() {
            return description;
        }

        int computeThreads() {
            return computeThreads;
        }

        /** The session, opened by the first caller (a lock, not a monitor: the callers are virtual threads). */
        GpuEvaluators.Session session() {
            lock.lock();
            try {
                if (closed) {
                    throw new IllegalStateException("the device evaluator is closed");
                }
                if (session == null) {
                    session = opener.get();
                    if (aborted) {
                        session.service().abort();
                    }
                }
                return session;
            } finally {
                lock.unlock();
            }
        }

        /** Whether {@link #abort} was called (tests). */
        boolean aborted() {
            lock.lock();
            try {
                return aborted;
            } finally {
                lock.unlock();
            }
        }

        void abort() {
            lock.lock();
            try {
                aborted = true;
                if (session != null) {
                    session.service().abort();
                }
            } finally {
                lock.unlock();
            }
        }

        /** Where the time of the device thread went, or "" before the first game. */
        String timings() {
            lock.lock();
            try {
                return session == null ? "" : session.timings().get();
            } finally {
                lock.unlock();
            }
        }

        PooledEvaluator.Stats stats() {
            lock.lock();
            try {
                return session == null ? null : session.service().stats();
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void close() {
            lock.lock();
            try {
                closed = true;
                if (session != null) {
                    session.close();
                }
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * @param device  the evaluator service, or null: every game thread has its own CPU forward pass
     * @param threads game threads: with a device the games in flight, else the compute threads
     */
    LoopGamesJob(Path dir, int generation, LoopGame.Settings settings, String networkSha256, Parameters network,
            int games, int shardGames, int threads, PrintStream err, Loop.StopHook hook, Device device) {
        super(dir, "selfplay", games, shardGames, threads, err);
        this.device = device;
        this.generation = generation;
        this.settings = settings;
        this.networkSha256 = networkSha256;
        this.network = network;
        this.hook = hook;
    }

    /** Search nodes of all games of this process. */
    long nodes() {
        return sims.get();
    }

    /**
     * Thread CPU time of all games of this process; with a device the CPU time of the whole process since the job was made
     * (the games are virtual threads, which have none of their own), the device thread and the garbage collector included.
     */
    long cpuNanos() {
        if (device != null) {
            return PROCESS.getProcessCpuTime() - processCpuAtStart;
        }
        return cpu.get();
    }

    @Override
    ExecutorService newPool() {
        if (device == null) {
            return super.newPool();
        }
        // read when the first virtual thread of the JVM starts; the carriers are the compute threads
        System.setProperty("jdk.virtualThreadScheduler.parallelism", Integer.toString(device.computeThreads()));
        System.setProperty("jdk.virtualThreadScheduler.maxPoolSize", Integer.toString(device.computeThreads()));
        return Executors.newFixedThreadPool(threads, Thread.ofVirtual().name("game-", 0).factory());
    }

    @Override
    Path shardFile(int shard) {
        return dir.resolve(String.format(Locale.ROOT, "samples-s%d-%05d.safetensors", settings.seed(), shard));
    }

    @Override
    ShardState check(Path file, int shard, int first, int count) {
        GameShard.Read r;
        try {
            r = GameShard.read(file);
        } catch (IOException | RuntimeException e) {
            return ShardState.CORRUPT;
        }
        if (!settings.describe().equals(r.metadata().get("selfplay"))) {
            mismatch("written with settings " + r.metadata().get("selfplay") + ", not " + settings.describe());
            return ShardState.MISMATCH;
        }
        if (!networkSha256.equals(r.metadata().get("network_sha256"))) {
            mismatch("written by the network " + r.metadata().get("network_sha256") + ", not " + networkSha256);
            return ShardState.MISMATCH;
        }
        String evaluator = r.metadata().get("evaluator");
        if (evaluator != null && !evaluator.equals(evaluatorId())) {
            err.println(label + ": " + file.getFileName() + " was written by the " + evaluator + " evaluator, this run uses the "
                    + evaluatorId() + " one: kept (the games differ by float noise only)");
        }
        if (r.games().size() != count) {
            return ShardState.CORRUPT;
        }
        for (int i = 0; i < count; i++) {
            if (r.games().get(i).id() != first + i) {
                return ShardState.CORRUPT;
            }
        }
        return ShardState.COMPLETE;
    }

    /** {@code gpu} or {@code cpu}: the value of the metadata key {@code evaluator}. */
    String evaluatorId() {
        return device == null ? "cpu" : "gpu";
    }

    @Override
    void requestStop() {
        super.requestStop();
        if (device != null) {
            device.abort();
        }
    }

    @Override
    Supplier<Worker<GameRecord>> workers() {
        if (device != null) {
            return () -> {
                PooledEvaluator.Client client = device.session().model().newClient();
                LoopGame game = new LoopGame(settings, client);
                return id -> {
                    client.begin();
                    try {
                        return finished(game.play(id));
                    } catch (PooledEvaluator.Aborted e) {
                        return null; // stopped: the game is discarded, its shard is not written
                    } finally {
                        client.end();
                    }
                };
            };
        }
        return () -> {
            LoopGame game = new LoopGame(settings, Networks.fastest(network));
            return id -> finished(game.play(id));
        };
    }

    /** Counts a finished game and asks the stop hook. */
    private GameRecord finished(GameRecord r) {
        cpu.addAndGet(r.cpuNanos());
        plies.addAndGet(r.moves().length);
        long done = games.incrementAndGet();
        sims.addAndGet(r.nodes());
        if (r.label() != Adjudication.Layer.NONE) {
            adjudicated.incrementAndGet();
        }
        if (hook.stop("selfplay", done)) {
            requestStop();
        }
        return r;
    }

    @Override
    String progressExtra() {
        long g = Math.max(1, games.get());
        String line = String.format(Locale.ROOT, "  %.0f plies/game, %.0f%% S1, %.0f sims/CPU-s", (double) plies.get() / g,
                100.0 * adjudicated.get() / g, sims.get() / Math.max(1e-9, cpuNanos() / 1e9));
        if (device != null) {
            PooledEvaluator.Stats st = device.stats();
            if (st != null) {
                line += String.format(Locale.ROOT, ", batch %.0f, device %.0f%% busy", st.meanBatch(),
                        100.0 * st.executeNanos() / Math.max(1, st.executeNanos() + st.idleNanos()));
            }
        }
        return line;
    }

    @Override
    void write(Path file, int shard, int first, List<GameRecord> results) throws IOException {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("format", GameShard.FORMAT);
        m.put("format_version", Integer.toString(GameShard.FORMAT_VERSION));
        m.put("players", "2");
        m.put("rules", RuleSet.defaults(2).toString());
        m.put("generation", Integer.toString(generation));
        m.put("network_sha256", networkSha256);
        m.put("search", settings.describe());
        m.put("teacher", "");
        m.put("seed", Long.toString(settings.seed()));
        m.put("selfplay", settings.describe());
        m.put("evaluator", evaluatorId());
        m.put("shard", Integer.toString(shard));
        m.put("first_game", Integer.toString(first));
        m.put("games", Integer.toString(results.size()));
        SampleFile.write(file, GameShard.tensors(results), m);
    }
}
