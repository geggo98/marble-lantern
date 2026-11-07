package sternhalma.tablebase;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import sternhalma.engine.Position;
import sternhalma.engine.RuleSet.LeaveGoal;

/**
 * The race table for the region "rows &gt;= {@value #MIN_ROW}" of player 0 (28 allowed cells, C(28, 10) =
 * 13,123,110 configurations, one byte each), built with {@code leaveGoal = FORBIDDEN}, the project default.
 * See {@link RaceCounts} for what a count means and what it must not be used for.
 *
 * <p><b>Where the table comes from.</b> {@link #rows2()} looks for {@code rows2.bin} in the directory named by
 * the system property {@code sternhalma.tablebase.dir}, else in {@code tablebase/tables/} of the repository
 * root (found by walking up from the working directory to {@code settings.gradle.kts}), else in the cache
 * {@code tablebase/build/tables/}. The committed file is tracked with Git LFS; a clone without LFS content has
 * only a pointer file there. A pointer, a missing file, a file with a wrong checksum or of another format version
 * is not used: the table is then rebuilt ({@link RetrogradeBuilder}, about 10 to 30 seconds), written to the
 * cache and checked against {@link #SHA256}, the SHA-256 of the file the builder is known to produce. A real
 * file is used only when its own SHA-256 equals {@link #SHA256}, so a stale or corrupt table never plays.
 *
 * <p>Run {@code RaceTable <directory>} (Gradle task {@code :tablebase:buildTable}) to write the file.
 */
public final class RaceTable implements RaceCounts {

    public static final int MIN_ROW = 2;
    public static final String FILE_NAME = "rows2.bin";
    public static final LeaveGoal LEAVE_GOAL = LeaveGoal.FORBIDDEN;

    /**
     * SHA-256 of the complete table file that {@link RetrogradeBuilder} produces for this region and version.
     * Re-record it (with the reason in the commit message) only when the format or the solitaire rules change.
     */
    public static final String SHA256 = "d62edf38bbccb229ca055da3054a11e6daffae817d2293dbfa523ad8bb3678e3";

    private static volatile RaceTable shared;

    private final Ranking ranking;
    private final byte[] table;
    private final int maxCount;

    private RaceTable(Ranking ranking, byte[] table) {
        this.ranking = ranking;
        this.table = table;
        int max = 0;
        for (byte b : table) {
            int v = b & 0xFF;
            if (v != RetrogradeBuilder.UNKNOWN) {
                max = Math.max(max, v);
            }
        }
        this.maxCount = max;
    }

    /** The ranking of the rows&gt;=2 region. */
    public static Ranking ranking() {
        return new Ranking(Rows.regionCells(MIN_ROW), Position.PIECES);
    }

    /** The shared instance; loads or builds the table on first use (thread-safe). */
    public static RaceTable rows2() {
        RaceTable t = shared;
        if (t == null) {
            synchronized (RaceTable.class) {
                t = shared;
                if (t == null) {
                    t = loadOrBuild();
                    shared = t;
                }
            }
        }
        return t;
    }

    /** Builds the table in memory without touching the file system. */
    public static RaceTable build(int threads) {
        Ranking ranking = ranking();
        return new RaceTable(ranking, RetrogradeBuilder.build(ranking, LEAVE_GOAL, threads));
    }

    /** The encoded file content of this table. */
    public byte[] encode() {
        return TableFile.encode(LEAVE_GOAL, MIN_ROW, ranking, table);
    }

    public Ranking rankingOf() {
        return ranking;
    }

    /** The largest count in the table. */
    public int maxCount() {
        return maxCount;
    }

    /** The raw entry of a rank ({@link RetrogradeBuilder#UNKNOWN} for an unreachable configuration). */
    public int entry(long rank) {
        return table[(int) rank] & 0xFF;
    }

    @Override
    public boolean covers(int[] ownCells) {
        if (!ranking.covers(ownCells)) {
            return false;
        }
        return entry(ranking.rank(ownCells)) != RetrogradeBuilder.UNKNOWN;
    }

    @Override
    public int count(int[] ownCells) {
        if (!ranking.covers(ownCells)) {
            return -1;
        }
        int v = entry(ranking.rank(ownCells));
        return v == RetrogradeBuilder.UNKNOWN ? -1 : v;
    }

    // ---- loading ----

    static Path repositoryRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isRegularFile(dir.resolve("settings.gradle.kts"))
                    && Files.isDirectory(dir.resolve("tablebase"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        return null;
    }

    private static RaceTable loadOrBuild() {
        String property = System.getProperty("sternhalma.tablebase.dir");
        return resolve(property == null ? null : Path.of(property), repositoryRoot(), true);
    }

    /**
     * The table from the first valid file among {@code propertyDir/rows2.bin}, {@code root/tablebase/tables/rows2.bin}
     * and {@code root/tablebase/build/tables/rows2.bin}; otherwise, if {@code allowBuild}, a rebuilt table that is
     * checked against {@link #SHA256} and cached (in {@code propertyDir}, else under {@code root/tablebase/build/tables},
     * else in the temporary directory). Without {@code allowBuild} a missing table gives null.
     */
    static RaceTable resolve(Path propertyDir, Path root, boolean allowBuild) {
        try {
            Path[] candidates = {
                propertyDir == null ? null : propertyDir.resolve(FILE_NAME),
                root == null ? null : root.resolve("tablebase").resolve("tables").resolve(FILE_NAME),
                root == null ? null : root.resolve("tablebase").resolve("build").resolve("tables").resolve(FILE_NAME),
            };
            for (Path file : candidates) {
                RaceTable loaded = tryLoad(file);
                if (loaded != null) {
                    return loaded;
                }
            }
            if (!allowBuild) {
                return null;
            }
            Path cache = propertyDir != null ? propertyDir
                    : root != null ? root.resolve("tablebase").resolve("build").resolve("tables")
                    : Path.of(System.getProperty("java.io.tmpdir")).resolve("sternhalma-tablebase");
            int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
            System.err.printf(Locale.ROOT, "[tablebase] no valid %s found; building (%d threads) into %s%n",
                    FILE_NAME, threads, cache);
            long t0 = System.nanoTime();
            RaceTable built = build(threads);
            byte[] encoded = built.encode();
            String sha = java.util.HexFormat.of().formatHex(TableFile.sha256(encoded));
            System.err.printf(Locale.ROOT, "[tablebase] built in %.1f s, sha256 %s%n", (System.nanoTime() - t0) / 1e9, sha);
            if (!sha.equals(SHA256)) {
                throw new IllegalStateException("the rebuilt table has sha256 " + sha + ", but RaceTable.SHA256 is "
                        + SHA256 + ": the builder or the format changed without re-recording the constant");
            }
            try {
                TableFile.write(cache.resolve(FILE_NAME), encoded);
            } catch (IOException e) {
                System.err.println("[tablebase] could not cache the table: " + e);
            }
            return built;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The table in {@code file} if it is a real, matching file; null if absent, a pointer, stale or corrupt. */
    static RaceTable tryLoad(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        if (TableFile.isLfsPointer(file)) {
            String oid = TableFile.lfsPointerOid(file);
            System.err.printf(Locale.ROOT, "[tablebase] %s is a Git LFS pointer (oid %s)%s%n", file, oid,
                    SHA256.equals(oid) ? "" : "; its oid differs from RaceTable.SHA256, the committed table is stale");
            return null;
        }
        String sha = TableFile.sha256Hex(file);
        if (!sha.equals(SHA256)) {
            System.err.printf(Locale.ROOT, "[tablebase] %s has sha256 %s, expected %s; ignored%n", file, sha, SHA256);
            return null;
        }
        TableFile.Content content = TableFile.read(file);
        Ranking ranking = ranking();
        TableFile.Header h = content.header();
        if (h.leaveGoal() != LEAVE_GOAL || h.minRow() != MIN_ROW || h.pieces() != Position.PIECES
                || !java.util.Arrays.equals(h.cells(), ranking.cells()) || h.states() != ranking.count()) {
            System.err.printf(Locale.ROOT, "[tablebase] %s describes another region; ignored%n", file);
            return null;
        }
        return new RaceTable(ranking, content.body());
    }

    /** Builds the table and writes it to {@code <directory>/rows2.bin}. */
    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args.length > 0 ? args[0] : "tablebase/tables");
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        long t0 = System.nanoTime();
        RaceTable built = build(threads);
        byte[] encoded = built.encode();
        String sha = java.util.HexFormat.of().formatHex(TableFile.sha256(encoded));
        Path file = dir.resolve(FILE_NAME);
        TableFile.write(file, encoded);
        long unknown = 0;
        long[] layers = new long[built.maxCount() + 1];
        for (byte b : built.table) {
            int v = b & 0xFF;
            if (v == RetrogradeBuilder.UNKNOWN) {
                unknown++;
            } else {
                layers[v]++;
            }
        }
        System.out.printf(Locale.ROOT, "wrote %s: %d states, %d bytes, max count %d, unreachable %d, %.1f s%n",
                file, built.table.length, encoded.length, built.maxCount(), unknown, (System.nanoTime() - t0) / 1e9);
        System.out.println("layers " + java.util.Arrays.toString(layers));
        System.out.println("sha256 " + sha + (sha.equals(SHA256) ? " (matches RaceTable.SHA256)"
                : " (DIFFERS from RaceTable.SHA256 = " + SHA256 + "; re-record the constant with a reason)"));
    }
}
