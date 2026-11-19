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

import sternhalma.engine.RuleSet.JumpRule;
import sternhalma.search.PlayerSpec;
import sternhalma.search.Strategies;

/**
 * The self-play data tool (round-2 plan, E05; docs/training.md, "Self-play data tool"). Classical engines only: it
 * never loads a network.
 *
 * <pre>
 *   SelfPlay run --out DIR --spec SPEC --games N --seed S [--threads T] [--shard 50]
 *                [--control all|none|FRACTION] [--s1 0|1] [--margin 0|500] [--margin-moves 2]
 *                [--eval-threshold 0|P] [--eval-plies 4]
 *                [--opening uniform|softmax] [--opening-min 4] [--opening-max 10] [--opening-temp 30]
 *                [--opening-depth 1|2] [--tt auto|spec] [--epsilon 0.05] [--samples 1|0] [--reuse 1|0]
 *                [--teacher-depth D [--teacher-tau T] [--teacher-alpha START,END,PLIES]] [--progress SECONDS]
 *                [--players 2|3] [--jump adjacent|long] [--nodes N]
 *   SelfPlay run --status --out DIR
 *   SelfPlay pilot [--games 20] [--threads T] (the options of run; writes nothing, prints length, move-limit share and cost)
 *   SelfPlay report DIR... [--sweep 300,500,700] [--check-s1 1]
 *   SelfPlay strong-check --from DIR... --out DIR --spec SPEC [--limit 300] [--threads T]
 *   SelfPlay strong-check --status --out DIR
 *   SelfPlay teacher-check --out DIR --seed S [--spec SPEC] [--positions 300] [--depths 2,3] [--cost-games 20]
 *   SelfPlay monitor-report RUN
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
            err.println("usage: SelfPlay run|report|strong-check|teacher-check ... (see the class comment)");
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
                case "pilot" -> VariantPilot.run(opts, out, err);
                case "report" -> Report.run(positional, opts, out, err);
                case "strong-check" -> StrongCheck.run(opts, err);
                case "teacher-check" -> TeacherCheck.run(opts, out, err);
                case "monitor-report" -> {
                    if (positional.size() != 1) {
                        throw new IllegalArgumentException("monitor-report needs one run directory");
                    }
                    yield Gen0.run(new String[] {"monitor-report", positional.get(0)}, out, err);
                }
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

    static JumpRule jump(String v) {
        return switch (v) {
            case "adjacent" -> JumpRule.ADJACENT;
            case "long" -> JumpRule.LONG;
            default -> throw new IllegalArgumentException("--jump must be adjacent or long, got " + v);
        };
    }

    /** Nodes per move of the two-player long-hop mover when neither --spec nor --nodes says otherwise. */
    static final long LONG_NODES = 5000;

    /**
     * The mover spec of a variant. Two players, adjacent jump: {@code --spec} is required, as always. Otherwise the
     * default is {@code Strategies.BROWSER_LONG_SPEC} (2p long, with {@code nodes=5000}), {@code BROWSER_3P_SPEC} or
     * {@code BROWSER_3P_LONG_SPEC}. {@code --nodes N} sets the node budget of any of them; {@code jump=long} is
     * added to a given spec that lacks a jump key. The table size is chosen from the nodes for two players
     * ({@link #effectiveSpec}); an {@code ab3:} spec keeps its own {@code tt}.
     */
    static String variantSpec(Map<String, String> opts, int players, JumpRule jump) {
        String spec = opts.get("spec");
        if (spec == null) {
            if (players == 3) {
                spec = jump == JumpRule.LONG ? Strategies.BROWSER_3P_LONG_SPEC : Strategies.BROWSER_3P_SPEC;
            } else if (jump == JumpRule.LONG) {
                spec = Strategies.BROWSER_LONG_SPEC + (opts.containsKey("nodes") ? "" : ",nodes=" + LONG_NODES);
            } else {
                spec = require(opts, "spec");
            }
        }
        if (jump == JumpRule.LONG && !spec.matches(".*[:,]jump=.*")) {
            spec += ",jump=long";
        }
        String nodes = opts.get("nodes");
        if (nodes != null) {
            Long.parseLong(nodes);
            spec = spec.matches(".*[:,]nodes=.*") ? spec.replaceAll("([:,])nodes=\\d+", "$1nodes=" + nodes)
                    : spec + ",nodes=" + nodes;
        }
        return players == 3 ? spec : effectiveSpec(spec, opts.getOrDefault("tt", "auto"));
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
        int players = intOpt(opts, "players", 2);
        JumpRule jump = jump(opts.getOrDefault("jump", "adjacent"));
        boolean long2 = players == 2 && jump == JumpRule.LONG;
        String spec = variantSpec(opts, players, jump);
        Adjudication.Config d = Adjudication.Config.PRODUCTION;
        // 2p long: no S1 (the race table counts single jumps), the score-margin layer on; 3p: no adjudication at all
        boolean variantOff = players == 3 || long2;
        Adjudication.Config adj = new Adjudication.Config(intOpt(opts, "s1", variantOff ? 0 : 1) == 1,
                intOpt(opts, "margin", long2 ? 500 : d.margin()), intOpt(opts, "margin-moves", d.marginMoves()),
                doubleOpt(opts, "eval-threshold", d.evalThreshold()), intOpt(opts, "eval-plies", d.evalPlies()));
        OpeningSampler.Mode mode = OpeningSampler.Mode.valueOf(
                opts.getOrDefault("opening", "uniform").toUpperCase(Locale.ROOT));
        return new SelfPlayGame.Settings(spec, Long.parseLong(require(opts, "seed")), adj,
                control(opts.getOrDefault("control", "0.1")), mode, intOpt(opts, "opening-min", 4),
                intOpt(opts, "opening-max", 10), doubleOpt(opts, "opening-temp", 30), intOpt(opts, "opening-depth", 1),
                doubleOpt(opts, "epsilon", 0.05), intOpt(opts, "samples", 1) == 1, intOpt(opts, "reuse", 1) == 1,
                teacher(opts, long2), players, jump);
    }

    /**
     * The teacher of {@code --teacher-depth D [--teacher-tau T] [--teacher-alpha START,END,PLIES]}, or null without
     * {@code --teacher-depth}. Without {@code --teacher-tau} the temperature measured for the depth (3: 0.1, 2: 0.06).
     */
    static Teacher.Config teacher(Map<String, String> opts) {
        return teacher(opts, false);
    }

    /**
     * With {@code long2} (two players, long hop) the teacher is on by default: depth 2 and the calibration
     * {@code 0.5,0.2,100}, the weights of the value blend that the sigma(score / 75) calibration of the adjacent game
     * does not justify for the long hop (unverified: docs/training.md).
     */
    static Teacher.Config teacher(Map<String, String> opts, boolean long2) {
        String depth = opts.get("teacher-depth");
        if (depth == null && long2) {
            depth = "2";
        }
        if (depth == null) {
            if (opts.containsKey("teacher-tau") || opts.containsKey("teacher-alpha")) {
                throw new IllegalArgumentException("--teacher-tau and --teacher-alpha need --teacher-depth");
            }
            return null;
        }
        int d = Integer.parseInt(depth);
        double tau = opts.containsKey("teacher-tau") ? Double.parseDouble(opts.get("teacher-tau"))
                : Teacher.Config.defaultTau(d);
        String alpha = opts.getOrDefault("teacher-alpha", long2 ? "0.5,0.2,100" : null);
        if (alpha == null) {
            return new Teacher.Config(d, tau);
        }
        String[] a = alpha.split(",");
        if (a.length != 3) {
            throw new IllegalArgumentException("--teacher-alpha takes START,END,PLIES");
        }
        return new Teacher.Config(d, tau, Double.parseDouble(a[0]), Double.parseDouble(a[1]),
                Integer.parseInt(a[2]));
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
        private final List<Teacher> teachers = new java.util.concurrent.CopyOnWriteArrayList<>();

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
                if (game.teacher() != null) {
                    teachers.add(game.teacher());
                }
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
            String extra = String.format(Locale.ROOT, "  %.0f plies/CPU-s", plies.get() / (cpuNanos.get() / 1e9));
            long teacherSamples = 0;
            long teacherNanos = 0;
            for (Teacher t : teachers) {
                teacherSamples += t.samples();
                teacherNanos += t.cpuNanos();
            }
            return teacherSamples == 0 ? extra : extra + String.format(Locale.ROOT, "  teacher %.2f ms/sample",
                    teacherNanos / 1e6 / teacherSamples);
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
