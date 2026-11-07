package sternhalma.search;

import sternhalma.engine.Position;

/** A static evaluation of a non-terminal position, in integer units, from the side to move. */
@FunctionalInterface
public interface Evaluator {
    int evaluate(Position position);

    /**
     * Whether futility pruning ({@link SearchOptions#futility()}) may cut moves at a node with {@code depth}
     * plies left. The bound assumes that one move changes the value by at most a fixed amount per step of
     * distance gained; an evaluator whose values jump inside {@code depth} plies (round-2 plan E08: a race band
     * once a passed position is reachable) returns false there. The default trusts the bound everywhere.
     */
    default boolean futilitySafe(Position position, int depth) {
        return true;
    }
}
