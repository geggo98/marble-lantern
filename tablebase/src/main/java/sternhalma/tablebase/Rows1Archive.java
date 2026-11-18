package sternhalma.tablebase;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;

/**
 * Unpacks a table archive ({@code rows1.bin.zst}, Git LFS, made by {@code scripts/tables/pack-rows1.zsh}) into the
 * cache with the {@code zstd} command line tool.
 *
 * <p>Why the CLI and not a Java library: no new dependency, no native library and no new verification metadata, and
 * the devenv shell provides {@code zstd}. Outside it, without {@code zstd} on the {@code PATH}, {@link #unpack}
 * throws and the caller falls back to rebuilding the table.
 *
 * <p>The unpack is atomic and checked: the CLI writes to {@code <target>.tmp.<pid>}, the result must have the
 * expected SHA-256, and only then is it renamed to {@code target}. A truncated or corrupt archive therefore gives an
 * {@link IOException} with the reason and leaves no file under the final name and no temporary file behind. A
 * second call with the target in place and correct is a no-op ({@link Outcome#ALREADY_PRESENT}). Unpacks into the
 * same cache are serialized by an exclusive lock on {@code <target>.lock} (the script
 * {@code scripts/tables/unpack-rows1.zsh} takes the same lock), so one process never deletes the live temporary file
 * of another.
 */
public final class Rows1Archive {

    /** What {@link #unpack} did. */
    public enum Outcome {
        /** The target was written by this call. */
        UNPACKED,
        /** The target existed with the expected SHA-256; nothing was written. */
        ALREADY_PRESENT
    }

    private Rows1Archive() {
    }

    /**
     * Whether {@code archive} is a real file that may be an archive: it exists and is not a Git LFS pointer (a clone
     * without LFS content). It does not look inside the file.
     */
    public static boolean isUsable(Path archive) throws IOException {
        return archive != null && Files.isRegularFile(archive) && !TableFile.isLfsPointer(archive);
    }

    /**
     * Decompresses {@code archive} to {@code target}, unless {@code target} is already a file with
     * {@code expectedSha256}.
     *
     * @return what happened
     * @throws IOException if the archive is a pointer, {@code zstd} is missing or fails (truncated, corrupt), or the
     *                     result has another SHA-256; no file named {@code target} is written then, and a wrong
     *                     existing {@code target} is left as it was
     */
    public static Outcome unpack(Path archive, Path target, String expectedSha256) throws IOException {
        if (Files.isRegularFile(target) && TableFile.sha256Hex(target).equals(expectedSha256)) {
            return Outcome.ALREADY_PRESENT;
        }
        if (!Files.isRegularFile(archive)) {
            throw new IOException(archive + " does not exist");
        }
        if (TableFile.isLfsPointer(archive)) {
            throw new IOException(archive + " is a Git LFS pointer, not the archive (clone with LFS content)");
        }
        Files.createDirectories(target.toAbsolutePath().getParent());
        // FileLock excludes other processes only; threads of this JVM would get OverlappingFileLockException.
        synchronized (Rows1Archive.class) {
            return unpackExclusive(archive, target, expectedSha256);
        }
    }

    private static Outcome unpackExclusive(Path archive, Path target, String expectedSha256) throws IOException {
        Path lockFile = target.resolveSibling(target.getFileName() + ".lock");
        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock = channel.lock();
            // Under the lock no other process unpacks: a temporary file found now is stale, not live.
            try {
                if (Files.isRegularFile(target) && TableFile.sha256Hex(target).equals(expectedSha256)) {
                    return Outcome.ALREADY_PRESENT;
                }
                return unpackLocked(archive, target, expectedSha256);
            } finally {
                lock.release();
            }
        }
    }

    private static Outcome unpackLocked(Path archive, Path target, String expectedSha256) throws IOException {
        TableFile.cleanTemporaries(target);
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp." + ProcessHandle.current().pid());
        Path log = target.resolveSibling(target.getFileName() + ".tmp." + ProcessHandle.current().pid() + ".log");
        try {
            Process process;
            try {
                process = new ProcessBuilder(List.of("zstd", "-d", "-q", "-f", "-o", tmp.toString(), "--",
                        archive.toString()))
                        .redirectErrorStream(true)
                        .redirectOutput(log.toFile())
                        .start();
            } catch (IOException e) {
                throw new IOException("cannot run the zstd command line tool (is it on the PATH?): " + e.getMessage(), e);
            }
            int exit;
            try {
                exit = process.waitFor();
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while unpacking " + archive, e);
            }
            if (exit != 0) {
                String message = Files.exists(log) ? Files.readString(log, StandardCharsets.UTF_8).strip() : "";
                throw new IOException(String.format(Locale.ROOT, "zstd could not unpack %s (exit %d): %s", archive, exit,
                        message.isEmpty() ? "no message" : message));
            }
            String sha = TableFile.sha256Hex(tmp);
            if (!sha.equals(expectedSha256)) {
                throw new IOException(archive + " unpacks to sha256 " + sha + ", expected " + expectedSha256);
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return Outcome.UNPACKED;
        } finally {
            Files.deleteIfExists(tmp);
            Files.deleteIfExists(log);
        }
    }
}
