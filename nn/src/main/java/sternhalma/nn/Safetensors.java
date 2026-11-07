package sternhalma.nn;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Reader and writer for safetensors files with dtype F32 only (docs/network.md, "Safetensors file").
 *
 * <p>Layout: an 8-byte little-endian unsigned header length N, N bytes of UTF-8 JSON header, then the tensor
 * data. Each header entry maps a tensor name to {@code {"dtype":"F32","shape":[...],"data_offsets":[b,e]}},
 * with offsets counted from the start of the data section. The optional entry {@code "__metadata__"} maps
 * strings to strings. Floats are little-endian.
 *
 * <p>The class needs no JSON library: the header grammar is small enough for a hand-written parser, which
 * still accepts any valid JSON for these objects (whitespace, string escapes, key order). It rejects
 * everything outside this grammar: other value types, unknown keys in a tensor entry, numbers with a sign,
 * fraction, exponent or leading zero, invalid UTF-8, unpaired surrogates in a {@code \}{@code u} escape,
 * duplicate keys in any object, and anything other than whitespace after the header object. Whitespace
 * after the object is the padding that the writer adds.
 *
 * <p>Float elements are copied bit for bit in both directions, so NaN payloads and -0.0f survive a round trip.
 */
public final class Safetensors {

    /** Name of the reserved header entry for metadata. */
    public static final String METADATA_KEY = "__metadata__";

    /**
     * The largest header length the reader accepts, in bytes. It is the limit of the reference
     * implementation of the format; it stops a corrupt length field from causing a huge allocation.
     */
    public static final long MAX_HEADER_BYTES = 100_000_000L;

    /** The header length field: 8 bytes, little-endian, unsigned. */
    private static final int LENGTH_FIELD_BYTES = 8;

    /** The header and therefore the data section start at a multiple of this. */
    private static final int ALIGNMENT = 8;

    private static final String DTYPE_F32 = "F32";

    /**
     * The content of a file.
     *
     * @param tensors  the tensors in order of their begin offset in the data section
     * @param metadata the metadata entries in header order; empty if the file has none
     */
    public record Contents(LinkedHashMap<String, Tensor> tensors, Map<String, String> metadata) {

        public Contents {
            Objects.requireNonNull(tensors, "tensors");
            Objects.requireNonNull(metadata, "metadata");
        }
    }

    private Safetensors() {
    }

    /**
     * Reads a file.
     *
     * <p>The header length is checked against the file size and {@link #MAX_HEADER_BYTES} before anything
     * else is read, so a corrupt length field fails fast without a large allocation.
     *
     * @throws IOException if the file cannot be read, or it breaks any rule of docs/network.md: a header
     *                     length larger than the file, a header that is not a JSON object, a duplicate name,
     *                     a dtype other than F32, offsets that do not match the shape, overlapping tensors,
     *                     gaps between tensors, or bytes after the last tensor. The message names the tensor
     *                     where one is involved.
     */
    public static Contents read(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = channel.size();
            ByteBuffer lengthField = ByteBuffer.allocate(LENGTH_FIELD_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            readFully(channel, lengthField);
            if (lengthField.hasRemaining()) {
                throw new IOException(file + ": file has " + size + " bytes, shorter than the 8-byte header length");
            }
            checkHeaderLength(lengthField.getLong(0), size);
            if (size > Integer.MAX_VALUE - 8) {
                throw new IOException(file + ": file has " + size + " bytes, more than this reader supports");
            }
            ByteBuffer all = ByteBuffer.allocate((int) size);
            channel.position(0);
            readFully(channel, all);
            if (all.hasRemaining()) {
                throw new IOException(file + ": file shrank while it was read");
            }
            return parse(all.array());
        }
    }

    private static void readFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                return;
            }
        }
    }

    /**
     * Parses the bytes of a complete file, with the same rules as {@link #read}. Tests use it for files built
     * by hand.
     */
    public static Contents fromBytes(byte[] file) throws IOException {
        Objects.requireNonNull(file, "file");
        return parse(file);
    }

    private static Contents parse(byte[] file) throws IOException {
        if (file.length < LENGTH_FIELD_BYTES) {
            throw new IOException("file has " + file.length + " bytes, shorter than the 8-byte header length");
        }
        ByteBuffer buffer = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
        long headerLength = buffer.getLong(0);
        checkHeaderLength(headerLength, file.length);
        int dataStart = LENGTH_FIELD_BYTES + (int) headerLength;
        String json = decodeUtf8(file, LENGTH_FIELD_BYTES, (int) headerLength);

        Header header = new HeaderParser(json).parse();
        long dataLength = file.length - dataStart;
        validateLayout(header.entries, dataLength);

        List<Entry> byOffset = new ArrayList<>(header.entries);
        byOffset.sort(DATA_ORDER);
        LinkedHashMap<String, Tensor> tensors = new LinkedHashMap<>();
        for (Entry e : byOffset) {
            float[] data = new float[(int) ((e.end - e.begin) / Float.BYTES)];
            // A bulk get through a float view copies the bits; it does not canonicalise NaN.
            buffer.slice(dataStart + (int) e.begin, data.length * Float.BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(data);
            tensors.put(e.name, new Tensor(e.name, e.shape, data));
        }
        return new Contents(tensors, Collections.unmodifiableMap(header.metadata));
    }

    /**
     * Rejects a header length that is negative as a signed long (so above 2^63 as unsigned), above
     * {@link #MAX_HEADER_BYTES}, or longer than the bytes after the length field.
     */
    private static void checkHeaderLength(long headerLength, long fileSize) throws IOException {
        if (headerLength < 0 || headerLength > MAX_HEADER_BYTES) {
            throw new IOException("header length " + Long.toUnsignedString(headerLength)
                    + " exceeds the limit of " + MAX_HEADER_BYTES + " bytes");
        }
        if (headerLength > fileSize - LENGTH_FIELD_BYTES) {
            throw new IOException("header length " + headerLength + " is larger than the "
                    + (fileSize - LENGTH_FIELD_BYTES) + " bytes after the length field");
        }
    }

    private static String decodeUtf8(byte[] bytes, int offset, int length) throws IOException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes, offset, length)).toString();
        } catch (CharacterCodingException e) {
            throw new IOException("header is not valid UTF-8", e);
        }
    }

    /**
     * Checks each entry's byte length against its shape, then that the entries, sorted by begin offset, tile
     * the data section [0, dataLength) exactly.
     */
    private static void validateLayout(List<Entry> entries, long dataLength) throws IOException {
        for (Entry e : entries) {
            if (e.end < e.begin) {
                throw new IOException("tensor " + e.name + ": data_offsets [" + e.begin + ", " + e.end
                        + "] end before they begin");
            }
            long count = Tensor.elementCount(e.shape);
            if (count > Integer.MAX_VALUE) {
                throw new IOException("tensor " + e.name + ": shape " + Arrays.toString(e.shape)
                        + " has more elements than a Java array can hold");
            }
            if (e.end - e.begin != count * Float.BYTES) {
                throw new IOException("tensor " + e.name + ": data_offsets [" + e.begin + ", " + e.end
                        + "] hold " + (e.end - e.begin) + " bytes, shape " + Arrays.toString(e.shape)
                        + " needs " + count * Float.BYTES);
            }
        }
        List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort(DATA_ORDER);
        long expected = 0;
        String previous = null;
        for (Entry e : sorted) {
            if (e.begin < expected) {
                throw new IOException("tensor " + e.name + ": data_offsets [" + e.begin + ", " + e.end
                        + "] overlap tensor " + previous + ", which ends at " + expected);
            }
            if (e.begin > expected) {
                throw new IOException("tensor " + e.name + ": data_offsets begin at " + e.begin
                        + ", leaving a gap after byte " + expected);
            }
            expected = e.end;
            previous = e.name;
        }
        if (expected > dataLength) {
            throw new IOException("tensor " + previous + ": data ends at " + expected
                    + ", beyond the data section of " + dataLength + " bytes");
        }
        if (expected < dataLength) {
            throw new IOException("data section has " + (dataLength - expected)
                    + " bytes after the last tensor");
        }
    }

    /**
     * Writes a file. The tensors go into the data section in the iteration order of the map, without gaps. The
     * header is padded with spaces so that its length N is a multiple of 8. The method writes to a temporary
     * file in the same directory and then renames it, so an interrupted write never leaves a truncated file
     * under the target name. The file gets the default permissions of a new file in that directory. If the
     * write fails, the temporary file is removed.
     *
     * @param tensors  map from name to tensor; each key must equal the tensor's name, and no key may be
     *                 {@value #METADATA_KEY}
     * @param metadata metadata entries, written in iteration order; may be empty
     * @throws IllegalArgumentException if a key differs from its tensor's name or is reserved
     * @throws IOException              if writing fails
     */
    public static void write(Path file, Map<String, Tensor> tensors, Map<String, String> metadata)
            throws IOException {
        byte[] bytes = toBytes(tensors, metadata);
        Path target = file.toAbsolutePath();
        // Not Files.createTempFile: it creates the file with mode 0600, and the rename would keep that mode.
        // CREATE_NEW gives the default permissions of a new file (umask) and never reuses an existing file.
        Path tmp = target.resolveSibling(target.getFileName() + "."
                + Long.toHexString(ThreadLocalRandom.current().nextLong()) + ".tmp");
        // Outside the try: if CREATE_NEW fails, the file at tmp is not ours and must not be deleted.
        OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            try (out) {
                out.write(bytes);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * The bytes that {@link #write} puts into the file.
     *
     * <p>The header lists {@value #METADATA_KEY} first if the metadata is not empty, then the tensors in map
     * order. It has no whitespace except the trailing padding. Strings escape {@code "}, {@code \} and the
     * control characters below U+0020; other characters are written as UTF-8.
     *
     * @throws IllegalArgumentException as for {@link #write}, and also if a name, key or value contains an
     *                                  unpaired surrogate (it has no UTF-8 encoding), or if the file would
     *                                  exceed the size of a Java array
     */
    public static byte[] toBytes(Map<String, Tensor> tensors, Map<String, String> metadata) {
        Objects.requireNonNull(tensors, "tensors");
        Objects.requireNonNull(metadata, "metadata");
        StringBuilder json = new StringBuilder("{");
        if (!metadata.isEmpty()) {
            appendString(json, METADATA_KEY);
            json.append(":{");
            boolean first = true;
            for (Map.Entry<String, String> m : metadata.entrySet()) {
                if (!first) {
                    json.append(',');
                }
                first = false;
                appendString(json, Objects.requireNonNull(m.getKey(), "metadata key"));
                json.append(':');
                appendString(json, Objects.requireNonNull(m.getValue(), "metadata value for " + m.getKey()));
            }
            json.append('}');
        }
        long offset = 0;
        for (Map.Entry<String, Tensor> t : tensors.entrySet()) {
            String name = Objects.requireNonNull(t.getKey(), "tensor key");
            Tensor tensor = Objects.requireNonNull(t.getValue(), "tensor " + name);
            if (name.equals(METADATA_KEY)) {
                throw new IllegalArgumentException("tensor name " + METADATA_KEY + " is reserved");
            }
            if (!name.equals(tensor.name())) {
                throw new IllegalArgumentException("map key " + name + " differs from tensor name " + tensor.name());
            }
            if (json.length() > 1) {
                json.append(',');
            }
            appendString(json, name);
            json.append(":{\"dtype\":\"").append(DTYPE_F32).append("\",\"shape\":[");
            int[] shape = tensor.shape();
            for (int i = 0; i < shape.length; i++) {
                if (i > 0) {
                    json.append(',');
                }
                json.append(shape[i]);
            }
            long end = offset + (long) tensor.size() * Float.BYTES;
            json.append("],\"data_offsets\":[").append(offset).append(',').append(end).append("]}");
            offset = end;
        }
        json.append('}');

        byte[] header = json.toString().getBytes(StandardCharsets.UTF_8);
        int padded = (header.length + ALIGNMENT - 1) / ALIGNMENT * ALIGNMENT;
        long total = LENGTH_FIELD_BYTES + (long) padded + offset;
        if (total > Integer.MAX_VALUE - 8) {
            throw new IllegalArgumentException("file would have " + total + " bytes, more than an array holds");
        }
        ByteBuffer out = ByteBuffer.allocate((int) total).order(ByteOrder.LITTLE_ENDIAN);
        out.putLong(padded);
        out.put(header);
        for (int i = header.length; i < padded; i++) {
            out.put((byte) ' ');
        }
        for (Tensor tensor : tensors.values()) {
            // A bulk put through a float view copies the bits; it does not canonicalise NaN.
            int start = out.position();
            out.slice(start, tensor.size() * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN)
                    .asFloatBuffer().put(tensor.data());
            out.position(start + tensor.size() * Float.BYTES);
        }
        return out.array();
    }

    /** Appends a JSON string literal. */
    private static void appendString(StringBuilder json, String s) {
        json.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                json.append(c).append(s.charAt(++i));
            } else if (Character.isSurrogate(c)) {
                throw new IllegalArgumentException("string has an unpaired surrogate at index " + i + ": "
                        + s.substring(0, i) + "...");
            } else if (c == '"' || c == '\\') {
                json.append('\\').append(c);
            } else if (c < 0x20) {
                json.append(String.format("\\u%04x", (int) c));
            } else {
                json.append(c);
            }
        }
        json.append('"');
    }

    /** One tensor entry of the header, before its data is read. */
    private record Entry(String name, int[] shape, long begin, long end) {
    }

    /**
     * Order in the data section: by begin, then by end, so that an empty tensor at offset b comes before a
     * tensor that starts at b. List.sort is stable, so empty tensors at the same offset keep their header order.
     */
    private static final Comparator<Entry> DATA_ORDER =
            Comparator.comparingLong(Entry::begin).thenComparingLong(Entry::end);

    /** The parsed header: tensor entries in header order, metadata in header order. */
    private record Header(List<Entry> entries, LinkedHashMap<String, String> metadata) {
    }

    /**
     * A recursive-descent parser for exactly the header grammar:
     *
     * <pre>
     * header   = ws "{" [ member *( "," member ) ] "}" ws
     * member   = string ":" ( tensor | metadata )        ; metadata only for the key "__metadata__"
     * tensor   = "{" three members "dtype": string, "shape": [ uint* ], "data_offsets": [ uint, uint ] "}"
     * metadata = "{" [ string ":" string *( "," string ":" string ) ] "}"
     * uint     = "0" | [1-9][0-9]*
     * </pre>
     *
     * with JSON whitespace (space, tab, LF, CR) allowed between all tokens.
     */
    private static final class HeaderParser {

        private final String s;
        private int pos;

        HeaderParser(String s) {
            this.s = s;
        }

        Header parse() throws IOException {
            List<Entry> entries = new ArrayList<>();
            LinkedHashMap<String, String> metadata = new LinkedHashMap<>();
            boolean sawMetadata = false;
            Set<String> names = new HashSet<>();
            skipWhitespace();
            expect('{', "header must be a JSON object");
            skipWhitespace();
            if (peek() != '}') {
                do {
                    skipWhitespace();
                    String key = parseString();
                    skipWhitespace();
                    expect(':', "expected ':' after key " + key);
                    skipWhitespace();
                    if (key.equals(METADATA_KEY)) {
                        if (sawMetadata) {
                            throw error("duplicate " + METADATA_KEY);
                        }
                        sawMetadata = true;
                        parseMetadata(metadata);
                    } else {
                        if (!names.add(key)) {
                            throw error("duplicate tensor name " + key);
                        }
                        entries.add(parseTensor(key));
                    }
                    skipWhitespace();
                } while (consume(','));
            }
            expect('}', "expected ',' or '}' in the header object");
            skipWhitespace();
            if (pos != s.length()) {
                throw error("unexpected content after the header object");
            }
            return new Header(entries, metadata);
        }

        private void parseMetadata(Map<String, String> metadata) throws IOException {
            expect('{', METADATA_KEY + " must be a JSON object");
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return;
            }
            do {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':', "expected ':' after metadata key " + key);
                skipWhitespace();
                if (peek() != '"') {
                    throw error("metadata value for " + key + " must be a string");
                }
                String value = parseString();
                if (metadata.putIfAbsent(key, value) != null) {
                    throw error("duplicate metadata key " + key);
                }
                skipWhitespace();
            } while (consume(','));
            expect('}', "expected ',' or '}' in " + METADATA_KEY);
        }

        private Entry parseTensor(String name) throws IOException {
            expect('{', "tensor " + name + ": entry must be a JSON object");
            String dtype = null;
            long[] shape = null;
            long[] offsets = null;
            skipWhitespace();
            if (peek() != '}') {
                do {
                    skipWhitespace();
                    String key = parseString();
                    skipWhitespace();
                    expect(':', "tensor " + name + ": expected ':' after key " + key);
                    skipWhitespace();
                    switch (key) {
                        case "dtype" -> {
                            if (dtype != null) {
                                throw error("tensor " + name + ": duplicate key dtype");
                            }
                            if (peek() != '"') {
                                throw error("tensor " + name + ": dtype must be a string");
                            }
                            dtype = parseString();
                        }
                        case "shape" -> {
                            if (shape != null) {
                                throw error("tensor " + name + ": duplicate key shape");
                            }
                            shape = parseUintArray(name, key);
                        }
                        case "data_offsets" -> {
                            if (offsets != null) {
                                throw error("tensor " + name + ": duplicate key data_offsets");
                            }
                            offsets = parseUintArray(name, key);
                        }
                        default -> throw error("tensor " + name + ": unknown key " + key);
                    }
                    skipWhitespace();
                } while (consume(','));
            }
            expect('}', "tensor " + name + ": expected ',' or '}'");
            if (dtype == null || shape == null || offsets == null) {
                throw error("tensor " + name + ": entry needs dtype, shape and data_offsets");
            }
            if (!dtype.equals(DTYPE_F32)) {
                throw error("tensor " + name + ": dtype " + dtype + " is not supported, only " + DTYPE_F32);
            }
            if (offsets.length != 2) {
                throw error("tensor " + name + ": data_offsets must have 2 elements, not " + offsets.length);
            }
            int[] dims = new int[shape.length];
            for (int i = 0; i < shape.length; i++) {
                if (shape[i] > Integer.MAX_VALUE) {
                    throw error("tensor " + name + ": dimension " + shape[i] + " is too large");
                }
                dims[i] = (int) shape[i];
            }
            return new Entry(name, dims, offsets[0], offsets[1]);
        }

        private long[] parseUintArray(String name, String key) throws IOException {
            expect('[', "tensor " + name + ": " + key + " must be an array");
            List<Long> values = new ArrayList<>();
            skipWhitespace();
            if (peek() != ']') {
                do {
                    skipWhitespace();
                    values.add(parseUint(name, key));
                    skipWhitespace();
                } while (consume(','));
            }
            expect(']', "tensor " + name + ": expected ',' or ']' in " + key);
            long[] result = new long[values.size()];
            for (int i = 0; i < result.length; i++) {
                result[i] = values.get(i);
            }
            return result;
        }

        /** A non-negative JSON integer without sign, fraction or exponent, that fits a long. */
        private long parseUint(String name, String key) throws IOException {
            int start = pos;
            while (pos < s.length() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') {
                pos++;
            }
            if (pos == start) {
                throw error("tensor " + name + ": " + key + " must hold non-negative integers");
            }
            if (pos < s.length() && (s.charAt(pos) == '.' || s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                throw error("tensor " + name + ": " + key + " must hold integers, not fractions");
            }
            String digits = s.substring(start, pos);
            if (digits.length() > 1 && digits.charAt(0) == '0') {
                throw error("tensor " + name + ": number " + digits + " in " + key + " has a leading zero");
            }
            try {
                return Long.parseLong(digits);
            } catch (NumberFormatException e) {
                throw error("tensor " + name + ": number " + digits + " in " + key + " is too large");
            }
        }

        /** A JSON string; the current character must be the opening quote. */
        private String parseString() throws IOException {
            expect('"', "expected a string");
            StringBuilder out = new StringBuilder();
            while (true) {
                if (pos >= s.length()) {
                    throw error("unterminated string");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return out.toString();
                }
                if (c < 0x20) {
                    throw error("unescaped control character U+" + String.format("%04X", (int) c) + " in a string");
                }
                if (c != '\\') {
                    // The header was decoded with a strict UTF-8 decoder, so surrogates here are always paired.
                    out.append(c);
                    continue;
                }
                if (pos >= s.length()) {
                    throw error("unterminated escape");
                }
                char e = s.charAt(pos++);
                switch (e) {
                    case '"', '\\', '/' -> out.append(e);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        char u = parseHex4();
                        if (Character.isHighSurrogate(u)) {
                            if (!(s.startsWith("\\u", pos))) {
                                throw error("unpaired surrogate escape \\u" + hex(u));
                            }
                            pos += 2;
                            char low = parseHex4();
                            if (!Character.isLowSurrogate(low)) {
                                throw error("unpaired surrogate escape \\u" + hex(u));
                            }
                            out.append(u).append(low);
                        } else if (Character.isLowSurrogate(u)) {
                            throw error("unpaired surrogate escape \\u" + hex(u));
                        } else {
                            out.append(u);
                        }
                    }
                    default -> throw error("invalid escape \\" + e);
                }
            }
        }

        private char parseHex4() throws IOException {
            if (pos + 4 > s.length()) {
                throw error("truncated \\u escape");
            }
            int value = 0;
            for (int i = 0; i < 4; i++) {
                int digit = asciiHexDigit(s.charAt(pos + i));
                if (digit < 0) {
                    throw error("invalid \\u escape");
                }
                value = value * 16 + digit;
            }
            pos += 4;
            return (char) value;
        }

        /** The value of an ASCII hex digit, or -1. Character.digit would also accept non-ASCII digits. */
        private static int asciiHexDigit(char c) {
            if (c >= '0' && c <= '9') {
                return c - '0';
            }
            if (c >= 'a' && c <= 'f') {
                return c - 'a' + 10;
            }
            if (c >= 'A' && c <= 'F') {
                return c - 'A' + 10;
            }
            return -1;
        }

        private static String hex(char c) {
            return String.format("%04X", (int) c);
        }

        private void skipWhitespace() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                    return;
                }
                pos++;
            }
        }

        private int peek() {
            return pos < s.length() ? s.charAt(pos) : -1;
        }

        private boolean consume(char c) {
            if (peek() == c) {
                pos++;
                return true;
            }
            return false;
        }

        private void expect(char c, String message) throws IOException {
            if (!consume(c)) {
                throw error(message);
            }
        }

        private IOException error(String message) {
            return new IOException("malformed header at character " + pos + ": " + message);
        }
    }
}
