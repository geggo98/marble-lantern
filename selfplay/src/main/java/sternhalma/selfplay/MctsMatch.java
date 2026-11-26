package sternhalma.selfplay;

import java.io.BufferedWriter;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongFunction;

import sternhalma.engine.Outcome;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.engine.RuleSet.JumpRule;
import sternhalma.nn.Parameters;
import sternhalma.nn.PooledEvaluator;
import sternhalma.search.AlphaBetaPlayer;
import sternhalma.search.Elo;
import sternhalma.search.GameRunner;
import sternhalma.search.MctsPlayer;
import sternhalma.search.MctsSpec;
import sternhalma.search.Player;
import sternhalma.search.PlayerSpec;
import sternhalma.search.Strategies;
import sternhalma.tornado.GpuEvaluators;

/**
 * Paired games of a network player (raw policy or MCTS) against opponents, with the arithmetic and the openings of
 * {@link StrengthEstimate} (docs/training.md, "As implemented (block 2g)"), and the cost of both sides.
 *
 * <pre>
 *   MctsMatch --out DIR --subject mcts:sims=100,... [--net FILE] --vs ab:100 [--vs raw] [--vs mcts:...]
 *             [--pairs 100] [--seed 3001] [--threads 4] [--opening-plies 4] [--progress-seconds 10]
 *             [--jump adjacent|long] [--rows 4|5] [--ladder-spec SPEC] [--evaluator cpu|gpu [--compute-threads 8] [--eval-cohorts 2]] [--subject-seed-offset 0]
 * </pre>
 *
 * <p>{@code --jump long} plays the long hop (2 players; docs/rules.md, "Jump rule"): the openings are drawn under that rule,
 * the default {@code --ladder-spec} is {@code BROWSER_LONG_SPEC} (no race table exists for the long hop), and a ladder spec of
 * the other rule is refused. The subject may be any network the {@code nn.Evaluator} accepts, including a {@code classic1v}
 * network (3-wide value head; a two-player position uses its first two outputs).
 *
 * <p>{@code --rows 5} plays the 5-row board (15 pieces, move limit 250): the openings are drawn on it, the default
 * {@code --ladder-spec} is {@code BROWSER_ROWS5_SPEC} ({@code BEST_SPEC} uses race play, which the engine refuses on 5
 * rows), a ladder spec of the other size is refused, and the board size goes into the hash of the pairs file, so 4-row
 * and 5-row results never share a file. The subject's network must list 5 in its {@code triangle_rows}.
 *
 * <p>{@code --evaluator gpu} gives the <em>subject's</em> network the device (docs/training.md, "As implemented (block G1)"):
 * {@code --threads} pairs run at the same time as virtual threads (the games in flight), {@code --compute-threads} carriers
 * compute, and the subject's searches take their evaluations from one {@code PooledEvaluator}; the opponent stays on the CPU.
 * With the same network on both sides this is the strength-neutrality check of the device evaluator. Both players of a game
 * get the same seed, so with no noise and no sampling the two sides play the same moves until a float difference decides a
 * move; {@code --subject-seed-offset} gives the subject another seed (another noise and sampling stream) for a match whose
 * games differ for every pair.
 *
 * <p>An opponent is {@code ab:N} (the {@code --ladder-spec}, default {@code BEST_SPEC}, at N nodes, N = 1 is depth 1, as the ladder rungs), {@code raw}
 * (the raw policy of {@code --net}) or an {@code mcts:...} spec; a spec without {@code net} uses {@code --net}. Each
 * pair starts from the same seeded random opening and is played twice with the colours swapped.
 *
 * <p>The <em>subject</em> may also be classical: {@code ab:N} (the {@code --ladder-spec} at N nodes) or a full spec
 * {@code ab:...}. Then no network is needed, and a classical match against a classical opponent uses the same pair
 * files, lock and resume as a network match, with the openings and games of {@code Arena} for the same seed (the games of
 * a pair take their colours in the same order). In the pair lines the subject's {@code s_sims} column then holds its nodes.
 *
 * <p>Job conventions (docs/training.md, "Job conventions"): every finished pair is appended as one JSON line to
 * {@code DIR/pairs-<hash>.jsonl} (the hash of subject, opponent, seed and opening), and the state of a rerun is read from
 * these files only: finished pairs are skipped, a torn last line is ignored. A running tool holds a lock on
 * {@code DIR/.match.lock}; a second one exits with 75. SIGINT and SIGTERM stop after the running pairs finish (130, 143).
 * Progress is one line per interval on stderr with the rate of this run (pairs that were skipped do not count).
 */
final class MctsMatch {

    static final int EXIT_OK = 0;
    static final int EXIT_ERROR = 1;
    static final int EXIT_USAGE = 2;
    static final int EXIT_RUNNING = 75;
    static final int EXIT_ABORTED = 130;

    /** What one pair measured, from the point of view of the subject. */
    record PairLine(int pair, double score, int wins, int draws, int losses, int plies, long subjectCpuNanos,
            long subjectMoves, long subjectSims, long subjectRaceMoves, long opponentCpuNanos, long opponentMoves,
            long opponentNodes) {

        Map<String, Object> fields() {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("pair", pair);
            f.put("score", score);
            f.put("wins", wins);
            f.put("draws", draws);
            f.put("losses", losses);
            f.put("plies", plies);
            f.put("s_cpu_ns", subjectCpuNanos);
            f.put("s_moves", subjectMoves);
            f.put("s_sims", subjectSims);
            f.put("s_race_moves", subjectRaceMoves);
            f.put("o_cpu_ns", opponentCpuNanos);
            f.put("o_moves", opponentMoves);
            f.put("o_nodes", opponentNodes);
            return f;
        }

        static PairLine of(Map<String, String> m) {
            return new PairLine(Integer.parseInt(m.get("pair")), Double.parseDouble(m.get("score")),
                    Integer.parseInt(m.get("wins")), Integer.parseInt(m.get("draws")), Integer.parseInt(m.get("losses")),
                    Integer.parseInt(m.get("plies")), Long.parseLong(m.get("s_cpu_ns")), Long.parseLong(m.get("s_moves")),
                    Long.parseLong(m.get("s_sims")), Long.parseLong(m.get("s_race_moves")),
                    Long.parseLong(m.get("o_cpu_ns")), Long.parseLong(m.get("o_moves")), Long.parseLong(m.get("o_nodes")));
        }
    }

    /** The summary of the finished pairs of one opponent. */
    record Summary(String subject, String opponent, int pairs, long seed, double score, double scoreStandardError,
            double elo, double eloConfidence95, int wins, int draws, int losses, double averagePlies,
            double subjectCpuMillisPerMove, double subjectSimsPerSearchedMove, double subjectSimsPerCpuSecond,
            double subjectRaceMoveShare, double opponentCpuMillisPerMove, double opponentNodesPerMove) {

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "%s vs %s: Elo %+.0f +- %.0f (95%%), score %.3f +- %.3f, %d-%d-%d games,"
                    + " %d pairs, %.0f plies; subject %.1f CPU-ms/move, %.1f sims/searched move, %.0f sims/CPU-s, %.0f%%"
                    + " moves by the race module; opponent %.1f CPU-ms/move, %.0f nodes/move", subject, opponent, elo,
                    eloConfidence95, score, scoreStandardError, wins, draws, losses, pairs, averagePlies,
                    subjectCpuMillisPerMove, subjectSimsPerSearchedMove, subjectSimsPerCpuSecond,
                    100 * subjectRaceMoveShare, opponentCpuMillisPerMove, opponentNodesPerMove);
        }
    }

    private MctsMatch() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        Map<String, String> opts = new TreeMap<>();
        List<String> opponents = new ArrayList<>();
        try {
            for (int i = 0; i < args.length; i += 2) {
                if (!args[i].startsWith("--") || i + 1 >= args.length) {
                    throw new IllegalArgumentException("expected --key value, got " + args[i]);
                }
                String key = args[i].substring(2);
                if (key.equals("vs")) {
                    opponents.add(args[i + 1]);
                } else {
                    opts.put(key, args[i + 1]);
                }
            }
            if (!opts.containsKey("out") || !opts.containsKey("subject") || opponents.isEmpty()) {
                throw new IllegalArgumentException("usage: MctsMatch --out DIR --subject SPEC [--net FILE] --vs OPP"
                        + " [--vs OPP ...] [--pairs 100] [--seed 3001] [--threads 4] [--opening-plies 4]");
            }
            return match(opts, opponents, out, err);
        } catch (IllegalArgumentException e) {
            err.println("mctsmatch: " + e.getMessage());
            return EXIT_USAGE;
        } catch (IOException e) {
            err.println("mctsmatch: " + e);
            return EXIT_ERROR;
        }
    }

    private static int match(Map<String, String> opts, List<String> opponents, PrintStream out, PrintStream err)
            throws IOException {
        Path dir = Path.of(opts.get("out"));
        String subject = opts.get("subject");
        int pairs = Integer.parseInt(opts.getOrDefault("pairs", "100"));
        long seed = Long.parseLong(opts.getOrDefault("seed", "3001"));
        int threads = Integer.parseInt(opts.getOrDefault("threads", "4"));
        int openingPlies = Integer.parseInt(opts.getOrDefault("opening-plies", String.valueOf(StrengthEstimate.OPENING_PLIES)));
        double progressSeconds = Double.parseDouble(opts.getOrDefault("progress-seconds", "10"));
        if (pairs < 2 || threads < 1 || openingPlies < 0) {
            throw new IllegalArgumentException("need pairs >= 2, threads >= 1, opening-plies >= 0");
        }
        Parameters own = opts.containsKey("net") ? Parameters.load(Path.of(opts.get("net"))) : null;
        JumpRule jump = SelfPlay.jump(opts.getOrDefault("jump", "adjacent"));
        RuleSet.TriangleRows rows = SelfPlay.rows(opts.getOrDefault("rows", "4"));
        String ladderSpec = opts.getOrDefault("ladder-spec", StrengthEstimate.defaultBase(jump, rows));
        StrengthEstimate.requireJump(ladderSpec, jump);
        if (PlayerSpec.parse(ladderSpec).rows() != rows) {
            throw new IllegalArgumentException("the ladder spec plays rows=" + PlayerSpec.parse(ladderSpec).rows().rows()
                    + " but the games use rows=" + rows.rows() + ": " + ladderSpec);
        }
        if (own != null && !own.config().supports(rows)) {
            throw new IllegalArgumentException("the network " + opts.get("net") + " is for "
                    + sternhalma.nn.NetworkConfig.ROWS_KEY + "=" + sternhalma.nn.NetworkConfig.rowsText(own.config().rows())
                    + " but the games use rows=" + rows.rows());
        }
        String evaluator = opts.getOrDefault("evaluator", "cpu");
        long seedOffset = Long.parseLong(opts.getOrDefault("subject-seed-offset", "0"));
        int computeThreads = Integer.parseInt(opts.getOrDefault("compute-threads", "8"));
        int cohorts = Integer.parseInt(opts.getOrDefault("eval-cohorts", "2"));
        if (!evaluator.equals("cpu") && !evaluator.equals("gpu")) {
            throw new IllegalArgumentException("--evaluator must be cpu or gpu, got " + evaluator);
        }
        GpuEvaluators.Session session = null;
        boolean classicalSubject = isClassical(subject);
        if (classicalSubject && evaluator.equals("gpu")) {
            throw new IllegalArgumentException("--evaluator gpu needs a network subject, got " + subject);
        }
        LongFunction<Player> subjectFactory = classicalSubject ? classicalFactory(subject, ladderSpec, jump, rows)
                : NetPlayers.factory(subject, own);
        Scope scope = null;
        if (evaluator.equals("gpu")) {
            String why = GpuEvaluators.unavailableReason();
            if (why != null) {
                throw new IllegalArgumentException("--evaluator gpu: " + why);
            }
            MctsSpec spec = MctsSpec.parse(subject);
            Parameters parameters = spec.net() != null ? Parameters.load(spec.net()) : own;
            if (parameters == null) {
                throw new IllegalArgumentException("--evaluator gpu needs the network of the subject (net= or --net)");
            }
            session = GpuEvaluators.open(parameters, 0, cohorts, threads);
            PooledEvaluator.Model model = session.model();
            ThreadLocal<PooledEvaluator.Client> mine = ThreadLocal.withInitial(model::newClient);
            subjectFactory = s -> spec.create(mine.get(), s);
            scope = new Scope(mine, computeThreads);
        }
        if (seedOffset != 0) {
            LongFunction<Player> inner = subjectFactory;
            subjectFactory = s -> inner.apply(s + seedOffset);
        }
        List<LongFunction<Player>> opponentFactories = new ArrayList<>();
        for (String opponent : opponents) {
            opponentFactories.add(opponentFactory(opponent, ladderSpec, own, jump, rows));
        }
        Files.createDirectories(dir);
        try (FileChannel lockChannel = FileChannel.open(dir.resolve(".match.lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE); FileLock lock = tryLock(lockChannel)) {
            if (lock == null) {
                err.println("mctsmatch: running already (" + dir.resolve(".match.lock") + " is held)");
                return EXIT_RUNNING;
            }
            LongFunction<Player> finalSubject = subjectFactory;
            Scope finalScope = scope;
            GpuEvaluators.Session finalSession = session;
            AtomicBoolean stop = new AtomicBoolean();
            CountDownLatch finished = new CountDownLatch(1);
            Thread hook = new Thread(() -> {
                stop.set(true);
                try {
                    finished.await(120, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "mctsmatch-shutdown");
            Runtime.getRuntime().addShutdownHook(hook);
            int ok = 0;
            int skip = 0;
            int left = 0;
            try {
                for (int k = 0; k < opponents.size(); k++) {
                    String opponent = opponents.get(k);
                    Path file = dir.resolve("pairs-" + Gen0.sha256Hex((subject + "|" + opponent + "|" + seed + "|"
                            + openingPlies + "|" + ladderSpec + "|" + (own == null ? "" : "own")
                            + (evaluator.equals("gpu") ? "|gpu" : "") + (jump == JumpRule.LONG ? "|long" : "")
                            + (rows == RuleSet.TriangleRows.FOUR ? "" : "|rows" + rows.rows()) + (seedOffset != 0 ? "|offset" + seedOffset : ""))
                            .getBytes(StandardCharsets.UTF_8))
                            .substring(0, 12) + ".jsonl");
                    Map<Integer, PairLine> done = read(file);
                    int todo = 0;
                    for (int p = 0; p < pairs; p++) {
                        todo += done.containsKey(p) ? 0 : 1;
                    }
                    skip += pairs - todo;
                    if (todo > 0) {
                        err.printf(Locale.ROOT, "mctsmatch: %s vs %s: %d pairs to play, %d finished before (%s)%n", subject,
                                opponent, todo, pairs - todo, file);
                        ok += play(file, finalSubject, opponentFactories.get(k), subject, opponent, pairs, seed,
                                openingPlies, threads, done, stop, err, progressSeconds, finalScope,
                                RuleSet.defaults(2, rows).withJumpRule(jump));
                        done = read(file);
                    }
                    if (stop.get()) {
                        left += pairs - done.size();
                        continue;
                    }
                    out.println(summary(subject, opponent, pairs, seed, done));
                }
                err.printf(Locale.ROOT, "mctsmatch: %d ok, %d skip, 0 error, %d left%s%n", ok, skip, left,
                        stop.get() ? " - aborted" : left == 0 ? " - nothing more to do" : "");
                return stop.get() ? EXIT_ABORTED : EXIT_OK;
            } finally {
                finished.countDown();
                try {
                    Runtime.getRuntime().removeShutdownHook(hook);
                } catch (IllegalStateException e) {
                    // shutting down because of a signal
                }
                if (finalSession != null) {
                    finalSession.close();
                }
            }
        }
    }

    /**
     * The games of a match on the device: the pairs are virtual threads on a scheduler of {@code computeThreads} carriers, and
     * a pair is one active game of the pooled evaluator (its two games run one after the other with the same client).
     */
    private record Scope(ThreadLocal<PooledEvaluator.Client> clients, int computeThreads) {

        ExecutorService pool(int threads) {
            System.setProperty("jdk.virtualThreadScheduler.parallelism", Integer.toString(computeThreads));
            System.setProperty("jdk.virtualThreadScheduler.maxPoolSize", Integer.toString(computeThreads));
            return Executors.newFixedThreadPool(threads, Thread.ofVirtual().factory());
        }

        void begin() {
            clients.get().begin();
        }

        void end() {
            clients.get().end();
        }
    }

    private static FileLock tryLock(FileChannel channel) throws IOException {
        try {
            return channel.tryLock();
        } catch (OverlappingFileLockException e) {
            return null;
        }
    }

    private static LongFunction<Player> opponentFactory(String text, String ladderSpec, Parameters own, JumpRule jump,
            RuleSet.TriangleRows rows) {
        if (isClassical(text)) {
            return classicalFactory(text, ladderSpec, jump, rows);
        }
        return NetPlayers.factory(text, own);
    }

    /** Whether the text is a classical (alpha-beta) player: {@code ab:N} or a full spec {@code ab:...}. */
    static boolean isClassical(String text) {
        return text.startsWith("ab:");
    }

    /**
     * A factory of classical players. {@code ab:N} (digits only) is the {@code ladderSpec} at N nodes (N = 1 is depth 1;
     * 2 to 99 are refused, see {@link StrengthEstimate#requireDistinctRungs}); any other {@code ab:...} text is a full
     * {@link PlayerSpec} that must play the jump rule and the board size of the games. The argument of the factory is
     * ignored: an alpha-beta player has no random stream.
     */
    static LongFunction<Player> classicalFactory(String text, String ladderSpec, JumpRule jump, RuleSet.TriangleRows rows) {
        String number = text.substring(3);
        PlayerSpec spec;
        if (!number.isEmpty() && number.chars().allMatch(c -> c >= '0' && c <= '9')) {
            int nodes = Integer.parseInt(number);
            StrengthEstimate.requireDistinctRungs(List.of(nodes));
            spec = PlayerSpec.parse(StrengthEstimate.spec(ladderSpec, nodes));
        } else {
            StrengthEstimate.requireJump(text, jump);
            spec = PlayerSpec.parse(text);
            if (spec.rows() != rows) {
                throw new IllegalArgumentException("the player " + text + " plays rows=" + spec.rows().rows()
                        + " but the games use rows=" + rows.rows());
            }
        }
        PlayerSpec chosen = spec;
        return seed -> chosen.create();
    }

    /** The finished pairs of a file; a line that does not parse (a torn last line) is ignored. */
    static Map<Integer, PairLine> read(Path file) throws IOException {
        Map<Integer, PairLine> result = new TreeMap<>();
        if (!Files.isRegularFile(file)) {
            return result;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            Map<String, String> m = RunMonitor.parse(line);
            if (m == null || !m.containsKey("pair") || !m.containsKey("o_nodes")) {
                continue;
            }
            try {
                PairLine p = PairLine.of(m);
                result.put(p.pair(), p);
            } catch (NumberFormatException e) {
                // a torn line
            }
        }
        return result;
    }

    private static int play(Path file, LongFunction<Player> subjectFactory, LongFunction<Player> opponentFactory,
            String subject, String opponent, int pairs, long seed, int openingPlies, int threads,
            Map<Integer, PairLine> done, AtomicBoolean stop, PrintStream err, double progressSeconds, Scope scope,
            RuleSet rules) throws IOException {
        int todo = pairs - done.size();
        AtomicInteger finished = new AtomicInteger();
        long t0 = System.nanoTime();
        long[] last = {t0};
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND); ExecutorService pool = scope == null ? Executors.newFixedThreadPool(threads)
                        : scope.pool(threads)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int pair = 0; pair < pairs; pair++) {
                if (done.containsKey(pair)) {
                    continue;
                }
                int index = pair;
                futures.add(pool.submit(() -> {
                    if (stop.get()) {
                        return;
                    }
                    Position start = StrengthEstimate.opening(rules, seed, index, openingPlies);
                    PairLine line;
                    if (scope != null) {
                        scope.begin();
                    }
                    try {
                        line = playPair(start, index, subjectFactory, opponentFactory, (seed * 1_000_003L + index) * 2);
                    } finally {
                        if (scope != null) {
                            scope.end();
                        }
                    }
                    synchronized (writer) {
                        try {
                            writer.write(RunMonitor.json(line.fields()));
                            writer.newLine();
                            writer.flush();
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                        int n = finished.incrementAndGet();
                        long now = System.nanoTime();
                        if (now - last[0] >= progressSeconds * 1e9 || n == todo) {
                            last[0] = now;
                            double el = (now - t0) / 1e9;
                            double rate = n / el;
                            err.printf(Locale.ROOT, "%s vs %s: %d/%d pairs  %.3f/s  ETA %s  elapsed %s%n", subject, opponent,
                                    n, todo, rate, Gen0.duration(Math.round((todo - n) / rate)),
                                    Gen0.duration(Math.round(el)));
                        }
                    }
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception e) {
                    futures.forEach(x -> x.cancel(false));
                    throw new IllegalStateException("game failed", e.getCause() != null ? e.getCause() : e);
                }
            }
        }
        return finished.get();
    }

    static PairLine playPair(Position start, int pair, LongFunction<Player> subjectFactory,
            LongFunction<Player> opponentFactory, long gameSeed) {
        double score = 0;
        int wins = 0;
        int draws = 0;
        int losses = 0;
        int plies = 0;
        long sCpu = 0;
        long sMoves = 0;
        long sSims = 0;
        long sRace = 0;
        long oCpu = 0;
        long oMoves = 0;
        long oNodes = 0;
        for (int index = 0; index < 2; index++) {
            Player mine = subjectFactory.apply(gameSeed + index);
            Player theirs = opponentFactory.apply(gameSeed + index);
            GameRunner.Result r = index == 0 ? GameRunner.play(start, mine, theirs) : GameRunner.play(start, theirs, mine);
            Outcome outcome = r.outcome();
            score += (1.0 + outcome.value(index)) / 2.0;
            if (outcome.hasWinner()) {
                if (outcome.winner() == index) {
                    wins++;
                } else {
                    losses++;
                }
            } else {
                draws++;
            }
            plies += r.plies();
            sCpu += r.cpuNanos()[index];
            sMoves += r.moves()[index];
            oCpu += r.cpuNanos()[1 - index];
            oMoves += r.moves()[1 - index];
            if (NetPlayers.unwrap(mine) instanceof MctsPlayer m) {
                sSims += m.totalSims();
                sRace += m.raceMoves();
            } else if (NetPlayers.unwrap(mine) instanceof AlphaBetaPlayer a) {
                sSims += a.totalNodes(); // a classical subject: nodes are counted in the sims column
            }
            if (NetPlayers.unwrap(theirs) instanceof AlphaBetaPlayer a) {
                oNodes += a.totalNodes();
            } else if (NetPlayers.unwrap(theirs) instanceof MctsPlayer m) {
                oNodes += m.totalSims();
            }
        }
        return new PairLine(pair, score / 2.0, wins, draws, losses, plies, sCpu, sMoves, sSims, sRace, oCpu, oMoves, oNodes);
    }

    /** The summary of the first {@code pairs} pairs of the file. */
    static Summary summarize(String subject, String opponent, int pairs, long seed, Map<Integer, PairLine> lines) {
        double[] scores = new double[pairs];
        int wins = 0;
        int draws = 0;
        int losses = 0;
        long plies = 0;
        long sCpu = 0;
        long sMoves = 0;
        long sSims = 0;
        long sRace = 0;
        long oCpu = 0;
        long oMoves = 0;
        long oNodes = 0;
        for (int p = 0; p < pairs; p++) {
            PairLine l = lines.get(p);
            if (l == null) {
                throw new IllegalStateException("pair " + p + " is missing");
            }
            scores[p] = l.score();
            wins += l.wins();
            draws += l.draws();
            losses += l.losses();
            plies += l.plies();
            sCpu += l.subjectCpuNanos();
            sMoves += l.subjectMoves();
            sSims += l.subjectSims();
            sRace += l.subjectRaceMoves();
            oCpu += l.opponentCpuNanos();
            oMoves += l.opponentMoves();
            oNodes += l.opponentNodes();
        }
        double score = Elo.mean(scores);
        double se = Elo.standardError(scores);
        long searched = sMoves - sRace;
        return new Summary(subject, opponent, pairs, seed, score, se, Elo.fromScore(score), Elo.confidence95(score, se),
                wins, draws, losses, (double) plies / (2.0 * pairs), sCpu / 1e6 / Math.max(1, sMoves),
                (double) sSims / Math.max(1, searched), sSims / Math.max(1e-9, sCpu / 1e9), (double) sRace / Math.max(1, sMoves),
                oCpu / 1e6 / Math.max(1, oMoves), (double) oNodes / Math.max(1, oMoves));
    }

    private static String summary(String subject, String opponent, int pairs, long seed, Map<Integer, PairLine> lines) {
        return summarize(subject, opponent, pairs, seed, lines).toString();
    }
}
