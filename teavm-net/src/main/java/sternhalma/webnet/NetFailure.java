package sternhalma.webnet;

/** A failure with a code the page can read; the facade turns it into an {@code error <code> <message>} reply. */
public final class NetFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String code;

    public NetFailure(String code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * One of: {@code illegal-history} (a move of the history is not legal, or the history is not a list of moves),
     * {@code bad-options} (an option of {@code create} is unknown or has a bad value), {@code not-ready} (a call in
     * the wrong order, for example {@code apply} without {@code prepare}), {@code no-weights-info},
     * {@code bad-weights-info}, {@code bad-outputs} (an array of the wrong length), {@code non-finite} (the
     * network gave NaN or infinite outputs; the search is dropped), {@code table-magic} (not a rows2 table),
     * {@code no-table} (S1 or race play without {@link WebNetCore#setTable}), {@code internal}.
     */
    public String code() {
        return code;
    }
}
