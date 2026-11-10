package sternhalma.selfplay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import sternhalma.nn.Network;
import sternhalma.nn.Optimizer;
import sternhalma.nn.Parameters;
import sternhalma.nn.StepTrainer;
import sternhalma.nn.TrainingObserver;
import sternhalma.nn.TrainingRun;
import sternhalma.tornado.GpuTrainers;

/**
 * The check of docs/training.md, "As implemented (block B6-B10)": trains the candidate of one generation of a finished
 * loop run again, from the same network, on the same replay window and with the same configuration, with the trainer of
 * your choice, and prints the held-out proxies of the result next to those of the start network and of the candidate
 * that the loop wrote. A main in the test sources, not a test; the loop directory is only read.
 *
 * <pre>
 *   GpuCheck --loop DIR --generation G --start NETWORK --out DIR [--trainer gpu|cpu] [--threads 8] [--eval-samples 2500]
 *            [--steps N]   (steps: stop early; default the planned steps of the loop)
 * </pre>
 *
 * Run: {@code ./gradlew --no-build-cache :selfplay:gpuCheck --args='...'}. The configuration is the loop's
 * {@code run.json}; the window, the held-out games and the batches are drawn as {@code Loop} draws them.
 */
final class GpuCheck {

    private GpuCheck() {
    }

    public static void main(String[] argv) throws Exception {
        Map<String, String> o = new HashMap<>();
        for (int i = 0; i + 1 < argv.length; i += 2) {
            o.put(argv[i].substring(2), argv[i + 1]);
        }
        Path loop = Path.of(o.get("loop"));
        int g = Integer.parseInt(o.get("generation"));
        Path out = Path.of(o.get("out"));
        String kind = o.getOrDefault("trainer", "gpu");
        int threads = Integer.parseInt(o.getOrDefault("threads", "8"));
        int evalSamples = Integer.parseInt(o.getOrDefault("eval-samples", "2500"));
        LoopConfig cfg = LoopConfig.fromJson(RunMonitor.parse(Files.readString(loop.resolve("run.json"))));
        Parameters start = Parameters.load(Path.of(o.get("start")));

        long t0 = System.nanoTime();
        ReplayBuffer train = new ReplayBuffer(2, cfg.window(), cfg.augment(), start.config().planes());
        ReplayBuffer held = new ReplayBuffer(2, Integer.MAX_VALUE, false, start.config().planes());
        for (int gen = 1; gen <= g; gen++) {
            List<Path> files = Loop.shards(loop.resolve(String.format("gen-%04d", gen)).resolve("samples"));
            train.addGeneration(gen, files, (m, id) -> !Loop.heldOut(m, id, cfg.holdout(), cfg.games()));
            if (gen == g) {
                held.addGeneration(gen, files, (m, id) -> Loop.heldOut(m, id, cfg.holdout(), cfg.games()));
            }
        }
        long newSamples = train.size(g);
        long planned = cfg.plannedSteps(newSamples);
        long steps = o.containsKey("steps") ? Long.parseLong(o.get("steps")) : planned;
        System.err.printf(Locale.ROOT, "check: %d new training samples (%d held out), window %d samples, plan %d steps, run %d,"
                + " trainer %s, loaded in %.1f s%n", newSamples, held.size(), train.size(), planned, steps, kind,
                (System.nanoTime() - t0) / 1e9);

        // the host side of a step: drawing, expanding and mirroring 256 samples (no trainer involved)
        long b0 = System.nanoTime();
        int built = 300;
        for (int i = 0; i < built; i++) {
            train.batch(cfg.seed(), g, 100_000 + i, cfg.batch());
        }
        System.err.printf(Locale.ROOT, "check: building a batch of %d samples takes %.2f ms (%d batches, one thread)%n",
                cfg.batch(), (System.nanoTime() - b0) / 1e6 / built, built);

        Parameters parameters = Parameters.load(Path.of(o.get("start")));
        Optimizer optimizer = new Optimizer(parameters, Optimizer.Settings.adamw());
        TrainingRun.Setup setup = new TrainingRun.Setup(parameters, optimizer, cfg.schedule(), train, cfg.batch(),
                cfg.microbatch(), threads, cfg.valueCoefficient(), cfg.seed(), g, steps);
        TrainingRun.Checkpointing checkpointing = new TrainingRun.Checkpointing(out.resolve("train"), 500,
                Long.MAX_VALUE, 2, cfg.sha256());
        TrainingObserver observer = new TrainingObserver() {
            @Override
            public void onCheckpoint(Path path, long wallNanos, long bytes) {
                System.err.printf(Locale.ROOT, "check: checkpoint %s, %.0f ms%n", path.getFileName(), wallNanos / 1e6);
            }
        };
        TrainingRun.Reporting reporting = new TrainingRun.Reporting(List.of(observer), System.err, 20_000_000_000L);
        StepTrainer trainer = kind.equals("gpu")
                ? GpuTrainers.create(parameters, optimizer, cfg.batch(), cfg.valueCoefficient())
                : StepTrainer.cpu(parameters, optimizer, cfg.valueCoefficient(), cfg.microbatch(), threads);
        TrainingRun.Summary summary;
        try (trainer) {
            summary = new TrainingRun(setup, checkpointing, reporting, TrainingRun.Clock.system(), () -> false, trainer)
                    .run();
        }
        double seconds = summary.timing().loopWallNanos() / 1e9;
        System.out.printf(Locale.ROOT, "check: %d steps in %.1f s = %.2f steps/s = %.0f samples/s (%s trainer; step %.1f s,"
                + " checkpoints %.1f s)%n", summary.stepsDone(), seconds, summary.stepsDone() / seconds,
                summary.stepsDone() * (double) cfg.batch() / seconds, kind,
                summary.timing().step().wallNanos() / 1e9, summary.timing().checkpoint().wallNanos() / 1e9);
        Files.createDirectories(out);
        parameters.extraMetadata().put("step", Long.toString(optimizer.steps()));
        parameters.extraMetadata().put("generation", Integer.toString(g));
        parameters.extraMetadata().put("trainer", kind);
        parameters.save(out.resolve("candidate.safetensors"));

        System.out.println("held-out proxies (" + Math.min(held.size(), evalSamples) + " samples):");
        proxies("start network", start, held, evalSamples, threads);
        Path cpuCandidate = loop.resolve(String.format("gen-%04d", g)).resolve("candidate.safetensors");
        if (Files.isRegularFile(cpuCandidate)) {
            proxies("CPU candidate of the loop", Parameters.load(cpuCandidate), held, evalSamples, threads);
        }
        proxies(kind + " candidate", parameters, held, evalSamples, threads);
    }

    private static void proxies(String what, Parameters p, ReplayBuffer held, int evalSamples, int threads)
            throws IOException {
        HeldOutMetrics.Metrics m = HeldOutMetrics.evaluate(new Network(p), held, evalSamples, threads);
        System.out.printf(Locale.ROOT, "  %-28s policy KL %.4f, top-1 %.2f%%, value loss %.4f, winner %.2f%% of %d%n", what,
                m.klPolicy(), 100 * m.top1(), m.valueLoss(), 100 * m.winnerAccuracy(), m.winnerSamples());
    }
}
