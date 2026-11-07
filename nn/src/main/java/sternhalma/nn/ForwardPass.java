package sternhalma.nn;

/**
 * A forward pass of the network of docs/network.md, "Layers". {@link Network} is the scalar reference;
 * {@link VectorNetwork} is the Vector API fast path. Every implementation agrees with {@link Network} within
 * the tolerance of {@link Network#agrees}.
 *
 * <p><b>Threads.</b> An implementation either allows concurrent calls of {@link #forward} ({@link #threadSafe()}
 * is true), or it detects a call that starts while another call on the same instance is running and throws
 * {@link IllegalStateException} for it. It never returns wrong results because of concurrent calls. Neither
 * kind allows changing the {@link Parameters} during a call.
 */
public interface ForwardPass {

    /** The size of the network. */
    NetworkConfig config();

    /**
     * Whether {@link #forward} may run in several threads at the same time on this instance. If false, a
     * concurrent call throws {@link IllegalStateException}; use one instance per thread.
     */
    boolean threadSafe();

    /**
     * Forward pass for a batch of encoded positions. Only the first {@code batch} slices of the outputs are
     * written.
     *
     * @param input        {@code batch * Encoder.INPUT_SIZE} floats from {@link Encoder#encode}; row 121 of
     *                     each position must be zero
     * @param batch        number of positions, &gt;= 1
     * @param policyLogits output, {@code batch * Encoder.POLICY_SIZE} floats: [batch][121][2] with "from" in
     *                     channel 0 and "to" in channel 1, canonical cells
     * @param values       output, {@code batch * players} floats: [batch][P], relative players (mover first),
     *                     each in [-1, 1]
     * @throws IllegalArgumentException if batch is less than 1, an array is shorter than stated, or row 121 of
     *                                  an input is not zero
     * @throws NullPointerException     if an array is null
     * @throws IllegalStateException    if the implementation is not {@link #threadSafe()} and another call on
     *                                  this instance is running
     */
    void forward(float[] input, int batch, float[] policyLogits, float[] values);
}
