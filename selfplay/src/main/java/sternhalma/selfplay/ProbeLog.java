package sternhalma.selfplay;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The file format of the rank logger ({@link RankProbe}): one gzip stream per shard. The first line is the manifest,
 * {@code RLOG1 key=value key=value ...} ending in a newline; a space, newline, carriage return or percent sign in a value is written
 * as %20, %0A, %0D or %25. After it come fixed-size
 * records of {@value #RECORD_BYTES} bytes, big endian:
 *
 * <pre>
 *   int   game      the game id (item number of the job)
 *   short ply       the game ply of the root of the search that made the lookup (race play: the position itself)
 *   byte  role      0 leaf evaluator, 1 race play (LookupObserver.LEAF, RACEPLAY)
 *   byte  k         stones of the looking-up side in row 1 (0..8)
 *   int   rank      rank in the rows&gt;=1 ranking; block = rank &gt;&gt;&gt; 16
 *   short rows1     the rows&gt;=1 entry, 255 = unreachable
 *   short rows2     the rows&gt;=2 count, 255 = not covered by that table (a stone in row 1)
 *   short estimate  the plain push estimate, 255 = none
 *   int   count     how often this (game, ply, role, rank) was looked up
 * </pre>
 *
 * Lookups with the same (game, ply, role, rank) are merged into one record with a count, so the sum of the counts is the
 * number of table reads and the number of records is smaller. The manifest carries {@code rows} (records), {@code
 * lookups} (sum of counts), {@code games}, {@code first} (first game id) and {@code plies}; a reader checks them against
 * the content, so a truncated or edited file is not taken for a complete one.
 */
final class ProbeLog {

    static final String MAGIC = "RLOG1";
    static final int RECORD_BYTES = 4 + 2 + 1 + 1 + 4 + 2 + 2 + 2 + 4;

    private ProbeLog() {
    }

    /** One record. */
    record Row(int game, int ply, int role, int k, int rank, int rows1, int rows2, int estimate, int count) {
        int block() {
            return rank >>> 16;
        }
    }

    /** The records of one game, packed, and the number of plies the game had. */
    record GameRows(int game, int plies, int rows, long lookups, byte[] packed) {
    }

    /** A shard that was read: manifest and the rows. */
    record Shard(Map<String, String> manifest, byte[] packed, int rows) {
        void forEach(Consumer<Row> sink) {
            ByteBuffer b = ByteBuffer.wrap(packed);
            for (int i = 0; i < rows; i++) {
                sink.accept(new Row(b.getInt(), b.getShort(), b.get(), b.get(), b.getInt(), b.getShort(), b.getShort(),
                        b.getShort(), b.getInt()));
            }
        }
    }

    /** Appends a record to a buffer of {@link #RECORD_BYTES} per row. */
    static void put(ByteBuffer b, Row r) {
        b.putInt(r.game()).putShort((short) r.ply()).put((byte) r.role()).put((byte) r.k()).putInt(r.rank())
                .putShort((short) r.rows1()).putShort((short) r.rows2()).putShort((short) r.estimate()).putInt(r.count());
    }

    /** Percent-encodes what would break the manifest line: space, newline, carriage return and the percent sign itself. */
    static String escape(String v) {
        StringBuilder sb = new StringBuilder(v.length());
        for (char c : v.toCharArray()) {
            switch (c) {
                case '%' -> sb.append("%25");
                case ' ' -> sb.append("%20");
                case '\n' -> sb.append("%0A");
                case '\r' -> sb.append("%0D");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    static String unescape(String v) {
        StringBuilder sb = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            String code = c == '%' && i + 3 <= v.length() ? v.substring(i, i + 3) : "";
            switch (code) {
                case "%25" -> sb.append('%');
                case "%20" -> sb.append(' ');
                case "%0A" -> sb.append('\n');
                case "%0D" -> sb.append('\r');
                default -> {
                    sb.append(c);
                    continue;
                }
            }
            i += 2;
        }
        return sb.toString();
    }

    /** Writes a shard under a temporary name and renames it when it is complete. */
    static void write(Path file, Map<String, String> settings, int shard, int first, java.util.List<GameRows> games)
            throws IOException {
        long rows = 0;
        long lookups = 0;
        long plies = 0;
        for (GameRows g : games) {
            rows += g.rows();
            lookups += g.lookups();
            plies += g.plies();
        }
        Map<String, String> m = new LinkedHashMap<>(settings);
        m.put("shard", Integer.toString(shard));
        m.put("first", Integer.toString(first));
        m.put("games", Integer.toString(games.size()));
        m.put("plies", Long.toString(plies));
        m.put("rows", Long.toString(rows));
        m.put("lookups", Long.toString(lookups));
        StringBuilder head = new StringBuilder(MAGIC);
        m.forEach((k, v) -> head.append(' ').append(k).append('=').append(escape(v)));
        head.append('\n');
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp." + ProcessHandle.current().pid());
        try (OutputStream out = new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(tmp), 1 << 16))) {
            out.write(head.toString().getBytes(StandardCharsets.UTF_8));
            for (GameRows g : games) {
                out.write(g.packed(), 0, g.rows() * RECORD_BYTES);
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Reads and validates a shard.
     *
     * @throws IOException if the file is truncated, has no manifest, or disagrees with its manifest
     */
    static Shard read(Path file) throws IOException {
        try (InputStream in = new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file), 1 << 16))) {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int c;
            while ((c = in.read()) >= 0 && c != '\n') {
                line.write(c);
            }
            if (c < 0) {
                throw new IOException(file + ": no manifest line");
            }
            String[] words = line.toString(StandardCharsets.UTF_8).split(" ");
            if (!words[0].equals(MAGIC)) {
                throw new IOException(file + ": not a rank log (first word " + words[0] + ")");
            }
            Map<String, String> m = new LinkedHashMap<>();
            for (int i = 1; i < words.length; i++) {
                String[] kv = words[i].split("=", 2);
                if (kv.length != 2) {
                    throw new IOException(file + ": bad manifest word " + words[i]);
                }
                m.put(kv[0], unescape(kv[1]));
            }
            byte[] body = in.readAllBytes();
            int rows = Integer.parseInt(m.getOrDefault("rows", "-1"));
            if (rows < 0 || (long) rows * RECORD_BYTES != body.length) {
                throw new IOException(file + ": manifest says " + rows + " rows, the body has " + body.length + " bytes");
            }
            Shard s = new Shard(m, body, rows);
            long[] sum = {0};
            s.forEach(r -> sum[0] += r.count());
            if (sum[0] != Long.parseLong(m.getOrDefault("lookups", "-1"))) {
                throw new IOException(file + ": manifest says " + m.get("lookups") + " lookups, the rows add up to " + sum[0]);
            }
            return s;
        } catch (NumberFormatException | java.nio.BufferUnderflowException e) {
            throw new IOException(file + ": unreadable: " + e, e);
        }
    }
}
