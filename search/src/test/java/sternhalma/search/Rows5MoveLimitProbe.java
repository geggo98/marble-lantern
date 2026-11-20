package sternhalma.search;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import sternhalma.engine.Layout;
import sternhalma.engine.Outcome;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.engine.RuleSet.ForeignCamps;
import sternhalma.engine.RuleSet.JumpRule;
import sternhalma.engine.RuleSet.TriangleRows;

/**
 * Measurement tool of the board-rows work (package V1), not a test: does the move limit of the 5-row board (250 plies for 2
 * players, 375 for 3, {@link RuleSet#defaults(int, TriangleRows)}) cut games that would still end? The browser players of the
 * 5-row board ({@link Strategies#BROWSER_ROWS5_SPEC}, {@link Strategies#BROWSER_3P_ROWS5_SPEC} and their long-hop forms) play
 * seeded self-play games on the 5-row rules with a move limit of 1500 plies, after {@code opening} random legal plies. One TSV
 * line per game records the ply at which the game ended, its outcome, and the pieces of each seat in its own goal house at ply
 * 250 (2 players) or 375 (3 players), so that one can tell a game that the default limit would have cut shortly before its end
 * from a game in which the players no longer make progress.
 *
 * <p>Resumable: a game whose line is in the output file is skipped; a line is written in one call when its game ends, and an
 * interrupted run loses only the games in progress. Progress with ETA on stderr.
 *
 * <pre>
 *   java -cp &lt;search test runtime classpath&gt; sternhalma.search.Rows5MoveLimitProbe OUT.tsv NODES[,NODES] GAMES THREADS [PLAYERS] [JUMPS] [CAMPS]
 * </pre>
 */
public final class Rows5MoveLimitProbe {

    private static final int LONG_LIMIT = 1500;
    private static final int OPENING = 4;
    private static final long SEED = 5000;

    private Rows5MoveLimitProbe() {
    }

    private record Job(int players, JumpRule jump, ForeignCamps camps, int nodes, long seed) {
        String key() {
            return players + "\t" + jump.name().toLowerCase(Locale.ROOT) + "\t" + camps.name().toLowerCase(Locale.ROOT) + "\t" + nodes + "\t" + seed;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("usage: Rows5MoveLimitProbe OUT.tsv NODES[,NODES] GAMES THREADS [PLAYERS=2,3] [JUMPS=adjacent,long] [CAMPS=closed]");
            System.exit(2);
        }
        Path out = Path.of(args[0]);
        int games = Integer.parseInt(args[2]);
        int threads = Integer.parseInt(args[3]);
        String playersArg = args.length > 4 ? args[4] : "2,3";
        String jumpsArg = args.length > 5 ? args[5] : "adjacent,long";
        String campsArg = args.length > 6 ? args[6] : "closed";
        Set<String> done = new HashSet<>();
        if (Files.exists(out)) {
            for (String line : Files.readAllLines(out, StandardCharsets.UTF_8)) {
                String[] f = line.split("\t");
                if (f.length > 5 && !line.startsWith("players")) {
                    done.add(String.join("\t", f[0], f[1], f[2], f[3], f[4]));
                }
            }
        } else {
            Files.writeString(out, "players\tjump\tcamps\tnodes\tseed\tply\toutcome\twinner\tdefaultLimit\townInGoalAtDefaultLimit\townInGoalAtEnd\tseconds\n",
                    StandardCharsets.UTF_8);
        }
        List<Job> jobs = new ArrayList<>();
        for (String n : args[1].split(",")) {
            for (String p : playersArg.split(",")) {
                for (String j : jumpsArg.split(",")) {
                    for (String c : campsArg.split(",")) {
                        for (int g = 0; g < games; g++) {
                            Job job = new Job(Integer.parseInt(p), JumpRule.valueOf(j.toUpperCase(Locale.ROOT)),
                                    ForeignCamps.valueOf(c.toUpperCase(Locale.ROOT).replace('-', '_')), Integer.parseInt(n), SEED + g);
                            if (!done.contains(job.key())) {
                                jobs.add(job);
                            }
                        }
                    }
                }
            }
        }
        System.err.printf("Rows5MoveLimitProbe: %d game(s) to play, %d skip (done)%n", jobs.size(), done.size());
        long t0 = System.nanoTime();
        AtomicInteger finished = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        try (Writer w = Files.newBufferedWriter(out, StandardCharsets.UTF_8, StandardOpenOption.APPEND)) {
            for (Job job : jobs) {
                futures.add(pool.submit(() -> {
                    String line = play(job);
                    synchronized (w) {
                        try {
                            w.write(line);
                            w.flush();
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    }
                    int n = finished.incrementAndGet();
                    double elapsed = (System.nanoTime() - t0) / 1e9;
                    System.err.printf("%d/%d  %s -> %s  %.1f games/min  ETA %.0fs  elapsed %.0fs%n", n, jobs.size(),
                            job.key().replace('\t', ' '), line.split("\t")[5], 60 * n / elapsed,
                            (jobs.size() - n) * elapsed / n, elapsed);
                }));
            }
            pool.shutdown();
            for (Future<?> f : futures) {
                f.get();
            }
            pool.awaitTermination(1, TimeUnit.DAYS);
        }
        System.err.printf("Rows5MoveLimitProbe: %d ok, %d skip, 0 error, 0 left%n", jobs.size(), done.size());
    }

    private static String play(Job job) {
        long start = System.nanoTime();
        RuleSet rules = RuleSet.defaults(job.players(), TriangleRows.FIVE).withJumpRule(job.jump()).withForeignCamps(job.camps());
        int defaultLimit = rules.moveLimit();
        rules = rules.withMoveLimit(LONG_LIMIT);
        boolean longHop = job.jump() == JumpRule.LONG;
        Player[] players = new Player[job.players()];
        for (int p = 0; p < players.length; p++) {
            if (job.players() == 2) {
                String spec = longHop ? Strategies.BROWSER_ROWS5_LONG_SPEC : Strategies.BROWSER_ROWS5_SPEC;
                players[p] = PlayerSpec.parse(spec + ",nodes=" + job.nodes()).create();
            } else {
                String spec = longHop ? Strategies.BROWSER_3P_ROWS5_LONG_SPEC : Strategies.BROWSER_3P_ROWS5_SPEC;
                players[p] = ThreePlayerSpec.parse(spec.replace("nodes=20000", "nodes=" + job.nodes())).create();
            }
        }
        Layout layout = Layout.of(job.players(), TriangleRows.FIVE);
        Random random = new Random(job.seed());
        Position pos = Position.initial(rules);
        String atDefault = "";
        while (pos.outcome() == null && pos.legalMoves().length > 0) {
            if (pos.ply() == defaultLimit) {
                atDefault = ownInGoal(pos, layout);
            }
            int[] legal = pos.legalMoves();
            int move = pos.ply() < OPENING ? legal[random.nextInt(legal.length)] : players[pos.sideToMove()].choose(pos);
            pos.make(move);
        }
        Outcome o = pos.outcome();
        String outcome = o == null ? "nomove" : o.hasWinner() ? "win" : pos.ply() >= LONG_LIMIT ? "limit" : "draw";
        return String.format(Locale.ROOT, "%s\t%d\t%s\t%d\t%d\t%s\t%s\t%.1f%n", job.key(), pos.ply(), outcome,
                o != null && o.hasWinner() ? o.winner() : -1, defaultLimit, atDefault.isEmpty() ? "-" : atDefault,
                ownInGoal(pos, layout), (System.nanoTime() - start) / 1e9);
    }

    /** The own pieces of each seat in its goal house, as "a/b/c". */
    private static String ownInGoal(Position pos, Layout layout) {
        StringBuilder s = new StringBuilder();
        for (int p = 0; p < pos.rules().players(); p++) {
            int n = 0;
            for (int c : pos.pieces(p)) {
                n += layout.inGoal(p, c) ? 1 : 0;
            }
            s.append(p == 0 ? "" : "/").append(n);
        }
        return s.toString();
    }
}
