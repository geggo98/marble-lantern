package sternhalma.engine;

import java.util.Arrays;

/**
 * The end of a game.
 *
 * <p>{@code values} holds one entry per player and always sums to zero. A win gives the winner +1 and
 * every other player -1/(players-1). A game without a winner gives all zeros with
 * {@code DrawHandling.DRAW}, and a score from the remaining single-agent distances with
 * {@code DrawHandling.SCORE_BY_DISTANCE}.
 */
public record Outcome(Reason reason, int winner, double[] values) {

    public enum Reason {
        WIN,
        MOVE_LIMIT,
        REPETITION,
        /** The player to move has no legal move. */
        NO_MOVES
    }

    public boolean hasWinner() {
        return reason == Reason.WIN;
    }

    /** The value for one player. */
    public double value(int player) {
        return values[player];
    }

    static Outcome win(int winner, int players) {
        double[] v = new double[players];
        Arrays.fill(v, -1.0 / (players - 1));
        v[winner] = 1.0;
        return new Outcome(Reason.WIN, winner, v);
    }

    static Outcome noWinner(Reason reason, double[] values) {
        return new Outcome(reason, -1, values);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Outcome other
                && reason == other.reason
                && winner == other.winner
                && Arrays.equals(values, other.values);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * reason.hashCode() + winner) + Arrays.hashCode(values);
    }

    @Override
    public String toString() {
        return "Outcome[" + reason + ", winner=" + winner + ", values=" + Arrays.toString(values) + "]";
    }
}
