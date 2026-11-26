package sternhalma.selfplay;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongFunction;

import sternhalma.engine.Outcome;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.engine.RuleSet.JumpRule;
import sternhalma.engine.RuleSet.Ranking;
import sternhalma.nn.Parameters;
import sternhalma.search.GameRunner;
import sternhalma.search.MctsPlayer;
import sternhalma.search.Player;

/**
 * Mixed three-player tables for the Elo study ({@code ThreeSeatMatch --table A,B,C}, plan section 2.6): three players, usually
 * three different ones, at one table. Every opening is played in all 6 seatings (each player in each seat twice), and the line
 * of an opening records which player won each game, so that a fit can use the winner's identity, not only the seat.
 *
 * <p>The three player texts are separated by a comma. A comma inside a player text ({@code mcts:sims=200,batch=1}) does not
 * split: a comma separates two players only where the next text starts with {@code random}, {@code raw}, {@code ab3} or
 * {@code mcts}. Each text is {@code random}, {@code ab3:...} or {@code mcts:...} as in {@link ThreeSeatMatch}; an {@code mcts}
 * text may carry its own {@code net=}, and {@code --net} is the network of the texts without one. Identical texts are allowed
 * (the three players are then the same player, which gives every player one third by construction when the player is
 * deterministic, and is a check of the setup, not evidence).
 *
 * <p>The rule is {@code FIRST_WINNER_ONLY} (set explicitly, whatever the default of {@link RuleSet} is). The seating {@code k}
 * of {@link #SEATINGS} puts player {@code SEATINGS[k][seat]} in seat {@code seat}. Seeds depend on (seed, opening, seating,
 * player) only, so a result does not depend on the thread count. Job conventions, lock file, resume and signals are those of
 * {@link ThreeSeatMatch}; the file is {@code DIR/tables-<hash>.jsonl}, the result {@code DIR/table-result-<hash>.json}.
 * A game without a winner is a draw: it counts for no player. The intervals treat the games as independent; the six games of
 * an opening are not, so they are a little too narrow.
 */
final class TableMatch {

    static final int PLAYERS = ThreeSeatMatch.SEATS;
    /** The six seatings: {@code SEATINGS[k][seat]} is the index of the player in that seat. */
    static final int[][] SEATINGS = {{0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}};

    /**
     * The six games of one opening.
     *
     * @param winnerSeat per seating: the seat of the winner, or -1 for a draw
     * @param cpuNanos per player (index in the table, not the seat): CPU time over its 6 games
     */
    record TableLine(int opening, long openingHash, int[] winnerSeat, int[] plies, boolean[] moveLimit, long[] cpuNanos,
            long[] moves, long[] sims) {

        /** The index of the player that won seating {@code k}, or -1 for a draw. */
        int winnerPlayer(int k) {
            return winnerSeat[k] < 0 ? -1 : SEATINGS[k][winnerSeat[k]];
        }

        Map<String, Object> fields() {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("opening", opening);
            f.put("open_hash", Long.toHexString(openingHash));
            for (int k = 0; k < SEATINGS.length; k++) {
                f.put("perm" + k, "" + SEATINGS[k][0] + SEATINGS[k][1] + SEATINGS[k][2]);
                f.put("w" + k, winnerSeat[k]);
                f.put("wp" + k, winnerPlayer(k));
                f.put("plies" + k, plies[k]);
                f.put("ml" + k, moveLimit[k]);
            }
            for (int p = 0; p < PLAYERS; p++) {
                f.put("cpu_ns_p" + p, cpuNanos[p]);
                f.put("moves_p" + p, moves[p]);
                f.put("sims_p" + p, sims[p]);
            }
            return f;
        }

        static TableLine of(Map<String, String> m) {
            int[] winner = new int[SEATINGS.length];
            int[] plies = new int[SEATINGS.length];
            boolean[] ml = new boolean[SEATINGS.length];
            for (int k = 0; k < SEATINGS.length; k++) {
                String perm = m.get("perm" + k);
                if (!perm.equals("" + SEATINGS[k][0] + SEATINGS[k][1] + SEATINGS[k][2])) {
                    throw new NumberFormatException("seating " + k + " is " + perm);
                }
                winner[k] = Integer.parseInt(m.get("w" + k));
                if (winner[k] < -1 || winner[k] >= PLAYERS) {
                    throw new NumberFormatException("winner seat " + winner[k]);
                }
                plies[k] = Integer.parseInt(m.get("plies" + k));
                ml[k] = Boolean.parseBoolean(m.get("ml" + k));
            }
            long[] cpu = new long[PLAYERS];
            long[] moves = new long[PLAYERS];
            long[] sims = new long[PLAYERS];
            for (int p = 0; p < PLAYERS; p++) {
                cpu[p] = Long.parseLong(m.get("cpu_ns_p" + p));
                moves[p] = Long.parseLong(m.get("moves_p" + p));
                sims[p] = Long.parseLong(m.get("sims_p" + p));
            }
            return new TableLine(Integer.parseInt(m.get("opening")), Long.parseUnsignedLong(m.get("open_hash"), 16), winner,
                    plies, ml, cpu, moves, sims);
        }
    }

    /** The summary of a table: wins per player (and per player and seat), wins per seat, draws. */
    record Summary(List<String> players, String jump, int openings, long seed, ThreeSeatMatch.Share[] playerShare,
            ThreeSeatMatch.Share[][] playerSeatShare, int[] seatWins, int draws, double moveLimitShare, double meanPlies) {

        int games() {
            return openings * SEATINGS.length;
        }

        String text() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.ROOT, "table %s, jump=%s, %d openings x 6 seatings = %d games, chance 1/3%n",
                    String.join(" | ", players), jump, openings, games()));
            for (int p = 0; p < PLAYERS; p++) {
                sb.append(String.format(Locale.ROOT, "  player %d %s: %s%n", p, players.get(p), playerShare[p]));
                for (int s = 0; s < PLAYERS; s++) {
                    sb.append(String.format(Locale.ROOT, "    in seat %d: %s%n", s, playerSeatShare[p][s]));
                }
            }
            sb.append(String.format(Locale.ROOT, "  seat wins: %d / %d / %d; draws (no winner) %d of %d games, of which at the move"
                    + " limit %.1f%% of all; mean game length %.1f plies%n", seatWins[0], seatWins[1], seatWins[2], draws,
                    games(), 100 * moveLimitShare, meanPlies));
            return sb.toString();
        }

        Map<String, Object> fields() {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("kind", "table");
            for (int p = 0; p < PLAYERS; p++) {
                f.put("player" + p, players.get(p));
            }
            f.put("jump", jump);
            f.put("ranking", Ranking.FIRST_WINNER_ONLY.name());
            f.put("openings", openings);
            f.put("games", games());
            f.put("seed", seed);
            for (int p = 0; p < PLAYERS; p++) {
                f.put("p" + p + "_wins", playerShare[p].wins());
                f.put("p" + p + "_share", playerShare[p].share());
                f.put("p" + p + "_ci95_lower", playerShare[p].lower());
                f.put("p" + p + "_ci95_upper", playerShare[p].upper());
                for (int s = 0; s < PLAYERS; s++) {
                    f.put("p" + p + "_seat" + s + "_wins", playerSeatShare[p][s].wins());
                }
            }
            for (int s = 0; s < PLAYERS; s++) {
                f.put("seat" + s + "_wins", seatWins[s]);
            }
            f.put("draws", draws);
            f.put("chance", 1.0 / PLAYERS);
            f.put("move_limit_share", moveLimitShare);
            f.put("mean_plies", meanPlies);
            return f;
        }
    }

    private TableMatch() {
    }

    /** Splits {@code A,B,C} into three player texts (see the class comment for the commas inside a text). */
    static List<String> split(String table) {
        List<String> parts = new ArrayList<>(List.of(table.split(",(?=(random|raw|ab3|mcts)([:,]|$))", -1)));
        if (parts.size() != PLAYERS) {
            throw new IllegalArgumentException("--table needs exactly 3 players A,B,C, got " + parts.size() + " in " + table);
        }
        return parts;
    }

    static int match(Map<String, String> opts, PrintStream out, PrintStream err) throws IOException {
        Path dir = Path.of(opts.get("out"));
        int openings = Integer.parseInt(opts.getOrDefault("openings", "100"));
        long seed = Long.parseLong(opts.getOrDefault("seed", "4001"));
        int threads = Integer.parseInt(opts.getOrDefault("threads", "4"));
        int openingPlies = Integer.parseInt(opts.getOrDefault("opening-plies",
                String.valueOf(ThreeSeatMatch.DEFAULT_OPENING_PLIES)));
        double progressSeconds = Double.parseDouble(opts.getOrDefault("progress-seconds", "10"));
        JumpRule jump = SelfPlay.jump(opts.getOrDefault("jump", "adjacent"));
        if (openings < 1 || threads < 1 || openingPlies < 0) {
            throw new IllegalArgumentException("need openings >= 1, threads >= 1, opening-plies >= 0");
        }
        List<String> players = split(opts.get("table"));
        Parameters own = opts.containsKey("net") ? Parameters.load(Path.of(opts.get("net"))) : null;
        List<LongFunction<Player>> factories = new ArrayList<>();
        for (String p : players) {
            factories.add(ThreeSeatMatch.factory(p, own, jump));
        }
        RuleSet rules = RuleSet.defaults(PLAYERS).withJumpRule(jump).withRanking(Ranking.FIRST_WINNER_ONLY);
        String hash = Gen0.sha256Hex(("table|" + String.join("|", players) + "|" + seed + "|" + openingPlies + "|" + jump + "|"
                + (own == null ? "" : "own")).getBytes(StandardCharsets.UTF_8)).substring(0, 12);
        Files.createDirectories(dir);
        try (FileChannel lockChannel = FileChannel.open(dir.resolve(".threeseat.lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE); FileLock lock = ThreeSeatMatch.tryLock(lockChannel)) {
            if (lock == null) {
                err.println("threeseatmatch: running already (" + dir.resolve(".threeseat.lock") + " is held)");
                return ThreeSeatMatch.EXIT_RUNNING;
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
            try {
                Path file = dir.resolve("tables-" + hash + ".jsonl");
                Map<Integer, TableLine> done = read(file);
                int todo = 0;
                for (int o = 0; o < openings; o++) {
                    todo += done.containsKey(o) ? 0 : 1;
                }
                int skip = openings - todo;
                int ok = 0;
                if (todo > 0) {
                    err.printf(Locale.ROOT, "threeseatmatch: table %s: %d openings to play, %d finished before (%s)%n",
                            String.join(" | ", players), todo, skip, file);
                    ok = ThreeSeatMatch.play(file, "table " + String.join(" | ", players), openings, threads, done.keySet(),
                            index -> playOpening(StrengthEstimate.opening(rules, seed, index, openingPlies), index, factories,
                                    tableSeed(seed, index)).fields(), stop, err, progressSeconds);
                    done = read(file);
                }
                int left = stop.get() ? openings - done.size() : 0;
                if (!stop.get()) {
                    Summary s = summarize(players, jump, openings, seed, done);
                    out.print(s.text());
                    Path result = dir.resolve("table-result-" + hash + ".json");
                    Path tmp = result.resolveSibling(result.getFileName() + ".tmp." + ProcessHandle.current().pid());
                    Files.writeString(tmp, RunMonitor.json(s.fields()) + "\n", StandardCharsets.UTF_8);
                    Files.move(tmp, result, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                }
                err.printf(Locale.ROOT, "threeseatmatch: %d ok, %d skip, 0 error, %d left%s%n", ok, skip, left,
                        stop.get() ? " - aborted" : " - nothing more to do");
                return stop.get() ? ThreeSeatMatch.EXIT_ABORTED : ThreeSeatMatch.EXIT_OK;
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

    /** The finished openings of a file; a line that does not parse (a torn last line) is ignored. */
    static Map<Integer, TableLine> read(Path file) throws IOException {
        Map<Integer, TableLine> result = new TreeMap<>();
        if (!Files.isRegularFile(file)) {
            return result;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            Map<String, String> m = RunMonitor.parse(line);
            if (m == null || !m.containsKey("opening") || !m.containsKey("sims_p" + (PLAYERS - 1))) {
                continue;
            }
            try {
                TableLine l = TableLine.of(m);
                result.put(l.opening(), l);
            } catch (NumberFormatException e) {
                // a torn line, or a line of another format
            }
        }
        return result;
    }

    /** The seed base of an opening: 32 slots, one per (seating, player) pair, used by {@link #playOpening}. */
    static long tableSeed(long seed, int opening) {
        return (seed * 1_000_003L + opening) * 32;
    }

    /**
     * The six games of an opening. In seating {@code k} the player of table index {@code SEATINGS[k][seat]} sits in
     * {@code seat}; it is made by its factory with the seed {@code base + 3k + player}.
     */
    static TableLine playOpening(Position start, int opening, List<LongFunction<Player>> factories, long base) {
        if (start.rules().players() != PLAYERS || factories.size() != PLAYERS) {
            throw new IllegalArgumentException("a table has three seats and three players");
        }
        int games = SEATINGS.length;
        int[] winnerSeat = new int[games];
        int[] plies = new int[games];
        boolean[] moveLimit = new boolean[games];
        long[] cpu = new long[PLAYERS];
        long[] moves = new long[PLAYERS];
        long[] sims = new long[PLAYERS];
        for (int k = 0; k < games; k++) {
            Player[] players = new Player[PLAYERS];
            for (int seat = 0; seat < PLAYERS; seat++) {
                int player = SEATINGS[k][seat];
                players[seat] = factories.get(player).apply(base + 3L * k + player);
            }
            GameRunner.Result r = GameRunner.play(start, players);
            Outcome outcome = r.outcome();
            winnerSeat[k] = outcome.hasWinner() ? outcome.winner() : -1;
            plies[k] = r.plies();
            moveLimit[k] = outcome.reason() == Outcome.Reason.MOVE_LIMIT;
            for (int seat = 0; seat < PLAYERS; seat++) {
                int player = SEATINGS[k][seat];
                cpu[player] += r.cpuNanos()[seat];
                moves[player] += r.moves()[seat];
                if (NetPlayers.unwrap(players[seat]) instanceof MctsPlayer m) {
                    sims[player] += m.totalSims();
                }
            }
        }
        return new TableLine(opening, start.hash(), winnerSeat, plies, moveLimit, cpu, moves, sims);
    }

    /** The summary of the first {@code openings} openings of the file. */
    static Summary summarize(List<String> players, JumpRule jump, int openings, long seed, Map<Integer, TableLine> lines) {
        int[] wins = new int[PLAYERS];
        int[][] winsInSeat = new int[PLAYERS][PLAYERS];
        int[] seatWins = new int[PLAYERS];
        int draws = 0;
        int moveLimit = 0;
        long plies = 0;
        for (int o = 0; o < openings; o++) {
            TableLine l = lines.get(o);
            if (l == null) {
                throw new IllegalStateException("opening " + o + " is missing");
            }
            for (int k = 0; k < SEATINGS.length; k++) {
                int w = l.winnerPlayer(k);
                if (w < 0) {
                    draws++;
                } else {
                    wins[w]++;
                    winsInSeat[w][l.winnerSeat()[k]]++;
                    seatWins[l.winnerSeat()[k]]++;
                }
                plies += l.plies()[k];
                moveLimit += l.moveLimit()[k] ? 1 : 0;
            }
        }
        int games = openings * SEATINGS.length;
        ThreeSeatMatch.Share[] share = new ThreeSeatMatch.Share[PLAYERS];
        ThreeSeatMatch.Share[][] seatShare = new ThreeSeatMatch.Share[PLAYERS][PLAYERS];
        for (int p = 0; p < PLAYERS; p++) {
            share[p] = ThreeSeatMatch.Share.of(wins[p], games);
            for (int s = 0; s < PLAYERS; s++) {
                seatShare[p][s] = ThreeSeatMatch.Share.of(winsInSeat[p][s], openings * 2);
            }
        }
        return new Summary(List.copyOf(players), jump.name().toLowerCase(Locale.ROOT), openings, seed, share, seatShare, seatWins,
                draws, (double) moveLimit / games, (double) plies / games);
    }
}
