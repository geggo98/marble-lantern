package sternhalma.web;

import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import sternhalma.engine.Geometry;
import sternhalma.engine.Outcome;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.engine.RuleSet.TriangleRows;
import sternhalma.search.AlphaBeta;
import sternhalma.search.AlphaBetaPlayer;
import sternhalma.search.Evaluators;
import sternhalma.search.LinearEvaluator;
import sternhalma.search.PlayerSpec;
import sternhalma.search.RaceTableSource;
import sternhalma.search.SearchResult;
import sternhalma.search.ThreePlayerPlayer;
import sternhalma.search.ThreePlayerSpec;
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

    /**
     * The analysis spec of the long hop: {@link Strategies#BROWSER_LONG_SPEC} (it has no race parts) with the same small
     * transposition table. Equals {@link #ANALYSIS_SPEC} apart from the evaluator ({@code linear-r3}) and {@code jump=long}.
     */
    static final String ANALYSIS_LONG_SPEC = Strategies.BROWSER_LONG_SPEC + ",tt=16";

    private static final int MATE_BOUND = AlphaBeta.MATE - 1000;

    private RaceTable rows2;
    private AlphaBetaPlayer analysisPlayer;
    private int analysisNodes;
    private ThreePlayerPlayer analysisPlayer3;
    private int analysisNodes3;
    private RuleSet.JumpRule analysisRule3;
    private int rows1Requests;
    private AlphaBetaPlayer[] players;
    private ThreePlayerPlayer[] players3;
    private int gamePlayers = 2;
    private RuleSet.JumpRule gameRule = RuleSet.JumpRule.ADJACENT;
    private RuleSet.Ranking gameRanking = RuleSet.Ranking.FIRST_WINNER_ONLY;
    private RuleSet.ForeignCamps gameCamps = RuleSet.ForeignCamps.CLOSED;
    private RuleSet.JumpRule analysisRule;
    private TriangleRows gameRows = TriangleRows.FOUR;
    private TriangleRows analysisRows = TriangleRows.FOUR;
    private TriangleRows analysisRows3 = TriangleRows.FOUR;

    /**
     * The jump rule of a facade argument: null, empty or {@code adjacent} is {@link RuleSet.JumpRule#ADJACENT},
     * {@code long} is {@link RuleSet.JumpRule#LONG}; anything else fails with {@code internal}.
     */
    public static RuleSet.JumpRule parseJump(String text) {
        if (text == null || text.isEmpty() || text.equals("adjacent")) {
            return RuleSet.JumpRule.ADJACENT;
        }
        if (text.equals("long")) {
            return RuleSet.JumpRule.LONG;
        }
        throw new WebFailure("internal", "jump rule must be adjacent or long, got '" + text + "'");
    }

    /**
     * The player count of a facade argument: null, empty or {@code "2"} is 2, {@code "3"} is 3; anything else fails
     * with {@code internal}.
     */
    public static int parsePlayers(String text) {
        if (text == null || text.isEmpty() || text.equals("2")) {
            return 2;
        }
        if (text.equals("3")) {
            return 3;
        }
        throw new WebFailure("internal", "players must be 2 or 3, got '" + text + "'");
    }

    /**
     * The ranking rule of a facade argument: null, empty or {@code first} is {@link RuleSet.Ranking#FIRST_WINNER_ONLY},
     * {@code full} is {@link RuleSet.Ranking#FULL_RANKING}; anything else fails with {@code internal}. With two players
     * the two values play the same game.
     */
    public static RuleSet.Ranking parseRanking(String text) {
        if (text == null || text.isEmpty() || text.equals("first")) {
            return RuleSet.Ranking.FIRST_WINNER_ONLY;
        }
        if (text.equals("full")) {
            return RuleSet.Ranking.FULL_RANKING;
        }
        throw new WebFailure("internal", "ranking must be first or full, got '" + text + "'");
    }

    private static RuleSet rules(RuleSet.JumpRule jump) {
        return rules(jump, 2);
    }

    private static RuleSet rules(RuleSet.JumpRule jump, int playerCount) {
        return rules(jump, playerCount, RuleSet.Ranking.FIRST_WINNER_ONLY, RuleSet.ForeignCamps.CLOSED);
    }

    private static RuleSet rules(RuleSet.JumpRule jump, int playerCount, RuleSet.Ranking ranking, RuleSet.ForeignCamps camps) {
        return rules(jump, playerCount, ranking, camps, TriangleRows.FOUR);
    }

    private static RuleSet rules(RuleSet.JumpRule jump, int playerCount, RuleSet.Ranking ranking, RuleSet.ForeignCamps camps,
            TriangleRows rows) {
        return RuleSet.defaults(playerCount, rows).withJumpRule(jump).withRanking(ranking).withForeignCamps(camps);
    }

    /**
     * The board size of a facade argument: null, empty or {@code "4"} is {@link TriangleRows#FOUR} (the 10-cell
     * houses), {@code "5"} is {@link TriangleRows#FIVE} (the 15-cell houses); anything else fails with
     * {@code internal}. A start choice stored before the feature has no size and reads as 4 rows.
     */
    public static TriangleRows parseRows(String text) {
        if (text == null || text.isEmpty() || text.equals("4")) {
            return TriangleRows.FOUR;
        }
        if (text.equals("5")) {
            return TriangleRows.FIVE;
        }
        throw new WebFailure("internal", "rows must be 4 or 5, got '" + text + "'");
    }

    /**
     * The foreign-corner rule of a facade argument (docs/rules.md, "Foreign corners"): null, empty or {@code closed} is
     * {@link RuleSet.ForeignCamps#CLOSED}, {@code pass-through} is {@code PASS_THROUGH}, {@code open} is {@code OPEN};
     * anything else fails with {@code internal}.
     */
    public static RuleSet.ForeignCamps parseCamps(String text) {
        if (text == null || text.isEmpty() || text.equals("closed")) {
            return RuleSet.ForeignCamps.CLOSED;
        }
        if (text.equals("pass-through")) {
            return RuleSet.ForeignCamps.PASS_THROUGH;
        }
        if (text.equals("open")) {
            return RuleSet.ForeignCamps.OPEN;
        }
        throw new WebFailure("internal", "camps must be closed, pass-through or open, got '" + text + "'");
    }

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
        players3 = null;
        gamePlayers = 2;
        analysisPlayer = null;
        analysisRule = null;
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
        newGame(nodes0, nodes1, RuleSet.JumpRule.ADJACENT);
    }

    /**
     * Starts a game under the given jump rule. ADJACENT: {@link Strategies#BROWSER_SPEC}, needs the table. LONG:
     * {@link Strategies#BROWSER_LONG_SPEC}, needs no table (race play and the race evaluator are single-jump only), so
     * it works with and without {@link #setTable}.
     */
    public void newGame(int nodes0, int nodes1, RuleSet.JumpRule jump) {
        newGame(nodes0, nodes1, jump, RuleSet.Ranking.FIRST_WINNER_ONLY);
    }

    /** {@link #newGame(int, int, RuleSet.JumpRule)} with a ranking rule; for two players it has no effect on play. */
    public void newGame(int nodes0, int nodes1, RuleSet.JumpRule jump, RuleSet.Ranking ranking) {
        newGame(nodes0, nodes1, jump, ranking, RuleSet.ForeignCamps.CLOSED);
    }

    /** {@link #newGame(int, int, RuleSet.JumpRule, RuleSet.Ranking)} with a foreign-corner rule. */
    public void newGame(int nodes0, int nodes1, RuleSet.JumpRule jump, RuleSet.Ranking ranking, RuleSet.ForeignCamps camps) {
        newGame(nodes0, nodes1, jump, ranking, camps, TriangleRows.FOUR);
    }

    /**
     * {@link #newGame(int, int, RuleSet.JumpRule, RuleSet.Ranking, RuleSet.ForeignCamps)} on a board of the given size.
     * {@link TriangleRows#FIVE} plays the 5-row specs and needs no table.
     */
    public void newGame(int nodes0, int nodes1, RuleSet.JumpRule jump, RuleSet.Ranking ranking, RuleSet.ForeignCamps camps,
            TriangleRows rows) {
        if (nodes0 <= 0 || nodes1 <= 0) {
            throw new WebFailure("internal", "node budget must be positive: " + nodes0 + ", " + nodes1);
        }
        players = new AlphaBetaPlayer[] {player(nodes0, jump, rows), player(nodes1, jump, rows)};
        players3 = null;
        gamePlayers = 2;
        gameRule = jump;
        gameRanking = ranking;
        gameCamps = camps;
        gameRows = rows;
    }

    /**
     * Starts a three-player game: one {@link ThreePlayerPlayer} per seat with the given node budgets, each with its own
     * transposition table. Needs no table; {@link #setTable} is not required and a table that is installed is ignored.
     */
    public void newGame3(int nodes0, int nodes1, int nodes2, RuleSet.JumpRule jump) {
        newGame3(nodes0, nodes1, nodes2, jump, RuleSet.Ranking.FIRST_WINNER_ONLY);
    }

    /** {@link #newGame3(int, int, int, RuleSet.JumpRule)} with a ranking rule: {@code FULL_RANKING} plays on for the lower ranks. */
    public void newGame3(int nodes0, int nodes1, int nodes2, RuleSet.JumpRule jump, RuleSet.Ranking ranking) {
        newGame3(nodes0, nodes1, nodes2, jump, ranking, RuleSet.ForeignCamps.CLOSED);
    }

    /** {@link #newGame3(int, int, int, RuleSet.JumpRule, RuleSet.Ranking)} with a foreign-corner rule. */
    public void newGame3(int nodes0, int nodes1, int nodes2, RuleSet.JumpRule jump, RuleSet.Ranking ranking, RuleSet.ForeignCamps camps) {
        newGame3(nodes0, nodes1, nodes2, jump, ranking, camps, TriangleRows.FOUR);
    }

    /** {@link #newGame3(int, int, int, RuleSet.JumpRule, RuleSet.Ranking, RuleSet.ForeignCamps)} on a board of the given size. */
    public void newGame3(int nodes0, int nodes1, int nodes2, RuleSet.JumpRule jump, RuleSet.Ranking ranking, RuleSet.ForeignCamps camps,
            TriangleRows rows) {
        if (nodes0 <= 0 || nodes1 <= 0 || nodes2 <= 0) {
            throw new WebFailure("internal", "node budget must be positive: " + nodes0 + ", " + nodes1 + ", " + nodes2);
        }
        players3 = new ThreePlayerPlayer[] {player3(nodes0, jump, rows), player3(nodes1, jump, rows), player3(nodes2, jump, rows)};
        players = null;
        gamePlayers = 3;
        gameRule = jump;
        gameRanking = ranking;
        gameCamps = camps;
        gameRows = rows;
    }

    private static ThreePlayerPlayer player3(int nodes, RuleSet.JumpRule jump, TriangleRows rows) {
        boolean five = rows == TriangleRows.FIVE;
        String base = jump == RuleSet.JumpRule.LONG
                ? (five ? Strategies.BROWSER_3P_ROWS5_LONG_SPEC : Strategies.BROWSER_3P_LONG_SPEC)
                : (five ? Strategies.BROWSER_3P_ROWS5_SPEC : Strategies.BROWSER_3P_SPEC);
        return ThreePlayerSpec.parse(base.replace("nodes=20000", "nodes=" + nodes)).create();
    }

    /** The legal moves after {@code history}; empty if the game is over. */
    public int[] legalMoves(int[] history) {
        return legalMoves(history, RuleSet.JumpRule.ADJACENT);
    }

    /** {@link #legalMoves(int[])} under the given jump rule; a move of the history that is illegal under it is {@code illegal-history}. */
    public int[] legalMoves(int[] history, RuleSet.JumpRule jump) {
        return legalMoves(history, jump, 2);
    }

    /** {@link #legalMoves(int[], RuleSet.JumpRule)} for 2 or 3 players. */
    public int[] legalMoves(int[] history, RuleSet.JumpRule jump, int playerCount) {
        return legalMoves(history, jump, playerCount, RuleSet.Ranking.FIRST_WINNER_ONLY);
    }

    /**
     * {@link #legalMoves(int[], RuleSet.JumpRule, int)} with a ranking rule. With {@code FULL_RANKING} and three players
     * a finished player is skipped: the moves are those of the next player who has not finished.
     */
    public int[] legalMoves(int[] history, RuleSet.JumpRule jump, int playerCount, RuleSet.Ranking ranking) {
        return legalMoves(history, jump, playerCount, ranking, RuleSet.ForeignCamps.CLOSED);
    }

    /** {@link #legalMoves(int[], RuleSet.JumpRule, int, RuleSet.Ranking)} with a foreign-corner rule. */
    public int[] legalMoves(int[] history, RuleSet.JumpRule jump, int playerCount, RuleSet.Ranking ranking, RuleSet.ForeignCamps camps) {
        return legalMoves(history, jump, playerCount, ranking, camps, TriangleRows.FOUR);
    }

    /** {@link #legalMoves(int[], RuleSet.JumpRule, int, RuleSet.Ranking, RuleSet.ForeignCamps)} on a board of the given size. */
    public int[] legalMoves(int[] history, RuleSet.JumpRule jump, int playerCount, RuleSet.Ranking ranking, RuleSet.ForeignCamps camps,
            TriangleRows rows) {
        Position position = replay(history, jump, playerCount, ranking, camps, rows);
        return position.outcome() != null ? new int[0] : position.legalMoves();
    }

    /**
     * The state of the game after {@code history}: {@code null} while it goes on, else the {@link Outcome}. A position
     * in which the side to move has no legal move is {@link Position#noMovesOutcome()}. At the move limit the rules
     * score the game by distance, which gives no winner ({@code winner() == -1}); the facade reports that as a draw.
     */
    public Outcome outcome(int[] history, RuleSet.JumpRule jump, int playerCount) {
        return state(history, jump, playerCount, RuleSet.Ranking.FIRST_WINNER_ONLY).outcome();
    }

    /**
     * The state of a game: the {@link Outcome} ({@code null} while the game goes on) and the set of players that have
     * finished, as bits (bit p for player p). The set is 0 unless {@code ranking} is {@code FULL_RANKING} with three
     * players. The outcome of such a game always carries the complete ranking.
     */
    public record State(Outcome outcome, int finishedMask) {
    }

    /** {@link #outcome(int[], RuleSet.JumpRule, int)} with a ranking rule, and the finished set. */
    public State state(int[] history, RuleSet.JumpRule jump, int playerCount, RuleSet.Ranking ranking) {
        return state(history, jump, playerCount, ranking, RuleSet.ForeignCamps.CLOSED);
    }

    /** {@link #state(int[], RuleSet.JumpRule, int, RuleSet.Ranking)} with a foreign-corner rule. */
    public State state(int[] history, RuleSet.JumpRule jump, int playerCount, RuleSet.Ranking ranking, RuleSet.ForeignCamps camps) {
        return state(history, jump, playerCount, ranking, camps, TriangleRows.FOUR);
    }

    /** {@link #state(int[], RuleSet.JumpRule, int, RuleSet.Ranking, RuleSet.ForeignCamps)} on a board of the given size. */
    public State state(int[] history, RuleSet.JumpRule jump, int playerCount, RuleSet.Ranking ranking, RuleSet.ForeignCamps camps,
            TriangleRows rows) {
        Position position = replay(history, jump, playerCount, ranking, camps, rows);
        Outcome outcome = position.outcome();
        if (outcome == null && position.generate(new int[Position.MAX_MOVES]) == 0) {
            outcome = position.noMovesOutcome();
        }
        return new State(outcome, position.finishedMask());
    }

    /** Searches the position after {@code history} with the player of the side to move. */
    public Choice choose(int[] history) {
        return choose(history, players == null ? RuleSet.JumpRule.ADJACENT : gameRule);
    }

    /**
     * Searches under the given jump rule. The rule must be the one of the current game ({@link #newGame}); a game
     * that was not started takes it from this call. A different rule fails with {@code internal}: the players of a game
     * play one rule.
     */
    public Choice choose(int[] history, RuleSet.JumpRule jump) {
        return choose(history, jump, 2);
    }

    /**
     * {@link #choose(int[], RuleSet.JumpRule)} for 2 or 3 players. The player count must be the one of the current game,
     * like the rule. For 3 players the search is {@link ThreePlayerPlayer}; its result has {@code raceMove} false.
     */
    public Choice choose(int[] history, RuleSet.JumpRule jump, int playerCount) {
        return choose(history, jump, playerCount, RuleSet.Ranking.FIRST_WINNER_ONLY);
    }

    /** {@link #choose(int[], RuleSet.JumpRule, int)} with a ranking rule, which must be the one of the current game. */
    public Choice choose(int[] history, RuleSet.JumpRule jump, int playerCount, RuleSet.Ranking ranking) {
        return choose(history, jump, playerCount, ranking, RuleSet.ForeignCamps.CLOSED);
    }

    /**
     * {@link #choose(int[], RuleSet.JumpRule, int, RuleSet.Ranking)} with a foreign-corner rule, which must be the one of
     * the current game.
     */
    public Choice choose(int[] history, RuleSet.JumpRule jump, int playerCount, RuleSet.Ranking ranking, RuleSet.ForeignCamps camps) {
        return choose(history, jump, playerCount, ranking, camps, TriangleRows.FOUR);
    }

    /**
     * {@link #choose(int[], RuleSet.JumpRule, int, RuleSet.Ranking, RuleSet.ForeignCamps)} with a board size, which must
     * be the one of the current game. A call with the wrong size would otherwise play the other board without a word.
     */
    public Choice choose(int[] history, RuleSet.JumpRule jump, int playerCount, RuleSet.Ranking ranking, RuleSet.ForeignCamps camps,
            TriangleRows rows) {
        Position position = replay(history, jump, playerCount, ranking, camps, rows);
        if (position.outcome() != null || position.generate(new int[Position.MAX_MOVES]) == 0) {
            throw new WebFailure("illegal-history", "the game is over after " + history.length + " plies");
        }
        if (players == null && players3 == null) {
            if (playerCount == 3) {
                newGame3(DEFAULT_NODES, DEFAULT_NODES, DEFAULT_NODES, jump, ranking, camps, rows);
            } else {
                newGame(DEFAULT_NODES, DEFAULT_NODES, jump, ranking, camps, rows);
            }
        } else if (gamePlayers != playerCount) {
            throw new WebFailure("internal", "the game has " + gamePlayers + " players, not " + playerCount + "; call newGame first");
        } else if (gameRule != jump) {
            throw new WebFailure("internal", "the game plays the " + gameRule + " rule, not " + jump + "; call newGame first");
        } else if (gameRanking != ranking) {
            throw new WebFailure("internal", "the game plays " + gameRanking + ", not " + ranking + "; call newGame first");
        } else if (gameCamps != camps) {
            throw new WebFailure("internal", "the game plays foreignCamps=" + gameCamps + ", not " + camps + "; call newGame first");
        } else if (gameRows != rows) {
            throw new WebFailure("internal", "the game plays " + gameRows + ", not " + rows + "; call newGame first");
        }
        if (playerCount == 3) {
            return choose3(players3[position.sideToMove()], position);
        }
        return choose(players[position.sideToMove()], position);
    }

    private Choice choose3(ThreePlayerPlayer player, Position position) {
        long t0 = System.nanoTime();
        int move = player.choose(position);
        double ms = (System.nanoTime() - t0) / 1e6;
        SearchResult result = player.lastResult();
        return new Choice(move, result.score(), result.depth(), result.nodes(), ms, false, rows1Requests);
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
        return analyse(history, moves, nodes, RuleSet.JumpRule.ADJACENT);
    }

    /**
     * {@link #analyse(int[], int[], int)} under the given jump rule: LONG uses {@link #ANALYSIS_LONG_SPEC} and needs no
     * table. Independent of the rule of the current game.
     */
    public Analysis analyse(int[] history, int[] moves, int nodes, RuleSet.JumpRule jump) {
        return analyse(history, moves, nodes, jump, 2);
    }

    /**
     * {@link #analyse(int[], int[], int, RuleSet.JumpRule)} for 2 or 3 players. For 3 players the moves are scored by a
     * dedicated {@link ThreePlayerPlayer} (the browser spec with the given node budget, its table cleared before every
     * move): the move is made, the child position is searched for the mover with {@code nodes} nodes, and the score is
     * the paranoid score from the mover's point of view (it needs no negation: see {@link ThreePlayerPlayer#analyseMove}).
     * Needs no table.
     */
    public Analysis analyse(int[] history, int[] moves, int nodes, RuleSet.JumpRule jump, int playerCount) {
        return analyse(history, moves, nodes, jump, playerCount, RuleSet.Ranking.FIRST_WINNER_ONLY);
    }

    /**
     * {@link #analyse(int[], int[], int, RuleSet.JumpRule, int)} with a ranking rule; for two players it changes nothing.
     * With {@code FULL_RANKING} and three players the position may be in the continued phase (a player has finished):
     * the moves are those of the players still in play, and the scores follow the search of the continued phase (first
     * place {@code 1000000 - h}, second place {@code 500000 - h}, last place {@code -(1000000 - h)}, with the same one-ply
     * shift as for mate scores). A game that is over fails with {@code illegal-history}.
     */
    public Analysis analyse(int[] history, int[] moves, int nodes, RuleSet.JumpRule jump, int playerCount,
            RuleSet.Ranking ranking) {
        return analyse(history, moves, nodes, jump, playerCount, ranking, RuleSet.ForeignCamps.CLOSED);
    }

    /** {@link #analyse(int[], int[], int, RuleSet.JumpRule, int, RuleSet.Ranking)} with a foreign-corner rule. */
    public Analysis analyse(int[] history, int[] moves, int nodes, RuleSet.JumpRule jump, int playerCount,
            RuleSet.Ranking ranking, RuleSet.ForeignCamps camps) {
        return analyse(history, moves, nodes, jump, playerCount, ranking, camps, TriangleRows.FOUR);
    }

    /** {@link #analyse(int[], int[], int, RuleSet.JumpRule, int, RuleSet.Ranking, RuleSet.ForeignCamps)} on a board of the given size. */
    public Analysis analyse(int[] history, int[] moves, int nodes, RuleSet.JumpRule jump, int playerCount,
            RuleSet.Ranking ranking, RuleSet.ForeignCamps camps, TriangleRows rows) {
        if (nodes < 1) {
            throw new WebFailure("internal", "node budget must be positive: " + nodes);
        }
        Position position = replay(history, jump, playerCount, ranking, camps, rows);
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
        if (playerCount == 3) {
            return analyse3(position, chosen, nodes, jump, rows);
        }
        if (analysisPlayer == null || analysisNodes != nodes || analysisRule != jump || analysisRows != rows) {
            String base = jump == RuleSet.JumpRule.LONG ? analysisLongSpec(rows) : analysisSpec(rows);
            analysisPlayer = player(base + ",nodes=" + nodes, jump, rows);
            analysisNodes = nodes;
            analysisRule = jump;
            analysisRows = rows;
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

    // analysisSpec and analysisLongSpec are instance methods on purpose: as static methods the TeaVM JavaScript build
    // read ANALYSIS_SPEC as null (a 4-row adjacent analyse failed with "NumberFormatException: 1null", measured
    // 2026-11-20), while the JVM tests passed.

    /** The analysis spec of a board size: on 5 rows the 5-row browser spec (it has no race parts) with the small table. */
    String analysisSpec(TriangleRows rows) {
        return rows == TriangleRows.FIVE ? Strategies.BROWSER_ROWS5_SPEC + ",tt=16" : ANALYSIS_SPEC;
    }

    /** {@link #analysisSpec} for the long hop. */
    String analysisLongSpec(TriangleRows rows) {
        return rows == TriangleRows.FIVE ? Strategies.BROWSER_ROWS5_LONG_SPEC + ",tt=16" : ANALYSIS_LONG_SPEC;
    }

    private Analysis analyse3(Position position, int[] chosen, int nodes, RuleSet.JumpRule jump, TriangleRows rows) {
        if (analysisPlayer3 == null || analysisNodes3 != nodes || analysisRule3 != jump || analysisRows3 != rows) {
            analysisPlayer3 = player3(nodes, jump, rows);
            analysisNodes3 = nodes;
            analysisRule3 = jump;
            analysisRows3 = rows;
        }
        long t0 = System.nanoTime();
        int[] scores = new int[chosen.length];
        long visited = 0;
        for (int i = 0; i < chosen.length; i++) {
            SearchResult result = analysisPlayer3.analyseMove(position, chosen[i]);
            scores[i] = result.score();
            visited += result.nodes();
        }
        double ms = (System.nanoTime() - t0) / 1e6;
        return new Analysis(chosen.clone(), scores, visited, ms, rows1Requests);
    }

    /**
     * The static evaluation of the position after {@code history} for a three-player game: three integers, one per
     * absolute player (0, 1, 2), each the leaf score of the three-player search for that player (see
     * {@link ThreePlayerPlayer#evaluation}). No search, no table; a finished game is evaluated like any position.
     */
    public int[] evaluate3(int[] history, RuleSet.JumpRule jump) {
        return evaluate3(history, jump, RuleSet.Ranking.FIRST_WINNER_ONLY);
    }

    /**
     * {@link #evaluate3(int[], RuleSet.JumpRule)} with a ranking rule. With {@code FULL_RANKING} the seat of a player
     * that has finished carries a rank constant instead of an evaluation: {@code 1000000} for first place,
     * {@code 500000} for second place and {@code -1000000} for the player that is left last once the ranking is
     * complete (the search's mate, second-place and loss scores at height 0). The seats of the players still in play hold the evaluation against
     * the active opponents (see {@link ThreePlayerPlayer#evaluation}).
     */
    public int[] evaluate3(int[] history, RuleSet.JumpRule jump, RuleSet.Ranking ranking) {
        return evaluate3(history, jump, ranking, RuleSet.ForeignCamps.CLOSED);
    }

    /** {@link #evaluate3(int[], RuleSet.JumpRule, RuleSet.Ranking)} with a foreign-corner rule. */
    public int[] evaluate3(int[] history, RuleSet.JumpRule jump, RuleSet.Ranking ranking, RuleSet.ForeignCamps camps) {
        return evaluate3(history, jump, ranking, camps, TriangleRows.FOUR);
    }

    /** {@link #evaluate3(int[], RuleSet.JumpRule, RuleSet.Ranking, RuleSet.ForeignCamps)} on a board of the given size. */
    public int[] evaluate3(int[] history, RuleSet.JumpRule jump, RuleSet.Ranking ranking, RuleSet.ForeignCamps camps,
            TriangleRows rows) {
        Position position = replay(history, jump, 3, ranking, camps, rows);
        if (analysisPlayer3 == null || analysisRule3 != jump || analysisRows3 != rows) {
            analysisPlayer3 = player3(analysisNodes3 > 0 ? analysisNodes3 : DEFAULT_NODES, jump, rows);
            analysisNodes3 = analysisNodes3 > 0 ? analysisNodes3 : DEFAULT_NODES;
            analysisRule3 = jump;
            analysisRows3 = rows;
        }
        return analysisPlayer3.evaluation(position);
    }

    /**
     * The result of {@link #features}: the {@link LinearEvaluator#GAIN_FEATURES} features of {@code linear-r3} in the
     * order of {@link LinearEvaluator#GAIN_NAMES} (side to move minus opponent), the {@code linear-r3} score for the side
     * to move, the side to move, and {@code distance[p][cell]}, the single-agent distance of a piece of player p on that
     * cell to p's goal corner ({@link sternhalma.engine.Layout#distance}).
     */
    public record Features(double[] values, int score, int sideToMove, int[][] distance) {
    }

    /**
     * The static evaluation of the position after {@code history} by {@code linear-r3}, term by term: the explanation of
     * the evaluation for the Info pages. Two players, adjacent jumps and closed foreign corners, on 4 or 5 rows (the
     * ranking rule changes nothing for two players), else {@code unsupported}. On 5 rows these are the features of the
     * 15-piece layout, with {@code dsumPhase} on the 10-piece scale: the evaluation that {@code BROWSER_ROWS5_SPEC}
     * plays with. No search and no table. The position
     * is replayed into a state of its own and the evaluator is a fresh one, so no game player, analysis player or
     * transposition table is touched. A finished game is evaluated like any position.
     */
    public Features features(int[] history, RuleSet.JumpRule jump, int playerCount, RuleSet.Ranking ranking,
            RuleSet.ForeignCamps camps, TriangleRows rows) {
        if (playerCount != 2 || jump != RuleSet.JumpRule.ADJACENT || camps != RuleSet.ForeignCamps.CLOSED) {
            throw new WebFailure("unsupported", "the evaluation features exist for the classic game only (two players, "
                    + "adjacent jumps, closed corners, 4 or 5 rows), not " + playerCount + " players, " + jump + ", " + rows
                    + ", " + camps);
        }
        Position position = replay(history, jump, playerCount, ranking, camps, rows);
        double[] values = new double[LinearEvaluator.GAIN_FEATURES];
        LinearEvaluator.features(position, values, LinearEvaluator.GainMode.BOTH);
        // linear-r3 reads no race table; the source only keeps Evaluators away from the ambient (system property) one
        RaceTableSource none = RaceTableSource.ofTables(race -> noTable(), race -> noTable());
        int score = Evaluators.byName("linear-r3", Map.of(), none).evaluate(position);
        int[][] distance = new int[2][Geometry.CELLS];
        for (int p = 0; p < 2; p++) {
            for (int cell = 0; cell < Geometry.CELLS; cell++) {
                distance[p][cell] = position.layout().distance(p, cell);
            }
        }
        return new Features(values, score, position.sideToMove(), distance);
    }

    /**
     * The benchmark game: self-play from the start position, one fresh player per side for the whole game, both with
     * {@code nodes} per move. Does not touch the players of the current game.
     */
    public Bench bench(int plies, int nodes) {
        return bench(plies, nodes, RuleSet.JumpRule.ADJACENT);
    }

    /** {@link #bench(int, int)} under the given jump rule. */
    public Bench bench(int plies, int nodes, RuleSet.JumpRule jump) {
        return bench(plies, nodes, jump, 2);
    }

    /** {@link #bench(int, int, RuleSet.JumpRule)} for 2 or 3 players; three players need no table. */
    public Bench bench(int plies, int nodes, RuleSet.JumpRule jump, int playerCount) {
        return bench(plies, nodes, jump, playerCount, RuleSet.Ranking.FIRST_WINNER_ONLY);
    }

    /**
     * {@link #bench(int, int, RuleSet.JumpRule, int)} with a ranking rule. With {@code FULL_RANKING} the self-play goes
     * on after the first finisher until the ranking is complete or the plies are used up.
     */
    public Bench bench(int plies, int nodes, RuleSet.JumpRule jump, int playerCount, RuleSet.Ranking ranking) {
        return bench(plies, nodes, jump, playerCount, ranking, RuleSet.ForeignCamps.CLOSED);
    }

    /** {@link #bench(int, int, RuleSet.JumpRule, int, RuleSet.Ranking)} with a foreign-corner rule. */
    public Bench bench(int plies, int nodes, RuleSet.JumpRule jump, int playerCount, RuleSet.Ranking ranking, RuleSet.ForeignCamps camps) {
        return bench(plies, nodes, jump, playerCount, ranking, camps, TriangleRows.FOUR);
    }

    /** {@link #bench(int, int, RuleSet.JumpRule, int, RuleSet.Ranking, RuleSet.ForeignCamps)} on a board of the given size. */
    public Bench bench(int plies, int nodes, RuleSet.JumpRule jump, int playerCount, RuleSet.Ranking ranking, RuleSet.ForeignCamps camps,
            TriangleRows rows) {
        if (plies <= 0 || nodes <= 0) {
            throw new WebFailure("internal", "plies and nodes must be positive: " + plies + ", " + nodes);
        }
        AlphaBetaPlayer[] pair = playerCount == 3 ? null : new AlphaBetaPlayer[] {player(nodes, jump, rows), player(nodes, jump, rows)};
        ThreePlayerPlayer[] trio = playerCount == 3
                ? new ThreePlayerPlayer[] {player3(nodes, jump, rows), player3(nodes, jump, rows), player3(nodes, jump, rows)} : null;
        Position position = Position.initial(rules(jump, playerCount, ranking, camps, rows));
        List<Choice> played = new ArrayList<>();
        for (int ply = 0; ply < plies; ply++) {
            if (position.outcome() != null) {
                break;
            }
            Choice choice = playerCount == 3
                    ? choose3(trio[position.sideToMove()], position)
                    : choose(pair[position.sideToMove()], position);
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

    private Position replay(int[] history, RuleSet.JumpRule jump) {
        return replay(history, jump, 2);
    }

    private Position replay(int[] history, RuleSet.JumpRule jump, int playerCount) {
        return replay(history, jump, playerCount, RuleSet.Ranking.FIRST_WINNER_ONLY, RuleSet.ForeignCamps.CLOSED, TriangleRows.FOUR);
    }

    private Position replay(int[] history, RuleSet.JumpRule jump, int playerCount, RuleSet.Ranking ranking, RuleSet.ForeignCamps camps,
            TriangleRows rows) {
        Position position = Position.initial(rules(jump, playerCount, ranking, camps, rows));
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

    private AlphaBetaPlayer player(int nodes, RuleSet.JumpRule jump, TriangleRows rows) {
        boolean five = rows == TriangleRows.FIVE;
        String base = jump == RuleSet.JumpRule.LONG
                ? (five ? Strategies.BROWSER_ROWS5_LONG_SPEC : Strategies.BROWSER_LONG_SPEC)
                : (five ? Strategies.BROWSER_ROWS5_SPEC : Strategies.BROWSER_SPEC);
        return player(base + ",nodes=" + nodes, jump, rows);
    }

    /** A player of any spec over the installed table; a spec that needs rows>=1 fails with rows1-requested. */
    AlphaBetaPlayer player(String spec) {
        return player(spec, RuleSet.JumpRule.ADJACENT);
    }

    /**
     * A player of the given spec. For LONG no table is needed or touched: the source fails with {@code internal} if a
     * long-hop spec asks for one (PlayerSpec already rejects such specs). For ADJACENT the table must be installed.
     */
    AlphaBetaPlayer player(String spec, RuleSet.JumpRule jump) {
        return player(spec, jump, TriangleRows.FOUR);
    }

    /**
     * {@link #player(String, RuleSet.JumpRule)} for a board size. On 5 rows no race table exists (the race module knows
     * the 10-cell houses only), so no table is needed or touched, as for the long hop.
     */
    AlphaBetaPlayer player(String spec, RuleSet.JumpRule jump, TriangleRows rows) {
        if (jump == RuleSet.JumpRule.LONG || rows == TriangleRows.FIVE) {
            RaceTableSource none = RaceTableSource.ofTables(race -> noTable(), race -> noTable());
            return PlayerSpec.parse(spec).create(none);
        }
        if (rows2 == null) {
            throw new WebFailure("internal", "no table installed");
        }
        RaceTableSource.Table table = new RaceTableSource.Table(rows2, 2);
        RaceTableSource source = RaceTableSource.ofTables(race -> forRows(table, race), race -> forRows(table, race));
        return PlayerSpec.parse(spec).create(source);
    }

    private static RaceTableSource.Table noTable() {
        throw new WebFailure("internal", "a race table was requested by a long-hop or 5-row player");
    }

    private RaceTableSource.Table forRows(RaceTableSource.Table table, int rows) {
        if (rows != 2) {
            rows1Requests++;
            throw new WebFailure("rows1-requested", "a rows>=" + rows + " race table was requested; the browser has only rows>=2");
        }
        return table;
    }
}
