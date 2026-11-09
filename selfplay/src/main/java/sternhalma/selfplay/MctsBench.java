package sternhalma.selfplay;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.LongFunction;

import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.nn.Parameters;
import sternhalma.search.MctsPlayer;
import sternhalma.search.Player;

/**
 * Simulations per second of an MCTS player on seeded mid-game positions (not a test; see docs/training.md, "As
 * implemented (block 2g)"):
 *
 * <pre>
 *   MctsBench --net FILE --subject mcts:sims=100,... [--threads 1,8] [--seconds 20] [--positions 40]
 * </pre>
 *
 * <p>Each thread searches the positions in turn with a new player per search (as a game does) for the given time after
 * a warm-up of a quarter of it; the report gives, per thread count, the simulations per wall-clock second of all threads
 * and of one thread, the simulations per CPU-second, and the CPU time per searched move. Race moves would cost nothing,
 * so the positions are not passed and the race module is off in the spec if it is not asked for.
 */
final class MctsBench {

    private MctsBench() {
    }

    public static void main(String[] args) throws Exception {
        String net = null;
        String subject = null;
        String threadsText = "1";
        double seconds = 20;
        int positions = 40;
        for (int i = 0; i < args.length; i += 2) {
            switch (args[i]) {
                case "--net" -> net = args[i + 1];
                case "--subject" -> subject = args[i + 1];
                case "--threads" -> threadsText = args[i + 1];
                case "--seconds" -> seconds = Double.parseDouble(args[i + 1]);
                case "--positions" -> positions = Integer.parseInt(args[i + 1]);
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        if (net == null || subject == null) {
            throw new IllegalArgumentException("usage: MctsBench --net FILE --subject mcts:... [--threads 1,8] [--seconds 20]");
        }
        Parameters parameters = Parameters.load(Path.of(net));
        LongFunction<Player> factory = NetPlayers.factory(subject, parameters);
        List<Position> set = positions(positions);
        System.out.printf(Locale.ROOT, "%s, %d positions, vector=%b, %d cores, load %.1f%n", subject, set.size(),
                sternhalma.nn.Networks.vectorAvailable(), Runtime.getRuntime().availableProcessors(),
                ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage());
        for (String t : threadsText.split(",")) {
            int threads = Integer.parseInt(t.trim());
            run(factory, set, threads, seconds / 4, false);
            run(factory, set, threads, seconds, true);
        }
    }

    /** Mid-game positions (plies 8 to 60 of seeded random games, not passed: no race moves, no S1 leaves at the root). */
    static List<Position> positions(int count) {
        Random random = new Random(42);
        List<Position> result = new ArrayList<>();
        while (result.size() < count) {
            Position p = Position.initial(RuleSet.defaults(2));
            int target = 8 + random.nextInt(53);
            for (int ply = 0; ply < target; ply++) {
                int[] moves = p.legalMoves();
                p.make(moves[random.nextInt(moves.length)]);
            }
            result.add(p);
        }
        return result;
    }

    private static void run(LongFunction<Player> factory, List<Position> set, int threads, double seconds, boolean report)
            throws Exception {
        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        long deadline = System.nanoTime() + Math.round(seconds * 1e9);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<long[]>> futures = new ArrayList<>();
            long wall0 = System.nanoTime();
            for (int t = 0; t < threads; t++) {
                int offset = t;
                futures.add(pool.submit(() -> {
                    long sims = 0;
                    long moves = 0;
                    long cpu0 = mx.getCurrentThreadCpuTime();
                    for (int i = offset; System.nanoTime() < deadline; i++) {
                        Position p = set.get(i % set.size());
                        MctsPlayer player = (MctsPlayer) factory.apply(i);
                        player.choose(p);
                        sims += player.totalSims();
                        moves++;
                    }
                    return new long[] {sims, moves, mx.getCurrentThreadCpuTime() - cpu0};
                }));
            }
            long sims = 0;
            long moves = 0;
            long cpu = 0;
            for (Future<long[]> f : futures) {
                long[] r = f.get();
                sims += r[0];
                moves += r[1];
                cpu += r[2];
            }
            double wall = (System.nanoTime() - wall0) / 1e9;
            if (report) {
                System.out.printf(Locale.ROOT, "threads %d: %.0f sims/s in all, %.0f sims/s per thread (wall), %.0f sims/CPU-s,"
                        + " %.1f CPU-ms per move, %d moves, load %.1f%n", threads, sims / wall, sims / wall / threads,
                        sims / (cpu / 1e9), cpu / 1e6 / moves, moves,
                        ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage());
            }
        }
    }
}
