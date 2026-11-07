package sternhalma.selfplay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sternhalma.selfplay.SampleFile.Tensor;

/** The typed safetensors layout of the sample files. */
class SampleFileTest {

    private static Map<String, Tensor> example() {
        Map<String, Tensor> t = new LinkedHashMap<>();
        t.put("cells", Tensor.u8(new byte[] {0, 1, 2, (byte) 200, 5, 6}, 2, 3));
        t.put("ply", Tensor.i16(new short[] {-3, 200, Short.MAX_VALUE}, 3));
        t.put("offsets", Tensor.i32(new int[] {0, 7, Integer.MIN_VALUE, 123456789}, 4));
        t.put("target", Tensor.f32(new float[] {0.25f, Float.NaN, -1e-7f}, 3, 1));
        t.put("empty", Tensor.u8(new byte[0], 0, 121));
        return t;
    }

    @Test
    void roundTripKeepsDtypesShapesValuesAndMetadata() throws IOException {
        Map<String, String> meta = Map.of("format", "sternhalma-samples", "quote", "a \"b\" \\ c\nd", "ü", "ß");
        SampleFile.Contents c = SampleFile.fromBytes(SampleFile.toBytes(example(), meta));
        assertEquals(meta, c.metadata());
        assertArrayEquals(new byte[] {0, 1, 2, (byte) 200, 5, 6}, c.tensor("cells").u8());
        assertArrayEquals(new long[] {2, 3}, c.tensor("cells").shape());
        assertArrayEquals(new short[] {-3, 200, Short.MAX_VALUE}, c.tensor("ply").i16());
        assertArrayEquals(new int[] {0, 7, Integer.MIN_VALUE, 123456789}, c.tensor("offsets").i32());
        float[] f = c.tensor("target").f32();
        assertEquals(0.25f, f[0]);
        assertTrue(Float.isNaN(f[1]));
        assertEquals(-1e-7f, f[2]);
        assertArrayEquals(new long[] {0, 121}, c.tensor("empty").shape());
        assertEquals(SampleFile.DType.I16, c.tensor("ply").dtype());
        assertThrows(IllegalStateException.class, () -> c.tensor("ply").i32(), "dtype is checked on access");
    }

    @Test
    void layoutIsTheSafetensorsLayout() {
        byte[] file = SampleFile.toBytes(example(), Map.of("k", "v"));
        long n = ByteBuffer.wrap(file, 0, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
        assertEquals(0, (8 + n) % 8, "the data section is 8-byte aligned");
        String header = new String(file, 8, (int) n, StandardCharsets.UTF_8);
        assertTrue(header.startsWith("{\"__metadata__\":{\"k\":\"v\"}"), header);
        assertTrue(header.contains("\"ply\":{\"dtype\":\"I16\",\"shape\":[3],\"data_offsets\":[6,12]}"), header);
        // little-endian I16 at data offset 6: -3 = 0xFFFD
        int data = (int) (8 + n);
        assertEquals((byte) 0xFD, file[data + 6]);
        assertEquals((byte) 0xFF, file[data + 7]);
        assertEquals(data + 6 + 6 + 16 + 12, file.length);
    }

    @Test
    void corruptFilesAreRejected() {
        byte[] good = SampleFile.toBytes(example(), Map.of());
        assertThrows(IOException.class, () -> SampleFile.fromBytes(Arrays.copyOf(good, good.length - 1)),
                "truncated data");
        assertThrows(IOException.class, () -> SampleFile.fromBytes(Arrays.copyOf(good, good.length + 4)),
                "trailing bytes");
        assertThrows(IOException.class, () -> SampleFile.fromBytes(Arrays.copyOf(good, 20)), "truncated header");
        byte[] wrongSize = header("{\"a\":{\"dtype\":\"I32\",\"shape\":[2],\"data_offsets\":[0,6]}}", 6);
        assertThrows(IOException.class, () -> SampleFile.fromBytes(wrongSize));
        byte[] gap = header("{\"a\":{\"dtype\":\"U8\",\"shape\":[2],\"data_offsets\":[1,3]}}", 3);
        assertThrows(IOException.class, () -> SampleFile.fromBytes(gap));
        byte[] dtype = header("{\"a\":{\"dtype\":\"F64\",\"shape\":[1],\"data_offsets\":[0,8]}}", 8);
        assertThrows(IOException.class, () -> SampleFile.fromBytes(dtype));
    }

    private static byte[] header(String json, int dataBytes) {
        byte[] h = json.getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocate(8 + h.length + dataBytes).order(ByteOrder.LITTLE_ENDIAN);
        b.putLong(h.length).put(h);
        return b.array();
    }

    @Test
    void writeIsAtomicAndLeavesNoTemporaryFile(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("samples-1.safetensors");
        SampleFile.write(f, example(), Map.of("x", "1"));
        SampleFile.write(f, example(), Map.of("x", "2"));
        assertEquals("2", SampleFile.read(f).metadata().get("x"));
        try (var s = Files.list(dir)) {
            assertEquals(1, s.count());
        }
    }

    /** Language neutrality: a Python reader with the standard library only (struct, json) decodes the file. */
    @Test
    void pythonStandardLibraryReadsTheFile(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("s.safetensors");
        SampleFile.write(f, example(), Map.of("format", "sternhalma-samples"));
        String script = String.join("\n",
                "import json, struct, sys",
                "b = open(sys.argv[1], 'rb').read()",
                "n = struct.unpack('<Q', b[:8])[0]",
                "h = json.loads(b[8:8 + n])",
                "d = b[8 + n:]",
                "t = h['ply']",
                "lo, hi = t['data_offsets']",
                "ply = struct.unpack('<%dh' % t['shape'][0], d[lo:hi])",
                "t = h['offsets']",
                "lo, hi = t['data_offsets']",
                "off = struct.unpack('<%di' % t['shape'][0], d[lo:hi])",
                "print(h['__metadata__']['format'], list(ply), list(off), h['cells']['dtype'], h['cells']['shape'])");
        Path py = dir.resolve("read.py");
        Files.writeString(py, script);
        Process p;
        try {
            p = new ProcessBuilder("python3", "-I", py.toString(), f.toString()).redirectErrorStream(true).start();
        } catch (IOException e) {
            assumeTrue(false, "python3 not available: " + e.getMessage());
            return;
        }
        assumeTrue(p.waitFor(60, TimeUnit.SECONDS), "python3 did not finish");
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertEquals(0, p.exitValue(), out);
        assertEquals("sternhalma-samples [-3, 200, 32767] [0, 7, -2147483648, 123456789] U8 [2, 3]", out);
    }
}
