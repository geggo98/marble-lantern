package sternhalma.selfplay;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import sternhalma.search.PlayerSpec;

/**
 * The self-play data tool (round-2 plan, E05; docs/training.md, "Self-play data tool"). Classical engines only: it
 * never loads a network.
 *
 * <pre>
 *   SelfPlay run --out DIR --spec SPEC --games N --seed S [--threads T] [--shard 50]
 *                [--control all|none|FRACTION] [--s1 0|1] [--margin 500] [--margin-moves 2]
 *                [--eval-threshold 0|P] [--eval-plies 4]
 *                [--opening uniform|softmax] [--opening-min 4] [--opening-max 10] [--opening-temp 30]
 *                [--opening-depth 1|2] [--tt auto|spec] [--epsilon 0.05] [--samples 1|0] [--reuse 1|0]
 *                [--progress SECONDS]
 *   SelfPlay run --status --out DIR
 *   SelfPlay report DIR... [--sweep 300,500,700] [--check-s1 1]
 *   SelfPlay strong-check --from DIR... --out DIR --spec SPEC [--limit 300] [--threads T]
 *   SelfPlay strong-check --status --out DIR
 * </pre>
 *
 * <p>Exit codes: 0 done or nothing to do, 1 error, 2 usage, 75 running already, 130/143 aborted (SIGINT/SIGTERM).
 */
public final class SelfPlay {

    static final String JOB = "selfplay";

    private SelfPlay() {
    }

    public static void main(String[] args) throws Exception {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) throws Exception {
        if (args.length == 0) {
            err.println("usage: SelfPlay run|report|strong-check ... (see the class comment)");
            return 2;
        }
        String command = args[0];
        Map<String, String> opts = new HashMap<>();
        List<String> positional = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--status")) {
                opts.put("status", "1");
            } else if (args[i].startsWith("--")) {
                if (i + 1 >= args.length) {
                    err.println("missing value for " + args[i]);
                    return 2;
                }
                String key = args[i].substring(2);
                String value = args[++i];
                opts.merge(key, value, (a, b) -> a + " " + b);
            } else {
                positional.add(args[i]);
            }
        }
        try {
            return switch (command) {
                case "run" -> runCommand(opts, err);
                case "report" -> Report.run(positional, opts, out, err);
                case "strong-check" -> StrongCheck.run(opts, err);
                default -> {
                    err.println("unknown command " + command);
                    yield 2;
                }
            };
        } catch (IllegalArgumentException e) {
            err.println("usage error: " + e.getMessage());
            return 2;
        }
    }

    static String require(Map<String, String> opts, String key) {
        String v = opts.get(key);
        if (v == null) {
            throw new IllegalArgumentException("--" + key + " is required");
        }
        return v;
    }

    static int intOpt(Map<String, String> opts, String key, int def) {
        String v = opts.get(key);
        return v == null ? def : Integer.parseInt(v);
    }

    static double doubleOpt(Map<String, String> opts, String key, double def) {
        String v = opts.get(key);
        return v == null ? def : Double.parseDouble(v);
    }

    /**
     * The effective spec: with {@code tt=auto} and no {@code tt} key in the spec, the table size follows the node
     * budget, {@code tt = clamp(ceil(log2(nodes)) + 6, 14, 18)} (1000 nodes: 16; 5000 and more: 18).
     */
    static String effectiveSpec(String spec, String ttMode) {
        PlayerSpec parsed = PlayerSpec.parse(spec);
        if (!ttMode.equals("auto") || spec.matches(".*[:,]tt=.*")) {
            return spec;
        }
        int bits = 64 - Long.numberOfLeadingZeros(Math.max(1, parsed.nodes() - 1)); // ceil(log2(nodes))
        int tt = Math.max(14, Math.min(18, bits + 6));
        return spec + ",tt=" + tt;
    }

    static double control(String v) {
        return switch (v) {
            case "all" -> 1.0;
            case "none" -> 0.0;
            default -> {
                double f = Double.parseDouble(v);
                if (f < 0 || f > 1) {
                    throw new IllegalArgumentException("--control must be all, none or a fraction in [0, 1]");
                }
                yield f;
            }
        };
    }

    static SelfPlayGame.Settings settings(Map<String, String> opts) {
        String spec = effectiveSpec(require(opts, "spec"), opts.getOrDefault("tt", "auto"));
        Adjudication.Config d = Adjudication.Config.DEFAULT;
        Adjudication.Config adj = new Adjudication.Config(intOpt(opts, "s1", 1) == 1,
                intOpt(opts, "margin", d.margin()), intOpt(opts, "margin-moves", d.marginMoves()),
                doubleOpt(opts, "eval-threshold", d.evalThreshold()), intOpt(opts, "eval-plies", d.evalPlies()));
        OpeningSampler.Mode mode = OpeningSampler.Mode.valueOf(
                opts.getOrDefault("opening", "uniform").toUpperCase(Locale.ROOT));
        return new SelfPlayGame.Settings(spec, Long.parseLong(require(opts, "seed")), adj,
                control(opts.getOrDefault("control", "0.1")), mode, intOpt(opts, "opening-min", 4),
                intOpt(opts, "opening-max", 10), doubleOpt(opts, "opening-temp", 30), intOpt(opts, "opening-depth", 1),
                doubleOpt(opts, "epsilon", 0.05), intOpt(opts, "samples", 1) == 1, intOpt(opts, "reuse", 1) == 1);
    }

    static int runCommand(Map<String, String> opts, PrintStream err) throws IOException, InterruptedException {
        Path out = Path.of(require(opts, "out"));
        if (opts.containsKey("status")) {
            return Job.status(out, JOB, System.out);
        }
        SelfPlayGame.Settings settings = settings(opts);
        int games = Integer.parseInt(require(opts, "games"));
        int threads = intOpt(opts, "threads", 1);
        GamesJob job = new GamesJob(out, settings, games, intOpt(opts, "shard", 50), threads, err);
        job.progressSeconds = doubleOpt(opts, "progress", 10);
        err.println(JOB + ": " + settings.describe() + "; games=" + games + "; threads=" + threads);
        return job.run();
    }

    /** The self-play job: item = game id, shard = {@code samples-s<seed>-<shard>.safetensors}. */
    static final class GamesJob extends Job<GameRecord> {
        final SelfPlayGame.Settings settings;
        private final java.util.concurrent.atomic.AtomicLong cpuNanos = new java.util.concurrent.atomic.AtomicLong();
        private final java.util.concurrent.atomic.AtomicLong plies = new java.util.concurrent.atomic.AtomicLong();

        GamesJob(Path dir, SelfPlayGame.Settings settings, int games, int shardSize, int threads, PrintStream err) {
            super(dir, JOB, games, shardSize, threads, err);
            this.settings = settings;
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
                SelfPlayGame game = new SelfPlayGame(settings);
                return id -> {
                    GameRecord r = game.play(id);
                    cpuNanos.addAndGet(r.cpuNanos());
                    plies.addAndGet(r.moves().length);
                    return r;
                };
            };
        }

        @Override
        String progressExtra() {
            return String.format(Locale.ROOT, "  %.0f plies/CPU-s", plies.get() / (cpuNanos.get() / 1e9));
        }

        @Override
        void write(Path file, int shard, int first, List<GameRecord> results) throws IOException {
            GameShard.write(file, settings, shard, first, results);
        }
    }

    /** All shards of the given directories, in name order. */
    static List<Path> shardFiles(List<String> dirs) throws IOException {
        List<Path> files = new ArrayList<>();
        for (String d : dirs) {
            Path p = Path.of(d);
            if (Files.isDirectory(p)) {
                try (var s = Files.list(p)) {
                    files.addAll(s.filter(f -> f.getFileName().toString().matches("samples-.*\\.safetensors"))
                            .sorted().toList());
                }
            } else {
                files.add(p);
            }
        }
        return files;
    }
}
