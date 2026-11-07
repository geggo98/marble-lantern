package sternhalma.selfplay;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import sternhalma.engine.Outcome;
import sternhalma.engine.Position;
import sternhalma.search.AlphaBetaPlayer;
import sternhalma.search.GameRunner;
import sternhalma.search.PlayerSpec;

/**
 * The stronger-continuation check of layer 2 (round-2 plan, E05): from the first {@code limit} score-margin firings
 * of a data set (in game id order) the game is played on from the firing position by a stronger spec on both sides
 * (for example the same spec at 80000 nodes); the label must agree. A long-running job like the self-play run:
 * shards {@code strong-<shard>.txt} of 25 positions, resumable, abortable, with a lock and {@code --status}.
 *
 * <p>Line format: {@code <game id> <firing ply> <label winner> <final ply> <value of player 0> <value of player 1>}.
 */
final class StrongCheck {

    static final String JOB = "strong-check";
    static final int SHARD = 25;

    private record Item(GameRecord game) {
    }

    /** One result line. */
    record Line(int game, int ply, int winner, int finalPly, double v0, double v1) {
        boolean agrees() {
            return (winner == 0 ? v0 : v1) == 1.0;
        }

        String format() {
            return game + " " + ply + " " + winner + " " + finalPly + " " + v0 + " " + v1;
        }

        static Line parse(String s) {
            String[] t = s.trim().split(" ");
            return new Line(Integer.parseInt(t[0]), Integer.parseInt(t[1]), Integer.parseInt(t[2]),
                    Integer.parseInt(t[3]), Double.parseDouble(t[4]), Double.parseDouble(t[5]));
        }
    }

    private StrongCheck() {
    }

    static int run(Map<String, String> opts, PrintStream err) throws IOException, InterruptedException {
        Path out = Path.of(SelfPlay.require(opts, "out"));
        if (opts.containsKey("status")) {
            return Job.status(out, JOB, System.out);
        }
        String spec = SelfPlay.require(opts, "spec");
        PlayerSpec parsed = PlayerSpec.parse(spec);
        int limit = SelfPlay.intOpt(opts, "limit", 300);
        List<String> from = List.of(SelfPlay.require(opts, "from").split(" "));
        List<GameRecord> firings = new ArrayList<>();
        for (Path f : SelfPlay.shardFiles(from)) {
            for (GameRecord g : GameShard.read(f).games()) {
                if (g.margin() != null) {
                    firings.add(g);
                }
            }
        }
        firings.sort(Comparator.comparingInt(GameRecord::id));
        List<Item> work = firings.stream().limit(limit).map(Item::new).toList();
        String header = "# strong-check spec=" + spec + " from=" + String.join(",", from) + " limit=" + limit;
        Job<Line> job = new Job<>(out, JOB, work.size(), SHARD, SelfPlay.intOpt(opts, "threads", 1), err) {
            @Override
            Path shardFile(int shard) {
                return dir.resolve(String.format(Locale.ROOT, "strong-%04d.txt", shard));
            }

            @Override
            ShardState check(Path file, int shard, int first, int count) {
                try {
                    List<String> lines = Files.readAllLines(file);
                    if (lines.isEmpty() || !lines.get(0).equals(header)) {
                        mismatch("written by '" + (lines.isEmpty() ? "" : lines.get(0)) + "', not '" + header + "'");
                        return ShardState.MISMATCH;
                    }
                    if (lines.size() != count + 1) {
                        return ShardState.CORRUPT;
                    }
                    for (int i = 0; i < count; i++) {
                        if (Line.parse(lines.get(i + 1)).game() != work.get(first + i).game().id()) {
                            return ShardState.CORRUPT;
                        }
                    }
                    return ShardState.COMPLETE;
                } catch (IOException | RuntimeException e) {
                    return ShardState.CORRUPT;
                }
            }

            @Override
            Supplier<Worker<Line>> workers() {
                return () -> i -> {
                    GameRecord g = work.get(i).game();
                    Position pos = Report.replay(g, g.margin().ply());
                    AlphaBetaPlayer a = parsed.create();
                    AlphaBetaPlayer b = parsed.create();
                    GameRunner.Result r = GameRunner.play(pos, a, b);
                    Outcome o = r.outcome();
                    return new Line(g.id(), g.margin().ply(), g.margin().winner(), r.plies(), o.value(0), o.value(1));
                };
            }

            @Override
            void write(Path file, int shard, int first, List<Line> results) throws IOException {
                StringBuilder sb = new StringBuilder(header).append('\n');
                for (Line l : results) {
                    sb.append(l.format()).append('\n');
                }
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp." + ProcessHandle.current().pid());
                Files.writeString(tmp, sb);
                Files.move(tmp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        };
        job.progressSeconds = SelfPlay.doubleOpt(opts, "progress", 10);
        err.println(JOB + ": " + work.size() + " margin firings (of " + firings.size() + "), spec " + spec);
        int code = job.run();
        if (code == 0) {
            summary(out, err);
        }
        return code;
    }

    static void summary(Path out, PrintStream err) throws IOException {
        int n = 0;
        int disagree = 0;
        try (var s = Files.list(out)) {
            for (Path f : s.filter(p -> p.getFileName().toString().matches("strong-\\d+\\.txt")).sorted().toList()) {
                for (String line : Files.readAllLines(f)) {
                    if (line.startsWith("#") || line.isBlank()) {
                        continue;
                    }
                    Line l = Line.parse(line);
                    n++;
                    if (!l.agrees()) {
                        disagree++;
                        err.println("strong-check: label overturned: " + line);
                    }
                }
            }
        }
        err.printf(Locale.ROOT, "strong-check: %d positions, %d labels overturned, CP95 upper %.3f%%%n", n, disagree,
                100 * ClopperPearson.upper95(disagree, n));
    }
}
