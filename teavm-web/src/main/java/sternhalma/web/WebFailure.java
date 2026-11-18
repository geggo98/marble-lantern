package sternhalma.web;

/** A failure with a protocol code (web/src/engines/protocol.ts, ErrorCode); the facade turns it into a reply the page can read. */
public final class WebFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String code;

    public WebFailure(String code, String message) {
        super(message);
        this.code = code;
    }

    /** One of: table-checksum, table-truncated, table-trailing, table-magic, illegal-history, rows1-requested, internal. */
    public String code() {
        return code;
    }
}
