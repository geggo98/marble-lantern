package sternhalma.selfplay;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.nn.ForwardPass;
import sternhalma.nn.Networks;
import sternhalma.nn.Parameters;
import sternhalma.search.MctsOptions;
import sternhalma.search.MctsPlayer;
import sternhalma.search.MctsResult;

/**
 * The noise floor of the policy targets (docs/training.md, "Capacity diagnostics 1"): how far apart are two searches of
 * the same network on the same position? Positions come from the held-out games of some generations of a loop run (the
 * loop's own split, {@link Loop#heldOut}); for every position the tool runs
 *
 * <ul>
 *   <li>two searches as in the self-play of the loop (root noise, {@code --sims} simulations) with two seeds, and reports
 *       the divergence between their visit distributions (both directions averaged), and
 *   <li>without noise {@code --sims} against {@code --long-sims} simulations: KL of the long search from the short one.
 * </ul>
 *
 * <p>Visit counts have zeros, so a plain KL is infinite. The primary figure smooths both distributions by adding half a
 * visit to every legal move (Jeffreys); the tool also writes a 1 % uniform mixture and the Jensen-Shannon divergence,
 * which needs no smoothing.
 *
 * <pre>
 *   TargetNoise --loop DIR --net FILE --out DIR [--generations 10-13] [--positions 2000] [--sims 200] [--long-sims 1600]
 *               [--noise 0.25] [--alpha 0.3] [--race 1] [--threads 8] [--seed 1]
 * </pre>
 *
 * Resumable: every finished position is one line of {@code OUT/positions.jsonl}, a rerun skips them. Exit 0 done, 1 error,
 * 2 usage, 75 running already.
 */
final class TargetNoise {

    static final int EXIT_OK = 0;
    static final int EXIT_ERROR = 1;
    static final int EXIT_USAGE = 2;

    private static final int MIN_PLY = 10;
    private static final int MAX_PLY = 80;

    private TargetNoise() {
    }

    /** A position to search: generation, game and ply, and the moves that lead to it. */
    private record Spot(int generation, int game, int ply, int[] moves) {
    }

    public static void main(String[] args) throws Exception {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) throws Exception {
        Map<String, String> o = new TreeMap<>();
        for (int i = 0; i < args.length; i += 2) {
            if (!args[i].startsWith("--") || i + 1 >= args.length) {
                err.println("usage: TargetNoise --loop DIR --net FILE --out DIR [options] (see the class comment)");
                return EXIT_USAGE;
            }
            o.put(args[i].substring(2), args[i + 1]);
        }
        if (!o.containsKey("loop") || !o.containsKey("net") || !o.containsKey("out")) {
            err.println("usage: TargetNoise --loop DIR --net FILE --out DIR [options]");
            return EXIT_USAGE;
        }
        Path loop = Path.of(o.get("loop"));
        Path outDir = Path.of(o.get("out"));
        String[] gens = o.getOrDefault("generations", "10-13").split("-");
        int firstGen = Integer.parseInt(gens[0]);
        int lastGen = Integer.parseInt(gens[gens.length - 1]);
        int positions = LoopConfig.integer(o, "positions", 2000);
        int sims = LoopConfig.integer(o, "sims", 200);
        int longSims = LoopConfig.integer(o, "long-sims", 1600);
        double noise = LoopConfig.dbl(o, "noise", 0.25);
        double alpha = LoopConfig.dbl(o, "alpha", 0.3);
        int race = LoopConfig.integer(o, "race", 1);
        int threads = LoopConfig.integer(o, "threads", 8);
        long seed = LoopConfig.lng(o, "seed", 1);
        LoopConfig cfg = LoopConfig.fromJson(RunMonitor.parse(Files.readString(loop.resolve("run.json"))));
        Parameters parameters = Parameters.load(Path.of(o.get("net")));

        List<Spot> spots = pick(loop, cfg, firstGen, lastGen, positions, seed, err);
        Files.createDirectories(outDir);
        Path file = outDir.resolve("positions.jsonl");
        Map<Integer, Map<String, String>> done = new TreeMap<>();
        if (Files.isRegularFile(file)) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                Map<String, String> m = RunMonitor.parse(line);
                if (m != null && m.containsKey("index") && m.containsKey("kl_js")) {
                    done.put(Integer.parseInt(m.get("index")), m);
                }
            }
        }
        MctsOptions d = MctsOptions.defaults();
        // the options of LoopGame: temp 0 (the visits are read, not a move), batch 1, cpuct 1
        MctsOptions noisy = new MctsOptions(sims, 1, 1.0, d.priorTemperature(), noise, alpha, 0, d.fpu(), race,
                d.raceRows(), d.s1(), d.win1(), false, Long.MAX_VALUE);
        MctsOptions quiet = new MctsOptions(sims, 1, 1.0, d.priorTemperature(), 0, alpha, 0, d.fpu(), race, d.raceRows(),
                d.s1(), d.win1(), false, Long.MAX_VALUE);
        MctsOptions longer = new MctsOptions(longSims, 1, 1.0, d.priorTemperature(), 0, alpha, 0, d.fpu(), race,
                d.raceRows(), d.s1(), d.win1(), false, Long.MAX_VALUE);
        ThreadLocal<ForwardPass> perThread = ThreadLocal.withInitial(() -> Networks.fastest(parameters));
        RuleSet rules = RuleSet.defaults(2);
        AtomicInteger finished = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        int todo = positions - done.size();
        long t0 = System.nanoTime();
        err.printf(Locale.ROOT, "targetnoise: %d positions, %d done before, %d threads, noisy %s, long %d sims%n", spots.size(),
                done.size(), threads, sims, longSims);
        try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND); ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < spots.size(); i++) {
                if (done.containsKey(i)) {
                    continue;
                }
                int index = i;
                Spot s = spots.get(i);
                futures.add(pool.submit(() -> {
                    Position pos = Position.initial(rules);
                    for (int k = 0; k < s.ply(); k++) {
                        pos.make(s.moves()[k]);
                    }
                    ForwardPass net = perThread.get();
                    long base = seed * 1_000_003L + index * 4L;
                    MctsResult a = search(net, noisy, base + 1, pos);
                    MctsResult b = search(net, noisy, base + 2, pos);
                    MctsResult c = search(net, quiet, base + 3, pos);
                    MctsResult e = search(net, longer, base + 3, pos);
                    if (a == null || b == null || c == null || e == null || a.sims() == 0 || e.sims() == 0) {
                        skipped.incrementAndGet(); // the race module played it, or one legal move: nothing to compare
                        return;
                    }
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("index", index);
                    f.put("generation", s.generation());
                    f.put("game", s.game());
                    f.put("ply", s.ply());
                    f.put("legal", a.moves().length);
                    f.put("kl_seed", sym(a.visits(), b.visits(), 0.5, 0));
                    f.put("kl_seed_mix", sym(a.visits(), b.visits(), 0, 0.01));
                    f.put("kl_js", js(a.visits(), b.visits()));
                    f.put("kl_ab", kl(a.visits(), b.visits(), 0.5, 0));
                    f.put("kl_ba", kl(b.visits(), a.visits(), 0.5, 0));
                    f.put("kl_long", kl(e.visits(), c.visits(), 0.5, 0));
                    f.put("kl_long_mix", kl(e.visits(), c.visits(), 0, 0.01));
                    f.put("js_long", js(e.visits(), c.visits()));
                    f.put("top1_seed", argmax(a.visits()) == argmax(b.visits()) ? 1 : 0);
                    f.put("top1_long", argmax(e.visits()) == argmax(c.visits()) ? 1 : 0);
                    f.put("entropy", entropy(a.visits()));
                    synchronized (writer) {
                        try {
                            writer.write(RunMonitor.json(f));
                            writer.newLine();
                            writer.flush();
                        } catch (IOException x) {
                            throw new IllegalStateException(x);
                        }
                    }
                    int n = finished.incrementAndGet();
                    if (n % 100 == 0) {
                        double el = (System.nanoTime() - t0) / 1e9;
                        double rate = n / el;
                        err.printf(Locale.ROOT, "targetnoise: %d/%d  %.2f/s  ETA %.0fs  elapsed %.0fs%n", n, todo, rate,
                                (todo - n) / rate, el);
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        }
        return report(file, out, err, skipped.get());
    }

    private static MctsResult search(ForwardPass net, MctsOptions options, long seed, Position pos) {
        MctsPlayer player = MctsPlayer.create(net, options, seed, "targetnoise");
        player.choose(pos);
        return player.lastResult();
    }

    /** The positions: every held-out game of the generations has its searched plies in [10, 80] as candidates, a seeded draw. */
    private static List<Spot> pick(Path loop, LoopConfig cfg, int firstGen, int lastGen, int positions, long seed,
            PrintStream err) throws IOException {
        SplittableRandom random = new SplittableRandom(seed);
        List<Spot> all = new ArrayList<>();
        int perGeneration = positions / (lastGen - firstGen + 1);
        for (int gen = firstGen; gen <= lastGen; gen++) {
            List<Spot> candidates = new ArrayList<>();
            for (Path shard : Loop.shards(loop.resolve(String.format("gen-%04d", gen)).resolve("samples"))) {
                SampleFile.Contents c = SampleFile.read(shard);
                int[] gameId = c.tensor("game_id").i32();
                int[] gameMoves = c.tensor("game_moves").i32();
                int[] offsets = c.tensor("game_move_offsets").i32();
                short[] ply = c.tensor("ply").i16();
                byte[] flags = c.tensor("flags").u8();
                int[] game = c.tensor("game").i32();
                for (int s = 0; s < ply.length; s++) {
                    int g = game[s];
                    if ((flags[s] & 1) == 0 || ply[s] < MIN_PLY || ply[s] > MAX_PLY
                            || !Loop.heldOut(c.metadata(), gameId[g], cfg.holdout(), cfg.games())) {
                        continue;
                    }
                    candidates.add(new Spot(gen, gameId[g], ply[s], Arrays.copyOfRange(gameMoves, offsets[g], offsets[g + 1])));
                }
            }
            Collections.shuffle(candidates, new java.util.Random(random.nextLong()));
            if (candidates.size() < perGeneration) {
                throw new IllegalStateException("generation " + gen + " has only " + candidates.size() + " candidate positions");
            }
            err.printf(Locale.ROOT, "targetnoise: generation %d: %d candidate positions, %d drawn%n", gen, candidates.size(),
                    perGeneration);
            all.addAll(candidates.subList(0, perGeneration));
        }
        return all;
    }

    // ---- divergences ----

    /** The distribution of visits: {@code (v + add) / (N + add L)}, mixed with the uniform distribution by {@code mix}. */
    static double[] dist(int[] visits, double add, double mix) {
        int l = visits.length;
        double n = 0;
        for (int v : visits) {
            n += v;
        }
        double[] p = new double[l];
        for (int i = 0; i < l; i++) {
            p[i] = (1 - mix) * (visits[i] + add) / (n + add * l) + mix / l;
        }
        return p;
    }

    /** KL(p || q) of the two smoothed visit distributions, in nats. */
    static double kl(int[] a, int[] b, double add, double mix) {
        double[] p = dist(a, add, mix);
        double[] q = dist(b, add, mix);
        double s = 0;
        for (int i = 0; i < p.length; i++) {
            if (p[i] > 0) {
                s += p[i] * Math.log(p[i] / q[i]);
            }
        }
        return s;
    }

    static double sym(int[] a, int[] b, double add, double mix) {
        return 0.5 * (kl(a, b, add, mix) + kl(b, a, add, mix));
    }

    /** Jensen-Shannon divergence of the raw visit shares, in nats (at most ln 2). */
    static double js(int[] a, int[] b) {
        double[] p = dist(a, 0, 0);
        double[] q = dist(b, 0, 0);
        double s = 0;
        for (int i = 0; i < p.length; i++) {
            double m = 0.5 * (p[i] + q[i]);
            if (p[i] > 0) {
                s += 0.5 * p[i] * Math.log(p[i] / m);
            }
            if (q[i] > 0) {
                s += 0.5 * q[i] * Math.log(q[i] / m);
            }
        }
        return s;
    }

    static double entropy(int[] visits) {
        double[] p = dist(visits, 0, 0);
        double s = 0;
        for (double x : p) {
            if (x > 0) {
                s -= x * Math.log(x);
            }
        }
        return s;
    }

    static int argmax(int[] v) {
        int best = 0;
        for (int i = 1; i < v.length; i++) {
            if (v[i] > v[best]) {
                best = i;
            }
        }
        return best;
    }

    // ---- report ----

    private static int report(Path file, PrintStream out, PrintStream err, int skipped) throws IOException {
        List<Map<String, String>> rows = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            Map<String, String> m = RunMonitor.parse(line);
            if (m != null && m.containsKey("kl_js")) {
                rows.add(m);
            }
        }
        out.printf(Locale.ROOT, "positions: %d (%d without a comparable search in this run)%n", rows.size(), skipped);
        out.println("measure                               mean   median      p10      p90      p99");
        for (String key : List.of("kl_seed", "kl_ab", "kl_ba", "kl_seed_mix", "kl_js", "kl_long", "kl_long_mix", "js_long",
                "entropy", "legal")) {
            row(out, key, rows, r -> true);
        }
        out.printf(Locale.ROOT, "top-1 agreement, two noisy seeds: %.3f; noise-free 200 vs long: %.3f%n", mean(rows, "top1_seed"),
                mean(rows, "top1_long"));
        for (int[] band : new int[][] {{10, 29}, {30, 54}, {55, 80}}) {
            out.printf(Locale.ROOT, "plies %d-%d:%n", band[0], band[1]);
            for (String key : List.of("kl_seed", "kl_long")) {
                row(out, "  " + key, rows, r -> {
                    int p = Integer.parseInt(r.get("ply"));
                    return p >= band[0] && p <= band[1];
                }, key);
            }
        }
        for (int parity = 0; parity < 2; parity++) {
            int pp = parity;
            row(out, "  kl_seed, mover " + parity, rows, r -> (Integer.parseInt(r.get("ply")) & 1) == pp, "kl_seed");
        }
        return EXIT_OK;
    }

    private interface Filter {
        boolean test(Map<String, String> r);
    }

    private static void row(PrintStream out, String key, List<Map<String, String>> rows, Filter f) {
        row(out, key, rows, f, key);
    }

    private static void row(PrintStream out, String label, List<Map<String, String>> rows, Filter f, String key) {
        double[] v = rows.stream().filter(f::test).mapToDouble(r -> Double.parseDouble(r.get(key))).sorted().toArray();
        if (v.length == 0) {
            return;
        }
        double sum = 0;
        for (double x : v) {
            sum += x;
        }
        out.printf(Locale.ROOT, "%-32s %8.4f %8.4f %8.4f %8.4f %8.4f  (n=%d)%n", label, sum / v.length, q(v, 0.5), q(v, 0.1),
                q(v, 0.9), q(v, 0.99), v.length);
    }

    private static double q(double[] sorted, double p) {
        return sorted[(int) Math.min(sorted.length - 1, Math.floor(p * sorted.length))];
    }

    private static double mean(List<Map<String, String>> rows, String key) {
        return rows.stream().mapToDouble(r -> Double.parseDouble(r.get(key))).average().orElse(Double.NaN);
    }
}
