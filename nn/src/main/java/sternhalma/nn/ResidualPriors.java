package sternhalma.nn;

/**
 * The classical priors of {@link PlaneSet#CLASSIC1} that every forward pass adds to the output of the network
 * (docs/network.md, "Classical planes and residual priors"), and the names of their two scalar parameters.
 *
 * <p><b>Policy.</b> The distance gain of a move is {@code DIST[from] - DIST[to]} of the mover. In the policy layout
 * it is a sum of a "from" term and a "to" term, so the prior is added to the logit outputs per cell:
 * {@code policy[c][FROM] += beta * 16 * d(c)} and {@code policy[c][TO] -= beta * 16 * d(c)}, with d the plane
 * {@link Encoder#DIST_OWN} (the distance divided by 16). The logit of a move is then {@code beta * gain + net logit}.
 *
 * <p><b>Value.</b> The value of relative player i is {@code tanh(net_i + k * sign_i * e)}, with e the plane
 * {@link Encoder#EVAL} (the linear evaluation of the mover in logit units), sign 0 = +1, sign 1 = -1 and sign 2 = 0.
 * The linear evaluation is a logit of "the mover wins", so the expected value of a game is {@code 2 p - 1 =
 * tanh(logit / 2)}: {@code k = 0.5} reproduces the classical value, which is the initial value of {@code prior.k}.
 *
 * <p>With these initial values, and the output layers at zero, an untrained network equals the classical prior.
 */
public final class ResidualPriors {

    /** Name of the scalar tensor that scales the distance gain in the policy logits. */
    public static final String BETA = "prior.beta";
    /** Name of the scalar tensor that scales the linear evaluation in the value. */
    public static final String K = "prior.k";
    /** Initial {@code prior.beta}: the policy prior is the plain distance gain. */
    public static final float INITIAL_BETA = 1f;
    /** Initial {@code prior.k}: {@code tanh(logit / 2)} is the expected value of a logistic win probability. */
    public static final float INITIAL_K = 0.5f;

    private ResidualPriors() {
    }

    /**
     * Adds {@code beta * gain} to the policy logits of one position: +{@code beta * 16 * d} to every "from" logit and
     * minus that to every "to" logit.
     *
     * @param input  the encoded position(s), {@code stride} planes per row, of a classical plane set
     * @param stride planes per row of {@code input}
     */
    public static void addPolicy(float beta, float[] input, int inputOffset, int stride, float[] policy,
            int policyOffset) {
        float scale = beta * ClassicalPlanes.DIST_SCALE;
        for (int c = 0; c < HexGrid.CELLS; c++) {
            float term = scale * input[inputOffset + Encoder.index(stride, c, Encoder.DIST_OWN)];
            policy[policyOffset + Encoder.policyIndex(c, Encoder.FROM)] += term;
            policy[policyOffset + Encoder.policyIndex(c, Encoder.TO)] -= term;
        }
    }

    /** The sign of the evaluation term of a relative player: +1 for the mover, -1 for relative player 1, else 0. */
    public static int valueSign(int relativePlayer) {
        return relativePlayer == 0 ? 1 : relativePlayer == 1 ? -1 : 0;
    }

    /**
     * The evaluation of one position seen by a relative player: {@code valueSign * e}, with e read at row 0 (the plane is
     * the same in every cell row). {@code prior.k} times this is added before the tanh.
     */
    public static float valueBase(float[] input, int inputOffset, int stride, int relativePlayer) {
        return valueSign(relativePlayer) * input[inputOffset + Encoder.index(stride, 0, Encoder.EVAL)];
    }
}
