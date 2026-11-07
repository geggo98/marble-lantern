package sternhalma.nn;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SafetensorsTest {

    private static final String GOLDEN_HEADER = "{\"__metadata__\":{\"k\":\"v\"},"
            + "\"b\":{\"dtype\":\"F32\",\"shape\":[2],\"data_offsets\":[0,8]},"
            + "\"a\":{\"dtype\":\"F32\",\"shape\":[],\"data_offsets\":[8,12]}}";

    /**
     * A file written by hand: tensor b = [1.0, -2.0], scalar a = 0.5, metadata k = v. The header has 132 bytes
     * and is padded with 4 spaces to N = 136 = 0x88, so the data starts at byte 144.
     */
    private static byte[] golden() {
        ByteBuffer b = ByteBuffer.allocate(8 + 136 + 12);
        b.put(new byte[] {(byte) 0x88, 0, 0, 0, 0, 0, 0, 0});
        b.put(GOLDEN_HEADER.getBytes(StandardCharsets.US_ASCII));
        b.put("    ".getBytes(StandardCharsets.US_ASCII));
        b.put(new byte[] {0, 0, (byte) 0x80, 0x3F}); // 1.0f = 0x3F800000, little-endian
        b.put(new byte[] {0, 0, 0, (byte) 0xC0}); // -2.0f = 0xC0000000
        b.put(new byte[] {0, 0, 0, 0x3F}); // 0.5f = 0x3F000000
        assertFalse(b.hasRemaining());
        return b.array();
    }

    private static Map<String, Tensor> goldenTensors() {
        Map<String, Tensor> tensors = new LinkedHashMap<>();
        tensors.put("b", new Tensor("b", new int[] {2}, new float[] {1.0f, -2.0f}));
        tensors.put("a", new Tensor("a", new int[0], new float[] {0.5f}));
        return tensors;
    }

    /** A file with the given header length field, header text (not padded) and data section. */
    private static byte[] file(long headerLength, String header, byte[] data) {
        byte[] h = header.getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocate(8 + h.length + data.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putLong(headerLength).put(h).put(data);
        return b.array();
    }

    /** A file whose header length field is correct. */
    private static byte[] file(String header, int dataBytes) {
        return file(header.getBytes(StandardCharsets.UTF_8).length, header, new byte[dataBytes]);
    }

    private static String entry(String name, String shape, long begin, long end) {
        return "\"" + name + "\":{\"dtype\":\"F32\",\"shape\":" + shape + ",\"data_offsets\":[" + begin + ","
                + end + "]}";
    }

    private static IOException rejected(byte[] file) {
        return assertThrows(IOException.class, () -> Safetensors.fromBytes(file));
    }

    private static void assertRejected(byte[] file, String messagePart) {
        IOException e = rejected(file);
        assertTrue(e.getMessage().contains(messagePart),
                () -> "message '" + e.getMessage() + "' should contain '" + messagePart + "'");
    }

    private static void assertSameBits(Tensor expected, Tensor actual) {
        assertEquals(expected.name(), actual.name());
        assertArrayEquals(expected.shape(), actual.shape(), expected.name());
        assertEquals(expected.size(), actual.size(), expected.name());
        for (int i = 0; i < expected.size(); i++) {
            int e = Float.floatToRawIntBits(expected.data()[i]);
            int a = Float.floatToRawIntBits(actual.data()[i]);
            int index = i;
            assertEquals(e, a, () -> expected.name() + "[" + index + "]: expected bits "
                    + Integer.toHexString(e) + ", got " + Integer.toHexString(a));
        }
    }

    private static void assertSameContents(Map<String, Tensor> tensors, Map<String, String> metadata,
            Safetensors.Contents contents) {
        assertEquals(List.copyOf(tensors.keySet()), List.copyOf(contents.tensors().keySet()), "tensor order");
        for (Tensor t : tensors.values()) {
            assertSameBits(t, contents.tensors().get(t.name()));
        }
        assertEquals(List.copyOf(metadata.entrySet()), List.copyOf(contents.metadata().entrySet()), "metadata");
    }

    // ---------------------------------------------------------------- writer

    @Test
    void writerMatchesTheHandWrittenFileByteForByte() {
        byte[] actual = Safetensors.toBytes(goldenTensors(), Map.of("k", "v"));
        assertArrayEquals(golden(), actual);
    }

    @Test
    void writerWithoutTensorsOrMetadataWritesAPaddedEmptyObject() {
        byte[] expected = {8, 0, 0, 0, 0, 0, 0, 0, '{', '}', ' ', ' ', ' ', ' ', ' ', ' '};
        assertArrayEquals(expected, Safetensors.toBytes(Map.of(), Map.of()));
    }

    /** For many random files: N is a multiple of 8, the padding is spaces, and the data follows directly. */
    @Test
    void headerIsPaddedWithSpacesToAMultipleOfEight() {
        Random random = new Random(11);
        for (int iteration = 0; iteration < 200; iteration++) {
            Map<String, Tensor> tensors = randomTensors(random);
            Map<String, String> metadata = randomMetadata(random);
            byte[] bytes = Safetensors.toBytes(tensors, metadata);
            long n = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getLong(0);
            assertEquals(0, n % 8, "header length " + n);
            long dataBytes = tensors.values().stream().mapToLong(t -> 4L * t.size()).sum();
            assertEquals(8 + n + dataBytes, bytes.length);
            String header = new String(bytes, 8, (int) n, StandardCharsets.UTF_8);
            String stripped = header.stripTrailing();
            assertTrue(stripped.endsWith("}"));
            assertEquals(" ".repeat(header.length() - stripped.length()), header.substring(stripped.length()));
            assertTrue(header.length() - stripped.length() < 8, "at most 7 padding bytes");
        }
    }

    @Test
    void writerRejectsAKeyThatDiffersFromTheTensorName() {
        Map<String, Tensor> tensors = Map.of("x", new Tensor("y", new int[] {1}, new float[1]));
        assertThrows(IllegalArgumentException.class, () -> Safetensors.toBytes(tensors, Map.of()));
    }

    @Test
    void writerRejectsTheReservedName() {
        Map<String, Tensor> tensors = Map.of(Safetensors.METADATA_KEY,
                new Tensor(Safetensors.METADATA_KEY, new int[] {1}, new float[1]));
        assertThrows(IllegalArgumentException.class, () -> Safetensors.toBytes(tensors, Map.of()));
    }

    /** An unpaired surrogate has no UTF-8 encoding; String.getBytes would silently write '?'. */
    @Test
    void writerRejectsUnpairedSurrogates() {
        Map<String, Tensor> tensors = Map.of("a\uD800", new Tensor("a\uD800", new int[] {1}, new float[1]));
        assertThrows(IllegalArgumentException.class, () -> Safetensors.toBytes(tensors, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> Safetensors.toBytes(Map.of(), Map.of("k", "\uDC00x")));
        assertThrows(IllegalArgumentException.class, () -> Safetensors.toBytes(Map.of(), Map.of("\uD83D", "v")));
    }

    // ---------------------------------------------------------------- reader

    @Test
    void readerDecodesTheHandWrittenFile() throws IOException {
        Safetensors.Contents c = Safetensors.fromBytes(golden());
        assertEquals(List.of("b", "a"), List.copyOf(c.tensors().keySet()));
        assertArrayEquals(new float[] {1.0f, -2.0f}, c.tensors().get("b").data());
        assertArrayEquals(new int[] {2}, c.tensors().get("b").shape());
        assertArrayEquals(new float[] {0.5f}, c.tensors().get("a").data());
        assertArrayEquals(new int[0], c.tensors().get("a").shape());
        assertEquals(Map.of("k", "v"), c.metadata());
    }

    /**
     * The header is not padded (N = odd), entries list the keys in another order, there is whitespace
     * everywhere JSON allows it, and the tensors appear in the header in the reverse of their data order.
     * The reader must return them by begin offset.
     */
    @Test
    void readerAcceptsAnyValidLayoutOfTheHeader() throws IOException {
        String header = " \t{ \"late\" : { \"data_offsets\" : [ 4 , 12 ] ,\r\n \"shape\" : [ 1 , 2 ] , "
                + "\"dtype\" : \"F32\" } ,\n \"__metadata__\" : { } , \"early\":{\"shape\":[1],"
                + "\"dtype\":\"F32\",\"data_offsets\":[0,4]} }\n ";
        ByteBuffer data = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        data.putFloat(7f).putFloat(8f).putFloat(9f);
        byte[] bytes = file(header.getBytes(StandardCharsets.UTF_8).length, header, data.array());
        assertTrue((bytes.length - 8 - 12) % 8 != 0, "test file must be unaligned");
        Safetensors.Contents c = Safetensors.fromBytes(bytes);
        assertEquals(List.of("early", "late"), List.copyOf(c.tensors().keySet()));
        assertArrayEquals(new float[] {7f}, c.tensors().get("early").data());
        assertArrayEquals(new float[] {8f, 9f}, c.tensors().get("late").data());
        assertArrayEquals(new int[] {1, 2}, c.tensors().get("late").shape());
        assertTrue(c.metadata().isEmpty());
    }

    @Test
    void readerDecodesEveryStringEscape() throws IOException {
        String header = "{\"__metadata__\":{\"e\":\"\\\" \\\\ \\/ \\b \\f \\n \\r \\t \\u00e9 \\u00E9 \\ud83d\\ude00\"},"
                + entry("n\\u0000\\\"", "[]", 0, 4) + "}";
        Safetensors.Contents c = Safetensors.fromBytes(file(header, 4));
        assertEquals("\" \\ / \b \f \n \r \t é é 😀", c.metadata().get("e"));
        assertEquals(List.of("n\u0000\""), List.copyOf(c.tensors().keySet()));
    }

    @Test
    void missingMetadataGivesAnEmptyMap() throws IOException {
        Safetensors.Contents c = Safetensors.fromBytes(file("{" + entry("a", "[1]", 0, 4) + "}", 4));
        assertTrue(c.metadata().isEmpty());
    }

    /** Empty tensors take no bytes, so several may begin at the same offset; header order breaks the tie. */
    @Test
    void emptyTensorsMayShareAnOffset() throws IOException {
        String header = "{" + entry("z", "[0]", 4, 4) + "," + entry("y", "[3,0]", 4, 4) + ","
                + entry("x", "[1]", 0, 4) + "," + entry("w", "[0]", 0, 0) + "}";
        Safetensors.Contents c = Safetensors.fromBytes(file(header, 4));
        assertEquals(List.of("w", "x", "z", "y"), List.copyOf(c.tensors().keySet()));
        assertArrayEquals(new int[] {3, 0}, c.tensors().get("y").shape());
    }

    // ---------------------------------------------------------------- round trips

    @Test
    void roundTripKeepsTensorsOrderAndMetadata() throws IOException {
        Map<String, Tensor> tensors = new LinkedHashMap<>();
        // Not in alphabetical order, so a reader that sorts by name fails.
        tensors.put("zeta.weight", new Tensor("zeta.weight", new int[] {2, 3}, new float[] {1, 2, 3, 4, 5, 6}));
        tensors.put("scalar", new Tensor("scalar", new int[0], new float[] {-7.25f}));
        tensors.put("empty", new Tensor("empty", new int[] {3, 0}, new float[0]));
        tensors.put("alpha.bias", new Tensor("alpha.bias", new int[] {3}, new float[] {0.1f, 0.2f, 0.3f}));
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("format", "sternhalma-nn");
        metadata.put("b", "2");
        metadata.put("a", "1");
        assertSameContents(tensors, metadata, Safetensors.fromBytes(Safetensors.toBytes(tensors, metadata)));
    }

    @Test
    void nonAsciiAndSpecialCharactersSurviveARoundTrip() throws IOException {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("Größe", "Spieler: ÄÖÜ ß 中文 😀 ☃");
        metadata.put("quote\"back\\slash", "line\nbreak\ttab\u0001\u001f\u007f end");
        metadata.put("", "");
        String name = "w\"e\\ird/é😀\n";
        Map<String, Tensor> tensors = Map.of(name, new Tensor(name, new int[] {1}, new float[] {3f}));
        byte[] bytes = Safetensors.toBytes(tensors, metadata);
        assertSameContents(tensors, metadata, Safetensors.fromBytes(bytes));
        // Non-ASCII is written as UTF-8, not as \\u escapes.
        String header = new String(bytes, 8, bytes.length - 12, StandardCharsets.UTF_8);
        assertTrue(header.contains("Größe"), header);
    }

    /** NaN payloads (quiet and signalling), infinities, -0.0f and subnormals keep their exact bits. */
    @Test
    void specialFloatBitPatternsSurviveARoundTrip() throws IOException {
        int[] bits = {0x7FC00000, 0x7FC00001, 0xFFC12345, 0x7F800001, 0xFFBFFFFF, 0x7F800000, 0xFF800000,
                0x80000000, 0x00000000, 0x00000001, 0x807FFFFF, 0x7F7FFFFF, 0x3F800000};
        float[] data = new float[bits.length];
        for (int i = 0; i < bits.length; i++) {
            data[i] = Float.intBitsToFloat(bits[i]);
        }
        // Do not trust intBitsToFloat on signalling NaN: check the input first.
        for (int i = 0; i < bits.length; i++) {
            assertEquals(bits[i], Float.floatToRawIntBits(data[i]), "input " + i);
        }
        Tensor t = new Tensor("special", new int[] {bits.length}, data);
        byte[] bytes = Safetensors.toBytes(Map.of("special", t), Map.of());
        // The data section holds the bits in little-endian order.
        ByteBuffer section = ByteBuffer.wrap(bytes, bytes.length - 4 * bits.length, 4 * bits.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < bits.length; i++) {
            assertEquals(bits[i], section.getInt(), "written element " + i);
        }
        assertSameBits(t, Safetensors.fromBytes(bytes).tensors().get("special"));
    }

    /** Random files: random ranks (including scalars), zero dimensions, random bits and random strings. */
    @Test
    void randomFilesSurviveARoundTrip() throws IOException {
        Random random = new Random(42);
        for (int iteration = 0; iteration < 300; iteration++) {
            Map<String, Tensor> tensors = randomTensors(random);
            Map<String, String> metadata = randomMetadata(random);
            assertSameContents(tensors, metadata, Safetensors.fromBytes(Safetensors.toBytes(tensors, metadata)));
        }
    }

    @Test
    void fileRoundTripReplacesTheTargetAndLeavesNoTemporaryFile(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("weights.safetensors");
        Files.write(target, new byte[] {1, 2, 3});
        Safetensors.write(target, goldenTensors(), Map.of("k", "v"));
        assertArrayEquals(golden(), Files.readAllBytes(target));
        Safetensors.Contents c = Safetensors.read(target);
        assertSameContents(goldenTensors(), Map.of("k", "v"), c);
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(List.of(target), files.toList());
        }
    }

    /**
     * Review finding: the written file has the permissions of any new file in the directory (umask), not the
     * owner-only mode of a temporary file. The reference is a file that Files.write creates in the same place.
     */
    @Test
    void writtenFileHasDefaultPermissions(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("weights.safetensors");
        Safetensors.write(target, goldenTensors(), Map.of("k", "v"));
        assertTrue(Files.isReadable(target));
        assertArrayEquals(golden(), Files.readAllBytes(target));
        Assumptions.assumeTrue(Files.getFileStore(target).supportsFileAttributeView(PosixFileAttributeView.class),
                "file system is not POSIX");
        Path reference = dir.resolve("reference");
        Files.write(reference, new byte[1]);
        Set<PosixFilePermission> expected = Files.getPosixFilePermissions(reference);
        assertTrue(expected.contains(PosixFilePermission.OWNER_READ) && expected.size() > 2,
                "the umask gives new files more than owner-only access: " + expected);
        assertEquals(expected, Files.getPosixFilePermissions(target));
        // Replacing an existing file gives the same permissions.
        Safetensors.write(target, goldenTensors(), Map.of());
        assertEquals(expected, Files.getPosixFilePermissions(target));
    }

    /** A write that fails after the temporary file exists removes it again and leaves the target alone. */
    @Test
    void failedWriteLeavesNoTemporaryFile(@TempDir Path dir) throws IOException {
        // The target is a non-empty directory, so the final rename fails.
        Path target = dir.resolve("weights.safetensors");
        Files.createDirectory(target);
        Path inside = target.resolve("keep");
        Files.write(inside, new byte[] {7});
        assertThrows(IOException.class, () -> Safetensors.write(target, goldenTensors(), Map.of()));
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(List.of(target), files.toList());
        }
        assertArrayEquals(new byte[] {7}, Files.readAllBytes(inside));
        // A directory that does not exist: nothing is created.
        Path missing = dir.resolve("no-such-dir").resolve("w.safetensors");
        assertThrows(IOException.class, () -> Safetensors.write(missing, goldenTensors(), Map.of()));
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(List.of(target), files.toList());
        }
    }

    @Test
    void readUsesTheSameRulesAsFromBytes(@TempDir Path dir) throws IOException {
        byte[] bytes = golden();
        Path truncated = dir.resolve("truncated.safetensors");
        Files.write(truncated, Arrays.copyOf(bytes, bytes.length - 1));
        assertThrows(IOException.class, () -> Safetensors.read(truncated));
        Path tiny = dir.resolve("tiny.safetensors");
        Files.write(tiny, new byte[] {1, 0, 0});
        assertThrows(IOException.class, () -> Safetensors.read(tiny));
        Path huge = dir.resolve("huge.safetensors");
        Files.write(huge, file(-1L, "{}", new byte[0]));
        assertThrows(IOException.class, () -> Safetensors.read(huge));
        assertThrows(IOException.class, () -> Safetensors.read(dir.resolve("missing.safetensors")));
    }

    // ---------------------------------------------------------------- rejections: header length

    @Test
    void truncatedFilesAreRejected() {
        byte[] bytes = golden();
        assertRejected(Arrays.copyOf(bytes, bytes.length - 1), "beyond the data section");
        assertRejected(Arrays.copyOf(bytes, 8 + 100), "larger than");
        assertRejected(Arrays.copyOf(bytes, 7), "shorter than");
        assertRejected(new byte[0], "shorter than");
    }

    @Test
    void headerLengthLargerThanTheFileIsRejected() {
        String header = "{}";
        assertRejected(file(3, header, new byte[0]), "larger than");
    }

    /**
     * A reader that treats the length as signed sees -1 or a negative number; one that reads only 32 bits
     * sees a small valid length. All must be rejected, and quickly: allocating these sizes would fail.
     */
    @Test
    void absurdHeaderLengthsAreRejectedBeforeAllocating() {
        String header = "{}      ";
        assertRejected(file(-1L, header, new byte[0]), "exceeds the limit");
        assertRejected(file(Long.MIN_VALUE + 8, header, new byte[0]), "exceeds the limit");
        assertRejected(file(Safetensors.MAX_HEADER_BYTES + 1, header, new byte[0]), "exceeds the limit");
        // 2^32 + 8: the low 32 bits alone give the correct length 8 of this header.
        assertRejected(file((1L << 32) + 8, header, new byte[0]), "exceeds the limit");
        assertRejected(file(Integer.MAX_VALUE, header, new byte[0]), "exceeds the limit");
    }

    // ---------------------------------------------------------------- rejections: JSON

    @Test
    void headersThatAreNotAJsonObjectAreRejected() {
        for (String header : List.of("", "   ", "[]", "\"x\"", "null", "{", "}", "{\"a\"}", "{\"a\":}",
                "{} x", "{}{}", "{,}", "{\"a\":1}", "{\"__metadata__\":[]}", "{\"__metadata__\":{\"k\":1}}",
                "{\"__metadata__\":{\"k\":null}}", "{\"__metadata__\":{\"k\":\"v\",}}",
                "{\"__metadata__\":{\"k\" \"v\"}}", "{\"__metadata__\":{}\u0000}", "{'a':{}}")) {
            assertThrows(IOException.class, () -> Safetensors.fromBytes(file(header, 0)), header);
        }
    }

    @Test
    void malformedStringsAreRejected() {
        for (String key : List.of("\"a", "\"a\\\"", "\"a\\x\"", "\"a\\u12\"", "\"a\\u12g4\"",
                "\"a\\uD800\"", "\"a\\uD800\\u0041\"", "\"a\\uDC00\"", "\"a\nb\"", "\"a\tb\"",
                "\"a\\u００４１\"")) {
            String header = "{\"__metadata__\":{" + key + ":\"v\"}}";
            assertThrows(IOException.class, () -> Safetensors.fromBytes(file(header, 0)), key);
        }
    }

    @Test
    void invalidUtf8IsRejected() {
        byte[] h = {'{', '"', '_', '_', 'm', 'e', 't', 'a', 'd', 'a', 't', 'a', '_', '_', '"', ':', '{', '"',
                (byte) 0xC3, '"', ':', '"', 'v', '"', '}', '}'};
        ByteBuffer b = ByteBuffer.allocate(8 + h.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putLong(h.length).put(h);
        assertRejected(b.array(), "UTF-8");
    }

    @Test
    void numbersOutsideTheGrammarAreRejected() {
        for (String shape : List.of("[-1]", "[1.0]", "[1e0]", "[01]", "[+1]", "[2147483648]",
                "[99999999999999999999]", "[1,]", "[,1]", "[\"1\"]", "1", "[null]")) {
            String header = "{" + entry("a", shape, 0, 4) + "}";
            assertThrows(IOException.class, () -> Safetensors.fromBytes(file(header, 4)), shape);
        }
        assertRejected(file("{\"a\":{\"dtype\":\"F32\",\"shape\":[1],\"data_offsets\":[0,4,8]}}", 4),
                "2 elements");
        assertRejected(file("{\"a\":{\"dtype\":\"F32\",\"shape\":[1],\"data_offsets\":[4]}}", 4), "2 elements");
        assertRejected(file("{\"a\":{\"dtype\":\"F32\",\"shape\":[1],\"data_offsets\":[0,-4]}}", 4),
                "non-negative");
    }

    @Test
    void tensorEntriesNeedExactlyTheThreeKeys() {
        assertRejected(file("{\"a\":{\"dtype\":\"F32\",\"shape\":[1]}}", 4), "needs dtype");
        assertRejected(file("{\"a\":{}}", 0), "needs dtype");
        assertRejected(file("{\"a\":{\"dtype\":\"F32\",\"shape\":[1],\"data_offsets\":[0,4],\"x\":[]}}", 4),
                "unknown key x");
        assertRejected(file("{\"a\":{\"dtype\":\"F32\",\"dtype\":\"F32\",\"shape\":[1],\"data_offsets\":[0,4]}}",
                4), "duplicate key dtype");
        assertRejected(file("{\"a\":{\"dtype\":32,\"shape\":[1],\"data_offsets\":[0,4]}}", 4), "string");
        assertRejected(file("{\"a\":[]}", 0), "JSON object");
    }

    // ---------------------------------------------------------------- rejections: rules of docs/network.md

    @Test
    void duplicateNamesAreRejected() {
        assertRejected(file("{" + entry("a", "[1]", 0, 4) + "," + entry("a", "[1]", 4, 8) + "}", 8),
                "duplicate tensor name a");
        assertRejected(file("{\"__metadata__\":{},\"__metadata__\":{}}", 0), "duplicate __metadata__");
        assertRejected(file("{\"__metadata__\":{\"k\":\"1\",\"k\":\"2\"}}", 0), "duplicate metadata key k");
        // The same name, once escaped: duplicates are found after decoding.
        assertRejected(file("{" + entry("a", "[1]", 0, 4) + "," + entry("\\u0061", "[1]", 4, 8) + "}", 8),
                "duplicate tensor name a");
    }

    @Test
    void dtypesOtherThanF32AreRejectedByName() {
        for (String dtype : List.of("F16", "BF16", "F64", "I32", "f32", "F32 ", "")) {
            String header = "{\"w\":{\"dtype\":\"" + dtype + "\",\"shape\":[1],\"data_offsets\":[0,4]}}";
            IOException e = rejected(file(header, 4));
            assertTrue(e.getMessage().contains("tensor w") && e.getMessage().contains("dtype " + dtype + " "),
                    e.getMessage());
        }
    }

    @Test
    void offsetsThatDoNotMatchTheShapeAreRejected() {
        assertRejected(file("{" + entry("a", "[2]", 0, 4) + "}", 4), "tensor a");
        assertRejected(file("{" + entry("a", "[1]", 0, 8) + "}", 8), "shape [1] needs 4");
        assertRejected(file("{" + entry("s", "[]", 0, 0) + "}", 0), "tensor s");
        assertRejected(file("{" + entry("e", "[0]", 0, 4) + "}", 4), "tensor e");
        assertRejected(file("{" + entry("a", "[1]", 8, 4) + "}", 8), "end before they begin");
    }

    /** 2^34 bytes would match the shape; the reader must reject it without allocating 2^32 floats. */
    @Test
    void shapesLargerThanAnArrayAreRejected() {
        assertRejected(file("{" + entry("h", "[65536,65536]", 0, 1L << 34) + "}", 0), "more elements");
    }

    /** Review finding: a zero dimension after large ones is an empty tensor, not "more elements". */
    @Test
    void zeroDimensionAfterLargeOnesIsAnEmptyTensor() throws IOException {
        String header = "{" + entry("a", "[2147483647,2,0]", 0, 0) + "," + entry("b", "[0,2147483647,2]", 0, 0)
                + "," + entry("c", "[1]", 0, 4) + "}";
        Safetensors.Contents c = Safetensors.fromBytes(file(header, 4));
        assertArrayEquals(new int[] {2147483647, 2, 0}, c.tensors().get("a").shape());
        assertEquals(0, c.tensors().get("a").size());
        assertArrayEquals(new int[] {0, 2147483647, 2}, c.tensors().get("b").shape());
        // Round trip through the writer.
        Map<String, Tensor> tensors = new LinkedHashMap<>(c.tensors());
        assertSameContents(tensors, Map.of(), Safetensors.fromBytes(Safetensors.toBytes(tensors, Map.of())));
        // Without the zero the same dimensions are too large.
        assertRejected(file("{" + entry("h", "[2147483647,2]", 0, 0) + "}", 0), "more elements");
    }

    @Test
    void overlappingTensorsAreRejected() {
        assertRejected(file("{" + entry("a", "[2]", 0, 8) + "," + entry("b", "[2]", 4, 12) + "}", 12),
                "overlap");
        assertRejected(file("{" + entry("a", "[1]", 0, 4) + "," + entry("b", "[1]", 0, 4) + "}", 4),
                "overlap");
    }

    @Test
    void gapsAreRejected() {
        assertRejected(file("{" + entry("a", "[1]", 0, 4) + "," + entry("b", "[1]", 8, 12) + "}", 12), "gap");
        assertRejected(file("{" + entry("a", "[1]", 4, 8) + "}", 8), "gap");
    }

    @Test
    void bytesAfterTheLastTensorAreRejected() {
        assertRejected(file("{" + entry("a", "[1]", 0, 4) + "}", 5), "after the last tensor");
        assertRejected(file("{}", 1), "after the last tensor");
        byte[] bytes = golden();
        assertRejected(Arrays.copyOf(bytes, bytes.length + 4), "after the last tensor");
    }

    @Test
    void tensorsBeyondTheEndOfTheFileAreRejected() {
        assertRejected(file("{" + entry("a", "[2]", 0, 8) + "}", 4), "beyond the data section");
    }

    // ---------------------------------------------------------------- helpers for random files

    private static Map<String, Tensor> randomTensors(Random random) {
        Map<String, Tensor> tensors = new LinkedHashMap<>();
        int count = random.nextInt(6);
        while (tensors.size() < count) {
            String name = randomString(random, 1 + random.nextInt(8));
            if (name.equals(Safetensors.METADATA_KEY) || tensors.containsKey(name)) {
                continue;
            }
            int[] shape = new int[random.nextInt(4)];
            for (int i = 0; i < shape.length; i++) {
                shape[i] = random.nextInt(5);
            }
            float[] data = new float[(int) Tensor.elementCount(shape)];
            for (int i = 0; i < data.length; i++) {
                data[i] = Float.intBitsToFloat(random.nextInt());
            }
            tensors.put(name, new Tensor(name, shape, data));
        }
        return tensors;
    }

    private static Map<String, String> randomMetadata(Random random) {
        Map<String, String> metadata = new LinkedHashMap<>();
        int count = random.nextInt(4);
        for (int i = 0; i < count; i++) {
            metadata.put(randomString(random, random.nextInt(6)), randomString(random, random.nextInt(12)));
        }
        return metadata;
    }

    /** Strings from an alphabet with JSON specials, control characters, non-ASCII and a surrogate pair. */
    private static String randomString(Random random, int length) {
        List<String> alphabet = new ArrayList<>(List.of("a", "Z", "0", ".", "_", " ", "\"", "\\", "/", "\n",
                "\u0000", "\u001f", "\u007f", "ä", "ß", "€", "中", "😀", "{", "}", ":", ","));
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < length; i++) {
            s.append(alphabet.get(random.nextInt(alphabet.size())));
        }
        return s.toString();
    }
}
