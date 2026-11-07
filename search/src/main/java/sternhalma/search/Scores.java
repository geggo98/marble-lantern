package sternhalma.search;

/**
 * Score bands of {@link AlphaBeta} (round-2 plan, label policy). Consumers that turn a search score into a label or
 * a value target must ask {@link #kind(int)} first and ignore {@link Kind#RACE} scores.
 *
 * <ul>
 *   <li>{@link Kind#MATE}: {@code |score| >= AlphaBeta.MATE_BOUND}, a forced win or loss inside the search tree;</li>
 *   <li>{@link Kind#RACE}: {@code RACE_BASE <= |score| < MATE_BOUND}, reserved for exact race arithmetic as a leaf
 *       value (experiment E08, which owns the constant). Such values come from region tables (upper bounds) and
 *       ignore blockades, so they must never become labels. The band also holds the scores of leaves at the move
 *       limit whose distance value is at least 0.5 ({@code value * END_SCALE}); a consumer that ignores the band
 *       therefore also ignores those, which errs on the safe side;</li>
 *   <li>{@link Kind#ORDINARY}: everything else, the evaluator's own scale.</li>
 * </ul>
 */
public final class Scores {

    /** Lower edge of the race band; below {@code MATE_BOUND - 200 * 20} and above twice the aspiration cap. */
    public static final int RACE_BASE = 5000;

    /** The band a score lies in. */
    public enum Kind { ORDINARY, RACE, MATE }

    private Scores() {
    }

    /** The band of a score of {@link AlphaBeta}, from either side's point of view. */
    public static Kind kind(int score) {
        int a = Math.abs(score);
        if (a >= AlphaBeta.MATE_BOUND) {
            return Kind.MATE;
        }
        return a >= RACE_BASE ? Kind.RACE : Kind.ORDINARY;
    }
}
