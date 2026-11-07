package sternhalma.search;

import sternhalma.engine.Position;

/**
 * Stage 2 of docs/search-and-evaluation.md for two players: the difference of the summed single-agent
 * distances, seen from the side to move. One table lookup per piece. It ignores blocks and jump bridges.
 */
public final class DistanceEvaluator implements Evaluator {

    /** Score units per step of distance difference. */
    public static final int UNIT = 10;

    @Override
    public int evaluate(Position position) {
        int me = position.sideToMove();
        int opponent = 1 - me;
        return UNIT * (position.distanceSum(opponent) - position.distanceSum(me));
    }
}
