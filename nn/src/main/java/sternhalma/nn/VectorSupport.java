package sternhalma.nn;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorShape;
import jdk.incubator.vector.VectorSpecies;

/**
 * Access to the float vector species of the running CPU.
 *
 * <p>By default the species is {@link FloatVector#SPECIES_PREFERRED}: 128 bit on Apple silicon (NEON), 256 bit on
 * x86 with AVX2 and, depending on the CPU and on {@code -XX:UseAVX} / {@code -XX:MaxVectorSize}, 256 or 512 bit
 * with AVX-512. The system property {@value #BITS_PROPERTY} ({@code 64}, {@code 128}, {@code 256} or
 * {@code 512}) forces a species. A species wider than the hardware is correct but runs through the Java fallback
 * of the Vector API, many times slower: use it for tests only (docs/architecture.md, "Portability"). The species
 * is read once, when this class loads, because the JIT can compile the vector code only for a constant species.
 */
public final class VectorSupport {

    /** System property that forces the species: the width in bits, one of 64, 128, 256, 512. */
    public static final String BITS_PROPERTY = "sternhalma.vector.bits";

    /** The float species in use: the forced one, else the widest one the CPU supports efficiently. */
    public static final VectorSpecies<Float> SPECIES = select(System.getProperty(BITS_PROPERTY));

    private VectorSupport() {
    }

    /** Number of float lanes per vector. */
    public static int lanes() {
        return SPECIES.length();
    }

    /**
     * The species for a width in bits, or the preferred one for {@code null} or an empty string.
     *
     * @throws IllegalArgumentException for a width other than 64, 128, 256 or 512
     */
    static VectorSpecies<Float> select(String bits) {
        if (bits == null || bits.isBlank()) {
            return FloatVector.SPECIES_PREFERRED;
        }
        int width;
        try {
            width = Integer.parseInt(bits.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(BITS_PROPERTY + "=" + bits + ": not a number", e);
        }
        return switch (width) {
            case 64, 128, 256, 512 -> VectorSpecies.of(float.class, VectorShape.forBitSize(width));
            default -> throw new IllegalArgumentException(BITS_PROPERTY + "=" + bits + ": use 64, 128, 256 or 512");
        };
    }

    /** One line for logs: the species in use, the preferred and the largest one of this JVM. */
    public static String describe() {
        return SPECIES.vectorBitSize() + "-bit vectors (" + SPECIES.length() + " float lanes), preferred "
                + FloatVector.SPECIES_PREFERRED.vectorBitSize() + "-bit, maximum "
                + FloatVector.SPECIES_MAX.vectorBitSize() + "-bit";
    }
}
