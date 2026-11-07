package sternhalma.search;

import sternhalma.engine.Position;

/**
 * The alpha-beta baseline as a player, searching to a fixed depth, for a time or for a number of nodes.
 * Keeps its transposition table for the whole game and counts nodes and time for the arena report.
 */
public final class AlphaBetaPlayer implements Player {

    private final AlphaBeta search;
    private final String name;
    private final int maxDepth;
    private final long timeLimitMillis;
    private final long maxNodes;
    private long totalNodes;
    private long totalNanos;
    private long moves;

    public AlphaBetaPlayer(int maxDepth) {
        this(new DistanceEvaluator(), maxDepth, Long.MAX_VALUE, Long.MAX_VALUE, 20, "distance");
    }

    public AlphaBetaPlayer(int maxDepth, long timeLimitMillis) {
        this(new DistanceEvaluator(), maxDepth, timeLimitMillis, Long.MAX_VALUE, 20, "distance");
    }

    public AlphaBetaPlayer(Evaluator evaluator, int maxDepth, long timeLimitMillis, long maxNodes, int tableBits,
            String evaluatorName) {
        this.search = new AlphaBeta(evaluator, tableBits, true);
        this.maxDepth = maxDepth;
        this.timeLimitMillis = timeLimitMillis;
        this.maxNodes = maxNodes;
        this.name = "ab[eval=" + evaluatorName + ", depth<=" + maxDepth
                + (timeLimitMillis == Long.MAX_VALUE ? "" : ", " + timeLimitMillis + " ms")
                + (maxNodes == Long.MAX_VALUE ? "" : ", " + maxNodes + " nodes") + "]";
    }

    @Override
    public int choose(Position position) {
        long t0 = System.nanoTime();
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

    /** Wall-clock time spent searching so far. */
    public long totalNanos() {
        return totalNanos;
    }

    /** Number of moves chosen so far. */
    public long moves() {
        return moves;
    }
}
