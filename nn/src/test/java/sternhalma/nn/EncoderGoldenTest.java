package sternhalma.nn;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import sternhalma.engine.Position;

/**
 * The encoder output and the files of the plane sets of format versions 1 and 2 did not change when the variant
 * planes came in: SHA-256 over the float bits of seeded random playouts, and over the bytes of seeded files. The
 * expected values were taken from the code before the change (commit 28363f3), not from the code under test.
 */
class EncoderGoldenTest {

    private static final List<PlaneSet> OLD_SETS = List.of(PlaneSet.PLAIN, PlaneSet.CLASSIC1, PlaneSet.CLASSIC1_PLANES);

    private static final Map<String, String> GOLDEN = Map.ofEntries(
            Map.entry("plain 2 encode", "483:b18cb8455bf474a5ab3e19954342485123f18e4343011ceb0f07f0bf8087ebd9"),
            Map.entry("plain 2 expand", "483:9eeeba17f89ddf1e3bee74553601ad4c72a7ef7f4c4bbf1cfc665417fc35a1d5"),
            Map.entry("plain 3 encode", "483:982d3f989b246b28f6436dc490695aa14f3d04917da7faff3ea206ca29ef65a1"),
            Map.entry("plain 3 expand", "483:982d3f989b246b28f6436dc490695aa14f3d04917da7faff3ea206ca29ef65a1"),
            Map.entry("classic1 2 encode", "483:5b36f8fe2989fb7c78169d2dfbf15af4fb06436a58171a051b59b23d32c408ed"),
            Map.entry("classic1 2 expand", "483:099717eb39765356c6b3a18a792dc8e42ba756c5455ae4a55a7507685b6802b5"),
            Map.entry("classic1 3 encode", "483:bb5d233a5bcd3b9c6c1de908ff834637b23d9b33a6a3b7a33ce2c1c03a834ca8"),
            Map.entry("classic1 3 expand", "483:bb5d233a5bcd3b9c6c1de908ff834637b23d9b33a6a3b7a33ce2c1c03a834ca8"),
            Map.entry("classic1-planes 2 encode", "483:5b36f8fe2989fb7c78169d2dfbf15af4fb06436a58171a051b59b23d32c408ed"),
            Map.entry("classic1-planes 2 expand", "483:099717eb39765356c6b3a18a792dc8e42ba756c5455ae4a55a7507685b6802b5"),
            Map.entry("classic1-planes 3 encode", "483:bb5d233a5bcd3b9c6c1de908ff834637b23d9b33a6a3b7a33ce2c1c03a834ca8"),
            Map.entry("classic1-planes 3 expand", "483:bb5d233a5bcd3b9c6c1de908ff834637b23d9b33a6a3b7a33ce2c1c03a834ca8"),
            Map.entry("file plain 2", "7824:8de9056c4491e3a8c933e1ae5f5d9aa25fc170186a94a22e077175c2313de9a1"),
            Map.entry("file plain 3", "7956:debb816c57ab4f23543b6a60f61a78e3c7f22856fdc52cd2c99476ebd4d4d88d"),
            Map.entry("file classic1 2", "9336:762ec43c51b4a58ae0f16e3ca6878b7c3a15834ae29e15d6ef9e5267fd0d7393"),
            Map.entry("file classic1 3", "9468:f38d0f59db8a409b48cf2405d2cbbc5d70c379c46d4e24ee605b24d6877fb408"),
            Map.entry("file classic1-planes 2", "9200:1c0cab2b23b912530ef4814151185aecd2434cbe963105412ed887282b525a38"),
            Map.entry("file classic1-planes 3", "9332:1069fa379859eea3c8f0a8e60bd6babd4755e42e42d4cbfe3d6c10ba7a9bc6fa"));

    private static String hash(PlaneSet planes, int players, long seed, boolean viaExpander) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        List<Position> positions = Playouts.positions(players, 3, 160, seed);
        int n = 0;
        for (Position position : positions) {
            float[] out = new float[planes.inputSize()];
            if (viaExpander) {
                SampleExpander.expand(planes, players, SampleExpanderTest.cellsOf(position), 0, position.ply(),
                        position.rules().moveLimit(), players == 2 && n % 2 == 1, out, 0);
            } else {
                Encoder.encode(planes, position, out, 0);
            }
            ByteBuffer bytes = ByteBuffer.allocate(out.length * 4);
            for (float f : out) {
                bytes.putInt(Float.floatToRawIntBits(f));
            }
            digest.update(bytes.array());
            n++;
        }
        return n + ":" + HexFormat.of().formatHex(digest.digest());
    }

    private static String fileHash(NetworkConfig config) throws Exception {
        byte[] bytes = Safetensors.toBytes(Parameters.uniform(config, 31, 0.4f).tensors(), config.toMetadata());
        return bytes.length + ":" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @Test
    void encoderOutputOfTheOldPlaneSetsIsUnchanged() throws Exception {
        int checked = 0;
        for (PlaneSet planes : OLD_SETS) {
            for (int players = 2; players <= 3; players++) {
                String key = planes.id() + " " + players;
                assertEquals(GOLDEN.get(key + " encode"), hash(planes, players, 700L + players, false), key + " encode");
                assertEquals(GOLDEN.get(key + " expand"), hash(planes, players, 700L + players, true), key + " expand");
                checked += 2;
            }
        }
        assertEquals(12, checked);
    }

    @Test
    void filesOfVersions1And2AreByteForByteUnchanged() throws Exception {
        int checked = 0;
        for (PlaneSet planes : OLD_SETS) {
            for (int players = 2; players <= 3; players++) {
                String key = "file " + planes.id() + " " + players;
                assertEquals(GOLDEN.get(key), fileHash(NetworkConfig.tiny(players).withPlanes(planes)), key);
                checked++;
            }
        }
        assertEquals(6, checked);
    }
}
