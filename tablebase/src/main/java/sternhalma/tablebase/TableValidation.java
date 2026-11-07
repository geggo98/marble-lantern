package sternhalma.tablebase;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import sternhalma.engine.Geometry;
import sternhalma.engine.Layout;
import sternhalma.engine.Move;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;

/**
 * Validation of a race table against the engine, independent of the builder (docs/endgame-and-opening.md,
 * "Validation plan", items 3 and 4):
 *
 * <ul>
 * <li>{@link #layerConsistency}: for every entry d &gt; 0 (or a random sample of entries) some legal move inside the
 * region reaches an entry d - 1 and none reaches less; entry 0 only at the goal; an unreachable entry has no
 * successor with a known entry. Moves come from {@link Position#generate}, the engine's generator, not from the
 * builder's {@link SoloMoves}. Together with "0 only at the goal" this proves every entry exact for the restricted
 * solitaire: a path of d - 1 moves exists, and any path of L moves lowers the entry by at most one per move, so
 * d &lt;= L.</li>
 * <li>{@link #compare}: a table of a larger region against a table of a region it contains, on every state of the
 * smaller region: the larger region can only shorten a route, so its entry is never larger.</li>
 * <li>{@link #forwardBfs}: a plain forward breadth-first search inside the region from sampled configurations,
 * with the engine's moves, reaches the goal in exactly the entry's number of moves. Feasible for small entries; the
 * sample is drawn among entries up to a given count.</li>
 * </ul>
 *
 * <p>Run {@code TableValidation --rows N [--threads T] [--samples S] [--bfs N] [--bfs-max-count C] [--bfs-cap CAP]
 * [--compare]} (Gradle task {@code :tablebase:validateTable}); {@code --samples 0} checks every entry. Exit code 0
 * when every check passes, 1 otherwise.
 */
public final class TableValidation {

    private static final RuleSet RULES = RuleSet.defaults(2).withLeaveGoal(RaceTable.LEAVE_GOAL);

    private TableValidation() {
    }

    /** Result of {@link #layerConsistency}. */
    public record LayerCheck(long checked, long unknown, long belowMinusOne, long noneCloser, long zeroNotGoal,
            long unknownWithKnownSuccessor) {

        public boolean consistent() {
            return belowMinusOne == 0 && noneCloser == 0 && zeroNotGoal == 0 && unknownWithKnownSuccessor == 0;
        }

        LayerCheck plus(LayerCheck o) {
            return new LayerCheck(checked + o.checked, unknown + o.unknown, belowMinusOne + o.belowMinusOne,
                    noneCloser + o.noneCloser, zeroNotGoal + o.zeroNotGoal,
                    unknownWithKnownSuccessor + o.unknownWithKnownSuccessor);
        }
    }

    /** Per-thread scratch for successor generation with the engine. */
    static final class Successors {
        private final Ranking ranking;
        private final int[] buffer = new int[Position.MAX_MOVES];
        private final int[] next = new int[Position.PIECES];
        private final long[] ranks = new long[Position.MAX_MOVES];

        Successors(Ranking ranking) {
            this.ranking = ranking;
        }

        /** Writes the ranks of the successors inside the region into {@link #ranks} and returns their number. */
        int of(int[] cells) {
            Position pos = Position.empty(RULES);
            for (int c : cells) {
                pos.put(0, c);
            }
            int n = pos.generate(buffer);
            int count = 0;
            for (int i = 0; i < n; i++) {
                int from = Move.from(buffer[i]);
                int to = Move.to(buffer[i]);
                if (!ranking.contains(to)) {
                    continue;
                }
                for (int j = 0; j < cells.length; j++) {
                    next[j] = cells[j] == from ? to : cells[j];
                }
                ranks[count++] = ranking.rank(next);
            }
            return count;
        }

        long rank(int i) {
            return ranks[i];
        }
    }

    /**
     * Layer consistency with the engine's moves on every entry ({@code samples <= 0}) or on {@code samples} random
     * ranks, split over {@code threads}.
     */
    public static LayerCheck layerConsistency(RaceTable table, long samples, long seed, int threads) {
        Ranking ranking = table.rankingOf();
        long n = ranking.count();
        long goalRank = ranking.rank(Geometry.cornerCells(Layout.of(2).goalCorner(0)));
        int workers = Math.max(1, threads);
        List<Future<LayerCheck>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(workers)) {
            for (int w = 0; w < workers; w++) {
                int worker = w;
                futures.add(pool.submit(() -> {
                    Successors succ = new Successors(ranking);
                    long checked = 0;
                    long unknown = 0;
                    long below = 0;
                    long noneCloser = 0;
                    long zeroNotGoal = 0;
                    long unknownKnown = 0;
                    Random random = samples > 0 ? new Random(seed * 31 + worker) : null;
                    long begin = samples > 0 ? samples * worker / workers : n * worker / workers;
                    long end = samples > 0 ? samples * (worker + 1) / workers : n * (worker + 1) / workers;
                    for (long i = begin; i < end; i++) {
                        long rank = random == null ? i : (long) (random.nextDouble() * n);
                        int d = table.entry(rank);
                        int[] cells = ranking.unrank(rank);
                        int count = succ.of(cells);
                        checked++;
                        if (d == RetrogradeBuilder.UNKNOWN) {
                            unknown++;
                            for (int s = 0; s < count; s++) {
                                if (table.entry(succ.rank(s)) != RetrogradeBuilder.UNKNOWN) {
                                    unknownKnown++;
                                    break;
                                }
                            }
                            continue;
                        }
                        if (d == 0) {
                            if (rank != goalRank) {
                                zeroNotGoal++;
                            }
                            continue;
                        }
                        boolean closer = false;
                        for (int s = 0; s < count; s++) {
                            int ds = table.entry(succ.rank(s));
                            if (ds == RetrogradeBuilder.UNKNOWN) {
                                continue;
                            }
                            if (ds < d - 1) {
                                below++;
                            } else if (ds == d - 1) {
                                closer = true;
                            }
                        }
                        if (!closer) {
                            noneCloser++;
                        }
                    }
                    return new LayerCheck(checked, unknown, below, noneCloser, zeroNotGoal, unknownKnown);
                }));
            }
            LayerCheck total = new LayerCheck(0, 0, 0, 0, 0, 0);
            for (Future<LayerCheck> f : futures) {
                total = total.plus(get(f));
            }
            return total;
        }
    }

    /** Result of {@link #compare}: entries of the larger region's table on the states of the smaller region. */
    public record Comparison(long states, long equal, long smaller, long larger, long unknownInLarge, int maxGap) {

        public boolean consistent() {
            return larger == 0 && unknownInLarge == 0;
        }
    }

    /**
     * Compares {@code large} (a table whose region contains the region of {@code small}) with {@code small} on every
     * state of the smaller region.
     */
    public static Comparison compare(RaceTable small, RaceTable large) {
        Ranking rs = small.rankingOf();
        Ranking rl = large.rankingOf();
        for (int c : rs.cells()) {
            if (!rl.contains(c)) {
                throw new IllegalArgumentException("the large region does not contain cell " + c);
            }
        }
        long equal = 0;
        long smaller = 0;
        long larger = 0;
        long unknown = 0;
        int maxGap = 0;
        int[] cells = new int[rs.pieces()];
        int[] idx = new int[rs.pieces()];
        int[] region = rs.cells();
        for (long rank = 0; rank < rs.count(); rank++) {
            rs.unrankIndices(rank, idx);
            for (int j = 0; j < idx.length; j++) {
                cells[j] = region[idx[j]];
            }
            int a = small.entry(rank);
            int b = large.entry(rl.rank(cells));
            if (a == RetrogradeBuilder.UNKNOWN) {
                continue; // not defined in the small table
            }
            if (b == RetrogradeBuilder.UNKNOWN) {
                unknown++;
            } else if (b == a) {
                equal++;
            } else if (b < a) {
                smaller++;
                maxGap = Math.max(maxGap, a - b);
            } else {
                larger++;
            }
        }
        return new Comparison(rs.count(), equal, smaller, larger, unknown, maxGap);
    }

    /** Result of {@link #forwardBfs}. */
    public record ForwardCheck(int sampled, int solved, int capped, int mismatches, int maxDistanceSolved,
            long expanded, int[] solvedPerDistance) {

        public boolean consistent() {
            return mismatches == 0;
        }
    }

    /**
     * Forward breadth-first search inside the region from {@code samples} random configurations whose entry is at
     * most {@code maxCount}: the exact distance must equal the entry. A search that expands more than {@code cap}
     * states is counted as capped, not as a mismatch.
     */
    public static ForwardCheck forwardBfs(RaceTable table, int samples, int maxCount, long seed, long cap, int threads) {
        Ranking ranking = table.rankingOf();
        long n = ranking.count();
        Random random = new Random(seed);
        List<Long> picked = new ArrayList<>();
        long tries = 0;
        while (picked.size() < samples && tries < 100_000_000L) {
            tries++;
            long rank = (long) (random.nextDouble() * n);
            int d = table.entry(rank);
            if (d != RetrogradeBuilder.UNKNOWN && d <= maxCount) {
                picked.add(rank);
            }
        }
        int workers = Math.max(1, threads);
        List<Future<int[]>> futures = new ArrayList<>();
        long[] expandedPerWorker = new long[workers];
        int[][] perDistance = new int[workers][64];
        try (ExecutorService pool = Executors.newFixedThreadPool(workers)) {
            for (int w = 0; w < workers; w++) {
                int worker = w;
                futures.add(pool.submit(() -> {
                    Successors succ = new Successors(ranking);
                    long goalRank = ranking.rank(Geometry.cornerCells(Layout.of(2).goalCorner(0)));
                    int solved = 0;
                    int capped = 0;
                    int mismatches = 0;
                    int maxSolved = 0;
                    for (int i = worker; i < picked.size(); i += workers) {
                        long rank = picked.get(i);
                        long[] result = forwardDistance(ranking, succ, rank, goalRank, cap);
                        expandedPerWorker[worker] += result[1];
                        if (result[0] < 0) {
                            capped++;
                        } else {
                            solved++;
                            int d = (int) result[0];
                            maxSolved = Math.max(maxSolved, d);
                            perDistance[worker][Math.min(63, d)]++;
                            if (d != table.entry(rank)) {
                                mismatches++;
                                System.err.printf(Locale.ROOT, "mismatch at rank %d %s: table %d, forward %d%n", rank,
                                        Arrays.toString(ranking.unrank(rank)), table.entry(rank), d);
                            }
                        }
                    }
                    return new int[] {solved, capped, mismatches, maxSolved};
                }));
            }
            int solved = 0;
            int capped = 0;
            int mismatches = 0;
            int maxSolved = 0;
            for (Future<int[]> f : futures) {
                int[] r = get(f);
                solved += r[0];
                capped += r[1];
                mismatches += r[2];
                maxSolved = Math.max(maxSolved, r[3]);
            }
            long expanded = 0;
            for (long e : expandedPerWorker) {
                expanded += e;
            }
            int[] hist = new int[maxSolved + 1];
            for (int[] pd : perDistance) {
                for (int d = 0; d <= maxSolved; d++) {
                    hist[d] += pd[d];
                }
            }
            return new ForwardCheck(picked.size(), solved, capped, mismatches, maxSolved, expanded, hist);
        }
    }

    /**
     * Exact restricted distance from {@code start} to the goal by breadth-first search over the engine's moves;
     * returns {distance or -1 if more than cap states were expanded, states expanded}.
     */
    static long[] forwardDistance(Ranking ranking, Successors succ, long start, long goalRank, long cap) {
        if (start == goalRank) {
            return new long[] {0, 0};
        }
        LongSet visited = new LongSet();
        visited.add(start);
        long[] frontier = {start};
        int frontierSize = 1;
        long expanded = 0;
        for (int depth = 1; ; depth++) {
            long[] nextFrontier = new long[Math.max(16, frontierSize * 8)];
            int nextSize = 0;
            for (int i = 0; i < frontierSize; i++) {
                int[] cells = ranking.unrank(frontier[i]);
                int count = succ.of(cells);
                expanded++;
                if (expanded > cap) {
                    return new long[] {-1, expanded};
                }
                for (int s = 0; s < count; s++) {
                    long r = succ.rank(s);
                    if (r == goalRank) {
                        return new long[] {depth, expanded};
                    }
                    if (visited.add(r)) {
                        if (nextSize == nextFrontier.length) {
                            nextFrontier = Arrays.copyOf(nextFrontier, nextSize * 2);
                        }
                        nextFrontier[nextSize++] = r;
                    }
                }
            }
            if (nextSize == 0) {
                throw new IllegalStateException("the goal is unreachable from rank " + start);
            }
            frontier = nextFrontier;
            frontierSize = nextSize;
        }
    }

    /** A small open-addressing set of non-negative longs. */
    static final class LongSet {
        private long[] keys = new long[1 << 12];
        private int size;

        LongSet() {
            Arrays.fill(keys, -1);
        }

        boolean add(long key) {
            if (size * 2 >= keys.length) {
                grow();
            }
            int mask = keys.length - 1;
            int i = (int) (mix(key) & mask);
            while (keys[i] >= 0) {
                if (keys[i] == key) {
                    return false;
                }
                i = (i + 1) & mask;
            }
            keys[i] = key;
            size++;
            return true;
        }

        private void grow() {
            long[] old = keys;
            keys = new long[old.length * 2];
            Arrays.fill(keys, -1);
            size = 0;
            for (long k : old) {
                if (k >= 0) {
                    add(k);
                }
            }
        }

        private static long mix(long x) {
            x ^= x >>> 33;
            x *= 0xff51afd7ed558ccdL;
            x ^= x >>> 33;
            return x & Long.MAX_VALUE;
        }
    }

    private static <T> T get(Future<T> f) {
        try {
            return f.get();
        } catch (Exception e) {
            throw new IllegalStateException("validation failed", e);
        }
    }

    public static void main(String[] args) {
        int minRow = 2;
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        long samples = 1_000_000;
        int bfs = 0;
        int bfsMaxCount = 6;
        long bfsCap = 2_000_000;
        boolean compare = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--rows" -> minRow = Integer.parseInt(args[++i]);
                case "--threads" -> threads = Integer.parseInt(args[++i]);
                case "--samples" -> samples = Long.parseLong(args[++i]);
                case "--bfs" -> bfs = Integer.parseInt(args[++i]);
                case "--bfs-max-count" -> bfsMaxCount = Integer.parseInt(args[++i]);
                case "--bfs-cap" -> bfsCap = Long.parseLong(args[++i]);
                case "--compare" -> compare = true;
                default -> throw new IllegalArgumentException("unknown argument " + args[i]);
            }
        }
        boolean ok = true;
        long t0 = System.nanoTime();
        RaceTable table = RaceTable.of(minRow);
        System.out.printf(Locale.ROOT, "table rows>=%d: %d states, max count %d, sha256 %s, loaded in %.1f s%n", minRow,
                table.states(), table.maxCount(), table.sha256Hex(), (System.nanoTime() - t0) / 1e9);
        long[] layers = table.layerSizes();
        System.out.println("layers " + Arrays.toString(Arrays.copyOf(layers, layers.length - 1)) + ", unreachable "
                + layers[layers.length - 1]);

        long t1 = System.nanoTime();
        LayerCheck layer = layerConsistency(table, samples, 7, threads);
        System.out.printf(Locale.ROOT,
                "layer consistency (engine moves, %s): %d entries checked, %d unreachable, below d-1: %d, none at d-1: %d, "
                        + "zero not at goal: %d, unreachable with reachable successor: %d -> %s, %.1f s on %d threads%n",
                samples <= 0 ? "every entry" : samples + " samples", layer.checked(), layer.unknown(), layer.belowMinusOne(),
                layer.noneCloser(), layer.zeroNotGoal(), layer.unknownWithKnownSuccessor(),
                layer.consistent() ? "CONSISTENT" : "INCONSISTENT", (System.nanoTime() - t1) / 1e9, threads);
        ok &= layer.consistent();

        if (compare && minRow < 2) {
            long t2 = System.nanoTime();
            Comparison c = compare(RaceTable.rows2(), table);
            System.out.printf(Locale.ROOT,
                    "against rows>=2 on its %d states: equal %d, shorter in rows>=%d %d (%.2f%%, largest gap %d), longer %d, "
                            + "unreachable %d -> %s, %.1f s%n",
                    c.states(), c.equal(), minRow, c.smaller(), 100.0 * c.smaller() / c.states(), c.maxGap(), c.larger(),
                    c.unknownInLarge(), c.consistent() ? "CONSISTENT" : "INCONSISTENT", (System.nanoTime() - t2) / 1e9);
            ok &= c.consistent();
        }

        if (bfs > 0) {
            long t3 = System.nanoTime();
            ForwardCheck f = forwardBfs(table, bfs, bfsMaxCount, 9, bfsCap, threads);
            System.out.printf(Locale.ROOT,
                    "forward BFS (engine moves) on %d sampled configurations with count <= %d: solved %d, capped %d (cap %d "
                            + "states), mismatches %d, largest distance solved %d, %d states expanded, solved per distance %s "
                            + "-> %s, %.1f s on %d threads%n",
                    f.sampled(), bfsMaxCount, f.solved(), f.capped(), bfsCap, f.mismatches(), f.maxDistanceSolved(),
                    f.expanded(), Arrays.toString(f.solvedPerDistance()), f.consistent() ? "CONSISTENT" : "INCONSISTENT",
                    (System.nanoTime() - t3) / 1e9, threads);
            ok &= f.consistent();
        }
        System.out.println(ok ? "ALL CHECKS PASSED" : "A CHECK FAILED");
        System.exit(ok ? 0 : 1);
    }
}
