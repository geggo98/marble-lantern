package sternhalma.search;

import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

import sternhalma.engine.Position;

/** Plays a uniformly random legal move. A weak opponent for tests and a floor for the strength scale. */
public final class RandomPlayer implements Player {

    private final RandomGenerator random;

    public RandomPlayer(long seed) {
        this.random = RandomGeneratorFactory.of("SplittableRandom").create(seed);
    }

    @Override
    public int choose(Position position) {
        int[] moves = position.legalMoves();
        return moves[random.nextInt(moves.length)];
    }

    @Override
    public String name() {
        return "random";
    }
}
