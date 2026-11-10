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
import sternhalma.nn.Orientation;
import sternhalma.nn.Parameters;
import sternhalma.nn.PlaneSet;
import sternhalma.nn.TrainingBatch;

/**
 * Time of the training forward pass, the losses and the backward pass of the heads on the device (blocks B3 to B5,
 * docs/training.md "As implemented (block B1-B5)"), per batch size, and the time of each kernel. A main in the test
 * sources, not a test. Run: {@code ./gradlew --no-build-cache :nn-tornado:trainerBenchmark}; arguments: the batch
 * sizes (default 256 and 1024), {@code log} to print the raw profiler log once.
 *
 * <p>Two instances per batch size: one without the profiler for the wall time of {@code run} (upload of the batch,
 * all kernels, the loss copy-back, the host sums), one with TornadoVM's silent profiler for the per-task kernel times,
 * which also lengthens the wall time (docs/tornado.md, "Measured speed", finding 1).
 */
public final class TrainerBenchmark {

    private static final int WARMUP = 10;
    private static final int RUNS = 30;

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
        System.out.println("Training kernels, SMALL classic1, 2 players, uniform weights; "
                + "Apple M3 Max, TornadoVM Metal; " + RUNS + " runs after " + WARMUP + " warm-up runs");
        for (int size : sizes) {
            TrainingBatch batch = realBatch(size);
            System.out.printf("%nbatch %d: %d moves%n", size, batch.moveCount());
            double[] stageWall = new double[4];
            for (int stages = TornadoTrainer.CONVOLUTIONS; stages <= TornadoTrainer.ALL_STAGES; stages++) {
                try (TornadoTrainer trainer = new TornadoTrainer(parameters, size, batch.moveCount() + 16, 1.0,
                        TornadoSupport.defaultDevice(), null, false, stages)) {
                    stageWall[stages] = median(trainer, batch);
                }
            }
            double wall = stageWall[TornadoTrainer.ALL_STAGES];
            double flops = convFlops(config, size);
            System.out.printf("  wall time of run(), by cut graph: convolutions only %.2f ms (%.0f GFLOP/s of matrix work,"
                    + " with the batch upload), + heads %.2f ms, + losses and head backward %.2f ms (%.0f samples/s)%n",
                    stageWall[1], flops / (stageWall[1] * 1e6), stageWall[2], wall, 1000.0 * size / wall);
            System.out.printf("  by difference: heads %.2f ms, losses and head backward %.2f ms%n",
                    stageWall[2] - stageWall[1], stageWall[3] - stageWall[2]);
            try (TornadoTrainer trainer = new TornadoTrainer(parameters, size, batch.moveCount() + 16, 1.0,
                    TornadoSupport.defaultDevice(), null, true, TornadoTrainer.ALL_STAGES)) {
                profiled(trainer, batch, config, size, wall, log);
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

    private static void profiled(TornadoTrainer trainer, TrainingBatch batch, NetworkConfig config, int size,
            double wall, boolean printLog) {
        for (int i = 0; i < WARMUP; i++) {
            trainer.run(batch);
        }
        Map<String, double[]> perTask = new LinkedHashMap<>();
        double[] total = new double[RUNS];
        double[] profiledWall = new double[RUNS];
        for (int i = 0; i < RUNS; i++) {
            long t0 = System.nanoTime();
            trainer.run(batch);
            profiledWall[i] = (System.nanoTime() - t0) / 1e6;
            total[i] = trainer.lastProfilerResult().getDeviceKernelTime() / 1e6;
            String text = trainer.lastProfilerResult().getProfileLog();
            if (printLog && i == 0) {
                System.out.println(text);
            }
            Matcher m = Pattern.compile("\"(trainer\\d+\\.[A-Za-z0-9_]+)\"\\s*:\\s*\\{[^}]*?\"TASK_KERNEL_TIME\"\\s*:\\s*\"?(\\d+)")
                    .matcher(text);
            while (m.find()) {
                perTask.computeIfAbsent(m.group(1), k -> new double[RUNS])[i] = Long.parseLong(m.group(2)) / 1e6;
            }
        }
        Arrays.sort(total);
        Arrays.sort(profiledWall);
        System.out.printf("  profiled: wall %.2f ms, kernel time %.2f ms (median)%n", profiledWall[RUNS / 2], total[RUNS / 2]);
        double convMs = 0;
        double convFlops = convFlops(config, size);
        Map<String, Double> groups = new LinkedHashMap<>();
        for (Map.Entry<String, double[]> e : perTask.entrySet()) {
            double[] v = e.getValue().clone();
            Arrays.sort(v);
            double med = v[RUNS / 2];
            String task = e.getKey().substring(e.getKey().indexOf('.') + 1);
            System.out.printf("    %-24s %8.3f ms%n", task, med);
            if (task.contains("conv")) {
                convMs += med;
            }
            groups.merge(task.contains("conv") ? "convolutions (forward)" : task, med, Double::sum);
        }
        if (convMs > 0) {
            System.out.printf("  forward convolutions: %.2f ms for %.1f GFLOP = %.0f GFLOP/s%n", convMs, convFlops / 1e9,
                    convFlops / (convMs * 1e6));
        }
        System.out.printf("  whole forward + loss + head backward: %.2f GFLOP of matrix work for the forward, %.0f"
                + " GFLOP/s of the forward convolutions at wall time %.2f ms%n", convFlops / 1e9,
                convFlops / (wall * 1e6), wall);
    }

    /** Matrix FLOPs of the convolutions of one forward pass of the batch: 2 per multiply-add. */
    private static double convFlops(NetworkConfig config, int batch) {
        double c = config.channels();
        double in = 7.0 * config.inputPlanes() * c + config.blocks() * 2 * 7.0 * c * c;
        return 2.0 * batch * 121 * in;
    }
}
