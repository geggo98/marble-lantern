package sternhalma.nn;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorSpecies;

/** Access to the preferred float vector species of the running CPU. */
public final class VectorSupport {

    /** The widest float species the CPU supports efficiently. */
    public static final VectorSpecies<Float> SPECIES = FloatVector.SPECIES_PREFERRED;

    private VectorSupport() {
    }

    /** Number of float lanes per vector. */
    public static int lanes() {
        return SPECIES.length();
    }
}
