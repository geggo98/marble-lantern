package sternhalma.nn;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import sternhalma.engine.Geometry;
import sternhalma.engine.Move;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;

class EvaluatorTest {

    private static final Network TWO = new Network(Parameters.uniform(NetworkConfig.tiny(2), 101, 0.5f));
    private static final Network THREE = new Network(Parameters.uniform(NetworkConfig.tiny(3), 102, 0.5f));

    /**
     * The priors are the softmax of the move logits over the legal moves: they sum to 1, cover exactly the
     * legal moves in generation order, and the ratio of two priors is exp of the logit difference.
     */
    @Test
    void priorsAreTheSoftmaxOverTheLegalMoves() {
        for (Position position : List.of(randomGame(2, 1, 12), randomGame(3, 2, 17))) {
            Network network = position.rules().players() == 2 ? TWO : THREE;
            Evaluation e = new Evaluator(network).evaluate(position);
            assertArrayEquals(position.legalMoves(), e.moves());
            assertEquals(e.moves().length, e.priors().length);
            assertTrue(e.moves().length > 10, "enough moves for the test");

            double sum = 0;
            for (float p : e.priors()) {
                assertTrue(p > 0 && p < 1, "prior " + p);
                sum += p;
            }
            assertEquals(1.0, sum, 1e-6);

            double[] logits = logits(network, position);
            int best = 0;
            int bestPrior = 0;
            for (int i = 0; i < logits.length; i++) {
                double expected = Math.exp(logits[i] - logits[0]);
                double actual = (double) e.priors()[i] / e.priors()[0];
                assertEquals(expected, actual, 1e-5 * expected, "prior ratio of move " + i);
                if (logits[i] > logits[best]) {
                    best = i;
                }
                if (e.priors()[i] > e.priors()[bestPrior]) {
                    bestPrior = i;
                }
            }
            assertEquals(best, bestPrior, "the largest logit has the largest prior");
        }
    }

    /**
     * The softmax subtracts the largest logit: logits around 1000 would overflow exp without it. Built with
     * a hand-set policy bias, which shifts every logit by the same amount and so must not change the priors.
     */
    @Test
    void priorsAreStableForLargeLogits() {
        Parameters parameters = Parameters.uniform(NetworkConfig.tiny(2), 101, 0.5f);
        Position position = randomGame(2, 0, 10);
        Evaluation before = new Evaluator(new Network(parameters)).evaluate(position);
        parameters.get("head_policy.bias").data()[0] += 500f;
        parameters.get("head_policy.bias").data()[1] += 500f;
        Evaluation after = new Evaluator(new Network(parameters)).evaluate(position);
        for (int i = 0; i < before.priors().length; i++) {
            assertEquals(before.priors()[i], after.priors()[i], 1e-4 + 1e-3 * before.priors()[i], "move " + i);
        }
    }

    /**
     * Review finding: from + to logits near the float limit. With all policy weights 0 every cell has the
     * logits (bias0, bias1), so every move has the same logit and the priors are uniform. A float sum of
     * 3e38 + 3e38 is infinite and gave NaN priors.
     */
    @Test
    void priorsStayFiniteWhenFromPlusToOverflowsFloat() {
        for (float bias : new float[] {3e38f, -3e38f, Float.MAX_VALUE, -Float.MAX_VALUE}) {
            Parameters parameters = Parameters.uniform(NetworkConfig.tiny(2), 101, 0.5f);
            Arrays.fill(parameters.get("head_policy.weight").data(), 0f);
            Arrays.fill(parameters.get("head_policy.bias").data(), bias);
            Position position = randomGame(2, 0, 10);
            Evaluation e = new Evaluator(new Network(parameters)).evaluate(position);
            float uniform = 1f / e.moves().length;
            double sum = 0;
            for (float p : e.priors()) {
                assertEquals(uniform, p, 1e-6, "bias " + bias);
                sum += p;
            }
            assertEquals(1.0, sum, 1e-6, "bias " + bias);
        }
    }

    /**
     * Non-finite network outputs are an error, not NaN priors: an infinite or NaN logit of a legal move or a
     * NaN value gives an IllegalStateException.
     */
    @Test
    void nonFiniteNetworkOutputsAreRejected() {
        Position position = randomGame(2, 0, 10);
        for (float bad : new float[] {Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NaN}) {
            for (String tensor : List.of("head_policy.bias", "head_value.dense2.bias")) {
                for (int index = 0; index < 2; index++) {
                    Parameters parameters = Parameters.uniform(NetworkConfig.tiny(2), 101, 0.5f);
                    parameters.get(tensor).data()[index] = bad;
                    Evaluator evaluator = new Evaluator(new Network(parameters));
                    String what = tensor + "[" + index + "] = " + bad;
                    if (tensor.startsWith("head_value") && !Float.isNaN(bad)) {
                        // tanh(+-inf) = +-1 is a valid value.
                        Evaluation e = evaluator.evaluate(position);
                        assertEquals(Math.signum(bad), e.values()[index], what);
                        continue;
                    }
                    IllegalStateException ex = assertThrows(IllegalStateException.class,
                            () -> evaluator.evaluate(position), what);
                    assertTrue(ex.getMessage().contains("not finite") || ex.getMessage().contains("NaN"),
                            ex.getMessage());
                }
            }
        }
    }

    /**
     * Review finding (mutant M30): the softmax must subtract the largest logit, not the first. The policy is
     * built by hand; the reference prior of move i is 1 / sum_j exp(l_j - l_i) in double, which needs no
     * maximum (a huge exponent gives 1 / inf = 0, the right limit).
     */
    @Test
    void softmaxSubtractsTheLargestLogitNotTheFirst() {
        Position position = Position.initial(RuleSet.defaults(2));
        int[] moves = position.legalMoves();
        Orientation orientation = Orientation.of(position);
        int firstFrom = orientation.toCanonical(Move.from(moves[0]));
        Random random = new Random(7);
        // First legal move much smaller than others: exp(l - l0) overflows if l0 is subtracted.
        // First legal move much larger, all logits near -2000: exp(l) underflows to 0 without a subtraction.
        for (double[] shift : new double[][] {{0, -1000}, {-2000, -1990}}) {
            float[] policy = new float[Encoder.POLICY_SIZE];
            for (int i = 0; i < policy.length; i++) {
                policy[i] = (float) (shift[0] / 2 + random.nextDouble() * 4 - 2);
            }
            policy[Encoder.policyIndex(firstFrom, Encoder.FROM)] = (float) (shift[1] - shift[0] / 2);
            float[] priors = Evaluator.priors(orientation, policy, 0, moves);

            double[] logits = new double[moves.length];
            for (int i = 0; i < moves.length; i++) {
                int f = orientation.toCanonical(Move.from(moves[i]));
                int t = orientation.toCanonical(Move.to(moves[i]));
                logits[i] = (double) policy[2 * f] + policy[2 * t + 1];
            }
            boolean otherCell = false;
            double sum = 0;
            for (int i = 0; i < moves.length; i++) {
                double denominator = 0;
                for (double l : logits) {
                    denominator += Math.exp(l - logits[i]);
                }
                double expected = 1 / denominator;
                assertTrue(Float.isFinite(priors[i]), "prior " + i + " is " + priors[i]);
                assertEquals(expected, priors[i], 1e-6 + 1e-5 * expected, "shift " + shift[1] + ", move " + i);
                sum += priors[i];
                otherCell |= orientation.toCanonical(Move.from(moves[i])) != firstFrom;
            }
            assertTrue(otherCell, "some move starts elsewhere than the first move");
            assertEquals(1.0, sum, 1e-6);
        }
    }

    /**
     * Review finding (mutant M29): each position of a batch uses its own orientation. The batches mix movers,
     * so an orientation taken from another position of the batch changes the priors.
     */
    @Test
    void batchWithMixedMoversEqualsSingleEvaluations() {
        for (int players = 2; players <= 3; players++) {
            Network network = players == 2 ? TWO : THREE;
            Evaluator evaluator = new Evaluator(network);
            List<Position> positions = new ArrayList<>();
            // Plies 6..6+2P-1: every mover appears twice, the first position has mover 0.
            for (int plies = 6; plies < 6 + 2 * players; plies++) {
                positions.add(randomGame(players, plies, plies));
            }
            List<Integer> movers = positions.stream().map(Position::sideToMove).distinct().sorted().toList();
            assertEquals(players == 2 ? List.of(0, 1) : List.of(0, 1, 2), movers, "all movers in the batch");
            // Also with a batch that does not start with mover 0.
            for (List<Position> batch : List.of(positions, positions.reversed())) {
                List<Evaluation> result = evaluator.evaluate(batch);
                for (int i = 0; i < batch.size(); i++) {
                    Evaluation single = evaluator.evaluate(batch.get(i));
                    String what = players + " players, mover " + batch.get(i).sideToMove() + ", position " + i;
                    assertArrayEquals(single.moves(), result.get(i).moves(), what);
                    assertArrayEquals(single.priors(), result.get(i).priors(), what);
                    assertArrayEquals(single.values(), result.get(i).values(), what);
                }
            }
        }
    }

    /**
     * Review finding: an Evaluator shared between threads. On a thread-safe network every result is right;
     * on a VectorNetwork a concurrent call throws IllegalStateException. A wrong value is never acceptable.
     * (Without the busy flag of VectorNetwork, nearly every overlapping call gave wrong priors or values.)
     */
    @Test
    void sharedEvaluatorGivesRightValuesOrFailsLoudly() throws Exception {
        Parameters parameters = Parameters.init(NetworkConfig.small(2), 3);
        List<Position> positions = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            positions.add(randomGame(2, 40 + i, 10 + i));
        }
        // Compared with the tolerance, not bit for bit: the vector path is not bit-reproducible while the JIT
        // warms up (a measured last-digit difference between the first calls and later ones, single thread).
        Evaluator reference = new Evaluator(new Network(parameters));
        List<Evaluation> expected = positions.stream().map(reference::evaluate).toList();
        for (ForwardPass network : List.<ForwardPass>of(new VectorNetwork(parameters), new Network(parameters))) {
            Evaluator shared = new Evaluator(network);
            int threads = 4;
            int rounds = network instanceof Network ? 5 : 60;
            AtomicInteger wrong = new AtomicInteger();
            AtomicInteger rejected = new AtomicInteger();
            AtomicInteger right = new AtomicInteger();
            CyclicBarrier start = new CyclicBarrier(threads);
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                List<Future<?>> futures = new ArrayList<>();
                for (int th = 0; th < threads; th++) {
                    int offset = th;
                    // Position has scratch buffers for move generation, so each thread gets its own copies.
                    List<Position> own = positions.stream().map(Position::copy).toList();
                    futures.add(pool.submit(() -> {
                        start.await();
                        for (int r = 0; r < rounds; r++) {
                            int i = (offset + r) % own.size();
                            try {
                                Evaluation e = shared.evaluate(own.get(i));
                                if (agree(e.priors(), expected.get(i).priors())
                                        && agree(e.values(), expected.get(i).values())) {
                                    right.incrementAndGet();
                                } else {
                                    wrong.incrementAndGet();
                                }
                            } catch (IllegalStateException ex) {
                                rejected.incrementAndGet();
                            }
                        }
                        return null;
                    }));
                }
                for (Future<?> f : futures) {
                    f.get(60, TimeUnit.SECONDS);
                }
            } finally {
                pool.shutdownNow();
            }
            String what = network.getClass().getSimpleName() + ": " + right + " right, " + rejected
                    + " rejected, " + wrong + " wrong";
            assertEquals(0, wrong.get(), what);
            assertEquals(threads * rounds, right.get() + rejected.get(), what);
            if (network.threadSafe()) {
                assertEquals(0, rejected.get(), what);
            } else {
                // The calls did overlap (else the test proves nothing), and calls without overlap still work.
                assertTrue(rejected.get() > 0 && right.get() > 0, what);
            }
        }
    }

    /**
     * Values come back in absolute player order: value[a] = output[(a - mover) mod 3]. The three outputs
     * differ, so any other permutation fails.
     */
    @Test
    void valuesAreReorderedToAbsolutePlayersForEveryMover() {
        for (int mover = 0; mover < 3; mover++) {
            Position position = Position.initial(RuleSet.defaults(3)).setSideToMove(mover);
            float[] input = new float[Encoder.INPUT_SIZE];
            Encoder.encode(position, input, 0);
            float[] raw = new float[3];
            THREE.forward(input, 1, new float[Encoder.POLICY_SIZE], raw);
            assertTrue(raw[0] != raw[1] && raw[1] != raw[2] && raw[0] != raw[2], "distinct outputs");

            float[] values = new Evaluator(THREE).evaluate(position).values();
            for (int a = 0; a < 3; a++) {
                assertEquals(raw[Math.floorMod(a - mover, 3)], values[a], "mover " + mover + ", player " + a);
            }
            // Spelled out once, so that the formula above cannot share a sign error with the code.
            if (mover == 1) {
                assertEquals(raw[0], values[1]);
                assertEquals(raw[1], values[2]);
                assertEquals(raw[2], values[0]);
            }
        }
    }

    @Test
    void batchEqualsSingleEvaluations() {
        List<Position> positions = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            positions.add(randomGame(3, i, 5 + 3 * i));
        }
        Evaluator evaluator = new Evaluator(THREE);
        List<Evaluation> batch = evaluator.evaluate(positions);
        assertEquals(positions.size(), batch.size());
        for (int i = 0; i < positions.size(); i++) {
            Evaluation single = evaluator.evaluate(positions.get(i));
            assertArrayEquals(single.moves(), batch.get(i).moves(), "moves " + i);
            assertArrayEquals(single.priors(), batch.get(i).priors(), "priors " + i);
            assertArrayEquals(single.values(), batch.get(i).values(), "values " + i);
        }
        assertNotEquals(batch.get(0).values()[0], batch.get(1).values()[0], "positions are distinguishable");
    }

    @Test
    void evaluationLeavesThePositionUnchanged() {
        Position position = randomGame(2, 3, 20);
        Position copy = position.copy();
        new Evaluator(TWO).evaluate(position);
        assertEquals(copy, position);
        assertEquals(copy.hash(), position.hash());
        assertEquals(copy.ply(), position.ply());
    }

    /** A side to move without pieces has no legal move: empty policy, values still present. */
    @Test
    void noLegalMovesGiveAnEmptyPolicy() {
        Position position = Position.empty(RuleSet.defaults(2)).put(1, CENTRE_CELL).setSideToMove(0);
        Evaluation e = new Evaluator(TWO).evaluate(position);
        assertEquals(0, e.moves().length);
        assertEquals(0, e.priors().length);
        assertEquals(2, e.values().length);
    }

    @Test
    void rejectsWrongPlayerCountsAndEmptyBatches() {
        Evaluator evaluator = new Evaluator(TWO);
        assertThrows(IllegalArgumentException.class,
                () -> evaluator.evaluate(Position.initial(RuleSet.defaults(3))));
        assertThrows(IllegalArgumentException.class, () -> evaluator.evaluate(List.of()));
        assertThrows(IllegalArgumentException.class, () -> evaluator.evaluate(
                List.of(Position.initial(RuleSet.defaults(2)), Position.initial(RuleSet.defaults(3)))));
        assertThrows(NullPointerException.class, () -> new Evaluator(null));
    }

    /**
     * The canonical orientation end to end: a two-player position and its colour-swapped 180-degree image
     * look the same to the network, so the values swap players and each move gets the same prior as its image.
     * Both positions are set up with put, so they have the same ply (plane 6).
     */
    @Test
    void colourSwappedImageHasTheSameEvaluation() {
        Evaluator evaluator = new Evaluator(TWO);
        for (int seed = 0; seed < 4; seed++) {
            for (int mover = 0; mover < 2; mover++) {
                Position p = rebuilt(randomGame(2, seed, 10 + 7 * seed), mover);
                Position q = p.transformed(Geometry::rotate180, true);
                assertEquals(1 - mover, q.sideToMove());
                Evaluation ep = evaluator.evaluate(p);
                Evaluation eq = evaluator.evaluate(q);

                assertEquals(ep.values()[0], eq.values()[1], 1e-6, "seed " + seed);
                assertEquals(ep.values()[1], eq.values()[0], 1e-6, "seed " + seed);

                Map<Integer, Float> imagePriors = new HashMap<>();
                for (int i = 0; i < eq.moves().length; i++) {
                    imagePriors.put(eq.moves()[i], eq.priors()[i]);
                }
                assertEquals(ep.moves().length, imagePriors.size());
                for (int i = 0; i < ep.moves().length; i++) {
                    int move = ep.moves()[i];
                    int image = Move.of(Geometry.rotate180(Move.from(move)), Geometry.rotate180(Move.to(move)));
                    Float prior = imagePriors.get(image);
                    assertTrue(prior != null, "image of " + Move.toString(move) + " is legal");
                    assertEquals(ep.priors()[i], prior, 1e-6, Move.toString(move));
                }
            }
        }
        // Control: the evaluation is not trivially symmetric, the two players' values differ.
        Evaluation e = evaluator.evaluate(rebuilt(randomGame(2, 0, 10), 1));
        assertNotEquals(e.values()[0], e.values()[1]);
    }

    private static final int CENTRE_CELL = 60;

    /** Same length and every element agrees with the reference within the tolerance of docs/network.md. */
    private static boolean agree(float[] actual, float[] ref) {
        if (actual.length != ref.length) {
            return false;
        }
        for (int i = 0; i < ref.length; i++) {
            if (!Network.agrees(actual[i], ref[i])) {
                return false;
            }
        }
        return true;
    }

    /** The raw logits of the legal moves, computed outside the evaluator. */
    private static double[] logits(Network network, Position position) {
        float[] input = new float[Encoder.INPUT_SIZE];
        Encoder.encode(position, input, 0);
        float[] policy = new float[Encoder.POLICY_SIZE];
        network.forward(input, 1, policy, new float[network.config().players()]);
        Orientation orientation = Orientation.of(position);
        int[] moves = position.legalMoves();
        double[] logits = new double[moves.length];
        for (int i = 0; i < moves.length; i++) {
            logits[i] = Encoder.moveLogit(orientation, policy, 0, moves[i]);
        }
        return logits;
    }

    /** A position after random legal moves from the start; stops early if the game ends. */
    static Position randomGame(int players, long seed, int plies) {
        Position position = Position.initial(RuleSet.defaults(players));
        Random random = new Random(seed);
        for (int i = 0; i < plies && position.outcome() == null; i++) {
            int[] moves = position.legalMoves();
            if (moves.length == 0) {
                break;
            }
            position.make(moves[random.nextInt(moves.length)]);
        }
        return position;
    }

    /** The same pieces set up with put, at ply 0, with the given side to move. */
    private static Position rebuilt(Position position, int sideToMove) {
        Position result = Position.empty(position.rules());
        for (int c = 0; c < Geometry.CELLS; c++) {
            if (position.owner(c) >= 0) {
                result.put(position.owner(c), c);
            }
        }
        return result.setSideToMove(sideToMove);
    }
}
