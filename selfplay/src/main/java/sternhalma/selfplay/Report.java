package sternhalma.selfplay;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.search.RaceAdjudication;
import sternhalma.search.Scores;
import sternhalma.selfplay.Adjudication.Firing;
import sternhalma.selfplay.Adjudication.Layer;
import sternhalma.tablebase.RaceTable;

/**
 * Statistics of a self-play data set: per adjudication layer the firings among played-out games, the wrong labels
 * with their one-sided 95% Clopper-Pearson bound and the plies saved; the combined saving; opening diversity and the
 * colour advantage; cost; and optionally an offline sweep of the score margin over the stored search scores.
 *
 * <p>A layer-2 or layer-3 label is <b>wrong</b> when the game, played to its end by the same players, is not won by
 * the labelled side (another winner, or the move limit). An S1 verdict is a game value: a game that ends otherwise
 * is a conversion failure of the players, counted separately, and checked with {@link VerdictCheck} (the plan against
 * the recorded loser moves and against every loser move); only a failed check is an S1 error.
 */
final class Report {

    private Report() {
    }

    /** Counts of one layer. */
    static final class Tally {
        long firings;
        long wrongWinner;
        long wrongLimit;
        long saved;
        long s1Disagreements;
        long playedPlies;
        final List<String> wrong = new ArrayList<>();

        long wrong() {
            return wrongWinner + wrongLimit;
        }

        void add(GameRecord g, Firing f) {
            firings++;
            saved += g.finalPly() - f.ply();
            playedPlies += g.finalPly();
            double v = g.finalValues()[f.winner()];
            if (v != 1.0 && f.layer() == Layer.S1) {
                s1Disagreements++; // a game value: a conversion failure of the players, checked in checkS1
            } else if (v != 1.0) {
                boolean otherWon = g.finalValues()[1 - f.winner()] == 1.0;
                if (otherWon) {
                    wrongWinner++;
                } else {
                    wrongLimit++;
                }
                wrong.add("game " + g.id() + " " + f.layer() + " at ply " + f.ply() + " for player " + f.winner()
                        + ", game ended at ply " + g.finalPly() + " with values " + g.finalValues()[0] + "/"
                        + g.finalValues()[1]);
            }
        }

        String line(String name, long totalPlies, long games) {
            return String.format(Locale.ROOT,
                    "%-8s firings %5d (%.1f%% of games)  wrong %d (other winner %d, limit %d)  CP95 upper %.3f%%  "
                            + "%ssaved %.2f plies/game = %.2f%% of all plies",
                    name, firings, 100.0 * firings / games, wrong(), wrongWinner, wrongLimit,
                    100 * ClopperPearson.upper95(wrong(), firings),
                    s1Disagreements > 0 ? "S1 play-out disagreements " + s1Disagreements + "  " : "",
                    (double) saved / games, 100.0 * saved / totalPlies);
        }
    }

    static int run(List<String> dirs, Map<String, String> opts, PrintStream out, PrintStream err) throws IOException {
        List<Path> files = SelfPlay.shardFiles(dirs);
        if (files.isEmpty()) {
            err.println("report: no shard files in " + dirs);
            return 1;
        }
        List<GameRecord> games = new ArrayList<>();
        Set<String> settings = new HashSet<>();
        long samples = 0;
        long bytes = 0;
        long sampleMoves = 0;
        for (Path f : files) {
            GameShard.Read r = GameShard.read(f);
            games.addAll(r.games());
            settings.add(r.metadata().get("selfplay"));
            samples += r.contents().tensor("ply").elements();
            sampleMoves += r.contents().tensor("move_from").elements();
            bytes += Files.size(f);
        }
        out.println("report over " + files.size() + " shards, " + games.size() + " games");
        for (String s : settings) {
            out.println("  settings: " + s);
        }
        print(games, out);
        out.printf(Locale.ROOT, "samples: %d (%.1f per game), %.1f legal moves per sample, %.0f bytes per sample "
                + "(file bytes / samples)%n", samples, (double) samples / games.size(),
                (double) sampleMoves / Math.max(1, samples), (double) bytes / Math.max(1, samples));
        if (opts.containsKey("sweep")) {
            sweep(games, opts.get("sweep"), out);
        }
        if (!"0".equals(opts.getOrDefault("check-s1", "1"))) {
            checkS1(games, out);
        }
        return 0;
    }

    static void print(List<GameRecord> games, PrintStream out) {
        List<GameRecord> played = games.stream().filter(GameRecord::playedOut).toList();
        long totalPlies = played.stream().mapToLong(GameRecord::finalPly).sum();
        out.printf(Locale.ROOT, "played out: %d of %d games, mean %.1f plies; labels: none %d, S1 %d, margin %d, "
                + "eval %d%n", played.size(), games.size(), (double) totalPlies / Math.max(1, played.size()),
                count(games, Layer.NONE), count(games, Layer.S1), count(games, Layer.MARGIN), count(games, Layer.EVAL));
        Tally s1 = new Tally();
        Tally margin = new Tally();
        Tally eval = new Tally();
        Tally combined = new Tally();
        long passedPlies = 0;
        long raceIgnored = 0;
        for (GameRecord g : games) {
            raceIgnored += g.raceScoresIgnored();
        }
        for (GameRecord g : played) {
            if (g.s1() != null) {
                s1.add(g, g.s1());
            }
            if (g.margin() != null) {
                margin.add(g, g.margin());
            }
            if (g.eval() != null) {
                eval.add(g, g.eval());
            }
            if (g.label() != Layer.NONE) {
                Firing cut = g.label() == Layer.S1 ? g.s1() : g.label() == Layer.MARGIN ? g.margin() : g.eval();
                combined.add(g, cut);
            }
            passedPlies += passedPlies(g);
        }
        long n = Math.max(1, played.size());
        out.println("per layer over the played-out games (first firing of each layer in a game):");
        out.println("  " + s1.line("S1", totalPlies, n) + "  [disagreements are checked below]");
        out.println("  " + margin.line("margin", totalPlies, n));
        out.println("  " + eval.line("eval", totalPlies, n));
        out.println("  " + combined.line("combined", totalPlies, n) + "  [first firing of any enabled layer]");
        long marginBeforeS1 = played.stream().filter(g -> g.label() == Layer.MARGIN).count();
        out.printf(Locale.ROOT, "  labels by margin (first firing before S1): %d; passed plies %.1f%%; race-band "
                + "scores ignored by layer 2: %d%n", marginBeforeS1, 100.0 * passedPlies / Math.max(1, totalPlies),
                raceIgnored);
        for (String w : margin.wrong) {
            out.println("  wrong margin label: " + w);
        }
        for (String w : eval.wrong) {
            out.println("  wrong eval label: " + w);
        }
        // margin-labelled positions must never be passed (label policy); checked on replay
        long passedMarginLabels = 0;
        for (GameRecord g : games) {
            if (g.margin() != null && RaceAdjudication.passed(replay(g, g.margin().ply()))) {
                passedMarginLabels++;
            }
        }
        out.println("  margin firings in passed positions (must be 0): " + passedMarginLabels);
        out.println("  margin firings backed by a race-band score (must be 0): " + raceBackedMarginLabels(games));
        diversity(games, out);
        colour(games, out);
        cost(games, out);
    }

    /**
     * Margin firings whose backing scores (the labelled player's own moves right before the firing ply, every second
     * stored score) include a race-band score or a move without a search score; the label policy says 0. The layer
     * resets its streak on such scores, so a count above 0 is a bug in the tool, not in the data.
     */
    static long raceBackedMarginLabels(List<GameRecord> games) {
        long bad = 0;
        for (GameRecord g : games) {
            if (g.margin() == null) {
                continue;
            }
            int ply = g.margin().ply();
            int moverMoves = 0;
            for (int i = ply - 1; i >= 0 && moverMoves < Adjudication.Config.DEFAULT.marginMoves(); i -= 2) {
                int s = g.scores()[i];
                if (s == GameRecord.NO_SCORE || Scores.kind(s) == Scores.Kind.RACE) {
                    bad++;
                    break;
                }
                moverMoves++;
            }
        }
        return bad;
    }

    private static long count(List<GameRecord> games, Layer l) {
        return games.stream().filter(g -> g.label() == l).count();
    }

    static Position replay(GameRecord g, int ply) {
        Position pos = Position.initial(RuleSet.defaults(2));
        for (int i = 0; i < ply; i++) {
            pos.make(g.moves()[i]);
        }
        return pos;
    }

    private static long passedPlies(GameRecord g) {
        Position pos = Position.initial(RuleSet.defaults(2));
        long n = 0;
        for (int m : g.moves()) {
            if (RaceAdjudication.passed(pos)) {
                n++;
            }
            pos.make(m);
        }
        return n;
    }

    /**
     * Distinct positions up to the mirror at plies 4, 10, 20 and 40, per block of 1000 consecutive games (the plan's
     * unit; the share falls as a set grows, because more games can collide) and over the whole set.
     */
    static void diversity(List<GameRecord> games, PrintStream out) {
        int block = Math.min(1000, games.size());
        StringBuilder b = new StringBuilder("diversity (distinct positions up to the mirror / games reaching the ply; "
                + "mean and minimum over blocks of " + block + " games, then the whole set):");
        for (int ply : new int[] {4, 10, 20, 40}) {
            double sum = 0;
            double min = 1;
            int blocks = 0;
            for (int from = 0; from + block <= games.size(); from += block) {
                double share = distinctShare(games.subList(from, from + block), ply);
                sum += share;
                min = Math.min(min, share);
                blocks++;
            }
            b.append(String.format(Locale.ROOT, "  ply %d: %.3f (min %.3f), all %.3f", ply, sum / blocks, min,
                    distinctShare(games, ply)));
        }
        out.println(b);
    }

    static double distinctShare(List<GameRecord> games, int ply) {
        Set<Long> keys = new HashSet<>();
        int reached = 0;
        for (GameRecord g : games) {
            if (g.moves().length >= ply) {
                reached++;
                keys.add(OpeningSampler.canonicalKey(replay(g, ply)));
            }
        }
        return (double) keys.size() / Math.max(1, reached);
    }

    /** Score of player 0 (the first mover of the game) from the labels, as Elo with a 95% interval. */
    static void colour(List<GameRecord> games, PrintStream out) {
        double sum = 0;
        double sq = 0;
        for (GameRecord g : games) {
            double s = (1 + g.labelValues()[0]) / 2;
            sum += s;
            sq += s * s;
        }
        int n = games.size();
        double mean = sum / n;
        double se = Math.sqrt(Math.max(0, sq / n - mean * mean) / Math.max(1, n - 1));
        out.printf(Locale.ROOT, "colour: player 0 scores %.4f +- %.4f, Elo(first mover) %+.1f [%+.1f, %+.1f] (95%%)%n",
                mean, 1.96 * se, elo(mean), elo(mean - 1.96 * se), elo(mean + 1.96 * se));
        StringBuilder b = new StringBuilder("  by opening plies (score of player 0, games):");
        for (int k = 0; k <= 20; k++) {
            int kk = k;
            List<GameRecord> sub = games.stream().filter(g -> g.openingPlies() == kk).toList();
            if (!sub.isEmpty()) {
                double s = sub.stream().mapToDouble(g -> (1 + g.labelValues()[0]) / 2).average().orElse(0);
                b.append(String.format(Locale.ROOT, "  %d: %.3f (%d)", k, s, sub.size()));
            }
        }
        out.println(b);
    }

    static double elo(double score) {
        double s = Math.min(0.9999, Math.max(0.0001, score));
        return -400 * Math.log10(1 / s - 1);
    }

    static void cost(List<GameRecord> games, PrintStream out) {
        long cpu = 0;
        long search = 0;
        long s1 = 0;
        long nodes = 0;
        long plies = 0;
        for (GameRecord g : games) {
            cpu += g.cpuNanos();
            search += g.searchCpuNanos();
            s1 += g.s1CpuNanos();
            nodes += g.nodes();
            plies += g.moves().length;
        }
        out.printf(Locale.ROOT, "cost: %.3f games per CPU-second (%.1f ms CPU per game; search %.1f%%, layers 1 and 3 "
                + "%.1f%%, rest %.1f%%), %.0f nodes and %.1f plies per game%n", games.size() / (cpu / 1e9),
                cpu / 1e6 / games.size(), 100.0 * search / cpu, 100.0 * s1 / cpu, 100.0 * (cpu - search - s1) / cpu,
                (double) nodes / games.size(), (double) plies / games.size());
    }

    /** Offline layer-2 variants over the stored scores of played-out games. */
    static void sweep(List<GameRecord> games, String spec, PrintStream out) {
        List<GameRecord> played = games.stream().filter(GameRecord::playedOut).toList();
        long totalPlies = played.stream().mapToLong(GameRecord::finalPly).sum();
        out.println("offline sweep of layer 2 over " + played.size() + " played-out games (stored scores):");
        for (String m : spec.split(",")) {
            for (int moves = 1; moves <= 3; moves++) {
                int margin = Integer.parseInt(m.trim());
                Tally t = new Tally();
                Tally withS1 = new Tally();
                for (GameRecord g : played) {
                    Firing f = marginFiring(g, margin, moves);
                    if (f != null) {
                        t.add(g, f);
                    }
                    Firing first = f == null || g.s1() != null && g.s1().ply() <= f.ply() ? g.s1() : f;
                    if (first != null) {
                        withS1.add(g, first);
                    }
                }
                out.println("  " + t.line("m" + margin + "x" + moves, totalPlies, played.size()));
                out.println("  " + withS1.line("  +S1", totalPlies, played.size()));
            }
        }
    }

    /** The first layer-2 firing of a game for a margin and a move count, from the stored scores. */
    static Firing marginFiring(GameRecord g, int margin, int moves) {
        Position pos = Position.initial(RuleSet.defaults(2));
        int[] streak = new int[2];
        int[] sign = new int[2];
        for (int i = 0; i < g.moves().length; i++) {
            int mover = pos.sideToMove();
            int s = g.scores()[i];
            pos.make(g.moves()[i]);
            int sg = 0;
            if (s != GameRecord.NO_SCORE && Scores.kind(s) != Scores.Kind.RACE && Math.abs(s) >= margin) {
                sg = Integer.signum(s);
            }
            if (sg != 0 && sg == sign[mover]) {
                streak[mover]++;
            } else {
                sign[mover] = sg;
                streak[mover] = sg == 0 ? 0 : 1;
            }
            if (streak[mover] >= moves && pos.outcome() == null && !RaceAdjudication.passed(pos)) {
                return new Firing(Layer.MARGIN, pos.ply(), sg > 0 ? mover : 1 - mover, -1);
            }
        }
        return null;
    }

    /** S1 play-out disagreements, each checked against the plan. */
    static void checkS1(List<GameRecord> games, PrintStream out) {
        RaceAdjudication adj = new RaceAdjudication(RaceTable.rows2());
        int disagreements = 0;
        int failures = 0;
        int capped = 0;
        for (GameRecord g : games) {
            if (!g.playedOut() || g.s1() == null || g.finalValues()[g.s1().winner()] == 1.0) {
                continue;
            }
            disagreements++;
            Position pos = replay(g, g.s1().ply());
            RaceAdjudication.Verdict v = adj.adjudicate(pos);
            String result;
            if (v == null || v.winner() != g.s1().winner() || v.endPly() != g.s1().endPly()) {
                failures++;
                result = "S1 does not reproduce the stored verdict";
            } else {
                VerdictCheck.Result r = VerdictCheck.check(pos, v, g.moves(), 2_000_000);
                if (r.failure() != null) {
                    failures++;
                } else if (r.capped()) {
                    capped++;
                }
                result = r.failure() != null ? "FAILED: " + r.failure()
                        : "plan holds (" + r.nodes() + " nodes" + (r.capped() ? ", full width capped" : "") + ")";
            }
            out.printf(Locale.ROOT, "  S1 play-out disagreement: game %d, verdict at ply %d for player %d by ply %d "
                    + "(plan of %d moves), game ended at ply %d: %s%n", g.id(), g.s1().ply(), g.s1().winner(),
                    g.s1().endPly(), v == null ? -1 : v.winnerMoves(), g.finalPly(), result);
        }
        long fired = games.stream().filter(g -> g.playedOut() && g.s1() != null).count();
        out.printf(Locale.ROOT, "S1: %d verdicts in played-out games, %d play-out disagreements (conversion "
                + "failures, CP95 upper %.2f%%), %d verdict check failures (label errors, must be 0), %d checks capped%n",
                fired, disagreements, 100 * ClopperPearson.upper95(disagreements, fired), failures, capped);
    }
}
