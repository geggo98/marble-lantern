package sternhalma.search;

import sternhalma.engine.Position;

/** A static evaluation of a non-terminal position, in integer units, from the side to move. */
@FunctionalInterface
public interface Evaluator {
    int evaluate(Position position);
}
