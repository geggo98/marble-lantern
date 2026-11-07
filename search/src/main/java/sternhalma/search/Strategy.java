package sternhalma.search;

import java.util.Set;
import java.util.function.LongFunction;

/**
 * A playing strategy as the contract tests see it.
 *
 * @param name             a readable name used in test reports
 * @param supportedPlayers the player counts the strategy can play (alpha-beta: 2)
 * @param mustBeatRandom   whether the strategy must beat a random player in every game (false for the random
 *                         player itself and for deliberately weak strategies)
 * @param factory          creates a fresh player from a seed; deterministic strategies ignore the seed
 */
public record Strategy(String name, Set<Integer> supportedPlayers, boolean mustBeatRandom, LongFunction<Player> factory) {

    public Strategy {
        supportedPlayers = Set.copyOf(supportedPlayers);
    }

    public boolean supports(int players) {
        return supportedPlayers.contains(players);
    }

    /** A new player; call once per player and game, because players keep search state. */
    public Player create(long seed) {
        return factory.apply(seed);
    }

    @Override
    public String toString() {
        return name;
    }
}
