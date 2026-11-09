package sternhalma.search;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.search.contract.PositionGenerator;

/**
 * The invariants of a search, for any options and positions: no virtual loss is left, the visits add up to the
 * simulations at the root and along every edge, and the move is legal.
 */
class MctsPropertyTest {

    /** Everything that makes a case, printable so that a failure can be rebuilt. */
    record Case(long seed, int variant, int plies, double greed, int sims, int batch, double noise, boolean s1,
            boolean win1, boolean reuse, double cpuct, double ptemp, double fpu, boolean end) {

        Position position() {
            RuleSet rules = PositionGenerator.rules(2, variant);
            return end ? PositionGenerator.justBeforeTheEnd(rules, seed)
                    : PositionGenerator.played(rules, seed, plies, greed);
        }

        MctsOptions options() {
            return MctsOptions.defaults().withRace(0).withSims(sims).withBatch(batch).withNoise(noise).withS1(s1)
                    .withWin1(win1).withReuse(reuse).withCpuct(cpuct).withPriorTemperature(ptemp).withFpu(fpu);
        }
    }

    @Provide
    Arbitrary<Case> cases() {
        return Combinators.combine(
                        Arbitraries.longs(),
                        Arbitraries.integers().between(0, PositionGenerator.VARIANTS - 1),
                        Arbitraries.integers().between(0, 190),
                        Arbitraries.of(0.0, 0.5, 0.95),
                        Arbitraries.integers().between(1, 90),
                        Arbitraries.frequencyOf(
                                net.jqwik.api.Tuple.of(3, Arbitraries.integers().between(1, 64)),
                                net.jqwik.api.Tuple.of(1, Arbitraries.just(1)),
                                net.jqwik.api.Tuple.of(1, Arbitraries.just(64))))
                .as((seed, variant, plies, greed, sims, batch) -> new Object[] {seed, variant, plies, greed, sims, batch})
                .flatMap(head -> Combinators.combine(
                                Arbitraries.of(0.0, 0.25, 1.0),
                                Arbitraries.of(true, false),
                                Arbitraries.of(true, false),
                                Arbitraries.of(true, false),
                                Arbitraries.of(0.0, 0.5, 1.5, 6.0),
                                Arbitraries.of(0.5, 1.0, 2.0),
                                Arbitraries.of(0.0, 0.3),
                                Arbitraries.of(false, false, false, true))
                        .as((noise, s1, win1, reuse, cpuct, ptemp, fpu, end) -> new Case((Long) head[0], (Integer) head[1],
                                (Integer) head[2], (Double) head[3], (Integer) head[4], (Integer) head[5], noise, s1, win1,
                                reuse, cpuct, ptemp, fpu, end)));
    }

    @Property(tries = 150, seed = "20261109")
    void noVirtualLossIsLeftAndTheVisitsAddUp(@ForAll("cases") Case c) {
        Position pos = c.position();
        HashedNetwork net = new HashedNetwork(2, c.seed(), 2.0);
        Mcts mcts = new Mcts(net, c.options(), c.seed());
        // two searches in a row, so that reuse has a tree to keep
        for (int round = 0; round < 2 && pos.outcome() == null && pos.legalMoves().length > 1; round++) {
            MctsResult r = mcts.search(pos);
            MctsNode root = mcts.root();
            assertTrue(root.cleanOfVirtualLoss(), "virtual loss left: " + c);
            assertEquals(c.sims(), r.sims(), c.toString());
            int visits = Arrays.stream(r.visits()).sum();
            if (c.reuse() && round > 0) {
                assertTrue(visits >= c.sims(), "the carried visits count too " + c);
            } else {
                assertEquals(c.sims(), visits, c.toString());
            }
            assertTrue(Arrays.stream(pos.legalMoves()).anyMatch(m -> m == r.move()), "legal move " + c);
            checkTree(root, c, c.reuse() && round > 0);
            pos.make(r.move());
            if (pos.outcome() == null && pos.legalMoves().length > 0) {
                int[] replies = pos.legalMoves();
                pos.make(replies[(int) (Math.abs(c.seed() % replies.length))]);
            }
        }
    }

    private static void checkTree(MctsNode node, Case c, boolean reused) {
        if (node.moves == null) {
            return;
        }
        int visits = 0;
        int virtual = 0;
        for (int i = 0; i < node.moves.length; i++) {
            visits += node.visits[i];
            virtual += node.virtualLoss[i];
            MctsNode child = node.child[i];
            assertTrue(node.visits[i] >= 0 && node.virtualLoss[i] == 0, "edge " + c);
            if (child == null) {
                assertEquals(0, node.visits[i], "visits without a child " + c);
            } else if (child.isExpanded()) {
                // the visit that expanded the child, then one visit for each simulation that went on through it
                assertEquals(child.totalVisits + 1, node.visits[i], "visits of an edge and its child " + c);
            }
            assertTrue(Math.abs(node.sum[i]) <= node.visits[i] + 1e-9, "values are in [-1, 1] " + c);
            if (child != null) {
                checkTree(child, c, reused);
            }
        }
        assertEquals(visits, node.totalVisits, "node total " + c);
        assertEquals(0, virtual + node.totalVirtual, c.toString());
    }

    @Property(tries = 40, seed = "20261110")
    void aSearchIsAFunctionOfPositionOptionsNetworkAndSeed(@ForAll("cases") Case c) {
        Position pos = c.position();
        HashedNetwork net = new HashedNetwork(2, c.seed(), 2.0);
        MctsResult a = new Mcts(net, c.options().withTemperature(1.0), c.seed()).search(pos);
        MctsResult b = new Mcts(new HashedNetwork(2, c.seed(), 2.0), c.options().withTemperature(1.0), c.seed()).search(pos);
        assertEquals(a.move(), b.move(), c.toString());
        assertArrayEquals(a.visits(), b.visits(), c.toString());
        assertArrayEquals(a.values(), b.values(), 0.0, c.toString());
    }
}
