package sternhalma.tablebase;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import sternhalma.engine.Position;
import sternhalma.engine.RuleSet.LeaveGoal;

/**
 * A race table: the exact restricted solitaire move count for every configuration of player 0's ten pieces on the
 * allowed cells with row {@code >= minRow}, one byte each, built with {@code leaveGoal = FORBIDDEN}, the project
 * default. See {@link RaceCounts} for what a count means and what it must not be used for. Two regions exist:
 *
 * <ul>
 * <li>{@link #ROWS2} (rows &gt;= 2, 28 cells, 13,123,110 states, 12.5 MB): the default table of race play,
 * committed as {@code tablebase/tables/rows2.bin} with Git LFS.</li>
 * <li>{@link #ROWS1} (rows &gt;= 1, 36 cells, 254,186,856 states, 242 MB; round-2 experiment E09): optional.
 * It is committed as {@code tablebase/tables/rows1.bin.zst} (zstd, 21 MB) with Git LFS because it costs several
 * minutes to build and is reused (owner decision, 2026-11-07; compressed 2026-11-18). {@link Rows1Archive} unpacks
 * it into the cache with the {@code zstd} tool. A clone without LFS content has only a pointer file:
 * {@link #rows1()} then rebuilds it into the cache, and {@link #rows1IfCached()} returns null. Every part of the
 * project works without it; race play uses it only when a spec says {@code racerows=1}.</li>
 * </ul>
 *
 * <p><b>Where a table comes from.</b> A table is looked for in the directory named by the system property
 * {@code sternhalma.tablebase.dir}, then (committed regions only) in {@code tablebase/tables/} of the repository
 * root (found by walking up from the working directory to {@code settings.gradle.kts}), then in the cache
 * {@code tablebase/build/tables/}, then, for the rows>=1 region, in the archive {@code rows1.bin.zst} (unpacked into
 * that cache). A Git LFS pointer file (a clone without LFS content), a missing file, a file
 * with a wrong checksum or of another format version is not used: the table is then rebuilt
 * ({@link RetrogradeBuilder}), written to the cache and checked against the region's recorded SHA-256 of the file the
 * builder is known to produce. A real file is used only when its own SHA-256 equals that constant, so a stale or
 * corrupt table never plays.
 *
 * <p>Run {@code RaceTable <directory> [--rows N] [--threads T]} (Gradle task {@code :tablebase:buildTable
 * [-Prows=N]}) to write a file.
 */
public final class RaceTable implements RaceCounts {

    /** The region of the default table. */
    public static final int MIN_ROW = 2;
    public static final String FILE_NAME = "rows2.bin";
    public static final LeaveGoal LEAVE_GOAL = LeaveGoal.FORBIDDEN;

    /**
     * SHA-256 of the complete rows&gt;=2 table file that {@link RetrogradeBuilder} produces for this format version.
     * Re-record it (with the reason in the commit message) only when the format or the solitaire rules change.
     */
    public static final String SHA256 = "d62edf38bbccb229ca055da3054a11e6daffae817d2293dbfa523ad8bb3678e3";

    public static final String ROWS1_FILE_NAME = "rows1.bin";

    /** Suffix of the zstd archive of a region's file, committed with Git LFS (see {@link Region#archived()}). */
    public static final String ARCHIVE_SUFFIX = ".zst";

    /**
     * SHA-256 of the complete rows&gt;=1 table file that the builder produces (recorded from two builds with
     * different thread counts). Only the zstd archive {@code tablebase/tables/rows1.bin.zst} is committed (Git
     * LFS); it is unpacked into the cache. See {@link #ROWS1}.
     */
    public static final String ROWS1_SHA256 = "85850d6f47f526daf45ac5cd3a0dad8841244cb50d0d8d15a5b6a54f09d1bfd1";

    /**
     * A table region with the name and the recorded checksum of its file.
     *
     * @param minRow    allowed cells of player 0 with row {@code >= minRow}
     * @param fileName  the file name in a table directory
     * @param sha256    SHA-256 of the file the builder produces
     * @param committed whether the file is committed under {@code tablebase/tables/} (Git LFS); an uncommitted
     *                  region is looked for and written in the cache only
     * @param archived  whether a zstd archive {@code <fileName>.zst} may be unpacked into the cache (Git LFS)
     */
    public record Region(int minRow, String fileName, String sha256, boolean committed, boolean archived) {

        /** A region without an archive. */
        public Region(int minRow, String fileName, String sha256, boolean committed) {
            this(minRow, fileName, sha256, committed, false);
        }

        /** The ranking of this region. */
        public Ranking ranking() {
            return new Ranking(Rows.regionCells(minRow), Position.PIECES);
        }
    }

    public static final Region ROWS2 = new Region(MIN_ROW, FILE_NAME, SHA256, true);
    public static final Region ROWS1 = new Region(1, ROWS1_FILE_NAME, ROWS1_SHA256, true, true);

    /** States above which a build prints one progress line per layer. */
    private static final long VERBOSE_BUILD_STATES = 100_000_000L;

    private static volatile RaceTable sharedRows2;
    private static volatile RaceTable sharedRows1;

    private final Region region;
    private final Ranking ranking;
    private final byte[] table;
    private final int maxCount;

    private RaceTable(Region region, Ranking ranking, byte[] table) {
        this.region = region;
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

    /** The region with that minimum row ({@link #ROWS2} or {@link #ROWS1}). */
    public static Region region(int minRow) {
        return switch (minRow) {
            case 2 -> ROWS2;
            case 1 -> ROWS1;
            default -> throw new IllegalArgumentException("no race table for rows >= " + minRow + " (1 or 2)");
        };
    }

    /** The ranking of the rows&gt;=2 region. */
    public static Ranking ranking() {
        return ROWS2.ranking();
    }

    /**
     * A rows>=2 table from the bytes of a table file, without file system, threads or digest (browser build): checks
     * magic, version, header and size, not the body checksum (the page checks the SHA-256 of the manifest).
     */
    public static RaceTable fromFileBytes(byte[] fileBytes) {
        try {
            TableFile.Content content = TableFile.readUnchecked(new java.io.ByteArrayInputStream(fileBytes), "rows2.bin");
            Ranking ranking = ROWS2.ranking();
            TableFile.Header h = content.header();
            if (h.leaveGoal() != LEAVE_GOAL || h.minRow() != ROWS2.minRow || h.pieces() != Position.PIECES
                    || !java.util.Arrays.equals(h.cells(), ranking.cells()) || h.states() != ranking.count()) {
                throw new IllegalArgumentException("rows2.bin describes another region");
            }
            return new RaceTable(ROWS2, ranking, content.body());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The shared rows&gt;=2 table; loads or builds it on first use (thread-safe). */
    public static RaceTable rows2() {
        return shared(ROWS2, true);
    }

    /**
     * The shared rows&gt;=1 table; loads it from the cache or builds it on first use (254 million states, several
     * minutes, progress on stderr). Thread-safe.
     */
    public static RaceTable rows1() {
        return shared(ROWS1, true);
    }

    /** The shared rows&gt;=1 table if a valid file is in the cache or in {@code sternhalma.tablebase.dir}, else null. */
    public static RaceTable rows1IfCached() {
        return shared(ROWS1, false);
    }

    /** The shared table of a region by its minimum row, loaded or built on first use. */
    public static RaceTable of(int minRow) {
        return shared(region(minRow), true);
    }

    private static final java.util.concurrent.atomic.AtomicInteger ROWS1_REQUESTS = new java.util.concurrent.atomic.AtomicInteger();

    /**
     * How often any code asked for the shared rows&gt;=1 table in this JVM ({@link #rows1()}, {@link #rows1IfCached()},
     * {@link #of(int)}), whether or not it was loaded. For tests that prove a player never touches the 242 MB table
     * (compare the value before and after).
     */
    public static int rows1Requests() {
        return ROWS1_REQUESTS.get();
    }

    private static RaceTable shared(Region region, boolean allowBuild) {
        if (region.minRow == ROWS1.minRow) {
            ROWS1_REQUESTS.incrementAndGet();
        }
        // the two regions are told apart by their minimum row; "committed" only says where a file may live
        boolean rows2 = region.minRow == ROWS2.minRow;
        RaceTable t = rows2 ? sharedRows2 : sharedRows1;
        if (t != null) {
            return t;
        }
        synchronized (RaceTable.class) {
            t = rows2 ? sharedRows2 : sharedRows1;
            if (t == null) {
                t = loadOrBuild(region, allowBuild);
                if (t != null) {
                    if (rows2) {
                        sharedRows2 = t;
                    } else {
                        sharedRows1 = t;
                    }
                }
            }
            return t;
        }
    }

    /** Builds the rows&gt;=2 table in memory without touching the file system. */
    public static RaceTable build(int threads) {
        return build(ROWS2, threads, null);
    }

    /** Builds a region's table in memory without touching the file system. */
    public static RaceTable build(Region region, int threads, RetrogradeBuilder.Progress progress) {
        Ranking ranking = region.ranking();
        return new RaceTable(region, ranking, RetrogradeBuilder.build(ranking, LEAVE_GOAL, threads, progress));
    }

    public Region region() {
        return region;
    }

    /** The minimum row of the region (2 for the default table, 1 for the extended one). */
    public int minRow() {
        return region.minRow;
    }

    /** The header bytes of this table's file. */
    public byte[] header() {
        return TableFile.encodeHeader(LEAVE_GOAL, region.minRow, ranking, table);
    }

    /** The encoded file content of this table in one array (copies the body; fine for the small default table). */
    public byte[] encode() {
        return TableFile.encode(LEAVE_GOAL, region.minRow, ranking, table);
    }

    /** SHA-256 of this table's file content, computed without copying the body. */
    public String sha256Hex() {
        return TableFile.sha256Hex(header(), table);
    }

    public Ranking rankingOf() {
        return ranking;
    }

    /** The largest count in the table. */
    public int maxCount() {
        return maxCount;
    }

    /** Number of states, that is table entries. */
    public long states() {
        return table.length;
    }

    /** The number of states per count, index 0 .. {@link #maxCount}, plus the unreachable states at the end. */
    public long[] layerSizes() {
        long[] layers = new long[maxCount + 2];
        for (byte b : table) {
            int v = b & 0xFF;
            layers[v == RetrogradeBuilder.UNKNOWN ? maxCount + 1 : v]++;
        }
        return layers;
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

    static Path propertyDir() {
        String property = System.getProperty("sternhalma.tablebase.dir");
        return property == null ? null : Path.of(property);
    }

    private static RaceTable loadOrBuild(Region region, boolean allowBuild) {
        return resolve(region, propertyDir(), repositoryRoot(), allowBuild);
    }

    /** {@link #resolve(Region, Path, Path, boolean)} for the default region. */
    static RaceTable resolve(Path propertyDir, Path root, boolean allowBuild) {
        return resolve(ROWS2, propertyDir, root, allowBuild);
    }

    /**
     * The table from the first valid file among {@code propertyDir/<file>}, {@code root/tablebase/tables/<file>}
     * (committed regions only) and {@code root/tablebase/build/tables/<file>}; then, for a region with an archive, the
     * archive {@code <file>.zst} unpacked into the cache (see {@link Rows1Archive}); otherwise, if {@code allowBuild}, a
     * rebuilt table that is checked against the region's SHA-256 and cached (in {@code propertyDir}, else under
     * {@code root/tablebase/build/tables}, else in the temporary directory; without propertyDir and root that cache is
     * also searched when loading). Without {@code allowBuild} a missing table gives null.
     */
    static RaceTable resolve(Region region, Path propertyDir, Path root, boolean allowBuild) {
        try {
            Path[] candidates = {
                propertyDir == null ? null : propertyDir.resolve(region.fileName),
                root == null || !region.committed ? null
                        : root.resolve("tablebase").resolve("tables").resolve(region.fileName),
                root == null ? null : root.resolve("tablebase").resolve("build").resolve("tables").resolve(region.fileName),
                propertyDir == null && root == null
                        ? Path.of(System.getProperty("java.io.tmpdir")).resolve("sternhalma-tablebase").resolve(region.fileName) : null,
            };
            for (Path file : candidates) {
                RaceTable loaded = tryLoad(region, file);
                if (loaded != null) {
                    return loaded;
                }
            }
            Path cache = propertyDir != null ? propertyDir
                    : root != null ? root.resolve("tablebase").resolve("build").resolve("tables")
                    : Path.of(System.getProperty("java.io.tmpdir")).resolve("sternhalma-tablebase");
            RaceTable unpacked = fromArchive(region, propertyDir, root, cache);
            if (unpacked != null) {
                return unpacked;
            }
            if (!allowBuild) {
                return null;
            }
            int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
            RaceTable built = buildChecked(region, threads, cache, region.ranking().count() >= VERBOSE_BUILD_STATES);
            try {
                TableFile.write(cache.resolve(region.fileName), built.header(), built.table);
            } catch (IOException e) {
                System.err.println("[tablebase] could not cache the table: " + e);
            }
            return built;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The table from the region's archive ({@code <file>.zst} in {@code propertyDir} or in
     * {@code root/tablebase/tables}), unpacked into {@code cache} with {@link Rows1Archive}; null if the region has no
     * archive, no real archive file is there (absent or a Git LFS pointer) or the unpack failed. Every failure but
     * an absent file prints one line that says why, because the caller then rebuilds (several minutes) or gives up.
     */
    private static RaceTable fromArchive(Region region, Path propertyDir, Path root, Path cache) throws IOException {
        if (!region.archived) {
            return null;
        }
        String archiveName = region.fileName + ARCHIVE_SUFFIX;
        Path[] archives = {
            propertyDir == null ? null : propertyDir.resolve(archiveName),
            root == null ? null : root.resolve("tablebase").resolve("tables").resolve(archiveName),
        };
        for (Path archive : archives) {
            if (archive == null || !Files.isRegularFile(archive)) {
                continue;
            }
            if (TableFile.isLfsPointer(archive)) {
                System.err.printf(Locale.ROOT, "[tablebase] %s is a Git LFS pointer, not an archive; not unpacked%n", archive);
                continue;
            }
            try {
                Path target = cache.resolve(region.fileName);
                Rows1Archive.Outcome outcome = Rows1Archive.unpack(archive, target, region.sha256);
                if (outcome == Rows1Archive.Outcome.UNPACKED) {
                    System.err.printf(Locale.ROOT, "[tablebase] unpacked %s into %s%n", archive, target);
                }
                RaceTable loaded = tryLoad(region, target);
                if (loaded != null) {
                    return loaded;
                }
            } catch (IOException e) {
                System.err.printf(Locale.ROOT, "[tablebase] ignoring the archive %s: %s%n", archive, e.getMessage());
            }
        }
        return null;
    }

    /**
     * Builds a region's table, logs the time, and checks the result against the region's recorded SHA-256.
     *
     * @throws IllegalStateException if the checksum differs (the builder or the format changed)
     */
    static RaceTable buildChecked(Region region, int threads, Path cache, boolean verbose) throws IOException {
        TableFile.cleanTemporaries(cache.resolve(region.fileName));
        System.err.printf(Locale.ROOT, "[tablebase] no valid %s found; building %d states (%d threads) into %s%n",
                region.fileName, region.ranking().count(), threads, cache);
        long t0 = System.nanoTime();
        RetrogradeBuilder.Progress progress = verbose ? (layer, states, elapsed) -> System.err.printf(Locale.ROOT,
                "[tablebase] %s layer %d: %d states, %.1f s elapsed%n", region.fileName, layer, states, elapsed / 1e9)
                : null;
        RaceTable built = build(region, threads, progress);
        String sha = built.sha256Hex();
        System.err.printf(Locale.ROOT, "[tablebase] built %s in %.1f s, sha256 %s%n", region.fileName,
                (System.nanoTime() - t0) / 1e9, sha);
        if (!sha.equals(region.sha256)) {
            throw new IllegalStateException("the rebuilt " + region.fileName + " has sha256 " + sha
                    + ", but the recorded constant is " + region.sha256
                    + ": the builder or the format changed without re-recording the constant");
        }
        return built;
    }

    /** {@link #tryLoad(Region, Path)} for the default region. */
    static RaceTable tryLoad(Path file) throws IOException {
        return tryLoad(ROWS2, file);
    }

    /** The table in {@code file} if it is a real, matching file; null if absent, a pointer, stale or corrupt. */
    static RaceTable tryLoad(Region region, Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        if (TableFile.isLfsPointer(file)) {
            String oid = TableFile.lfsPointerOid(file);
            System.err.printf(Locale.ROOT, "[tablebase] %s is a Git LFS pointer (oid %s)%s%n", file, oid,
                    region.sha256.equals(oid) ? "" : "; its oid differs from the recorded SHA-256, the committed table is stale");
            return null;
        }
        String sha = TableFile.sha256Hex(file);
        if (!sha.equals(region.sha256)) {
            System.err.printf(Locale.ROOT, "[tablebase] %s has sha256 %s, expected %s; ignored%n", file, sha, region.sha256);
            return null;
        }
        TableFile.Content content = TableFile.read(file);
        Ranking ranking = region.ranking();
        TableFile.Header h = content.header();
        if (h.leaveGoal() != LEAVE_GOAL || h.minRow() != region.minRow || h.pieces() != Position.PIECES
                || !java.util.Arrays.equals(h.cells(), ranking.cells()) || h.states() != ranking.count()) {
            System.err.printf(Locale.ROOT, "[tablebase] %s describes another region; ignored%n", file);
            return null;
        }
        return new RaceTable(region, ranking, content.body());
    }

    /**
     * Builds a table and writes it to {@code <directory>/<file>}. Arguments: the directory (default
     * {@code tablebase/tables}), {@code --rows N} (default 2), {@code --threads T} (default half the processors).
     * An uncommitted region (rows &gt;= 1) is refused for the committed directory {@code tablebase/tables}.
     */
    public static void main(String[] args) throws IOException {
        Path dir = Path.of("tablebase/tables");
        int minRow = MIN_ROW;
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--rows" -> minRow = Integer.parseInt(args[++i]);
                case "--threads" -> threads = Integer.parseInt(args[++i]);
                default -> dir = Path.of(args[i]);
            }
        }
        Region region = region(minRow);
        Path absolute = dir.toAbsolutePath().normalize();
        if (!region.committed && absolute.endsWith(Path.of("tablebase", "tables"))) {
            System.err.println("refusing to write the uncommitted " + region.fileName + " into " + absolute
                    + ": that directory is tracked with Git LFS; use tablebase/build/tables or -Dsternhalma.tablebase.dir");
            System.exit(2);
        }
        Path file = dir.resolve(region.fileName);
        TableFile.cleanTemporaries(file);
        long t0 = System.nanoTime();
        RaceTable built = build(region, threads, (layer, states, elapsed) -> System.err.printf(Locale.ROOT,
                "layer %d: %d states, %.1f s elapsed%n", layer, states, elapsed / 1e9));
        long buildNanos = System.nanoTime() - t0;
        byte[] header = built.header();
        String sha = TableFile.sha256Hex(header, built.table);
        TableFile.write(file, header, built.table);
        long[] layers = built.layerSizes();
        long unknown = layers[layers.length - 1];
        System.out.printf(Locale.ROOT,
                "wrote %s: %d states, %d bytes, max count %d, unreachable %d, built in %.1f s on %d threads (%.0f states/s), %.1f s total%n",
                file, built.table.length, header.length + built.table.length, built.maxCount(), unknown, buildNanos / 1e9,
                threads, built.table.length / (buildNanos / 1e9), (System.nanoTime() - t0) / 1e9);
        System.out.println("layers " + java.util.Arrays.toString(java.util.Arrays.copyOf(layers, layers.length - 1)));
        System.out.println("sha256 " + sha + (sha.equals(region.sha256) ? " (matches the recorded constant)"
                : " (DIFFERS from the recorded constant " + region.sha256 + "; re-record it with a reason)"));
    }
}
