package sternhalma.tablebase;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
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
 */
public final class TableFile {

    static final byte[] MAGIC = "SHRT".getBytes(StandardCharsets.US_ASCII);
    static final int VERSION = 1;

    /** A Git LFS pointer file starts with this line; a clone without LFS content has only the pointer. */
    static final String LFS_POINTER_PREFIX = "version https://git-lfs.github.com/spec/v1";

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

    static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** SHA-256 of a whole file as lower-case hex. */
    public static String sha256Hex(Path file) throws IOException {
        return HexFormat.of().formatHex(sha256(Files.readAllBytes(file)));
    }

    /** Serialises header and body. */
    public static byte[] encode(LeaveGoal leaveGoal, int minRow, Ranking ranking, byte[] body) {
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
        byte[] out = new byte[header.position() + body.length];
        System.arraycopy(header.array(), 0, out, 0, header.position());
        System.arraycopy(body, 0, out, header.position(), body.length);
        return out;
    }

    /** Writes the file atomically: to a temporary name in the same directory, then renamed. */
    public static void write(Path file, byte[] encoded) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp." + ProcessHandle.current().pid());
        try {
            Files.write(tmp, encoded);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Whether the file is a Git LFS pointer instead of a table (a clone without the LFS content). */
    public static boolean isLfsPointer(Path file) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) > 1024) {
            return false;
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
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
        try (InputStream in = Files.newInputStream(file); DataInputStream data = new DataInputStream(in)) {
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
            if (!Arrays.equals(sha256(body), sha)) {
                throw new IOException(file + " body checksum mismatch");
            }
            return new Content(new Header(LeaveGoal.values()[leaveGoal], minRow, pieces, cells, states, sha), body);
        }
    }
}
