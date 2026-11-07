package sternhalma.nn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import sternhalma.engine.Corner;
import sternhalma.engine.Geometry;
import sternhalma.engine.Layout;
import sternhalma.engine.Move;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;

class OrientationTest {

    /** Every (players, mover) pair. */
    private static final int[][] CASES = {{2, 0}, {2, 1}, {3, 0}, {3, 1}, {3, 2}};

    // ---- item 1: bijection and inverse ----

    @Test
    void mapsAreBijectionsWithInverse() {
        for (int[] pm : CASES) {
            Orientation o = Orientation.of(pm[0], pm[1]);
            boolean[] hit = new boolean[Geometry.CELLS];
            for (int c = 0; c < Geometry.CELLS; c++) {
                int image = o.toCanonical(c);
                assertTrue(image >= 0 && image < Geometry.CELLS, o + ": cell " + c + " -> " + image);
                assertTrue(!hit[image], o + ": two cells map to " + image);
                hit[image] = true;
                assertEquals(c, o.fromCanonical(image), o + ": fromCanonical(toCanonical(" + c + "))");
                assertEquals(c, o.toCanonical(o.fromCanonical(c)), o + ": toCanonical(fromCanonical(" + c + "))");
            }
        }
    }

    // ---- item 2: neighbourhood, and a rotation rather than a mirror ----

    /**
     * A rotation sends direction d of every cell to one fixed direction sigma(d). This is stronger than "neighbours
     * stay neighbours": it also requires the same direction permutation everywhere.
     */
    @Test
    void mapsPreserveNeighbourhoodWithOneDirectionPermutation() {
        int centre = Geometry.id(0, 0);
        for (int[] pm : CASES) {
            Orientation o = Orientation.of(pm[0], pm[1]);
            int[] sigma = new int[Geometry.DIRECTIONS];
            for (int d = 0; d < Geometry.DIRECTIONS; d++) {
                sigma[d] = directionBetween(o.toCanonical(centre), o.toCanonical(Geometry.neighbor(centre, d)));
                assertTrue(sigma[d] >= 0, o + ": image of a centre neighbour is not a neighbour");
            }
            for (int c = 0; c < Geometry.CELLS; c++) {
                for (int d = 0; d < Geometry.DIRECTIONS; d++) {
                    int n = Geometry.neighbor(c, d);
                    int imageNeighbour = Geometry.neighbor(o.toCanonical(c), sigma[d]);
                    // Off the board on one side means off the board on the other side.
                    assertEquals(n < 0 ? -1 : o.toCanonical(n), imageNeighbour, o + ": cell " + c + ", direction " + d);
                }
            }
        }
    }

    /**
     * A mirror also preserves neighbourhood, so this checks the orientation: the image of the axial basis (1, 0),
     * (0, 1) must have determinant +1 (a mirror gives -1).
     */
    @Test
    void mapsAreRotationsNotMirrors() {
        int centre = Geometry.id(0, 0);
        for (int[] pm : CASES) {
            Orientation o = Orientation.of(pm[0], pm[1]);
            int c = o.toCanonical(centre);
            int a = o.toCanonical(Geometry.id(1, 0));
            int b = o.toCanonical(Geometry.id(0, 1));
            int aq = Geometry.q(a) - Geometry.q(c);
            int ar = Geometry.r(a) - Geometry.r(c);
            int bq = Geometry.q(b) - Geometry.q(c);
            int br = Geometry.r(b) - Geometry.r(c);
            assertEquals(1, aq * br - ar * bq, o + ": determinant");
        }
    }

    /**
     * The screen angle of the rotation (pointy-top layout, y down, a rising angle turns clockwise): 180 degrees for
     * mover 1 of 2, -120 degrees (counter-clockwise) for mover 1 of 3, +120 degrees for mover 2 of 3.
     */
    @Test
    void rotationAnglesOnScreen() {
        assertEquals(0, rotationDegrees(Orientation.of(2, 0)));
        assertEquals(180, rotationDegrees(Orientation.of(2, 1)));
        assertEquals(0, rotationDegrees(Orientation.of(3, 0)));
        assertEquals(240, rotationDegrees(Orientation.of(3, 1)));
        assertEquals(120, rotationDegrees(Orientation.of(3, 2)));
    }

    // ---- items 3 and 4: corners and Layout ----

    @Test
    void moverStartGoesToTopAndGoalToBottom() {
        for (int[] pm : CASES) {
            Orientation o = Orientation.of(pm[0], pm[1]);
            Layout layout = Layout.of(pm[0]);
            assertEquals(sorted(Geometry.cornerCells(Corner.R_NEG.ordinal())),
                    sorted(image(o, Geometry.cornerCells(layout.startCorner(pm[1])))), o + ": start corner");
            assertEquals(sorted(Geometry.cornerCells(Corner.R_POS.ordinal())),
                    sorted(image(o, Geometry.cornerCells(layout.goalCorner(pm[1])))), o + ": goal corner");
        }
    }

    /** Every corner goes to a whole corner; the tables of docs/network.md, "Where the corners go". */
    @Test
    void cornerTablesOfTheDesign() {
        assertCorners(Orientation.of(2, 1), Corner.R_POS, Corner.R_NEG);
        assertCorners(Orientation.of(2, 1), Corner.R_NEG, Corner.R_POS);
        assertCorners(Orientation.of(2, 1), Corner.Q_POS, Corner.Q_NEG);
        assertCorners(Orientation.of(3, 1), Corner.R_NEG, Corner.Q_NEG);
        assertCorners(Orientation.of(3, 1), Corner.S_NEG, Corner.R_NEG);
        assertCorners(Orientation.of(3, 1), Corner.Q_NEG, Corner.S_NEG);
        assertCorners(Orientation.of(3, 2), Corner.R_NEG, Corner.S_NEG);
        assertCorners(Orientation.of(3, 2), Corner.S_NEG, Corner.Q_NEG);
        assertCorners(Orientation.of(3, 2), Corner.Q_NEG, Corner.R_NEG);
        for (int[] pm : CASES) {
            Orientation o = Orientation.of(pm[0], pm[1]);
            for (int corner = 0; corner < 6; corner++) {
                int target = Geometry.cornerOf(o.toCanonical(Geometry.cornerCells(corner)[0]));
                assertCorners(o, Corner.ofIndex(corner), Corner.ofIndex(target));
            }
            for (int c = 0; c < Geometry.CELLS; c++) {
                if (Geometry.cornerOf(c) < 0) {
                    assertEquals(-1, Geometry.cornerOf(o.toCanonical(c)), o + ": hexagon cell " + c);
                }
            }
        }
    }

    /**
     * Relative player i of the real board plays like player i of the canonical board: same start and goal corner,
     * same allowed cells, same single-agent distances (items 4 and 5).
     */
    @Test
    void consistentWithLayoutForEveryRelativePlayer() {
        for (int[] pm : CASES) {
            Orientation o = Orientation.of(pm[0], pm[1]);
            Layout layout = Layout.of(pm[0]);
            for (int i = 0; i < pm[0]; i++) {
                int a = o.absolutePlayer(i);
                assertEquals(sorted(Geometry.cornerCells(layout.startCorner(i))),
                        sorted(image(o, Geometry.cornerCells(layout.startCorner(a)))), o + ": start of relative " + i);
                assertEquals(sorted(Geometry.cornerCells(layout.goalCorner(i))),
                        sorted(image(o, Geometry.cornerCells(layout.goalCorner(a)))), o + ": goal of relative " + i);
                for (int c = 0; c < Geometry.CELLS; c++) {
                    int cc = o.toCanonical(c);
                    assertEquals(layout.allowed(a, c), layout.allowed(i, cc), o + ": allowed, relative " + i + ", cell " + c);
                    assertEquals(layout.distance(a, c), layout.distance(i, cc), o + ": distance, relative " + i + ", cell " + c);
                }
            }
        }
    }

    // ---- item 6 and compositions ----

    @Test
    void twoPlayersMoverOneIsRotate180() {
        Orientation o = Orientation.of(2, 1);
        for (int c = 0; c < Geometry.CELLS; c++) {
            assertEquals(Geometry.rotate180(c), o.toCanonical(c), "cell " + c);
        }
    }

    @Test
    void moverZeroIsTheIdentity() {
        for (int players = 2; players <= 3; players++) {
            Orientation o = Orientation.of(players, 0);
            for (int c = 0; c < Geometry.CELLS; c++) {
                assertEquals(c, o.toCanonical(c));
                assertEquals(c, o.fromCanonical(c));
            }
        }
    }

    /** Twice the 180-degree turn and three times a 120-degree turn are the identity; two 120-degree turns are the other one. */
    @Test
    void compositions() {
        Orientation two = Orientation.of(2, 1);
        Orientation one = Orientation.of(3, 1);
        Orientation other = Orientation.of(3, 2);
        for (int c = 0; c < Geometry.CELLS; c++) {
            assertEquals(c, two.toCanonical(two.toCanonical(c)));
            assertEquals(c, one.toCanonical(one.toCanonical(one.toCanonical(c))));
            assertEquals(c, other.toCanonical(other.toCanonical(other.toCanonical(c))));
            assertEquals(other.toCanonical(c), one.toCanonical(one.toCanonical(c)));
            assertEquals(other.toCanonical(c), one.fromCanonical(c), "the two 120-degree turns are inverse");
        }
    }

    /** The example cells of docs/network.md, by id and by axial coordinates. */
    @Test
    void exampleCellsOfTheDesign() {
        int tipRNeg = Geometry.id(4, -8);
        int tipSNeg = Geometry.id(4, 4);
        int tipQNeg = Geometry.id(-8, 4);
        int centre = Geometry.id(0, 0);
        assertEquals(List.of(0, 110, 98, 60), List.of(tipRNeg, tipSNeg, tipQNeg, centre));

        Orientation two = Orientation.of(2, 1);
        assertEquals(120, two.toCanonical(0));
        assertEquals(10, two.toCanonical(110));
        assertEquals(22, two.toCanonical(98));
        assertEquals(Geometry.id(-4, 8), two.toCanonical(0));
        assertEquals(Geometry.id(-4, -4), two.toCanonical(110));
        assertEquals(Geometry.id(8, -4), two.toCanonical(98));

        Orientation one = Orientation.of(3, 1);
        assertEquals(98, one.toCanonical(0));
        assertEquals(0, one.toCanonical(110));
        assertEquals(110, one.toCanonical(98));

        Orientation other = Orientation.of(3, 2);
        assertEquals(110, other.toCanonical(0));
        assertEquals(98, other.toCanonical(110));
        assertEquals(0, other.toCanonical(98));
        for (int[] pm : CASES) {
            assertEquals(60, Orientation.of(pm[0], pm[1]).toCanonical(60));
        }
    }

    @Test
    void centreIsTheOnlyFixedCellOfARotation() {
        for (Orientation o : List.of(Orientation.of(2, 1), Orientation.of(3, 1), Orientation.of(3, 2))) {
            for (int c = 0; c < Geometry.CELLS; c++) {
                assertEquals(c == 60, o.toCanonical(c) == c, o + ": cell " + c);
            }
        }
    }

    // ---- players ----

    @Test
    void relativeAndAbsolutePlayers() {
        Orientation one = Orientation.of(3, 1);
        // Mover 1: the next player is 2, the one after is 0.
        assertEquals(0, one.relativePlayer(1));
        assertEquals(1, one.relativePlayer(2));
        assertEquals(2, one.relativePlayer(0));
        assertEquals(1, one.absolutePlayer(0));
        assertEquals(2, one.absolutePlayer(1));
        assertEquals(0, one.absolutePlayer(2));
        Orientation other = Orientation.of(3, 2);
        assertEquals(1, other.relativePlayer(0));
        assertEquals(2, other.relativePlayer(1));
        assertEquals(0, other.absolutePlayer(1));
        Orientation two = Orientation.of(2, 1);
        assertEquals(1, two.relativePlayer(0));
        assertEquals(0, two.absolutePlayer(1));
        for (int[] pm : CASES) {
            Orientation o = Orientation.of(pm[0], pm[1]);
            assertEquals(pm[0], o.players());
            assertEquals(pm[1], o.mover());
            for (int p = 0; p < pm[0]; p++) {
                assertEquals(p, o.absolutePlayer(o.relativePlayer(p)));
                assertEquals(p, o.relativePlayer(o.absolutePlayer(p)));
            }
            assertThrows(IllegalArgumentException.class, () -> o.relativePlayer(pm[0]));
            assertThrows(IllegalArgumentException.class, () -> o.relativePlayer(-1));
            assertThrows(IllegalArgumentException.class, () -> o.absolutePlayer(pm[0]));
            assertThrows(IllegalArgumentException.class, () -> o.absolutePlayer(-1));
        }
    }

    @Test
    void factoryChecksArgumentsAndUsesTheSideToMove() {
        assertThrows(IllegalArgumentException.class, () -> Orientation.of(4, 0));
        assertThrows(IllegalArgumentException.class, () -> Orientation.of(1, 0));
        assertThrows(IllegalArgumentException.class, () -> Orientation.of(2, 2));
        assertThrows(IllegalArgumentException.class, () -> Orientation.of(3, 3));
        assertThrows(IllegalArgumentException.class, () -> Orientation.of(3, -1));
        assertSame(Orientation.of(3, 1), Orientation.of(3, 1));
        Position position = Position.initial(RuleSet.defaults(3)).setSideToMove(2);
        assertSame(Orientation.of(3, 2), Orientation.of(position));
        Position afterMove = Position.initial(RuleSet.defaults(3));
        afterMove.make(afterMove.legalMoves()[0]);
        assertEquals(1, Orientation.of(afterMove).mover());
    }

    @Test
    void cellsOutOfRangeAreRejected() {
        Orientation o = Orientation.of(3, 1);
        assertThrows(IndexOutOfBoundsException.class, () -> o.toCanonical(121));
        assertThrows(IndexOutOfBoundsException.class, () -> o.toCanonical(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> o.fromCanonical(121));
        assertThrows(IndexOutOfBoundsException.class, () -> o.toCanonicalMove(Move.of(0, 121)));
        assertThrows(IndexOutOfBoundsException.class, () -> o.fromCanonicalMove(Move.of(200, 0)));
    }

    // ---- move mapping ----

    @Test
    void movesByHand() {
        // Mover 1 of 2: (4,-8) -> (4,-7) becomes (-4,8) -> (-4,7).
        assertEquals(Move.of(Geometry.id(-4, 8), Geometry.id(-4, 7)),
                Orientation.of(2, 1).toCanonicalMove(Move.of(Geometry.id(4, -8), Geometry.id(4, -7))));
        // Mover 1 of 3, (q, r) -> (r, -q - r): (4,4) -> (3,4) becomes (4,-8) -> (4,-7).
        assertEquals(Move.of(Geometry.id(4, -8), Geometry.id(4, -7)),
                Orientation.of(3, 1).toCanonicalMove(Move.of(Geometry.id(4, 4), Geometry.id(3, 4))));
        // Mover 2 of 3, (q, r) -> (-q - r, q): (-8,4) -> (-7,4) becomes (4,-8) -> (3,-7).
        assertEquals(Move.of(Geometry.id(4, -8), Geometry.id(3, -7)),
                Orientation.of(3, 2).toCanonicalMove(Move.of(Geometry.id(-8, 4), Geometry.id(-7, 4))));
        // The start cell stays in the low byte: from and to are not swapped.
        int m = Orientation.of(3, 0).toCanonicalMove(Move.of(5, 17));
        assertEquals(5, Move.from(m));
        assertEquals(17, Move.to(m));
    }

    /**
     * For every legal move of random positions: the mapping round-trips, and the canonical moves are exactly the
     * legal moves of the canonical position (built independently of the encoder). The second check fails for any
     * map that is not a symmetry of the rules for this mover.
     */
    @Test
    void legalMovesRoundTripAndMatchTheCanonicalPosition() {
        int checkedMoves = 0;
        int[] moversSeen = new int[3];
        for (int players = 2; players <= 3; players++) {
            for (Position position : Playouts.positions(players, 3, 150, 1234 + players)) {
                Orientation o = Orientation.of(position);
                moversSeen[o.mover()]++;
                int[] legal = position.legalMoves();
                int[] canonical = new int[legal.length];
                for (int i = 0; i < legal.length; i++) {
                    canonical[i] = o.toCanonicalMove(legal[i]);
                    assertEquals(legal[i], o.fromCanonicalMove(canonical[i]), Move.toString(legal[i]));
                    checkedMoves++;
                }
                assertEquals(sorted(Playouts.canonical(position).legalMoves()), sorted(canonical),
                        o + "\n" + position);
            }
        }
        assertTrue(checkedMoves > 10_000, "only " + checkedMoves + " moves checked");
        assertTrue(moversSeen[0] > 100 && moversSeen[1] > 100 && moversSeen[2] > 100, Arrays.toString(moversSeen));
    }

    // ---- helpers ----

    /** The direction d with neighbor(a, d) == b, or -1. */
    private static int directionBetween(int a, int b) {
        for (int d = 0; d < Geometry.DIRECTIONS; d++) {
            if (Geometry.neighbor(a, d) == b) {
                return d;
            }
        }
        return -1;
    }

    /** Screen angle in degrees [0, 360) by which the map turns the vector (1, 0), rounded to an integer. */
    private static long rotationDegrees(Orientation o) {
        int c = o.toCanonical(Geometry.id(0, 0));
        int a = o.toCanonical(Geometry.id(1, 0));
        int dq = Geometry.q(a) - Geometry.q(c);
        int dr = Geometry.r(a) - Geometry.r(c);
        // Pointy-top pixel coordinates with y pointing down; (1, 0) has angle 0.
        double x = Math.sqrt(3) * (dq + dr / 2.0);
        double y = 1.5 * dr;
        double degrees = Math.toDegrees(Math.atan2(y, x));
        return Math.round((degrees + 360) % 360);
    }

    private static void assertCorners(Orientation o, Corner from, Corner to) {
        assertEquals(sorted(Geometry.cornerCells(to.ordinal())), sorted(image(o, Geometry.cornerCells(from.ordinal()))),
                o + ": " + from + " -> " + to);
    }

    private static int[] image(Orientation o, int[] cells) {
        int[] result = new int[cells.length];
        for (int i = 0; i < cells.length; i++) {
            result[i] = o.toCanonical(cells[i]);
        }
        return result;
    }

    private static List<Integer> sorted(int[] values) {
        return Arrays.stream(values).sorted().boxed().toList();
    }
}
