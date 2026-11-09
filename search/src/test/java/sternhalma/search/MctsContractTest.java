package sternhalma.search;

import java.util.List;
import java.util.Set;

import sternhalma.search.contract.StrategyContract;

/**
 * The strategy contract (never an illegal move, clean self-play from any legal position, in the rule variants of the
 * contract) for MCTS with a fake network whose outputs are a hash of the position: an arbitrary but fixed policy and
 * value, with noise and sampling on so that the seed matters. Such a network is not expected to beat a random player,
 * so the strategies are flagged as deliberately weak; {@link MctsTrainedNetworkTest} runs the beat-random check with
 * the trained network.
 */
class MctsContractTest extends StrategyContract {

    @Override
    protected List<Strategy> strategies() {
        HashedNetwork net = new HashedNetwork(2, 11, 2.0);
        MctsOptions base = MctsOptions.defaults().withSims(8).withBatch(4).withNoise(0.25).withTemperature(1.0);
        return List.of(
                new Strategy("mcts(hashed net, race=1)", Set.of(2), false,
                        seed -> MctsPlayer.create(net, base, seed, "mcts")),
                new Strategy("mcts(hashed net, race=0, batch 1, reuse)", Set.of(2), false,
                        seed -> MctsPlayer.create(net, base.withRace(0).withBatch(1).withReuse(true), seed, "mcts")),
                new Strategy("mcts(hashed net, no s1, no win1)", Set.of(2), false,
                        seed -> MctsPlayer.create(net, base.withRace(2).withS1(false).withWin1(false), seed, "mcts")));
    }
}
