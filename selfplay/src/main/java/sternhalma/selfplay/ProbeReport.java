package sternhalma.selfplay;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import sternhalma.tablebase.LookupObserver;
import sternhalma.tablebase.RowOneBlocks;

/**
 * Analysis of rank logs ({@link ProbeLog}), the {@code report} command of {@link RankProbe}. Everything is weighted by
 * the lookup counts of the records, not by the number of records. A source is the {@code source} of the manifests.
 *
 * <ol>
 *   <li><b>Input:</b> files, games, records and lookups per source. A source without a lookup prints NO LOOKUPS, never a
 *       "nothing found" conclusion.</li>
 *   <li><b>Roles and k:</b> the share of lookups per role, and per k (stones in row 1) with the cumulative shares for
 *       k&lt;=1 and k&lt;=2.</li>
 *   <li><b>Coverage curves</b> per source and per phase (game-ply band of {@code --phase-width}, default 50, last band
 *       open): lookups, distinct blocks, blocks needed for 50/90/95/99/99.9 % of the lookups, and the share served by
 *       the N most frequent blocks. The convergence line gives the distinct blocks after the first G games: a count that
 *       still grows at the end says that the sample is too small for any block-set claim.</li>
 *   <li><b>Hold-out matrix:</b> a block set is chosen from the even games of a source (the N most frequent blocks, N a
 *       share of the 3879 blocks from {@code --budgets}, in percent, default 1,2,5,10,20, plus the smallest set that covers
 *       99 % of that training weight) and tested on the odd games of every source. Reported: the lookup hit rate and the
 *       share of positions (game, ply, role) with at least one miss. The diagonal is the true hold-out; the other cells are
 *       cross-source.</li>
 *   <li><b>Value differences:</b> fallback minus rows&gt;=1 (the rows&gt;=2 count, only where that table covers the
 *       configuration, i.e. k = 0) and plain-estimate minus rows&gt;=1, per k class, as a histogram of -3..+3 with the
 *       tails; unreachable rows&gt;=1 entries are counted separately.</li>
 * </ol>
 * {@code --emit-blocks FILE --from SOURCE --top N} writes the N most frequent blocks of a source (all its games) as a block
 * set file for the {@code blocks:FILE} race variant.
 */
final class ProbeReport {

    static final int BLOCKS = 3879;
    static final int[] COVERAGE_N = {1, 10, 40, 100, 400, 1000, 2000};
    static final double[] COVERAGE_SHARE = {0.5, 0.9, 0.95, 0.99, 0.999};
    static final int MAX_PHASES = 10;
    static final int K_CLASSES = 5; // 0, 1, 2, 3, 4+
    static final int BINS = 9; // <-3, -3..3, >3

    private ProbeReport() {
    }

    /** What pass 1 collects for one source. */
    private static final class Stats {
        final String source;
        int shards;
        long games;
        long rows;
        long lookups;
        long plies;
        final long[][] roleK = new long[2][9];
        final long[][] phaseBlocks = new long[MAX_PHASES][BLOCKS];
        final long[] allBlocks = new long[BLOCKS];
        final long[][] halfBlocks = new long[2][BLOCKS]; // even games, odd games
        final long[][][] fallbackDiff = new long[K_CLASSES][2][BINS]; // [k class][0 rows2 - rows1, 1 estimate - rows1]
        final long[] fallbackCovered = new long[K_CLASSES];
        final long[] estimateCovered = new long[K_CLASSES];
        final long[] unreachable = new long[K_CLASSES];
        final List<long[]> convergence = new ArrayList<>(); // {games, distinct blocks}
        final BitSet seen = new BitSet(BLOCKS);
        int lastGame = -1;
        final List<Integer> checkpoints = new ArrayList<>(List.of(10, 25, 50, 100, 150, 200, 300, 400, 600, 800, 1000));

        Stats(String source) {
            this.source = source;
        }
    }

    static int run(List<String> dirs, Map<String, String> opts, PrintStream out, PrintStream err) throws IOException {
        if (dirs.isEmpty()) {
            throw new IllegalArgumentException("report needs at least one directory or file");
        }
        int phaseWidth = SelfPlay.intOpt(opts, "phase-width", 50);
        List<Double> budgets = new ArrayList<>();
        for (String b : opts.getOrDefault("budgets", "1,2,5,10,20").split(",")) {
            budgets.add(Double.parseDouble(b));
        }
        List<Path> files = new ArrayList<>();
        for (String d : dirs) {
            Path p = Path.of(d);
            if (Files.isDirectory(p)) {
                try (Stream<Path> s = Files.list(p)) {
                    files.addAll(s.filter(f -> f.getFileName().toString().endsWith(".rlog.gz")).sorted().toList());
                }
            } else {
                files.add(p);
            }
        }
        out.println("rank log report: " + files.size() + " files, phase width " + phaseWidth + " plies");
        if (files.isEmpty()) {
            out.println("NO INPUT FILES: no conclusion possible");
            return 1;
        }
        Map<String, Stats> bySource = new TreeMap<>();
        for (Path f : files) {
            ProbeLog.Shard shard;
            try {
                shard = ProbeLog.read(f);
            } catch (IOException e) {
                err.println("skipped " + f + ": " + e.getMessage());
                continue;
            }
            Stats st = bySource.computeIfAbsent(shard.manifest().getOrDefault("source", "?"), Stats::new);
            st.shards++;
            st.games += Long.parseLong(shard.manifest().get("games"));
            st.plies += Long.parseLong(shard.manifest().get("plies"));
            int firstGame = Integer.parseInt(shard.manifest().get("first"));
            int games = Integer.parseInt(shard.manifest().get("games"));
            // games without any record still count towards the convergence checkpoints
            shard.forEach(r -> collect(st, r, phaseWidth));
            checkpoint(st, firstGame + games);
        }
        for (Stats st : bySource.values()) {
            print(st, out, phaseWidth);
        }
        out.println();
        holdOut(files, bySource, budgets, out, err);
        String emit = opts.get("emit-blocks");
        if (emit != null) {
            Stats from = bySource.get(SelfPlay.require(opts, "from"));
            if (from == null) {
                throw new IllegalArgumentException("--from names no source in the input: " + bySource.keySet());
            }
            int top = Integer.parseInt(SelfPlay.require(opts, "top"));
            BitSet set = topBlocks(from.allBlocks, top);
            RowOneBlocks.write(Path.of(emit), set, "top " + top + " blocks of source " + from.source + ", " + from.lookups
                    + " lookups in " + from.games + " games");
            out.println("wrote " + set.cardinality() + " blocks to " + emit);
        }
        return 0;
    }

    private static void collect(Stats st, ProbeLog.Row r, int phaseWidth) {
        st.rows++;
        st.lookups += r.count();
        st.roleK[r.role() & 1][Math.min(8, r.k())] += r.count();
        int block = r.block();
        st.allBlocks[block] += r.count();
        st.phaseBlocks[Math.min(MAX_PHASES - 1, r.ply() / phaseWidth)][block] += r.count();
        st.halfBlocks[r.game() & 1][block] += r.count();
        int kc = Math.min(K_CLASSES - 1, r.k());
        if (r.rows1() == LookupObserver.UNKNOWN) {
            st.unreachable[kc] += r.count();
        } else {
            if (r.rows2() != LookupObserver.UNKNOWN) {
                st.fallbackCovered[kc] += r.count();
                st.fallbackDiff[kc][0][bin(r.rows2() - r.rows1())] += r.count();
            }
            if (r.estimate() != LookupObserver.UNKNOWN) {
                st.estimateCovered[kc] += r.count();
                st.fallbackDiff[kc][1][bin(r.estimate() - r.rows1())] += r.count();
            }
        }
        st.seen.set(block);
        st.lastGame = Math.max(st.lastGame, r.game());
    }

    /** Records the distinct blocks at the games checkpoints passed by {@code gamesSoFar} (game ids are 0-based). */
    private static void checkpoint(Stats st, int gamesSoFar) {
        while (!st.checkpoints.isEmpty() && st.checkpoints.get(0) <= gamesSoFar) {
            st.convergence.add(new long[] {st.checkpoints.remove(0), st.seen.cardinality()});
        }
    }

    private static int bin(int d) {
        return d < -3 ? 0 : d > 3 ? BINS - 1 : d + 4;
    }

    private static void print(Stats st, PrintStream out, int phaseWidth) {
        out.println();
        out.println("=== source " + st.source + ": " + st.shards + " shards, " + st.games + " games, " + st.plies
                + " plies, " + st.rows + " records, " + st.lookups + " lookups ===");
        if (st.lookups == 0) {
            out.println("NO LOOKUPS in this source: no conclusion about blocks, k or values is possible");
            return;
        }
        for (int role = 0; role < 2; role++) {
            long n = Arrays.stream(st.roleK[role]).sum();
            out.printf(Locale.ROOT, "role %s: %d lookups (%.1f%%)%n", role == 0 ? "leaf" : "raceplay", n, pct(n, st.lookups));
        }
        long[] k = new long[9];
        for (int role = 0; role < 2; role++) {
            for (int i = 0; i < 9; i++) {
                k[i] += st.roleK[role][i];
            }
        }
        out.println("k (stones in row 1), share of lookups, all roles / leaf / raceplay:");
        long cum = 0;
        for (int i = 0; i < 9; i++) {
            cum += k[i];
            out.printf(Locale.ROOT, "  k=%d  %6.2f%%  %6.2f%%  %6.2f%%   cumulative %6.2f%%%n", i, pct(k[i], st.lookups),
                    pct(st.roleK[0][i], Arrays.stream(st.roleK[0]).sum()), pct(st.roleK[1][i], Arrays.stream(st.roleK[1]).sum()),
                    pct(cum, st.lookups));
        }
        long leaf = Arrays.stream(st.roleK[0]).sum();
        long leaf3 = 0;
        for (int i = 3; i < 9; i++) {
            leaf3 += st.roleK[0][i];
        }
        out.printf(Locale.ROOT, "leaf lookups with k>=3: %.2f%% (option 4, the row-1 decomposition, is dead above 5%%)%n",
                pct(leaf3, leaf));
        out.printf(Locale.ROOT, "distinct blocks touched: %d of %d = %.1f%% (option 3, a block subset, is dead above 40%%)%n",
                st.seen.cardinality(), BLOCKS, pct(st.seen.cardinality(), BLOCKS));
        StringBuilder conv = new StringBuilder("distinct blocks after G games:");
        for (long[] c : st.convergence) {
            conv.append(' ').append(c[0]).append(':').append(c[1]);
        }
        out.println(conv + (st.convergence.isEmpty() ? " (fewer games than the first checkpoint)" : ""));
        out.println("coverage by blocks (share of lookups):");
        out.printf(Locale.ROOT, "  %-10s %12s %8s  %s  %s%n", "phase", "lookups", "distinct",
                "blocks for 50/90/95/99/99.9%", "share of top-N: " + Arrays.toString(COVERAGE_N));
        coverageLine(out, "all", st.allBlocks);
        for (int p = 0; p < MAX_PHASES; p++) {
            long n = Arrays.stream(st.phaseBlocks[p]).sum();
            if (n > 0) {
                coverageLine(out, p == MAX_PHASES - 1 ? (p * phaseWidth) + "+" : (p * phaseWidth) + "-" + ((p + 1) * phaseWidth - 1),
                        st.phaseBlocks[p]);
            }
        }
        out.println("value differences (estimate or fallback minus rows>=1), share per bin; bins <-3,-3..3,>3:");
        String[] names = {"k=0", "k=1", "k=2", "k=3", "k>=4"};
        for (int c = 0; c < K_CLASSES; c++) {
            long reach = st.fallbackCovered[c];
            long est = st.estimateCovered[c];
            out.printf(Locale.ROOT, "  %-5s unreachable entries: %d%n", names[c], st.unreachable[c]);
            if (reach > 0) {
                out.println("    rows2 - rows1   n=" + reach + "  " + hist(st.fallbackDiff[c][0], reach));
            } else {
                out.println("    rows2 - rows1   no lookup (the rows>=2 table covers k=0 only)");
            }
            if (est > 0) {
                out.println("    estimate - rows1 n=" + est + "  " + hist(st.fallbackDiff[c][1], est));
            }
        }
    }

    private static String hist(long[] bins, long n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < BINS; i++) {
            sb.append(String.format(Locale.ROOT, "%s%.2f%%", i == 0 ? "" : " ", pct(bins[i], n)));
        }
        return sb.toString();
    }

    private static void coverageLine(PrintStream out, String label, long[] blocks) {
        long total = Arrays.stream(blocks).sum();
        long[] sorted = blocks.clone();
        Arrays.sort(sorted);
        long[] desc = new long[sorted.length];
        for (int i = 0; i < sorted.length; i++) {
            desc[i] = sorted[sorted.length - 1 - i];
        }
        int distinct = (int) Arrays.stream(blocks).filter(c -> c > 0).count();
        StringBuilder need = new StringBuilder();
        long run = 0;
        int idx = 0;
        for (double share : COVERAGE_SHARE) {
            while (idx < desc.length && run < share * total) {
                run += desc[idx++];
            }
            need.append(need.length() == 0 ? "" : "/").append(idx);
        }
        StringBuilder top = new StringBuilder();
        long acc = 0;
        int pos = 0;
        for (int n : COVERAGE_N) {
            while (pos < Math.min(n, desc.length)) {
                acc += desc[pos++];
            }
            top.append(String.format(Locale.ROOT, "%s%.1f%%", top.length() == 0 ? "" : " ", pct(acc, total)));
        }
        out.printf(Locale.ROOT, "  %-10s %12d %8d  %-28s  %s%n", label, total, distinct, need, top);
    }

    /** The N blocks with the largest count (ties: lower index). */
    static BitSet topBlocks(long[] counts, int n) {
        Integer[] order = new Integer[counts.length];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> counts[a] != counts[b] ? Long.compare(counts[b], counts[a]) : Integer.compare(a, b));
        BitSet set = new BitSet(counts.length);
        for (int i = 0; i < Math.min(n, order.length) && counts[order[i]] > 0; i++) {
            set.set(order[i]);
        }
        return set;
    }

    /** The smallest set of most frequent blocks that holds at least {@code share} of the weight. */
    static BitSet coverSet(long[] counts, double share) {
        long total = Arrays.stream(counts).sum();
        BitSet all = topBlocks(counts, counts.length);
        Integer[] order = new Integer[all.cardinality()];
        int j = 0;
        for (int b = all.nextSetBit(0); b >= 0; b = all.nextSetBit(b + 1)) {
            order[j++] = b;
        }
        Arrays.sort(order, (a, b) -> counts[a] != counts[b] ? Long.compare(counts[b], counts[a]) : Integer.compare(a, b));
        BitSet set = new BitSet(counts.length);
        long run = 0;
        for (int b : order) {
            if (run >= share * total) {
                break;
            }
            set.set(b);
            run += counts[b];
        }
        return set;
    }

    /** Pass 2: sets from the even games of each source, tested on the odd games of each source. */
    private static void holdOut(List<Path> files, Map<String, Stats> bySource, List<Double> budgets, PrintStream out,
            PrintStream err) throws IOException {
        List<String> names = bySource.entrySet().stream().filter(e -> e.getValue().lookups > 0).map(Map.Entry::getKey).toList();
        if (names.isEmpty()) {
            out.println("hold-out matrix: NO LOOKUPS in any source");
            return;
        }
        List<String> labels = new ArrayList<>();
        List<Integer> sizes = new ArrayList<>();
        for (double b : budgets) {
            labels.add("top " + b + "% (" + Math.max(1, (int) Math.ceil(b / 100.0 * BLOCKS)) + " blocks)");
        }
        for (int i = 0; i < budgets.size(); i++) {
            sizes.add(Math.max(1, (int) Math.ceil(budgets.get(i) / 100.0 * BLOCKS)));
        }
        // sets[train source][set index]; the last set index is the 99% cover set
        BitSet[][] sets = new BitSet[names.size()][sizes.size() + 1];
        for (int x = 0; x < names.size(); x++) {
            long[] even = bySource.get(names.get(x)).halfBlocks[0];
            for (int s = 0; s < sizes.size(); s++) {
                sets[x][s] = topBlocks(even, sizes.get(s));
            }
            sets[x][sizes.size()] = coverSet(even, 0.99);
        }
        labels.add("cover 99% of even games");
        int nSets = sizes.size() + 1;
        long[][][] hit = new long[names.size()][nSets][names.size()];
        long[][] total = new long[names.size()][1];
        long[][][] groupMiss = new long[names.size()][nSets][names.size()];
        long[] groups = new long[names.size()];
        for (Path f : files) {
            ProbeLog.Shard shard;
            try {
                shard = ProbeLog.read(f);
            } catch (IOException e) {
                continue;
            }
            int y = names.indexOf(shard.manifest().getOrDefault("source", "?"));
            if (y < 0) {
                continue;
            }
            boolean[][] missInGroup = new boolean[names.size()][nSets];
            long[] key = {-1};
            int yy = y;
            shard.forEach(r -> {
                if ((r.game() & 1) == 0) {
                    return; // odd games test
                }
                long g = ((long) r.game() << 40) | ((long) r.ply() << 2) | (r.role() & 1);
                if (g != key[0]) {
                    if (key[0] >= 0) {
                        flush(groupMiss, missInGroup, yy, groups);
                    }
                    key[0] = g;
                }
                total[yy][0] += r.count();
                for (int x = 0; x < names.size(); x++) {
                    for (int s = 0; s < nSets; s++) {
                        if (sets[x][s].get(r.block())) {
                            hit[x][s][yy] += r.count();
                        } else {
                            missInGroup[x][s] = true;
                        }
                    }
                }
            });
            if (key[0] >= 0) {
                flush(groupMiss, missInGroup, yy, groups);
            }
        }
        out.println("hold-out matrix: block sets from the EVEN games of the row source, tested on the ODD games of the column source");
        out.println("(cell: lookup hit rate / share of positions with at least one miss; n = odd-game lookups and positions)");
        StringBuilder head = new StringBuilder(String.format(Locale.ROOT, "  %-28s", "set \\ test on"));
        for (int y = 0; y < names.size(); y++) {
            head.append(String.format(Locale.ROOT, " %-24s", names.get(y) + " (n=" + total[y][0] + ", pos=" + groups[y] + ")"));
        }
        out.println(head);
        for (int x = 0; x < names.size(); x++) {
            for (int s = 0; s < nSets; s++) {
                StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "  %-28s",
                        names.get(x) + ": " + labels.get(s) + " [" + sets[x][s].cardinality() + "]"));
                for (int y = 0; y < names.size(); y++) {
                    line.append(String.format(Locale.ROOT, " %-24s", total[y][0] == 0 ? "no odd-game lookups"
                            : String.format(Locale.ROOT, "%.2f%% / %.2f%%", pct(hit[x][s][y], total[y][0]),
                                    pct(groupMiss[x][s][y], groups[y]))));
                }
                out.println(line);
            }
        }
    }

    private static void flush(long[][][] groupMiss, boolean[][] missInGroup, int y, long[] groups) {
        groups[y]++;
        for (int x = 0; x < missInGroup.length; x++) {
            for (int s = 0; s < missInGroup[x].length; s++) {
                if (missInGroup[x][s]) {
                    groupMiss[x][s][y]++;
                    missInGroup[x][s] = false;
                }
            }
        }
    }

    private static double pct(long part, long whole) {
        return whole == 0 ? Double.NaN : 100.0 * part / whole;
    }
}
