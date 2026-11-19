package sternhalma.web;

import org.teavm.jso.JSByRef;
import org.teavm.jso.JSExport;

/**
 * The surface TeaVM exports to JavaScript, for both targets (Wasm GC and JS). Every method returns a String and never
 * throws: a Java exception crossing the boundary reaches the page as an opaque object. The reply is
 * {@code "ok <fields>"} or {@code "error <code> <message>"}, with the code from web/src/engines/protocol.ts.
 * Fields are separated by one space; lists by commas. The page parses them in web/src/engines/java/core.ts.
 *
 * <p>The state lives in one {@link WebCore}. The history is passed as a comma separated list of moves, so no array
 * type crosses the boundary (an empty history is an empty string).
 */
public final class Web {
    private static final WebCore CORE = new WebCore();

    private Web() {
    }

    /** TeaVM requires a main method; the exports are the entry points. */
    public static void main(String[] args) {
    }

    /** {@code ok <states>}. {@code file} is the decompressed rows2.bin; {@code verify} checks the body checksum of its header. */
    @JSExport
    public static String setTable(@JSByRef(optional = true) byte[] file, boolean verify) {
        try {
            return "ok " + CORE.setTable(file, verify);
        } catch (RuntimeException e) {
            return error(e);
        }
    }

    /**
     * {@code ok}. {@code jump} is {@code "adjacent"} or {@code "long"}; null or empty means adjacent. A long-hop game
     * needs no table. Every entry point below with a {@code jump} argument takes it the same way.
     */
    @JSExport
    public static String newGame(int nodes0, int nodes1, String jump) {
        try {
            CORE.newGame(nodes0, nodes1, WebCore.parseJump(jump));
            return "ok";
        } catch (RuntimeException e) {
            return error(e);
        }
    }

    /** {@code ok <moves>}, the moves comma separated (empty when the game is over). */
    @JSExport
    public static String legalMoves(String history, String jump) {
        try {
            return "ok " + join(CORE.legalMoves(parse(history), WebCore.parseJump(jump)));
        } catch (RuntimeException e) {
            return error(e);
        }
    }

    /** {@code ok <move> <score> <depth> <nodes> <ms> <raceMove 0|1> <rows1Requests>}. */
    @JSExport
    public static String choose(String history, String jump) {
        try {
            WebCore.Choice c = CORE.choose(parse(history), WebCore.parseJump(jump));
            return "ok " + c.move() + " " + c.score() + " " + c.depth() + " " + c.nodes() + " " + c.ms() + " "
                    + (c.raceMove() ? 1 : 0) + " " + c.rows1Requests();
        } catch (RuntimeException e) {
            return error(e);
        }
    }

    /**
     * {@code ok <nodes> <ms> <rows1Requests> <moves> <scores>}, the last two comma separated and in the same order.
     * {@code moves} is a comma separated list of the moves to score, or empty for all legal moves.
     */
    @JSExport
    public static String analyse(String history, String moves, int nodes, String jump) {
        try {
            WebCore.Analysis a = CORE.analyse(parse(history), moves == null || moves.isEmpty() ? null : parse(moves), nodes,
                    WebCore.parseJump(jump));
            return "ok " + a.nodes() + " " + a.ms() + " " + a.rows1Requests() + " " + join(a.moves()) + " " + join(a.scores());
        } catch (RuntimeException e) {
            return error(e);
        }
    }

    /** {@code ok <moves> <nodes per ply> <ms per ply> <rows1Requests>}, the three lists comma separated. */
    @JSExport
    public static String bench(int plies, int nodes, String jump) {
        try {
            WebCore.Bench b = CORE.bench(plies, nodes, WebCore.parseJump(jump));
            StringBuilder ms = new StringBuilder();
            for (int i = 0; i < b.ms().length; i++) {
                ms.append(i == 0 ? "" : ",").append(b.ms()[i]);
            }
            StringBuilder nodeList = new StringBuilder();
            for (int i = 0; i < b.nodes().length; i++) {
                nodeList.append(i == 0 ? "" : ",").append(b.nodes()[i]);
            }
            return "ok " + join(b.moves()) + " " + nodeList + " " + ms + " " + b.rows1Requests();
        } catch (RuntimeException e) {
            return error(e);
        }
    }

    static String error(RuntimeException e) {
        String code = e instanceof WebFailure f ? f.code() : "internal";
        String message = String.valueOf(e.getMessage()).replace('\n', ' ');
        return "error " + code + " " + (e instanceof WebFailure ? message : e.getClass().getName() + ": " + message);
    }

    static int[] parse(String history) {
        if (history == null || history.isEmpty()) {
            return new int[0];
        }
        String[] parts = history.split(",");
        int[] moves = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                moves[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                throw new WebFailure("illegal-history", "not a move: '" + parts[i] + "'");
            }
        }
        return moves;
    }

    private static String join(int[] values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            sb.append(i == 0 ? "" : ",").append(values[i]);
        }
        return sb.toString();
    }
}
