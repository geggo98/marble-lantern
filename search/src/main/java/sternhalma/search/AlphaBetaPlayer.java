package sternhalma.search;

import sternhalma.engine.Position;

/** The alpha-beta baseline with the stage 2 distance evaluation, searching to a fixed depth or time. */
public final class AlphaBetaPlayer implements Player {

    private final AlphaBeta search;
    private final int maxDepth;
    private final long timeLimitMillis;

    public AlphaBetaPlayer(int maxDepth) {
        this(maxDepth, Long.MAX_VALUE);
    }

    public AlphaBetaPlayer(int maxDepth, long timeLimitMillis) {
        this.search = new AlphaBeta(new DistanceEvaluator());
        this.maxDepth = maxDepth;
        this.timeLimitMillis = timeLimitMillis;
    }

    @Override
    public int choose(Position position) {
        return search.search(position, maxDepth, timeLimitMillis).move();
    }

    @Override
    public String name() {
        return "alpha-beta(depth<=" + maxDepth + (timeLimitMillis == Long.MAX_VALUE ? "" : ", " + timeLimitMillis + " ms") + ")";
    }
}
