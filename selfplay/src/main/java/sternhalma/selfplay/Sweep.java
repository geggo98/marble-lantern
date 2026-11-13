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
import java.util.TreeMap;
import java.util.function.LongFunction;

import sternhalma.nn.LearningRateSchedule;
import sternhalma.nn.Network;
import sternhalma.nn.Optimizer;
import sternhalma.nn.Parameters;
import sternhalma.nn.StepTrainer;
import sternhalma.nn.TrainingObserver;
import sternhalma.nn.TrainingRun;
import sternhalma.search.Player;
import sternhalma.search.PlayerSpec;
import sternhalma.tornado.GpuTrainers;

/**
 * An offline hyperparameter sweep on the data of a finished loop run (docs/training.md, "Loop run 2 and hyperparameter
 * sweep 1"): every variant trains a candidate from the same start network, on the same replay window and with the same
 * seeds as the loop's generation {@code G} (only the learning-rate schedule, the number of steps and the value
 * coefficient differ), reports the held-out proxies of the loop, plays the gate of the loop against the start network
 * (the same player, opening scheme and pair job) and, on request, the strength record against the fixed rungs.
 * Everything is resumable: a candidate, a gate shard and a strength shard that exist are skipped.
 *
 * <pre>
 *   Sweep --loop DIR --out DIR --variants BASE,A,C,NAME:lr=1e-4/final=1e-6/steps=15000/cv=2
 *         [--generation 3] [--best FILE] [--pairs 200] [--gate-generation G] [--tag NAME]
 *         [--strength-rungs 25600,102400] [--strength-pairs 100] [--strength-seed 3001]
 *         [--trainer gpu|cpu] [--threads 8] [--eval-samples 2500]
 * </pre>
 *
 * A variant is a name from {@link #BUILT_IN} or {@code NAME:key=value/...} with the keys {@code lr} (default the loop's),
 * {@code final} (cosine to this rate over the steps; default none, constant), {@code steps} (0 or missing: the loop's
 * planned steps of generation {@code G}) and {@code cv} (value coefficient). {@code --pairs 0} trains without a gate.
 * {@code --gate-generation} names the generation whose gate openings are played (default {@code G}, the loop's own;
 * another value gives fresh openings: the confirmation). The result of every variant is appended to
 * {@code OUT/results.jsonl}. One sweep runs at a time (exit 75 otherwise); 0 is done, 1 an error, 2 a usage error.
 */
final class Sweep {

    static final int EXIT_OK = 0;
    static final int EXIT_ERROR = 1;
    static final int EXIT_USAGE = 2;
    static final int EXIT_RUNNING = 75;

    /** The variants of sweep 1: constant rates, cosine decays, more steps, a heavier value loss. Steps 0: the planned ones. */
    static final Map<String, String> BUILT_IN = Map.of(
            "BASE", "BASE:",
            "A", "A:lr=1e-4",
            "B", "B:lr=3e-5",
            "C", "C:final=1e-5",
            "D", "D:lr=1e-3/final=1e-5",
            "E", "E:steps=15000",
            "F", "F:final=1e-5/steps=15000",
            "G", "G:lr=1e-4/final=1e-6/steps=15000",
            "H", "H:final=1e-5/cv=2");

    private Sweep() {
    }

    /**
     * One variant.
     *
     * @param lr      base rate after the warm-up
     * @param finalLr rate at the end of a cosine over the steps; 0: constant
     * @param steps   training steps; 0: the planned steps of the loop's generation
     * @param cv      value coefficient
     */
    record Variant(String name, double lr, double finalLr, long steps, double cv) {

        static Variant parse(String text, LoopConfig cfg) {
            String spec = BUILT_IN.getOrDefault(text, text);
            int colon = spec.indexOf(':');
            if (colon < 1) {
                throw new IllegalArgumentException("unknown variant " + text + " (NAME:key=value/... or one of "
                        + new TreeMap<>(BUILT_IN).keySet() + ")");
            }
            double lr = cfg.lr();
            double finalLr = 0;
            long steps = 0;
            double cv = cfg.valueCoefficient();
            for (String kv : spec.substring(colon + 1).split("/")) {
                if (kv.isEmpty()) {
                    continue;
                }
                String[] p = kv.split("=", 2);
                if (p.length != 2) {
                    throw new IllegalArgumentException("bad variant item " + kv);
                }
                switch (p[0]) {
                    case "lr" -> lr = Double.parseDouble(p[1]);
                    case "final" -> finalLr = Double.parseDouble(p[1]);
                    case "steps" -> steps = Long.parseLong(p[1]);
                    case "cv" -> cv = Double.parseDouble(p[1]);
                    default -> throw new IllegalArgumentException("unknown variant key " + p[0]);
                }
            }
            if (!(lr > 0) || finalLr < 0 || steps < 0 || !(cv > 0)) {
                throw new IllegalArgumentException("variant " + text + " is out of range");
            }
            return new Variant(spec.substring(0, colon), lr, finalLr, steps, cv);
        }

        long stepsFor(long planned) {
            return steps > 0 ? steps : planned;
        }

        /** The schedule: the loop's warm-up, then constant, or a cosine to {@code finalLr} over all the steps. */
        LearningRateSchedule schedule(LoopConfig cfg, long planned) {
            return new LearningRateSchedule(lr, 100, 1000, 0.1, cfg.warmup(), List.of(),
                    finalLr > 0 ? stepsFor(planned) : 0, finalLr);
        }

        String describe(long planned) {
            return String.format(Locale.ROOT, "lr %s%s, %d steps, value coefficient %s", lr,
                    finalLr > 0 ? " cosine to " + finalLr : " constant", stepsFor(planned), cv);
        }
    }

    public static void main(String[] args) throws Exception {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) throws Exception {
        Map<String, String> o = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--") || i + 1 >= args.length) {
                err.println("usage: Sweep --loop DIR --out DIR --variants LIST [options] (see the class comment)");
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

    private static final class Session {
        private final PrintStream out;
        private final PrintStream err;
        private final Path loop;
        private final Path outDir;
        private final int g;
        private final Path bestFile;
        private final int pairs;
        private final int gateGeneration;
        private final String tag;
        private final List<Integer> rungs = new ArrayList<>();
        private final int strengthPairs;
        private final long strengthSeed;
        private final String trainer;
        private final int threads;
        private final int evalSamples;
        private final String variants;
        private final LoopConfig cfg;
        private final Parameters best;
        private final String bestSha;

        Session(Map<String, String> o, PrintStream out, PrintStream err) throws IOException {
            this.out = out;
            this.err = err;
            if (!o.containsKey("loop") || !o.containsKey("out") || !o.containsKey("variants")) {
                throw new IllegalArgumentException("--loop, --out and --variants are required");
            }
            loop = Path.of(o.get("loop"));
            outDir = Path.of(o.get("out"));
            g = LoopConfig.integer(o, "generation", 3);
            bestFile = o.containsKey("best") ? Path.of(o.get("best")) : loop.resolve("gen-0000").resolve("candidate.safetensors");
            pairs = LoopConfig.integer(o, "pairs", 200);
            gateGeneration = LoopConfig.integer(o, "gate-generation", g);
            tag = o.getOrDefault("tag", gateGeneration == g ? "gate" : "gate-g" + gateGeneration);
            for (String s : o.getOrDefault("strength-rungs", "").split(",")) {
                if (!s.isBlank()) {
                    rungs.add(Integer.parseInt(s.strip()));
                }
            }
            StrengthEstimate.requireDistinctRungs(rungs);
            strengthPairs = LoopConfig.integer(o, "strength-pairs", 100);
            strengthSeed = LoopConfig.lng(o, "strength-seed", 3001);
            trainer = o.getOrDefault("trainer", "gpu");
            threads = LoopConfig.integer(o, "threads", Math.min(8, Runtime.getRuntime().availableProcessors()));
            evalSamples = LoopConfig.integer(o, "eval-samples", 2500);
            variants = o.get("variants");
            if (g < 1 || pairs < 0 || pairs == 1 || strengthPairs < 2 || threads < 1 || evalSamples < 1
                    || !trainer.equals("gpu") && !trainer.equals("cpu")) {
                throw new IllegalArgumentException("an option is out of range");
            }
            cfg = LoopConfig.fromJson(RunMonitor.parse(Files.readString(loop.resolve("run.json"))));
            best = Parameters.load(bestFile);
            bestSha = Gen0.sha256Hex(Files.readAllBytes(bestFile)).substring(0, 16);
        }

        int execute() throws Exception {
            Files.createDirectories(outDir);
            try (FileChannel channel = FileChannel.open(outDir.resolve(".sweep.lock"), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE)) {
                FileLock held;
                try {
                    held = channel.tryLock();
                } catch (OverlappingFileLockException e) {
                    held = null;
                }
                if (held == null) {
                    err.println("sweep: runs already (one at a time: the gates need all the threads)");
                    return EXIT_RUNNING;
                }
                try {
                    return sweep();
                } finally {
                    held.release();
                }
            }
        }

        private int sweep() throws Exception {
            ReplayBuffer train = new ReplayBuffer(2, cfg.window(), cfg.augment(), best.config().planes());
            ReplayBuffer held = new ReplayBuffer(2, Integer.MAX_VALUE, false, best.config().planes());
            for (int gen = 1; gen <= g; gen++) {
                List<Path> files = Loop.shards(loop.resolve(String.format("gen-%04d", gen)).resolve("samples"));
                if (files.isEmpty()) {
                    throw new IllegalArgumentException("generation " + gen + " has no samples in " + loop);
                }
                train.addGeneration(gen, files, (m, id) -> !Loop.heldOut(m, id, cfg.holdout(), cfg.games()));
                if (gen == g) {
                    held.addGeneration(gen, files, (m, id) -> Loop.heldOut(m, id, cfg.holdout(), cfg.games()));
                }
            }
            long newSamples = train.size(g);
            long planned = cfg.plannedSteps(newSamples);
            err.printf(Locale.ROOT, "sweep: start network %s, window generations %s (%d samples), %d new samples (%d held out), planned"
                    + " steps %d, trainer %s%n", bestSha, train.generations(), train.size(), newSamples, held.size(), planned,
                    trainer);
            List<Variant> list = new ArrayList<>();
            for (String name : variants.split(",")) {
                list.add(Variant.parse(name.strip(), cfg));
            }
            proxies("start network", best, held);
            for (Variant v : list) {
                Path dir = outDir.resolve(v.name());
                Files.createDirectories(dir);
                Path candidate = dir.resolve("candidate.safetensors");
                String desc = v.describe(planned);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("variant", v.name());
                row.put("setup", desc);
                err.println("sweep: variant " + v.name() + ": " + desc);
                String variantKey = desc + "; start " + bestSha + "; window " + g + "; run " + cfg.sha256().substring(0, 12);
                Parameters p = null;
                if (Files.isRegularFile(candidate)) {
                    Parameters existing = Parameters.load(candidate);
                    if (variantKey.equals(existing.extraMetadata().get("sweep_variant"))) {
                        p = existing;
                        err.println("sweep: variant " + v.name() + ": candidate exists, skipped");
                    }
                }
                if (p == null) {
                    p = train(v, dir, train, planned, variantKey);
                    p.save(candidate);
                }
                HeldOutMetrics.Metrics m = proxies(v.name(), p, held);
                row.put("candidate_sha256", Gen0.sha256Hex(Files.readAllBytes(candidate)).substring(0, 16));
                row.put("kl", m.klPolicy());
                row.put("top1", m.top1());
                row.put("value_loss", m.valueLoss());
                row.put("winner_accuracy", m.winnerAccuracy());
                if (pairs > 0) {
                    int rc = gate(v, dir, p, candidate, row);
                    if (rc != EXIT_OK) {
                        return rc;
                    }
                }
                for (int rung : rungs) {
                    int rc = strength(v, dir, p, candidate, rung, row);
                    if (rc != EXIT_OK) {
                        return rc;
                    }
                }
                Files.writeString(outDir.resolve("results.jsonl"), RunMonitor.json(row) + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            return EXIT_OK;
        }

        private HeldOutMetrics.Metrics proxies(String what, Parameters p, ReplayBuffer held) {
            HeldOutMetrics.Metrics m = HeldOutMetrics.evaluate(new Network(p), held, evalSamples, threads);
            out.printf(Locale.ROOT, "proxies %-8s policy KL %.4f, top-1 %.2f%%, value loss %.4f, winner %.2f%% of %d (%d held-out samples)%n",
                    what, m.klPolicy(), 100 * m.top1(), m.valueLoss(), 100 * m.winnerAccuracy(), m.winnerSamples(), m.samples());
            return m;
        }

        /** Trains the variant from the start network with a fresh optimiser, as {@code Loop.train} does for generation {@code g}. */
        private Parameters train(Variant v, Path dir, ReplayBuffer window, long planned, String variantKey)
                throws IOException {
            Parameters parameters = Parameters.load(bestFile);
            Optimizer optimizer = new Optimizer(parameters, Optimizer.Settings.adamw());
            long steps = v.stepsFor(planned);
            TrainingRun.Setup setup = new TrainingRun.Setup(parameters, optimizer, v.schedule(cfg, planned), window,
                    cfg.batch(), cfg.microbatch(), threads, v.cv(), cfg.seed(), g, steps);
            Path trainDir = dir.resolve("train");
            // one checkpoint at the end of the run (the trainer's last sync); removed below, the candidate is what stays
            TrainingRun.Checkpointing checkpointing = new TrainingRun.Checkpointing(trainDir, 1_000_000, Long.MAX_VALUE, 1,
                    cfg.sha256());
            TrainingRun.Reporting reporting = new TrainingRun.Reporting(List.<TrainingObserver>of(), err, 10_000_000_000L);
            StepTrainer stepTrainer = trainer.equals("gpu")
                    ? GpuTrainers.create(parameters, optimizer, cfg.batch(), v.cv())
                    : StepTrainer.cpu(parameters, optimizer, v.cv(), cfg.microbatch(), threads);
            TrainingRun.Summary summary;
            try (stepTrainer) {
                summary = new TrainingRun(setup, checkpointing, reporting, TrainingRun.Clock.system(), () -> false, stepTrainer)
                        .run();
            }
            double seconds = summary.timing().loopWallNanos() / 1e9;
            err.printf(Locale.ROOT, "sweep: variant %s trained: %d steps in %.1f s (%.1f steps/s)%n", v.name(), summary.stepsDone(),
                    seconds, summary.stepsDone() / seconds);
            deleteTree(trainDir);
            parameters.extraMetadata().put("step", Long.toString(optimizer.steps()));
            parameters.extraMetadata().put("generation", Integer.toString(g));
            parameters.extraMetadata().put("sweep_variant", variantKey);
            return parameters;
        }

        private int gate(Variant v, Path dir, Parameters p, Path candidate, Map<String, Object> row) throws Exception {
            String candSha = Gen0.sha256Hex(Files.readAllBytes(candidate)).substring(0, 16);
            Path gateDir = dir.resolve(tag);
            String identity = String.format(Locale.ROOT, "gate;g=%d;cand=%s;best=%s;player=%s;seed=%d;opening=%d", gateGeneration,
                    candSha, bestSha, cfg.evalPlayer(), cfg.gateSeed(gateGeneration), cfg.gateOpening());
            Files.createDirectories(gateDir);
            Gate.PairJob job = new Gate.PairJob(gateDir, "vs-best", "variant " + v.name() + " gate", identity, pairs,
                    cfg.pairShard(), threads, err,
                    Gate.pairWorkers(cfg.gateSeed(gateGeneration), cfg.gateOpening(), network(p), network(best)), null, "gate");
            int rc = job.run();
            List<MctsMatch.PairLine> lines = rc == Job.EXIT_OK ? job.results() : null;
            if (lines == null) {
                err.println("sweep: the gate of variant " + v.name() + " ended with exit " + rc);
                return rc != Job.EXIT_OK ? rc : EXIT_ERROR;
            }
            Gate.Decision d = Gate.decide(Gate.scores(lines), new double[0], new double[0], 0, 0);
            int wins = 0;
            int draws = 0;
            int losses = 0;
            for (MctsMatch.PairLine l : lines) {
                wins += l.wins();
                draws += l.draws();
                losses += l.losses();
            }
            row.put("gate_tag", tag);
            row.put("gate_seed_generation", gateGeneration);
            row.put("pairs", d.pairs());
            row.put("wins", wins);
            row.put("draws", draws);
            row.put("losses", losses);
            row.put("score", d.score());
            row.put("score_se", d.scoreStandardError());
            row.put("z", d.z());
            row.put("score_minus_1_28_se", d.score() - 1.28 * d.scoreStandardError());
            out.printf(Locale.ROOT, "gate %-8s %s: score %.4f +- %.4f over %d pairs (%d-%d-%d games), z %.2f, score - 1.28 se = %.4f%n",
                    v.name(), tag, d.score(), d.scoreStandardError(), d.pairs(), wins, draws, losses, d.z(),
                    d.score() - 1.28 * d.scoreStandardError());
            return EXIT_OK;
        }

        /** The strength record of Loop.strengthOf for one rung: the same player, openings, seed and identity. */
        private int strength(Variant v, Path dir, Parameters p, Path candidate, int rung, Map<String, Object> row)
                throws Exception {
            String candSha = Gen0.sha256Hex(Files.readAllBytes(candidate)).substring(0, 16);
            Path sdir = dir.resolve("strength");
            Files.createDirectories(sdir);
            PlayerSpec spec = PlayerSpec.parse(StrengthEstimate.spec(cfg.ladderSpec(), rung));
            String identity = String.format(Locale.ROOT, "strength;net=%s;rung=%d;ladder=%s;player=%s;seed=%d;opening=%d", candSha,
                    rung, Gen0.sha256Hex(cfg.ladderSpec().getBytes(StandardCharsets.UTF_8)).substring(0, 12), cfg.evalPlayer(),
                    strengthSeed, StrengthEstimate.OPENING_PLIES);
            Gate.PairJob job = new Gate.PairJob(sdir, "ab" + rung, "variant " + v.name() + " strength vs " + rung + " nodes",
                    identity, strengthPairs, cfg.pairShard(), threads, err,
                    Gate.pairWorkers(strengthSeed, StrengthEstimate.OPENING_PLIES, network(p), seed -> spec.create()), null,
                    "strength");
            int rc = job.run();
            List<MctsMatch.PairLine> lines = rc == Job.EXIT_OK ? job.results() : null;
            if (lines == null) {
                err.println("sweep: the strength match of variant " + v.name() + " ended with exit " + rc);
                return rc != Job.EXIT_OK ? rc : EXIT_ERROR;
            }
            Map<Integer, MctsMatch.PairLine> byPair = new TreeMap<>();
            for (MctsMatch.PairLine l : lines) {
                byPair.put(l.pair(), l);
            }
            MctsMatch.Summary s = MctsMatch.summarize(cfg.evalPlayer(), "ab:" + rung, strengthPairs, strengthSeed, byPair);
            row.put("elo_" + rung, s.elo());
            row.put("elo_" + rung + "_ci95", s.eloConfidence95());
            row.put("score_" + rung, s.score());
            out.printf(Locale.ROOT, "strength %-8s vs %d nodes: Elo %+.0f +- %.0f (95%%), score %.3f, %d-%d-%d games, %d pairs%n",
                    v.name(), rung, s.elo(), s.eloConfidence95(), s.score(), s.wins(), s.draws(), s.losses(), s.pairs());
            return EXIT_OK;
        }

        private LongFunction<Player> network(Parameters parameters) {
            return NetPlayers.factory(cfg.evalPlayer(), parameters);
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
