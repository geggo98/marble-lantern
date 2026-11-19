package sternhalma.search;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.nn.Encoder;
import sternhalma.nn.Network;
import sternhalma.nn.NetworkConfig;
import sternhalma.nn.Parameters;
import sternhalma.nn.PlaneSet;

/**
 * The stepwise search (begin, prepare, pendingInputs, apply, result) against the synchronous {@link Mcts#search} with
 * the same network and seed: the same move, visits, values, tree size and exact leaves, for batch 1 and larger, two and
 * three players, noise and sampling, tree reuse.
 */
class MctsStepwiseTest {

    private static final NetworkConfig CLASSIC = NetworkConfig.tiny(2).withPlanes(PlaneSet.CLASSIC1);
    private static final NetworkConfig VARIANTS = NetworkConfig.tiny(3).withPlanes(PlaneSet.CLASSIC1V);
    private static final MctsOptions BASE = MctsOptions.defaults().withS1(false).withRace(0);

    private static Network net(NetworkConfig config, long seed) {
        return new Network(Parameters.init(config, seed));
    }

    /** Runs the stepwise protocol with the network's forward pass done by hand on the encoded inputs. */
    static MctsResult stepwise(Mcts mcts, Network network, Position position, int[] batchSizes) {
        NetworkConfig config = network.config();
        mcts.begin(position);
        int n;
        while ((n = mcts.prepare()) > 0) {
            assertEquals(n, mcts.pendingCount());
            assertEquals(n, mcts.prepare(), "prepare is idempotent until apply");
            batchSizes[0] = Math.max(batchSizes[0], n);
            float[] input = mcts.pendingInputs();
            assertEquals(n * config.inputSize(), input.length);
            float[] policy = new float[n * Encoder.POLICY_SIZE];
            float[] values = new float[n * config.players()];
            network.forward(input, n, policy, values);
            mcts.apply(policy, values);
        }
        return mcts.result();
    }

    private static void assertSameResult(MctsResult expected, MctsResult actual, String what) {
        assertEquals(expected.move(), actual.move(), what + " move");
        assertArrayEquals(expected.moves(), actual.moves(), what + " moves");
        assertArrayEquals(expected.visits(), actual.visits(), what + " visits");
        assertArrayEquals(expected.values(), actual.values(), 0.0, what + " values");
        assertEquals(expected.rootValue(), actual.rootValue(), 0.0, what + " root value");
        assertEquals(expected.sims(), actual.sims(), what + " sims");
        assertEquals(expected.nodes(), actual.nodes(), what + " nodes");
        assertEquals(expected.exactLeaves(), actual.exactLeaves(), what + " exact leaves");
    }

    private static List<Position> positions(int players, int count, long seed) {
        Random random = new Random(seed);
        List<Position> result = new ArrayList<>();
        Position p = Position.initial(RuleSet.defaults(players));
        for (int ply = 0; result.size() < count && p.outcome() == null; ply++) {
            int[] moves = p.legalMoves();
            if (moves.length == 0) {
                break;
            }
            if (ply % 5 == 0) {
                result.add(p.copy());
            }
            p.make(moves[random.nextInt(moves.length)]);
        }
        return result;
    }

    private static void compare(NetworkConfig config, int players, MctsOptions options, int expectedBatchAtLeast) {
        Network network = net(config, 11);
        int[] largest = new int[1];
        for (Position p : positions(players, 8, 5)) {
            MctsResult expected = new Mcts(network, options, 99).search(p);
            Mcts stepped = new Mcts(config, options, SearchRandom.splittable(99), null);
            MctsResult actual = stepwise(stepped, network, p, largest);
            assertSameResult(expected, actual, "ply " + p.ply());
        }
        assertTrue(largest[0] >= expectedBatchAtLeast, "a batch of " + expectedBatchAtLeast + " was asked for, the largest was " + largest[0]);
        assertTrue(largest[0] <= options.batch());
    }

    @Test
    void matchesTheSynchronousSearchWithBatchOne() {
        compare(CLASSIC, 2, BASE.withSims(60).withBatch(1), 1);
    }

    @Test
    void matchesTheSynchronousSearchWithBatchFour() {
        compare(CLASSIC, 2, BASE.withSims(60).withBatch(4), 4);
    }

    @Test
    void matchesTheSynchronousSearchWithBatchEightAndWinInOne() {
        compare(CLASSIC, 2, BASE.withSims(80).withBatch(8).withWin1(true), 8);
    }

    @Test
    void matchesForThreePlayersOfTheVariantNet() {
        compare(VARIANTS, 3, BASE.withSims(50).withBatch(4), 4);
    }

    @Test
    void matchesWithNoiseAndSampling() {
        compare(CLASSIC, 2, BASE.withSims(40).withBatch(2).withNoise(0.25).withTemperature(1.0), 2);
    }

    @Test
    void matchesAcrossMovesWithTreeReuse() {
        Network network = net(CLASSIC, 3);
        MctsOptions options = BASE.withSims(40).withBatch(4).withReuse(true);
        Mcts sync = new Mcts(network, options, 7);
        Mcts stepped = new Mcts(CLASSIC, options, SearchRandom.splittable(7), null);
        Position p = Position.initial(RuleSet.defaults(2));
        int[] largest = new int[1];
        for (int move = 0; move < 6 && p.outcome() == null; move++) {
            MctsResult expected = sync.search(p);
            MctsResult actual = stepwise(stepped, network, p, largest);
            assertSameResult(expected, actual, "move " + move);
            p.make(expected.move());
            // the reply of the opponent: the first legal move, so that the tree has to find the played line
            p.make(p.legalMoves()[0]);
        }
    }

    @Test
    void aSingleLegalMoveNeedsOnlyTheRootEvaluation() {
        // A position with one legal move is evaluated once (the root) and then finishes without simulations.
        Network network = net(CLASSIC, 3);
        Position p = Position.initial(RuleSet.defaults(2));
        Random random = new Random(1);
        Position single = null;
        for (int game = 0; game < 200 && single == null; game++) {
            Position q = p.copy();
            while (q.outcome() == null && q.legalMoves().length > 1) {
                q.make(q.legalMoves()[random.nextInt(q.legalMoves().length)]);
            }
            if (q.outcome() == null && q.legalMoves().length == 1) {
                single = q;
            }
        }
        if (single == null) {
            return; // no such position found in the playouts: nothing to check
        }
        Mcts mcts = new Mcts(CLASSIC, BASE.withSims(30), SearchRandom.splittable(1), null);
        mcts.begin(single);
        assertEquals(1, mcts.prepare());
        float[] policy = new float[Encoder.POLICY_SIZE];
        float[] values = new float[2];
        network.forward(mcts.pendingInputs(), 1, policy, values);
        mcts.apply(policy, values);
        assertEquals(0, mcts.prepare());
        assertEquals(0, mcts.result().sims());
        assertEquals(single.legalMoves()[0], mcts.result().move());
    }

    @Test
    void resultIsTheSameObjectUntilTheNextBegin() {
        Network network = net(CLASSIC, 3);
        Mcts mcts = new Mcts(CLASSIC, BASE.withSims(10), SearchRandom.splittable(1), null);
        Position p = Position.initial(RuleSet.defaults(2));
        MctsResult first = stepwise(mcts, network, p, new int[1]);
        assertSame(first, mcts.result());
        assertNotSame(first, stepwise(mcts, network, p, new int[1]));
    }

    @Test
    void callsInTheWrongOrderAreRefused() {
        Mcts mcts = new Mcts(CLASSIC, BASE.withSims(10), SearchRandom.splittable(1), null);
        assertThrows(IllegalStateException.class, mcts::prepare);
        assertThrows(IllegalStateException.class, mcts::result);
        assertThrows(IllegalStateException.class, mcts::pendingInputs);
        assertThrows(IllegalStateException.class, () -> mcts.apply(new float[Encoder.POLICY_SIZE], new float[2]));
        mcts.begin(Position.initial(RuleSet.defaults(2)));
        assertThrows(IllegalStateException.class, mcts::result, "the root is still waiting for the network");
    }

    @Test
    void nonFiniteOutputsDropTheSearchAndLeaveItIdle() {
        Mcts mcts = new Mcts(CLASSIC, BASE.withSims(10), SearchRandom.splittable(1), null);
        mcts.begin(Position.initial(RuleSet.defaults(2)));
        assertEquals(1, mcts.prepare());
        float[] policy = new float[Encoder.POLICY_SIZE];
        java.util.Arrays.fill(policy, Float.NaN);
        assertThrows(IllegalStateException.class, () -> mcts.apply(policy, new float[2]));
        assertThrows(IllegalStateException.class, mcts::prepare, "idle after the failure");
    }

    @Test
    void s1WithoutATableIsRefusedAtConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new Mcts(CLASSIC, BASE.withS1(true), SearchRandom.portable(1), null));
    }

    @Test
    void portableRandomIsReproducibleAndUniformEnough() {
        SearchRandom a = SearchRandom.portable(42);
        SearchRandom b = SearchRandom.portable(42);
        double sum = 0;
        double sumSquares = 0;
        int n = 20000;
        for (int i = 0; i < n; i++) {
            double d = a.nextDouble();
            assertEquals(d, b.nextDouble(), 0.0);
            assertTrue(d >= 0 && d < 1);
            double g = a.nextGaussian();
            assertEquals(g, b.nextGaussian(), 0.0);
            sum += g;
            sumSquares += g * g;
        }
        assertEquals(0.0, sum / n, 0.03, "mean of the normals");
        assertEquals(1.0, sumSquares / n, 0.05, "variance of the normals");
    }
}
