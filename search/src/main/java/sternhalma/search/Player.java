package sternhalma.search;

import sternhalma.engine.Position;

/** Something that chooses a move. */
public interface Player {

    /** A legal move for the side to move. The position has at least one legal move. */
    int choose(Position position);

    String name();
}
