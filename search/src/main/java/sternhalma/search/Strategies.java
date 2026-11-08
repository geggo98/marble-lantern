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

    /** The search options every BEST of this project has used since round 1. */
    static final String OPTIONS = "pvs=1,killers=1,history=1,lmr=2,asp=1,ttrep=1,mate=1,partial=1";

    /**
     * The BEST of round 1: the learned linear evaluation ({@code eval=linear}, the round-1 weights) with all search
     * enhancements and no race play. It beat the original baseline ({@code ab:eval=distance}) in 198 of 200 games
     * at 15 ms per move. Frozen under this name by the integration commit of round 2, which replaced it as
     * {@link #BEST_SPEC}; it stays registered, so the contract keeps covering it, and the round-2 results against
     * "the old BEST" are reproducible (docs/search-and-evaluation.md, "Integration of round 2").
     */
    public static final String BEST_R1_SPEC = "ab:eval=linear," + OPTIONS;

    /**
     * The wave-1 reference candidate of round 2 (the base of every wave-2 experiment and of the integration
     * matches): the refit weights {@code linear-r2} (E04), futility pruning with E06's margins and race play with the
     * rows&gt;=2 table (E03). Measured, not chosen: the integration matches compared every candidate against it at
     * equal CPU time per move.
     */
    public static final String W1_SPEC = "ab:eval=linear-r2," + OPTIONS + ",fut=1,futk=30,fut2=1,race=1";

    /**
     * The strongest baseline so far, chosen by the integration commit of round 2 (docs/search-and-evaluation.md,
     * "Integration of round 2"): the race arithmetic of E08 ({@code racelin1}, rows&gt;=1 table) as the leaf value
     * over the bestGain evaluator of E07 ({@code linear-r3}), futility pruning at depth 1 and 2 with the gain factor
     * re-tuned for that evaluator (E06, E07), and race play with the rows&gt;=1 table (E03, E09; race play in the
     * BEST is an owner decision). New strategies (a network, MCTS) are measured against it at equal time. Its tree
     * at 20000 nodes is pinned by {@code BestSpecFingerprintTest} and frozen as the reference player R3
     * ({@link ReferencePositions#R3_SPEC}).
     */
    public static final String BEST_SPEC =
            "ab:eval=racelin1-r3," + OPTIONS + ",fut=1,futk=20,fut2=1,race=1,racerows=1";

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
        // the round-1 BEST with race play (round-2 experiment E03, both modes) so that the contract covers RaceMove
        // with the rows>=2 table too; BEST_SPEC itself plays races with the rows>=1 table
        for (String spec : List.of(BEST_SPEC, BEST_R1_SPEC, W1_SPEC, FUTILITY_SPEC, GAIN_SPEC, RACELIN_SPEC,
                "ab:eval=stage4,ws=15,wq=6,wj=3", BEST_R1_SPEC + ",race=1", BEST_R1_SPEC + ",race=2")) {
            String limited = spec + ",nodes=" + CONTRACT_NODES + ",tt=14";
            list.add(new Strategy(limited, Set.of(2), true, seed -> PlayerSpec.parse(limited).create()));
        }
        return List.copyOf(list);
    }
}
