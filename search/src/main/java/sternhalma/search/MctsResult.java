package sternhalma.search;

/**
 * What one {@link Mcts#search} found.
 *
 * @param move      the chosen move (one of {@code moves})
 * @param moves     the legal moves of the root, in the order of {@code Position.legalMoves()}
 * @param visits    simulations through each move; they add up to {@code sims}
 * @param values    the mean value of each move for the side that plays it, in [-1, 1]; 0 for a move without visits
 * @param rootValue the mean value of the root for its mover after the search
 * @param sims      simulations run (less than the budget only if the time budget ran out; 0 if there was a single
 *                  legal move, which is played without a search)
 * @param nodes     nodes in the tree after the search
 * @param exactLeaves simulations that ended in an exact leaf (game over, S1 verdict) instead of the network
 */
public record MctsResult(int move, int[] moves, int[] visits, double[] values, double rootValue, int sims, int nodes,
        int exactLeaves) {
}
