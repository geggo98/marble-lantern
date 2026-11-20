package sternhalma.search;

import sternhalma.engine.Position;
import sternhalma.engine.RuleSet.JumpRule;
import sternhalma.engine.RuleSet.TriangleRows;

/**
 * The alpha-beta baseline as a player, searching to a fixed depth, for a time or for a number of nodes.
 * Keeps its transposition table for the whole game and counts nodes and time for the arena report.
 *
 * <p>With a {@link RaceMove} (spec key {@code race}), a passed two-player position is played by the race module
 * when it offers a legal move; such a move costs no search nodes and is counted in {@link #raceMoves()}.
 */
public final class AlphaBetaPlayer implements Player {

    private final AlphaBeta search;
    private final RaceMove race;
    private final String name;
    private final JumpRule jumpRule;
    private final TriangleRows rows;
    private final int maxDepth;
    private final long timeLimitMillis;
    private final long maxNodes;
    private long totalNodes;
    private long totalNanos;
    private long moves;
    private long raceMoves;
    private SearchResult lastResult;

    public AlphaBetaPlayer(int maxDepth) {
        this(new DistanceEvaluator(), maxDepth, Long.MAX_VALUE, Long.MAX_VALUE, 20, "distance");
    }

    public AlphaBetaPlayer(int maxDepth, long timeLimitMillis) {
        this(new DistanceEvaluator(), maxDepth, timeLimitMillis, Long.MAX_VALUE, 20, "distance");
    }

    public AlphaBetaPlayer(Evaluator evaluator, int maxDepth, long timeLimitMillis, long maxNodes, int tableBits,
            String evaluatorName) {
        this(evaluator, maxDepth, timeLimitMillis, maxNodes, tableBits, evaluatorName, SearchOptions.DEFAULT);
    }

    public AlphaBetaPlayer(Evaluator evaluator, int maxDepth, long timeLimitMillis, long maxNodes, int tableBits,
            String evaluatorName, SearchOptions options) {
        this(evaluator, maxDepth, timeLimitMillis, maxNodes, tableBits, evaluatorName, options, null);
    }

    /** @param race race play before the search, or null */
    public AlphaBetaPlayer(Evaluator evaluator, int maxDepth, long timeLimitMillis, long maxNodes, int tableBits,
            String evaluatorName, SearchOptions options, RaceMove race) {
        this(evaluator, maxDepth, timeLimitMillis, maxNodes, tableBits, evaluatorName, options, race, JumpRule.ADJACENT);
    }

    /**
     * @param race     race play before the search, or null; must be null for {@link JumpRule#LONG}
     * @param jumpRule the rule of the games this player plays; {@link #choose} refuses a position of another rule
     */
    public AlphaBetaPlayer(Evaluator evaluator, int maxDepth, long timeLimitMillis, long maxNodes, int tableBits,
            String evaluatorName, SearchOptions options, RaceMove race, JumpRule jumpRule) {
        this(evaluator, maxDepth, timeLimitMillis, maxNodes, tableBits, evaluatorName, options, race, jumpRule,
                TriangleRows.FOUR);
    }

    /**
     * @param rows the house size of the games this player plays; {@link #choose} refuses a position of another size;
     *             must be {@link TriangleRows#FOUR} with race play (the race code knows the 10-cell houses only)
     */
    public AlphaBetaPlayer(Evaluator evaluator, int maxDepth, long timeLimitMillis, long maxNodes, int tableBits,
            String evaluatorName, SearchOptions options, RaceMove race, JumpRule jumpRule, TriangleRows rows) {
        if (rows == TriangleRows.FIVE && race != null) {
            throw new IllegalArgumentException("race play is computed for the 10-cell houses and cannot play rows=5");
        }
        this.rows = rows;
        if (jumpRule == JumpRule.LONG && race != null) {
            throw new IllegalArgumentException("race play is computed for single jumps and cannot play jump=long");
        }
        this.jumpRule = jumpRule;
        this.search = new AlphaBeta(evaluator, tableBits, true, options);
        this.race = race;
        this.maxDepth = maxDepth;
        this.timeLimitMillis = timeLimitMillis;
        this.maxNodes = maxNodes;
        this.name = "ab[eval=" + evaluatorName + ", depth<=" + maxDepth
                + (timeLimitMillis == Long.MAX_VALUE ? "" : ", " + timeLimitMillis + " ms")
                + (maxNodes == Long.MAX_VALUE ? "" : ", " + maxNodes + " nodes")
                + (options.isDefault() ? "" : ", " + options.describe())
                + (jumpRule == JumpRule.LONG ? ", jump=long" : "")
                + (rows == TriangleRows.FIVE ? ", rows=5" : "")
                + (race == null ? "" : ", race=" + race.mode() + (race.usesTable() ? "" : ", racetable=0")
                        + (race != null && race.tableMinRow() == 1 ? ", racerows=1" : "")) + "]";
    }

    /** The race module of this player, or null. */
    public RaceMove race() {
        return race;
    }

    @Override
    public int choose(Position position) {
        if (position.rules().jumpRule() != jumpRule) {
            throw new IllegalArgumentException("player " + name + " plays jump=" + jumpRule.name().toLowerCase(java.util.Locale.ROOT)
                    + " but the position has jump=" + position.rules().jumpRule().name().toLowerCase(java.util.Locale.ROOT));
        }
        if (position.rules().triangleRows() != rows) {
            throw new IllegalArgumentException("player " + name + " plays rows=" + (rows == TriangleRows.FIVE ? 5 : 4)
                    + " but the position has rows=" + (position.rules().triangleRows() == TriangleRows.FIVE ? 5 : 4));
        }
        long t0 = System.nanoTime();
        if (race != null && position.rules().players() == 2) {
            int move = race.choose(position);
            if (move >= 0) {
                totalNanos += System.nanoTime() - t0;
                moves++;
                raceMoves++;
                lastResult = null;
                return move;
            }
        }
        SearchResult result = search.search(position, maxDepth, timeLimitMillis, maxNodes);
        totalNanos += System.nanoTime() - t0;
        totalNodes += result.nodes();
        moves++;
        lastResult = result;
        return result.move();
    }

    /**
     * The search result of the last {@link #choose} call, or null if the race module chose that move (no search,
     * no score) or no move was chosen since the last {@link #clearSearch}. Its score is from the side that moved;
     * classify it with {@link Scores#kind} before using it as a label.
     */
    public SearchResult lastResult() {
        return lastResult;
    }

    /**
     * The band of the last search score ({@link Scores#kind}), or null when the last move came from the race module
     * or no move was chosen since the last {@link #clearSearch}. Label consumers use only {@link Scores.Kind#ORDINARY}
     * and {@link Scores.Kind#MATE} scores; a {@link Scores.Kind#RACE} score (E08's racelin) is "no information".
     */
    public Scores.Kind lastScoreKind() {
        return lastResult == null ? null : Scores.kind(lastResult.score());
    }

    /**
     * Forgets the transposition table, the killers, the history and the last result, so that the next game plays
     * exactly as with a fresh player of the same spec (checked by the self-play tool's tests). The counters
     * ({@link #totalNodes()} and the others) keep counting.
     */
    public void clearSearch() {
        search.clear();
        lastResult = null;
    }

    @Override
    public String name() {
        return name;
    }

    /** Nodes visited in all searches so far. */
    public long totalNodes() {
        return totalNodes;
    }

    /** Wall-clock time spent choosing moves so far. */
    public long totalNanos() {
        return totalNanos;
    }

    /** Number of moves chosen so far. */
    public long moves() {
        return moves;
    }

    /** Number of moves chosen by the race module instead of the search. */
    public long raceMoves() {
        return raceMoves;
    }
}
