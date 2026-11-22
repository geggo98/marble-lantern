package sternhalma.web;

import org.teavm.jso.JSByRef;
import org.teavm.jso.JSExport;
import sternhalma.engine.Outcome;
import sternhalma.engine.RuleSet;

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
     * needs no table. Every entry point below with a {@code jump} argument takes it the same way, and likewise
     * {@code players}: {@code "2"} (also null or empty) or {@code "3"}. A three-player game needs no table and takes a
     * node budget per seat: {@code nodes0}, {@code nodes1}, {@code nodes2} (a missing or non-positive {@code nodes2} is
     * {@code nodes1}). With two players {@code nodes2} is ignored. Pass every argument: the TeaVM JavaScript export
     * fails on an omitted String argument (measured under node), so a two-player caller passes {@code null} for
     * {@code players} and 0 for {@code nodes2}.
     *
     * <p>The last argument of every entry point is {@code ranking}: {@code "first"} (also null or empty) ends a game at
     * the first winner, {@code "full"} plays on after a first winner so that the others play for the lower ranks (three
     * players; with two players it changes nothing). Like {@code jump} and {@code players} it is always passed, and a
     * game plays the ranking rule of its {@code newGame}.
     *
     * <p>After {@code ranking} comes {@code camps}, the foreign-corner rule (docs/rules.md, "Foreign corners"):
     * {@code "closed"} (also null or empty), {@code "pass-through"} or {@code "open"}. It is always passed as well, and a
     * game plays the rule of its {@code newGame}.
     *
     * <p>The last argument of every entry point is {@code rows}, the board size: {@code "4"} (also null or empty) for the
     * 10-cell houses, {@code "5"} for the 15-cell houses (docs/rules.md, "Board size"). Always passed, and a game plays
     * the size of its {@code newGame}. A 5-row game needs no table and has no race play.
     */
    @JSExport
    public static String newGame(int nodes0, int nodes1, String jump, String players, int nodes2, String ranking,
            String camps, String rows) {
        try {
            if (WebCore.parsePlayers(players) == 3) {
                CORE.newGame3(nodes0, nodes1, nodes2 > 0 ? nodes2 : nodes1, WebCore.parseJump(jump),
                        WebCore.parseRanking(ranking), WebCore.parseCamps(camps), WebCore.parseRows(rows));
            } else {
                CORE.newGame(nodes0, nodes1, WebCore.parseJump(jump), WebCore.parseRanking(ranking),
                        WebCore.parseCamps(camps), WebCore.parseRows(rows));
            }
            return "ok";
        } catch (RuntimeException e) {
            return error(e);
        }
    }

    /** {@code ok <moves>}, the moves comma separated (empty when the game is over). */
    @JSExport
    public static String legalMoves(String history, String jump, String players, String ranking, String camps, String rows) {
        try {
            return "ok " + join(CORE.legalMoves(parse(history), WebCore.parseJump(jump), WebCore.parsePlayers(players),
                    WebCore.parseRanking(ranking), WebCore.parseCamps(camps), WebCore.parseRows(rows)));
        } catch (RuntimeException e) {
            return error(e);
        }
    }

    /** {@code ok <move> <score> <depth> <nodes> <ms> <raceMove 0|1> <rows1Requests>}. */
    @JSExport
    public static String choose(String history, String jump, String players, String ranking, String camps, String rows) {
        try {
            WebCore.Choice c = CORE.choose(parse(history), WebCore.parseJump(jump), WebCore.parsePlayers(players),
                    WebCore.parseRanking(ranking), WebCore.parseCamps(camps), WebCore.parseRows(rows));
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
    public static String analyse(String history, String moves, int nodes, String jump, String players, String ranking,
            String camps, String rows) {
        try {
            WebCore.Analysis a = CORE.analyse(parse(history), moves == null || moves.isEmpty() ? null : parse(moves), nodes,
                    WebCore.parseJump(jump), WebCore.parsePlayers(players), WebCore.parseRanking(ranking),
                    WebCore.parseCamps(camps), WebCore.parseRows(rows));
            return "ok " + a.nodes() + " " + a.ms() + " " + a.rows1Requests() + " " + join(a.moves()) + " " + join(a.scores());
        } catch (RuntimeException e) {
            return error(e);
        }
    }

    /**
     * {@code ok <e0> <e1> <e2>}: the static evaluation of a three-player position, one integer per absolute player,
     * see {@link WebCore#evaluate3}. With {@code ranking} {@code "full"} the seats of players that have finished
     * carry the rank constants documented there. A two-player game has no such vector; use {@link #analyse}.
     */
    @JSExport
    public static String evaluate3(String history, String jump, String ranking, String camps, String rows) {
        try {
            int[] e = CORE.evaluate3(parse(history), WebCore.parseJump(jump), WebCore.parseRanking(ranking),
                    WebCore.parseCamps(camps), WebCore.parseRows(rows));
            return "ok " + e[0] + " " + e[1] + " " + e[2];
        } catch (RuntimeException e) {
            return error(e);
        }
    }

    /**
     * The state of the game. With {@code ranking} {@code "first"}, and with two players whatever {@code ranking} is:
     * {@code ok ongoing}, {@code ok win <side>} (side 0, 1 or 2) or {@code ok draw}. A draw is every end without a
     * winner: the move limit (the rules then score by distance, which names no winner), repetition and a side
     * without legal moves.
     *
     * <p>With {@code ranking} {@code "full"} and three players: {@code ok ongoing} while nobody has finished,
     * {@code ok ongoing finished <mask>} after a first finisher while the others play for second place ({@code mask} is
     * a decimal number, bit p set for player p: 1, 2 or 4), and {@code ok ranking <a> <b> <c>} when the game is over,
     * the three players by rank, first place first. The game is then never a {@code draw}: at the move limit, at a
     * repetition and for a side without legal moves the players that have not finished are ranked by distance to the
     * goal (docs/rules.md, "Playing on for second place").
     */
    @JSExport
    public static String outcome(String history, String jump, String players, String ranking, String camps, String rows) {
        try {
            RuleSet.Ranking rule = WebCore.parseRanking(ranking);
            int playerCount = WebCore.parsePlayers(players);
            WebCore.State s = CORE.state(parse(history), WebCore.parseJump(jump), playerCount, rule,
                    WebCore.parseCamps(camps), WebCore.parseRows(rows));
            Outcome o = s.outcome();
            if (rule == RuleSet.Ranking.FULL_RANKING && playerCount == 3) {
                if (o == null) {
                    return s.finishedMask() == 0 ? "ok ongoing" : "ok ongoing finished " + s.finishedMask();
                }
                int[] r = o.ranking();
                return "ok ranking " + r[0] + " " + r[1] + " " + r[2];
            }
            return o == null ? "ok ongoing" : o.hasWinner() ? "ok win " + o.winner() : "ok draw";
        } catch (RuntimeException e) {
            return error(e);
        }
    }

    /**
     * {@code ok <f0,...,f12> <score> <sideToMove> <d0_0,...,d0_120> <d1_0,...,d1_120>}: the 13 features of
     * {@code linear-r3} (order of {@code LinearEvaluator.GAIN_NAMES}, side to move minus opponent), its score for the side
     * to move, the side to move, and the single-agent distance of every cell for player 0 and for player 1, see
     * {@link WebCore#features}. The features are printed by {@link Double#toString}, which parses back to the same
     * double. A game that is not two players with adjacent jumps and closed corners (on 4 or 5 rows) gives
     * {@code error unsupported ...}.
     */
    @JSExport
    public static String features(String history, String jump, String players, String ranking, String camps, String rows) {
        try {
            WebCore.Features f = CORE.features(parse(history), WebCore.parseJump(jump), WebCore.parsePlayers(players),
                    WebCore.parseRanking(ranking), WebCore.parseCamps(camps), WebCore.parseRows(rows));
            StringBuilder values = new StringBuilder();
            for (int i = 0; i < f.values().length; i++) {
                values.append(i == 0 ? "" : ",").append(Double.toString(f.values()[i]));
            }
            return "ok " + values + " " + f.score() + " " + f.sideToMove() + " " + join(f.distance()[0]) + " "
                    + join(f.distance()[1]);
        } catch (RuntimeException e) {
            return error(e);
        }
    }

    /** {@code ok <moves> <nodes per ply> <ms per ply> <rows1Requests>}, the three lists comma separated. */
    @JSExport
    public static String bench(int plies, int nodes, String jump, String players, String ranking, String camps, String rows) {
        try {
            WebCore.Bench b = CORE.bench(plies, nodes, WebCore.parseJump(jump), WebCore.parsePlayers(players),
                    WebCore.parseRanking(ranking), WebCore.parseCamps(camps), WebCore.parseRows(rows));
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
