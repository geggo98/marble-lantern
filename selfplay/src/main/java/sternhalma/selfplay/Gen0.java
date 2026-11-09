package sternhalma.selfplay;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import sternhalma.nn.Checkpoint;
import sternhalma.nn.LearningRateSchedule;
import sternhalma.nn.Network;
import sternhalma.nn.NetworkConfig;
import sternhalma.nn.Optimizer;
import sternhalma.nn.Parameters;
import sternhalma.nn.TrainingObserver;
import sternhalma.nn.TrainingRun;
import sternhalma.search.Strategies;

/**
 * Generation 0: supervised training of a network on teacher samples, resumable, with checkpoints, ETA and strength
 * estimates (docs/training.md, "Bootstrap" and "As implemented (block 2e)").
 *
 * <pre>
 *   Gen0 run DATA RUN [--preset small|tiny] [--steps N] [--batch 64] [--microbatch 16] [--threads T] [--lr 1e-3]
 *                     [--warmup 200] [--seed S] [--holdout 0.1] [--augment 1|0] [--value-coefficient 1]
 *                     [--checkpoint-steps 200] [--checkpoint-seconds 120] [--keep 2] [--progress-seconds 10]
 *                     [--eval-samples 2500] [--proxy-every 1] [--proxy-seconds 120] [--elo-every 0] [--elo-seconds 500] [--ladder 200] [--ladder-spec SPEC]
 *                     [--elo-pairs 50] [--elo-seed 1] [--elo-threads 1] [--monitor 1|0]
 *   Gen0 run --status RUN
 *   Gen0 strength RUN [--checkpoint FILE] [--ladder 200] [--ladder-spec SPEC] [--elo-pairs 50] [--elo-seed 1]
 *                     [--elo-threads 4] [--progress-seconds 10] [--player raw|mcts:...]
 *   Gen0 proxies RUN --data DIR [--checkpoint FILE] [--samples 2500] [--threads 4]
 *   Gen0 monitor-report RUN
 * </pre>
 *
 * <p>DATA is a directory of {@code samples-*.safetensors} from {@code selfplay run --teacher-depth D}. The games are
 * split by game id into training and held-out games ({@link #heldOut}); the held-out samples are used by the proxies
 * only. The run directory:
 * <pre>
 *   RUN/run.json                      the configuration, written once
 *   RUN/.gen0.lock, .gen0.status      lock (held by the process) and display status
 *   RUN/metrics.jsonl                 the monitor lines (display only)
 *   RUN/gen-0000/train/step-*.safetensors   checkpoints
 *   RUN/gen-0000/candidate.safetensors      the network after the last step, a plain network file
 *   RUN/gen-0000/strength/step-*.jsonl      the Elo estimate of a checkpoint
 *   RUN/gen-0000/strength/step-*.player-*.jsonl  the same for another player ({@code --player}), one file per player text
 * </pre>
 *
 * <p>Job conventions (docs/training.md, "Job conventions"): the state comes from the artefacts (the newest checkpoint
 * that reads, the candidate file); a checkpoint cadence, an estimate cadence or switching the monitor off changes no
 * result (switching it off also switches off the proxies and the Elo estimate in the loop, and a note says so), and none of them is part of the configuration that {@code run.json} fixes. A second process exits with 75,
 * SIGINT with 130 and SIGTERM with 143 after the summary, 1 is an error (also another configuration in {@code run.json}
 * or other data), 2 a usage error, and a second run after success does nothing and exits with 0.
 */
public final class Gen0 {

    static final String JOB = "gen0";
    static final String STRENGTH_JOB = "strength";
    static final int GENERATION = 0;
    static final String FORMAT = "sternhalma-gen0";
    /** Mixed into the split so it is not the same function as any other hash of the game id. */
    static final long SPLIT_SALT = 0x6A09E667F3BCC908L;

    /** Held-out samples per proxy evaluation: the standard error of the winner prediction is about 0.9 points. */
    static final int DEFAULT_EVAL_SAMPLES = 2500;

    static final int EXIT_OK = 0;
    static final int EXIT_ERROR = 1;
    static final int EXIT_USAGE = 2;
    static final int EXIT_RUNNING = 75;
    static final int EXIT_ABORTED = 130;

    /**
     * What a run is, fixed in {@code run.json} and hashed into every checkpoint. Cadences are not here: another
     * cadence gives the same result. The thread count is not here either: the trainer adds its microbatches in
     * microbatch order, so the result does not depend on it.
     */
    record Config(String preset, long seed, long steps, int batch, int microbatch, double lr, int warmup,
            double valueCoefficient, boolean augment, double holdout, String dataSha256, int dataFiles) {

        NetworkConfig network() {
            return switch (preset) {
                case "small" -> NetworkConfig.small(2);
                case "tiny" -> NetworkConfig.tiny(2);
                default -> throw new IllegalArgumentException("--preset must be small or tiny, got " + preset);
            };
        }

        LearningRateSchedule schedule() {
            return new LearningRateSchedule(lr, warmup, steps, 0.1, 100, List.of());
        }

        String json() {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("format", FORMAT);
            f.put("version", 1);
            f.put("preset", preset);
            f.put("players", 2);
            f.put("seed", seed);
            f.put("steps", steps);
            f.put("batch", batch);
            f.put("microbatch", microbatch);
            f.put("optimizer", "adamw");
            f.put("lr", lr);
            f.put("warmup", warmup);
            f.put("final_fraction", 0.1);
            f.put("value_coefficient", valueCoefficient);
            f.put("augment", augment);
            f.put("holdout", holdout);
            f.put("holdout_salt", SPLIT_SALT);
            f.put("data_sha256", dataSha256);
            f.put("data_files", dataFiles);
            return RunMonitor.json(f);
        }

        String sha256() {
            return sha256Hex(json().getBytes(StandardCharsets.UTF_8));
        }
    }

    /** How often things happen. None of it changes a result. */
    record Cadence(long checkpointSteps, double checkpointSeconds, int keep, double progressSeconds, int evalSamples,
            int proxyEvery, double proxySeconds, int eloEvery, double eloSeconds, String ladderBase, List<Integer> ladder, int eloPairs, long eloSeed,
            int eloThreads, boolean monitor, int threads) {
    }

    private Gen0() {
    }

    public static void main(String[] args) throws Exception {
        System.exit(run(args, System.out, System.err, () -> false));
    }

    static int run(String[] args, PrintStream out, PrintStream err) throws Exception {
        return run(args, out, err, () -> false);
    }

    /**
     * @param testStop asked between training steps and strength pairs besides the signals; true aborts like SIGINT
     *                 (a test hook)
     */
    static int run(String[] args, PrintStream out, PrintStream err, BooleanSupplier testStop) throws Exception {
        if (args.length == 0) {
            err.println("usage: Gen0 run DATA RUN | run --status RUN | strength RUN | proxies RUN | monitor-report RUN"
                    + " (see the class comment)");
            return EXIT_USAGE;
        }
        Map<String, String> opts = new HashMap<>();
        List<String> positional = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--status")) {
                opts.put("status", "1");
            } else if (args[i].startsWith("--")) {
                if (i + 1 >= args.length) {
                    err.println("missing value for " + args[i]);
                    return EXIT_USAGE;
                }
                opts.put(args[i].substring(2), args[++i]);
            } else {
                positional.add(args[i]);
            }
        }
        try {
            return switch (args[0]) {
                case "run" -> opts.containsKey("status") ? status(runDir(positional, 0), JOB, out)
                        : train(opts, positional, out, err, testStop);
                case "strength" -> strength(opts, runDir(positional, 0), out, err, testStop);
                case "proxies" -> proxiesCommand(opts, runDir(positional, 0), out, err);
                case "monitor-report" -> monitorReport(runDir(positional, 0), out, err);
                default -> {
                    err.println("unknown command " + args[0]);
                    yield EXIT_USAGE;
                }
            };
        } catch (IllegalArgumentException e) {
            err.println("usage error: " + e.getMessage());
            return EXIT_USAGE;
        }
    }

    private static Path runDir(List<String> positional, int index) {
        if (positional.size() <= index) {
            throw new IllegalArgumentException("the run directory is missing");
        }
        return Path.of(positional.get(index));
    }

    // ---- options ----

    private static String str(Map<String, String> o, String key, String def) {
        return o.getOrDefault(key, def);
    }

    private static long lng(Map<String, String> o, String key, long def) {
        return o.containsKey(key) ? Long.parseLong(o.get(key)) : def;
    }

    private static int integer(Map<String, String> o, String key, int def) {
        return o.containsKey(key) ? Integer.parseInt(o.get(key)) : def;
    }

    private static double dbl(Map<String, String> o, String key, double def) {
        return o.containsKey(key) ? Double.parseDouble(o.get(key)) : def;
    }

    private static boolean flag(Map<String, String> o, String key, boolean def) {
        String v = o.get(key);
        if (v == null) {
            return def;
        }
        return switch (v) {
            case "1", "on", "true" -> true;
            case "0", "off", "false" -> false;
            default -> throw new IllegalArgumentException("--" + key + " must be 1 or 0, got " + v);
        };
    }

    private static List<Integer> ladder(Map<String, String> o) {
        List<Integer> ladder = new ArrayList<>();
        for (String s : str(o, "ladder", "200").split(",")) {
            int nodes = Integer.parseInt(s.strip());
            ladder.add(nodes);
        }
        StrengthEstimate.requireDistinctRungs(ladder);
        return ladder;
    }

    static Cadence cadence(Map<String, String> o) {
        Cadence c = new Cadence(lng(o, "checkpoint-steps", 200), dbl(o, "checkpoint-seconds", 120), integer(o, "keep", 2),
                dbl(o, "progress-seconds", 10), integer(o, "eval-samples", DEFAULT_EVAL_SAMPLES), integer(o, "proxy-every", 1), dbl(o, "proxy-seconds", 120),
                integer(o, "elo-every", 0), dbl(o, "elo-seconds", 500), str(o, "ladder-spec", Strategies.BEST_SPEC), ladder(o),
                integer(o, "elo-pairs", 50), lng(o, "elo-seed", 1), integer(o, "elo-threads", 1),
                flag(o, "monitor", true), integer(o, "threads", Math.min(4, Runtime.getRuntime().availableProcessors())));
        if (c.checkpointSteps < 1 || !(c.checkpointSeconds > 0) || c.keep < 1 || !(c.progressSeconds > 0)
                || c.evalSamples < 1 || c.proxySeconds < 0 || c.proxyEvery < 0 || c.eloEvery < 0 || c.eloSeconds < 0 || c.eloPairs < 2 || c.eloThreads < 1
                || c.threads < 1) {
            throw new IllegalArgumentException("a cadence or count option is out of range");
        }
        return c;
    }

    // ---- hashing, split, data ----

    static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Whether a game belongs to the held-out set: a fixed function of the file's {@code seed} and the game id, so
     * the split does not depend on the file order, the sample order or the run, and the two sets are disjoint by
     * construction (a game is in exactly one of them). About {@code fraction} of the games are held out.
     */
    static boolean heldOut(Map<String, String> metadata, int gameId, double fraction) {
        String seedText = metadata.getOrDefault("seed", "0");
        long seed;
        try {
            seed = Long.parseLong(seedText);
        } catch (NumberFormatException e) {
            seed = seedText.hashCode();
        }
        long h = sternhalma.nn.BatchSampler.mix(SPLIT_SALT, gameId, seed);
        return (h >>> 11) * 0x1.0p-53 < fraction;
    }

    /** The training and the held-out samples of the data files, split by game. */
    record Split(ReplayBuffer train, ReplayBuffer heldOut, List<Path> skipped) {
    }

    /**
     * Loads the files twice, once with the games that are not held out (the training buffer, mirrored if asked) and
     * once with the held-out games (never mirrored). Every game is in exactly one of the two.
     */
    static Split split(List<Path> files, double holdout, boolean augment) throws IOException {
        ReplayBuffer train = new ReplayBuffer(2, Integer.MAX_VALUE, augment);
        ReplayBuffer held = new ReplayBuffer(2, Integer.MAX_VALUE, false);
        ReplayBuffer.Loaded loaded = train.addGeneration(GENERATION, files, (m, id) -> !heldOut(m, id, holdout));
        held.addGeneration(GENERATION, files, (m, id) -> heldOut(m, id, holdout));
        return new Split(train, held, loaded.skipped());
    }

    static List<Path> dataFiles(Path data) throws IOException {
        try (Stream<Path> s = Files.list(data)) {
            return s.filter(f -> f.getFileName().toString().matches("samples-.*\\.safetensors")).sorted().toList();
        }
    }

    /** SHA-256 over {@code name:size} of the data files: the data is fixed by its shards, which never change. */
    static String dataFingerprint(List<Path> files) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Path f : files) {
            sb.append(f.getFileName()).append(':').append(Files.size(f)).append('\n');
        }
        return sha256Hex(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    // ---- lock, status ----

    private static FileLock tryLock(FileChannel channel) throws IOException {
        try {
            return channel.tryLock();
        } catch (OverlappingFileLockException e) {
            return null; // held by this JVM
        }
    }

    static int status(Path run, String name, PrintStream out) throws IOException {
        return Job.status(run, name, out);
    }

    private static void writeStatus(Path file, String start, String progress) {
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, "pid=" + ProcessHandle.current().pid() + " start=" + start + "\n"
                    + (progress == null ? "" : progress + "\n"));
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // the status file is for display only
        }
    }

    /** Lines to stderr and the status file; counts the progress lines (those with a rate). */
    private static final class ProgressSink extends OutputStream {
        private final PrintStream err;
        private final Path statusFile;
        private final String start;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();
        volatile long progressLines;

        ProgressSink(PrintStream err, Path statusFile, String start) {
            this.err = err;
            this.statusFile = statusFile;
            this.start = start;
        }

        @Override
        public void write(int b) {
            if (b == '\n') {
                String text = line.toString(StandardCharsets.UTF_8);
                line.reset();
                err.println(text);
                if (text.contains(" steps/s ")) {
                    progressLines++;
                    writeStatus(statusFile, start, text);
                }
            } else {
                line.write(b);
            }
        }
    }

    // ---- ETA text ----

    static String duration(long seconds) {
        if (seconds < 60) {
            return seconds + "s";
        }
        if (seconds < 3600) {
            return String.format(Locale.ROOT, "%dm%02ds", seconds / 60, seconds % 60);
        }
        return String.format(Locale.ROOT, "%dh%02dm%02ds", seconds / 3600, seconds % 3600 / 60, seconds % 60);
    }

    /** {@code whole run: L steps left, ETA 2m07s at 31.5 steps/s (source)}; the ETA of the rest of the run. */
    static String wholeRun(long stepsLeft, double stepsPerSecond, String source) {
        if (stepsLeft <= 0) {
            return "whole run: nothing left to train";
        }
        if (!(stepsPerSecond > 0)) {
            return "whole run: " + stepsLeft + " steps left, ETA unknown (no rate yet)";
        }
        return String.format(Locale.ROOT, "whole run: %d steps left, ETA %s at %.2f steps/s (%s)", stepsLeft,
                duration(Math.round(stepsLeft / stepsPerSecond)), stepsPerSecond, source);
    }

    /** The step rate of the loops recorded so far in the metrics file, 0 if there are none (display only). */
    private static double earlierRate(Path run) {
        try {
            RunMonitor.Report r = RunMonitor.report(run);
            return r.loopWallNanos() > 0 && r.steps() > 0 ? r.steps() / (r.loopWallNanos() / 1e9) : 0;
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    // ---- the training run ----

    private static int train(Map<String, String> opts, List<String> positional, PrintStream out, PrintStream err,
            BooleanSupplier testStop) throws Exception {
        Path data = runDir(positional, 0);
        Path run = runDir(positional, 1);
        Cadence cad = cadence(opts);
        List<Path> files = Files.isDirectory(data) ? dataFiles(data) : List.of();
        if (files.isEmpty()) {
            err.println("gen0: no samples-*.safetensors in " + data);
            return EXIT_ERROR;
        }
        Config cfg = new Config(str(opts, "preset", "small"), lng(opts, "seed", 1), lng(opts, "steps", 4000),
                integer(opts, "batch", 64), integer(opts, "microbatch", 16), dbl(opts, "lr", 1e-3),
                integer(opts, "warmup", 200), dbl(opts, "value-coefficient", 1.0), flag(opts, "augment", true),
                dbl(opts, "holdout", 0.1), dataFingerprint(files), files.size());
        cfg.network();
        cfg.schedule();
        if (cfg.batch < 1 || cfg.microbatch < 1 || !(cfg.holdout > 0 && cfg.holdout < 1)) {
            throw new IllegalArgumentException("batch and microbatch must be >= 1 and holdout in (0, 1)");
        }
        Files.createDirectories(run);
        try (FileChannel lockChannel = FileChannel.open(run.resolve("." + JOB + ".lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE); FileLock lock = tryLock(lockChannel)) {
            if (lock == null) {
                status(run, JOB, err);
                return EXIT_RUNNING;
            }
            return trainLocked(cfg, cad, files, run, err, testStop);
        }
    }

    private static int trainLocked(Config cfg, Cadence cad, List<Path> files, Path run, PrintStream err,
            BooleanSupplier testStop) throws Exception {
        String start = Instant.now().toString();
        Path statusFile = run.resolve("." + JOB + ".status");
        writeStatus(statusFile, start, null);
        long t0 = System.nanoTime();

        // phase 1: the configuration
        Path runJson = run.resolve("run.json");
        String text = cfg.json() + "\n";
        if (Files.exists(runJson)) {
            if (!Files.readString(runJson).equals(text)) {
                err.println("gen0: " + runJson + " holds another configuration or other data; use another run"
                        + " directory or delete this one.\n  existing: " + Files.readString(runJson).strip()
                        + "\n  wanted:   " + cfg.json());
                return EXIT_ERROR;
            }
        } else {
            Path tmp = runJson.resolveSibling("run.json.tmp");
            Files.writeString(tmp, text);
            Files.move(tmp, runJson, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        String sha = cfg.sha256();
        Path genDir = run.resolve("gen-0000");
        Path trainDir = genDir.resolve("train");
        Path strengthDir = genDir.resolve("strength");
        Path candidate = genDir.resolve("candidate.safetensors");
        Files.createDirectories(trainDir);
        // genDir is ours (the lock); the strength directory and the run directory are shared with a separate
        // strength process, whose in-flight temporary files must not be taken away
        int cleanup = removeTemporaries(genDir, Duration.ZERO) + removeTemporaries(strengthDir, FOREIGN_TEMP_AGE)
                + removeTemporaries(run, FOREIGN_TEMP_AGE);

        // done already?
        if (candidateDone(candidate, cfg, sha)) {
            err.printf(Locale.ROOT, "gen 0 train: 0 ok, %d skip, 0 error, %d cleanup; 0 left; nothing more to do%n",
                    cfg.steps, cleanup);
            err.println("gen 0: candidate " + candidate + " is complete; " + wholeRun(0, 0, ""));
            return EXIT_OK;
        }

        // phase 2: load the data and split it
        Split split;
        try {
            split = split(files, cfg.holdout(), cfg.augment());
        } catch (IllegalArgumentException e) {
            err.println("gen0: the data does not fit: " + e.getMessage());
            return EXIT_ERROR;
        }
        if (!split.skipped().isEmpty()) {
            err.println("gen0: these data files do not read: " + split.skipped());
            return EXIT_ERROR;
        }
        ReplayBuffer trainBuffer = split.train();
        ReplayBuffer heldBuffer = split.heldOut();
        if (trainBuffer.size() == 0 || heldBuffer.size() == 0) {
            err.println("gen0: the split leaves " + trainBuffer.size() + " training and " + heldBuffer.size()
                    + " held-out samples; use more data or another --holdout");
            return EXIT_ERROR;
        }
        Checkpoint.Recovery recovery = Checkpoint.recover(trainDir);
        cleanup += recovery.quarantined().size();
        Parameters parameters;
        Optimizer optimizer;
        if (recovery.loaded() != null) {
            Checkpoint.Loaded loaded = recovery.loaded();
            if (!loaded.runConfigSha256().equals(sha) || loaded.generation() != GENERATION
                    || !loaded.parameters().config().equals(cfg.network())) {
                err.println("gen0: the newest checkpoint belongs to another configuration");
                return EXIT_ERROR;
            }
            parameters = loaded.parameters();
            optimizer = loaded.optimizer();
        } else {
            parameters = Parameters.init(cfg.network(), cfg.seed());
            optimizer = new Optimizer(parameters, Optimizer.Settings.adamw());
        }
        long startStep = optimizer.steps();
        if (startStep > cfg.steps()) {
            err.println("gen0: the checkpoint is at step " + startStep + ", the run plans " + cfg.steps());
            return EXIT_ERROR;
        }
        double earlier = earlierRate(run);
        err.printf(Locale.ROOT, "gen 0 load: %d files, %d training and %d held-out samples (held out by game id),"
                + " start at step %d, %d cleanup, %.1f s; %s%n", files.size(), trainBuffer.size(), heldBuffer.size(),
                startStep, cleanup, (System.nanoTime() - t0) / 1e9,
                wholeRun(cfg.steps() - startStep, earlier, earlier > 0 ? "rate of an earlier run" : "none yet"));

        // phase 3: train
        TrainingRun.Clock clock = TrainingRun.Clock.system();
        RunMonitor monitor = cad.monitor() ? new RunMonitor(run.resolve("metrics.jsonl"), clock) : null;
        if (monitor == null) {
            err.println("gen 0 note: --monitor 0 also switches off the held-out proxies and the Elo estimate in the"
                    + " loop (both report through the monitor); no metrics.jsonl is written. Use `gen0 strength`"
                    + " for Elo estimates of checkpoints.");
        }
        AtomicBoolean signalStop = new AtomicBoolean();
        BooleanSupplier stop = () -> signalStop.get() || testStop.getAsBoolean();
        Observer observer = new Observer(cfg, cad, monitor, parameters, heldBuffer, strengthDir, stop, err);
        ProgressSink sink = new ProgressSink(err, statusFile, start);
        PrintStream progress = new PrintStream(sink, true, StandardCharsets.UTF_8);
        TrainingRun.Setup setup = new TrainingRun.Setup(parameters, optimizer, cfg.schedule(), trainBuffer, cfg.batch(),
                cfg.microbatch(), cad.threads(), cfg.valueCoefficient(), cfg.seed(), GENERATION, cfg.steps());
        TrainingRun.Checkpointing checkpointing = new TrainingRun.Checkpointing(trainDir, cad.checkpointSteps(),
                Math.round(cad.checkpointSeconds() * 1e9), cad.keep(), sha);
        TrainingRun.Reporting reporting = new TrainingRun.Reporting(
                monitor == null ? List.<TrainingObserver>of() : List.<TrainingObserver>of(observer), progress,
                Math.round(cad.progressSeconds() * 1e9));
        TrainingRun trainingRun = new TrainingRun(setup, checkpointing, reporting, clock, stop);

        CountDownLatch finished = new CountDownLatch(1);
        Thread hook = new Thread(() -> {
            signalStop.set(true);
            try {
                finished.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, JOB + "-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
        int exit;
        try {
            TrainingRun.Summary summary = trainingRun.run();
            progress.flush();
            cleanup += summary.cleanup();
            TrainingRun.Timing timing = summary.timing();
            double rate = timing.loopWallNanos() > 0 && summary.stepsDone() > 0
                    ? summary.stepsDone() / (timing.loopWallNanos() / 1e9) : 0;
            long left = cfg.steps() - summary.endStep();
            err.printf(Locale.ROOT, "gen 0 train: %d steps in %s, %.2f steps/s = %.1f samples/s (%d checkpoints, %d"
                    + " strength estimates in the loop); %s%n", summary.stepsDone(),
                    duration(Math.round(timing.loopWallNanos() / 1e9)), rate, rate * cfg.batch(),
                    summary.checkpointsWritten(), observer.estimates, wholeRun(left, rate, "this run"));
            if (monitor != null) {
                monitor.recordRun(timing, cfg.batch(), observer.spent(), false, sink.progressLines);
            }
            if (summary.aborted()) {
                err.println("gen 0 finish: aborted at step " + summary.endStep() + "; run again to continue; "
                        + wholeRun(left, rate, "this run"));
                exit = EXIT_ABORTED;
            } else {
                parameters.extraMetadata().put("step", Long.toString(optimizer.steps()));
                parameters.extraMetadata().put("run_config_sha256", sha);
                parameters.save(candidate);
                err.println("gen 0 finish: candidate written, " + candidate + " (step " + optimizer.steps() + "); "
                        + wholeRun(0, rate, "this run"));
                exit = EXIT_OK;
            }
        } finally {
            finished.countDown();
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException e) {
                // shutting down because of a signal; the JVM ends with 128 + signal
            }
        }
        return exit;
    }

    /**
     * How old a temporary file of a directory shared with another process must be before it is a leftover. A
     * temporary file is written and renamed within seconds; a younger one may belong to a live process (the loop and
     * a separate strength process write into the same directories under different locks).
     */
    static final Duration FOREIGN_TEMP_AGE = Duration.ofMinutes(10);

    /**
     * Removes the {@code *.tmp} files of {@code dir} that were last modified at least {@code minAge} ago. A file that
     * vanishes meanwhile (its owner renamed it) or cannot be removed is not an error and not counted.
     *
     * @return the number of files removed
     */
    static int removeTemporaries(Path dir, Duration minAge) throws IOException {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        Instant limit = Instant.now().minus(minAge);
        int removed = 0;
        List<Path> candidates;
        try (Stream<Path> s = Files.list(dir)) {
            candidates = s.filter(f -> f.getFileName().toString().endsWith(".tmp")).toList();
        }
        for (Path f : candidates) {
            try {
                if (Files.getLastModifiedTime(f).toInstant().isAfter(limit)) {
                    continue;
                }
                if (Files.deleteIfExists(f)) {
                    removed++;
                }
            } catch (IOException e) {
                // gone already, or not ours to remove: a leftover is harmless, a failed cleanup is not worth a run
            }
        }
        return removed;
    }

    /** The candidate exists, reads, and is the network after the planned last step of this configuration. */
    private static boolean candidateDone(Path candidate, Config cfg, String sha) {
        if (!Files.isRegularFile(candidate)) {
            return false;
        }
        try {
            Parameters p = Parameters.load(candidate);
            return p.config().equals(cfg.network()) && Long.toString(cfg.steps()).equals(p.extraMetadata().get("step"))
                    && sha.equals(p.extraMetadata().get("run_config_sha256"));
        } catch (IOException e) {
            return false;
        }
    }

    // ---- strength estimates inside the run ----

    /**
     * The training observer: at a checkpoint the cheap proxies on the held-out set (every {@code proxyEvery}-th
     * checkpoint) and, if asked for, the Elo estimate (every {@code eloEvery}-th). Both are measured by the monitor;
     * the time is inside the observer time of the loop, which is why {@link #spent()} is subtracted from it.
     */
    private static final class Observer implements TrainingObserver {
        private final Config cfg;
        private final Cadence cad;
        private final RunMonitor monitor;
        private final Parameters parameters;
        private final ReplayBuffer heldOut;
        private final Path strengthDir;
        private final BooleanSupplier stop;
        private final PrintStream err;
        private int checkpoints;
        private long lastProxies = Long.MIN_VALUE;
        private long lastElo = Long.MIN_VALUE;
        int estimates;
        private long wall;
        private long cpu;

        Observer(Config cfg, Cadence cad, RunMonitor monitor, Parameters parameters, ReplayBuffer heldOut,
                Path strengthDir, BooleanSupplier stop, PrintStream err) {
            this.cfg = cfg;
            this.cad = cad;
            this.monitor = monitor;
            this.parameters = parameters;
            this.heldOut = heldOut;
            this.strengthDir = strengthDir;
            this.stop = stop;
            this.err = err;
        }

        TrainingRun.Spent spent() {
            return new TrainingRun.Spent(wall, cpu);
        }

        @Override
        public void onCheckpoint(Path path, long wallNanos, long bytes) {
            try {
                long step = Checkpoint.stepOf(path);
                checkpoints++;
                boolean last = step == cfg.steps();
                long now = monitor.clock().wallNanos().getAsLong();
                boolean firstProxies = lastProxies == Long.MIN_VALUE;
                if (cad.proxyEvery() > 0 && (last || firstProxies || checkpoints % cad.proxyEvery() == 0
                        && now - lastProxies >= cad.proxySeconds() * 1e9)) {
                    proxies(step);
                    lastProxies = monitor.clock().wallNanos().getAsLong();
                }
                if (cad.eloEvery() > 0 && checkpoints % cad.eloEvery() == 0
                        && (lastElo == Long.MIN_VALUE || now - lastElo >= cad.eloSeconds() * 1e9)) {
                    elo(step);
                    lastElo = monitor.clock().wallNanos().getAsLong();
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private void add(TrainingRun.Spent s) {
            wall += s.wallNanos();
            cpu += s.cpuNanos();
        }

        private void proxies(long step) throws IOException {
            HeldOutMetrics.Metrics[] result = new HeldOutMetrics.Metrics[1];
            add(monitor.measure(RunMonitor.Category.STRENGTH, RunMonitor.PROXY, false, step,
                    () -> result[0] = HeldOutMetrics.evaluate(new Network(parameters), heldOut, cad.evalSamples(),
                            cad.threads())));
            HeldOutMetrics.Metrics m = result[0];
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("kind", RunMonitor.PROXY);
            f.put("step", step);
            f.put("samples", m.samples());
            f.put("kl", m.klPolicy());
            f.put("top1", m.top1());
            f.put("value_loss", m.valueLoss());
            f.put("winner_samples", m.winnerSamples());
            f.put("winner_accuracy", m.winnerAccuracy());
            monitor.event("metric", f);
            err.printf(Locale.ROOT, "gen 0 proxies at step %d (%d held-out samples): policy KL %.3f, top-1 %.1f%%,"
                    + " value loss %.3f, winner %.1f%% of %d (yardstick 78.8%%)%n", step, m.samples(), m.klPolicy(),
                    100 * m.top1(), m.valueLoss(), 100 * m.winnerAccuracy(), m.winnerSamples());
        }

        private void elo(long step) throws IOException {
            Path file = StrengthEstimate.file(strengthDir, step);
            if (StrengthEstimate.complete(file, cad.ladderBase(), cad.ladder(), cad.eloPairs(), cad.eloSeed())) {
                return;
            }
            List<StrengthEstimate.Rung>[] result = newResult();
            PairProgress pairs = new PairProgress(err, cad.progressSeconds(), cad.eloPairs() * cad.ladder().size(),
                    "gen 0 elo step " + step);
            add(monitor.measureAttempt(RunMonitor.Category.STRENGTH, RunMonitor.ELO, false, step, () -> {
                result[0] = StrengthEstimate.estimate(new Network(parameters), cad.ladderBase(), cad.ladder(),
                        cad.eloPairs(), cad.eloSeed(), cad.eloThreads(), stop, pairs);
                return result[0] != null;
            }));
            if (result[0] == null) {
                return; // stopped: the estimate is discarded, the loop sees the stop before its next step
            }
            estimates++;
            StrengthEstimate.write(file, step, result[0]);
            for (StrengthEstimate.Rung r : result[0]) {
                monitor.event("metric", metricFields(r, step));
                err.println("gen 0 elo at step " + step + ": " + r);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<StrengthEstimate.Rung>[] newResult() {
        return (List<StrengthEstimate.Rung>[]) new List<?>[1];
    }

    private static Map<String, Object> metricFields(StrengthEstimate.Rung r, long step) {
        Map<String, Object> f = new LinkedHashMap<>(r.fields(step));
        f.put("kind", RunMonitor.ELO);
        return f;
    }

    /** Progress lines of an estimate: pairs done, rate of this call, ETA, at most one per interval. */
    private static final class PairProgress implements java.util.function.IntConsumer {
        private final PrintStream err;
        private final long everyNanos;
        private final int total;
        private final String label;
        private final long t0 = System.nanoTime();
        private long last = t0;

        PairProgress(PrintStream err, double seconds, int total, String label) {
            this.err = err;
            this.everyNanos = Math.round(seconds * 1e9);
            this.total = total;
            this.label = label;
        }

        @Override
        public synchronized void accept(int done) {
            long now = System.nanoTime();
            if (now - last < everyNanos || done >= total) {
                return;
            }
            last = now;
            double el = (now - t0) / 1e9;
            double rate = done / el;
            err.printf(Locale.ROOT, "%s: %d/%d pairs  %.2f/s  ETA %s  elapsed %s%n", label, done, total, rate,
                    duration(Math.round((total - done) / rate)), duration(Math.round(el)));
        }
    }

    // ---- network files ----

    /** A network of this run: its parameters and the training step it stands for. */
    record NetworkFile(Parameters parameters, long step) {
    }

    /**
     * Reads a training checkpoint ({@code gen-0000/train/step-*.safetensors}) or a plain network file (parameters
     * only, such as {@code candidate.safetensors}) of the run whose {@code run.json} hashes to {@code runSha}. Both
     * carry the run's hash and the step: the checkpoint in its optimiser state, the network file in the metadata keys
     * {@code step} and {@code run_config_sha256} that this tool writes. A network file without them is refused,
     * because its run and its step are unknown.
     */
    static NetworkFile loadNetworkFile(Path file, String runSha) throws IOException {
        Checkpoint.Loaded checkpoint;
        try {
            checkpoint = Checkpoint.read(file);
        } catch (IOException notACheckpoint) {
            Parameters plain;
            try {
                plain = Parameters.load(file);
            } catch (IOException notANetwork) {
                notANetwork.addSuppressed(notACheckpoint);
                throw new IOException("neither a training checkpoint (" + notACheckpoint.getMessage()
                        + ") nor a network file (" + notANetwork.getMessage() + ")", notANetwork);
            }
            Map<String, String> md = plain.extraMetadata();
            String fileSha = md.get("run_config_sha256");
            String stepText = md.get("step");
            if (fileSha == null || stepText == null) {
                throw new IOException("a network file without the metadata run_config_sha256 and step; it does not"
                        + " say which run and step it belongs to");
            }
            if (!fileSha.equals(runSha)) {
                throw new IOException("belongs to another configuration than this run's run.json");
            }
            try {
                return new NetworkFile(plain, Long.parseLong(stepText));
            } catch (NumberFormatException e) {
                throw new IOException("step metadata is not a number: " + stepText, e);
            }
        }
        if (!checkpoint.runConfigSha256().equals(runSha)) {
            throw new IOException("belongs to another configuration than this run's run.json");
        }
        return new NetworkFile(checkpoint.parameters(), checkpoint.optimizer().steps());
    }

    // ---- the separate proxies step ----

    /**
     * {@code Gen0 proxies RUN --data DIR [--checkpoint FILE] [--samples N] [--threads T]}: the held-out proxies of one
     * network on N samples (default {@link #DEFAULT_EVAL_SAMPLES}), in this process, written to stdout. It reads the
     * data directory and the split of {@code run.json} (the data path is not part of the configuration, hence
     * {@code --data}; its fingerprint must match). Without {@code --checkpoint} the candidate is used, or the newest
     * checkpoint if there is none. It takes no lock and writes nothing.
     */
    private static int proxiesCommand(Map<String, String> opts, Path run, PrintStream out, PrintStream err)
            throws IOException {
        Path runJson = run.resolve("run.json");
        if (!Files.isRegularFile(runJson)) {
            err.println("gen0 proxies: no run.json in " + run + "; this is not a gen0 run directory");
            return EXIT_ERROR;
        }
        if (!opts.containsKey("data")) {
            throw new IllegalArgumentException("--data DIR is needed: run.json does not record where the data is");
        }
        int samples = integer(opts, "samples", DEFAULT_EVAL_SAMPLES);
        int threads = integer(opts, "threads", Math.min(4, Runtime.getRuntime().availableProcessors()));
        if (samples < 1 || threads < 1) {
            throw new IllegalArgumentException("--samples and --threads must be >= 1");
        }
        String runText = Files.readString(runJson);
        Map<String, String> cfg = RunMonitor.parse(runText);
        if (cfg == null || !cfg.containsKey("holdout") || !cfg.containsKey("data_sha256")) {
            err.println("gen0 proxies: " + runJson + " is not a run configuration");
            return EXIT_ERROR;
        }
        List<Path> files = Files.isDirectory(Path.of(opts.get("data"))) ? dataFiles(Path.of(opts.get("data")))
                : List.of();
        if (files.isEmpty() || !dataFingerprint(files).equals(cfg.get("data_sha256"))) {
            err.println("gen0 proxies: the files in " + opts.get("data") + " are not the data of this run"
                    + " (no samples-*.safetensors, or another fingerprint than run.json)");
            return EXIT_ERROR;
        }
        Path file;
        Path genDir = run.resolve("gen-0000");
        if (opts.containsKey("checkpoint")) {
            file = Path.of(opts.get("checkpoint"));
        } else if (Files.isRegularFile(genDir.resolve("candidate.safetensors"))) {
            file = genDir.resolve("candidate.safetensors");
        } else {
            List<Path> all = Checkpoint.list(genDir.resolve("train"));
            if (all.isEmpty()) {
                err.println("gen0 proxies: no candidate and no checkpoint in " + genDir);
                return EXIT_ERROR;
            }
            file = all.get(all.size() - 1);
        }
        NetworkFile net;
        try {
            net = loadNetworkFile(file, sha256Hex(runText.strip().getBytes(StandardCharsets.UTF_8)));
        } catch (IOException e) {
            err.println("gen0 proxies: cannot use " + file + ": " + e.getMessage());
            return EXIT_ERROR;
        }
        double holdout = Double.parseDouble(cfg.get("holdout"));
        ReplayBuffer held = new ReplayBuffer(2, Integer.MAX_VALUE, false);
        held.addGeneration(GENERATION, files, (m, id) -> heldOut(m, id, holdout));
        if (held.size() == 0) {
            err.println("gen0 proxies: the split leaves no held-out sample");
            return EXIT_ERROR;
        }
        HeldOutMetrics.Metrics m = HeldOutMetrics.evaluate(new Network(net.parameters()), held, samples, threads);
        double se = Math.sqrt(m.winnerAccuracy() * (1 - m.winnerAccuracy()) / Math.max(1, m.winnerSamples()));
        out.printf(Locale.ROOT, "step %d proxies on %d of %d held-out samples: policy KL %.3f, top-1 %.1f%%,"
                + " value loss %.3f, winner %.1f%% +- %.1f of %d (yardstick 78.8%%)%n", net.step(), m.samples(),
                held.size(), m.klPolicy(), 100 * m.top1(), m.valueLoss(), 100 * m.winnerAccuracy(), 100 * se,
                m.winnerSamples());
        return EXIT_OK;
    }

    // ---- the separate strength step ----

    private static int strength(Map<String, String> opts, Path run, PrintStream out, PrintStream err,
            BooleanSupplier testStop) throws Exception {
        if (opts.containsKey("status")) {
            return status(run, STRENGTH_JOB, out);
        }
        Cadence cad = cadence(opts);
        String player = opts.get("player");
        if (player != null && !NetPlayers.isNetPlayer(player)) {
            err.println("gen0 strength: --player must be raw or mcts:..., got " + player);
            return EXIT_USAGE;
        }
        if (NetPlayers.RAW.equals(player)) {
            player = null; // the default player: the usual file and the usual monitor lines
        }
        final String otherPlayer = player;
        Path runJson = run.resolve("run.json");
        if (!Files.isRegularFile(runJson)) {
            err.println("gen0 strength: no run.json in " + run + "; this is not a gen0 run directory");
            return EXIT_ERROR;
        }
        String sha = sha256Hex(Files.readString(runJson).strip().getBytes(StandardCharsets.UTF_8));
        Path trainDir = run.resolve("gen-0000").resolve("train");
        Path file;
        if (opts.containsKey("checkpoint")) {
            file = Path.of(opts.get("checkpoint"));
        } else {
            List<Path> all = Checkpoint.list(trainDir);
            if (all.isEmpty()) {
                err.println("gen0 strength: no checkpoint in " + trainDir);
                return EXIT_ERROR;
            }
            file = all.get(all.size() - 1);
        }
        try (FileChannel lockChannel = FileChannel.open(run.resolve("." + STRENGTH_JOB + ".lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE); FileLock lock = tryLock(lockChannel)) {
            if (lock == null) {
                status(run, STRENGTH_JOB, err);
                return EXIT_RUNNING;
            }
            String start = Instant.now().toString();
            Path statusFile = run.resolve("." + STRENGTH_JOB + ".status");
            writeStatus(statusFile, start, null);
            int cleanup = removeTemporaries(run.resolve("gen-0000").resolve("strength"), FOREIGN_TEMP_AGE);
            NetworkFile loaded;
            try {
                loaded = loadNetworkFile(file, sha);
            } catch (IOException e) {
                err.println("gen0 strength: cannot use " + file + ": " + e.getMessage());
                return EXIT_ERROR;
            }
            long step = loaded.step();
            Path result = otherPlayer == null ? StrengthEstimate.file(run.resolve("gen-0000").resolve("strength"), step)
                    : StrengthEstimate.playerFile(run.resolve("gen-0000").resolve("strength"), step, otherPlayer);
            if (StrengthEstimate.complete(result, cad.ladderBase(), cad.ladder(), cad.eloPairs(), cad.eloSeed())) {
                err.printf(Locale.ROOT, "gen 0 strength: 0 ok, 1 skip, 0 error, %d cleanup; step %d is estimated"
                        + " already; nothing more to do%n", cleanup, step);
                return EXIT_OK;
            }
            AtomicBoolean signalStop = new AtomicBoolean();
            BooleanSupplier stop = () -> signalStop.get() || testStop.getAsBoolean();
            CountDownLatch finished = new CountDownLatch(1);
            Thread hook = new Thread(() -> {
                signalStop.set(true);
                try {
                    finished.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, STRENGTH_JOB + "-shutdown");
            Runtime.getRuntime().addShutdownHook(hook);
            try {
                TrainingRun.Clock clock = TrainingRun.Clock.system();
                RunMonitor monitor = new RunMonitor(run.resolve("metrics.jsonl"), clock);
                PairProgress pairs = new PairProgress(err, cad.progressSeconds(), cad.eloPairs() * cad.ladder().size(),
                        "gen 0 strength step " + step);
                List<StrengthEstimate.Rung>[] holder = newResult();
                long wall0 = System.nanoTime();
                if (otherPlayer == null) {
                    monitor.measureAttempt(RunMonitor.Category.STRENGTH, RunMonitor.ELO, true, step, () -> {
                        holder[0] = StrengthEstimate.estimate(new Network(loaded.parameters()), cad.ladderBase(),
                                cad.ladder(), cad.eloPairs(), cad.eloSeed(), cad.eloThreads(), stop, pairs);
                        return holder[0] != null;
                    });
                } else {
                    // another player is not the run's strength metric: no monitor line, no budget accounting
                    holder[0] = StrengthEstimate.estimate(NetPlayers.factory(otherPlayer, loaded.parameters()),
                            cad.ladderBase(), cad.ladder(), cad.eloPairs(), cad.eloSeed(), cad.eloThreads(), stop,
                            pairs);
                }
                if (holder[0] == null) {
                    err.printf(Locale.ROOT, "gen 0 strength: 0 ok, 0 skip, 0 error, %d cleanup; aborted after %s, the"
                            + " estimate is discarded; run again to redo it%n", cleanup,
                            duration(Math.round((System.nanoTime() - wall0) / 1e9)));
                    return EXIT_ABORTED;
                }
                StrengthEstimate.write(result, step, holder[0]);
                for (StrengthEstimate.Rung r : holder[0]) {
                    if (otherPlayer == null) {
                        monitor.event("metric", metricFields(r, step));
                    }
                    out.println("step " + step + (otherPlayer == null ? " " : " [" + otherPlayer + "] ") + r);
                }
                err.printf(Locale.ROOT, "gen 0 strength: 1 ok, 0 skip, 0 error, %d cleanup; step %d in %s; nothing"
                        + " more to do%n", cleanup, step, duration(Math.round((System.nanoTime() - wall0) / 1e9)));
                return EXIT_OK;
            } finally {
                finished.countDown();
                try {
                    Runtime.getRuntime().removeShutdownHook(hook);
                } catch (IllegalStateException e) {
                    // shutting down because of a signal
                }
            }
        }
    }

    private static int monitorReport(Path run, PrintStream out, PrintStream err) {
        try {
            RunMonitor.print(RunMonitor.report(run), out);
            return EXIT_OK;
        } catch (IOException e) {
            err.println("monitor-report: " + e.getMessage());
            return EXIT_ERROR;
        }
    }
}
