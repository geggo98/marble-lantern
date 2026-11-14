package sternhalma.nn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import jdk.incubator.vector.FloatVector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;

/**
 * {@link VectorNetwork} with every species width the Vector API offers, against the reference. The species is
 * read once per JVM ({@link VectorSupport#BITS_PROPERTY}), so each width runs in its own process
 * ({@link VectorSpeciesCheck}). A species wider than the hardware (256 and 512 bit on Apple silicon, 512 bit on an
 * AVX2 machine) runs through the Java fallback of the Vector API: slow, but it exercises the generic lane
 * handling and the padding of the tail lanes with the same code as the fast path.
 */
class VectorSpeciesTest {

    private static final String JAVA = Path.of(System.getProperty("java.home"), "bin", "java").toString();

    private static String run(String bits) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(JAVA, "--add-modules", "jdk.incubator.vector"));
        if (bits != null) {
            cmd.add("-D" + VectorSupport.BITS_PROPERTY + "=" + bits);
        }
        cmd.addAll(List.of("-cp", System.getProperty("java.class.path"), "sternhalma.nn.VectorSpeciesCheck"));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String text = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(240, TimeUnit.SECONDS), "timeout for " + bits);
        assertEquals(0, p.exitValue(), "species " + bits + ":\n" + text);
        return text.lines().filter(l -> l.startsWith("OK ")).findFirst().orElseThrow(
                () -> new AssertionError("no result line for " + bits + ":\n" + text));
    }

    @Test
    void everySpeciesWidthAgreesWithTheReference(TestReporter reporter) throws Exception {
        for (int bits : new int[] {64, 128, 256, 512}) {
            String[] result = run(Integer.toString(bits)).split(" ");
            assertEquals(Integer.toString(bits), result[1], "the species in use");
            assertEquals(Integer.toString(bits / 32), result[2], "lanes");
            reporter.publishEntry("species " + bits, "lanes " + result[2] + ", largest error / bound " + result[3]);
        }
    }

    @Test
    void thePreferredSpeciesAgreesWithTheReference(TestReporter reporter) throws Exception {
        String[] result = run(null).split(" ");
        assertEquals(Integer.toString(VectorSupport.SPECIES.vectorBitSize()), result[1]);
        reporter.publishEntry("preferred", VectorSupport.describe() + ", largest error / bound " + result[3]);
    }

    @Test
    void selectParsesWidthsAndRejectsTheRest() {
        assertEquals(256, VectorSupport.select("256").vectorBitSize());
        assertEquals(64, VectorSupport.select(" 64 ").vectorBitSize());
        assertEquals(FloatVector.SPECIES_PREFERRED, VectorSupport.select(null));
        assertEquals(FloatVector.SPECIES_PREFERRED, VectorSupport.select(" "));
        assertThrows(IllegalArgumentException.class, () -> VectorSupport.select("192"));
        assertThrows(IllegalArgumentException.class, () -> VectorSupport.select("wide"));
    }
}
