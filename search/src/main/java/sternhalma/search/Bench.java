package sternhalma.search;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Measures the search speed of a player spec (nodes per CPU-second and CPU milliseconds per move) on the
 * {@link ReferencePositions} plus full self-play games, single-threaded, and the speed ratio of two sides A and B.
 * This is the tool behind every nodes/s ratio of round 2 (docs/search-and-evaluation.md, "Round 2 yardstick").
 *
 * <pre>
 *   Bench &lt;specA&gt; [&lt;specB&gt;] [--copy] [--nodes N] [--reps R] [--positions K] [--games G] [--ignore-load]
 *   devenv shell -- ./gradlew --no-build-cache :search:bench --args='ab:eval=linear-r1,...,nodes=20000'
 * </pre>
 *
 * <p><b>Sides.</b> With one spec, A is that spec on the current build and B the same spec on the frozen pre-round
 * build: {@code engine.jar} and {@code search.jar} in {@code -Dsternhalma.reference.dir} (default
 * {@code .reference} in the working directory; scripts/freeze-reference.sh builds it). The frozen jars are loaded
 * in their own {@link URLClassLoader} whose parent is the platform class loader, so they never see the current
 * classes. If the directory does not exist, only A is measured. With two specs, both run on the current build
 * (the self-check of the tool: {@code linear-r1} against {@code linear} must give 1.00). The frozen build of
 * round 2 predates the registry name {@code linear-r1}; when the frozen registry lacks it, B uses
 * {@code eval=linear}, which held the same weights at the frozen commit (the tree check below confirms it).
 * {@code --copy} makes B the same spec on a second copy of the current build in its own class loader: the
 * end-to-end check of the cross-build path, which must give 1.00 because the code is the same.
 *
 * <p><b>Symmetry.</b> Both sides, also the current build, are driven through the same reflective adapter
 * ({@code PlayerSpec.parse}, {@code Evaluators.byName}, {@code new AlphaBeta(...)}, {@code AlphaBeta.search},
 * {@code AlphaBetaPlayer.choose}), API that exists unchanged in the frozen build, so both pay the same call
 * overhead. A position search uses a fresh searcher, as {@link Fingerprint} does; a game uses two fresh players
 * that keep their tables between moves, as in the arena.
 *
 * <p><b>Interleaving.</b> First one untimed warm-up pass of both sides over the positions (JIT compilation of
 * both builds' classes). Then {@code R} repetitions (default 3). A repetition runs every item (each position,
 * each full game) on both sides directly after each other; the order alternates with item and repetition
 * ({@code A,B} when item + repetition is even, else {@code B,A}), so a change of machine load hits both sides
 * alike and neither side is always first. The ratio of a repetition is nps(A)/nps(B) over all its items; the
 * reported ratio is the median over the repetitions.
 *
 * <p><b>CPU time.</b> {@link ThreadMXBean#getCurrentThreadCpuTime()} of the searching thread (the main thread)
 * around each {@code search} or {@code choose} call, never per node (a call costs about a microsecond). Searcher
 * construction (table allocation) and position replay lie outside the timed region. Wall time is reported beside
 * it for information only.
 *
 * <p><b>Load.</b> The 1-minute load average is logged before and after. If it exceeds the number of cores
 * before, the bench refuses to start; if after, it prints its numbers but a {@code REFUSED} line instead of the
 * {@code RESULT} line. Exit code 3 in both cases; {@code --ignore-load} reports anyway and marks the result.
 */
public final class Bench {

    /** Default directory of the frozen reference build, relative to the working directory. */
    public static final String DEFAULT_REFERENCE_DIR = ".reference";
    public static final String REFERENCE_DIR_PROPERTY = "sternhalma.reference.dir";

    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();

    private Bench() {
    }

    /**
     * Search and game API of one build, called by reflection. {@link #current()} uses the classes of this
     * build; {@link #load} loads a build from jars or class directories into an isolated class loader.
     */
    static final class Build implements AutoCloseable {

        final String label;
        private final URLClassLoader owned;
        private final Method rulesDefaults;
        private final Method positionInitial;
        private final Method make;
        private final Method outcome;
        private final Method legalMoves;
        private final Method sideToMove;
        private final Method parse;
        private final Method specEvaluator;
        private final Method specWeights;
        private final Method specTableBits;
        private final Method specOptions;
        private final Method specDepth;
        private final Method specMillis;
        private final Method specNodes;
        private final Method create;
        private final Method byName;
        private final Method names;
        private final Constructor<?> alphaBeta;
        private final Method search;
        private final Method resultMove;
        private final Method resultScore;
        private final Method resultDepth;
        private final Method resultNodes;
        private final Method choose;
        private final Method totalNodes;

        private Build(String label, ClassLoader loader, URLClassLoader owned) {
            this.label = label;
            this.owned = owned;
            try {
                Class<?> rules = Class.forName("sternhalma.engine.RuleSet", true, loader);
                Class<?> position = Class.forName("sternhalma.engine.Position", true, loader);
                Class<?> spec = Class.forName("sternhalma.search.PlayerSpec", true, loader);
                Class<?> evaluators = Class.forName("sternhalma.search.Evaluators", true, loader);
                Class<?> evaluator = Class.forName("sternhalma.search.Evaluator", true, loader);
                Class<?> options = Class.forName("sternhalma.search.SearchOptions", true, loader);
                Class<?> ab = Class.forName("sternhalma.search.AlphaBeta", true, loader);
                Class<?> result = Class.forName("sternhalma.search.SearchResult", true, loader);
                Class<?> player = Class.forName("sternhalma.search.AlphaBetaPlayer", true, loader);
                rulesDefaults = rules.getMethod("defaults", int.class);
                positionInitial = position.getMethod("initial", rules);
                make = position.getMethod("make", int.class);
                outcome = position.getMethod("outcome");
                legalMoves = position.getMethod("legalMoves");
                sideToMove = position.getMethod("sideToMove");
                parse = spec.getMethod("parse", String.class);
                specEvaluator = spec.getMethod("evaluator");
                specWeights = spec.getMethod("weights");
                specTableBits = spec.getMethod("tableBits");
                specOptions = spec.getMethod("options");
                specDepth = spec.getMethod("depth");
                specMillis = spec.getMethod("millis");
                specNodes = spec.getMethod("nodes");
                create = spec.getMethod("create");
                byName = evaluators.getMethod("byName", String.class, Map.class);
                names = evaluators.getMethod("names");
                alphaBeta = ab.getConstructor(evaluator, int.class, boolean.class, options);
                search = ab.getMethod("search", position, int.class, long.class, long.class);
                resultMove = result.getMethod("move");
                resultScore = result.getMethod("score");
                resultDepth = result.getMethod("depth");
                resultNodes = result.getMethod("nodes");
                choose = player.getMethod("choose", position);
                totalNodes = player.getMethod("totalNodes");
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("build '" + label + "' lacks the bench API", e);
            }
        }

        /** The classes of this build, through the same reflective calls as a loaded build. */
        static Build current() {
            return new Build("current build", Bench.class.getClassLoader(), null);
        }

        /** A build from jars or class directories, isolated from the classes of this build. */
        static Build load(String label, List<Path> classpath) {
            URL[] urls = classpath.stream().map(Build::url).toArray(URL[]::new);
            URLClassLoader loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader());
            try {
                return new Build(label, loader, loader);
            } catch (RuntimeException e) {
                closeQuietly(loader);
                throw e;
            }
        }

        /** The frozen reference build in {@code dir}, or null if the directory holds none. */
        static Build reference(Path dir) {
            Path engine = dir.resolve("engine.jar");
            Path searchJar = dir.resolve("search.jar");
            if (!Files.isRegularFile(engine) || !Files.isRegularFile(searchJar)) {
                return null;
            }
            String commit = "";
            try {
                commit = Files.readAllLines(dir.resolve("REFERENCE.txt")).stream()
                        .filter(l -> l.startsWith("commit=")).findFirst().orElse("");
            } catch (java.io.IOException e) {
                commit = "no REFERENCE.txt";
            }
            return load("frozen build " + dir + (commit.isEmpty() ? "" : " (" + commit + ")"),
                    List.of(engine, searchJar));
        }

        /** A second copy of the current build in its own class loader (for testing the cross-build path). */
        static Build isolatedCopyOfCurrent() {
            return load("isolated copy of the current build",
                    List.of(codeSource(Bench.class), codeSource(sternhalma.engine.Position.class),
                            codeSource(sternhalma.tablebase.RaceCounts.class)));
        }

        private static Path codeSource(Class<?> type) {
            try {
                return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
            } catch (URISyntaxException e) {
                throw new IllegalStateException(e);
            }
        }

        private static URL url(Path path) {
            try {
                return path.toUri().toURL();
            } catch (MalformedURLException e) {
                throw new IllegalArgumentException(path.toString(), e);
            }
        }

        private static void closeQuietly(URLClassLoader loader) {
            try {
                loader.close();
            } catch (java.io.IOException e) {
                // nothing was loaded through it that needs closing
            }
        }

        boolean knowsEvaluator(String name) {
            return ((Set<?>) call(names, null)).contains(name);
        }

        Object position(int[] moves) {
            Object pos = call(positionInitial, null, call(rulesDefaults, null, 2));
            for (int m : moves) {
                call(make, pos, m);
            }
            return pos;
        }

        /** A fresh searcher for {@code spec}: {@code new AlphaBeta(evaluator, tableBits, true, options)}. */
        Searcher searcher(String spec) {
            Object p = call(parse, null, spec);
            Object evaluator = call(byName, null, call(specEvaluator, p), call(specWeights, p));
            try {
                Object ab = alphaBeta.newInstance(evaluator, call(specTableBits, p), true, call(specOptions, p));
                return new Searcher(this, ab, (Integer) call(specDepth, p), (Long) call(specMillis, p),
                        (Long) call(specNodes, p));
            } catch (InstantiationException | IllegalAccessException e) {
                throw new IllegalStateException(e);
            } catch (InvocationTargetException e) {
                throw rethrow(e);
            }
        }

        Object player(String spec) {
            return call(create, call(parse, null, spec));
        }

        long[] search(Searcher s, Object pos) {
            Object r = call(search, s.alphaBeta(), pos, s.depth(), s.millis(), s.nodes());
            return new long[] {(Integer) call(resultMove, r), (Integer) call(resultScore, r),
                (Integer) call(resultDepth, r), (Long) call(resultNodes, r)};
        }

        int choose(Object player, Object pos) {
            return (Integer) call(choose, player, pos);
        }

        long totalNodes(Object player) {
            return (Long) call(totalNodes, player);
        }

        boolean gameOver(Object pos) {
            return call(outcome, pos) != null || ((int[]) call(legalMoves, pos)).length == 0;
        }

        int sideToMove(Object pos) {
            return (Integer) call(sideToMove, pos);
        }

        void make(Object pos, int move) {
            call(make, pos, move);
        }

        private static Object call(Method method, Object target, Object... args) {
            try {
                return method.invoke(target, args);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            } catch (InvocationTargetException e) {
                throw rethrow(e);
            }
        }

        private static RuntimeException rethrow(InvocationTargetException e) {
            return e.getCause() instanceof RuntimeException r ? r : new IllegalStateException(e.getCause());
        }

        @Override
        public void close() {
            if (owned != null) {
                closeQuietly(owned);
            }
        }
    }

    /** A searcher of one build with the limits of its spec. */
    record Searcher(Build build, Object alphaBeta, int depth, long millis, long nodes) {
    }

    /** Totals of one side over a set of items. */
    public record Totals(long nodes, long cpuNanos, long wallNanos, long moves) {

        static final Totals ZERO = new Totals(0, 0, 0, 0);

        Totals plus(Totals o) {
            return new Totals(nodes + o.nodes, cpuNanos + o.cpuNanos, wallNanos + o.wallNanos, moves + o.moves);
        }

        public double nodesPerCpuSecond() {
            return nodes / (cpuNanos / 1e9);
        }

        public double cpuMillisPerMove() {
            return cpuNanos / 1e6 / moves;
        }

        public double wallMillisPerMove() {
            return wallNanos / 1e6 / moves;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT,
                    "%d nodes, %d moves, %.0f CPU-ms, %.0f wall-ms: %.0f nodes/CPU-s, %.3f CPU-ms/move, %.3f wall-ms/move",
                    nodes, moves, cpuNanos / 1e6, wallNanos / 1e6, nodesPerCpuSecond(), cpuMillisPerMove(),
                    wallMillisPerMove());
        }
    }

    /** One measured item: totals plus a signature of the tree (moves, scores, depths, node counts). */
    record Measurement(Totals totals, long signature) {
    }

    /** One side of a bench: a spec on a build. */
    record Side(String name, Build build, String spec) {
    }

    /**
     * The result of a bench.
     *
     * @param positions     per side (0 = A, 1 = B): totals over the position searches of all timed repetitions
     * @param games         per side: totals over the full games of all timed repetitions
     * @param repRatios     nps(A)/nps(B) of each repetition (empty without B)
     * @param identicalPositions positions where A and B searched the identical tree in every repetition
     * @param identicalGames     full games that A and B played identically in every repetition
     */
    public record Result(String specA, String specB, Totals[] positions, Totals[] games, double[] repRatios,
            int positionCount, int gameCount, int identicalPositions, int identicalGames) {

        public boolean hasB() {
            return positions.length == 2;
        }

        public Totals all(int side) {
            return positions[side].plus(games[side]);
        }

        public double medianRatio() {
            double[] sorted = repRatios.clone();
            Arrays.sort(sorted);
            int n = sorted.length;
            return n == 0 ? Double.NaN : n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
        }

        public boolean identicalTrees() {
            return identicalPositions == positionCount && identicalGames == gameCount;
        }
    }

    /**
     * Runs the bench.
     *
     * @param positionCount number of reference positions used (the first ones, at most {@value ReferencePositions#COUNT})
     * @param gameCount     number of full games (at most {@value ReferencePositions#FULL_GAMES})
     * @param log           progress lines (one per repetition)
     */
    static Result run(Side a, Side b, int reps, int positionCount, int gameCount, java.util.function.Consumer<String> log) {
        if (!THREADS.isCurrentThreadCpuTimeSupported()) {
            throw new IllegalStateException("this JVM cannot measure thread CPU time");
        }
        if (!THREADS.isThreadCpuTimeEnabled()) {
            THREADS.setThreadCpuTimeEnabled(true);
        }
        List<int[]> positions = ReferencePositions.moveLists().subList(0, positionCount);
        List<int[]> openings = new ArrayList<>();
        for (int g = 0; g < gameCount; g++) {
            openings.add(ReferencePositions.gameOpening(g));
        }
        List<Side> sides = b == null ? List.of(a) : List.of(a, b);
        int items = positionCount + gameCount;

        for (Side side : sides) { // warm-up: JIT compilation of both builds, untimed
            for (int[] moves : positions) {
                searchPosition(side, moves);
            }
        }

        Totals[] posTotals = {Totals.ZERO, Totals.ZERO};
        Totals[] gameTotals = {Totals.ZERO, Totals.ZERO};
        long[][] firstSignature = new long[2][items];
        boolean[] identical = new boolean[items];
        Arrays.fill(identical, b != null);
        double[] ratios = new double[b == null ? 0 : reps];
        for (int r = 0; r < reps; r++) {
            Totals[] rep = {Totals.ZERO, Totals.ZERO};
            for (int k = 0; k < items; k++) {
                boolean aFirst = (k + r) % 2 == 0;
                for (int turn = 0; turn < sides.size(); turn++) {
                    int s = sides.size() == 1 ? 0 : aFirst ? turn : 1 - turn;
                    Measurement m = k < positionCount
                            ? searchPosition(sides.get(s), positions.get(k))
                            : playGame(sides.get(s), openings.get(k - positionCount));
                    rep[s] = rep[s].plus(m.totals());
                    if (k < positionCount) {
                        posTotals[s] = posTotals[s].plus(m.totals());
                    } else {
                        gameTotals[s] = gameTotals[s].plus(m.totals());
                    }
                    if (r == 0) {
                        firstSignature[s][k] = m.signature();
                    } else if (firstSignature[s][k] != m.signature()) {
                        throw new IllegalStateException(sides.get(s).name() + " is not deterministic on item " + k);
                    }
                }
                if (b != null && r == 0) {
                    identical[k] = firstSignature[0][k] == firstSignature[1][k];
                }
            }
            if (b != null) {
                ratios[r] = rep[0].nodesPerCpuSecond() / rep[1].nodesPerCpuSecond();
                log.accept(String.format(Locale.ROOT, "rep %d: A %.0f nodes/CPU-s, B %.0f nodes/CPU-s, ratio %.4f",
                        r + 1, rep[0].nodesPerCpuSecond(), rep[1].nodesPerCpuSecond(), ratios[r]));
            } else {
                log.accept(String.format(Locale.ROOT, "rep %d: A %.0f nodes/CPU-s", r + 1, rep[0].nodesPerCpuSecond()));
            }
        }
        int samePositions = 0;
        int sameGames = 0;
        for (int k = 0; k < items; k++) {
            if (identical[k]) {
                if (k < positionCount) {
                    samePositions++;
                } else {
                    sameGames++;
                }
            }
        }
        int n = sides.size();
        return new Result(a.spec(), b == null ? null : b.spec(), Arrays.copyOf(posTotals, n),
                Arrays.copyOf(gameTotals, n), ratios, positionCount, gameCount, samePositions, sameGames);
    }

    private static Measurement searchPosition(Side side, int[] moves) {
        Build build = side.build();
        Object pos = build.position(moves);
        Searcher searcher = build.searcher(side.spec());
        long c0 = THREADS.getCurrentThreadCpuTime();
        long w0 = System.nanoTime();
        long[] r = build.search(searcher, pos);
        long wall = System.nanoTime() - w0;
        long cpu = THREADS.getCurrentThreadCpuTime() - c0;
        long h = ((r[0] * 31 + r[1]) * 31 + r[2]) * 31 + r[3];
        return new Measurement(new Totals(r[3], cpu, wall, 1), h);
    }

    private static Measurement playGame(Side side, int[] opening) {
        Build build = side.build();
        Object pos = build.position(opening);
        Object[] players = {build.player(side.spec()), build.player(side.spec())};
        long cpu = 0;
        long wall = 0;
        long moves = 0;
        long h = 17;
        while (!build.gameOver(pos)) {
            Object player = players[build.sideToMove(pos)];
            long c0 = THREADS.getCurrentThreadCpuTime();
            long w0 = System.nanoTime();
            int m = build.choose(player, pos);
            wall += System.nanoTime() - w0;
            cpu += THREADS.getCurrentThreadCpuTime() - c0;
            moves++;
            h = h * 31 + m;
            build.make(pos, m);
        }
        long nodes = build.totalNodes(players[0]) + build.totalNodes(players[1]);
        return new Measurement(new Totals(nodes, cpu, wall, moves), h * 31 + nodes);
    }

    /** True if a 1-minute load average forbids a report: above the core count (unknown load never refuses). */
    static boolean tooLoaded(double load, int cores) {
        return load > cores;
    }

    /** The spec for the frozen build: {@code eval=linear-r1} becomes {@code eval=linear} if the build lacks the alias. */
    static String specFor(Build build, String spec, java.util.function.Consumer<String> log) {
        if (spec.contains("eval=linear-r1") && !build.knowsEvaluator("linear-r1")) {
            log.accept("note: " + build.label + " has no evaluator linear-r1; it uses eval=linear, which held the "
                    + "round-1 weights there");
            return spec.replace("eval=linear-r1", "eval=linear");
        }
        return spec;
    }

    public static void main(String[] args) {
        List<String> specs = new ArrayList<>();
        Long nodes = null;
        int reps = 3;
        int positionCount = ReferencePositions.COUNT;
        int gameCount = ReferencePositions.FULL_GAMES;
        boolean ignoreLoad = false;
        boolean copy = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--nodes" -> nodes = Long.parseLong(value(args, ++i));
                case "--reps" -> reps = Integer.parseInt(value(args, ++i));
                case "--positions" -> positionCount = Integer.parseInt(value(args, ++i));
                case "--games" -> gameCount = Integer.parseInt(value(args, ++i));
                case "--ignore-load" -> ignoreLoad = true;
                case "--copy" -> copy = true;
                default -> {
                    if (args[i].startsWith("--")) {
                        throw new IllegalArgumentException("unknown option " + args[i]);
                    }
                    specs.add(args[i]);
                }
            }
        }
        if (specs.isEmpty() || specs.size() > 2 || copy && specs.size() == 2 || reps < 1 || positionCount < 0
                || positionCount > ReferencePositions.COUNT || gameCount < 0
                || gameCount > ReferencePositions.FULL_GAMES || positionCount + gameCount == 0) {
            throw new IllegalArgumentException("usage: Bench <specA> [<specB> | --copy] [--nodes N] [--reps R] "
                    + "[--positions 0.." + ReferencePositions.COUNT + "] [--games 0.." + ReferencePositions.FULL_GAMES
                    + "] [--ignore-load]");
        }
        if (nodes != null) {
            long n = nodes;
            specs.replaceAll(s -> Fingerprint.withNodes(s, n));
        }
        System.exit(bench(specs, copy, reps, positionCount, gameCount, ignoreLoad));
    }

    private static String value(String[] args, int i) {
        if (i >= args.length) {
            throw new IllegalArgumentException("missing value for " + args[i - 1]);
        }
        return args[i];
    }

    private static int bench(List<String> specs, boolean copy, int reps, int positionCount, int gameCount,
            boolean ignoreLoad) {
        java.util.function.Consumer<String> log = System.out::println;
        int cores = Runtime.getRuntime().availableProcessors();
        double loadBefore = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
        System.out.printf(Locale.ROOT, "load before: %.2f (1-minute average, %d cores)%n", loadBefore, cores);
        if (tooLoaded(loadBefore, cores) && !ignoreLoad) {
            System.out.printf(Locale.ROOT, "REFUSED: load %.2f exceeds %d cores; not started%n", loadBefore, cores);
            return 3;
        }
        Path referenceDir = Path.of(System.getProperty(REFERENCE_DIR_PROPERTY, DEFAULT_REFERENCE_DIR));
        try (Build current = Build.current();
                Build reference = copy ? Build.isolatedCopyOfCurrent()
                        : specs.size() == 1 ? Build.reference(referenceDir) : null) {
            Side a = new Side("A", current, specs.get(0));
            Side b = specs.size() == 2 ? new Side("B", current, specs.get(1))
                    : reference == null ? null : new Side("B", reference, specFor(reference, specs.get(0), log));
            if (specs.size() == 1 && reference == null) {
                log.accept("no frozen build in " + referenceDir.toAbsolutePath() + " (-D" + REFERENCE_DIR_PROPERTY
                        + "); measuring A only");
            }
            log.accept("A = " + a.spec() + " on " + a.build().label);
            if (b != null) {
                log.accept("B = " + b.spec() + " on " + b.build().label);
            }
            log.accept(String.format(Locale.ROOT, "items: %d reference positions + %d full games, %d timed "
                    + "repetitions, single thread, thread CPU time", positionCount, gameCount, reps));
            Result result = run(a, b, reps, positionCount, gameCount, log);
            double loadAfter = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
            for (int s = 0; s < (result.hasB() ? 2 : 1); s++) {
                String name = s == 0 ? "A" : "B";
                log.accept(name + " positions: " + result.positions()[s]);
                log.accept(name + " games:     " + result.games()[s]);
                log.accept(name + " all:       " + result.all(s));
            }
            if (result.hasB()) {
                log.accept(String.format(Locale.ROOT, "trees: identical on %d/%d positions and %d/%d games",
                        result.identicalPositions(), positionCount, result.identicalGames(), gameCount));
                StringBuilder reps2 = new StringBuilder();
                for (double r : result.repRatios()) {
                    reps2.append(String.format(Locale.ROOT, " %.4f", r));
                }
                log.accept(String.format(Locale.ROOT, "ratio nps(A)/nps(B): median %.4f (repetitions:%s)",
                        result.medianRatio(), reps2));
                long nodesB = PlayerSpec.parse(specs.size() == 2 ? specs.get(1) : specs.get(0)).nodes();
                log.accept(String.format(Locale.ROOT, "equal cost: nodes(A) = nodes(B) x ratio = %d x %.4f = %.0f",
                        nodesB, result.medianRatio(), nodesB * result.medianRatio()));
            }
            System.out.printf(Locale.ROOT, "load after: %.2f%n", loadAfter);
            boolean loaded = tooLoaded(loadBefore, cores) || tooLoaded(loadAfter, cores);
            if (loaded && !ignoreLoad) {
                System.out.printf(Locale.ROOT, "REFUSED: load %.2f exceeds %d cores; no result%n", loadAfter, cores);
                return 3;
            }
            System.out.printf(Locale.ROOT, "RESULT nps_a=%.0f cpu_ms_a=%.3f%s%s%n",
                    result.all(0).nodesPerCpuSecond(), result.all(0).cpuMillisPerMove(),
                    result.hasB() ? String.format(Locale.ROOT, " nps_b=%.0f cpu_ms_b=%.3f ratio=%.4f identical=%b",
                            result.all(1).nodesPerCpuSecond(), result.all(1).cpuMillisPerMove(), result.medianRatio(),
                            result.identicalTrees()) : "",
                    loaded ? " UNDER_LOAD" : "");
            return 0;
        }
    }
}
