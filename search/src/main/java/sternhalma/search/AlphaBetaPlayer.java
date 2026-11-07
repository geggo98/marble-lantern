package sternhalma.search;

import sternhalma.engine.Position;

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
    private final int maxDepth;
    private final long timeLimitMillis;
    private final long maxNodes;
    private long totalNodes;
    private long totalNanos;
    private long moves;
    private long raceMoves;

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
        this.search = new AlphaBeta(evaluator, tableBits, true, options);
        this.race = race;
        this.maxDepth = maxDepth;
        this.timeLimitMillis = timeLimitMillis;
        this.maxNodes = maxNodes;
        this.name = "ab[eval=" + evaluatorName + ", depth<=" + maxDepth
                + (timeLimitMillis == Long.MAX_VALUE ? "" : ", " + timeLimitMillis + " ms")
                + (maxNodes == Long.MAX_VALUE ? "" : ", " + maxNodes + " nodes")
                + (options.isDefault() ? "" : ", " + options.describe())
                + (race == null ? "" : ", race=" + race.mode() + (race.usesTable() ? "" : ", racetable=0")) + "]";
    }

    @Override
    public int choose(Position position) {
        long t0 = System.nanoTime();
        if (race != null && position.rules().players() == 2) {
            int move = race.choose(position);
            if (move >= 0) {
                totalNanos += System.nanoTime() - t0;
                moves++;
                raceMoves++;
                return move;
            }
        }
        SearchResult result = search.search(position, maxDepth, timeLimitMillis, maxNodes);
        totalNanos += System.nanoTime() - t0;
        totalNodes += result.nodes();
        moves++;
        return result.move();
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
