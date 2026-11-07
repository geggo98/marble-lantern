package sternhalma.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Every strategy of this module. The strategy contract tests (see the test fixtures) run against this list,
 * so a strategy that is registered here is tested without further work. Other modules (a network player,
 * MCTS) extend the contract with their own list.
 */
public final class Strategies {

    /** Node budget per move for contract tests: small, because the contracts play many games. */
    public static final long CONTRACT_NODES = 1_000;

    private Strategies() {
    }

    public static List<Strategy> registered() {
        List<Strategy> list = new ArrayList<>();
        list.add(new Strategy("random", Set.of(2, 3), false, RandomPlayer::new));
        for (String evaluator : Evaluators.names()) {
            String spec = "ab:eval=" + evaluator + ",nodes=" + CONTRACT_NODES + ",tt=14";
            list.add(new Strategy(spec, Set.of(2), true, seed -> PlayerSpec.parse(spec).create()));
        }
        return List.copyOf(list);
    }
}
