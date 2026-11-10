package sternhalma.selfplay;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import sternhalma.engine.RuleSet;
import sternhalma.nn.Networks;
import sternhalma.nn.Parameters;

/**
 * The self-play of one generation as a {@link Job}: item = game id, shard = {@code samples-s<seed>-<shard>.safetensors}
 * in the generation's {@code samples/} directory. A shard is complete when it reads, was written with these settings
 * for this network, and holds exactly its games; a shard that does not read is renamed to {@code *.corrupt} and
 * redone, a shard of other settings or another network stops the run (the shard is the work of another run).
 */
final class LoopGamesJob extends Job<GameRecord> {

    private final LoopGame.Settings settings;
    private final String networkSha256;
    private final Parameters network;
    private final Loop.StopHook hook;
    private final int generation;
    private final AtomicLong cpu = new AtomicLong();
    private final AtomicLong plies = new AtomicLong();
    private final AtomicLong games = new AtomicLong();
    private final AtomicLong adjudicated = new AtomicLong();
    private final AtomicLong sims = new AtomicLong();

    LoopGamesJob(Path dir, int generation, LoopGame.Settings settings, String networkSha256, Parameters network,
            int games, int shardGames, int threads, PrintStream err, Loop.StopHook hook) {
        super(dir, "selfplay", games, shardGames, threads, err);
        this.generation = generation;
        this.settings = settings;
        this.networkSha256 = networkSha256;
        this.network = network;
        this.hook = hook;
    }

    /** Thread CPU time of all games of this process. */
    long cpuNanos() {
        return cpu.get();
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

    @Override
    Supplier<Worker<GameRecord>> workers() {
        return () -> {
            LoopGame game = new LoopGame(settings, Networks.fastest(network));
            return id -> {
                GameRecord r = game.play(id);
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
            };
        };
    }

    @Override
    String progressExtra() {
        long g = Math.max(1, games.get());
        return String.format(Locale.ROOT, "  %.0f plies/game, %.0f%% S1, %.0f sims/CPU-s", (double) plies.get() / g,
                100.0 * adjudicated.get() / g, sims.get() / Math.max(1e-9, cpu.get() / 1e9));
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
        m.put("shard", Integer.toString(shard));
        m.put("first_game", Integer.toString(first));
        m.put("games", Integer.toString(results.size()));
        SampleFile.write(file, GameShard.tensors(results), m);
    }
}
