package sternhalma.search;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import sternhalma.engine.Outcome;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.tablebase.RaceTable;
import sternhalma.tablebase.Rows;

/**
 * Measurement tool of experiment E09 (docs/baseline-round2-plan.md): plays self-play games of a spec from the arena's
 * openings and measures, at every passed ply, how the default rows&gt;=2 table and the extended rows&gt;=1 table cover
 * the position (the mover's configuration, both configurations) and what race play would do with each table
 * (table move, plan move, or back to the search). When the spec itself has race play, it also reports what the
 * players did (table, plan, search). The games are the same for both tables, so the comparison is paired.
 *
 * <pre>
 *   java -cp engine/build/classes/java/main:tablebase/build/classes/java/main:search/build/classes/java/main:\
 *       search/build/classes/java/test sternhalma.search.RaceCoverage '&lt;spec&gt;' &lt;games&gt; &lt;seed&gt; [threads]
 *       [--out &lt;file&gt;] [--first F]
 * </pre>
 *
 * <p>With {@code --out} every finished game appends one line to the file and a game whose line exists is skipped, so
 * a run of 1000 games can be split into chunks ({@code --first F} plays games F .. games-1) and resumed; the summary
 * is computed over all lines of the file below {@code games}.
 */
public final class RaceCoverage {

    /** Per-table counters over passed plies. */
    static final class TableStats {
        long moverCovered;
        long bothCovered;
        long tableMove;
        long planMove;
        long search;
        long firstBothCoveredPlySum;
        int gamesBothCovered;

        void add(TableStats o) {
            moverCovered += o.moverCovered;
            bothCovered += o.bothCovered;
            tableMove += o.tableMove;
            planMove += o.planMove;
            search += o.search;
            firstBothCoveredPlySum += o.firstBothCoveredPlySum;
            gamesBothCovered += o.gamesBothCovered;
        }

        void write(StringBuilder sb) {
            sb.append(' ').append(moverCovered).append(' ').append(bothCovered).append(' ').append(tableMove).append(' ')
                    .append(planMove).append(' ').append(search).append(' ').append(firstBothCoveredPlySum).append(' ')
                    .append(gamesBothCovered);
        }

        int read(String[] f, int at) {
            moverCovered = Long.parseLong(f[at++]);
            bothCovered = Long.parseLong(f[at++]);
            tableMove = Long.parseLong(f[at++]);
            planMove = Long.parseLong(f[at++]);
            search = Long.parseLong(f[at++]);
            firstBothCoveredPlySum = Long.parseLong(f[at++]);
            gamesBothCovered = Integer.parseInt(f[at++]);
            return at;
        }
    }

    /** Counters of one game or of a whole run. */
    static final class Stats {
        int games;
        int decided;
        long plies;
        long passedPlies;
        long firstPassedPlySum;
        int gamesPassed;
        final TableStats rows2 = new TableStats();
        final TableStats rows1 = new TableStats();
        long playerTableMoves;
        long playerPlanMoves;
        long playerRaceMoves;
        long playerMoves;

        void add(Stats o) {
            games += o.games;
            decided += o.decided;
            plies += o.plies;
            passedPlies += o.passedPlies;
            firstPassedPlySum += o.firstPassedPlySum;
            gamesPassed += o.gamesPassed;
            rows2.add(o.rows2);
            rows1.add(o.rows1);
            playerTableMoves += o.playerTableMoves;
            playerPlanMoves += o.playerPlanMoves;
            playerRaceMoves += o.playerRaceMoves;
            playerMoves += o.playerMoves;
        }

        /** One line per game: {@code game <index> <counters...>}. */
        String toLine(int index) {
            StringBuilder sb = new StringBuilder("game ").append(index).append(' ').append(games).append(' ')
                    .append(decided).append(' ').append(plies).append(' ').append(passedPlies).append(' ')
                    .append(firstPassedPlySum).append(' ').append(gamesPassed);
            rows2.write(sb);
            rows1.write(sb);
            sb.append(' ').append(playerTableMoves).append(' ').append(playerPlanMoves).append(' ').append(playerRaceMoves)
                    .append(' ').append(playerMoves);
            return sb.toString();
        }

        static int indexOf(String line) {
            String[] f = line.trim().split(" ");
            return f.length > 1 && f[0].equals("game") ? Integer.parseInt(f[1]) : -1;
        }

        static Stats fromLine(String line) {
            String[] f = line.trim().split(" ");
            Stats s = new Stats();
            int at = 2;
            s.games = Integer.parseInt(f[at++]);
            s.decided = Integer.parseInt(f[at++]);
            s.plies = Long.parseLong(f[at++]);
            s.passedPlies = Long.parseLong(f[at++]);
            s.firstPassedPlySum = Long.parseLong(f[at++]);
            s.gamesPassed = Integer.parseInt(f[at++]);
            at = s.rows2.read(f, at);
            at = s.rows1.read(f, at);
            s.playerTableMoves = Long.parseLong(f[at++]);
            s.playerPlanMoves = Long.parseLong(f[at++]);
            s.playerRaceMoves = Long.parseLong(f[at++]);
            s.playerMoves = Long.parseLong(f[at]);
            return s;
        }
    }

    private RaceCoverage() {
    }

    /** Classifies what race play with {@code probe} would do in {@code pos} and records the coverage. */
    private static void probe(Position pos, RaceTable table, RaceMove probe, TableStats stats, boolean[] firstBoth,
            int ply) {
        int me = pos.sideToMove();
        int[] mine0 = Rows.toPlayer0(me, pos.pieces(me));
        int[] theirs0 = Rows.toPlayer0(1 - me, pos.pieces(1 - me));
        boolean mover = table.covers(mine0);
        boolean both = mover && table.covers(theirs0);
        if (mover) {
            stats.moverCovered++;
        }
        if (both) {
            stats.bothCovered++;
            if (!firstBoth[0]) {
                firstBoth[0] = true;
                stats.firstBothCoveredPlySum += ply;
                stats.gamesBothCovered++;
            }
        }
        long t = probe.tableMoves();
        long p = probe.planMoves();
        int m = probe.choose(pos);
        if (m < 0) {
            stats.search++;
        } else if (probe.tableMoves() > t) {
            stats.tableMove++;
        } else if (probe.planMoves() > p) {
            stats.planMove++;
        } else {
            throw new IllegalStateException("a race move that is neither table nor plan");
        }
    }

    static Stats play(PlayerSpec spec, RuleSet rules, int[] opening, RaceTable rows2, RaceTable rows1) {
        Position pos = Position.initial(rules);
        for (int m : opening) {
            pos.make(m);
        }
        AlphaBetaPlayer[] players = {spec.create(), spec.create()};
        RaceMove probe2 = new RaceMove(rows2, 1);
        RaceMove probe1 = new RaceMove(rows1, 1);
        Stats s = new Stats();
        s.games = 1;
        boolean[] firstBoth2 = {false};
        boolean[] firstBoth1 = {false};
        boolean passedSeen = false;
        Outcome outcome;
        while (true) {
            outcome = pos.outcome();
            if (outcome != null) {
                break;
            }
            if (pos.legalMoves().length == 0) {
                outcome = pos.noMovesOutcome();
                break;
            }
            if (RaceAdjudication.passed(pos)) {
                s.passedPlies++;
                if (!passedSeen) {
                    passedSeen = true;
                    s.firstPassedPlySum += pos.ply();
                    s.gamesPassed++;
                }
                probe(pos, rows2, probe2, s.rows2, firstBoth2, pos.ply());
                probe(pos, rows1, probe1, s.rows1, firstBoth1, pos.ply());
            }
            int move = players[pos.sideToMove()].choose(pos);
            pos.make(move);
        }
        s.plies = pos.ply();
        if (outcome.hasWinner()) {
            s.decided = 1;
        }
        for (AlphaBetaPlayer p : players) {
            s.playerMoves += p.moves();
            s.playerRaceMoves += p.raceMoves();
            if (p.race() != null) {
                s.playerTableMoves += p.race().tableMoves();
                s.playerPlanMoves += p.race().planMoves();
            }
        }
        return s;
    }

    /** The per-game lines of an output file, by game index. */
    static TreeMap<Integer, Stats> readFile(Path out) throws IOException {
        TreeMap<Integer, Stats> done = new TreeMap<>();
        if (out != null && Files.isRegularFile(out)) {
            for (String line : Files.readAllLines(out, StandardCharsets.UTF_8)) {
                int index = Stats.indexOf(line);
                if (index >= 0) {
                    done.put(index, Stats.fromLine(line));
                }
            }
        }
        return done;
    }

    /**
     * Plays games {@code first .. games - 1} on {@code threads} threads, skipping the games whose line is in
     * {@code out}, and returns the totals over all games below {@code games} that are now done.
     */
    static Stats run(String spec, int games, int first, long seed, int threads, Path out) throws IOException {
        PlayerSpec p = PlayerSpec.parse(spec);
        RuleSet rules = RuleSet.defaults(2);
        RaceTable rows2 = RaceTable.rows2();
        RaceTable rows1 = RaceTable.rows1();
        TreeMap<Integer, Stats> done = readFile(out);
        int skipped = 0;
        long t0 = System.nanoTime();
        try (PrintWriter writer = out == null ? null : new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND), true);
                ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads))) {
            List<Future<Stats>> futures = new ArrayList<>();
            List<Integer> indices = new ArrayList<>();
            for (int g = first; g < games; g++) {
                if (done.containsKey(g)) {
                    skipped++;
                    continue;
                }
                int index = g;
                indices.add(g);
                futures.add(pool.submit(() -> play(p, rules, RaceReplay.opening(rules, seed, index), rows2, rows1)));
            }
            int played = 0;
            for (int i = 0; i < futures.size(); i++) {
                Stats s;
                try {
                    s = futures.get(i).get();
                } catch (Exception e) {
                    throw new IllegalStateException("game failed", e);
                }
                done.put(indices.get(i), s);
                played++;
                if (writer != null) {
                    writer.println(s.toLine(indices.get(i)));
                }
                if (played % 50 == 0 || played == futures.size()) {
                    double elapsed = (System.nanoTime() - t0) / 1e9;
                    System.err.printf(Locale.ROOT, "%d/%d games, %.2f games/s, ETA %.0f s, elapsed %.0f s%n", played,
                            futures.size(), played / elapsed, (futures.size() - played) * elapsed / played, elapsed);
                }
            }
            System.err.printf(Locale.ROOT, "%d played, %d skipped (already in %s)%n", played, skipped, out);
        }
        Stats total = new Stats();
        for (var e : done.entrySet()) {
            if (e.getKey() < games) {
                total.add(e.getValue());
            }
        }
        return total;
    }

    static String tableLine(String name, TableStats t, long passed) {
        return String.format(Locale.ROOT,
                "%s: mover covered %d (%.1f%% of passed plies), both covered %d (%.1f%%), first both-covered ply mean %.1f in %d games; "
                        + "race play would use: table %d (%.1f%%), plan %d (%.1f%%), search %d (%.1f%%)%n",
                name, t.moverCovered, 100.0 * t.moverCovered / passed, t.bothCovered, 100.0 * t.bothCovered / passed,
                t.gamesBothCovered == 0 ? Double.NaN : t.firstBothCoveredPlySum / (double) t.gamesBothCovered,
                t.gamesBothCovered, t.tableMove, 100.0 * t.tableMove / passed, t.planMove, 100.0 * t.planMove / passed,
                t.search, 100.0 * t.search / passed);
    }

    static String summary(String spec, long seed, Stats s) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "spec=%s seed=%d games=%d decided=%d mean plies=%.1f%n", spec, seed, s.games,
                s.decided, s.plies / (double) s.games));
        sb.append(String.format(Locale.ROOT, "passed plies: %d of %d (%.1f%%); games that passed %d, first passed ply mean %.1f%n",
                s.passedPlies, s.plies, 100.0 * s.passedPlies / s.plies, s.gamesPassed,
                s.gamesPassed == 0 ? Double.NaN : s.firstPassedPlySum / (double) s.gamesPassed));
        sb.append(tableLine("rows>=2", s.rows2, s.passedPlies));
        sb.append(tableLine("rows>=1", s.rows1, s.passedPlies));
        sb.append(String.format(Locale.ROOT, "table moves as a share of all plies: rows>=2 %.1f%%, rows>=1 %.1f%% (%+.1f points)%n",
                100.0 * s.rows2.tableMove / s.plies, 100.0 * s.rows1.tableMove / s.plies,
                100.0 * (s.rows1.tableMove - s.rows2.tableMove) / s.plies));
        if (s.playerRaceMoves > 0) {
            sb.append(String.format(Locale.ROOT,
                    "the players themselves: %d moves, race moves %d (%.1f%%): table %d (%.1f%%), plan %d (%.1f%%); searched %d%n",
                    s.playerMoves, s.playerRaceMoves, 100.0 * s.playerRaceMoves / s.playerMoves, s.playerTableMoves,
                    100.0 * s.playerTableMoves / s.playerMoves, s.playerPlanMoves, 100.0 * s.playerPlanMoves / s.playerMoves,
                    s.playerMoves - s.playerRaceMoves));
        }
        return sb.toString();
    }

    public static void main(String[] args) {
        String spec = args[0];
        int games = Integer.parseInt(args[1]);
        long seed = Long.parseLong(args[2]);
        int threads = Runtime.getRuntime().availableProcessors();
        Path out = null;
        int first = 0;
        List<String> rest = new ArrayList<>(Arrays.asList(args).subList(3, args.length));
        for (int i = 0; i < rest.size(); i++) {
            switch (rest.get(i)) {
                case "--out" -> out = Path.of(rest.get(++i));
                case "--first" -> first = Integer.parseInt(rest.get(++i));
                default -> threads = Integer.parseInt(rest.get(i));
            }
        }
        long t0 = System.nanoTime();
        try {
            Stats s = run(spec, games, first, seed, threads, out);
            System.out.print(summary(spec, seed, s));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        System.out.printf(Locale.ROOT, "elapsed %.1f s on %d threads%n", (System.nanoTime() - t0) / 1e9, threads);
    }
}
