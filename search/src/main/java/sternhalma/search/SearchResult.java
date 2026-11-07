package sternhalma.search;

import sternhalma.engine.Move;

/**
 * The result of a search.
 *
 * @param move     best move found, or -1 if the side to move has no legal move
 * @param score    score of that move from the side to move, in {@link AlphaBeta} units
 * @param depth    depth of the last completed iteration (0 if none completed)
 * @param nodes    nodes visited in all iterations
 * @param complete whether the last requested depth finished before the time limit
 */
public record SearchResult(int move, int score, int depth, long nodes, boolean complete) {

    @Override
    public String toString() {
        return "SearchResult[move=" + (move < 0 ? "none" : Move.toString(move)) + ", score=" + score
                + ", depth=" + depth + ", nodes=" + nodes + ", complete=" + complete + "]";
    }
}
