package sternhalma.nn;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;

/** Seeded random playouts that give test positions with every mover, also late in the game. */
final class Playouts {

    private Playouts() {
    }

    /**
     * The positions of a few seeded random games, one copy per ply, including the start position. A game stops
     * at its outcome, when the side to move has no legal move, or after {@code maxPlies}.
     */
    static List<Position> positions(int players, int games, int maxPlies, long seed) {
        return positions(players, RuleSet.JumpRule.ADJACENT, games, maxPlies, seed);
    }

    /** The same under the given jump rule. */
    static List<Position> positions(int players, RuleSet.JumpRule jumpRule, int games, int maxPlies, long seed) {
        return positions(players, jumpRule, RuleSet.TriangleRows.FOUR, games, maxPlies, seed);
    }

    /** The same on a board of the given size. */
    static List<Position> positions(int players, RuleSet.JumpRule jumpRule, RuleSet.TriangleRows rows, int games,
            int maxPlies, long seed) {
        List<Position> result = new ArrayList<>();
        Random random = new Random(seed);
        for (int g = 0; g < games; g++) {
            Position position = Position.initial(
                    RuleSet.defaults(players, rows).withMoveLimit(1000).withJumpRule(jumpRule));
            result.add(position.copy());
            for (int ply = 0; ply < maxPlies && position.outcome() == null; ply++) {
                int[] moves = position.legalMoves();
                if (moves.length == 0) {
                    break;
                }
                position.make(moves[random.nextInt(moves.length)]);
                result.add(position.copy());
            }
        }
        return result;
    }

    /**
     * The position as the network should see it, built without {@link Encoder}: every piece of absolute player a
     * on cell c is put for relative player a - mover on the canonical cell of c, and player 0 moves. The result
     * has ply 0.
     */
    static Position canonical(Position position) {
        Orientation orientation = Orientation.of(position);
        Position result = Position.empty(position.rules());
        for (int c = 0; c < HexGrid.CELLS; c++) {
            int owner = position.owner(c);
            if (owner >= 0) {
                result.put(orientation.relativePlayer(owner), orientation.toCanonical(c));
            }
        }
        return result.setSideToMove(0);
    }

    /** The same pieces and side to move at ply 0, so that the progress plane is 0. */
    static Position atPlyZero(Position position) {
        return position.transformed(c -> c, false);
    }
}
