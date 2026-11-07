package sternhalma.nn;

/**
 * The network's view of one position: priors over the legal moves and one value per player.
 *
 * @param moves  the legal engine moves of the position, in the order of {@code Position.generate}; board
 *               cells, not canonical cells; empty if the side to move has no legal move
 * @param priors {@code priors[i]} is the softmax probability of {@code moves[i]} over the legal moves only;
 *               the priors sum to 1 within 1e-6 unless {@code moves} is empty
 * @param values {@code values[a]} is the expected game value for absolute player a, in [-1, 1]
 */
public record Evaluation(int[] moves, float[] priors, float[] values) {
}
