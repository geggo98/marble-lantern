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
import java.nio.file.StandardCopyOption;
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
import sternhalma.search.GameRunner;
import sternhalma.search.MctsPlayer;
import sternhalma.search.Player;
import sternhalma.search.RandomPlayer;
import sternhalma.search.ThreePlayerSpec;

/**
 * Three-player games of a candidate against two identical opponents, with the candidate rotated through the seats
 * (docs/training.md, "Evaluating variants"). Seat 0 moves first, so the seat is worth something; every opening is
 * therefore played three times, the candidate in seat 0, 1 and 2, and the report gives the win share per seat and in
 * total. With three players and no skill difference each player wins a third of the games, so chance is 1/3.
 *
 * <pre>
 *   ThreeSeatMatch --out DIR (--candidate SPEC | --control ab3|random) [--net FILE] --vs OPP [--vs OPP ...]
 *                  [--jump adjacent|long] [--openings 100] [--seed 4001] [--threads 4] [--opening-plies 6]
 *                  [--progress-seconds 10]
 * </pre>
 *
 * <p>A player text is {@code random}, {@code ab3:nodes=N,...} ({@link ThreePlayerSpec}; a missing {@code jump} key is set
 * to {@code --jump}, a different one is refused) or {@code mcts:...} (a network player; a spec without {@code net} uses
 * {@code --net}, which may be a {@code classic1v} network). Each {@code --vs} is one match: both opponent seats play that
 * player. {@code --control ab3} replaces the candidate by the opponent's own spec (an {@code ab3} spec) and
 * {@code --control random} by a random player, so that the share of the candidate seats measures the advantage of the seat
 * (and of the draw handling) alone, with no strength difference.
 *
 * <p>The unit of work is an opening: its three games are played one after the other by one thread and written as one JSON
 * line to {@code DIR/openings-<hash>.jsonl} (the hash covers candidate, opponent, seed, opening plies, rule and network
 * presence). Openings and every player's seed depend on (seed, opening, rotation) only, so the result does not depend on the
 * thread count or timing. Job conventions as for {@link MctsMatch}: the state of a rerun comes from the lines alone (finished
 * openings are skipped, a torn last line is ignored), a lock on {@code DIR/.threeseat.lock} makes a second run exit with 75,
 * SIGINT and SIGTERM stop after the running openings (130, 143), progress is a line with count, rate, ETA and elapsed time every
 * {@code --progress-seconds}, and the rate counts only openings played by this run. The result of a match is printed as text
 * and written as JSON to {@code DIR/result-<hash>.json}.
 *
 * <p>A draw (no winner: move limit, repetition or no move) is neither a win nor a loss of the candidate. The interval of the
 * total share treats the games as independent; the three games of an opening are not, so that interval is a little too
 * narrow, and a comparison of two candidates should use {@link PairedReport}, which pairs by opening.
 */
final class ThreeSeatMatch {

    static final int EXIT_OK = 0;
    static final int EXIT_ERROR = 1;
    static final int EXIT_USAGE = 2;
    static final int EXIT_RUNNING = 75;
    static final int EXIT_ABORTED = 130;
    static final int DEFAULT_OPENING_PLIES = 6;
    static final int SEATS = 3;

    /** The result of the candidate in one game. */
    enum Result {
        WIN('W'), LOSS('L'), DRAW('D');

        final char code;

        Result(char code) {
            this.code = code;
        }

        static Result of(String code) {
            for (Result r : values()) {
                if (code.length() == 1 && r.code == code.charAt(0)) {
                    return r;
                }
            }
            throw new NumberFormatException("result " + code);
        }
    }

    /**
     * The three games of one opening, from the candidate's side; index = the candidate's seat.
     *
     * @param openingHash the Zobrist hash of the position after the opening, so that two result files can be checked to
     *                    hold the same openings
     */
    record OpeningLine(int opening, long openingHash, Result[] results, int[] plies, boolean[] moveLimit,
            long candidateCpuNanos, long candidateMoves, long candidateSims) {

        Map<String, Object> fields() {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("opening", opening);
            f.put("open_hash", Long.toHexString(openingHash));
            for (int seat = 0; seat < SEATS; seat++) {
                f.put("r" + seat, String.valueOf(results[seat].code));
                f.put("plies" + seat, plies[seat]);
                f.put("ml" + seat, moveLimit[seat]);
            }
            f.put("c_cpu_ns", candidateCpuNanos);
            f.put("c_moves", candidateMoves);
            f.put("c_sims", candidateSims);
            return f;
        }

        static OpeningLine of(Map<String, String> m) {
            Result[] results = new Result[SEATS];
            int[] plies = new int[SEATS];
            boolean[] ml = new boolean[SEATS];
            for (int seat = 0; seat < SEATS; seat++) {
                results[seat] = Result.of(m.get("r" + seat));
                plies[seat] = Integer.parseInt(m.get("plies" + seat));
                ml[seat] = Boolean.parseBoolean(m.get("ml" + seat));
            }
            return new OpeningLine(Integer.parseInt(m.get("opening")), Long.parseUnsignedLong(m.get("open_hash"), 16), results,
                    plies, ml, Long.parseLong(m.get("c_cpu_ns")), Long.parseLong(m.get("c_moves")),
                    Long.parseLong(m.get("c_sims")));
        }

        /** The share of the three games the candidate won: 0, 1/3, 2/3 or 1 (the unit of {@link PairedReport}). */
        double winShare() {
            int wins = 0;
            for (Result r : results) {
                wins += r == Result.WIN ? 1 : 0;
            }
            return wins / (double) SEATS;
        }
    }

    /** A win share with its exact 95 % interval. */
    record Share(int wins, int games, double share, double lower, double upper) {

        static Share of(int wins, int games) {
            double[] ci = ClopperPearson.interval95(wins, games);
            return new Share(wins, games, games == 0 ? Double.NaN : (double) wins / games, ci[0], ci[1]);
        }

        /** Where chance (1/3) lies relative to the interval: the share is above it, below it, or compatible with it. */
        String versusChance() {
            double chance = 1.0 / SEATS;
            return lower > chance ? "above chance" : upper < chance ? "below chance" : "compatible with chance";
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "%d/%d  share %.3f  CP95 [%.3f, %.3f]  %s", wins, games, share, lower, upper,
                    versusChance());
        }
    }

    /** The summary of one match. {@code seats} and {@code losses}/{@code draws} are indexed by the candidate's seat. */
    record Summary(String candidate, String opponent, String jump, int openings, long seed, Share[] seats, int[] losses,
            int[] draws, Share total, double drawShare, double moveLimitShare, double meanPlies, boolean control) {

        String text() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.ROOT, "%s%s vs 2 x %s, jump=%s, %d openings x 3 seats = %d games, chance 1/3%n",
                    control ? "control " : "", candidate, opponent, jump, openings, openings * SEATS));
            for (int seat = 0; seat < SEATS; seat++) {
                sb.append(String.format(Locale.ROOT, "  seat %d: %s; %d losses, %d draws%n", seat, seats[seat], losses[seat],
                        draws[seat]));
            }
            sb.append(String.format(Locale.ROOT, "  total:  %s%n", total));
            sb.append(String.format(Locale.ROOT, "  draws (no winner) %.1f%%, of which at the move limit %.1f%%; mean game length"
                    + " %.1f plies%n", 100 * drawShare, 100 * moveLimitShare, meanPlies));
            return sb.toString();
        }

        Map<String, Object> fields() {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("candidate", candidate);
            f.put("opponent", opponent);
            f.put("control", control);
            f.put("jump", jump);
            f.put("openings", openings);
            f.put("games", openings * SEATS);
            f.put("seed", seed);
            for (int seat = 0; seat < SEATS; seat++) {
                f.put("seat" + seat + "_wins", seats[seat].wins());
                f.put("seat" + seat + "_losses", losses[seat]);
                f.put("seat" + seat + "_draws", draws[seat]);
                f.put("seat" + seat + "_share", seats[seat].share());
                f.put("seat" + seat + "_ci95_lower", seats[seat].lower());
                f.put("seat" + seat + "_ci95_upper", seats[seat].upper());
            }
            f.put("total_wins", total.wins());
            f.put("total_share", total.share());
            f.put("total_ci95_lower", total.lower());
            f.put("total_ci95_upper", total.upper());
            f.put("chance", 1.0 / SEATS);
            f.put("versus_chance", total.versusChance());
            f.put("draw_share", drawShare);
            f.put("move_limit_share", moveLimitShare);
            f.put("mean_plies", meanPlies);
            return f;
        }
    }

    private ThreeSeatMatch() {
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
            if (!opts.containsKey("out") || opponents.isEmpty() || opts.containsKey("candidate") == opts.containsKey("control")) {
                throw new IllegalArgumentException("usage: ThreeSeatMatch --out DIR (--candidate SPEC | --control ab3|random)"
                        + " [--net FILE] --vs OPP [--vs OPP ...] [--jump adjacent|long] [--openings 100] [--seed 4001]"
                        + " [--threads 4] [--opening-plies 6] [--progress-seconds 10]");
            }
            return match(opts, opponents, out, err);
        } catch (IllegalArgumentException e) {
            err.println("threeseatmatch: " + e.getMessage());
            return EXIT_USAGE;
        } catch (IOException e) {
            err.println("threeseatmatch: " + e);
            return EXIT_ERROR;
        }
    }

    /** The text of the candidate of a match: the {@code --candidate}, or the control player of the opponent. */
    static String candidateText(Map<String, String> opts, String opponent) {
        String control = opts.get("control");
        if (control == null) {
            return opts.get("candidate");
        }
        return switch (control) {
            case "ab3" -> {
                if (!opponent.startsWith("ab3")) {
                    throw new IllegalArgumentException("--control ab3 needs an ab3 opponent, got " + opponent);
                }
                yield opponent;
            }
            case "random" -> "random";
            default -> throw new IllegalArgumentException("--control must be ab3 or random, got " + control);
        };
    }

    /**
     * A factory of players for one text. The argument of the factory seeds the player. An {@code ab3} spec gets the jump
     * rule of the games if it names none; a spec that names the other rule is refused.
     */
    static LongFunction<Player> factory(String text, Parameters own, JumpRule jump) {
        if (text.equals("random")) {
            return RandomPlayer::new;
        }
        if (text.startsWith("ab3")) {
            ThreePlayerSpec spec = ThreePlayerSpec.parse(withJump(text, jump));
            if (spec.jumpRule() != jump) {
                throw new IllegalArgumentException(text + " plays jump=" + spec.jumpRule().name().toLowerCase(Locale.ROOT)
                        + " but the games use jump=" + jump.name().toLowerCase(Locale.ROOT));
            }
            return seed -> spec.create();
        }
        if (NetPlayers.isNetPlayer(text) && !text.equals(NetPlayers.RAW)) {
            return NetPlayers.factory(text, own);
        }
        throw new IllegalArgumentException("a three-player player is random, ab3:... or mcts:..., got " + text);
    }

    /** {@code text} with {@code jump=long} added to an ab3 spec that names no rule ({@code adjacent} is the default). */
    static String withJump(String text, JumpRule jump) {
        if (jump == JumpRule.ADJACENT || text.matches(".*[:,]jump=.*")) {
            return text;
        }
        String separator = !text.contains(":") ? ":" : text.endsWith(":") ? "" : ",";
        return text + separator + "jump=long";
    }

    private static int match(Map<String, String> opts, List<String> opponents, PrintStream out, PrintStream err)
            throws IOException {
        Path dir = Path.of(opts.get("out"));
        int openings = Integer.parseInt(opts.getOrDefault("openings", "100"));
        long seed = Long.parseLong(opts.getOrDefault("seed", "4001"));
        int threads = Integer.parseInt(opts.getOrDefault("threads", "4"));
        int openingPlies = Integer.parseInt(opts.getOrDefault("opening-plies", String.valueOf(DEFAULT_OPENING_PLIES)));
        double progressSeconds = Double.parseDouble(opts.getOrDefault("progress-seconds", "10"));
        JumpRule jump = SelfPlay.jump(opts.getOrDefault("jump", "adjacent"));
        boolean control = opts.containsKey("control");
        if (openings < 1 || threads < 1 || openingPlies < 0) {
            throw new IllegalArgumentException("need openings >= 1, threads >= 1, opening-plies >= 0");
        }
        Parameters own = opts.containsKey("net") ? Parameters.load(Path.of(opts.get("net"))) : null;
        List<String> candidates = new ArrayList<>();
        List<LongFunction<Player>> candidateFactories = new ArrayList<>();
        List<LongFunction<Player>> opponentFactories = new ArrayList<>();
        for (String opponent : opponents) {
            String candidate = candidateText(opts, opponent);
            candidates.add(candidate);
            candidateFactories.add(factory(candidate, own, jump));
            opponentFactories.add(factory(opponent, own, jump));
        }
        Files.createDirectories(dir);
        try (FileChannel lockChannel = FileChannel.open(dir.resolve(".threeseat.lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE); FileLock lock = tryLock(lockChannel)) {
            if (lock == null) {
                err.println("threeseatmatch: running already (" + dir.resolve(".threeseat.lock") + " is held)");
                return EXIT_RUNNING;
            }
            AtomicBoolean stop = new AtomicBoolean();
            CountDownLatch finished = new CountDownLatch(1);
            Thread hook = new Thread(() -> {
                stop.set(true);
                try {
                    finished.await(120, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "threeseatmatch-shutdown");
            Runtime.getRuntime().addShutdownHook(hook);
            int ok = 0;
            int skip = 0;
            int left = 0;
            try {
                for (int k = 0; k < opponents.size(); k++) {
                    String candidate = candidates.get(k);
                    String opponent = opponents.get(k);
                    String hash = Gen0.sha256Hex((candidate + "|" + opponent + "|" + seed + "|" + openingPlies + "|" + jump + "|"
                            + (own == null ? "" : "own")).getBytes(StandardCharsets.UTF_8)).substring(0, 12);
                    Path file = dir.resolve("openings-" + hash + ".jsonl");
                    Map<Integer, OpeningLine> done = read(file);
                    int todo = 0;
                    for (int o = 0; o < openings; o++) {
                        todo += done.containsKey(o) ? 0 : 1;
                    }
                    skip += openings - todo;
                    if (todo > 0) {
                        err.printf(Locale.ROOT, "threeseatmatch: %s vs 2 x %s: %d openings to play, %d finished before (%s)%n",
                                candidate, opponent, todo, openings - todo, file);
                        ok += play(file, candidateFactories.get(k), opponentFactories.get(k), candidate, opponent, openings,
                                seed, openingPlies, jump, threads, done, stop, err, progressSeconds);
                        done = read(file);
                    }
                    if (stop.get()) {
                        left += openings - done.size();
                        continue;
                    }
                    Summary s = summarize(candidate, opponent, jump, openings, seed, control, done);
                    out.print(s.text());
                    Path result = dir.resolve("result-" + hash + ".json");
                    Path tmp = result.resolveSibling(result.getFileName() + ".tmp." + ProcessHandle.current().pid());
                    Files.writeString(tmp, RunMonitor.json(s.fields()) + "\n", StandardCharsets.UTF_8);
                    Files.move(tmp, result, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                }
                err.printf(Locale.ROOT, "threeseatmatch: %d ok, %d skip, 0 error, %d left%s%n", ok, skip, left,
                        stop.get() ? " - aborted" : left == 0 ? " - nothing more to do" : "");
                return stop.get() ? EXIT_ABORTED : EXIT_OK;
            } finally {
                finished.countDown();
                try {
                    Runtime.getRuntime().removeShutdownHook(hook);
                } catch (IllegalStateException e) {
                    // shutting down because of a signal
                }
            }
        }
    }

    private static FileLock tryLock(FileChannel channel) throws IOException {
        try {
            return channel.tryLock();
        } catch (OverlappingFileLockException e) {
            return null;
        }
    }

    /** The finished openings of a file; a line that does not parse (a torn last line) is ignored. */
    static Map<Integer, OpeningLine> read(Path file) throws IOException {
        Map<Integer, OpeningLine> result = new TreeMap<>();
        if (!Files.isRegularFile(file)) {
            return result;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            Map<String, String> m = RunMonitor.parse(line);
            if (m == null || !m.containsKey("opening") || !m.containsKey("c_sims")) {
                continue;
            }
            try {
                OpeningLine l = OpeningLine.of(m);
                result.put(l.opening(), l);
            } catch (NumberFormatException e) {
                // a torn line
            }
        }
        return result;
    }

    private static int play(Path file, LongFunction<Player> candidate, LongFunction<Player> opponent, String candidateText,
            String opponentText, int openings, long seed, int openingPlies, JumpRule jump, int threads,
            Map<Integer, OpeningLine> done, AtomicBoolean stop, PrintStream err, double progressSeconds) throws IOException {
        RuleSet rules = RuleSet.defaults(SEATS).withJumpRule(jump);
        int todo = openings - done.size();
        AtomicInteger finished = new AtomicInteger();
        long t0 = System.nanoTime();
        long[] last = {t0};
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND); ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int opening = 0; opening < openings; opening++) {
                if (done.containsKey(opening)) {
                    continue;
                }
                int index = opening;
                futures.add(pool.submit(() -> {
                    if (stop.get()) {
                        return;
                    }
                    Position start = StrengthEstimate.opening(rules, seed, index, openingPlies);
                    OpeningLine line = playOpening(start, index, candidate, opponent, gameSeed(seed, index));
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
                            err.printf(Locale.ROOT, "%s vs 2 x %s: %d/%d openings  %.3f/s  ETA %s  elapsed %s%n", candidateText,
                                    opponentText, n, todo, rate, Gen0.duration(Math.round((todo - n) / rate)),
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

    /** The seed of the games of an opening: a function of the match seed and the opening index only. */
    static long gameSeed(long seed, int opening) {
        return (seed * 1_000_003L + opening) * 8;
    }

    /**
     * The three games of an opening: game {@code r} has the candidate in seat {@code r} and the opponent in the other two
     * seats. A player is made per game and seat; the candidate's seed is {@code gameSeed + r}, an opponent's seed is
     * {@code gameSeed + 3 + seat}, so no two players of the opening share a seed.
     */
    static OpeningLine playOpening(Position start, int opening, LongFunction<Player> candidateFactory,
            LongFunction<Player> opponentFactory, long gameSeed) {
        if (start.rules().players() != SEATS) {
            throw new IllegalArgumentException("a three-seat match needs three players");
        }
        Result[] results = new Result[SEATS];
        int[] plies = new int[SEATS];
        boolean[] moveLimit = new boolean[SEATS];
        long cpu = 0;
        long moves = 0;
        long sims = 0;
        for (int rotation = 0; rotation < SEATS; rotation++) {
            Player[] players = new Player[SEATS];
            Player mine = candidateFactory.apply(gameSeed + rotation);
            for (int seat = 0; seat < SEATS; seat++) {
                players[seat] = seat == rotation ? mine : opponentFactory.apply(gameSeed + SEATS + seat);
            }
            GameRunner.Result r = GameRunner.play(start, players);
            Outcome outcome = r.outcome();
            results[rotation] = !outcome.hasWinner() ? Result.DRAW : outcome.winner() == rotation ? Result.WIN : Result.LOSS;
            plies[rotation] = r.plies();
            moveLimit[rotation] = outcome.reason() == Outcome.Reason.MOVE_LIMIT;
            cpu += r.cpuNanos()[rotation];
            moves += r.moves()[rotation];
            if (NetPlayers.unwrap(mine) instanceof MctsPlayer m) {
                sims += m.totalSims();
            }
        }
        return new OpeningLine(opening, start.hash(), results, plies, moveLimit, cpu, moves, sims);
    }

    /** The summary of the first {@code openings} openings of the file. */
    static Summary summarize(String candidate, String opponent, JumpRule jump, int openings, long seed, boolean control,
            Map<Integer, OpeningLine> lines) {
        int[] wins = new int[SEATS];
        int[] losses = new int[SEATS];
        int[] draws = new int[SEATS];
        long plies = 0;
        int moveLimit = 0;
        for (int o = 0; o < openings; o++) {
            OpeningLine l = lines.get(o);
            if (l == null) {
                throw new IllegalStateException("opening " + o + " is missing");
            }
            for (int seat = 0; seat < SEATS; seat++) {
                switch (l.results()[seat]) {
                    case WIN -> wins[seat]++;
                    case LOSS -> losses[seat]++;
                    case DRAW -> draws[seat]++;
                }
                plies += l.plies()[seat];
                moveLimit += l.moveLimit()[seat] ? 1 : 0;
            }
        }
        Share[] seats = new Share[SEATS];
        int totalWins = 0;
        int totalDraws = 0;
        for (int seat = 0; seat < SEATS; seat++) {
            seats[seat] = Share.of(wins[seat], openings);
            totalWins += wins[seat];
            totalDraws += draws[seat];
        }
        int games = openings * SEATS;
        return new Summary(candidate, opponent, jump.name().toLowerCase(Locale.ROOT), openings, seed, seats, losses, draws,
                Share.of(totalWins, games), (double) totalDraws / games, (double) moveLimit / games, (double) plies / games,
                control);
    }
}
