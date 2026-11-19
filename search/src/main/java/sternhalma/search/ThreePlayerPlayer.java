package sternhalma.search;

import java.util.Locale;

import sternhalma.engine.Position;
import sternhalma.engine.RuleSet.JumpRule;

/**
 * The classical three-player player: {@link ThreePlayerSearch} (paranoid alpha-beta) on a node, depth or time
 * budget. Built from an {@code ab3:} text by {@link ThreePlayerSpec}. It keeps a transposition table for the whole
 * game and needs no move history: replay the game on a {@link Position} with {@code make} and call {@link #choose}.
 * The seat is the side to move of the position it is asked about, so one player object may play every seat.
 */
public final class ThreePlayerPlayer implements Player {

    private final ThreePlayerSearch search;
    private final String name;
    private final JumpRule jumpRule;
    private final int maxDepth;
    private final long timeLimitMillis;
    private final long maxNodes;
    private long totalNodes;
    private long totalNanos;
    private long moves;
    private SearchResult lastResult;

    ThreePlayerPlayer(double[] weights, String evaluatorName, int maxDepth, long timeLimitMillis, long maxNodes,
            int tableBits, JumpRule jumpRule) {
        this.search = new ThreePlayerSearch(weights, tableBits);
        this.jumpRule = jumpRule;
        this.maxDepth = maxDepth;
        this.timeLimitMillis = timeLimitMillis;
        this.maxNodes = maxNodes;
        this.name = "ab3[eval=" + evaluatorName + ", depth<=" + maxDepth
                + (timeLimitMillis == Long.MAX_VALUE ? "" : ", " + timeLimitMillis + " ms")
                + (maxNodes == Long.MAX_VALUE ? "" : ", " + maxNodes + " nodes")
                + (jumpRule == JumpRule.LONG ? ", jump=long" : "") + "]";
    }

    @Override
    public int choose(Position position) {
        return analyse(position).move();
    }

    /**
     * Searches and returns the full result (move, paranoid score from the mover's view, depth, nodes). Counts like
     * {@link #choose}, which calls it.
     */
    public SearchResult analyse(Position position) {
        if (position.rules().players() != 3) {
            throw new IllegalArgumentException("player " + name + " plays three players");
        }
        if (position.rules().jumpRule() != jumpRule) {
            throw new IllegalArgumentException("player " + name + " plays jump=" + jumpRule.name().toLowerCase(Locale.ROOT)
                    + " but the position has jump=" + position.rules().jumpRule().name().toLowerCase(Locale.ROOT));
        }
        long t0 = System.nanoTime();
        SearchResult result = search.search(position, maxDepth, timeLimitMillis, maxNodes);
        totalNanos += System.nanoTime() - t0;
        totalNodes += result.nodes();
        moves++;
        lastResult = result;
        return result;
    }

    /** The result of the last {@link #choose} or {@link #analyse}, or null. */
    public SearchResult lastResult() {
        return lastResult;
    }

    public long totalNodes() {
        return totalNodes;
    }

    public long totalNanos() {
        return totalNanos;
    }

    public long moves() {
        return moves;
    }

    @Override
    public String name() {
        return name;
    }
}
