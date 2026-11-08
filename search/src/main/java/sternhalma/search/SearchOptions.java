package sternhalma.search;

/**
 * Optional search enhancements of {@link AlphaBeta}. All default to off, so that {@link #DEFAULT} searches
 * exactly like the original baseline.
 *
 * @param pvs            principal variation search: null windows after the first move, re-search on a fail high
 * @param killers        two killer moves per height, ordered right after the table move
 * @param history        a history table (from, to) that breaks ties of the distance gain in move ordering
 * @param lmr            late move reductions: 0 off, 1 reduces late moves by one ply, 2 by ln(depth) ln(move)/2,
 *                       3 by the more aggressive ln(depth) ln(move)/1.25
 * @param aspiration     aspiration window half-width at the root in score units; 0 off
 * @param depthPreferred two entries per table bucket: one keeps the deeper entry of the current search, one is
 *                       always replaced
 * @param mateFix        store mate scores relative to the node, not to the root, and convert them back on probing
 * @param partial        use the best move of an iteration that the limit interrupted, if at least the first root
 *                       move (the previous best) was searched completely and the score is not a fail low
 * @param futility       futility pruning at remaining depth 1 (round-2 experiment E06): the margin in score units,
 *                       0 off. Not exact: a pruned move is assumed to score at most
 *                       {@code static eval + futilityGain * distance gain + margin}
 * @param futilityGain   score units per step of distance gain in the futility bound (default
 *                       {@value #DEFAULT_FUTILITY_GAIN}); only read when {@code futility} or {@code futility2} is on
 * @param futility2      futility pruning at remaining depth 2: the margin, 0 off
 */
public record SearchOptions(boolean pvs, boolean killers, boolean history, int lmr, int aspiration,
        boolean depthPreferred, boolean mateFix, boolean partial, int futility, int futilityGain, int futility2) {

    /** Default score units per step of distance gain in the futility bound. */
    public static final int DEFAULT_FUTILITY_GAIN = 30;

    public static final SearchOptions DEFAULT = new SearchOptions(false, false, false, 0, 0, false, false, false);

    public SearchOptions(boolean pvs, boolean killers, boolean history, int lmr, int aspiration,
            boolean depthPreferred, boolean mateFix, boolean partial) {
        this(pvs, killers, history, lmr, aspiration, depthPreferred, mateFix, partial, 0, DEFAULT_FUTILITY_GAIN, 0);
    }

    public SearchOptions(boolean pvs, boolean killers, boolean history, int lmr, int aspiration,
            boolean depthPreferred, boolean mateFix) {
        this(pvs, killers, history, lmr, aspiration, depthPreferred, mateFix, false);
    }

    /** Default aspiration half-width: five steps of distance difference. */
    public static final int DEFAULT_ASPIRATION = 5 * DistanceEvaluator.UNIT;

    public SearchOptions {
        if (lmr < 0 || lmr > 3) {
            throw new IllegalArgumentException("lmr must be 0, 1, 2 or 3");
        }
        if (aspiration < 0) {
            throw new IllegalArgumentException("aspiration must be >= 0");
        }
        if (futility < 0 || futility2 < 0 || futilityGain < 0) {
            throw new IllegalArgumentException("fut, fut2 and futk must be >= 0");
        }
        if (futility == 0 && futility2 == 0) {
            futilityGain = DEFAULT_FUTILITY_GAIN; // unused; normalised so that equals() ignores it
        }
    }

    /** These options with futility pruning set (margins 0 = off). */
    public SearchOptions withFutility(int margin1, int gain, int margin2) {
        return new SearchOptions(pvs, killers, history, lmr, aspiration, depthPreferred, mateFix, partial,
                margin1, gain, margin2);
    }

    /**
     * Whether a search with these options has no pruning that makes root scores inexact: futility and late move
     * reductions off. Principal variation search, killers, history, the table and the move ordering keep every
     * score in the window exact; aspiration only concerns {@code search}, which {@code rootScores} does not use.
     */
    public boolean exactRoot() {
        return lmr == 0 && !futilityOn();
    }

    /** These options with futility and late move reductions off: what {@code AlphaBeta.rootScores} requires. */
    public SearchOptions exact() {
        return new SearchOptions(pvs, killers, history, 0, aspiration, depthPreferred, mateFix, partial, 0,
                DEFAULT_FUTILITY_GAIN, 0);
    }

    /** Whether futility pruning is on at any depth. */
    public boolean futilityOn() {
        return futility > 0 || futility2 > 0;
    }

    public boolean isDefault() {
        return equals(DEFAULT);
    }

    /** A short text for player names, e.g. {@code pvs,killers,lmr=1}; empty for the default. */
    public String describe() {
        StringBuilder b = new StringBuilder();
        if (pvs) {
            b.append(",pvs");
        }
        if (killers) {
            b.append(",killers");
        }
        if (history) {
            b.append(",history");
        }
        if (lmr > 0) {
            b.append(",lmr=").append(lmr);
        }
        if (aspiration > 0) {
            b.append(",asp=").append(aspiration);
        }
        if (depthPreferred) {
            b.append(",ttrep");
        }
        if (mateFix) {
            b.append(",mate");
        }
        if (partial) {
            b.append(",partial");
        }
        if (futility > 0) {
            b.append(",fut=").append(futility);
        }
        if (futility2 > 0) {
            b.append(",fut2=").append(futility2);
        }
        if (futilityOn()) {
            b.append(",futk=").append(futilityGain);
        }
        return b.isEmpty() ? "" : b.substring(1);
    }
}
