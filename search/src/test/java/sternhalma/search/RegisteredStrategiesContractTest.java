package sternhalma.search;

import java.util.List;

import sternhalma.search.contract.StrategyContract;

/**
 * Runs the strategy contract against every strategy that {@link Strategies} registers. Strategies that read the
 * rows&gt;=1 table (evaluator {@code racelin1...} or {@code racerows=1}) are left out when only a Git LFS pointer is
 * present, because creating them would rebuild the table (about four minutes); the other strategies still run.
 */
class RegisteredStrategiesContractTest extends StrategyContract {

    @Override
    protected List<Strategy> strategies() {
        boolean rows1 = Rows1Tables.available();
        return Strategies.registered().stream()
                .filter(s -> rows1 || !(s.name().contains("racelin1") || s.name().contains("racerows=1")))
                .toList();
    }
}
