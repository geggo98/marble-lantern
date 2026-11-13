package sternhalma.tornado;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import sternhalma.engine.Position;
import sternhalma.nn.Encoder;
import sternhalma.nn.Evaluator;
import sternhalma.nn.NetworkConfig;
import sternhalma.nn.Parameters;
import sternhalma.nn.PlaneSet;
import sternhalma.nn.PooledEvaluator;

/**
 * Not a test: how many evaluations per second the device delivers to games that compute real classic1 planes (docs/training.md,
 * "As implemented (block G1)"). {@code G = batch * cohorts} game threads, at most {@code --threads} of them compute at a
 * time (the compute permits); each takes a position of a seeded random game, encodes it with the classical planes, waits
 * for the batch it lands in, and turns the outputs into priors and values ({@link Evaluator}: the same work as one MCTS
 * simulation without the tree). So the figure is the ceiling of tree-less self-play on this machine: the planes, the host
 * priors, the transfers and the kernels, with all the waiting between them.
 *
 * <pre>
 *   gpuFeed --args="--size small --batches 64,128,256,512 --seconds 60 --threads 8 --cohorts 2"
 * </pre>
 */
final class GpuFeedBenchmark {

    private GpuFeedBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        String size = "small";
        String batches = "64,128,256,512";
        double seconds = 60;
        int threads = 8;
        int cohorts = 2;
        boolean virtual = true;
        for (int i = 0; i < args.length; i += 2) {
            switch (args[i]) {
                case "--size" -> size = args[i + 1];
                case "--batches" -> batches = args[i + 1];
                case "--seconds" -> seconds = Double.parseDouble(args[i + 1]);
                case "--threads" -> threads = Integer.parseInt(args[i + 1]);
                case "--cohorts" -> cohorts = Integer.parseInt(args[i + 1]);
                case "--virtual" -> virtual = Boolean.parseBoolean(args[i + 1]);
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        if (GpuEvaluators.unavailableReason() != null) {
            System.out.println("no device: " + GpuEvaluators.unavailableReason());
            return;
        }
        NetworkConfig config = (size.equals("large") ? NetworkConfig.large(2) : NetworkConfig.small(2)).withPlanes(PlaneSet.CLASSIC1);
        Parameters parameters = Parameters.uniform(config, 5, (float) Math.sqrt(6.0 / (7 * config.channels())));
        List<Position> positions = Playouts.positions(2, 60, 160, 1);
        if (virtual) {
            // the scheduler of the virtual threads has as many carriers as compute threads; read once, at the first use
            System.setProperty("jdk.virtualThreadScheduler.parallelism", Integer.toString(threads));
            System.setProperty("jdk.virtualThreadScheduler.maxPoolSize", Integer.toString(threads));
        }
        System.out.printf(Locale.ROOT, "%s classic1, %s, %d positions, %d compute threads (%s), %d cohorts, %.0f s per row, load %.1f%n",
                size.toUpperCase(Locale.ROOT), GpuEvaluators.description(), positions.size(), threads,
                virtual ? "virtual threads" : "permits", cohorts, seconds,
                ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage());
        System.out.println("batch  games  evals/s  sims/s-equivalent  mean-batch  device-busy  up MB/s  down MB/s  cpu-cores");
        for (String b : batches.split(",")) {
            int batch = Integer.parseInt(b.trim());
            run(config, parameters, positions, batch, cohorts, threads, seconds, virtual);
        }
    }

    private static void run(NetworkConfig config, Parameters parameters, List<Position> positions, int batch, int cohorts,
            int threads, double seconds, boolean virtual) throws Exception {
        int games = batch * cohorts;
        try (GpuEvaluators.Session session = GpuEvaluators.open(parameters, virtual ? 0 : threads, cohorts, games);
                ExecutorService pool = virtual ? Executors.newFixedThreadPool(games, Thread.ofVirtual().factory())
                        : Executors.newFixedThreadPool(games)) {
            AtomicBoolean stop = new AtomicBoolean();
            AtomicLong count = new AtomicLong();
            List<Future<?>> futures = new ArrayList<>();
            for (int g = 0; g < games; g++) {
                PooledEvaluator.Client client = session.model().newClient();
                int seed = g;
                futures.add(pool.submit(() -> {
                    Evaluator evaluator = new Evaluator(client);
                    Random random = new Random(seed);
                    client.begin();
                    try {
                        long mine = 0;
                        while (!stop.get()) {
                            evaluator.evaluate(positions.get(random.nextInt(positions.size())));
                            mine++;
                        }
                        count.addAndGet(mine);
                    } finally {
                        client.end();
                    }
                    return null;
                }));
            }
            Thread.sleep(Math.round(seconds * 0.2 * 1000)); // warm-up: the JIT, the first batches
            long c0 = count.get();
            PooledEvaluator.Stats s0 = session.service().stats();
            com.sun.management.OperatingSystemMXBean os =
                    (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            long cpu0 = os.getProcessCpuTime();
            long t0 = System.nanoTime();
            Thread.sleep(Math.round(seconds * 1000));
            long wall = System.nanoTime() - t0;
            PooledEvaluator.Stats s1 = session.service().stats();
            long cpu1 = os.getProcessCpuTime();
            long done = s1.positions() - s0.positions();
            stop.set(true);
            for (Future<?> f : futures) {
                f.get();
            }
            double secondsMeasured = wall / 1e9;
            long batchesRun = s1.batches() - s0.batches();
            double meanBatch = batchesRun == 0 ? 0 : (double) done / batchesRun;
            double busy = (s1.executeNanos() - s0.executeNanos()) / (double) wall;
            // the device runs the bucket (the next power of two), and moves the whole bucket
            double bucket = TornadoNetwork.bucket((int) Math.round(meanBatch), 1024);
            double up = done / secondsMeasured * config.inputSize() * 4 * (bucket / Math.max(1, meanBatch)) / 1e6;
            double down = done / secondsMeasured * (Encoder.POLICY_SIZE + config.players()) * 4 * (bucket / Math.max(1, meanBatch)) / 1e6;
            System.out.printf(Locale.ROOT, "%5d  %5d  %7.0f  %17.0f  %10.1f  %10.0f%%  %7.1f  %9.1f  %9.2f%n", batch, games,
                    done / secondsMeasured, (count.get() - c0) / secondsMeasured, meanBatch, 100 * busy, up, down,
                    (cpu1 - cpu0) / (double) wall);
            System.out.println("       device thread: " + session.timings().get());
        }
    }
}
