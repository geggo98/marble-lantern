package sternhalma.selfplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import sternhalma.engine.Move;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.nn.Encoder;
import sternhalma.nn.Evaluation;
import sternhalma.nn.Evaluator;
import sternhalma.nn.ForwardPass;
import sternhalma.nn.Network;
import sternhalma.nn.NetworkConfig;
import sternhalma.nn.Orientation;
import sternhalma.nn.Parameters;

/** The raw-policy player: legal, deterministic, the argmax of the masked logits. */
class RawPolicyPlayerTest {

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
                if (ply % 3 == 0) {
                    result.add(p.copy());
                }
                p.make(moves[random.nextInt(moves.length)]);
            }
        }
        return result;
    }

    @Test
    void playsTheLegalMoveWithTheHighestPriorAndIsDeterministic() {
        Parameters parameters = Parameters.init(NetworkConfig.tiny(2), 5);
        Network network = new Network(parameters);
        RawPolicyPlayer player = new RawPolicyPlayer(network, "raw");
        Evaluator evaluator = new Evaluator(network);
        for (Position p : positions(60, 1)) {
            int move = player.choose(p);
            assertEquals(move, player.choose(p), "deterministic");
            Evaluation e = evaluator.evaluate(p);
            int chosen = -1;
            float best = -1;
            for (int i = 0; i < e.moves().length; i++) {
                if (e.moves()[i] == move) {
                    chosen = i;
                }
                best = Math.max(best, e.priors()[i]);
            }
            assertTrue(chosen >= 0, "a legal move");
            assertEquals(best, e.priors()[chosen], 1e-7, "the move with the highest prior");
        }
    }

    /** A network that likes high canonical cells: the logit of a move is 1000 times its from cell plus its to cell. */
    private static class CellLover implements ForwardPass {
        @Override
        public NetworkConfig config() {
            return NetworkConfig.tiny(2);
        }

        @Override
        public boolean threadSafe() {
            return true;
        }

        @Override
        public void forward(float[] input, int batch, float[] policyLogits, float[] values) {
            for (int b = 0; b < batch; b++) {
                for (int c = 0; c < 121; c++) {
                    policyLogits[b * Encoder.POLICY_SIZE + Encoder.policyIndex(c, Encoder.FROM)] = 1000f * c;
                    policyLogits[b * Encoder.POLICY_SIZE + Encoder.policyIndex(c, Encoder.TO)] = c;
                }
            }
        }
    }

    @Test
    void anIllegalCellWithAHugeLogitIsMaskedOut() {
        RawPolicyPlayer player = new RawPolicyPlayer(new CellLover(), "lover");
        for (Position p : positions(40, 2)) {
            Orientation o = Orientation.of(p);
            int expected = -1;
            double best = Double.NEGATIVE_INFINITY;
            for (int m : p.legalMoves()) {
                int cm = o.toCanonicalMove(m);
                double logit = 1000.0 * Move.from(cm) + Move.to(cm);
                if (logit > best) {
                    best = logit;
                    expected = m;
                }
            }
            assertEquals(expected, player.choose(p));
        }
    }

    @Test
    void aNonFiniteLogitOfALegalMoveIsAnError() {
        Parameters parameters = Parameters.init(NetworkConfig.tiny(2), 5);
        parameters.get("head_policy.bias").data()[0] = Float.POSITIVE_INFINITY;
        RawPolicyPlayer player = new RawPolicyPlayer(new Network(parameters), "broken");
        Position start = Position.initial(RuleSet.defaults(2));
        assertThrows(IllegalStateException.class, () -> player.choose(start));
    }

    /** A network whose logits are all equal. */
    private static final class Flat implements ForwardPass {
        @Override
        public NetworkConfig config() {
            return NetworkConfig.tiny(2);
        }

        @Override
        public boolean threadSafe() {
            return true;
        }

        @Override
        public void forward(float[] input, int batch, float[] policyLogits, float[] values) {
            java.util.Arrays.fill(policyLogits, 0, batch * Encoder.POLICY_SIZE, 0.25f);
        }
    }

    @Test
    void equalLogitsTakeTheFirstLegalMove() {
        RawPolicyPlayer player = new RawPolicyPlayer(new Flat(), "flat");
        for (Position p : positions(40, 3)) {
            assertEquals(p.legalMoves()[0], player.choose(p), "the first of equal moves, as the class comment promises");
        }
    }

    @Test
    void aTieAmongTheBestOnlyTakesTheFirstOfThose() {
        // the logit depends on the from cell only (none on the to cell), so every move from one cell ties
        ForwardPass fromOnly = new CellLover() {
            @Override
            public void forward(float[] input, int batch, float[] policyLogits, float[] values) {
                for (int b = 0; b < batch; b++) {
                    for (int c = 0; c < 121; c++) {
                        policyLogits[b * Encoder.POLICY_SIZE + Encoder.policyIndex(c, Encoder.FROM)] = c;
                        policyLogits[b * Encoder.POLICY_SIZE + Encoder.policyIndex(c, Encoder.TO)] = 0;
                    }
                }
            }
        };
        RawPolicyPlayer player = new RawPolicyPlayer(fromOnly, "from-only");
        boolean sawTie = false;
        for (Position p : positions(60, 4)) {
            Orientation o = Orientation.of(p);
            int bestFrom = -1;
            int expected = -1;
            for (int m : p.legalMoves()) {
                int from = Move.from(o.toCanonicalMove(m));
                if (from > bestFrom) {
                    bestFrom = from;
                    expected = m;
                } else if (from == bestFrom) {
                    sawTie = true;
                }
            }
            assertEquals(expected, player.choose(p));
        }
        assertTrue(sawTie, "the positions contain moves that tie for the best");
    }

    /** E11: the raw player of a classical network takes the largest of beta * gain + net logit, with the planes of its network. */
    @Test
    void aClassicalNetworkPlaysTheMoveWithTheHighestPriorToo() {
        for (sternhalma.nn.PlaneSet planes : sternhalma.nn.PlaneSet.values()) {
            Parameters parameters = Parameters.uniform(NetworkConfig.tiny(2).withPlanes(planes), 7, 0.3f);
            Network network = new Network(parameters);
            RawPolicyPlayer player = new RawPolicyPlayer(network, "raw");
            Evaluator evaluator = new Evaluator(network);
            for (Position p : positions(40, 3)) {
                int move = player.choose(p);
                Evaluation e = evaluator.evaluate(p);
                float best = -1;
                float chosen = -2;
                for (int i = 0; i < e.moves().length; i++) {
                    best = Math.max(best, e.priors()[i]);
                    if (e.moves()[i] == move) {
                        chosen = e.priors()[i];
                    }
                }
                assertEquals(best, chosen, 1e-7, planes + ", ply " + p.ply());
            }
        }
    }
}
