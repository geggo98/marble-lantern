package sternhalma.selfplay;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongFunction;
import java.util.function.Supplier;

import sternhalma.nn.Parameters;
import sternhalma.nn.PooledEvaluator;
import sternhalma.search.Player;
import sternhalma.search.PlayerSpec;
import sternhalma.search.Strategies;
import sternhalma.tornado.GpuEvaluators;

/**
 * Pairs per second of the loop's matches with the CPU or the device evaluator (not a test; docs/training.md, "As implemented
 * (block G2)"): the {@link Gate.PairJob} of the gate (a network against another network, both on the device thread) or of
 * the strength record (a network against a classical rung), with the evaluation player of the loop and the wiring of
 * {@code Loop} (games in flight, cohorts, tail), written as shards into a directory.
 *
 * <pre>
 *   LoopMatchBench --net FILE (--vs-net FILE | --rung NODES) --out DIR [--pairs 100] [--sims 100] [--evaluator cpu|gpu]
 *                  [--games-in-flight 256] [--eval-cohorts 2] [--tail-pairs 0] [--tail-threads 2] [--threads 8]
 *                  [--seed 3001] [--opening-plies 4] [--pair-shard 10]
 * </pre>
 *
 * The report has the wall time, pairs per second, the decision statistics of the pairs ({@link Gate#decide}, no baseline
 * clause) and, on the device, the batches; {@code OUT/scores.txt} has the score of every pair, for comparing two runs.
 * The device needs the TornadoVM flags (the Gradle task {@code matchBench} sets them).
 */
final class LoopMatchBench {

    private LoopMatchBench() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> o = new HashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            o.put(args[i].substring(2), args[i + 1]);
        }
        Path out = Path.of(o.get("out"));
        Parameters net = Parameters.load(Path.of(o.get("net")));
        Parameters vs = o.containsKey("vs-net") ? Parameters.load(Path.of(o.get("vs-net"))) : null;
        int rung = LoopConfig.integer(o, "rung", 0);
        if (vs == null == (rung == 0)) {
            throw new IllegalArgumentException("exactly one of --vs-net and --rung is needed");
        }
        int pairs = LoopConfig.integer(o, "pairs", 100);
        int threads = LoopConfig.integer(o, "threads", 8);
        long seed = LoopConfig.lng(o, "seed", 3001);
        int openingPlies = LoopConfig.integer(o, "opening-plies", 4);
        EvaluatorOptions eval = EvaluatorOptions.of(o);
        String player = "mcts:sims=" + o.getOrDefault("sims", "100") + ",batch=1,cpuct=1.0,noise=0,temp=0,race=1";
        Files.createDirectories(out);

        LongFunction<Player> cpuNet = NetPlayers.factory(player, net);
        LongFunction<Player> cpuOther = vs != null ? NetPlayers.factory(player, vs)
                : rungFactory(Strategies.BEST_SPEC, rung);
        Supplier<Job.Worker<MctsMatch.PairLine>> cpuPlay = Gate.pairWorkers(seed, openingPlies, cpuNet, cpuOther);
        Supplier<Job.Worker<MctsMatch.PairLine>> play = cpuPlay;
        LoopGamesJob.Device device = null;
        int inFlight = threads;
        if (eval.gpu()) {
            String why = GpuEvaluators.unavailableReason();
            if (why != null) {
                throw new IllegalStateException("no device: " + why);
            }
            List<Parameters> nets = new ArrayList<>(List.of(net));
            if (vs != null) {
                nets.add(vs);
            }
            inFlight = Math.max(1, Math.min(eval.gamesInFlight(), pairs));
            int flights = inFlight;
            device = LoopGamesJob.Device.ofPool(() -> GpuEvaluators.openAll(nets, 0, eval.cohorts(), flights),
                    GpuEvaluators.description(), threads);
            LoopGamesJob.Device d = device;
            LongFunction<Player> a = NetPlayers.onDevice(player, () -> d.pool().models().get(0));
            LongFunction<Player> b = vs != null ? NetPlayers.onDevice(player, () -> d.pool().models().get(1)) : cpuOther;
            play = Gate.pairWorkers(seed, openingPlies, a, b);
        }
        PrintStream err = System.err;
        Gate.PairJob job = new Gate.PairJob(out, "bench", "bench match", "bench;" + player + ";" + seed, pairs,
                LoopConfig.integer(o, "pair-shard", 10), inFlight, err, play, (p, n) -> false, "gate");
        job.progressSeconds = 10;
        int tailThreads = Math.max(1, Math.min(eval.tailThreads(), threads - 1));
        if (device != null) {
            job.device(threads, device::abort);
            job.tail(Math.min(eval.tailPairs(), pairs), tailThreads, cpuPlay);
        }
        long t0 = System.nanoTime();
        long c0 = LoopGamesJob.processCpuNanos();
        int code = job.run();
        double wall = (System.nanoTime() - t0) / 1e9;
        System.out.printf(Locale.ROOT, "%s: exit %d, %d pairs in %.1f s = %.3f pairs/s (%.2f games/s), %.1f process-CPU-s%n",
                eval.gpu() ? "gpu" : "cpu", code, job.doneItems, wall, job.doneItems / wall, 2 * job.doneItems / wall,
                (LoopGamesJob.processCpuNanos() - c0) / 1e9);
        if (code == 0) {
            List<MctsMatch.PairLine> lines = job.results();
            double[] scores = Gate.scores(lines);
            int wins = 0;
            int draws = 0;
            int losses = 0;
            StringBuilder sb = new StringBuilder();
            for (MctsMatch.PairLine l : lines) {
                wins += l.wins();
                draws += l.draws();
                losses += l.losses();
                sb.append(l.pair()).append(' ').append(l.score()).append(' ').append(l.plies()).append('\n');
            }
            Files.writeString(out.resolve("scores.txt"), sb.toString(), StandardCharsets.UTF_8);
            System.out.println(Gate.decide(scores, new double[0], new double[0], 2, 1.28).describe() + "; games "
                    + wins + "-" + draws + "-" + losses);
        }
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

    private static LongFunction<Player> rungFactory(String ladderSpec, int nodes) {
        PlayerSpec spec = PlayerSpec.parse(StrengthEstimate.spec(ladderSpec, nodes));
        return seed -> spec.create();
    }
}
