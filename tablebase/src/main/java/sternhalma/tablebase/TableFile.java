package sternhalma.tablebase;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

import sternhalma.engine.RuleSet.LeaveGoal;

/**
 * The file format of a race table. Everything is deterministic (no timestamps), so the same table always gives
 * the same bytes and the file's SHA-256 identifies its content, which is also what Git LFS stores as the object id.
 *
 * <pre>
 *   magic "SHRT"          4 bytes
 *   format version        int (1)
 *   leaveGoal             int (0 FORBIDDEN, 1 ALLOWED)
 *   minRow                int (the region: allowed cells of player 0 with row >= minRow)
 *   pieces                int
 *   cell count            int, then the cells as ints (ascending)
 *   state count           long
 *   body SHA-256          32 bytes
 *   body                  state count bytes, one distance per rank ({@link RetrogradeBuilder#UNKNOWN} = 255)
 * </pre>
 *
 * <p>Header and body are handled as separate arrays, so that a 254 MB table is never copied into a second array
 * just to hash or write it.
 */
public final class TableFile {

    static final byte[] MAGIC = "SHRT".getBytes(StandardCharsets.US_ASCII);
    static final int VERSION = 1;

    /** A Git LFS pointer file starts with this line; a clone without LFS content has only the pointer. */
    static final String LFS_POINTER_PREFIX = "version https://git-lfs.github.com/spec/v1";

    private static final int BUFFER = 1 << 20;

    /** The header of a table file. */
    public record Header(LeaveGoal leaveGoal, int minRow, int pieces, int[] cells, long states, byte[] bodySha256) {

        public Header {
            cells = cells.clone();
            bodySha256 = bodySha256.clone();
        }

        public String bodySha256Hex() {
            return HexFormat.of().formatHex(bodySha256);
        }
    }

    /** A table file read into memory. */
    public record Content(Header header, byte[] body) {
    }

    private TableFile() {
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] sha256(byte[] data) {
        return sha256().digest(data);
    }

    /** SHA-256 of the concatenation of {@code parts} as lower-case hex. */
    public static String sha256Hex(byte[]... parts) {
        MessageDigest digest = sha256();
        for (byte[] part : parts) {
            digest.update(part);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** SHA-256 of a whole file as lower-case hex, streamed. */
    public static String sha256Hex(Path file) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[BUFFER];
            int n;
            while ((n = in.read(buffer)) > 0) {
                digest.update(buffer, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** The header bytes for a body (hashes the body once). */
    public static byte[] encodeHeader(LeaveGoal leaveGoal, int minRow, Ranking ranking, byte[] body) {
        if (body.length != ranking.count()) {
            throw new IllegalArgumentException("body has " + body.length + " entries, region " + ranking.count());
        }
        int[] cells = ranking.cells();
        ByteBuffer header = ByteBuffer.allocate(4 + 4 * 5 + 4 * cells.length + 8 + 32);
        header.put(MAGIC);
        header.putInt(VERSION);
        header.putInt(leaveGoal.ordinal());
        header.putInt(minRow);
        header.putInt(ranking.pieces());
        header.putInt(cells.length);
        for (int c : cells) {
            header.putInt(c);
        }
        header.putLong(body.length);
        header.put(sha256(body));
        return Arrays.copyOf(header.array(), header.position());
    }

    /** Serialises header and body into one array (for small tables; large ones use the header and body apart). */
    public static byte[] encode(LeaveGoal leaveGoal, int minRow, Ranking ranking, byte[] body) {
        byte[] header = encodeHeader(leaveGoal, minRow, ranking, body);
        byte[] out = new byte[header.length + body.length];
        System.arraycopy(header, 0, out, 0, header.length);
        System.arraycopy(body, 0, out, header.length, body.length);
        return out;
    }

    /**
     * Writes the concatenation of {@code parts} atomically: to a temporary name in the same directory, then renamed,
     * so that only a complete file ever carries the final name.
     */
    public static void write(Path file, byte[]... parts) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp." + ProcessHandle.current().pid());
        try {
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(tmp), BUFFER)) {
                for (byte[] part : parts) {
                    out.write(part);
                }
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Deletes temporary files of interrupted earlier writes of {@code file}; returns how many. */
    public static int cleanTemporaries(Path file) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        if (dir == null || !Files.isDirectory(dir)) {
            return 0;
        }
        String prefix = file.getFileName() + ".tmp.";
        int removed = 0;
        try (var stream = Files.list(dir)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (p.getFileName().toString().startsWith(prefix) && Files.deleteIfExists(p)) {
                    removed++;
                }
            }
        }
        return removed;
    }

    /** Whether the file is a Git LFS pointer instead of a table (a clone without the LFS content). */
    public static boolean isLfsPointer(Path file) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) > 1024) {
            return false;
        }
        // ISO-8859-1 never fails: a small binary file (a truncated archive) is simply not a pointer
        String text = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
        return text.startsWith(LFS_POINTER_PREFIX);
    }

    /** The object id (SHA-256 hex of the real file) that a Git LFS pointer names, or null if absent. */
    public static String lfsPointerOid(Path file) throws IOException {
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.startsWith("oid sha256:")) {
                return line.substring("oid sha256:".length()).trim();
            }
        }
        return null;
    }

    /**
     * Reads a table file and checks its magic, version and body checksum.
     *
     * @throws IOException if the file is not a valid table file of this version
     */
    public static Content read(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            Content content = readUnchecked(in, file.toString());
            if (!Arrays.equals(sha256(content.body()), content.header().bodySha256())) {
                throw new IOException(file + " body checksum mismatch");
            }
            return content;
        }
    }

    /** Like {@link #read(Path)} from a stream, without the body checksum (the caller checks it, e.g. the browser with crypto.subtle). */
    public static Content readUnchecked(InputStream in, String file) throws IOException {
        try (DataInputStream data = new DataInputStream(in)) {
            byte[] magic = new byte[MAGIC.length];
            data.readFully(magic);
            if (!Arrays.equals(magic, MAGIC)) {
                throw new IOException(file + " is not a race table (bad magic)");
            }
            int version = data.readInt();
            if (version != VERSION) {
                throw new IOException(file + " has format version " + version + ", expected " + VERSION);
            }
            int leaveGoal = data.readInt();
            int minRow = data.readInt();
            int pieces = data.readInt();
            int cellCount = data.readInt();
            if (leaveGoal < 0 || leaveGoal >= LeaveGoal.values().length || pieces <= 0 || cellCount <= 0
                    || cellCount > sternhalma.engine.Geometry.CELLS) {
                throw new IOException(file + " has an implausible header");
            }
            int[] cells = new int[cellCount];
            for (int i = 0; i < cellCount; i++) {
                cells[i] = data.readInt();
            }
            long states = data.readLong();
            if (states <= 0 || states > Integer.MAX_VALUE) {
                throw new IOException(file + " has an implausible state count " + states);
            }
            byte[] sha = new byte[32];
            data.readFully(sha);
            byte[] body = new byte[(int) states];
            data.readFully(body);
            if (data.read() != -1) {
                throw new IOException(file + " has trailing bytes");
            }
            return new Content(new Header(LeaveGoal.values()[leaveGoal], minRow, pieces, cells, states, sha), body);
        }
    }
}
