package sternhalma.tornado;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
import sternhalma.nn.ResidualPriors;

/**
 * {@link TornadoNetwork} for the plane set {@code classic1v} (15 input planes, value head of width 3, format version 3)
 * agrees with the reference {@link Network} on positions of all four variants. The device code is generic in the plane
 * count and in the number of players; this test pins that for the new combination.
 */
class TornadoVariantsTest {

    private static List<Position> mixed() {
        List<Position> result = new ArrayList<>();
        Random random = new Random(5);
        for (int players = 2; players <= 3; players++) {
            for (RuleSet.JumpRule jump : RuleSet.JumpRule.values()) {
                Position position = Position.initial(RuleSet.defaults(players).withMoveLimit(1000).withJumpRule(jump));
                for (int ply = 0; ply < 40 && position.outcome() == null; ply++) {
                    int[] moves = position.legalMoves();
                    position.make(moves[random.nextInt(moves.length)]);
                    if (ply % 3 == 0) {
                        result.add(position.copy());
                    }
                }
            }
        }
        return result;
    }

    @Test
    void classic1vAgreesWithTheReference() {
        TornadoAssumptions.assumeTornado();
        NetworkConfig config = NetworkConfig.tiny(3).withPlanes(PlaneSet.CLASSIC1V);
        Parameters parameters = Parameters.uniform(config, 41, (float) Math.sqrt(6.0 / (7 * config.channels())));
        parameters.get(ResidualPriors.BETA).data()[0] = 0.7f;
        parameters.get(ResidualPriors.K).data()[0] = 1.3f;
        List<Position> positions = mixed();
        int batch = positions.size();
        assertTrue(batch > 20);
        int size = config.inputSize();
        float[] input = new float[batch * size];
        for (int b = 0; b < batch; b++) {
            Encoder.encode(config.planes(), positions.get(b), input, b * size);
        }
        float[] refPolicy = new float[batch * Encoder.POLICY_SIZE];
        float[] refValues = new float[batch * 3];
        new Network(parameters).forward(input, batch, refPolicy, refValues);
        float[] policy = new float[batch * Encoder.POLICY_SIZE];
        float[] values = new float[batch * 3];
        try (TornadoNetwork device = new TornadoNetwork(parameters)) {
            assertEquals(config, device.config());
            device.forward(input, batch, policy, values);
        }
        for (int i = 0; i < policy.length; i++) {
            if (!Network.agrees(policy[i], refPolicy[i])) {
                fail("logit " + i + ": device " + policy[i] + ", reference " + refPolicy[i]);
            }
        }
        for (int i = 0; i < values.length; i++) {
            if (!Network.agrees(values[i], refValues[i])) {
                fail("value " + i + ": device " + values[i] + ", reference " + refValues[i]);
            }
        }
    }
}
