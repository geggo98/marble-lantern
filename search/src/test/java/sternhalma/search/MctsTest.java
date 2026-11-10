package sternhalma.search;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

import sternhalma.engine.Geometry;
import sternhalma.engine.Move;
import sternhalma.engine.Outcome;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.nn.Evaluation;
import sternhalma.nn.Evaluator;
import sternhalma.nn.ForwardPass;
import sternhalma.nn.Network;
import sternhalma.nn.NetworkConfig;
import sternhalma.nn.Parameters;

/**
 * The MCTS search against an independent sequential reference, on constructed positions (mate in one, the move limit,
 * S1 verdicts for both movers), and its handling of noise, temperature, reuse, a time budget and a broken network.
 */
class MctsTest {

    static final MctsOptions PLAIN = MctsOptions.defaults().withBatch(1).withRace(0).withS1(false).withWin1(false);

    /** Positions of seeded random playouts, both movers, from the start to deep into a game. */
    static List<Position> positions(int count, long seed) {
        Random random = new Random(seed);
        List<Position> result = new ArrayList<>();
        while (result.size() < count) {
            Position p = Position.initial(RuleSet.defaults(2));
            for (int ply = 0; ply < 150 && p.outcome() == null && result.size() < count; ply++) {
                int[] moves = p.legalMoves();
                if (moves.length == 0) {
                    break;
                }
                if (ply % 7 == 3) {
                    result.add(p.copy());
                }
                p.make(moves[random.nextInt(moves.length)]);
            }
        }
        return result;
    }

    // ---- an independent reference ----

    /** Sequential PUCT written again, without batches, virtual loss, exact leaves or any option; same rules. */
    private static final class Reference {
        private final Evaluator evaluator;
        private final double cpuct;

        Reference(ForwardPass network, double cpuct) {
            this.evaluator = new Evaluator(network);
            this.cpuct = cpuct;
        }

        private final class Node {
            final int mover;
            final int[] moves;
            final float[] priors;
            final double value;
            final int[] n;
            final double[] w;
            final Node[] children;
            final double[] exact;

            Node(int mover, Evaluation e) {
                this.mover = mover;
                this.moves = e.moves();
                this.priors = e.priors();
                this.value = e.values()[mover];
                this.n = new int[moves.length];
                this.w = new double[moves.length];
                this.children = new Node[moves.length];
                this.exact = null;
            }

            Node(int mover, double[] exact) {
                this.mover = mover;
                this.moves = new int[0];
                this.priors = new float[0];
                this.value = 0;
                this.n = new int[0];
                this.w = new double[0];
                this.children = new Node[0];
                this.exact = exact;
            }
        }

        int[] run(Position root, int sims) {
            Position pos = root.copy();
            Node r = new Node(pos.sideToMove(), evaluator.evaluate(pos));
            for (int s = 0; s < sims; s++) {
                simulate(r, pos);
            }
            return r.n;
        }

        private double[] simulate(Node node, Position pos) {
            int total = 0;
            double sum = 0;
            for (int i = 0; i < node.moves.length; i++) {
                total += node.n[i];
                sum += node.w[i];
            }
            double mean = (node.value + sum) / (1 + total);
            int best = -1;
            double bestScore = -Double.MAX_VALUE;
            for (int i = 0; i < node.moves.length; i++) {
                double q = node.n[i] == 0 ? mean : node.w[i] / node.n[i];
                double u = cpuct * node.priors[i] * Math.sqrt(Math.max(1, total)) / (1 + node.n[i]);
                if (q + u > bestScore) {
                    bestScore = q + u;
                    best = i;
                }
            }
            pos.make(node.moves[best]);
            double[] values;
            Node child = node.children[best];
            if (child == null) {
                Outcome o = pos.outcome();
                if (o != null) {
                    child = new Node(pos.sideToMove(), o.values());
                } else {
                    child = new Node(pos.sideToMove(), evaluator.evaluate(pos));
                }
                node.children[best] = child;
                values = o != null ? o.values() : absolute(pos, child);
            } else if (child.exact != null) {
                values = child.exact;
            } else {
                values = simulate(child, pos);
            }
            pos.unmake();
            node.n[best]++;
            node.w[best] += values[node.mover];
            return values;
        }

        private double[] absolute(Position pos, Node child) {
            Evaluation e = evaluator.evaluate(pos);
            double[] v = new double[e.values().length];
            for (int a = 0; a < v.length; a++) {
                v[a] = e.values()[a];
            }
            return v;
        }
    }

    @Test
    void sequentialSearchEqualsAnIndependentReferenceInVisitsForBothMovers() {
        int checked = 0;
        for (long salt = 1; salt <= 3; salt++) {
            HashedNetwork net = new HashedNetwork(2, salt);
            for (Position p : positions(14, salt)) {
                if (p.outcome() != null) {
                    continue;
                }
                int sims = 60;
                for (double c : new double[] {0.5, 1.5, 4.0}) {
                    Mcts mcts = new Mcts(net, PLAIN.withSims(sims).withCpuct(c), 1);
                    MctsResult result = mcts.search(p);
                    int[] expected = new Reference(net, c).run(p, sims);
                    assertArrayEquals(expected, result.visits(), "salt " + salt + " cpuct " + c + " mover "
                            + p.sideToMove() + " ply " + p.ply());
                    checked++;
                }
            }
        }
        assertTrue(checked >= 100, "checked " + checked);
    }

    @Test
    void theReferenceAlsoAgreesWithARealNetworkAndTheMoveIsTheMostVisited() {
        Network net = new Network(Parameters.init(NetworkConfig.tiny(2), 7));
        for (Position p : positions(6, 5)) {
            MctsResult r = new Mcts(net, PLAIN.withSims(30).withCpuct(1.5), 1).search(p);
            assertArrayEquals(new Reference(net, 1.5).run(p, 30), r.visits());
            int most = 0;
            for (int i = 1; i < r.visits().length; i++) {
                if (r.visits()[i] > r.visits()[most]) {
                    most = i;
                }
            }
            assertEquals(r.moves()[most], r.move(), "temp 0 plays the most visited move");
        }
    }

    // ---- batches and virtual loss ----

    @Test
    void everyNodeHoldsTheEvaluationOfItsPositionWhateverTheBatchComposition() {
        HashedNetwork net = new HashedNetwork(2, 9);
        Evaluator single = new Evaluator(net);
        for (Position p : positions(8, 11)) {
            Mcts mcts = new Mcts(net, PLAIN.withBatch(16).withSims(120), 3);
            mcts.search(p);
            MctsNode root = mcts.root();
            assertTrue(root.cleanOfVirtualLoss());
            int checked = compareWithSingleEvaluations(root, p.copy(), single);
            assertTrue(checked > 10, "expanded nodes checked: " + checked);
        }
    }

    private static int compareWithSingleEvaluations(MctsNode node, Position pos, Evaluator single) {
        Evaluation e = single.evaluate(pos);
        assertArrayEquals(e.moves(), node.moves);
        assertArrayEquals(e.priors(), node.prior, 0f, "priors of " + node);
        assertEquals(e.values()[node.mover], node.value, 0.0, "value of " + node);
        int count = 1;
        for (int i = 0; i < node.moves.length; i++) {
            MctsNode child = node.child[i];
            if (child != null && child.isExpanded()) {
                pos.make(node.moves[i]);
                count += compareWithSingleEvaluations(child, pos, single);
                pos.unmake();
            }
        }
        return count;
    }

    @Test
    void batchesNeverExceedTheOptionAndEvaluateEveryLeafOnce() {
        for (int batch : new int[] {1, 4, 16, 64}) {
            HashedNetwork net = new HashedNetwork(2, 4);
            Position p = positions(1, 2).get(0);
            Mcts mcts = new Mcts(net, PLAIN.withBatch(batch).withSims(200), 1);
            MctsResult r = mcts.search(p);
            assertEquals(200, r.sims());
            assertTrue(net.batches().stream().allMatch(b -> b >= 1 && b <= batch), "batches " + net.batches());
            assertEquals(r.nodes(), net.evaluations, "one evaluation per node of the tree, the root included");
            if (batch > 1) {
                assertTrue(net.batches().stream().anyMatch(b -> b > 1), "batching happens");
            }
        }
    }

    @Test
    void aBatchOfOneIsTheSequentialSearch() {
        HashedNetwork net = new HashedNetwork(2, 6);
        Position p = positions(3, 8).get(2);
        MctsResult one = new Mcts(net, PLAIN.withBatch(1).withSims(80), 1).search(p);
        MctsResult big = new Mcts(net, PLAIN.withBatch(32).withSims(80), 1).search(p);
        assertEquals(80, Arrays.stream(big.visits()).sum());
        assertEquals(80, Arrays.stream(one.visits()).sum());
        assertFalse(Arrays.equals(one.visits(), big.visits()), "virtual loss spreads the visits");
    }

    // ---- exact leaves ----

    @Test
    void aMateInOneIsFoundWithOneSimulationWhateverTheNetworkSays() {
        Position pos = WinInOneTest.winInOne(9);
        int win = Move.of(Geometry.id(0, 4), Geometry.id(-1, 5));
        for (long salt = 1; salt <= 6; salt++) {
            for (int sims : new int[] {1, 2, 8}) {
                MctsResult r = new Mcts(new HashedNetwork(2, salt), MctsOptions.defaults().withRace(0).withSims(sims), 1)
                        .search(pos);
                assertEquals(win, r.move(), "salt " + salt + " sims " + sims);
                int index = indexOf(r.moves(), win);
                assertEquals(sims, r.visits()[index]);
                assertEquals(1.0, r.values()[index], 0.0, "the win is exact");
                assertEquals(sims, r.exactLeaves());
            }
        }
    }

    @Test
    void withoutTheWinInOneShortcutTheExactTerminalValueStillFindsTheMate() {
        Position pos = WinInOneTest.winInOne(9);
        int win = Move.of(Geometry.id(0, 4), Geometry.id(-1, 5));
        for (long salt = 1; salt <= 3; salt++) {
            MctsResult r = new Mcts(new HashedNetwork(2, salt), PLAIN.withBatch(8).withSims(400).withCpuct(1.5), 1).search(pos);
            assertEquals(win, r.move(), "salt " + salt);
            assertEquals(1.0, r.values()[indexOf(r.moves(), win)], 0.0);
        }
    }

    @Test
    void aMoveThatAllowsAnImmediateLossIsNotChosen() {
        Position pos = WinInOneTest.threat(9);
        for (long salt = 1; salt <= 3; salt++) {
            MctsResult r = new Mcts(new HashedNetwork(2, salt, 1.0, 0.0), MctsOptions.defaults().withRace(0).withSims(600), 1)
                    .search(pos);
            Position after = pos.copy();
            after.make(r.move());
            boolean loses = false;
            for (int reply : after.legalMoves()) {
                after.make(reply);
                Outcome o = after.outcome();
                loses |= o != null && o.hasWinner() && o.winner() == 1;
                after.unmake();
            }
            assertFalse(loses, "salt " + salt + ": played " + Move.toString(r.move()));
        }
    }

    @Test
    void positionsAtTheMoveLimitAreExactWithTheEnginesValues() {
        RuleSet rules = RuleSet.defaults(2).withMoveLimit(30);
        Random random = new Random(3);
        Position pos = Position.initial(rules);
        while (pos.ply() < 29) {
            int[] moves = pos.legalMoves();
            pos.make(moves[random.nextInt(moves.length)]);
        }
        assertNull(pos.outcome());
        HashedNetwork net = new HashedNetwork(2, 1, 5.0);
        MctsResult r = new Mcts(net, PLAIN.withBatch(4).withSims(pos.legalMoves().length * 3), 1).search(pos);
        assertEquals(pos.legalMoves().length, r.moves().length);
        int mover = pos.sideToMove();
        for (int i = 0; i < r.moves().length; i++) {
            pos.make(r.moves()[i]);
            Outcome o = pos.outcome();
            assertNotNull(o);
            pos.unmake();
            if (r.visits()[i] > 0) {
                assertEquals(o.value(mover), r.values()[i], 1e-12, "the move limit scores as in a game, move " + i);
            }
        }
        assertEquals(r.sims(), r.exactLeaves(), "every simulation ends at the limit");
        assertEquals(1, net.batches().size(), "only the root goes to the network");
    }

    /** A passed position, player 1 to move: player 0 needs one move, player 1 needs two. S1: player 0 wins. */
    private static Position loserToMove() {
        return RaceAdjudicationTest.nineAndOne(RuleSet.defaults(2), Geometry.id(-2, 4), Geometry.id(0, 0), 1);
    }

    @Test
    void anS1VerdictIsAnExactLeafWithTheRightSignForTheMoverWhoLoses() {
        Position pos = loserToMove();
        Mcts mcts = new Mcts(new HashedNetwork(2, 2), MctsOptions.defaults().withRace(0).withSims(200).withBatch(8), 1);
        MctsResult r = mcts.search(pos);
        int exact = 0;
        for (int i = 0; i < r.moves().length; i++) {
            MctsNode child = mcts.root().child[i];
            if (child != null && child.exact != null) {
                exact++;
                assertArrayEquals(new double[] {1, -1}, child.exact, "player 0 wins");
                assertEquals(-1.0, r.values()[i], 1e-12, "player 1 moves and loses: the mover's value is -1");
            }
        }
        assertTrue(exact > 0, "S1 fires after some moves of player 1");
        assertTrue(r.exactLeaves() > 0);
    }

    @Test
    void anS1VerdictBacksUpPlusOneForTheMoverWhoWins() {
        // player 0 to move, two steps from the win, player 1 two steps too, but second: after (-2,3) -> (-2,4) S1 says player 0 wins
        Position pos = RaceAdjudicationTest.nineAndOne(RuleSet.defaults(2), Geometry.id(-2, 3), Geometry.id(0, 0), 0);
        Mcts mcts = new Mcts(new HashedNetwork(2, 2), MctsOptions.defaults().withRace(0).withSims(300).withBatch(8), 1);
        MctsResult r = mcts.search(pos);
        int exact = 0;
        for (int i = 0; i < r.moves().length; i++) {
            MctsNode child = mcts.root().child[i];
            if (child != null && child.exact != null && child.exact[0] == 1) {
                exact++;
                assertArrayEquals(new double[] {1, -1}, child.exact);
                assertEquals(1.0, r.values()[i], 1e-12);
            }
        }
        assertTrue(exact > 0, "S1 fires");
        assertTrue(r.values()[indexOf(r.moves(), r.move())] > 0.99, "the chosen move wins the race");
    }

    @Test
    void withoutS1OnlyTheEndOfTheGameIsAnExactLeaf() {
        Position pos = loserToMove();
        MctsOptions options = MctsOptions.defaults().withRace(0).withSims(300).withBatch(8);
        Mcts without = new Mcts(new HashedNetwork(2, 2), options.withS1(false), 1);
        MctsResult r = without.search(pos);
        int exact = exactNodesOutsideTheEnd(without.root(), pos.copy());
        assertEquals(0, exact, "no exact leaf except the end of the game");
        Mcts with = new Mcts(new HashedNetwork(2, 2), options, 1);
        with.search(pos);
        assertTrue(exactNodesOutsideTheEnd(with.root(), pos.copy()) > 0, "S1 makes exact leaves of passed positions");
        assertTrue(r.exactLeaves() >= 0);
    }

    /** Nodes of the tree that are exact although the game is not over in their position. */
    private static int exactNodesOutsideTheEnd(MctsNode node, Position pos) {
        int count = 0;
        if (node.exact != null) {
            return pos.outcome() == null ? 1 : 0;
        }
        if (node.moves == null) {
            return 0;
        }
        for (int i = 0; i < node.moves.length; i++) {
            if (node.child[i] != null) {
                pos.make(node.moves[i]);
                count += exactNodesOutsideTheEnd(node.child[i], pos);
                pos.unmake();
            }
        }
        return count;
    }

    @Test
    void theRaceModuleIsOnlyUsedByThePlayerAndOnlyAtTheRoot() {
        Position pos = loserToMove();
        MctsPlayer player = MctsPlayer.create(new HashedNetwork(2, 2), MctsOptions.defaults().withRace(1).withSims(20), 1, "x");
        int move = player.choose(pos);
        assertEquals(1, player.raceMoves(), "a passed position is played from the race table");
        assertEquals(0, player.totalSims());
        assertTrue(Arrays.stream(pos.legalMoves()).anyMatch(m -> m == move));
        assertNull(player.lastResult());
        Position start = Position.initial(RuleSet.defaults(2));
        player.choose(start);
        assertEquals(1, player.raceMoves());
        assertEquals(20, player.totalSims());
        assertNotNull(player.lastResult());
    }

    // ---- noise, temperature, seeds ----

    @Test
    void withoutNoiseAndTemperatureTheSeedChangesNothing() {
        HashedNetwork net = new HashedNetwork(2, 5);
        Position p = positions(2, 4).get(1);
        MctsResult a = new Mcts(net, PLAIN.withSims(50), 1).search(p);
        MctsResult b = new Mcts(net, PLAIN.withSims(50), 999).search(p);
        assertArrayEquals(a.visits(), b.visits());
        assertEquals(a.move(), b.move());
    }

    @Test
    void noiseIsSeededAndChangesTheSearch() {
        HashedNetwork net = new HashedNetwork(2, 5);
        Position p = positions(2, 4).get(1);
        MctsOptions noisy = PLAIN.withSims(120).withNoise(0.5);
        int[] a = new Mcts(net, noisy, 11).search(p).visits();
        int[] b = new Mcts(net, noisy, 11).search(p).visits();
        int[] c = new Mcts(net, noisy, 12).search(p).visits();
        int[] clean = new Mcts(net, noisy.withNoise(0), 11).search(p).visits();
        assertArrayEquals(a, b, "the same seed gives the same search");
        assertFalse(Arrays.equals(a, c), "another seed gives another noise");
        assertFalse(Arrays.equals(a, clean), "noise changes the visits");
        assertEquals(120, Arrays.stream(a).sum());
    }

    @Test
    void theRootPriorsStayCleanAndTheNoiseIsAppliedAgainOnEverySearch() {
        HashedNetwork net = new HashedNetwork(2, 5);
        Position p = positions(2, 4).get(1);
        Mcts mcts = new Mcts(net, PLAIN.withSims(40).withNoise(0.5).withReuse(true), 3);
        mcts.search(p);
        MctsNode root = mcts.root();
        assertNull(root.searchPrior, "the noise does not outlive the search");
        float[] clean = new Evaluator(net).evaluate(p).priors();
        assertArrayEquals(clean, root.prior, 0f);
    }

    /**
     * The mixing weights of the root noise, {@code (1 - noise) * prior + noise * d}, against the Dirichlet sample of the same
     * seeded generator that the test draws itself: noise 1 is the sample, noise 0 the prior, noise 0.25 the exact blend. A
     * swapped blend ({@code noise * prior + (1 - noise) * d}) fails the 0.25 case and the two extremes.
     */
    @Test
    void theNoiseMixesPriorAndDirichletSampleWithTheDocumentedWeights() {
        HashedNetwork net = new HashedNetwork(2, 5);
        float[] prior = {0.5f, 0.2f, 0.15f, 0.1f, 0.05f};
        long seed = 4242;
        double alpha = 0.3;
        // the sample that a generator with this seed gives: one gamma draw per move, normalised
        var random = java.util.random.RandomGeneratorFactory.of("SplittableRandom").create(seed);
        double[] g = new double[prior.length];
        double sum = 0;
        for (int i = 0; i < g.length; i++) {
            g[i] = Mcts.gamma(random, alpha);
            sum += g[i];
        }
        double[] sample = new double[g.length];
        for (int i = 0; i < g.length; i++) {
            sample[i] = g[i] / sum;
        }
        assertNotEquals(sample[0], prior[0], 0.01, "the sample must differ from the prior, or the test shows nothing");
        float[] full = new Mcts(net, PLAIN.withNoise(1.0), seed).noisy(prior);
        float[] none = new Mcts(net, PLAIN.withNoise(0.0), seed).noisy(prior);
        float[] quarter = new Mcts(net, PLAIN.withNoise(0.25), seed).noisy(prior);
        for (int i = 0; i < prior.length; i++) {
            assertEquals(sample[i], full[i], 1e-6, "noise 1 is the Dirichlet sample, move " + i);
            assertEquals(prior[i], none[i], 1e-7, "noise 0 is the prior, move " + i);
            assertEquals(0.75 * prior[i] + 0.25 * sample[i], quarter[i], 1e-6, "noise 0.25, move " + i);
        }
        float total = 0;
        for (float f : quarter) {
            total += f;
        }
        assertEquals(1f, total, 1e-6f);
    }

    @Test
    void noisyPriorsAreADistributionAndGammaHasTheRightMean() {
        SplittableRandom r = new SplittableRandom(1);
        for (double alpha : new double[] {0.1, 0.3, 1.0, 3.0}) {
            double sum = 0;
            int n = 40_000;
            for (int i = 0; i < n; i++) {
                double g = Mcts.gamma(r, alpha);
                assertTrue(g >= 0 && Double.isFinite(g));
                sum += g;
            }
            assertEquals(alpha, sum / n, 0.05 * Math.max(alpha, 0.2), "mean of Gamma(" + alpha + ")");
        }
    }

    @Test
    void temperatureSamplesMovesInProportionToTheirVisitsAndIsSeeded() {
        HashedNetwork net = new HashedNetwork(2, 5);
        Position p = positions(2, 4).get(1);
        MctsOptions o = PLAIN.withSims(60).withTemperature(1.0);
        MctsResult base = new Mcts(net, o.withTemperature(0), 1).search(p);
        int[] counts = new int[base.moves().length];
        List<Integer> sequence = new ArrayList<>();
        for (long seed = 0; seed < 600; seed++) {
            MctsResult r = new Mcts(net, o, seed).search(p);
            int i = indexOf(r.moves(), r.move());
            assertTrue(r.visits()[i] > 0, "only visited moves are played");
            counts[i]++;
            sequence.add(r.move());
            if (seed < 5) {
                assertEquals(r.move(), new Mcts(net, o, seed).search(p).move(), "seeded");
            }
        }
        assertArrayEquals(base.visits(), new Mcts(net, o, 0).search(p).visits(), "the search itself does not depend on the seed");
        int distinct = (int) sequence.stream().distinct().count();
        assertTrue(distinct > 1, "sampling varies the move");
        for (int i = 0; i < counts.length; i++) {
            double expected = 600.0 * base.visits()[i] / 60;
            assertEquals(expected, counts[i], 5 * Math.sqrt(Math.max(expected, 1)) + 1, "move " + i);
        }
    }

    @Test
    void aLowTemperatureSharpensTheChoiceTowardsTheMostVisitedMove() {
        HashedNetwork net = new HashedNetwork(2, 5);
        Position p = positions(2, 4).get(1);
        MctsResult greedy = new Mcts(net, PLAIN.withSims(80), 1).search(p);
        int most = Arrays.stream(greedy.visits()).max().getAsInt();
        int same = 0;
        for (long seed = 0; seed < 100; seed++) {
            MctsResult r = new Mcts(net, PLAIN.withSims(80).withTemperature(0.05), seed).search(p);
            same += r.move() == greedy.move() ? 1 : 0;
            assertTrue(r.visits()[indexOf(r.moves(), r.move())] * 2 >= most, "a rival of the most visited move at most");
        }
        assertTrue(same >= 60, "same move in " + same + " of 100");
    }

    @Test
    void thePriorTemperatureFlattensThePriors() {
        float[] p = {0.7f, 0.2f, 0.1f};
        assertArrayEquals(p, Mcts.temper(p, 1.0), 0f);
        float[] flat = Mcts.temper(p, 2.0);
        double sum = flat[0] + flat[1] + flat[2];
        assertEquals(1.0, sum, 1e-6);
        assertTrue(flat[0] < 0.7f && flat[2] > 0.1f);
        assertEquals(Math.sqrt(0.7) / (Math.sqrt(0.7) + Math.sqrt(0.2) + Math.sqrt(0.1)), flat[0], 1e-6);
        float[] sharp = Mcts.temper(p, 0.5);
        assertTrue(sharp[0] > 0.7f);
        float[] dead = Mcts.temper(new float[] {0f, 0f}, 3.0);
        assertEquals(0.5f, dead[0], 0f);
    }

    // ---- tree reuse, budget, failure, players ----

    @Test
    void reuseKeepsTheSubtreeOfThePlayedLineAndNothingElse() {
        HashedNetwork net = new HashedNetwork(2, 8);
        Position p = Position.initial(RuleSet.defaults(2));
        Mcts mcts = new Mcts(net, PLAIN.withBatch(8).withSims(150).withReuse(true), 1);
        MctsResult first = mcts.search(p);
        MctsNode oldRoot = mcts.root();
        p.make(first.move());
        MctsNode mine = oldRoot.child[indexOf(first.moves(), first.move())];
        // the opponent's most visited reply, which the tree has
        int reply = -1;
        int best = -1;
        for (int i = 0; i < mine.moves.length; i++) {
            if (mine.child[i] != null && mine.child[i].isExpanded() && mine.visits[i] > best) {
                best = mine.visits[i];
                reply = i;
            }
        }
        assertTrue(best > 0, "the tree has a reply");
        MctsNode expected = mine.child[reply];
        int carried = expected.totalVisits;
        p.make(mine.moves[reply]);
        mcts.search(p);
        assertTrue(mcts.root() == expected, "the new root is the old grandchild");
        assertEquals(carried + 150, mcts.root().totalVisits, "the carried visits count, the new ones are added");
        // a position that is not in the tree starts a new one
        Position other = positions(1, 77).get(0);
        mcts.search(other);
        assertEquals(150, mcts.root().totalVisits);
        // without reuse a search always starts from nothing
        Mcts plain = new Mcts(net, PLAIN.withSims(50), 1);
        plain.search(p);
        plain.search(p);
        assertEquals(50, plain.root().totalVisits);
    }

    /**
     * The hash does not contain the ply, so the same board with the same side to move can come up at another ply (every
     * piece goes back where it was after four plies). The tree of the first position must not be reused for the later
     * one: its exact leaves and move-limit values depend on the ply. A reuse by hash alone would carry the old visits.
     */
    @Test
    void reuseNeedsTheSamePlyNotOnlyTheSameHash() {
        HashedNetwork net = new HashedNetwork(2, 8);
        Position start = Position.initial(RuleSet.defaults(2));
        Position later = start.copy();
        int[] first = later.legalMoves();
        int a = first[0];
        later.make(a);
        int b = later.legalMoves()[0];
        later.make(b);
        int aBack = Move.of(Move.to(a), Move.from(a));
        assertTrue(Arrays.stream(later.legalMoves()).anyMatch(m -> m == aBack), "the first move can be undone");
        later.make(aBack);
        int bBack = Move.of(Move.to(b), Move.from(b));
        assertTrue(Arrays.stream(later.legalMoves()).anyMatch(m -> m == bBack), "the reply can be undone");
        later.make(bBack);
        assertEquals(start.hash(), later.hash(), "the same board and side to move");
        assertEquals(4, later.ply());
        assertEquals(0, start.ply());

        Mcts mcts = new Mcts(net, PLAIN.withSims(60).withReuse(true), 1);
        mcts.search(start);
        assertEquals(60, mcts.root().totalVisits);
        mcts.search(later);
        assertEquals(60, mcts.root().totalVisits, "another ply is another position: nothing is carried over");
        // the same ply (the same position searched again) is reused, so the test can tell the two cases apart
        mcts.search(later);
        assertEquals(120, mcts.root().totalVisits);
    }

    @Test
    void reuseGivesAValidTreeWithoutVirtualLoss() {
        HashedNetwork net = new HashedNetwork(2, 8);
        Position p = Position.initial(RuleSet.defaults(2));
        Mcts mcts = new Mcts(net, PLAIN.withBatch(16).withSims(100).withReuse(true), 1);
        for (int ply = 0; ply < 40 && p.outcome() == null; ply++) {
            MctsResult r = mcts.search(p);
            assertTrue(mcts.root().cleanOfVirtualLoss());
            assertEquals(mcts.root().totalVisits, Arrays.stream(r.visits()).sum());
            p.make(r.move());
        }
    }

    @Test
    void aTimeBudgetStopsAfterWholeBatchesAndSimsStayAnUpperBound() {
        HashedNetwork net = new HashedNetwork(2, 3);
        Position p = positions(1, 1).get(0);
        MctsResult r = new Mcts(net, PLAIN.withBatch(4).withSims(1_000_000).withMillis(30), 1).search(p);
        assertTrue(r.sims() >= 1 && r.sims() < 1_000_000, "sims " + r.sims());
        assertEquals(r.sims(), Arrays.stream(r.visits()).sum());
        assertTrue(new Mcts(net, PLAIN.withSims(10).withMillis(60_000), 1).search(p).sims() == 10);
    }

    @Test
    void aBrokenNetworkFailsLoudlyAndLeavesNoVirtualLossBehind() {
        HashedNetwork good = new HashedNetwork(2, 3);
        int[] calls = new int[1];
        ForwardPass flaky = new ForwardPass() {
            @Override
            public NetworkConfig config() {
                return good.config();
            }

            @Override
            public boolean threadSafe() {
                return true;
            }

            @Override
            public void forward(float[] input, int batch, float[] policy, float[] values) {
                good.forward(input, batch, policy, values);
                if (++calls[0] == 3) {
                    values[0] = Float.NaN;
                }
            }
        };
        Position p = positions(1, 1).get(0);
        Mcts mcts = new Mcts(flaky, PLAIN.withBatch(8).withSims(100), 1);
        assertThrows(IllegalStateException.class, () -> mcts.search(p));
        assertNull(mcts.root(), "the broken tree is dropped");
        calls[0] = 100;
        MctsResult r = mcts.search(p);
        assertEquals(100, r.sims());
        assertTrue(mcts.root().cleanOfVirtualLoss());
    }

    @Test
    void threePlayersBackUpTheValueOfEachMover() {
        HashedNetwork net = new HashedNetwork(3, 4);
        Position p = Position.initial(RuleSet.defaults(3));
        Mcts mcts = new Mcts(net, MctsOptions.defaults().withRace(0).withSims(80).withBatch(8), 1);
        for (int ply = 0; ply < 6; ply++) {
            MctsResult r = mcts.search(p);
            assertEquals(80, Arrays.stream(r.visits()).sum());
            assertTrue(indexOf(p.legalMoves(), r.move()) >= 0);
            assertTrue(mcts.root().cleanOfVirtualLoss());
            p.make(r.move());
        }
    }

    @Test
    void aFinishedGameOrANetworkOfAnotherPlayerCountIsRefused() {
        Position over = WinInOneTest.winInOne(9);
        over.make(Move.of(Geometry.id(0, 4), Geometry.id(-1, 5)));
        assertNotNull(over.outcome());
        Mcts mcts = new Mcts(new HashedNetwork(2, 1), MctsOptions.defaults().withRace(0), 1);
        assertThrows(IllegalArgumentException.class, () -> mcts.search(over));
        assertThrows(IllegalArgumentException.class, () -> mcts.search(Position.initial(RuleSet.defaults(3))));
    }

    @Test
    void theSearchDoesNotChangeThePositionAndKeepsNoSharedState() {
        HashedNetwork net = new HashedNetwork(2, 5);
        Position p = positions(1, 9).get(0);
        long hash = p.hash();
        int ply = p.ply();
        Mcts a = new Mcts(net, PLAIN.withSims(30).withNoise(0.25), 1);
        Mcts b = new Mcts(net, PLAIN.withSims(30).withNoise(0.25), 1);
        MctsResult ra = a.search(p);
        // b's work in between must not influence a second search of a fresh a
        b.search(positions(1, 10).get(0));
        MctsResult rb = b.search(p);
        assertEquals(hash, p.hash());
        assertEquals(ply, p.ply());
        assertEquals(ra.move(), new Mcts(net, PLAIN.withSims(30).withNoise(0.25), 1).search(p).move());
        assertNotEquals(-1, rb.move());
    }

    private static int indexOf(int[] moves, int move) {
        for (int i = 0; i < moves.length; i++) {
            if (moves[i] == move) {
                return i;
            }
        }
        return -1;
    }
}
