package sternhalma.tornado;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;

/**
 * Seeded random playouts that give test positions with every mover, also late in the game. The same method as
 * {@code Playouts} in the tests of module nn, which this module cannot see.
 */
final class Playouts {

    private Playouts() {
    }

    /**
     * The positions of a few seeded random games, one copy per ply, including the start position. A game stops at
     * its outcome, when the side to move has no legal move, or after {@code maxPlies}.
     */
    static List<Position> positions(int players, int games, int maxPlies, long seed) {
        return positions(RuleSet.defaults(players).withMoveLimit(1000), games, maxPlies, seed);
    }

    /** As above under the given rules (player count and jump rule). */
    static List<Position> positions(RuleSet rules, int games, int maxPlies, long seed) {
        List<Position> result = new ArrayList<>();
        Random random = new Random(seed);
        for (int g = 0; g < games; g++) {
            Position position = Position.initial(rules);
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

    /** {@code count} positions spread evenly over {@link #positions}. */
    static List<Position> sample(int players, int count, long seed) {
        List<Position> all = positions(players, 3, 200, seed);
        List<Position> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            result.add(all.get((int) ((long) i * all.size() / count)));
        }
        return result;
    }
}
