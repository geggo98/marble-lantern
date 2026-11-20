package sternhalma.search;

import java.util.List;

import sternhalma.engine.RuleSet;
import sternhalma.search.contract.StrategyContract;

/**
 * The strategy contract on the 5-row board (15-cell houses): the strategies of {@link Strategies#registeredRows5()}
 * play from random, greedy and end-of-game positions of 2 and 3 players, under the same four rule variants as the
 * 4-row sweep ({@link sternhalma.search.contract.PositionGenerator#rules(int, int, RuleSet.TriangleRows)}).
 */
class RegisteredStrategiesRows5ContractTest extends StrategyContract {

    @Override
    protected List<Strategy> strategies() {
        return Strategies.registeredRows5();
    }

    @Override
    protected RuleSet.TriangleRows rows() {
        return RuleSet.TriangleRows.FIVE;
    }
}
