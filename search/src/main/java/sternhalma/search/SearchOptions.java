package sternhalma.search;

/**
 * Optional search enhancements of {@link AlphaBeta}. All default to off, so that {@link #DEFAULT} searches
 * exactly like the original baseline.
 *
 * @param pvs            principal variation search: null windows after the first move, re-search on a fail high
 * @param killers        two killer moves per height, ordered right after the table move
 * @param history        a history table (from, to) that breaks ties of the distance gain in move ordering
 * @param lmr            late move reductions: 0 off, 1 reduces late moves by one ply, 2 by a log formula
 * @param aspiration     aspiration window half-width at the root in score units; 0 off
 * @param depthPreferred two entries per table bucket: one keeps the deeper entry of the current search, one is
 *                       always replaced
 * @param mateFix        store mate scores relative to the node, not to the root, and convert them back on probing
 * @param partial        use the best move of an iteration that the limit interrupted, if at least the first root
 *                       move (the previous best) was searched completely and the score is not a fail low
 */
public record SearchOptions(boolean pvs, boolean killers, boolean history, int lmr, int aspiration,
        boolean depthPreferred, boolean mateFix, boolean partial) {

    public static final SearchOptions DEFAULT = new SearchOptions(false, false, false, 0, 0, false, false, false);

    public SearchOptions(boolean pvs, boolean killers, boolean history, int lmr, int aspiration,
            boolean depthPreferred, boolean mateFix) {
        this(pvs, killers, history, lmr, aspiration, depthPreferred, mateFix, false);
    }

    /** Default aspiration half-width: five steps of distance difference. */
    public static final int DEFAULT_ASPIRATION = 5 * DistanceEvaluator.UNIT;

    public SearchOptions {
        if (lmr < 0 || lmr > 2) {
            throw new IllegalArgumentException("lmr must be 0, 1 or 2");
        }
        if (aspiration < 0) {
            throw new IllegalArgumentException("aspiration must be >= 0");
        }
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
        return b.isEmpty() ? "" : b.substring(1);
    }
}
