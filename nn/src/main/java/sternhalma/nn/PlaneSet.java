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
 *   <li>{@link #CLASSIC1V}: the thirteen planes of {@link #CLASSIC1} plus two constant planes {@link Encoder#LONG}
 *       and {@link Encoder#THREE_PLAYERS}, the same priors; one network for two and three players under both jump
 *       rules (format version 3, docs/network.md, "Format version 3 (variants)").
 * </ul>
 *
 * <p>The name is the value of the metadata key {@code planes}.
 */
public enum PlaneSet {

    PLAIN("plain", Encoder.PLANES, false),
    CLASSIC1("classic1", Encoder.CLASSIC_PLANES, true),
    CLASSIC1_PLANES("classic1-planes", Encoder.CLASSIC_PLANES, false),
    CLASSIC1V("classic1v", Encoder.VARIANT_PLANES, true);

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

    /** Whether the planes {@link Encoder#LONG} and {@link Encoder#THREE_PLAYERS} are present: {@link #CLASSIC1V}. */
    public boolean variants() {
        return planes == Encoder.VARIANT_PLANES;
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
        throw new IllegalArgumentException("unknown planes: " + id + " (expected plain, classic1, classic1-planes or classic1v)");
    }

    @Override
    public String toString() {
        return id;
    }
}
