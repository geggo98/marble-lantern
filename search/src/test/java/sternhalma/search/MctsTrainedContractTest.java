package sternhalma.search;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import sternhalma.nn.ForwardPass;
import sternhalma.nn.Networks;
import sternhalma.nn.Parameters;
import sternhalma.search.contract.StrategyContract;

/**
 * The whole strategy contract, the beat-random property included, for MCTS with the trained generation-0 candidate
 * ({@link MctsTrainedNetworkTest#candidate()}). Without the file or without the Vector API module the list is empty and
 * the properties are skipped (the fake-network contract of {@link MctsContractTest} still runs).
 */
class MctsTrainedContractTest extends StrategyContract {

    @Override
    protected List<Strategy> strategies() {
        Path file = MctsTrainedNetworkTest.candidate();
        if (file == null || !Networks.vectorAvailable()) {
            return List.of();
        }
        ForwardPass net;
        try {
            net = Networks.fastest(Parameters.load(file)); // the properties run one game at a time
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        MctsOptions options = MctsOptions.defaults().withSims(8).withBatch(8);
        return List.of(new Strategy("mcts(candidate, 8 sims)", Set.of(2), true,
                seed -> MctsPlayer.create(net, options, seed, "mcts")));
    }
}
