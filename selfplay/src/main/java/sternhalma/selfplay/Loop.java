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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.LongFunction;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import sternhalma.nn.BatchSampler;
import sternhalma.nn.Checkpoint;
import sternhalma.nn.Network;
import sternhalma.nn.Optimizer;
import sternhalma.nn.Parameters;
import sternhalma.nn.StepTrainer;
import sternhalma.nn.TrainingObserver;
import sternhalma.nn.TrainingRun;
import sternhalma.tornado.GpuTrainers;
import sternhalma.search.Player;
import sternhalma.search.PlayerSpec;

/**
 * The training loop: self-play with the best network, training of a candidate, gating, and a strength record per
 * generation (docs/training.md, "Training loop" and "As implemented (block 2h)").
 *
 * <pre>
 *   Loop run RUN [--init NETWORK] [--generations 3] [--games 600] [--shard-games 25] [--sims 100] [--mcts-batch 1]
 *                [--cpuct 1.0] [--noise 0.25] [--alpha 0.3] [--temp 1.0] [--temp-plies 14] [--opening-min 4]
 *                [--opening-max 10] [--race 1] [--s1 1] [--seed 1]
 *                [--lr 3e-4] [--lr-final 0] [--warmup 100] [--batch 256] [--microbatch 32] [--reuse 4] [--window 500000] [--trainer cpu|gpu]
 *                [--augment 1] [--value-coefficient 1] [--holdout 0.1] [--teacher DIR [--teacher-files N]]
 *                [--gate-pairs 100] [--pair-shard 10] [--gate-opening 4] [--baseline-rung 1600]
 *                [--baseline-pairs 50] [--baseline-seed 4001] [--baseline-sigmas 2] [--promote-z 1.28]
 *                [--ladder-spec SPEC]
 *                [--strength-every 1] [--ladder 100,400,1600,6400] [--strength-pairs 50] [--strength-seed 3001]
 *                [--threads 8] [--train-threads T] [--checkpoint-steps 50] [--checkpoint-seconds 60] [--keep 2]
 *                [--progress-seconds 10] [--eval-samples 2500] [--proxy-every 1] [--proxy-seconds 120]
 *   Loop run --status RUN
 *   Loop strength RUN [--generation N] [--ladder ...] [--strength-pairs 50] [--strength-seed 3001] [--threads 8]
 *   Loop monitor-report RUN
 * </pre>
 *
 * <p>The run directory:
 * <pre>
 *   RUN/run.json                       the configuration, written once
 *   RUN/.lock, RUN/.status             lock (held by the process) and display status
 *   RUN/metrics.jsonl                  the monitor lines (display only)
 *   RUN/gen-0000/candidate.safetensors generation 0: the network of --init, promoted, decision.json says "init"
 *   RUN/gen-NNNN/samples/              self-play shards of generation NNNN
 *   RUN/gen-NNNN/train/                training checkpoints
 *   RUN/gen-NNNN/candidate.safetensors the candidate after the last planned step
 *   RUN/gen-NNNN/gate/                 vs-best-*.jsonl and vs-baseline-*.jsonl shards of the gate
 *   RUN/gen-NNNN/decision.json         promoted or rejected, with the statistics
 *   RUN/gen-NNNN/strength/             ab&lt;rung&gt;-*.jsonl shards of the strength record of the network of generation NNNN
 * </pre>
 * There is no pointer file: the best network is the candidate of the highest generation whose decision says
 * promoted.
 *
 * <p>Job conventions: the state comes from the artefacts (shards that read and name this run's settings; the newest
 * checkpoint that reads; the candidate; the decision); a second run after success does nothing and exits with 0, a
 * second process with 75, SIGINT with 130 and SIGTERM with 143 after the summary, 1 is an error, 2 a usage error.
 * Cadences, thread counts, the strength estimate and the number of generations are no part of the configuration.
 */
public final class Loop {

    static final int EXIT_OK = 0;
    static final int EXIT_ERROR = 1;
    static final int EXIT_USAGE = 2;
    static final int EXIT_RUNNING = 75;
    static final int EXIT_ABORTED = 130;

    /** A test hook: asked after every item of a phase (a game, a training step, a pair); true aborts like SIGINT. */
    @FunctionalInterface
    interface StopHook {
        /**
         * @param phase one of {@link Eta#PHASES}
         * @param done  items of this phase done by this process so far
         */
        boolean stop(String phase, long done);
    }

    /** How often things happen and how many threads there are. None of it changes a result. */
    record Cadence(int generations, long checkpointSteps, double checkpointSeconds, int keep, double progressSeconds,
            int evalSamples, int proxyEvery, double proxySeconds, int strengthEvery, List<Integer> ladder,
            int strengthPairs, long strengthSeed, int threads, int trainThreads) {

        static Cadence of(Map<String, String> o) {
            int threads = LoopConfig.integer(o, "threads", Math.min(8, Runtime.getRuntime().availableProcessors()));
            List<Integer> ladder = new ArrayList<>();
            for (String s : o.getOrDefault("ladder", "100,400,1600,6400").split(",")) {
                ladder.add(Integer.parseInt(s.strip()));
            }
            StrengthEstimate.requireDistinctRungs(ladder);
            Cadence c = new Cadence(LoopConfig.integer(o, "generations", 3), LoopConfig.lng(o, "checkpoint-steps", 50),
                    LoopConfig.dbl(o, "checkpoint-seconds", 60), LoopConfig.integer(o, "keep", 2),
                    LoopConfig.dbl(o, "progress-seconds", 10), LoopConfig.integer(o, "eval-samples", 2500),
                    LoopConfig.integer(o, "proxy-every", 1), LoopConfig.dbl(o, "proxy-seconds", 120),
                    LoopConfig.integer(o, "strength-every", 1), ladder, LoopConfig.integer(o, "strength-pairs", 50),
                    LoopConfig.lng(o, "strength-seed", 3001), threads, LoopConfig.integer(o, "train-threads", threads));
            if (c.generations < 0 || c.checkpointSteps < 1 || !(c.checkpointSeconds > 0) || c.keep < 1
                    || !(c.progressSeconds > 0) || c.evalSamples < 1 || c.proxyEvery < 0 || c.proxySeconds < 0
                    || c.strengthEvery < 0 || c.strengthPairs < 2 || c.threads < 1 || c.trainThreads < 1) {
                throw new IllegalArgumentException("a cadence or count option is out of range");
            }
            return c;
        }
    }

    private Loop() {
    }

    public static void main(String[] args) throws Exception {
        System.exit(run(args, System.out, System.err, (phase, done) -> false));
    }

    static int run(String[] args, PrintStream out, PrintStream err) throws Exception {
        return run(args, out, err, (phase, done) -> false);
    }

    /**
     * A test hook: a pair of a gate or baseline match ({@code prefix} is "vs-best" or "vs-baseline") that it answers is
     * not played; null plays it. This makes a promotion or a rejection a given and not a property of the networks.
     */
    @FunctionalInterface
    interface PairFake {
        MctsMatch.PairLine pair(String prefix, int generation, int pair);
    }

    static int run(String[] args, PrintStream out, PrintStream err, StopHook hook) throws Exception {
        return run(args, out, err, hook, null);
    }

    static int run(String[] args, PrintStream out, PrintStream err, StopHook hook, PairFake fake) throws Exception {
        if (args.length == 0) {
            err.println("usage: Loop run RUN | run --status RUN | strength RUN | monitor-report RUN (see the class comment)");
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
            if (positional.size() != 1) {
                throw new IllegalArgumentException("the run directory is missing (exactly one is expected)");
            }
            Path run = Path.of(positional.get(0));
            return switch (args[0]) {
                case "run" -> opts.containsKey("status")
                        ? Job.status(run, run.resolve(".lock"), run.resolve(".status"), out)
                        : new Session(run, opts, out, err, hook, fake).execute();
                case "strength" -> new Session(run, opts, out, err, hook, fake).strengthCommand();
                case "monitor-report" -> {
                    try {
                        RunMonitor.print(RunMonitor.report(run), out);
                        yield EXIT_OK;
                    } catch (IOException e) {
                        err.println("monitor-report: " + e.getMessage());
                        yield EXIT_ERROR;
                    }
                }
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

    /**
     * Whether a game belongs to the held-out set of a generation of {@code games} games (ids 0 to {@code games - 1}):
     * every {@code period}-th game id, {@code period = round(1 / fraction)} (at least 2), with an offset that is a
     * function of the shard's seed. A generation with fewer games than the period holds out the one game that the
     * seed picks. So a generation of two or more games always holds out at least one game and leaves at least one to
     * train on (a hash of the id would hold out any number of the few games of a small generation, none at times),
     * the split does not depend on the order or the number of the shards, and a game is in exactly one of the two
     * sets. A generation of one game cannot be split: nothing is held out.
     */
    static boolean heldOut(Map<String, String> metadata, int gameId, double fraction, int games) {
        long seed;
        try {
            seed = Long.parseLong(metadata.getOrDefault("seed", "0"));
        } catch (NumberFormatException e) {
            seed = metadata.getOrDefault("seed", "0").hashCode();
        }
        if (games < 2) {
            return false;
        }
        long period = Math.min(games, Math.max(2, Math.round(1 / fraction)));
        long offset = Math.floorMod(BatchSampler.mix(LoopConfig.SPLIT_SALT, 0, seed), period);
        return Math.floorMod(gameId - offset, period) == 0;
    }

    /** The files of a generation's samples, in name order. */
    static List<Path> shards(Path samples) throws IOException {
        if (!Files.isDirectory(samples)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(samples)) {
            return s.filter(f -> f.getFileName().toString().matches("samples-.*\\.safetensors")).sorted().toList();
        }
    }

    /** The best network at some point of a run. */
    record Best(int generation, Path file, Parameters parameters, String sha256) {
    }

    private static FileLock tryLock(FileChannel channel) throws IOException {
        try {
            return channel.tryLock();
        } catch (OverlappingFileLockException e) {
            return null; // held by this JVM
        }
    }

    private static final Pattern PROGRESS = Pattern.compile("(\\d+)/(\\d+) (?:items|steps)\\s+([0-9.]+)(?: steps)?/s");

    /** One run of the tool on one run directory. */
    private static final class Session {
        private final Path run;
        private final Map<String, String> opts;
        private final PrintStream out;
        private final PrintStream stderr;
        private final StopHook hook;
        private final PairFake fake;
        private final Cadence cad;
        private final AtomicBoolean signalStop = new AtomicBoolean();
        private final TrainingRun.Clock clock = TrainingRun.Clock.system();
        private final Sink sink = new Sink();
        private final PrintStream log = new PrintStream(sink, true, StandardCharsets.UTF_8);
        private LoopConfig cfg;
        private String cfgSha;
        private RunMonitor monitor;
        private Eta eta;
        private String start;
        private volatile String phase;
        private volatile int generation;
        private volatile long trainUnits;
        /** Whether the gate of the running generation has shown a significant score (above 0.5 by promote-z standard errors) (or the generation is decided). */
        private volatile boolean optionalDue;
        private boolean worked;

        Session(Path run, Map<String, String> opts, PrintStream out, PrintStream err, StopHook hook, PairFake fake) {
            this.run = run;
            this.opts = opts;
            this.out = out;
            this.stderr = err;
            this.hook = hook;
            this.fake = fake;
            this.cad = Cadence.of(opts);
        }

        // ---- progress lines, status, ETA ----

        /** Lines to stderr and the status file; every progress line gets the ETA of the generation and the run. */
        private final class Sink extends OutputStream {
            private final ByteArrayOutputStream line = new ByteArrayOutputStream();
            private volatile long stepLines;

            /** Progress lines of the training loop printed so far. */
            long progressLines() {
                return stepLines;
            }

            @Override
            public synchronized void write(int b) {
                if (b != '\n') {
                    line.write(b);
                    return;
                }
                String text = line.toString(StandardCharsets.UTF_8);
                line.reset();
                Matcher m = PROGRESS.matcher(text);
                if (text.contains(" steps/s ")) {
                    stepLines++;
                }
                if (phase != null && m.find()) {
                    double remaining = Double.parseDouble(m.group(2)) - Double.parseDouble(m.group(1));
                    int after = Math.max(0, cad.generations() - generation);
                    text += "  | " + estimate(phase, remaining, Double.parseDouble(m.group(3)), after).text(after);
                }
                stderr.println(text);
                if (text.startsWith("gen ") || text.startsWith("loop")) {
                    writeStatus(text);
                }
            }
        }

        private void writeStatus(String progress) {
            try {
                Path file = run.resolve(".status");
                Path tmp = run.resolve(".status.tmp");
                Files.writeString(tmp, "pid=" + ProcessHandle.current().pid() + " start=" + start + "\n"
                        + (progress == null ? "" : progress + "\n"));
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                // the status file is for display only
            }
        }

        /**
         * The estimate of the running generation and the ones to come. Baseline and strength of the running generation
         * count once its gate score is significant (the candidate is then promoted unless the baseline clause says no);
         * before that the estimate leaves them out and says so (a lower bound). The further generations count them.
         */
        private Eta.Estimate estimate(String phase, double remaining, double rate, int after) {
            Map<String, Double> further = unitsPerGeneration();
            Map<String, Double> thisGeneration = new HashMap<>(further);
            boolean pending = false;
            if (!optionalDue) {
                for (String optional : List.of("baseline", "strength")) {
                    Double units = thisGeneration.remove(optional);
                    pending |= units != null && units > 0;
                }
            }
            return eta.estimate(phase, remaining, rate, thisGeneration, further, after, pending);
        }

        /** The units of each phase in one generation, for the ETA of the generations to come. */
        private Map<String, Double> unitsPerGeneration() {
            Map<String, Double> u = new HashMap<>();
            u.put("selfplay", (double) cfg.games());
            u.put("train", (double) trainUnits);
            u.put("gate", (double) cfg.gatePairs());
            u.put("baseline", (double) cfg.baselinePairs());
            if (cad.strengthEvery() > 0) {
                u.put("strength", (double) cad.ladder().size() * cad.strengthPairs() / cad.strengthEvery());
            }
            return u;
        }

        private boolean stopped() {
            return signalStop.get();
        }

        private long nanos() {
            return clock.wallNanos().getAsLong();
        }

        // ---- paths and state from the artefacts ----

        private Path genDir(int g) {
            return run.resolve(String.format(Locale.ROOT, "gen-%04d", g));
        }

        private Path candidateFile(int g) {
            return genDir(g).resolve("candidate.safetensors");
        }

        private Path decisionFile(int g) {
            return genDir(g).resolve("decision.json");
        }

        /** The decision of generation {@code g} if its file reads and says promoted or rejected, else null. */
        private Map<String, String> decision(int g) {
            try {
                Path f = decisionFile(g);
                if (!Files.isRegularFile(f)) {
                    return null;
                }
                Map<String, String> m = RunMonitor.parse(Files.readString(f));
                if (m == null || !Integer.toString(g).equals(m.get("generation"))
                        || !("promoted".equals(m.get("decision")) || "rejected".equals(m.get("decision")))) {
                    return null;
                }
                return m;
            } catch (IOException e) {
                return null;
            }
        }

        /** The highest generation g such that every generation 0..g has a decision; -1 if none. */
        private int lastDecided() {
            int g = -1;
            while (decision(g + 1) != null) {
                g++;
            }
            return g;
        }

        private Best best(int upTo) throws IOException {
            for (int g = upTo; g >= 0; g--) {
                Map<String, String> d = decision(g);
                if (d != null && d.get("decision").equals("promoted")) {
                    Path f = candidateFile(g);
                    Parameters p = Parameters.load(f);
                    return new Best(g, f, p, Gen0.sha256Hex(Files.readAllBytes(f)));
                }
            }
            throw new IOException("no promoted generation up to " + upTo + " in " + run);
        }

        // ---- the run ----

        int execute() throws Exception {
            Files.createDirectories(run);
            try (FileChannel lockChannel = FileChannel.open(run.resolve(".lock"), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE); FileLock lock = tryLock(lockChannel)) {
                if (lock == null) {
                    Job.status(run, run.resolve(".lock"), run.resolve(".status"), stderr);
                    return EXIT_RUNNING;
                }
                return locked();
            }
        }

        /** The configuration of the options, with the init network and the teacher data it names. */
        private LoopConfig configuration() throws IOException {
            Path runJson = run.resolve("run.json");
            String init;
            if (opts.containsKey("init")) {
                init = Gen0.sha256Hex(Files.readAllBytes(Path.of(opts.get("init"))));
            } else if (Files.isRegularFile(runJson)) {
                Map<String, String> m = RunMonitor.parse(Files.readString(runJson));
                if (m == null || !m.containsKey("init_sha256")) {
                    throw new IllegalArgumentException(runJson + " is not a run configuration");
                }
                init = m.get("init_sha256");
            } else {
                throw new IllegalArgumentException("a new run needs --init NETWORK (the network of generation 0)");
            }
            String teacherSha = "";
            int teacherFiles = 0;
            if (opts.containsKey("teacher")) {
                List<Path> all = shards(Path.of(opts.get("teacher")));
                int n = Math.min(all.size(), LoopConfig.integer(opts, "teacher-files", all.size()));
                if (n < 1) {
                    throw new IllegalArgumentException("no samples-*.safetensors in " + opts.get("teacher"));
                }
                List<Path> files = all.subList(0, n);
                teacherSha = Gen0.dataFingerprint(files);
                teacherFiles = n;
            }
            Map<String, String> effective = opts;
            if ("gpu".equals(opts.get("trainer"))) {
                String why = GpuTrainers.unavailableReason();
                if (why != null) {
                    // decided here, so that run.json names the trainer that ran; a run that run.json calls gpu is not
                    // continued on the CPU: its configuration then differs and the run is refused
                    stderr.println("loop: --trainer gpu: " + why + "; training the candidates on the CPU instead");
                    effective = new HashMap<>(opts);
                    effective.put("trainer", "cpu");
                }
            }
            return LoopConfig.of(effective, init, teacherSha, teacherFiles);
        }

        private int locked() throws Exception {
            start = Instant.now().toString();
            writeStatus(null);
            long t0 = System.nanoTime();
            cfg = configuration();
            cfgSha = cfg.sha256();
            Path runJson = run.resolve("run.json");
            String text = cfg.json() + "\n";
            if (Files.exists(runJson)) {
                if (!Files.readString(runJson).equals(text)) {
                    stderr.println("loop: " + runJson + " holds another configuration; use another run directory or delete"
                            + " this one.\n  existing: " + Files.readString(runJson).strip() + "\n  wanted:   " + cfg.json());
                    return EXIT_ERROR;
                }
            } else {
                Path tmp = run.resolve("run.json.tmp");
                Files.writeString(tmp, text);
                Files.move(tmp, runJson, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            monitor = new RunMonitor(run.resolve("metrics.jsonl"), clock);
            eta = Eta.fromMetrics(monitor.file());
            trainUnits = Math.round(eta.lastUnits("train"));
            int cleanup = cleanTemporaries();
            int rc = initGenerationZero();
            if (rc != EXIT_OK) {
                return rc;
            }
            int last = lastDecided();
            log.printf(Locale.ROOT, "loop: %s; %d generation(s) decided, target %d; best = generation %d; %d cleanup%n",
                    run, last + 1, cad.generations(), best(last).generation(), cleanup);

            CountDownLatch finished = new CountDownLatch(1);
            Thread shutdown = new Thread(() -> {
                signalStop.set(true);
                try {
                    finished.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "loop-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdown);
            int exit = EXIT_OK;
            try {
                for (int g = last; g <= cad.generations() && exit == EXIT_OK; g++) {
                    exit = iteration(g, g > last);
                }
                if (exit == EXIT_OK) {
                    log.printf(Locale.ROOT, "loop: %d generations decided, best = generation %d, %s; nothing more to do%n",
                            lastDecided() + 1, best(lastDecided()).generation(), Job.human((System.nanoTime() - t0) / 1e9));
                } else if (exit == EXIT_ABORTED) {
                    log.printf(Locale.ROOT, "loop: aborted after %s; %d generation(s) decided; run again to continue%n",
                            Job.human((System.nanoTime() - t0) / 1e9), lastDecided() + 1);
                }
            } finally {
                finished.countDown();
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdown);
                } catch (IllegalStateException e) {
                    // shutting down because of a signal; the JVM ends with 128 + signal
                }
            }
            return exit;
        }

        /**
         * Removes the leftovers of interrupted writes anywhere in the run directory ({@code *.tmp}, {@code *.tmp.<pid>}).
         * The run directory is ours (the lock), except the strength directories, which a separate strength process
         * writes into under its own lock: a temporary file there must be old before it is a leftover.
         */
        private int cleanTemporaries() throws IOException {
            int removed = 0;
            List<Path> candidates;
            try (Stream<Path> s = Files.walk(run)) {
                candidates = s.filter(f -> Files.isRegularFile(f) && f.getFileName().toString().contains(".tmp")
                        && !f.getFileName().toString().equals(".status.tmp")).toList();
            }
            Instant limit = Instant.now().minus(Gen0.FOREIGN_TEMP_AGE);
            for (Path f : candidates) {
                try {
                    boolean foreign = run.relativize(f).toString().contains("/strength/");
                    if (foreign && Files.getLastModifiedTime(f).toInstant().isAfter(limit)) {
                        continue;
                    }
                    removed += Files.deleteIfExists(f) ? 1 : 0;
                } catch (IOException e) {
                    // gone already, or not ours to remove
                }
            }
            return removed;
        }

        /** Generation 0 is a given network: its candidate, copied once, and a decision that says so. */
        private int initGenerationZero() throws IOException {
            Path candidate = candidateFile(0);
            Files.createDirectories(genDir(0));
            if (!Files.isRegularFile(candidate)) {
                if (!opts.containsKey("init")) {
                    stderr.println("loop: " + candidate + " is missing; give --init NETWORK");
                    return EXIT_ERROR;
                }
                Parameters p = Parameters.load(Path.of(opts.get("init"))); // reads and validates
                if (p.config().players() != 2) {
                    stderr.println("loop: the loop plays two players; the init network has " + p.config().players());
                    return EXIT_ERROR;
                }
                Path tmp = candidate.resolveSibling("candidate.safetensors.init.tmp");
                Files.copy(Path.of(opts.get("init")), tmp, StandardCopyOption.REPLACE_EXISTING);
                Files.move(tmp, candidate, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            String sha = Gen0.sha256Hex(Files.readAllBytes(candidate));
            if (!sha.equals(cfg.initSha256())) {
                stderr.println("loop: " + candidate + " is not the network of this run's configuration");
                return EXIT_ERROR;
            }
            if (decision(0) == null) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("generation", 0);
                f.put("decision", "promoted");
                f.put("init", true);
                f.put("init_sha256", sha);
                writeAtomically(decisionFile(0), RunMonitor.json(f) + "\n");
            }
            return EXIT_OK;
        }

        private static void writeAtomically(Path file, String text) throws IOException {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }

        /**
         * Generation {@code g}: self-play, training, gating when {@code fresh}; then its strength record. A
         * generation that is decided already only gets the strength record that may still be missing.
         */
        private int iteration(int g, boolean fresh) throws Exception {
            if (stopped()) {
                return EXIT_ABORTED; // a signal came between two phases: no job may start while the JVM shuts down
            }
            generation = g;
            worked = false;
            optionalDue = !fresh; // a decided generation has shown what it is; a fresh one shows it at its gate
            long w0 = nanos();
            int rc = EXIT_OK;
            try {
                if (fresh) {
                    Best best = best(g - 1);
                    rc = selfPlay(g, best);
                    if (rc == EXIT_OK) {
                        rc = train(g, best);
                    }
                    if (rc == EXIT_OK) {
                        rc = gate(g, best);
                    }
                }
                if (rc == EXIT_OK) {
                    rc = strength(g);
                }
            } finally {
                phase = null;
                if (worked) {
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("gen", g);
                    f.put("wall_ns", nanos() - w0);
                    f.put("done", rc == EXIT_OK ? 1 : 0);
                    // the generations of the mean in the report: a decided, promoted generation after generation 0
                    // (generation 0 has a strength record and nothing else; a rejected one has no baseline and no record)
                    Map<String, String> decided = decision(g);
                    f.put("full", rc == EXIT_OK && g > 0 && decided != null && "promoted".equals(decided.get("decision")) ? 1 : 0);
                    monitor.event("gen", f);
                }
            }
            if (rc == EXIT_OK && fresh) {
                Map<String, String> d = decision(g);
                log.printf(Locale.ROOT, "gen %d: %s; best = generation %d%n", g, d.get("decision"), best(g).generation());
            }
            return rc;
        }

        /** Maps the exit code of a sub-job and records the phase; returns the exit code of the loop. */
        private int endPhase(String name, int g, int rc, long units, long wallNanos, long cpuNanos,
                RunMonitor.Category category, String kind) throws IOException {
            if (units > 0) {
                worked = true;
                eta.observe(name, units, wallNanos / 1e9);
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("gen", g);
                f.put("phase", name);
                f.put("units", units);
                f.put("wall_ns", wallNanos);
                f.put("complete", rc == EXIT_OK);
                monitor.event("phase", f);
                if (rc == EXIT_OK) {
                    monitor.cost(category, kind, false, g, category == RunMonitor.Category.STRENGTH ? 1 : units, wallNanos,
                            cpuNanos);
                } else {
                    monitor.cost(category, kind + "-partial", false, g, 0, wallNanos, cpuNanos);
                }
                if (rc == EXIT_OK) {
                    int after = Math.max(0, cad.generations() - g);
                    Eta.Estimate e = estimate(name, 0, 0, after);
                    log.printf(Locale.ROOT, "gen %d %s phase: %d done in %s (%.2f/s); %s%n", g, name, units,
                            Job.human(wallNanos / 1e9), units / (wallNanos / 1e9), e.text(after));
                }
            }
            return switch (rc) {
                case Job.EXIT_OK -> stopped() ? EXIT_ABORTED : EXIT_OK;
                case Job.EXIT_ABORTED -> EXIT_ABORTED;
                case Job.EXIT_RUNNING -> EXIT_RUNNING;
                default -> EXIT_ERROR;
            };
        }

        // ---- self-play ----

        private int selfPlay(int g, Best best) throws Exception {
            if (stopped()) {
                return EXIT_ABORTED;
            }
            Path dir = genDir(g).resolve("samples");
            Files.createDirectories(dir);
            LoopGamesJob job = new LoopGamesJob(dir, g, cfg.settings(g), best.sha256(), best.parameters(), cfg.games(),
                    cfg.shardGames(), cad.threads(), log, (p, n) -> hook.stop(p, n));
            job.label = "gen " + g + " selfplay";
            job.progressSeconds = cad.progressSeconds();
            phase = "selfplay";
            log.printf(Locale.ROOT, "gen %d selfplay: best network = generation %d, %d games, %s%n", g, best.generation(),
                    cfg.games(), cfg.settings(g).describe());
            long w0 = nanos();
            int rc = job.run();
            return endPhase("selfplay", g, rc, job.doneItems, nanos() - w0, job.cpuNanos(),
                    RunMonitor.Category.SELFPLAY, "");
        }

        // ---- training ----

        private record Data(ReplayBuffer train, ReplayBuffer heldOut, int cleanup) {
        }

        /** The replay window up to generation {@code g} (training games) and the held-out games of {@code g}. */
        private Data load(int g) throws IOException {
            ReplayBuffer train = new ReplayBuffer(2, cfg.window(), cfg.augment(), best(0).parameters().config().planes());
            ReplayBuffer held = new ReplayBuffer(2, Integer.MAX_VALUE, false, best(0).parameters().config().planes());
            if (cfg.teacherFiles() > 0) {
                List<Path> all = shards(Path.of(opts.get("teacher")));
                List<Path> files = all.subList(0, cfg.teacherFiles());
                if (!Gen0.dataFingerprint(files).equals(cfg.teacherSha256())) {
                    throw new IllegalArgumentException("the teacher shards are not those of run.json");
                }
                ReplayBuffer.Loaded loaded = train.addGeneration(0, files);
                if (!loaded.skipped().isEmpty()) {
                    throw new IllegalStateException("teacher files do not read: " + loaded.skipped());
                }
            }
            double holdout = cfg.holdout();
            int expected = (cfg.games() + cfg.shardGames() - 1) / cfg.shardGames();
            for (int gen = 1; gen <= g; gen++) {
                List<Path> files = shards(genDir(gen).resolve("samples"));
                if (files.size() != expected) {
                    throw new IllegalStateException("generation " + gen + " has " + files.size() + " of " + expected
                            + " sample shards; the replay window needs all of them (run again after the self-play of"
                            + " that generation is complete, or delete the later generations)");
                }
                ReplayBuffer.Loaded loaded = train.addGeneration(gen, files,
                        (m, id) -> !heldOut(m, id, holdout, cfg.games()));
                if (!loaded.skipped().isEmpty()) {
                    throw new IllegalStateException("sample files do not read: " + loaded.skipped());
                }
                if (gen == g) {
                    held.addGeneration(gen, files, (m, id) -> heldOut(m, id, holdout, cfg.games()));
                }
            }
            return new Data(train, held, 0);
        }

        private boolean candidateDone(int g) {
            Path file = candidateFile(g);
            if (!Files.isRegularFile(file)) {
                return false;
            }
            try {
                Map<String, String> md = Parameters.load(file).extraMetadata();
                return cfgSha.equals(md.get("run_config_sha256")) && Integer.toString(g).equals(md.get("generation"))
                        && md.containsKey("new_samples") && md.get("step") != null
                        && Long.toString(cfg.plannedSteps(Long.parseLong(md.get("new_samples")))).equals(md.get("step"));
            } catch (IOException | RuntimeException e) {
                return false;
            }
        }

        private int train(int g, Best best) throws Exception {
            phase = "train";
            Path trainDir = genDir(g).resolve("train");
            Path candidate = candidateFile(g);
            if (candidateDone(g)) {
                log.printf(Locale.ROOT, "gen %d train: 0 ok, %s skip, 0 error, 0 cleanup; 0 left; nothing more to do%n", g,
                        Parameters.load(candidate).extraMetadata().get("step"));
                return EXIT_OK;
            }
            Files.createDirectories(trainDir);
            long t0 = System.nanoTime();
            Data data;
            try {
                data = load(g);
            } catch (IllegalStateException | IllegalArgumentException e) {
                stderr.println("gen " + g + " train: " + e.getMessage());
                return EXIT_ERROR;
            }
            long newSamples = data.train().size(g);
            if (newSamples == 0 || data.heldOut().size() == 0) {
                stderr.println("gen " + g + " train: the generation has " + newSamples + " training and "
                        + data.heldOut().size() + " held-out samples; use more games or another --holdout");
                return EXIT_ERROR;
            }
            long planned = cfg.plannedSteps(newSamples);
            trainUnits = planned;
            int cleanup = 0;
            Checkpoint.Recovery recovery = Checkpoint.recover(trainDir);
            cleanup += recovery.quarantined().size();
            Parameters parameters;
            Optimizer optimizer;
            if (recovery.loaded() != null) {
                Checkpoint.Loaded loaded = recovery.loaded();
                if (!loaded.runConfigSha256().equals(cfgSha) || loaded.generation() != g
                        || !loaded.parameters().config().equals(best.parameters().config())) {
                    stderr.println("gen " + g + " train: the newest checkpoint belongs to another configuration");
                    return EXIT_ERROR;
                }
                parameters = loaded.parameters();
                optimizer = loaded.optimizer();
            } else {
                // the candidate starts from the best network's parameters, with a fresh optimiser: the step count of
                // the optimiser is at once Adam's t, the index of the learning-rate schedule and the sampler step
                parameters = Parameters.load(best.file());
                optimizer = new Optimizer(parameters, Optimizer.Settings.adamw());
            }
            long startStep = optimizer.steps();
            if (startStep > planned) {
                stderr.println("gen " + g + " train: the checkpoint is at step " + startStep + ", the plan is " + planned);
                return EXIT_ERROR;
            }
            log.printf(Locale.ROOT, "gen %d load: %d new training samples (%d held out), window %d samples in generations"
                    + " %s, plan %d steps (reuse %s, batch %d), start at step %d, %d cleanup, %.1f s%n", g, newSamples,
                    data.heldOut().size(), data.train().size(), data.train().generations(), planned, cfg.reuse(),
                    cfg.batch(), startStep, cleanup, (System.nanoTime() - t0) / 1e9);

            long[] stepsHere = {0};
            BooleanSupplier stop = () -> signalStop.get() || hook.stop("train", stepsHere[0]++);
            long linesBefore = sink.progressLines();
            Observer observer = new Observer(g, planned, parameters, data.heldOut());
            TrainingRun.Setup setup = new TrainingRun.Setup(parameters, optimizer, cfg.schedule(planned), data.train(),
                    cfg.batch(), cfg.microbatch(), cad.trainThreads(), cfg.valueCoefficient(), cfg.seed(), g, planned);
            TrainingRun.Checkpointing checkpointing = new TrainingRun.Checkpointing(trainDir, cad.checkpointSteps(),
                    Math.round(cad.checkpointSeconds() * 1e9), cad.keep(), cfgSha);
            TrainingRun.Reporting reporting = new TrainingRun.Reporting(List.<TrainingObserver>of(observer), log,
                    Math.round(cad.progressSeconds() * 1e9));
            TrainingRun.Summary summary;
            try (StepTrainer stepTrainer = stepTrainer(g, parameters, optimizer)) {
                TrainingRun trainingRun = new TrainingRun(setup, checkpointing, reporting, clock, stop, stepTrainer);
                summary = trainingRun.run();
            } catch (IllegalStateException e) {
                // a NaN or infinite loss, gradient or parameter: the trainer may have applied the update (the GPU
                // trainer always has), so the run ends here, without a checkpoint of the bad step
                stderr.println("gen " + g + " train: stopped, " + e.getMessage() + "; the newest checkpoint is the last"
                        + " sound state");
                return EXIT_ERROR;
            }
            TrainingRun.Timing timing = summary.timing();
            monitor.recordRun(timing, cfg.batch(), observer.spent(), false, sink.progressLines() - linesBefore);
            if (summary.stepsDone() > 0) {
                worked = true;
                long ownSteps = summary.stepsDone(); // the steps of this process: the cost per step is measured on them alone
                eta.observe("train", ownSteps, timing.loopWallNanos() / 1e9);
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("gen", g);
                f.put("phase", "train");
                f.put("units", ownSteps);
                f.put("wall_ns", timing.loopWallNanos());
                f.put("complete", !summary.aborted());
                monitor.event("phase", f);
            }
            double rate = timing.loopWallNanos() > 0 ? summary.stepsDone() / (timing.loopWallNanos() / 1e9) : 0;
            if (summary.aborted()) {
                log.printf(Locale.ROOT, "gen %d train: aborted at step %d of %d (%.2f steps/s); run again to continue%n", g,
                        summary.endStep(), planned, rate);
                return EXIT_ABORTED;
            }
            parameters.extraMetadata().put("step", Long.toString(optimizer.steps()));
            parameters.extraMetadata().put("run_config_sha256", cfgSha);
            parameters.extraMetadata().put("generation", Integer.toString(g));
            parameters.extraMetadata().put("parent", Integer.toString(best.generation()));
            parameters.extraMetadata().put("new_samples", Long.toString(newSamples));
            parameters.save(candidate);
            log.printf(Locale.ROOT, "gen %d train: %d steps in %s, %.2f steps/s = %.1f samples/s (%d checkpoints, %d proxy"
                    + " evaluations); candidate written, %s%n", g, summary.stepsDone(),
                    Job.human(timing.loopWallNanos() / 1e9), rate, rate * cfg.batch(), summary.checkpointsWritten(),
                    observer.evaluations, candidate);
            return EXIT_OK;
        }

        /**
         * The trainer of the run's configuration: the CPU reference trainer, or the device trainer, which holds the
         * parameters and the optimiser state on the device and copies them back at every checkpoint.
         */
        private StepTrainer stepTrainer(int g, Parameters parameters, Optimizer optimizer) {
            if (cfg.trainer().equals("gpu")) {
                log.printf(Locale.ROOT, "gen %d train: GPU trainer on %s, batch %d in executions of %d%n", g,
                        GpuTrainers.description(), cfg.batch(), GpuTrainers.microbatchFor(cfg.batch()));
                return GpuTrainers.create(parameters, optimizer, cfg.batch(), cfg.valueCoefficient());
            }
            return StepTrainer.cpu(parameters, optimizer, cfg.valueCoefficient(), cfg.microbatch(), cad.trainThreads());
        }

        /**
         * The proxies at the last checkpoint of the generation and else at least {@code proxySeconds} after the previous
         * evaluation (or the start of the training): one evaluation per generation of the demo's length, which keeps
         * them inside their 2 % budget (a first version also evaluated at the first checkpoint and spent 2.9 %).
         */
        private final class Observer implements TrainingObserver {
            private final int g;
            private final long planned;
            private final Parameters parameters;
            private final ReplayBuffer heldOut;
            private int checkpoints;
            private long lastProxies = nanos();
            int evaluations;
            private long wall;
            private long cpu;

            Observer(int g, long planned, Parameters parameters, ReplayBuffer heldOut) {
                this.g = g;
                this.planned = planned;
                this.parameters = parameters;
                this.heldOut = heldOut;
            }

            TrainingRun.Spent spent() {
                return new TrainingRun.Spent(wall, cpu);
            }

            @Override
            public void onCheckpoint(Path path, long wallNanos, long bytes) {
                try {
                    long step = Checkpoint.stepOf(path);
                    checkpoints++;
                    long now = nanos();
                    if (cad.proxyEvery() > 0 && (step == planned || checkpoints % cad.proxyEvery() == 0
                            && now - lastProxies >= cad.proxySeconds() * 1e9)) {
                        proxies(step);
                        lastProxies = nanos();
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }

            private void proxies(long step) throws IOException {
                HeldOutMetrics.Metrics[] result = new HeldOutMetrics.Metrics[1];
                TrainingRun.Spent s = monitor.measure(RunMonitor.Category.STRENGTH, RunMonitor.PROXY, false, step,
                        () -> result[0] = HeldOutMetrics.evaluate(new Network(parameters), heldOut, cad.evalSamples(),
                                cad.trainThreads()));
                wall += s.wallNanos();
                cpu += s.cpuNanos();
                evaluations++;
                HeldOutMetrics.Metrics m = result[0];
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("kind", RunMonitor.PROXY);
                f.put("gen", g);
                f.put("step", step);
                f.put("samples", m.samples());
                f.put("kl", m.klPolicy());
                f.put("top1", m.top1());
                f.put("value_loss", m.valueLoss());
                f.put("winner_samples", m.winnerSamples());
                f.put("winner_accuracy", m.winnerAccuracy());
                monitor.event("metric", f);
                log.printf(Locale.ROOT, "gen %d proxies at step %d (%d held-out samples): policy KL %.3f, top-1 %.1f%%,"
                        + " value loss %.3f, winner %.1f%% of %d%n", g, step, m.samples(), m.klPolicy(), 100 * m.top1(),
                        m.valueLoss(), 100 * m.winnerAccuracy(), m.winnerSamples());
            }
        }

        // ---- players ----

        private LongFunction<Player> network(Parameters parameters) {
            return NetPlayers.factory(cfg.evalPlayer(), parameters);
        }

        private LongFunction<Player> rung(int nodes) {
            PlayerSpec spec = PlayerSpec.parse(StrengthEstimate.spec(cfg.ladderSpec(), nodes));
            return seed -> spec.create();
        }

        private static String shaOf(Path file) throws IOException {
            return Gen0.sha256Hex(Files.readAllBytes(file)).substring(0, 16);
        }

        /** The end of a match: the exit code of the loop (0: the pairs are in order, else null) and the pairs. */
        private record Played(int exit, List<MctsMatch.PairLine> pairs) {
        }

        private Played match(Path dir, String prefix, String label, String identity, int pairs, long seed,
                int openingPlies, LongFunction<Player> subject, LongFunction<Player> opponent, RunMonitor.Category category,
                String kind, String eloPhase, int g) throws IOException, InterruptedException {
            if (stopped()) {
                return new Played(EXIT_ABORTED, null);
            }
            Files.createDirectories(dir);
            Supplier<Job.Worker<MctsMatch.PairLine>> played = Gate.pairWorkers(seed, openingPlies, subject, opponent);
            if (fake != null) {
                Supplier<Job.Worker<MctsMatch.PairLine>> real = played;
                played = () -> {
                    Job.Worker<MctsMatch.PairLine> worker = real.get();
                    return pair -> {
                        MctsMatch.PairLine given = fake.pair(prefix, g, pair);
                        return given != null ? given : worker.run(pair);
                    };
                };
            }
            Gate.PairJob job = new Gate.PairJob(dir, prefix, label, identity, pairs, cfg.pairShard(), cad.threads(), log,
                    played, hook, eloPhase);
            job.progressSeconds = cad.progressSeconds();
            phase = eloPhase;
            long w0 = nanos();
            int rc = job.run();
            int exit = endPhase(eloPhase, g, rc, job.doneItems, nanos() - w0, job.cpuNanos.get(), category, kind);
            return new Played(exit, exit == EXIT_OK ? job.results() : null);
        }

        // ---- gating ----

        private int gate(int g, Best best) throws Exception {
            Parameters cand = Parameters.load(candidateFile(g));
            String candSha = shaOf(candidateFile(g));
            String bestSha = best.sha256().substring(0, 16);
            Path gateDir = genDir(g).resolve("gate");
            log.printf(Locale.ROOT, "gen %d gate: candidate %s against best (generation %d) %s, %d pairs of %s%n", g, candSha,
                    best.generation(), bestSha, cfg.gatePairs(), cfg.evalPlayer());
            String identity = String.format(Locale.ROOT, "gate;g=%d;cand=%s;best=%s;player=%s;seed=%d;opening=%d", g,
                    candSha, bestSha, cfg.evalPlayer(), cfg.gateSeed(g), cfg.gateOpening());
            Played vs = match(gateDir, "vs-best", "gen " + g + " gate", identity, cfg.gatePairs(),
                    cfg.gateSeed(g), cfg.gateOpening(), network(cand), network(best.parameters()),
                    RunMonitor.Category.GATE, "vs-best", "gate", g);
            if (vs.exit() != EXIT_OK) {
                return vs.exit();
            }
            double[] gateScores = Gate.scores(vs.pairs());
            double[] candBase = new double[0];
            double[] bestBase = new double[0];
            boolean skipped = false;
            boolean aboveHalf = Gate.decide(gateScores, new double[0], new double[0], cfg.baselineSigmas(),
                    cfg.promoteZ()).promoted();
            optionalDue = aboveHalf;
            if (cfg.baselinePairs() > 0) {
                if (aboveHalf) {
                    Played c = baseline(g, cand, candSha, genDir(g));
                    if (c.exit() != EXIT_OK) {
                        return c.exit();
                    }
                    Played b = baseline(best.generation(), best.parameters(), bestSha, genDir(best.generation()));
                    if (b.exit() != EXIT_OK) {
                        return b.exit();
                    }
                    candBase = Gate.scores(c.pairs());
                    bestBase = Gate.scores(b.pairs());
                } else {
                    skipped = true; // not above 0.5: rejected whatever the rung says
                }
            }
            Gate.Decision d = Gate.decide(gateScores, candBase, bestBase, cfg.baselineSigmas(), cfg.promoteZ());
            int wins = 0;
            int draws = 0;
            int losses = 0;
            for (MctsMatch.PairLine l : vs.pairs()) {
                wins += l.wins();
                draws += l.draws();
                losses += l.losses();
            }
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("generation", g);
            f.put("decision", d.promoted() ? "promoted" : "rejected");
            f.put("best_before", best.generation());
            f.put("candidate_sha256", candSha);
            f.put("best_sha256", bestSha);
            f.put("sims", cfg.sims());
            f.put("pairs", d.pairs());
            f.put("wins", wins);
            f.put("draws", draws);
            f.put("losses", losses);
            f.put("score", d.score());
            f.put("score_se", d.scoreStandardError());
            f.put("ci95_low", d.ci95Low());
            f.put("ci95_high", d.ci95High());
            f.put("promote_z", d.promoteZ());
            f.put("z", d.z());
            f.put("baseline_rung", cfg.baselineRung());
            f.put("baseline_pairs", d.baselinePairs());
            f.put("baseline_skipped", skipped);
            f.put("baseline_candidate", d.baselineCandidate());
            f.put("baseline_best", d.baselineBest());
            f.put("baseline_diff", d.baselineDiff());
            f.put("baseline_diff_se", d.baselineDiffStandardError());
            f.put("baseline_sigmas", d.baselineSigmas());
            f.put("baseline_ok", d.baselineOk());
            writeAtomically(decisionFile(g), RunMonitor.json(f) + "\n");
            Map<String, Object> metric = new LinkedHashMap<>(f);
            metric.put("kind", "gate");
            monitor.event("metric", metric);
            log.println("gen " + g + " decision: " + d.describe());
            return EXIT_OK;
        }

        /** The matches of the network of generation {@code owner} against the fixed rung, in that generation's gate directory. */
        private Played baseline(int owner, Parameters network, String sha, Path ownerDir)
                throws IOException, InterruptedException {
            String identity = String.format(Locale.ROOT, "baseline;net=%s;rung=%d;ladder=%s;player=%s;seed=%d;opening=%d", sha,
                    cfg.baselineRung(), Gen0.sha256Hex(cfg.ladderSpec().getBytes(StandardCharsets.UTF_8)).substring(0, 12),
                    cfg.evalPlayer(), cfg.baselineSeed(), cfg.gateOpening());
            return match(ownerDir.resolve("gate"), "vs-baseline", "gen " + generation + " baseline of generation "
                    + owner, identity, cfg.baselinePairs(), cfg.baselineSeed(), cfg.gateOpening(), network(network),
                    rung(cfg.baselineRung()), RunMonitor.Category.GATE, "baseline", "baseline", generation);
        }

        // ---- strength ----

        /** After generation {@code g}'s decision: the record of the best network, if one is due. */
        private int strength(int g) throws Exception {
            if (cad.strengthEvery() <= 0 || g % cad.strengthEvery() != 0 || cad.ladder().isEmpty()) {
                return EXIT_OK;
            }
            return strengthOf(best(g), g);
        }

        private int strengthOf(Best best, int g) throws Exception {
            Path dir = genDir(best.generation()).resolve("strength");
            long w0 = nanos();
            long done = 0;
            long cpu = 0;
            Map<Integer, List<MctsMatch.PairLine>> results = new TreeMap<>();
            for (int rung : cad.ladder()) {
                if (stopped()) {
                    return endPhase("strength", g, Job.EXIT_ABORTED, done, nanos() - w0, cpu, RunMonitor.Category.STRENGTH,
                            RunMonitor.ELO);
                }
                String identity = String.format(Locale.ROOT, "strength;net=%s;rung=%d;ladder=%s;player=%s;seed=%d;opening=%d",
                        best.sha256().substring(0, 16), rung,
                        Gen0.sha256Hex(cfg.ladderSpec().getBytes(StandardCharsets.UTF_8)).substring(0, 12), cfg.evalPlayer(),
                        cad.strengthSeed(), StrengthEstimate.OPENING_PLIES);
                Files.createDirectories(dir);
                Gate.PairJob job = new Gate.PairJob(dir, "ab" + rung, "gen " + g + " strength vs " + rung + " nodes",
                        identity, cad.strengthPairs(), cfg.pairShard(), cad.threads(), log,
                        Gate.pairWorkers(cad.strengthSeed(), StrengthEstimate.OPENING_PLIES, network(best.parameters()),
                                rung(rung)), hook, "strength");
                job.progressSeconds = cad.progressSeconds();
                phase = "strength";
                int rc = job.run();
                done += job.doneItems;
                cpu += job.cpuNanos.get();
                if (rc != Job.EXIT_OK || stopped()) {
                    return endPhase("strength", g, rc != Job.EXIT_OK ? rc : Job.EXIT_ABORTED, done, nanos() - w0, cpu,
                            RunMonitor.Category.STRENGTH, RunMonitor.ELO);
                }
                List<MctsMatch.PairLine> lines = job.results();
                if (lines == null) {
                    stderr.println("gen " + g + " strength: the shards of " + rung + " nodes do not read");
                    return EXIT_ERROR;
                }
                results.put(rung, lines);
            }
            int rc = endPhase("strength", g, Job.EXIT_OK, done, nanos() - w0, cpu, RunMonitor.Category.STRENGTH,
                    RunMonitor.ELO);
            boolean emit = done > 0 || !hasEloMetric(g);
            for (Map.Entry<Integer, List<MctsMatch.PairLine>> e : results.entrySet()) {
                Map<Integer, MctsMatch.PairLine> byPair = new TreeMap<>();
                for (MctsMatch.PairLine l : e.getValue()) {
                    byPair.put(l.pair(), l);
                }
                MctsMatch.Summary s = MctsMatch.summarize(cfg.evalPlayer(), "ab:" + e.getKey(), cad.strengthPairs(),
                        cad.strengthSeed(), byPair);
                StrengthEstimate.Rung r = new StrengthEstimate.Rung(e.getKey(),
                        StrengthEstimate.spec(cfg.ladderSpec(), e.getKey()), s.pairs(), s.seed(), s.score(),
                        s.scoreStandardError(), s.elo(), s.eloConfidence95(), s.wins(), s.draws(), s.losses(),
                        StrengthEstimate.clamped(s.score()));
                log.println("gen " + g + " strength (best = generation " + best.generation() + "): " + r);
                if (emit) {
                    Map<String, Object> f = new LinkedHashMap<>(r.fields(g));
                    f.put("kind", RunMonitor.ELO);
                    f.put("gen", g);
                    f.put("best", best.generation());
                    monitor.event("metric", f);
                }
            }
            return rc;
        }

        /** Whether the metrics file has the Elo lines of generation {@code g} already (display only: no line twice). */
        private boolean hasEloMetric(int g) {
            try {
                for (String line : Files.readAllLines(monitor.file(), StandardCharsets.UTF_8)) {
                    Map<String, String> m = RunMonitor.parse(line);
                    if (m != null && "metric".equals(m.get("event")) && RunMonitor.ELO.equals(m.get("kind"))
                            && Integer.toString(g).equals(m.get("gen"))) {
                        return true;
                    }
                }
            } catch (IOException e) {
                // no metrics yet
            }
            return false;
        }

        /** {@code Loop strength RUN [--generation N]}: the strength record of the best network up to N, as a separate step. */
        int strengthCommand() throws Exception {
            Path runJson = run.resolve("run.json");
            if (!Files.isRegularFile(runJson)) {
                stderr.println("loop strength: no run.json in " + run);
                return EXIT_ERROR;
            }
            Map<String, String> m = RunMonitor.parse(Files.readString(runJson));
            cfg = LoopConfig.fromJson(m);
            cfgSha = cfg.sha256();
            if (!Files.readString(runJson).equals(cfg.json() + "\n")) {
                stderr.println("loop strength: " + runJson + " is not a configuration of this version");
                return EXIT_ERROR;
            }
            try (FileChannel lockChannel = FileChannel.open(run.resolve(".strength.lock"), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE); FileLock lock = tryLock(lockChannel)) {
                if (lock == null) {
                    stderr.println("loop strength: running already");
                    return EXIT_RUNNING;
                }
                start = Instant.now().toString();
                monitor = new RunMonitor(run.resolve("metrics.jsonl"), clock);
                eta = Eta.fromMetrics(monitor.file());
                int up = opts.containsKey("generation") ? Integer.parseInt(opts.get("generation")) : lastDecided();
                if (up < 0 || up > lastDecided()) {
                    stderr.println("loop strength: generation " + up + " is not decided (last decided: " + lastDecided() + ")");
                    return EXIT_ERROR;
                }
                generation = up;
                CountDownLatch finished = new CountDownLatch(1);
                Thread shutdown = new Thread(() -> {
                    signalStop.set(true);
                    try {
                        finished.await(60, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }, "strength-shutdown");
                Runtime.getRuntime().addShutdownHook(shutdown);
                try {
                    int rc = strengthOf(best(up), up);
                    if (rc == EXIT_OK) {
                        log.println("loop strength: nothing more to do");
                    }
                    return rc;
                } finally {
                    finished.countDown();
                    try {
                        Runtime.getRuntime().removeShutdownHook(shutdown);
                    } catch (IllegalStateException e) {
                        // shutting down because of a signal
                    }
                }
            }
        }
    }
}
