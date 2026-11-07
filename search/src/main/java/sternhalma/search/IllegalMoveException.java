package sternhalma.search;

import sternhalma.engine.Move;
import sternhalma.engine.Position;

/** A player chose a move that is not legal in the position. This is a bug in the player, never a game result. */
public final class IllegalMoveException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public IllegalMoveException(String playerName, int move, Position position) {
        super("player '" + playerName + "' chose illegal move " + describe(move) + " in position:\n" + position);
    }

    private static String describe(int move) {
        int from = Move.from(move);
        int to = Move.to(move);
        return from < sternhalma.engine.Geometry.CELLS && to < sternhalma.engine.Geometry.CELLS
                ? Move.toString(move) : "raw value " + move;
    }
}
