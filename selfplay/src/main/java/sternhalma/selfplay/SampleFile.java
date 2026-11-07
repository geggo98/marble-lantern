package sternhalma.selfplay;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A safetensors file with the integer dtypes of the sample format (docs/training.md, "Self-play sample files"):
 * an 8-byte little-endian header length, a JSON header (tensor name to dtype, shape and byte offsets, plus
 * {@code __metadata__} with string values), padded with spaces to a multiple of 8, then the raw little-endian
 * tensor bytes in header order. Python's {@code safetensors} and {@code numpy} read it directly.
 *
 * <p>The project's {@code nn.Safetensors} accepts {@code F32} only (a decision of docs/network.md); this class
 * is the typed variant for sample files and does not touch network files. Writes are atomic: a temporary file in
 * the same directory, then a rename.
 */
public final class SampleFile {

    /** The dtypes used by the sample format. */
    public enum DType {
        U8(1), I16(2), I32(4), F32(4);

        final int size;

        DType(int size) {
            this.size = size;
        }
    }

    /** One tensor: dtype, shape and its little-endian bytes. */
    public record Tensor(DType dtype, long[] shape, byte[] data) {

        public Tensor {
            shape = shape.clone();
            long n = 1;
            for (long d : shape) {
                if (d < 0) {
                    throw new IllegalArgumentException("negative dimension in " + Arrays.toString(shape));
                }
                n *= d;
            }
            if (n * dtype.size != data.length) {
                throw new IllegalArgumentException(dtype + Arrays.toString(shape) + " needs " + n * dtype.size
                        + " bytes, got " + data.length);
            }
        }

        public long elements() {
            return data.length / dtype.size;
        }

        public static Tensor u8(byte[] values, long... shape) {
            return new Tensor(DType.U8, shape, values.clone());
        }

        public static Tensor i16(short[] values, long... shape) {
            ByteBuffer b = ByteBuffer.allocate(values.length * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (short v : values) {
                b.putShort(v);
            }
            return new Tensor(DType.I16, shape, b.array());
        }

        public static Tensor i32(int[] values, long... shape) {
            ByteBuffer b = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int v : values) {
                b.putInt(v);
            }
            return new Tensor(DType.I32, shape, b.array());
        }

        public static Tensor f32(float[] values, long... shape) {
            ByteBuffer b = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (float v : values) {
                b.putFloat(v);
            }
            return new Tensor(DType.F32, shape, b.array());
        }

        public byte[] u8() {
            require(DType.U8);
            return data.clone();
        }

        public short[] i16() {
            require(DType.I16);
            ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            short[] out = new short[data.length / 2];
            for (int i = 0; i < out.length; i++) {
                out[i] = b.getShort();
            }
            return out;
        }

        public int[] i32() {
            require(DType.I32);
            ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            int[] out = new int[data.length / 4];
            for (int i = 0; i < out.length; i++) {
                out[i] = b.getInt();
            }
            return out;
        }

        public float[] f32() {
            require(DType.F32);
            ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            float[] out = new float[data.length / 4];
            for (int i = 0; i < out.length; i++) {
                out[i] = b.getFloat();
            }
            return out;
        }

        private void require(DType expected) {
            if (dtype != expected) {
                throw new IllegalStateException("tensor is " + dtype + ", not " + expected);
            }
        }
    }

    /** The contents of a file. */
    public record Contents(Map<String, Tensor> tensors, Map<String, String> metadata) {

        public Tensor tensor(String name) {
            Tensor t = tensors.get(name);
            if (t == null) {
                throw new IllegalArgumentException("no tensor '" + name + "'");
            }
            return t;
        }
    }

    private static final long MAX_HEADER = 100_000_000L;

    private SampleFile() {
    }

    /** The file bytes; tensors in the iteration order of the map. */
    public static byte[] toBytes(Map<String, Tensor> tensors, Map<String, String> metadata) {
        StringBuilder h = new StringBuilder("{");
        h.append(json("__metadata__")).append(":{");
        boolean first = true;
        for (Map.Entry<String, String> e : new TreeMap<>(metadata).entrySet()) {
            h.append(first ? "" : ",").append(json(e.getKey())).append(':').append(json(e.getValue()));
            first = false;
        }
        h.append('}');
        long offset = 0;
        for (Map.Entry<String, Tensor> e : tensors.entrySet()) {
            if (e.getKey().equals("__metadata__")) {
                throw new IllegalArgumentException("reserved tensor name");
            }
            Tensor t = e.getValue();
            h.append(',').append(json(e.getKey())).append(":{\"dtype\":\"").append(t.dtype()).append("\",\"shape\":[");
            for (int i = 0; i < t.shape().length; i++) {
                h.append(i == 0 ? "" : ",").append(t.shape()[i]);
            }
            h.append("],\"data_offsets\":[").append(offset).append(',').append(offset + t.data().length).append("]}");
            offset += t.data().length;
        }
        h.append('}');
        h.append(" ".repeat((8 - utf8(h).length % 8) % 8)); // align the data section to 8 bytes
        byte[] header = utf8(h);
        ByteBuffer out = ByteBuffer.allocate(Math.toIntExact(8 + header.length + offset)).order(ByteOrder.LITTLE_ENDIAN);
        out.putLong(header.length);
        out.put(header);
        for (Tensor t : tensors.values()) {
            out.put(t.data());
        }
        return out.array();
    }

    /** Writes atomically: {@code <file>.tmp.<pid>} in the same directory, then a rename. */
    public static void write(Path file, Map<String, Tensor> tensors, Map<String, String> metadata) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp." + ProcessHandle.current().pid());
        try {
            Files.write(tmp, toBytes(tensors, metadata));
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    public static Contents read(Path file) throws IOException {
        return fromBytes(Files.readAllBytes(file));
    }

    /**
     * Parses and validates a file: header length within the file, offsets contiguous from 0 in header order and
     * covering the data section exactly, sizes equal to dtype size times the shape product.
     *
     * @throws IOException on any violation
     */
    public static Contents fromBytes(byte[] file) throws IOException {
        if (file.length < 8) {
            throw new IOException("file shorter than 8 bytes");
        }
        long headerLength = ByteBuffer.wrap(file, 0, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
        if (headerLength < 2 || headerLength > MAX_HEADER || 8 + headerLength > file.length) {
            throw new IOException("bad header length " + headerLength);
        }
        String header = new String(file, 8, (int) headerLength, StandardCharsets.UTF_8);
        Object parsed;
        try {
            parsed = new Json(header).parseTop();
        } catch (IllegalArgumentException e) {
            throw new IOException("bad header: " + e.getMessage(), e);
        }
        if (!(parsed instanceof Map<?, ?> top)) {
            throw new IOException("header is not an object");
        }
        int dataStart = (int) (8 + headerLength);
        long dataLength = file.length - dataStart;
        Map<String, String> metadata = new LinkedHashMap<>();
        Map<String, Tensor> tensors = new LinkedHashMap<>();
        long expectedOffset = 0;
        for (Map.Entry<?, ?> e : top.entrySet()) {
            String name = (String) e.getKey();
            if (name.equals("__metadata__")) {
                if (!(e.getValue() instanceof Map<?, ?> m)) {
                    throw new IOException("__metadata__ is not an object");
                }
                for (Map.Entry<?, ?> me : m.entrySet()) {
                    if (!(me.getValue() instanceof String s)) {
                        throw new IOException("metadata value of " + me.getKey() + " is not a string");
                    }
                    metadata.put((String) me.getKey(), s);
                }
                continue;
            }
            if (!(e.getValue() instanceof Map<?, ?> desc)) {
                throw new IOException("tensor " + name + " is not an object");
            }
            DType dtype;
            try {
                dtype = DType.valueOf((String) desc.get("dtype"));
            } catch (RuntimeException ex) {
                throw new IOException("tensor " + name + ": unsupported dtype " + desc.get("dtype"), ex);
            }
            List<?> shapeList = (List<?>) desc.get("shape");
            List<?> offsets = (List<?>) desc.get("data_offsets");
            if (shapeList == null || offsets == null || offsets.size() != 2) {
                throw new IOException("tensor " + name + ": missing shape or data_offsets");
            }
            long[] shape = new long[shapeList.size()];
            long n = 1;
            for (int i = 0; i < shape.length; i++) {
                shape[i] = (Long) shapeList.get(i);
                if (shape[i] < 0) {
                    throw new IOException("tensor " + name + ": negative dimension");
                }
                n = Math.multiplyExact(n, shape[i]);
            }
            long begin = (Long) offsets.get(0);
            long end = (Long) offsets.get(1);
            if (begin != expectedOffset || end < begin || end > dataLength) {
                throw new IOException("tensor " + name + ": offsets [" + begin + ", " + end + ") not contiguous or "
                        + "outside the data section of " + dataLength + " bytes");
            }
            if (end - begin != n * dtype.size) {
                throw new IOException("tensor " + name + ": " + (end - begin) + " bytes for " + dtype
                        + Arrays.toString(shape));
            }
            tensors.put(name, new Tensor(dtype, shape,
                    Arrays.copyOfRange(file, dataStart + (int) begin, dataStart + (int) end)));
            expectedOffset = end;
        }
        if (expectedOffset != dataLength) {
            throw new IOException("data section has " + dataLength + " bytes, tensors cover " + expectedOffset);
        }
        return new Contents(tensors, metadata);
    }

    private static byte[] utf8(CharSequence s) {
        return s.toString().getBytes(StandardCharsets.UTF_8);
    }

    static String json(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }

    /** A minimal JSON parser for safetensors headers: objects, arrays, strings, integers. */
    private static final class Json {
        private final String s;
        private int i;

        Json(String s) {
            this.s = s;
        }

        Object parseTop() {
            Object v = value();
            ws();
            if (i != s.length()) {
                throw new IllegalArgumentException("trailing characters at " + i);
            }
            return v;
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        private Object value() {
            ws();
            if (i >= s.length()) {
                throw new IllegalArgumentException("unexpected end");
            }
            char c = s.charAt(i);
            if (c == '{') {
                return object();
            }
            if (c == '[') {
                return array();
            }
            if (c == '"') {
                return string();
            }
            if (c == '-' || Character.isDigit(c)) {
                int start = i++;
                while (i < s.length() && Character.isDigit(s.charAt(i))) {
                    i++;
                }
                return Long.parseLong(s.substring(start, i));
            }
            throw new IllegalArgumentException("unexpected '" + c + "' at " + i);
        }

        private Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (peek() == '}') {
                i++;
                return m;
            }
            while (true) {
                ws();
                String key = string();
                ws();
                expect(':');
                if (m.put(key, value()) != null) {
                    throw new IllegalArgumentException("duplicate key " + key);
                }
                ws();
                if (peek() == ',') {
                    i++;
                } else {
                    expect('}');
                    return m;
                }
            }
        }

        private List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++;
            ws();
            if (peek() == ']') {
                i++;
                return l;
            }
            while (true) {
                l.add(value());
                ws();
                if (peek() == ',') {
                    i++;
                } else {
                    expect(']');
                    return l;
                }
            }
        }

        private String string() {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (true) {
                if (i >= s.length()) {
                    throw new IllegalArgumentException("unterminated string");
                }
                char c = s.charAt(i++);
                if (c == '"') {
                    return b.toString();
                }
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"', '\\', '/' -> b.append(e);
                        case 'n' -> b.append('\n');
                        case 'r' -> b.append('\r');
                        case 't' -> b.append('\t');
                        case 'b' -> b.append('\b');
                        case 'f' -> b.append('\f');
                        case 'u' -> {
                            b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                        default -> throw new IllegalArgumentException("bad escape \\" + e);
                    }
                } else {
                    b.append(c);
                }
            }
        }

        private char peek() {
            if (i >= s.length()) {
                throw new IllegalArgumentException("unexpected end");
            }
            return s.charAt(i);
        }

        private void expect(char c) {
            if (peek() != c) {
                throw new IllegalArgumentException("expected '" + c + "' at " + i + ", got '" + s.charAt(i) + "'");
            }
            i++;
        }
    }
}
