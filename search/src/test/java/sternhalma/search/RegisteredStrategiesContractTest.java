package sternhalma.search;

import java.util.List;

import sternhalma.search.contract.StrategyContract;

/** Runs the strategy contract against every strategy that {@link Strategies} registers. */
class RegisteredStrategiesContractTest extends StrategyContract {

    @Override
    protected List<Strategy> strategies() {
        return Strategies.registered();
    }
}
