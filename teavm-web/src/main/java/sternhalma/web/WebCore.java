package sternhalma.web;

import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.search.AlphaBeta;
import sternhalma.search.AlphaBetaPlayer;
import sternhalma.search.PlayerSpec;
import sternhalma.search.RaceTableSource;
import sternhalma.search.SearchResult;
import sternhalma.search.Strategies;
import sternhalma.tablebase.RaceTable;

/**
 * The engine of the browser: the classical player {@link Strategies#BROWSER_SPEC} over a rows&gt;=2 table that the page
 * hands in as bytes. No file system, no threads, no rows&gt;=1 table: a request for it fails loudly
 * ({@code rows1-requested}) and is counted. Plain Java, so the JVM tests and the TeaVM build run the same code;
 * {@link Web} is the string-based surface that TeaVM exports.
 *
 * <p>The move history is the game state. Every call replays it from the start position, which gives the search the
 * exact position history. One player per side lives for the whole game, so its transposition table carries over;
 * {@link #newGame} replaces both.
 */
public final class WebCore {
    /** Node budget per move when {@link #newGame} was not called. */
    public static final int DEFAULT_NODES = 20_000;

    /** The result of one {@link #choose} call. {@code nodes}, {@code score} and {@code depth} are 0 for a race move. */
    public record Choice(int move, int score, int depth, long nodes, double ms, boolean raceMove, int rows1Requests) {
    }

    /** The result of {@link #bench}: per ply the move, the nodes of that search and its time. */
    public record Bench(int[] moves, long[] nodes, double[] ms, int rows1Requests) {
    }

    /**
     * The result of one {@link #analyse} call: the scored moves and their scores (same order, integers of the search
     * scale, from the point of view of the side that makes the move, higher is better), the nodes of all searches
     * together and the wall time.
     */
    public record Analysis(int[] moves, int[] scores, long nodes, double ms, int rows1Requests) {
    }

    /**
     * The spec of the analysis player: BROWSER_SPEC without race play and with a small transposition table. Race play
     * would play a passed position without a search and so without a score; without it the search scores the position
     * with the exact race arithmetic of the evaluator. The table is cleared before every scored move. Must equal
     * {@code kAnalysisSpecPrefix} of cpp/src/wasm/wasm_api.cpp.
     */
    static final String ANALYSIS_SPEC = Strategies.BROWSER_SPEC.replace(",race=1,racerows=2", "") + ",tt=16";

    private static final int MATE_BOUND = AlphaBeta.MATE - 1000;

    private RaceTable rows2;
    private AlphaBetaPlayer analysisPlayer;
    private int analysisNodes;
    private int rows1Requests;
    private AlphaBetaPlayer[] players;

    /**
     * Installs the table from the decompressed file (header and body). Checks magic, version, region and size;
     * with {@code verify} also the SHA-256 of the body against the one in the file header.
     *
     * @return the number of states
     */
    public long setTable(byte[] file, boolean verify) {
        RaceTable table;
        try {
            table = RaceTable.fromFileBytes(file);
        } catch (UncheckedIOException e) {
            throw tableFailure(e.getCause());
        } catch (IllegalArgumentException e) {
            throw new WebFailure("table-magic", String.valueOf(e.getMessage()));
        }
        if (verify) {
            int states = (int) table.states();
            int bodyAt = file.length - states;
            byte[] expected = Arrays.copyOfRange(file, bodyAt - 32, bodyAt);
            if (!Arrays.equals(Sha256.digest(file, bodyAt, file.length), expected)) {
                throw new WebFailure("table-checksum", "the table body does not match the checksum in its header");
            }
        }
        rows2 = table;
        players = null;
        analysisPlayer = null;
        return table.states();
    }

    private static WebFailure tableFailure(Throwable cause) {
        String text = String.valueOf(cause == null ? null : cause.getMessage());
        if (cause instanceof EOFException) {
            return new WebFailure("table-truncated", "the table file is shorter than its header says");
        }
        if (cause instanceof IOException && text.contains("trailing bytes")) {
            return new WebFailure("table-trailing", text);
        }
        return new WebFailure("table-magic", text);
    }

    /** Starts a game: two new players with the given node budgets (side 0, side 1), empty transposition tables. */
    public void newGame(int nodes0, int nodes1) {
        if (nodes0 <= 0 || nodes1 <= 0) {
            throw new WebFailure("internal", "node budget must be positive: " + nodes0 + ", " + nodes1);
        }
        players = new AlphaBetaPlayer[] {player(nodes0), player(nodes1)};
    }

    /** The legal moves after {@code history}; empty if the game is over. */
    public int[] legalMoves(int[] history) {
        Position position = replay(history);
        return position.outcome() != null ? new int[0] : position.legalMoves();
    }

    /** Searches the position after {@code history} with the player of the side to move. */
    public Choice choose(int[] history) {
        Position position = replay(history);
        if (position.outcome() != null || position.generate(new int[Position.MAX_MOVES]) == 0) {
            throw new WebFailure("illegal-history", "the game is over after " + history.length + " plies");
        }
        if (players == null) {
            newGame(DEFAULT_NODES, DEFAULT_NODES);
        }
        return choose(players[position.sideToMove()], position);
    }

    private Choice choose(AlphaBetaPlayer player, Position position) {
        long t0 = System.nanoTime();
        int move = player.choose(position);
        double ms = (System.nanoTime() - t0) / 1e6;
        SearchResult result = player.lastResult();
        // lastResult() is null when the race module chose the move: no search, no score.
        return result == null
                ? new Choice(move, 0, 0, 0, ms, true, rows1Requests)
                : new Choice(move, result.score(), result.depth(), result.nodes(), ms, false, rows1Requests);
    }

    /**
     * Scores moves of the position after {@code history} for the side to move. {@code moves} lists the moves to score
     * (each must be legal), or is null for all legal moves in the order of {@link #legalMoves}. Each move is made, the
     * child position is searched with {@code nodes} nodes by a dedicated analysis player (not a game player, so the
     * game's own searches stay as they are) and the score is negated, so it is from the point of view of the side that
     * makes the move. A win or loss found by the search is one ply farther from the root than from the child, so mate
     * scores move one step towards 0: a move that wins at once scores {@code AlphaBeta.MATE - 1}, as in a search of the
     * parent position.
     */
    public Analysis analyse(int[] history, int[] moves, int nodes) {
        if (nodes < 1) {
            throw new WebFailure("internal", "node budget must be positive: " + nodes);
        }
        Position position = replay(history);
        int[] legal = position.outcome() != null ? new int[0] : position.legalMoves();
        if (legal.length == 0) {
            throw new WebFailure("illegal-history", "the game is over after " + history.length + " plies");
        }
        int[] chosen = moves == null ? legal : moves;
        for (int move : chosen) {
            if (!contains(legal, move)) {
                throw new WebFailure("illegal-move", "move " + move + " is not legal after " + history.length + " plies");
            }
        }
        if (analysisPlayer == null || analysisNodes != nodes) {
            analysisPlayer = player(ANALYSIS_SPEC + ",nodes=" + nodes);
            analysisNodes = nodes;
        }
        long t0 = System.nanoTime();
        int[] scores = new int[chosen.length];
        long visited = 0;
        for (int i = 0; i < chosen.length; i++) {
            position.make(chosen[i]);
            analysisPlayer.clearSearch();
            analysisPlayer.choose(position);
            position.unmake();
            SearchResult result = analysisPlayer.lastResult();
            int score = result == null ? 0 : -result.score();
            if (score >= MATE_BOUND) {
                score--;
            } else if (score <= -MATE_BOUND) {
                score++;
            }
            scores[i] = score;
            visited += result == null ? 0 : result.nodes();
        }
        double ms = (System.nanoTime() - t0) / 1e6;
        return new Analysis(chosen.clone(), scores, visited, ms, rows1Requests);
    }

    /**
     * The benchmark game: self-play from the start position, one fresh player per side for the whole game, both with
     * {@code nodes} per move. Does not touch the players of the current game.
     */
    public Bench bench(int plies, int nodes) {
        if (plies <= 0 || nodes <= 0) {
            throw new WebFailure("internal", "plies and nodes must be positive: " + plies + ", " + nodes);
        }
        AlphaBetaPlayer[] pair = {player(nodes), player(nodes)};
        Position position = Position.initial(RuleSet.defaults(2));
        List<Choice> played = new ArrayList<>();
        for (int ply = 0; ply < plies; ply++) {
            if (position.outcome() != null) {
                break;
            }
            Choice choice = choose(pair[position.sideToMove()], position);
            position.make(choice.move());
            played.add(choice);
        }
        int n = played.size();
        int[] moves = new int[n];
        long[] nodeCounts = new long[n];
        double[] ms = new double[n];
        for (int i = 0; i < n; i++) {
            moves[i] = played.get(i).move();
            nodeCounts[i] = played.get(i).nodes();
            ms[i] = played.get(i).ms();
        }
        return new Bench(moves, nodeCounts, ms, rows1Requests);
    }

    /** Rows&gt;=1 requests so far (ADR 0001 guard; must stay 0). */
    public int rows1Requests() {
        return rows1Requests;
    }

    private Position replay(int[] history) {
        Position position = Position.initial(RuleSet.defaults(2));
        for (int ply = 0; ply < history.length; ply++) {
            int move = history[ply];
            if (!contains(position.legalMoves(), move)) {
                throw new WebFailure("illegal-history", "move " + move + " at ply " + ply + " is not legal");
            }
            position.make(move);
        }
        return position;
    }

    private static boolean contains(int[] moves, int move) {
        for (int m : moves) {
            if (m == move) {
                return true;
            }
        }
        return false;
    }

    private AlphaBetaPlayer player(int nodes) {
        return player(Strategies.BROWSER_SPEC + ",nodes=" + nodes);
    }

    /** A player of any spec over the installed table; a spec that needs rows>=1 fails with rows1-requested. */
    AlphaBetaPlayer player(String spec) {
        if (rows2 == null) {
            throw new WebFailure("internal", "no table installed");
        }
        RaceTableSource.Table table = new RaceTableSource.Table(rows2, 2);
        RaceTableSource source = RaceTableSource.ofTables(rows -> forRows(table, rows), rows -> forRows(table, rows));
        return PlayerSpec.parse(spec).create(source);
    }

    private RaceTableSource.Table forRows(RaceTableSource.Table table, int rows) {
        if (rows != 2) {
            rows1Requests++;
            throw new WebFailure("rows1-requested", "a rows>=" + rows + " race table was requested; the browser has only rows>=2");
        }
        return table;
    }
}
