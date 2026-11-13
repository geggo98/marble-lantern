package sternhalma.selfplay;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import sternhalma.engine.Geometry;
import sternhalma.nn.Checkpoint;
import sternhalma.nn.Encoder;
import sternhalma.nn.HexGrid;
import sternhalma.nn.LearningRateSchedule;
import sternhalma.nn.Network;
import sternhalma.nn.NetworkConfig;
import sternhalma.nn.Optimizer;
import sternhalma.nn.Parameters;
import sternhalma.nn.StepTrainer;
import sternhalma.nn.TrainingObserver;
import sternhalma.nn.TrainingRun;
import sternhalma.tornado.GpuTrainers;

/**
 * An offline capacity test (docs/training.md, "Capacity diagnostics 1"): networks of different sizes are trained from
 * scratch (random initialisation) on the same data, with the same seed, batches, schedule and number of steps, and are
 * compared on the same held-out games; every network then plays the gate of the loop against a reference network.
 *
 * <pre>
 *   Capacity --loop DIR --teacher DIR --best FILE --out DIR --nets SMALL=32x4,MID=48x6 --steps 30000
 *            [--first-generation 7] [--last-generation 13] [--lr 3e-4] [--lr-final 1e-5] [--batch 256] [--seed 1]
 *            [--stage train|gate|all] [--pairs 150] [--gate-sims 100] [--gate-seed 7001] [--threads 8]
 *            [--train-threads 4] [--eval-samples 12000] [--trainer gpu|cpu] [--checkpoint-steps 2500] [--tag NAME]
 * </pre>
 *
 * The training data is the window {@code first..last} of the loop's generations (the games that the loop does not hold
 * out, {@link Loop#heldOut}) plus every shard of {@code --teacher} as generation 0; the held-out games of the same
 * generations are the evaluation set (the same for all networks). {@code NAME=CxB} is C channels and B residual blocks
 * with the plane set of {@code --best}. {@code --best} is also the reference network of the gate; {@code --tag} names another gate (own shard directory and results file), for example
 * against one of the trained networks. Resumable: a candidate that exists is not trained again (a run that was
 * interrupted continues from its last checkpoint), a gate shard that exists is not played again. The result of every
 * network is one line of {@code OUT/results.jsonl}. One run at a time (exit 75 otherwise); 0 done, 1 error, 2 usage.
 */
final class Capacity {

    static final int EXIT_OK = 0;
    static final int EXIT_ERROR = 1;
    static final int EXIT_USAGE = 2;
    static final int EXIT_RUNNING = 75;

    private Capacity() {
    }

    /** The multiply-adds of one forward pass of one position: the stencil convolutions, the heads. */
    static long macs(NetworkConfig c) {
        long cells = Geometry.CELLS;
        long ch = c.channels();
        long stencil = HexGrid.STENCIL;
        long trunk = cells * (stencil * c.inputPlanes() * ch + (long) c.blocks() * 2 * stencil * ch * ch);
        long policy = cells * Encoder.POLICY_CHANNELS * ch;
        long value = (long) c.valueHidden() * ch + (long) c.players() * c.valueHidden();
        return trunk + policy + value;
    }

    public static void main(String[] args) throws Exception {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) throws Exception {
        Map<String, String> o = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--") || i + 1 >= args.length) {
                err.println("usage: Capacity --loop DIR --teacher DIR --best FILE --out DIR --nets NAME=CxB,... --steps N"
                        + " [options] (see the class comment)");
                return EXIT_USAGE;
            }
            o.put(args[i].substring(2), args[++i]);
        }
        try {
            return new Session(o, out, err).execute();
        } catch (IllegalArgumentException e) {
            err.println("usage error: " + e.getMessage());
            return EXIT_USAGE;
        }
    }

    private record Spec(String name, int channels, int blocks) {

        static Spec parse(String text) {
            String[] p = text.split("=", 2);
            String[] cb = p.length == 2 ? p[1].split("x") : new String[0];
            if (cb.length != 2) {
                throw new IllegalArgumentException("a network is NAME=CxB, got " + text);
            }
            return new Spec(p[0], Integer.parseInt(cb[0]), Integer.parseInt(cb[1]));
        }
    }

    private static final class Session {
        private final PrintStream out;
        private final PrintStream err;
        private final Path loop;
        private final Path teacher;
        private final Path bestFile;
        private final Path outDir;
        private final List<Spec> nets = new ArrayList<>();
        private final long steps;
        private final int firstGen;
        private final int lastGen;
        private final double lr;
        private final double lrFinal;
        private final int batch;
        private final long seed;
        private final String stage;
        private final int pairs;
        private final int gateSims;
        private final long gateSeed;
        private final int threads;
        private final int trainThreads;
        private final int evalSamples;
        private final String trainer;
        private final long checkpointSteps;
        private final String tag;
        private final LoopConfig cfg;
        private final Parameters best;
        private final String bestSha;

        Session(Map<String, String> o, PrintStream out, PrintStream err) throws IOException {
            this.out = out;
            this.err = err;
            for (String key : List.of("loop", "teacher", "best", "out", "nets", "steps")) {
                if (!o.containsKey(key)) {
                    throw new IllegalArgumentException("--" + key + " is required");
                }
            }
            loop = Path.of(o.get("loop"));
            teacher = Path.of(o.get("teacher"));
            bestFile = Path.of(o.get("best"));
            outDir = Path.of(o.get("out"));
            for (String n : o.get("nets").split(",")) {
                nets.add(Spec.parse(n.strip()));
            }
            steps = Long.parseLong(o.get("steps"));
            firstGen = LoopConfig.integer(o, "first-generation", 7);
            lastGen = LoopConfig.integer(o, "last-generation", 13);
            lr = LoopConfig.dbl(o, "lr", 3e-4);
            lrFinal = LoopConfig.dbl(o, "lr-final", 1e-5);
            batch = LoopConfig.integer(o, "batch", 256);
            seed = LoopConfig.lng(o, "seed", 1);
            stage = o.getOrDefault("stage", "all");
            pairs = LoopConfig.integer(o, "pairs", 150);
            gateSims = LoopConfig.integer(o, "gate-sims", 100);
            gateSeed = LoopConfig.lng(o, "gate-seed", 7001);
            threads = LoopConfig.integer(o, "threads", Math.min(8, Runtime.getRuntime().availableProcessors()));
            trainThreads = LoopConfig.integer(o, "train-threads", 4);
            evalSamples = LoopConfig.integer(o, "eval-samples", 12_000);
            trainer = o.getOrDefault("trainer", "gpu");
            checkpointSteps = LoopConfig.lng(o, "checkpoint-steps", 2500);
            tag = o.getOrDefault("tag", "");
            if (steps < 1 || firstGen < 1 || lastGen < firstGen || !(lr > 0) || lrFinal < 0 || lrFinal > lr || batch < 1
                    || pairs < 2 || threads < 1 || trainThreads < 1 || !List.of("train", "gate", "all").contains(stage)
                    || !trainer.equals("gpu") && !trainer.equals("cpu")) {
                throw new IllegalArgumentException("an option is out of range");
            }
            cfg = LoopConfig.fromJson(RunMonitor.parse(Files.readString(loop.resolve("run.json"))));
            best = Parameters.load(bestFile);
            bestSha = Gen0.sha256Hex(Files.readAllBytes(bestFile)).substring(0, 16);
        }

        int execute() throws Exception {
            Files.createDirectories(outDir);
            try (FileChannel channel = FileChannel.open(outDir.resolve(".capacity.lock"), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE)) {
                FileLock held;
                try {
                    held = channel.tryLock();
                } catch (OverlappingFileLockException e) {
                    held = null;
                }
                if (held == null) {
                    err.println("capacity: runs already (one at a time)");
                    return EXIT_RUNNING;
                }
                try {
                    return session();
                } finally {
                    held.release();
                }
            }
        }

        private String key() throws IOException {
            return String.format(Locale.ROOT, "random init; seed %d; generations %d-%d + teacher %s; lr %s cosine to %s; %d steps;"
                    + " batch %d; run %s", seed, firstGen, lastGen, Gen0.dataFingerprint(Loop.shards(teacher)).substring(0, 12), lr,
                    lrFinal, steps, batch, cfg.sha256().substring(0, 12));
        }

        private int session() throws Exception {
            ReplayBuffer window = new ReplayBuffer(2, 20_000_000, cfg.augment(), best.config().planes());
            ReplayBuffer heldAll = new ReplayBuffer(2, Integer.MAX_VALUE, false, best.config().planes());
            ReplayBuffer heldLast = new ReplayBuffer(2, Integer.MAX_VALUE, false, best.config().planes());
            if (!stage.equals("gate")) {
                List<Path> teacherFiles = Loop.shards(teacher);
                ReplayBuffer.Loaded t = window.addGeneration(0, teacherFiles);
                if (!t.skipped().isEmpty()) {
                    throw new IllegalStateException("teacher files do not read: " + t.skipped());
                }
                for (int gen = firstGen; gen <= lastGen; gen++) {
                    List<Path> files = Loop.shards(loop.resolve(String.format("gen-%04d", gen)).resolve("samples"));
                    if (files.isEmpty()) {
                        throw new IllegalArgumentException("generation " + gen + " has no samples in " + loop);
                    }
                    window.addGeneration(gen, files, (m, id) -> !Loop.heldOut(m, id, cfg.holdout(), cfg.games()));
                    heldAll.addGeneration(gen, files, (m, id) -> Loop.heldOut(m, id, cfg.holdout(), cfg.games()));
                    if (gen == lastGen) {
                        heldLast.addGeneration(gen, files, (m, id) -> Loop.heldOut(m, id, cfg.holdout(), cfg.games()));
                    }
                }
                long epochSamples = 2L * window.size(); // mirror augmentation doubles the distinct positions
                err.printf(Locale.ROOT, "capacity: window %d samples (teacher %d, generations %d-%d: %d), held out %d (last"
                        + " generation %d); %d steps of %d = %.2f passes over the samples (%.2f with the mirror images)%n",
                        window.size(), window.size(0), firstGen, lastGen, window.size() - window.size(0), heldAll.size(),
                        heldLast.size(), steps, batch, (double) steps * batch / window.size(),
                        (double) steps * batch / epochSamples);
            }
            for (Spec spec : nets) {
                Path dir = outDir.resolve(spec.name());
                Files.createDirectories(dir);
                Path candidate = dir.resolve("candidate.safetensors");
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("net", spec.name());
                NetworkConfig config = new NetworkConfig(spec.channels(), spec.blocks(), 2, NetworkConfig.VALUE_HIDDEN,
                        best.config().planes());
                row.put("channels", config.channels());
                row.put("blocks", config.blocks());
                row.put("parameters", config.parameterCount());
                row.put("macs_per_forward", macs(config));
                row.put("steps", steps);
                Parameters p;
                if (!stage.equals("gate")) {
                    p = trainOrLoad(spec, config, dir, candidate, window, row);
                    HeldOutMetrics.Metrics all = proxies(spec.name() + " (generations " + firstGen + "-" + lastGen + ")", p,
                            heldAll);
                    HeldOutMetrics.Metrics last = proxies(spec.name() + " (generation " + lastGen + ")", p, heldLast);
                    row.put("kl", all.klPolicy());
                    row.put("top1", all.top1());
                    row.put("value_loss", all.valueLoss());
                    row.put("winner_accuracy", all.winnerAccuracy());
                    row.put("held_out_samples", all.samples());
                    row.put("kl_last_generation", last.klPolicy());
                    row.put("top1_last_generation", last.top1());
                    row.put("value_loss_last_generation", last.valueLoss());
                    row.put("winner_accuracy_last_generation", last.winnerAccuracy());
                    Files.writeString(dir.resolve("train-row.json"), RunMonitor.json(row) + "\n", StandardCharsets.UTF_8);
                }
                if (!stage.equals("train")) {
                    if (!Files.isRegularFile(candidate)) {
                        err.println("capacity: no candidate for " + spec.name() + " yet (stage train first)");
                        return EXIT_ERROR;
                    }
                    p = Parameters.load(candidate);
                    if (Files.isRegularFile(dir.resolve("train-row.json"))) {
                        for (Map.Entry<String, String> e : RunMonitor.parse(Files.readString(dir.resolve("train-row.json"))).entrySet()) {
                            row.put(e.getKey(), number(e.getValue()));
                        }
                    }
                    int rc = gate(spec, dir, p, candidate, row);
                    if (rc != EXIT_OK) {
                        return rc;
                    }
                    Files.writeString(outDir.resolve(tag.isEmpty() ? "results.jsonl" : "results-" + tag + ".jsonl"), RunMonitor.json(row) + "\n", StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                }
            }
            return EXIT_OK;
        }

        private HeldOutMetrics.Metrics proxies(String what, Parameters p, ReplayBuffer held) {
            HeldOutMetrics.Metrics m = HeldOutMetrics.evaluate(new Network(p), held, evalSamples, threads);
            out.printf(Locale.ROOT, "proxies %-34s policy KL %.4f, top-1 %.2f%%, value loss %.4f, winner %.2f%% of %d (%d held-out"
                    + " samples)%n", what, m.klPolicy(), 100 * m.top1(), m.valueLoss(), 100 * m.winnerAccuracy(),
                    m.winnerSamples(), m.samples());
            return m;
        }

        /** The mean of the data-term losses of the last steps of the run. */
        private static final class Tail implements TrainingObserver {
            private final long from;
            private double loss;
            private double kl;
            private double value;
            private long n;

            Tail(long from) {
                this.from = from;
            }

            @Override
            public void onStep(long step, double l, double k, double v, long wallNanos) {
                if (step > from) {
                    loss += l;
                    kl += k;
                    value += v;
                    n++;
                }
            }
        }

        private Parameters trainOrLoad(Spec spec, NetworkConfig config, Path dir, Path candidate, ReplayBuffer window,
                Map<String, Object> row) throws IOException {
            String variantKey = key() + "; net " + spec.channels() + "x" + spec.blocks();
            if (Files.isRegularFile(candidate)) {
                Parameters existing = Parameters.load(candidate);
                if (variantKey.equals(existing.extraMetadata().get("capacity_key"))) {
                    err.println("capacity: " + spec.name() + ": candidate exists, skipped");
                    Map<String, String> info = existing.extraMetadata();
                    row.put("train_seconds", Double.parseDouble(info.getOrDefault("train_seconds", "NaN")));
                    row.put("steps_per_second", Double.parseDouble(info.getOrDefault("steps_per_second", "NaN")));
                    row.put("train_loss", Double.parseDouble(info.getOrDefault("train_loss", "NaN")));
                    row.put("train_kl", Double.parseDouble(info.getOrDefault("train_kl", "NaN")));
                    row.put("train_value_loss", Double.parseDouble(info.getOrDefault("train_value_loss", "NaN")));
                    return existing;
                }
                throw new IllegalStateException(candidate + " exists but was trained with another setup");
            }
            Path trainDir = dir.resolve("train");
            Files.createDirectories(trainDir);
            Checkpoint.Recovery recovery = Checkpoint.recover(trainDir);
            Parameters parameters;
            Optimizer optimizer;
            if (recovery.loaded() != null) {
                Checkpoint.Loaded loaded = recovery.loaded();
                if (!loaded.runConfigSha256().equals(Gen0.sha256Hex(variantKey.getBytes(StandardCharsets.UTF_8)))
                        || !loaded.parameters().config().equals(config)) {
                    throw new IllegalStateException("the checkpoint in " + trainDir + " belongs to another setup");
                }
                parameters = loaded.parameters();
                optimizer = loaded.optimizer();
                err.printf(Locale.ROOT, "capacity: %s: continues from the checkpoint at step %d%n", spec.name(), optimizer.steps());
            } else {
                parameters = Parameters.init(config, seed);
                optimizer = new Optimizer(parameters, Optimizer.Settings.adamw());
            }
            LearningRateSchedule schedule = new LearningRateSchedule(lr, 100, 1000, 0.1, cfg.warmup(), List.of(), steps,
                    lrFinal);
            TrainingRun.Setup setup = new TrainingRun.Setup(parameters, optimizer, schedule, window, batch, cfg.microbatch(),
                    trainThreads, cfg.valueCoefficient(), seed, 1, steps);
            TrainingRun.Checkpointing checkpointing = new TrainingRun.Checkpointing(trainDir, checkpointSteps, 15L * 60_000_000_000L,
                    1, Gen0.sha256Hex(variantKey.getBytes(StandardCharsets.UTF_8)));
            Tail tail = new Tail(Math.max(0, steps - 1000));
            TrainingRun.Reporting reporting = new TrainingRun.Reporting(List.<TrainingObserver>of(tail), err, 60_000_000_000L);
            StepTrainer stepTrainer = trainer.equals("gpu")
                    ? GpuTrainers.create(parameters, optimizer, batch, cfg.valueCoefficient())
                    : StepTrainer.cpu(parameters, optimizer, cfg.valueCoefficient(), cfg.microbatch(), trainThreads);
            err.printf(Locale.ROOT, "capacity: %s: %dx%d, %d parameters, %d MACs per forward pass, trainer %s%n", spec.name(),
                    config.channels(), config.blocks(), config.parameterCount(), macs(config), trainer);
            TrainingRun.Summary summary;
            try (stepTrainer) {
                summary = new TrainingRun(setup, checkpointing, reporting, TrainingRun.Clock.system(), () -> false, stepTrainer)
                        .run();
            }
            double seconds = summary.timing().loopWallNanos() / 1e9;
            double rate = summary.stepsDone() / seconds;
            err.printf(Locale.ROOT, "capacity: %s trained: %d steps in %.1f s (%.1f steps/s)%n", spec.name(), summary.stepsDone(),
                    seconds, rate);
            double trainLoss = tail.loss / Math.max(1, tail.n);
            double trainKl = tail.kl / Math.max(1, tail.n);
            double trainValue = tail.value / Math.max(1, tail.n);
            parameters.extraMetadata().put("step", Long.toString(optimizer.steps()));
            parameters.extraMetadata().put("capacity_key", variantKey);
            parameters.extraMetadata().put("train_seconds", Double.toString(seconds));
            parameters.extraMetadata().put("steps_per_second", Double.toString(rate));
            parameters.extraMetadata().put("train_loss", Double.toString(trainLoss));
            parameters.extraMetadata().put("train_kl", Double.toString(trainKl));
            parameters.extraMetadata().put("train_value_loss", Double.toString(trainValue));
            parameters.save(candidate);
            deleteTree(trainDir);
            row.put("train_seconds", seconds);
            row.put("steps_per_second", rate);
            row.put("train_loss", trainLoss);
            row.put("train_kl", trainKl);
            row.put("train_value_loss", trainValue);
            out.printf(Locale.ROOT, "train %-8s mean of the last %d steps: loss %.4f, policy KL %.4f, value loss %.4f%n", spec.name(),
                    tail.n, trainLoss, trainKl, trainValue);
            return parameters;
        }

        private int gate(Spec spec, Path dir, Parameters p, Path candidate, Map<String, Object> row) throws Exception {
            String candSha = Gen0.sha256Hex(Files.readAllBytes(candidate)).substring(0, 16);
            String player = "mcts:sims=" + gateSims + ",batch=1,cpuct=1.0,noise=0,temp=0,race=" + cfg.race();
            Path gateDir = dir.resolve("gate-" + gateSims + (tag.isEmpty() ? "" : "-" + tag));
            String identity = String.format(Locale.ROOT, "capacity-gate;cand=%s;best=%s;player=%s;seed=%d;opening=%d", candSha, bestSha,
                    player, gateSeed, cfg.gateOpening());
            Files.createDirectories(gateDir);
            Gate.PairJob job = new Gate.PairJob(gateDir, "vs-best", spec.name() + " against the reference network", identity, pairs,
                    cfg.pairShard(), threads, err, Gate.pairWorkers(gateSeed, cfg.gateOpening(),
                            NetPlayers.factory(player, p), NetPlayers.factory(player, best)), null, "gate");
            int rc = job.run();
            List<MctsMatch.PairLine> lines = rc == Job.EXIT_OK ? job.results() : null;
            if (lines == null) {
                err.println("capacity: the gate of " + spec.name() + " ended with exit " + rc);
                return rc != Job.EXIT_OK ? rc : EXIT_ERROR;
            }
            Gate.Decision d = Gate.decide(Gate.scores(lines), new double[0], new double[0], 0, 0);
            int wins = 0;
            int draws = 0;
            int losses = 0;
            long sCpu = 0;
            long sMoves = 0;
            long oCpu = 0;
            long oMoves = 0;
            for (MctsMatch.PairLine l : lines) {
                wins += l.wins();
                draws += l.draws();
                losses += l.losses();
                sCpu += l.subjectCpuNanos();
                sMoves += l.subjectMoves();
                oCpu += l.opponentCpuNanos();
                oMoves += l.opponentMoves();
            }
            double elo = sternhalma.search.Elo.fromScore(d.score());
            row.put("gate_sims", gateSims);
            row.put("reference", bestSha);
            row.put("pairs", d.pairs());
            row.put("wins", wins);
            row.put("draws", draws);
            row.put("losses", losses);
            row.put("score", d.score());
            row.put("score_se", d.scoreStandardError());
            row.put("elo", elo);
            row.put("subject_cpu_ms_per_move", sMoves == 0 ? Double.NaN : sCpu / 1e6 / sMoves);
            row.put("reference_cpu_ms_per_move", oMoves == 0 ? Double.NaN : oCpu / 1e6 / oMoves);
            out.printf(Locale.ROOT, "gate %-8s at %d simulations against the reference: score %.4f +- %.4f over %d pairs (%d-%d-%d games),"
                    + " Elo %+.0f, %.1f CPU-ms/move against %.1f%n", spec.name(), gateSims, d.score(), d.scoreStandardError(),
                    d.pairs(), wins, draws, losses, elo, sCpu / 1e6 / Math.max(1, sMoves), oCpu / 1e6 / Math.max(1, oMoves));
            return EXIT_OK;
        }
    }

    private static Object number(String text) {
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return text;
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            for (Path f : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(f);
            }
        }
    }
}
