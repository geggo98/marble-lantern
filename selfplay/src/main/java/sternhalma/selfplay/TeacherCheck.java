package sternhalma.selfplay;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.function.Supplier;

import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.search.AlphaBetaPlayer;
import sternhalma.search.PlayerSpec;
import sternhalma.search.RaceAdjudication;
import sternhalma.search.Scores;
import sternhalma.search.Strategies;

/**
 * The measurement of the teacher samples (round-2 plan, E10): cost per sample and quality on held-out positions.
 *
 * <pre>
 *   SelfPlay teacher-check --out DIR --seed S [--spec SPEC] [--positions 300] [--depths 2,3] [--ref-nodes 100000]
 *                          [--cost-games 20] [--cost-seed S+1] [--threads 1]
 * </pre>
 *
 * <p>Phase 1 plays {@code positions} games of the spec (S1 adjudication, 20000 nodes unless the spec says otherwise)
 * as a resumable job and draws one position per game, at a ply uniform in [10, min(90, cut - 1)]; the lines go to
 * {@code positions-<shard>.txt}. Phase 2 labels the positions at each depth and prints, per depth: the policy NLL of
 * the move of a reference search ({@code --ref-nodes}, a within-family proxy: the reference is the same engine) for the
 * teacher target, for the one-hot target of the bootstrap (1 - eps on the 20000-node move) and for the uniform
 * distribution; the log loss of the blended value target and of the pure teacher value against the game result; and
 * the winner accuracy of the sign of the best score. Phase 3 plays {@code cost-games} further games with samples off
 * and labels every position of them, which gives the CPU time per sample of the game and of the teacher.
 *
 * <p>Line format of phase 1: {@code <game> <ply> <value of player 0> <value of player 1> <moves 0..ply>}; the position
 * is the one before move {@code ply}, and move {@code ply} is the move the 20000-node players played there.
 */
final class TeacherCheck {

    static final String JOB = "teacher-check";
    static final int SHARD = 25;
    static final int MIN_PLY = 10;
    static final int MAX_PLY = 90;

    private TeacherCheck() {
    }

    /** One drawn position. */
    record Item(int game, int ply, double v0, double v1, int[] moves) {

        static Item parse(String line) {
            String[] t = line.trim().split(" ");
            int[] moves = new int[t.length - 4];
            for (int i = 0; i < moves.length; i++) {
                moves[i] = Integer.parseInt(t[4 + i]);
            }
            return new Item(Integer.parseInt(t[0]), Integer.parseInt(t[1]), Double.parseDouble(t[2]),
                    Double.parseDouble(t[3]), moves);
        }

        String format() {
            StringBuilder sb = new StringBuilder();
            sb.append(game).append(' ').append(ply).append(' ').append(v0).append(' ').append(v1);
            for (int m : moves) {
                sb.append(' ').append(m);
            }
            return sb.toString();
        }

        Position position() {
            Position pos = Position.initial(RuleSet.defaults(2));
            for (int i = 0; i < ply; i++) {
                pos.make(moves[i]);
            }
            return pos;
        }

        int played() {
            return moves[ply];
        }
    }

    static int run(Map<String, String> opts, PrintStream out, PrintStream err) throws IOException,
            InterruptedException {
        Path dir = Path.of(SelfPlay.require(opts, "out"));
        if (opts.containsKey("status")) {
            return Job.status(dir, JOB, out);
        }
        String spec = SelfPlay.effectiveSpec(opts.getOrDefault("spec", Strategies.BEST_SPEC),
                opts.getOrDefault("tt", "auto"));
        long seed = Long.parseLong(SelfPlay.require(opts, "seed"));
        int positions = SelfPlay.intOpt(opts, "positions", 300);
        String header = "# teacher-check spec=" + spec + " seed=" + seed;
        SelfPlayGame.Settings settings = new SelfPlayGame.Settings(spec, seed, Adjudication.Config.PRODUCTION, 0,
                OpeningSampler.Mode.UNIFORM, 4, 10, 30, 1, 0.05, false, true);
        Job<String> job = new Job<>(dir, JOB, positions, SHARD, SelfPlay.intOpt(opts, "threads", 1), err) {
            @Override
            Path shardFile(int shard) {
                return dir.resolve(String.format(Locale.ROOT, "positions-%04d.txt", shard));
            }

            @Override
            ShardState check(Path file, int shard, int first, int count) {
                try {
                    List<String> lines = Files.readAllLines(file);
                    if (lines.isEmpty() || !lines.get(0).equals(header)) {
                        mismatch("written by '" + (lines.isEmpty() ? "" : lines.get(0)) + "', not '" + header + "'");
                        return ShardState.MISMATCH;
                    }
                    return lines.size() == count + 1 ? ShardState.COMPLETE : ShardState.CORRUPT;
                } catch (IOException | RuntimeException e) {
                    return ShardState.CORRUPT;
                }
            }

            @Override
            Supplier<Worker<String>> workers() {
                return () -> {
                    SelfPlayGame game = new SelfPlayGame(settings);
                    return id -> {
                        GameRecord g = game.play(id);
                        int top = Math.min(MAX_PLY, g.labelPly() - 1);
                        if (top < MIN_PLY) {
                            return "-"; // too short to draw a position from
                        }
                        int ply = MIN_PLY + new SplittableRandom(seed * 0x9E3779B97F4A7C15L + id)
                                .nextInt(top - MIN_PLY + 1);
                        return new Item(id, ply, g.labelValues()[0], g.labelValues()[1],
                                java.util.Arrays.copyOf(g.moves(), ply + 1)).format();
                    };
                };
            }

            @Override
            void write(Path file, int shard, int first, List<String> results) throws IOException {
                StringBuilder sb = new StringBuilder(header).append('\n');
                for (String l : results) {
                    sb.append(l).append('\n');
                }
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp." + ProcessHandle.current().pid());
                Files.writeString(tmp, sb);
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
        };
        job.progressSeconds = SelfPlay.doubleOpt(opts, "progress", 10);
        err.println(JOB + ": " + positions + " games of " + spec);
        int code = job.run();
        if (code != 0) {
            return code;
        }
        List<Item> items = new ArrayList<>();
        try (var s = Files.list(dir)) {
            for (Path f : s.filter(p -> p.getFileName().toString().matches("positions-\\d+\\.txt")).sorted().toList()) {
                for (String line : Files.readAllLines(f)) {
                    if (!line.startsWith("#") && !line.isBlank() && !line.equals("-")) {
                        items.add(Item.parse(line));
                    }
                }
            }
        }
        quality(items, spec, opts, out, err);
        cost(spec, opts, seed, out, err);
        return 0;
    }

    private static double ln(double x) {
        return Math.log(Math.max(x, 1e-12));
    }

    /** Phase 2: NLL of the reference move and log loss of the value targets. */
    static void quality(List<Item> items, String spec, Map<String, String> opts, PrintStream out, PrintStream err) {
        int[] depths = java.util.Arrays.stream(opts.getOrDefault("depths", "2,3").split(",")).mapToInt(
                Integer::parseInt).toArray();
        long refNodes = Long.parseLong(opts.getOrDefault("ref-nodes", "100000"));
        PlayerSpec ref = PlayerSpec.parse(spec.replaceAll("nodes=\\d+", "nodes=" + refNodes)
                .replaceAll("tt=\\d+", "tt=20") + (spec.contains("nodes=") ? "" : ",nodes=" + refNodes)
                + (spec.contains("tt=") ? "" : ",tt=20"));
        AlphaBetaPlayer refPlayer = ref.create();
        int[] reference = new int[items.size()];
        for (int i = 0; i < items.size(); i++) {
            refPlayer.clearSearch();
            reference[i] = refPlayer.choose(items.get(i).position());
        }
        out.printf(Locale.ROOT, "teacher-check: %d positions, plies %d-%d, reference move from %d nodes, spec %s%n",
                items.size(), MIN_PLY, MAX_PLY, refNodes, spec);
        double uniform = 0;
        double oneHot = 0;
        int agree20k = 0;
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            int n = it.position().legalMoves().length;
            uniform += ln(1.0 / n);
            double p = 0.05 / n + (it.played() == reference[i] ? 0.95 : 0);
            oneHot += ln(p);
            agree20k += it.played() == reference[i] ? 1 : 0;
        }
        out.printf(Locale.ROOT, "  uniform NLL %.3f; one-hot (eps 0.05) on the played move NLL %.3f; played move = reference "
                + "in %.1f%%%n", -uniform / items.size(), -oneHot / items.size(), 100.0 * agree20k / items.size());
        PlayerSpec parsed = PlayerSpec.parse(spec);
        double[] taus = java.util.Arrays.stream(opts.getOrDefault("taus", "0.03,0.04,0.05,0.06,0.08,0.1,0.12,0.15,0.2")
                .split(",")).mapToDouble(Double::parseDouble).toArray();
        for (int depth : depths) {
            double tau = opts.containsKey("tau") ? Double.parseDouble(opts.get("tau")) : Teacher.Config.defaultTau(depth);
            Teacher teacher = new Teacher(new Teacher.Config(depth, tau), parsed);
            double[] nllByTau = new double[taus.length];
            double nll = 0;
            int usable = 0;
            double nllOneHotSame = 0;
            int top1 = 0;
            double blendLoss = 0;
            double pureLoss = 0;
            int pureCount = 0;
            int signRight = 0;
            int signCount = 0;
            for (int i = 0; i < items.size(); i++) {
                Item it = items.get(i);
                Position pos = it.position();
                Teacher.Label label = teacher.label(pos);
                int[] legal = pos.legalMoves();
                int refIndex = java.util.Arrays.stream(legal).boxed().toList().indexOf(reference[i]);
                if (label.policy() != null) {
                    for (int k = 0; k < taus.length; k++) {
                        nllByTau[k] -= ln(Teacher.softmax(label.scores(), taus[k])[refIndex]);
                    }
                    usable++;
                    nll -= ln(label.policy()[refIndex]);
                    double p = 0.05 / legal.length + (it.played() == reference[i] ? 0.95 : 0);
                    nllOneHotSame -= ln(p);
                    int arg = 0;
                    for (int j = 1; j < legal.length; j++) {
                        arg = label.policy()[j] > label.policy()[arg] ? j : arg;
                    }
                    top1 += legal[arg] == reference[i] ? 1 : 0;
                }
                double z = pos.sideToMove() == 0 ? it.v0 : it.v1;
                double[] v = teacher.config().value(new double[] {z, -z}, pos.ply(), label.best(),
                        RaceAdjudication.passed(pos));
                blendLoss += logLoss(z, v[0]);
                if (Teacher.usesScore(label.best()) && !RaceAdjudication.passed(pos)) {
                    double t = 2 / (1 + Math.exp(-label.best() / Teacher.SCORE_SCALE)) - 1;
                    pureLoss += logLoss(z, t);
                    pureCount++;
                    if (z != 0) {
                        signCount++;
                        signRight += (label.best() > 0) == (z > 0) ? 1 : 0;
                    }
                }
            }
            out.printf(Locale.ROOT, "  depth %d (tau %s): policy NLL of the reference move %.3f over %d usable of %d "
                    + "(one-hot on the same %d: %.3f); teacher top-1 = reference in %.1f%%%n", depth,
                    teacher.config().tau(), nll / usable, usable, items.size(), usable, nllOneHotSame / usable,
                    100.0 * top1 / usable);
            out.printf(Locale.ROOT, "          value log loss: blended target %.3f over %d, pure sigma(score/%.0f) %.3f over "
                    + "%d ordinary non-passed; winner by sign of the score %.1f%% of %d; teacher CPU %.2f ms/sample%n",
                    blendLoss / items.size(), items.size(), Teacher.SCORE_SCALE, pureLoss / pureCount, pureCount,
                    100.0 * signRight / signCount, signCount, teacher.cpuNanos() / 1e6 / teacher.samples());
            StringBuilder sweep = new StringBuilder("          NLL by tau:");
            for (int k = 0; k < taus.length; k++) {
                sweep.append(String.format(Locale.ROOT, " %s: %.3f;", taus[k], nllByTau[k] / usable));
            }
            out.println(sweep);
        }
    }

    /** Log loss of the win probability (1 + v) / 2 against the result z (a draw counts as 1/2). */
    static double logLoss(double z, double v) {
        double p = Math.min(1 - 1e-6, Math.max(1e-6, (1 + v) / 2));
        double target = (1 + z) / 2;
        return -(target * Math.log(p) + (1 - target) * Math.log(1 - p));
    }

    /** Phase 3: CPU per sample of the game and of the teacher over every position of a few games. */
    static void cost(String spec, Map<String, String> opts, long seed, PrintStream out, PrintStream err) {
        int games = SelfPlay.intOpt(opts, "cost-games", 20);
        if (games == 0) {
            return;
        }
        long costSeed = Long.parseLong(opts.getOrDefault("cost-seed", Long.toString(seed + 1)));
        SelfPlayGame.Settings settings = new SelfPlayGame.Settings(spec, costSeed, Adjudication.Config.PRODUCTION, 0,
                OpeningSampler.Mode.UNIFORM, 4, 10, 30, 1, 0.05, false, true);
        SelfPlayGame game = new SelfPlayGame(settings);
        PlayerSpec parsed = PlayerSpec.parse(spec);
        int[] depths = java.util.Arrays.stream(opts.getOrDefault("depths", "2,3").split(",")).mapToInt(
                Integer::parseInt).toArray();
        Teacher[] teachers = new Teacher[depths.length];
        for (int d = 0; d < depths.length; d++) {
            teachers[d] = new Teacher(new Teacher.Config(depths[d], Teacher.Config.defaultTau(depths[d])), parsed);
        }
        long samples = 0;
        long gameNanos = 0;
        long raceBest = 0;
        for (int id = 0; id < games; id++) {
            GameRecord g = game.play(id);
            gameNanos += g.cpuNanos();
            Position pos = Position.initial(RuleSet.defaults(2));
            for (int i = 0; i < g.labelPly(); i++) {
                for (int d = 0; d < depths.length; d++) {
                    Teacher.Label label = teachers[d].label(pos);
                    if (d == 0 && Scores.kind(label.best()) == Scores.Kind.RACE) {
                        raceBest++;
                    }
                }
                samples++;
                pos.make(g.moves()[i]);
            }
            err.printf(Locale.ROOT, "teacher-check cost: game %d/%d%n", id + 1, games);
        }
        double gameMs = gameNanos / 1e6 / samples;
        out.printf(Locale.ROOT, "cost over %d samples of %d games (seed %d, one thread, CPU time): game %.3f ms/sample "
                + "(search, adjudication), best score in the race band at depth %d: %.1f%%%n", samples, games, costSeed,
                gameMs, depths[0], 100.0 * raceBest / samples);
        for (int d = 0; d < depths.length; d++) {
            double ms = teachers[d].cpuNanos() / 1e6 / samples;
            double nodes = (double) teachers[d].nodes() / samples;
            out.printf(Locale.ROOT, "  depth %d: teacher %.3f ms/sample, %.0f nodes/sample; total %.3f ms/sample; "
                    + "1M samples = %.2f core-hours (teacher alone %.2f)%n", depths[d], ms, nodes, gameMs + ms,
                    (gameMs + ms) * 1e6 / 3.6e6, ms * 1e6 / 3.6e6);
        }
    }
}
