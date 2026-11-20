package sternhalma.nn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;

/**
 * The network encoding is for the 4-row board. Without a guard the encoder takes a 15-piece position and marks only
 * the 10 corner cells as goal (seen red: the encoder accepted it and wrote 10 goal cells, not 15).
 */
class FiveRowsRefusalTest {

    private static Position five(int players) {
        return Position.initial(RuleSet.defaults(players, RuleSet.TriangleRows.FIVE));
    }

    @Test
    void encoderRefusesFiveRowsWithNamedMessage() {
        for (int players : new int[] {2, 3}) {
            Position p = five(players);
            for (PlaneSet planes : PlaneSet.values()) {
                float[] out = new float[planes.inputSize()];
                IllegalArgumentException e =
                        assertThrows(IllegalArgumentException.class, () -> Encoder.encode(planes, p, out, 0));
                assertTrue(e.getMessage().contains("4-row") && e.getMessage().contains("FIVE"), e.getMessage());
            }
        }
    }

    @Test
    void orientationRefusesFiveRows() {
        assertThrows(IllegalArgumentException.class, () -> Orientation.of(five(2)));
        assertThrows(IllegalArgumentException.class, () -> Orientation.requireFourRows(five(3)));
    }

    @Test
    void classicalPlanesRefuseFiveRows() {
        Position p = five(2);
        float[] out = new float[Encoder.INPUT_SIZE * 2];
        assertThrows(IllegalArgumentException.class,
                () -> ClassicalPlanes.fill(p, Orientation.of(2, 0), false, false, out, 0, Encoder.CLASSIC_PLANES + 7));
    }

    @Test
    void fourRowsStillEncode() {
        for (int players : new int[] {2, 3}) {
            Position p = Position.initial(RuleSet.defaults(players));
            float[] out = new float[PlaneSet.PLAIN.inputSize()];
            Encoder.encode(PlaneSet.PLAIN, p, out, 0);
            int goal = 0;
            for (int row = 0; row < sternhalma.engine.Geometry.CELLS; row++) {
                goal += out[Encoder.index(PlaneSet.PLAIN.planes(), row, Encoder.GOAL)] == 1f ? 1 : 0;
            }
            assertEquals(10, goal);
        }
    }
}
