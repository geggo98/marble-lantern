package sternhalma.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Conformance tests 1 to 3 of docs/rules.md. */
class BoardTest {

    @Test
    void boardHas121CellsWith61InTheHexagonAnd10InEachCorner() {
        int hexagon = 0;
        int[] corners = new int[6];
        for (int c = 0; c < Geometry.CELLS; c++) {
            int corner = Geometry.cornerOf(c);
            if (corner < 0) {
                hexagon++;
                assertTrue(Math.abs(Geometry.q(c)) <= 4 && Math.abs(Geometry.r(c)) <= 4 && Math.abs(Geometry.s(c)) <= 4);
            } else {
                corners[corner]++;
            }
        }
        assertEquals(61, hexagon);
        for (int k = 0; k < 6; k++) {
            assertEquals(10, corners[k], "corner " + Corner.ofIndex(k));
            assertEquals(10, Geometry.cornerCells(k).length);
        }
        assertEquals(121, Geometry.CELLS);
    }

    @Test
    void cellIdsAreUniqueAndRoundTrip() {
        Set<Integer> ids = new HashSet<>();
        for (int c = 0; c < Geometry.CELLS; c++) {
            assertEquals(c, Geometry.id(Geometry.q(c), Geometry.r(c)));
            assertEquals(0, Geometry.q(c) + Geometry.r(c) + Geometry.s(c));
            ids.add(c);
        }
        assertEquals(121, ids.size());
        assertEquals(-1, Geometry.id(5, 5));
        assertEquals(-1, Geometry.id(-9, 0));
    }

    @Test
    void neighbourRelationIsSymmetricAndEachCellHasTwoToSixNeighbours() {
        for (int c = 0; c < Geometry.CELLS; c++) {
            int count = 0;
            for (int d = 0; d < Geometry.DIRECTIONS; d++) {
                int n = Geometry.neighbor(c, d);
                if (n >= 0) {
                    count++;
                    assertEquals(c, Geometry.neighbor(n, d ^ 1), "opposite direction of " + d);
                }
            }
            assertTrue(count >= 2 && count <= 6, "cell " + c + " has " + count + " neighbours");
        }
    }

    @Test
    void cornersAreInClockwiseOrderAndOppositeCornersAreThreeApart() {
        // docs/rules.md: q > 4, s < -4, r > 4, q < -4, s > 4, r < -4
        assertTrue(Geometry.q(Geometry.cornerCells(Corner.Q_POS.ordinal())[0]) > 4);
        assertTrue(Geometry.s(Geometry.cornerCells(Corner.S_NEG.ordinal())[0]) < -4);
        assertTrue(Geometry.r(Geometry.cornerCells(Corner.R_POS.ordinal())[0]) > 4);
        assertTrue(Geometry.q(Geometry.cornerCells(Corner.Q_NEG.ordinal())[0]) < -4);
        assertTrue(Geometry.s(Geometry.cornerCells(Corner.S_POS.ordinal())[0]) > 4);
        assertTrue(Geometry.r(Geometry.cornerCells(Corner.R_NEG.ordinal())[0]) < -4);
        assertEquals(Corner.R_POS, Corner.R_NEG.opposite());
        assertEquals(Corner.Q_NEG, Corner.Q_POS.opposite());
        assertEquals(Corner.S_POS, Corner.S_NEG.opposite());
    }

    @Test
    void clockwiseIndexMatchesTheScreenAngleOfTheCornerCentre() {
        // pointy-top layout, y down: x = sqrt(3) (q + r/2), y = 3/2 r; a rising angle turns clockwise
        double previous = Double.NEGATIVE_INFINITY;
        for (int k = 0; k < 6; k++) {
            double x = 0;
            double y = 0;
            for (int c : Geometry.cornerCells(k)) {
                x += Math.sqrt(3) * (Geometry.q(c) + Geometry.r(c) / 2.0);
                y += 1.5 * Geometry.r(c);
            }
            double angle = Math.toDegrees(Math.atan2(y, x));
            if (angle < 0 && k > 0) {
                angle += 360; // -30 degrees for index 0, then 30 up to 270 degrees for index 5
            }
            assertTrue(angle > previous, "corner " + k + " angle " + angle + " after " + previous);
            previous = angle;
        }
    }

    @Test
    void twoPlayersStartInOppositeCornersWithTenPiecesEach() {
        Position p = Position.initial(RuleSet.defaults(2));
        assertEquals(Corner.R_NEG.ordinal(), p.layout().startCorner(0));
        assertEquals(Corner.R_POS.ordinal(), p.layout().startCorner(1));
        assertEquals(p.layout().goalCorner(0), p.layout().startCorner(1));
        assertEquals(p.layout().goalCorner(1), p.layout().startCorner(0));
        for (int player = 0; player < 2; player++) {
            assertEquals(10, p.pieces(player).length);
            for (int c : p.pieces(player)) {
                assertEquals(p.layout().startCorner(player), Geometry.cornerOf(c));
            }
        }
        assertEquals(0, p.sideToMove());
        assertFalse(p.hasWon(0) || p.hasWon(1));
    }

    @Test
    void threePlayersStartInEverySecondCornerGoalsAreEmptyAndOrderIsClockwise() {
        Position p = Position.initial(RuleSet.defaults(3));
        Layout layout = p.layout();
        assertEquals(Corner.R_NEG.ordinal(), layout.startCorner(0));
        assertEquals(Corner.S_NEG.ordinal(), layout.startCorner(1));
        assertEquals(Corner.Q_NEG.ordinal(), layout.startCorner(2));
        for (int player = 0; player < 3; player++) {
            int next = (player + 1) % 3;
            // clockwise: the next player's start corner is two corners further round the star
            assertEquals(2, Math.floorMod(layout.startCorner(next) - layout.startCorner(player), 6));
            assertEquals(10, p.pieces(player).length);
            for (int c : Geometry.cornerCells(layout.goalCorner(player))) {
                assertEquals(-1, p.owner(c), "goal of player " + player + " must be empty at the start");
            }
            assertEquals((layout.startCorner(player) + 3) % 6, layout.goalCorner(player));
        }
    }

    @Test
    void playersMayEnterOnlyHexagonStartAndGoalCorner() {
        Layout layout = Layout.of(3);
        for (int player = 0; player < 3; player++) {
            for (int c = 0; c < Geometry.CELLS; c++) {
                int corner = Geometry.cornerOf(c);
                boolean expected = corner < 0 || corner == layout.startCorner(player) || corner == layout.goalCorner(player);
                assertEquals(expected, layout.allowed(player, c));
                assertEquals(ReferenceGenerator.allowed(3, player, c), layout.allowed(player, c));
            }
        }
    }

    @Test
    void distanceTableIsZeroInTheGoalAndGrowsByOneStepPerCell() {
        Layout layout = Layout.of(2);
        for (int player = 0; player < 2; player++) {
            for (int c : Geometry.cornerCells(layout.goalCorner(player))) {
                assertEquals(0, layout.distance(player, c));
            }
            for (int c = 0; c < Geometry.CELLS; c++) {
                if (!layout.allowed(player, c) || layout.distance(player, c) == 0) {
                    continue;
                }
                int best = Integer.MAX_VALUE;
                for (int d = 0; d < Geometry.DIRECTIONS; d++) {
                    int n = Geometry.neighbor(c, d);
                    if (n >= 0 && layout.allowed(player, n)) {
                        best = Math.min(best, layout.distance(player, n));
                    }
                }
                assertEquals(best + 1, layout.distance(player, c));
            }
        }
        // a start-corner tip is the farthest cell: 8 rows to cross plus the corner depth
        int tip = Geometry.id(4, -8);
        assertEquals(layout.maxDistance(0), layout.distance(0, tip));
    }
}
