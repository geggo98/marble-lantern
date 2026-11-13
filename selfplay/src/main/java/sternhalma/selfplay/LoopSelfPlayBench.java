package sternhalma.selfplay;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import sternhalma.nn.Parameters;
import sternhalma.nn.PooledEvaluator;
import sternhalma.tornado.GpuEvaluators;

/**
 * Games per second of the loop's self-play with the CPU or the device evaluator (not a test; docs/training.md, "As implemented
 * (block G1)"): the games of {@link LoopGamesJob} with the settings of the loop, written as shards into a directory.
 *
 * <pre>
 *   LoopSelfPlayBench --net FILE --out DIR [--games 200] [--sims 200] [--evaluator cpu|gpu] [--games-in-flight 256]
 *                     [--eval-cohorts 2] [--threads 8] [--shard-games 25] [--seed 1]
 * </pre>
 *
 * The settings are the loop's defaults ({@link LoopConfig#of}): noise 0.25, temperature 1 for 14 plies, openings of 4 to 10
 * plies, race play 1, S1 adjudication. The report has the wall time, games and simulations per second and, on the device, the
 * batches. The device needs the TornadoVM flags (the Gradle task {@code selfplayBench} sets them).
 */
final class LoopSelfPlayBench {

    private LoopSelfPlayBench() {
    }

    public static void main(String[] args) throws Exception {
        java.util.Map<String, String> o = new java.util.HashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            o.put(args[i].substring(2), args[i + 1]);
        }
        Path out = Path.of(o.getOrDefault("out", ""));
        Parameters network = Parameters.load(Path.of(o.get("net")));
        int games = LoopConfig.integer(o, "games", 200);
        int threads = LoopConfig.integer(o, "threads", 8);
        EvaluatorOptions eval = EvaluatorOptions.of(o);
        LoopConfig cfg = LoopConfig.of(java.util.Map.of("sims", o.getOrDefault("sims", "200"), "games", Integer.toString(games),
                "seed", o.getOrDefault("seed", "1"), "shard-games", o.getOrDefault("shard-games", "25")), "bench", "", 0);
        Files.createDirectories(out);
        LoopGamesJob.Device device = null;
        int pool = threads;
        if (eval.gpu()) {
            String why = GpuEvaluators.unavailableReason();
            if (why != null) {
                throw new IllegalStateException("no device: " + why);
            }
            int inFlight = Math.min(eval.gamesInFlight(), games);
            device = new LoopGamesJob.Device(() -> GpuEvaluators.open(network, 0, eval.cohorts(), inFlight),
                    GpuEvaluators.description(), threads);
            pool = inFlight;
        }
        LoopGamesJob job = new LoopGamesJob(out, 0, cfg.settings(0), "bench", network, games, cfg.shardGames(), pool, System.err,
                (p, n) -> false, device);
        job.label = "bench selfplay";
        job.progressSeconds = 10;
        long t0 = System.nanoTime();
        int code = job.run();
        double wall = (System.nanoTime() - t0) / 1e9;
        System.out.printf(Locale.ROOT, "%s: exit %d, %d games in %.1f s = %.2f games/s, %.0f nodes/s, %.1f process-CPU-s%n",
                eval.gpu() ? "gpu" : "cpu", code, job.doneItems, wall, job.doneItems / wall, job.nodes() / wall,
                job.cpuNanos() / 1e9);
        if (device != null) {
            PooledEvaluator.Stats st = device.stats();
            if (st != null) {
                System.out.printf(Locale.ROOT, "device: %d batches, %d positions, mean batch %.1f, busy %.1f s of %.1f s (%.0f%%),"
                        + " idle %.1f s%n", st.batches(), st.positions(), st.meanBatch(), st.executeNanos() / 1e9, wall,
                        100.0 * st.executeNanos() / 1e9 / wall, st.idleNanos() / 1e9);
            }
            System.out.println("device thread: " + device.timings());
            device.close();
        }
        System.exit(code);
    }
}
