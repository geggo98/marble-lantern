package sternhalma.nn;

/**
 * Which input planes a network reads and whether it adds the classical priors to its outputs (docs/network.md,
 * "Classical planes and residual priors").
 *
 * <ul>
 *   <li>{@link #PLAIN}: the seven planes of format version 1, no priors; the first version of every network.
 *   <li>{@link #CLASSIC1}: the seven planes plus six classical planes ({@link Encoder#DIST_OWN} to
 *       {@link Encoder#EVAL}), and two learnable scalars, {@code prior.beta} and {@code prior.k}, that add a
 *       distance-gain prior to the policy logits and a linear-evaluation term to the value before the tanh.
 *   <li>{@link #CLASSIC1_PLANES}: the thirteen planes without the priors (an ablation of {@link #CLASSIC1}).
 * </ul>
 *
 * <p>The name is the value of the metadata key {@code planes}.
 */
public enum PlaneSet {

    PLAIN("plain", Encoder.PLANES, false),
    CLASSIC1("classic1", Encoder.CLASSIC_PLANES, true),
    CLASSIC1_PLANES("classic1-planes", Encoder.CLASSIC_PLANES, false);

    private final String id;
    private final int planes;
    private final boolean residualPriors;

    PlaneSet(String id, int planes, boolean residualPriors) {
        this.id = id;
        this.planes = planes;
        this.residualPriors = residualPriors;
    }

    /** The text of the metadata key {@code planes}. */
    public String id() {
        return id;
    }

    /** Input planes per cell. */
    public int planes() {
        return planes;
    }

    /** Floats per encoded position: 122 rows times the planes. */
    public int inputSize() {
        return HexGrid.ROWS * planes;
    }

    /** Whether the planes beyond the seven plain ones are present. */
    public boolean classical() {
        return planes > Encoder.PLANES;
    }

    /** Whether the network has the scalars {@code prior.beta} and {@code prior.k} and adds the priors. */
    public boolean residualPriors() {
        return residualPriors;
    }

    /**
     * The set with the given id.
     *
     * @throws IllegalArgumentException if the text names no set
     */
    public static PlaneSet ofId(String id) {
        for (PlaneSet set : values()) {
            if (set.id.equals(id)) {
                return set;
            }
        }
        throw new IllegalArgumentException("unknown planes: " + id + " (expected plain, classic1 or classic1-planes)");
    }

    @Override
    public String toString() {
        return id;
    }
}
