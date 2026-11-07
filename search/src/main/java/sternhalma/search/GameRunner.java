package sternhalma.search;

import sternhalma.engine.Outcome;
import sternhalma.engine.Position;

/** Plays a game between players from a start position and rejects illegal moves. */
public final class GameRunner {

    /** The end of a game and the number of plies played. */
    public record Result(Outcome outcome, int plies) {
    }

    private GameRunner() {
    }

    /**
     * Plays until the game ends. {@code players[i]} controls player i. The start position is not modified.
     *
     * @throws IllegalMoveException if a player returns a move that is not legal
     */
    public static Result play(Position start, Player... players) {
        if (players.length != start.rules().players()) {
            throw new IllegalArgumentException("need " + start.rules().players() + " players, got " + players.length);
        }
        Position position = start.copy();
        while (true) {
            Outcome outcome = position.outcome();
            if (outcome != null) {
                return new Result(outcome, position.ply());
            }
            int[] legal = position.legalMoves();
            if (legal.length == 0) {
                return new Result(position.noMovesOutcome(), position.ply());
            }
            Player player = players[position.sideToMove()];
            int move = player.choose(position);
            if (!contains(legal, move)) {
                throw new IllegalMoveException(player.name(), move, position);
            }
            position.make(move);
        }
    }

    private static boolean contains(int[] moves, int move) {
        for (int m : moves) {
            if (m == move) {
                return true;
            }
        }
        return false;
    }
}
