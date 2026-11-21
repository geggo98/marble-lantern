package sternhalma.webnet;

import org.teavm.jso.JSByRef;
import org.teavm.jso.JSExport;
import sternhalma.engine.Outcome;

/**
 * The surface TeaVM exports to JavaScript, for both targets (Wasm GC and JS): the net-MCTS engine without its forward
 * pass (see {@link WebNetCore}). Control calls return a String and never throw: {@code "ok <fields>"} or
 * {@code "error <code> <message>"}, fields separated by one space, lists by commas, codes of {@link NetFailure}.
 * Bulk data crosses as {@code Float32Array} ({@code float[]} by reference, {@link JSByRef}; the same layout as
 * {@link sternhalma.nn.ForwardPass}). Every String argument is always passed: the TeaVM JavaScript export fails on an
 * omitted one (as for teavm-web).
 *
 * <p>One engine per module instance. The calls of one move, in order:
 * <pre>
 *   create(...)  setWeightsInfo(...)  [setTable(...)]         // once per game or when the variant changes
 *   n = begin(history)                                         // "ok n"
 *   while (n &gt; 0) { x = inputs();  y = network(x);  n = apply(from, to, values) }
 *   best()                                                     // "ok move ..."
 * </pre>
 */
public final class WebNet {
    private static final WebNetCore CORE = new WebNetCore();
    private static final float[] NONE = new float[0];

    private WebNet() {
    }

    /** TeaVM requires a main method; the exports are the entry points. */
    public static void main(String[] args) {
    }

    /**
     * {@code ok}. Sets the variant ({@code jump} {@code "adjacent"} or {@code "long"}, {@code players} {@code "2"} or
     * {@code "3"}), the simulations per move, the leaves per batch, the seed and the options text of
     * {@link WebNetCore#create} (empty for the defaults). Drops the tree. {@code camps} is the foreign-corner rule:
     * {@code "closed"} (also null or empty), {@code "pass-through"} or {@code "open"}; {@link #legalMoves} and
     * {@link #outcome} take it the same way. Always pass it: the TeaVM export fails on an omitted String argument.
     * The last argument of {@link #create}, {@link #legalMoves} and {@link #outcome} is {@code rows}, the board size
     * ({@code "4"}, also null or empty, or {@code "5"}). The two calls that need no net play either size;
     * {@link #create} fails with {@code unsupported} on {@code "5"}, so no search can start on 5 rows.
     */
    @JSExport
    public static String create(String jump, String players, int sims, int batch, int seed, String options, String camps, String rows) {
        try {
            CORE.create(WebNetCore.parseJump(jump), WebNetCore.parsePlayers(players), WebNetCore.parseCamps(camps),
                    WebNetCore.parseRows(rows), sims, batch, seed, options);
            return "ok";
        } catch (RuntimeException e) {
            return error(e);
        }
    }

    /** {@code ok <inputSize> <valueWidth>}; see {@link WebNetCore#setWeightsInfo}. */
    @JSExport
    public static String setWeightsInfo(String planes, int valueWidth) {
        try {
            CORE.setWeightsInfo(planes, valueWidth);
            return "ok " + CORE.inputSize() + " " + CORE.valueWidth();
        } catch (RuntimeException e) {
            return error(e);
        }
    }

    /** {@code ok <states>}. {@code file} is the inflated rows2.bin; only needed for the options {@code s1} and {@code race}. */
    @JSExport
    public static String setTable(@JSByRef(optional = true) byte[] file) {
        try {
            return "ok " + CORE.setTable(file);
        } catch (RuntimeException e) {
            return error(e);
        }
    }

    /** {@code ok <moves>}, the legal moves comma separated (empty when the game is over). */
    @JSExport
    public static String legalMoves(String history, String jump, String players, String camps, String rows) {
        try {
            return "ok " + join(CORE.legalMoves(parse(history), WebNetCore.parseJump(jump), WebNetCore.parsePlayers(players),
                    WebNetCore.parseCamps(camps), WebNetCore.parseRows(rows)));
        } catch (RuntimeException e) {
            return error(e);
        }
    }

    /** {@code ok ongoing}, {@code ok win <side>} or {@code ok draw}, as in teavm-web. */
    @JSExport
    public static String outcome(String history, String jump, String players, String camps, String rows) {
        try {
            Outcome o = CORE.outcome(parse(history), WebNetCore.parseJump(jump), WebNetCore.parsePlayers(players),
                    WebNetCore.parseCamps(camps), WebNetCore.parseRows(rows));
            return o == null ? "ok ongoing" : o.hasWinner() ? "ok win " + o.winner() : "ok draw";
        } catch (RuntimeException e) {
            return error(e);
        }
    }

    /** {@code ok <n>}: the positions to evaluate for the first batch of the move; 0 if {@link #best} is ready. */
    @JSExport
    public static String begin(String history) {
        try {
            return "ok " + CORE.begin(parse(history));
        } catch (RuntimeException e) {
            return error(e);
        }
    }

    /** {@code ok <n>}: the positions still waiting; the same number until {@link #apply}. */
    @JSExport
    public static String prepare() {
        try {
            return "ok " + CORE.prepare();
        } catch (RuntimeException e) {
            return error(e);
        }
    }

    /**
     * The encoded positions of the pending batch: {@code n * inputSize} floats. An empty array if nothing is pending
     * (a caller that follows {@link #begin} and {@link #apply} never sees that).
     */
    @JSExport
    @JSByRef
    public static float[] inputs() {
        try {
            return CORE.inputs();
        } catch (RuntimeException e) {
            return NONE;
        }
    }

    /**
     * {@code ok <n>}: hands in the outputs for the pending batch and returns the positions to evaluate next (0 when
     * the search is finished). {@code policyFrom} and {@code policyTo} have 121 floats per position, {@code values}
     * as many as the value width.
     */
    @JSExport
    public static String apply(@JSByRef float[] policyFrom, @JSByRef float[] policyTo, @JSByRef float[] values) {
        try {
            return "ok " + CORE.apply(policyFrom, policyTo, values);
        } catch (RuntimeException e) {
            return error(e);
        }
    }

    /**
     * {@code ok <move> <rootValue> <sims> <nodes> <exactLeaves> <raceMove 0|1> <moves> <visits> <values>}: the move to play and
     * the statistics of the search; the two lists (legal moves of the root and their visits) are comma separated and
     * empty when no search ran (a single legal move, a race move). A ninth field follows: {@code <values>}, the root
     * value of each of those moves (the mean backed-up value for the player to move, as {@code MctsResult.values}),
     * comma separated in the order of the moves, printed with {@link Double#toString}; empty when no search ran.
     */
    @JSExport
    public static String best() {
        try {
            WebNetCore.Best b = CORE.best();
            return "ok " + b.move() + " " + b.rootValue() + " " + b.sims() + " " + b.nodes() + " " + b.exactLeaves() + " "
                    + (b.raceMove() ? 1 : 0) + " " + join(b.moves()) + " " + join(b.visits()) + " " + join(b.values());
        } catch (RuntimeException e) {
            return error(e);
        }
    }

    static String error(RuntimeException e) {
        String code = e instanceof NetFailure f ? f.code() : "internal";
        String message = String.valueOf(e.getMessage()).replace('\n', ' ');
        return "error " + code + " " + (e instanceof NetFailure ? message : e.getClass().getName() + ": " + message);
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
                throw new NetFailure("illegal-history", "not a move: '" + parts[i] + "'");
            }
        }
        return moves;
    }

    private static String join(double[] values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            sb.append(i == 0 ? "" : ",").append(Double.toString(values[i]));
        }
        return sb.toString();
    }

    private static String join(int[] values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            sb.append(i == 0 ? "" : ",").append(values[i]);
        }
        return sb.toString();
    }
}
