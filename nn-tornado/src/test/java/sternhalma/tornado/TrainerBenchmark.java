package sternhalma.tornado;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sternhalma.engine.Move;
import sternhalma.engine.Position;
import sternhalma.nn.Encoder;
import sternhalma.nn.NetworkConfig;
import sternhalma.nn.Optimizer;
import sternhalma.nn.Orientation;
import sternhalma.nn.Parameters;
import sternhalma.nn.PlaneSet;
import sternhalma.nn.TrainingBatch;

/**
 * Time of one whole training step on the device (blocks B3 to B8, docs/training.md "As implemented (block B6-B10)"),
 * per batch size: the wall time of {@code step} with its host side (copy of the batch, execution, loss sums), the
 * cut graphs by difference, and the time of each kernel group from the silent profiler. A main in the test sources,
 * not a test. Run: {@code ./gradlew --no-build-cache :nn-tornado:trainerBenchmark --args='256 512 1024 2048'};
 * arguments: batch sizes (default 256 and 1024), {@code log} prints the raw profiler log once,
 * {@code splits=N} sets the number of split-K chunks of the convolution weight gradient.
 *
 * <p>Instances per batch size: cut graphs without the profiler (no optimiser), one trainer with the optimiser for
 * {@code step}, and one with the profiler for the per-task times, which also lengthen the wall time
 * (docs/tornado.md, "Measured speed", finding 1).
 */
public final class TrainerBenchmark {

    private static final int WARMUP = 10;
    private static final int RUNS = 30;

    private static final Pattern TASK = Pattern
            .compile("\"(trainer\\d+\\.[A-Za-z0-9_]+)\"\\s*:\\s*\\{[^}]*?\"TASK_KERNEL_TIME\"\\s*:\\s*\"?(\\d+)");

    private TrainerBenchmark() {
    }

    public static void main(String[] args) {
        if (!TornadoSupport.isUsable()) {
            System.out.println("TornadoVM is not usable here: " + TornadoSupport.status().reason());
            return;
        }
        List<Integer> sizes = new ArrayList<>();
        boolean log = false;
        for (String a : args) {
            if (a.equals("log")) {
                log = true;
            } else if (a.startsWith("splits=")) {
                System.setProperty("sternhalma.tornado.convSplits", a.substring(7));
            } else {
                sizes.add(Integer.parseInt(a));
            }
        }
        if (sizes.isEmpty()) {
            sizes = List.of(256, 1024);
        }
        NetworkConfig config = NetworkConfig.small(2).withPlanes(PlaneSet.CLASSIC1);
        Parameters parameters = Parameters.uniform(config, 5, TrainTesting.initBound(config));
        parameters.get("prior.beta").data()[0] = 0.8f;
        parameters.get("prior.k").data()[0] = 0.04f;
        System.out.println("Training step, SMALL classic1, 2 players, uniform weights, AdamW; "
                + "Apple M3 Max, TornadoVM Metal; " + RUNS + " runs after " + WARMUP + " warm-up runs; conv splits "
                + TornadoTrainer.CONV_SPLITS);
        for (int size : sizes) {
            TrainingBatch batch = realBatch(size);
            System.out.printf("%nbatch %d: %d moves%n", size, batch.moveCount());
            double[] stageWall = new double[5];
            for (int stages = TornadoTrainer.CONVOLUTIONS; stages <= TornadoTrainer.ALL_STAGES; stages++) {
                try (TornadoTrainer trainer = new TornadoTrainer(parameters, size, batch.moveCount() + 16, 1.0,
                        TornadoSupport.defaultDevice(), null, false, stages)) {
                    stageWall[stages] = median(trainer, batch);
                }
            }
            double flops = convFlops(config, size);
            System.out.printf("  run() by cut graph: convolutions only %.2f ms (%.0f GFLOP/s of forward matrix work, with"
                    + " the batch upload), + heads %.2f, + losses and head backward %.2f, + convolution backward %.2f ms%n",
                    stageWall[1], flops / (stageWall[1] * 1e6), stageWall[2], stageWall[3], stageWall[4]);
            System.out.printf("  by difference: heads %.2f ms, losses and head backward %.2f ms, convolution backward"
                    + " %.2f ms%n", stageWall[2] - stageWall[1], stageWall[3] - stageWall[2],
                    stageWall[4] - stageWall[3]);
            Parameters host = TrainTesting.copyOf(parameters);
            Optimizer optimizer = new Optimizer(host, Optimizer.Settings.adamw());
            try (TornadoTrainer trainer = new TornadoTrainer(host, optimizer, size, size, batch.moveCount() + 16, 1.0,
                    TornadoSupport.defaultDevice())) {
                for (int i = 0; i < WARMUP; i++) {
                    trainer.step(batch, 1e-5);
                }
                double[] wall = new double[RUNS];
                double[][] parts = new double[3][RUNS];
                for (int i = 0; i < RUNS; i++) {
                    long t0 = System.nanoTime();
                    trainer.step(batch, 1e-5);
                    wall[i] = (System.nanoTime() - t0) / 1e6;
                    long[] t = trainer.lastTimes();
                    for (int k = 0; k < 3; k++) {
                        parts[k][i] = t[k] / 1e6;
                    }
                }
                Arrays.sort(wall);
                for (double[] part : parts) {
                    Arrays.sort(part);
                }
                double step = wall[RUNS / 2];
                double trainFlops = trainingFlops(config, size);
                long t0 = System.nanoTime();
                trainer.sync();
                double syncMs = (System.nanoTime() - t0) / 1e6;
                System.out.printf("  step(): median %.2f ms = %.0f samples/s, %.0f steps/s, %.0f GFLOP/s of matrix work"
                        + " (%.1f GFLOP); min %.2f max %.2f ms%n", step, 1000.0 * size / step, 1000.0 / step,
                        trainFlops / (step * 1e6), trainFlops / 1e9, wall[0], wall[RUNS - 1]);
                System.out.printf("  host side of step(): batch copy %.2f ms, plan.execute %.2f ms, loss sums %.2f ms;"
                        + " sync() (parameters and state to the host) %.2f ms; device arrays %.0f MB%n",
                        parts[0][RUNS / 2], parts[1][RUNS / 2], parts[2][RUNS / 2], syncMs,
                        trainer.deviceBytes() / 1e6);
            }
            Parameters hostProfiled = TrainTesting.copyOf(parameters);
            Optimizer profiledOptimizer = new Optimizer(hostProfiled, Optimizer.Settings.adamw());
            try (TornadoTrainer trainer = new TornadoTrainer(hostProfiled, profiledOptimizer, size, size,
                    batch.moveCount() + 16, 1.0, TornadoSupport.defaultDevice(), null, true)) {
                profiled(trainer, batch, log);
            }
        }
    }

    /** {@code size} samples from seeded random two-player games with the classic1 planes. */
    private static TrainingBatch realBatch(int size) {
        int games = size / 30 + 4;
        List<Position> all = Playouts.positions(2, games, 60, 21);
        List<Position> usable = new ArrayList<>();
        for (Position p : all) {
            if (p.legalMoves().length > 0) {
                usable.add(p);
            }
        }
        TrainingBatch.Builder builder = new TrainingBatch.Builder(2, PlaneSet.CLASSIC1);
        java.util.Random random = new java.util.Random(4);
        for (int i = 0; i < size; i++) {
            Position position = usable.get((int) ((long) i * usable.size() / size));
            int[] moves = position.legalMoves();
            Orientation orientation = Orientation.of(position);
            float[] input = new float[PlaneSet.CLASSIC1.inputSize()];
            Encoder.encode(PlaneSet.CLASSIC1, position, input, 0);
            int[] from = new int[moves.length];
            int[] to = new int[moves.length];
            double[] target = new double[moves.length];
            for (int m = 0; m < moves.length; m++) {
                from[m] = orientation.toCanonical(Move.from(moves[m]));
                to[m] = orientation.toCanonical(Move.to(moves[m]));
                target[m] = 0.05 + random.nextDouble();
            }
            double[] absolute = random.nextBoolean() ? new double[] {1, -1} : new double[] {-1, 1};
            builder.add(input, from, to, target, TrainingBatch.relativeValues(absolute, position.sideToMove()), 1f, 1f);
        }
        return builder.build();
    }

    private static double median(TornadoTrainer trainer, TrainingBatch batch) {
        for (int i = 0; i < WARMUP; i++) {
            trainer.run(batch);
        }
        double[] ms = new double[RUNS];
        for (int i = 0; i < RUNS; i++) {
            long t0 = System.nanoTime();
            trainer.run(batch);
            ms[i] = (System.nanoTime() - t0) / 1e6;
        }
        Arrays.sort(ms);
        return ms[RUNS / 2];
    }

    /** The per-task kernel times of the profiler, summed over the kernels of a group; medians over the runs. */
    private static void profiled(TornadoTrainer trainer, TrainingBatch batch, boolean printLog) {
        for (int i = 0; i < WARMUP; i++) {
            trainer.step(batch, 1e-5);
        }
        Map<String, double[]> perTask = new LinkedHashMap<>();
        double[] total = new double[RUNS];
        double[] profiledWall = new double[RUNS];
        for (int i = 0; i < RUNS; i++) {
            long t0 = System.nanoTime();
            trainer.step(batch, 1e-5);
            profiledWall[i] = (System.nanoTime() - t0) / 1e6;
            total[i] = trainer.lastProfilerResult().getDeviceKernelTime() / 1e6;
            String text = trainer.lastProfilerResult().getProfileLog();
            if (printLog && i == 0) {
                System.out.println(text);
            }
            Matcher m = TASK.matcher(text);
            while (m.find()) {
                perTask.computeIfAbsent(m.group(1), k -> new double[RUNS])[i] = Long.parseLong(m.group(2)) / 1e6;
            }
        }
        Arrays.sort(total);
        Arrays.sort(profiledWall);
        System.out.printf("  profiled: wall %.2f ms, kernel time %.2f ms (median; the profiler has a floor of about 0.5 ms"
                + " per task)%n", profiledWall[RUNS / 2], total[RUNS / 2]);
        Map<String, Double> groups = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map.Entry<String, double[]> e : perTask.entrySet()) {
            double[] v = e.getValue().clone();
            Arrays.sort(v);
            String task = e.getKey().substring(e.getKey().indexOf('.') + 1);
            String group = group(task);
            groups.merge(group, v[RUNS / 2], Double::sum);
            counts.merge(group, 1, Integer::sum);
        }
        for (Map.Entry<String, Double> e : groups.entrySet()) {
            System.out.printf("    %-28s %2d tasks %8.3f ms%n", e.getKey(), counts.get(e.getKey()), e.getValue());
        }
    }

    private static String group(String task) {
        if (task.startsWith("opt_")) {
            return "optimiser";
        }
        if (task.endsWith("_wpartial")) {
            return "conv weight partial (7)";
        }
        if (task.endsWith("_wreduce")) {
            return "conv weight reduce (9)";
        }
        if (task.endsWith("_din")) {
            return "conv input gradient (8)";
        }
        if (task.endsWith("_permute")) {
            return "weight permute (6)";
        }
        if (task.contains("mask")) {
            return "masks";
        }
        if (task.contains("conv")) {
            return "convolutions (forward)";
        }
        return task;
    }

    /** Matrix FLOPs of the convolutions of one forward pass of the batch: 2 per multiply-add. */
    private static double convFlops(NetworkConfig config, int batch) {
        double c = config.channels();
        double in = 7.0 * config.inputPlanes() * c + config.blocks() * 2 * 7.0 * c * c;
        return 2.0 * batch * 121 * in;
    }

    /**
     * Matrix FLOPs of the convolutions of one step: the forward pass, the weight gradient (the same) and the input
     * gradient (the forward pass without conv_in), as docs/training.md, "How the numbers were computed".
     */
    private static double trainingFlops(NetworkConfig config, int batch) {
        double c = config.channels();
        double convIn = 2.0 * batch * 121 * 7.0 * config.inputPlanes() * c;
        return 3 * convFlops(config, batch) - convIn;
    }
}
