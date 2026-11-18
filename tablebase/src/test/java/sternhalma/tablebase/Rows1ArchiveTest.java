package sternhalma.tablebase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sternhalma.engine.RuleSet.LeaveGoal;

/**
 * {@link Rows1Archive} and the archive step of {@link RaceTable#resolve}, with a small synthetic table (region
 * rows&gt;=4) instead of the 242 MB one. The tests need the {@code zstd} command line tool and are skipped with a
 * message when it is missing (the devenv shell and CI provide it).
 */
class Rows1ArchiveTest {

    private static boolean zstd;

    @BeforeAll
    static void findZstd() {
        try {
            zstd = new ProcessBuilder("zstd", "--version").redirectErrorStream(true).start().waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            zstd = false;
        }
    }

    private static void needZstd() {
        assumeTrue(zstd, "the zstd command line tool is not on the PATH; run inside devenv shell");
    }

    private record Fixture(byte[] file, String sha, RaceTable.Region region) {
    }

    private static Fixture fixture() {
        Ranking ranking = new Ranking(Rows.regionCells(4), 10);
        byte[] body = RetrogradeBuilder.build(ranking, LeaveGoal.FORBIDDEN, 1);
        byte[] file = TableFile.encode(LeaveGoal.FORBIDDEN, 4, ranking, body);
        String sha = TableFile.sha256Hex(file);
        return new Fixture(file, sha, new RaceTable.Region(4, "rows4.bin", sha, true, true));
    }

    /** Writes {@code content} and compresses it with the CLI to {@code archive}. */
    private static void compress(byte[] content, Path archive) throws IOException, InterruptedException {
        Path raw = archive.resolveSibling("raw.input");
        Files.createDirectories(archive.getParent());
        Files.write(raw, content);
        int exit = new ProcessBuilder("zstd", "-q", "-f", "-o", archive.toString(), "--", raw.toString())
                .inheritIO().start().waitFor();
        assertEquals(0, exit, "zstd must compress the fixture");
        Files.delete(raw);
    }

    /** File names in {@code dir} without the lock files, which stay by design. */
    private static List<String> names(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).filter(n -> !n.endsWith(".lock")).sorted().toList();
        }
    }

    @Test
    void aCorrectArchiveIsUnpackedAtomicallyAndASecondUnpackIsASkip(@TempDir Path dir) throws Exception {
        needZstd();
        Fixture f = fixture();
        Path archive = dir.resolve("in").resolve("rows4.bin.zst");
        compress(f.file, archive);
        Path target = dir.resolve("cache").resolve("rows4.bin");

        assertEquals(Rows1Archive.Outcome.UNPACKED, Rows1Archive.unpack(archive, target, f.sha));
        assertEquals(f.sha, TableFile.sha256Hex(target));
        assertEquals(List.of("rows4.bin"), names(target.getParent()), "no temporary or log file is left behind");

        long modified = Files.getLastModifiedTime(target).toMillis();
        Files.setLastModifiedTime(target, java.nio.file.attribute.FileTime.fromMillis(modified - 60_000));
        long before = Files.getLastModifiedTime(target).toMillis();
        assertEquals(Rows1Archive.Outcome.ALREADY_PRESENT, Rows1Archive.unpack(archive, target, f.sha));
        assertEquals(before, Files.getLastModifiedTime(target).toMillis(), "the second unpack does not write");
    }

    @Test
    void twoUnpacksIntoOneCacheAtTheSameTimeBothSucceedAndLeaveOneCorrectFile(@TempDir Path dir) throws Exception {
        needZstd();
        Fixture f = fixture();
        Path archive = dir.resolve("in").resolve("rows4.bin.zst");
        compress(f.file, archive);
        Path target = dir.resolve("cache").resolve("rows4.bin");
        for (int round = 0; round < 5; round++) {
            Files.deleteIfExists(target);
            java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                java.util.concurrent.Callable<Rows1Archive.Outcome> job = () -> {
                    go.await();
                    return Rows1Archive.unpack(archive, target, f.sha);
                };
                var a = pool.submit(job);
                var b = pool.submit(job);
                go.countDown();
                List<Rows1Archive.Outcome> outcomes = List.of(a.get(), b.get());
                assertTrue(outcomes.contains(Rows1Archive.Outcome.UNPACKED), "one call unpacks: " + outcomes);
                assertEquals(f.sha, TableFile.sha256Hex(target));
                assertEquals(List.of("rows4.bin"), names(target.getParent()), "no temporary file is left");
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void aTruncatedArchiveGivesAClearErrorAndLeavesNothingInTheCache(@TempDir Path dir) throws Exception {
        needZstd();
        Fixture f = fixture();
        Path archive = dir.resolve("in").resolve("rows4.bin.zst");
        compress(f.file, archive);
        byte[] bytes = Files.readAllBytes(archive);
        assertTrue(bytes.length > 100, "the fixture compresses to more than a few bytes");
        Files.write(archive, java.util.Arrays.copyOf(bytes, bytes.length / 2));
        Path target = dir.resolve("cache").resolve("rows4.bin");

        IOException e = assertThrows(IOException.class, () -> Rows1Archive.unpack(archive, target, f.sha));
        assertTrue(e.getMessage().contains("zstd could not unpack"), e.getMessage());
        assertTrue(e.getMessage().contains(archive.toString()), "the message names the archive: " + e.getMessage());
        assertEquals(List.of(), names(target.getParent()), "no final file and no temporary file");
    }

    @Test
    void anArchiveThatUnpacksToTheWrongContentIsRefusedAndAWrongExistingTargetIsKept(@TempDir Path dir) throws Exception {
        needZstd();
        Fixture f = fixture();
        Path archive = dir.resolve("in").resolve("rows4.bin.zst");
        compress(f.file, archive);
        Path target = dir.resolve("cache").resolve("rows4.bin");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "stale");
        String wrong = "00" + f.sha.substring(2);

        IOException e = assertThrows(IOException.class, () -> Rows1Archive.unpack(archive, target, wrong));
        assertTrue(e.getMessage().contains("expected " + wrong), e.getMessage());
        assertEquals("stale", Files.readString(target), "the existing file is left as it was");
        assertEquals(List.of("rows4.bin"), names(target.getParent()));
    }

    @Test
    void aPointerArchiveIsNotUsableAndUnpackSaysSo(@TempDir Path dir) throws Exception {
        Path archive = dir.resolve("rows4.bin.zst");
        Files.writeString(archive, "version https://git-lfs.github.com/spec/v1\noid sha256:" + "ab".repeat(32)
                + "\nsize 21216784\n");
        assertFalse(Rows1Archive.isUsable(archive));
        assertFalse(Rows1Archive.isUsable(dir.resolve("missing.zst")));
        Path target = dir.resolve("cache").resolve("rows4.bin");
        IOException e = assertThrows(IOException.class, () -> Rows1Archive.unpack(archive, target, "00".repeat(32)));
        assertTrue(e.getMessage().contains("Git LFS pointer"), e.getMessage());
        assertFalse(Files.exists(target));
    }

    @Test
    void resolveIgnoresAPointerArchiveAndDoesNotBuildWithoutPermission(@TempDir Path root) throws Exception {
        Fixture f = fixture();
        Files.writeString(root.resolve("settings.gradle.kts"), "// fake root\n");
        Path tables = Files.createDirectories(root.resolve("tablebase").resolve("tables"));
        Files.writeString(tables.resolve("rows4.bin.zst"), "version https://git-lfs.github.com/spec/v1\noid sha256:"
                + "ab".repeat(32) + "\nsize 21216784\n");
        assertNull(RaceTable.resolve(f.region, null, root, false), "a pointer archive gives no table");
        assertFalse(Files.exists(root.resolve("tablebase").resolve("build").resolve("tables").resolve("rows4.bin")));
    }

    @Test
    void resolveUnpacksARealArchiveIntoTheCacheAndLoadsIt(@TempDir Path root) throws Exception {
        needZstd();
        Fixture f = fixture();
        Files.writeString(root.resolve("settings.gradle.kts"), "// fake root\n");
        compress(f.file, root.resolve("tablebase").resolve("tables").resolve("rows4.bin.zst"));
        Path cacheFile = root.resolve("tablebase").resolve("build").resolve("tables").resolve("rows4.bin");

        RaceTable table = RaceTable.resolve(f.region, null, root, false);
        assertNotNull(table, "the archive is unpacked even without allowBuild; nothing is built");
        assertEquals(4, table.minRow());
        assertEquals(f.sha, TableFile.sha256Hex(cacheFile));
    }

    @Test
    void resolveFallsBackWhenTheArchiveIsTruncatedAndLeavesTheCacheClean(@TempDir Path root) throws Exception {
        needZstd();
        Fixture f = fixture();
        Files.writeString(root.resolve("settings.gradle.kts"), "// fake root\n");
        Path archive = root.resolve("tablebase").resolve("tables").resolve("rows4.bin.zst");
        compress(f.file, archive);
        byte[] bytes = Files.readAllBytes(archive);
        Files.write(archive, java.util.Arrays.copyOf(bytes, bytes.length / 2));

        assertNull(RaceTable.resolve(f.region, null, root, false));
        Path cache = root.resolve("tablebase").resolve("build").resolve("tables");
        assertEquals(List.of(), Files.isDirectory(cache) ? names(cache) : List.of(), "nothing in the cache");
    }

    @Test
    void aRegionWithoutArchiveFlagNeverLooksAtAnArchive(@TempDir Path root) throws Exception {
        needZstd();
        Fixture f = fixture();
        Files.writeString(root.resolve("settings.gradle.kts"), "// fake root\n");
        compress(f.file, root.resolve("tablebase").resolve("tables").resolve("rows4.bin.zst"));
        RaceTable.Region plain = new RaceTable.Region(4, "rows4.bin", f.sha, true);
        assertFalse(plain.archived());
        assertNull(RaceTable.resolve(plain, null, root, false));
    }
}
