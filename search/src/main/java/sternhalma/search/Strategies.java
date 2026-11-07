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

    /**
     * The strongest baseline so far: the learned linear evaluation with all search enhancements. New strategies
     * (a network, MCTS) are measured against it at equal time. It beats the original baseline
     * ({@code ab:eval=distance}) in 198 of 200 games at 15 ms per move, which is the resolution limit of the arena.
     */
    public static final String BEST_SPEC =
            "ab:eval=linear,pvs=1,killers=1,history=1,lmr=2,asp=1,ttrep=1,mate=1,partial=1";

    private Strategies() {
    }

    public static List<Strategy> registered() {
        List<Strategy> list = new ArrayList<>();
        list.add(new Strategy("random", Set.of(2, 3), false, RandomPlayer::new));
        for (String evaluator : Evaluators.names()) {
            String spec = "ab:eval=" + evaluator + ",nodes=" + CONTRACT_NODES + ",tt=14";
            list.add(new Strategy(spec, Set.of(2), true, seed -> PlayerSpec.parse(spec).create()));
        }
        for (String spec : List.of(BEST_SPEC, "ab:eval=stage4,ws=15,wq=6,wj=3")) {
            String limited = spec + ",nodes=" + CONTRACT_NODES + ",tt=14";
            list.add(new Strategy(limited, Set.of(2), true, seed -> PlayerSpec.parse(limited).create()));
        }
        return List.copyOf(list);
    }
}
