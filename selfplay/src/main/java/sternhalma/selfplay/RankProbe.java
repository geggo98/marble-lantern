package sternhalma.selfplay;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongFunction;
import java.util.function.Supplier;

import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.search.AlphaBetaPlayer;
import sternhalma.search.DistanceEvaluator;
import sternhalma.search.Player;
import sternhalma.search.PlayerSpec;
import sternhalma.search.RaceEvaluator;
import sternhalma.search.RaceMove;
import sternhalma.search.RaceTableSource;
import sternhalma.search.RandomPlayer;
import sternhalma.search.Strategies;
import sternhalma.tablebase.LookupObserver;
import sternhalma.tablebase.ObservedRows1Counts;
import sternhalma.tablebase.RaceCounts;
import sternhalma.tablebase.RaceTable;

/**
 * The rank logger: which entries of the rows&gt;=1 race table does a player read, and how often? It plays games and
 * writes every table read as a record ({@link ProbeLog}); {@link ProbeReport} analyses the logs.
 *
 * <pre>
 *   RankProbe run --out DIR --source best|net|random --games N --seed S [--spec SPEC] [--net FILE] [--net-spec TEXT]
 *                 [--threads T] [--shard 10] [--opening-min 4] [--opening-max 10] [--shadow 0|1] [--progress SECONDS]
 *   RankProbe run --status --out DIR
 *   RankProbe report DIR... [--phase-width 50] [--budgets 1,2,5,10,20] [--emit-blocks FILE --from SOURCE --top N]
 * </pre>
 *
 * <p><b>Sources.</b> {@code best}: both sides play {@code --spec} (default {@link Strategies#BEST_SPEC} at 20000 nodes);
 * the table reads are the real ones of the leaf evaluator (role 0) and of race play (role 1), through
 * {@link ObservedRows1Counts}, which returns the table's own values. {@code net}: both sides play the MCTS player
 * {@code --net-spec} (default {@code mcts:sims=200,race=1,racerows=2} with {@code --net FILE}); {@code random}: both sides
 * move uniformly at random after the opening. A network or random player reads no rows&gt;=1 table itself, so for these
 * two sources every position of the game is <em>probed</em> (default {@code --shadow 1}): a BEST evaluator evaluates the
 * position (role 0: the two configurations) and BEST race play chooses a move in it (role 1: the position and every
 * successor of the side to move); the move is ignored. For {@code best} the probe is off by default ({@code --shadow 0});
 * with {@code --shadow 1} it adds the same probes at the games' own positions.
 *
 * <p>Every game starts with a random opening of {@code opening-min .. opening-max} plies ({@link OpeningSampler}, game id
 * and seed fix it) and is played to its end, without adjudication. The ply of a record is the game ply of the root of the
 * search that read the table.
 *
 * <p>The job follows the project's conventions ({@link Job}): shards of {@code --shard} games, resumable (a valid shard
 * is skipped, a broken one is moved to {@code *.corrupt}), atomic, abortable with SIGINT/SIGTERM, progress lines with
 * ETA on stderr, a lock ({@code .rankprobe.lock}) and {@code --status}. Exit codes: 0 done or nothing to do, 1 error,
 * 2 usage, 75 running already, 130/143 aborted.
 */
public final class RankProbe {

    static final String JOB = "rankprobe";
    static final int NODES = 20_000;

    private RankProbe() {
    }

    public static void main(String[] args) throws Exception {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) throws Exception {
        if (args.length == 0) {
            err.println("usage: RankProbe run|report ... (see the class comment)");
            return 2;
        }
        Map<String, String> opts = new HashMap<>();
        List<String> positional = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--status")) {
                opts.put("status", "1");
            } else if (args[i].startsWith("--")) {
                if (i + 1 >= args.length) {
                    err.println("missing value for " + args[i]);
                    return 2;
                }
                opts.put(args[i].substring(2), args[++i]);
            } else {
                positional.add(args[i]);
            }
        }
        try {
            return switch (args[0]) {
                case "run" -> runCommand(opts, err);
                case "report" -> ProbeReport.run(positional, opts, out, err);
                default -> {
                    err.println("unknown command " + args[0]);
                    yield 2;
                }
            };
        } catch (IllegalArgumentException e) {
            err.println("usage error: " + e.getMessage());
            return 2;
        }
    }

    /** The settings of a run; {@link #describe()} is stored in every shard and must match on resume. */
    record Settings(String source, String spec, String netSpec, Path net, long seed, int openingMin, int openingMax,
            boolean shadow) {

        Settings {
            if (!List.of("best", "net", "random").contains(source)) {
                throw new IllegalArgumentException("--source must be best, net or random, got " + source);
            }
            if (source.equals("net") && net == null) {
                throw new IllegalArgumentException("--source net needs --net FILE");
            }
        }

        String describe() {
            return "source=" + source + ";seed=" + seed + ";opening=uniform," + openingMin + "-" + openingMax
                    + ";shadow=" + (shadow ? 1 : 0)
                    + (source.equals("best") ? ";spec=" + spec : "")
                    + (source.equals("net") ? ";player=" + netSpec + ";net=" + net.getFileName() : "");
        }
    }

    static Settings settings(Map<String, String> opts) {
        String source = SelfPlay.require(opts, "source");
        String spec = opts.getOrDefault("spec", Strategies.BEST_SPEC + ",nodes=" + NODES);
        PlayerSpec.parse(spec); // fail early on a bad spec
        String netFile = opts.get("net");
        String netSpec = opts.getOrDefault("net-spec", "mcts:sims=200,race=1,racerows=2");
        String shadow = opts.getOrDefault("shadow", source.equals("best") ? "0" : "1");
        return new Settings(source, spec, netSpec, netFile == null ? null : Path.of(netFile),
                Long.parseLong(SelfPlay.require(opts, "seed")), SelfPlay.intOpt(opts, "opening-min", 4),
                SelfPlay.intOpt(opts, "opening-max", 10), shadow.equals("1"));
    }

    static int runCommand(Map<String, String> opts, PrintStream err) throws IOException, InterruptedException {
        Path out = Path.of(SelfPlay.require(opts, "out"));
        if (opts.containsKey("status")) {
            return Job.status(out, JOB, System.out);
        }
        Settings settings = settings(opts);
        int games = Integer.parseInt(SelfPlay.require(opts, "games"));
        int threads = SelfPlay.intOpt(opts, "threads", 1);
        ProbeJob job = new ProbeJob(out, settings, games, SelfPlay.intOpt(opts, "shard", 10), threads, err);
        job.progressSeconds = SelfPlay.doubleOpt(opts, "progress", 10);
        err.println(JOB + ": " + settings.describe() + "; games=" + games + "; threads=" + threads);
        return job.run();
    }

    /** The job: item = game id, shard = {@code rlog-<source>-s<seed>-<shard>.rlog.gz}. */
    static final class ProbeJob extends Job<ProbeLog.GameRows> {
        final Settings settings;
        private final java.util.concurrent.atomic.AtomicLong lookups = new java.util.concurrent.atomic.AtomicLong();
        private final java.util.concurrent.atomic.AtomicLong plies = new java.util.concurrent.atomic.AtomicLong();

        ProbeJob(Path dir, Settings settings, int games, int shardSize, int threads, PrintStream err) {
            super(dir, JOB, games, shardSize, threads, err);
            this.settings = settings;
        }

        @Override
        Path shardFile(int shard) {
            return dir.resolve(String.format(Locale.ROOT, "rlog-%s-s%d-%05d.rlog.gz", settings.source(), settings.seed(), shard));
        }

        @Override
        ShardState check(Path file, int shard, int first, int count) {
            ProbeLog.Shard s;
            try {
                s = ProbeLog.read(file);
            } catch (IOException | RuntimeException e) {
                return ShardState.CORRUPT;
            }
            if (!settings.describe().equals(s.manifest().get("settings"))) {
                mismatch("written with settings " + s.manifest().get("settings") + ", not " + settings.describe());
                return ShardState.MISMATCH;
            }
            boolean right = Integer.toString(first).equals(s.manifest().get("first"))
                    && Integer.toString(count).equals(s.manifest().get("games"));
            return right ? ShardState.COMPLETE : ShardState.CORRUPT;
        }

        @Override
        Supplier<Worker<ProbeLog.GameRows>> workers() {
            return () -> {
                ProbeGame game = new ProbeGame(settings);
                return id -> {
                    ProbeLog.GameRows r = game.play(id);
                    lookups.addAndGet(r.lookups());
                    plies.addAndGet(r.plies());
                    return r;
                };
            };
        }

        @Override
        String progressExtra() {
            return String.format(Locale.ROOT, "  %d lookups, %d plies", lookups.get(), plies.get());
        }

        @Override
        void write(Path file, int shard, int first, List<ProbeLog.GameRows> results) throws IOException {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("format", "sternhalma-rank-log");
            m.put("version", "1");
            m.put("source", settings.source());
            m.put("settings", settings.describe());
            ProbeLog.write(file, m, shard, first, results);
        }
    }

    /** Sets the recorder's ply before every move choice of the wrapped player. */
    private record PlyTagging(Player inner, RankRecorder recorder) implements Player {
        @Override
        public int choose(Position position) {
            recorder.ply(position.ply());
            return inner.choose(position);
        }

        @Override
        public String name() {
            return inner.name();
        }
    }

    /** Plays the games of one thread. */
    static final class ProbeGame {
        private final Settings settings;
        private final RuleSet rules = RuleSet.defaults(2);
        private final RankRecorder recorder = new RankRecorder();
        private final OpeningSampler openings;
        private final RaceTable rows1 = RaceTable.rows1();
        private final RaceTable rows2 = RaceTable.rows2();
        private final PlayerSpec spec;
        private final LongFunction<Player> netPlayers;
        // the probe of net and random games: a BEST evaluator and BEST race play over the observed table
        private final RaceEvaluator probeEvaluator;
        private final RaceMove probeRace;

        ProbeGame(Settings settings) {
            this.settings = settings;
            this.openings = new OpeningSampler(OpeningSampler.Mode.UNIFORM, settings.openingMin(), settings.openingMax(), 0,
                    1, null);
            this.spec = settings.source().equals("best") ? PlayerSpec.parse(settings.spec()) : null;
            this.netPlayers = settings.source().equals("net") ? NetPlayers.factory(settings.netSpec(), parameters(settings)) : null;
            this.probeEvaluator = settings.shadow()
                    ? new RaceEvaluator(new DistanceEvaluator(), observed(LookupObserver.LEAF), 1, false) : null;
            this.probeRace = settings.shadow() ? new RaceMove(observed(LookupObserver.RACEPLAY), 1) : null;
        }

        private static sternhalma.nn.Parameters parameters(Settings s) {
            try {
                return sternhalma.nn.Parameters.load(s.net());
            } catch (IOException e) {
                throw new IllegalArgumentException("cannot read the network " + s.net() + ": " + e.getMessage(), e);
            }
        }

        private RaceCounts observed(int role) {
            return new ObservedRows1Counts(rows1, rows2, recorder, role);
        }

        ProbeLog.GameRows play(int id) {
            recorder.start(id);
            Player[] players = new Player[2];
            switch (settings.source()) {
                case "best" -> {
                    RaceTableSource source = RaceTableSource.ofTables(
                            rows -> rows == 1 ? new RaceTableSource.Table(observed(LookupObserver.LEAF), 1) : RaceTableSource.exact(rows),
                            rows -> rows == 1 ? new RaceTableSource.Table(observed(LookupObserver.RACEPLAY), 1) : RaceTableSource.exact(rows));
                    for (int p = 0; p < 2; p++) {
                        AlphaBetaPlayer ab = spec.create(source);
                        players[p] = new PlyTagging(ab, recorder);
                    }
                }
                case "net" -> {
                    for (int p = 0; p < 2; p++) {
                        players[p] = netPlayers.apply(settings.seed() * 1_000_003L + 2L * id + p);
                    }
                }
                default -> {
                    for (int p = 0; p < 2; p++) {
                        players[p] = new RandomPlayer(settings.seed() * 1_000_003L + 2L * id + p);
                    }
                }
            }
            Position pos = Position.initial(rules);
            for (int m : openings.sample(rules, settings.seed(), id)) {
                pos.make(m);
            }
            while (pos.outcome() == null) {
                int[] legal = pos.legalMoves();
                if (legal.length == 0) {
                    break;
                }
                recorder.ply(pos.ply());
                if (settings.shadow()) {
                    probe(pos);
                }
                int move = players[pos.sideToMove()].choose(pos);
                if (java.util.Arrays.stream(legal).noneMatch(l -> l == move)) {
                    throw new IllegalStateException("illegal move " + move + " from " + players[pos.sideToMove()].name());
                }
                pos.make(move);
            }
            return recorder.finish(pos.ply());
        }

        /** The lookups that BEST would make in this position: its evaluator at a leaf and its race play at the root. */
        private void probe(Position pos) {
            probeEvaluator.evaluate(pos);
            probeRace.choose(pos);
        }
    }
}
