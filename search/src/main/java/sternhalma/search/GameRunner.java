package sternhalma.search;

import sternhalma.engine.Outcome;
import sternhalma.engine.Position;

/** Plays a game between players from a start position. */
public final class GameRunner {

    /** The end of a game and the number of plies played. */
    public record Result(Outcome outcome, int plies) {
    }

    private GameRunner() {
    }

    /** Plays until the game ends. {@code players[i]} controls player i. The start position is not modified. */
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
            if (position.legalMoves().length == 0) {
                return new Result(position.noMovesOutcome(), position.ply());
            }
            position.make(players[position.sideToMove()].choose(position));
        }
    }
}
