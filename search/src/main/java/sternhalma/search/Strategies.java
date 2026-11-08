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

    /**
     * Round-2 experiment E06: futility pruning at remaining depth 1 and 2 with the refit weights {@code linear-r2}
     * (docs/search-and-evaluation.md, "Futility pruning (E06)"). Registered so that the strategy contract covers
     * the pruning, including its exclusions near a win and near the move limit; it is not the BEST_SPEC, which
     * only an integration commit changes.
     */
    public static final String FUTILITY_SPEC =
            "ab:eval=linear-r2,pvs=1,killers=1,history=1,lmr=2,asp=1,ttrep=1,mate=1,partial=1,fut=1,futk=30,fut2=1";

    /**
     * Round-2 experiment E07: the evaluator {@code linear-r3} (13th feature bestGain) with the futility margins re-tuned
     * on its scale ({@code futk=20}) and race play (docs/search-and-evaluation.md, "bestGain (E07)"). Registered so
     * that the strategy contract covers the new evaluator together with pruning and race play; it is not the
     * BEST_SPEC, which only an integration commit changes.
     */
    public static final String GAIN_SPEC =
            "ab:eval=linear-r3,pvs=1,killers=1,history=1,lmr=2,asp=1,ttrep=1,mate=1,partial=1,fut=1,futk=20,fut2=1,race=1";

    /**
     * Round-2 experiment E08: {@code racelin1} (exact race arithmetic from the rows&gt;=1 table as the leaf value in
     * passed positions, inside the RACE score band) on top of the wave-1 candidate with futility pruning and race
     * play from the same table, the configuration that measured +45 and +49 Elo (docs/search-and-evaluation.md,
     * "Race arithmetic as a leaf value (E08)"). Registered so that the contract covers the race band together with the
     * futility hook ({@link RaceEvaluator#futilitySafe}) and the aspiration rule; the plain {@code racelin} and
     * {@code racelin1} evaluators are covered through the registry. Not the BEST_SPEC, which only an integration
     * commit changes.
     */
    public static final String RACELIN_SPEC = "ab:eval=racelin1,pvs=1,killers=1,history=1,lmr=2,asp=1,ttrep=1,mate=1,"
            + "partial=1,fut=1,futk=30,fut2=1,race=1,racerows=1";

    private Strategies() {
    }

    public static List<Strategy> registered() {
        List<Strategy> list = new ArrayList<>();
        list.add(new Strategy("random", Set.of(2, 3), false, RandomPlayer::new));
        for (String evaluator : Evaluators.names()) {
            String spec = "ab:eval=" + evaluator + ",nodes=" + CONTRACT_NODES + ",tt=14";
            list.add(new Strategy(spec, Set.of(2), true, seed -> PlayerSpec.parse(spec).create()));
        }
        // BEST_SPEC with race play (round-2 experiment E03, both modes) so that the contract covers RaceMove
        for (String spec : List.of(BEST_SPEC, FUTILITY_SPEC, GAIN_SPEC, RACELIN_SPEC, "ab:eval=stage4,ws=15,wq=6,wj=3",
                BEST_SPEC + ",race=1", BEST_SPEC + ",race=2")) {
            String limited = spec + ",nodes=" + CONTRACT_NODES + ",tt=14";
            list.add(new Strategy(limited, Set.of(2), true, seed -> PlayerSpec.parse(limited).create()));
        }
        return List.copyOf(list);
    }
}
