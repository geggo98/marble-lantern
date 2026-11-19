package sternhalma.engine;

import java.util.Arrays;

/**
 * The end of a game.
 *
 * <p>{@code values} holds one entry per player and always sums to zero. A win gives the winner +1 and
 * every other player -1/(players-1). A game without a winner gives all zeros with
 * {@code DrawHandling.DRAW}, and a score from the remaining single-agent distances with
 * {@code DrawHandling.SCORE_BY_DISTANCE}.
 *
 * <p>{@code ranking} lists the absolute players by rank, best first. With {@code Ranking.FIRST_WINNER_ONLY} it is
 * just the winner, or empty without a winner. With {@code Ranking.FULL_RANKING} and three players it holds all
 * three players whenever the game is over (docs/rules.md, "Playing on for second place"). Its values are 1, 0 and -1
 * for the ranks, except for the players still unranked at a move limit, a repetition or a blockade, who share the
 * remaining rank values and are ordered by distance. {@code winner} is set only for {@link Reason#WIN}.
 */
public record Outcome(Reason reason, int winner, double[] values, int[] ranking) {

    /** An outcome without a ranking list: the winner alone first, or empty if there is no winner. */
    public Outcome(Reason reason, int winner, double[] values) {
        this(reason, winner, values, winner < 0 ? new int[0] : new int[] {winner});
    }

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

    /** A game that ended with a complete ranking: the first player of {@code ranking} is the winner. */
    static Outcome ranked(Reason reason, int[] ranking, double[] values) {
        return new Outcome(reason, reason == Reason.WIN ? ranking[0] : -1, values, ranking);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Outcome other
                && reason == other.reason
                && winner == other.winner
                && Arrays.equals(values, other.values)
                && Arrays.equals(ranking, other.ranking);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * (31 * reason.hashCode() + winner) + Arrays.hashCode(values)) + Arrays.hashCode(ranking);
    }

    @Override
    public String toString() {
        return "Outcome[" + reason + ", winner=" + winner + ", values=" + Arrays.toString(values)
                + ", ranking=" + Arrays.toString(ranking) + "]";
    }
}
