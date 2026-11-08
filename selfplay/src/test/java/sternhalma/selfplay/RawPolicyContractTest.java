package sternhalma.selfplay;

import java.util.List;
import java.util.Set;

import sternhalma.nn.Network;
import sternhalma.nn.NetworkConfig;
import sternhalma.nn.Parameters;
import sternhalma.search.Strategy;
import sternhalma.search.contract.StrategyContract;

/**
 * The strategy contract of the search module (never an illegal move, clean self-play from any legal position) for the
 * raw-policy player of untrained networks. The registry of {@code Strategies} lives in a module that does not know the
 * networks, so the contract is run here directly. An untrained network is not expected to beat a random player, so the
 * strategy is flagged as deliberately weak ({@code mustBeatRandom} false) like the random player itself; what the
 * dry run measures for trained networks is the Elo estimate against the ladder.
 */
class RawPolicyContractTest extends StrategyContract {

    @Override
    protected List<Strategy> strategies() {
        return List.of(new Strategy("raw-policy(tiny, random weights)", Set.of(2), false, seed -> {
            Network network = new Network(Parameters.init(NetworkConfig.tiny(2), seed));
            return new RawPolicyPlayer(network, "raw-policy");
        }));
    }
}
