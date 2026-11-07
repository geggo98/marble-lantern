package sternhalma.nn;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import sternhalma.engine.Corner;
import sternhalma.engine.Geometry;
import sternhalma.engine.Layout;
import sternhalma.engine.Move;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;

class EncoderTest {

    @Test
    void constants() {
        assertEquals(854, Encoder.INPUT_SIZE);
        assertEquals(242, Encoder.POLICY_SIZE);
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7), List.of(Encoder.OWN, Encoder.NEXT, Encoder.THIRD,
                Encoder.GOAL, Encoder.START, Encoder.PLAYABLE, Encoder.PROGRESS, Encoder.PLANES));
        assertEquals(3 * 7 + 5, Encoder.index(3, 5));
        assertEquals(2 * 9 + 1, Encoder.policyIndex(9, Encoder.TO));
    }

    // ---- hand-checked positions ----

    /** Two players, start position, player 0 moves: the canonical picture is the real one. */
    @Test
    void startPositionTwoPlayersByHand() {
        float[] in = encode(Position.initial(RuleSet.defaults(2)));
        // R_NEG = rows r = -8 .. -5 = cells 0 to 9; R_POS = cells 111 to 120.
        assertEquals(range(0, 10), cellsOf(in, Encoder.OWN));
        assertEquals(range(111, 121), cellsOf(in, Encoder.NEXT));
        assertEquals(List.of(), cellsOf(in, Encoder.THIRD));
        assertEquals(range(111, 121), cellsOf(in, Encoder.GOAL));
        assertEquals(range(0, 10), cellsOf(in, Encoder.START));
        assertEquals(81, cellsOf(in, Encoder.PLAYABLE).size());
        for (int c = 0; c < HexGrid.CELLS; c++) {
            assertEquals(0f, in[Encoder.index(c, Encoder.PROGRESS)]);
        }
    }

    /**
     * Three players, player 1 to move, one piece per player on the tips of the start corners. Mover 1 maps the tip of
     * S_NEG (110) to 0, of Q_NEG (98) to 110, of R_NEG (0) to 98. Relative player 1 is player 2, relative player 2 is
     * player 0. A reversed play order would swap planes NEXT and THIRD.
     */
    @Test
    void threePlayersRelativeOrderByHand() {
        Position position = Position.empty(RuleSet.defaults(3))
                .put(0, 0)
                .put(1, 110)
                .put(2, 98)
                .setSideToMove(1);
        float[] in = encode(position);
        assertEquals(List.of(0), cellsOf(in, Encoder.OWN));
        assertEquals(List.of(110), cellsOf(in, Encoder.NEXT));
        assertEquals(List.of(98), cellsOf(in, Encoder.THIRD));
        assertEquals(sorted(Geometry.cornerCells(Corner.R_POS.ordinal())), cellsOf(in, Encoder.GOAL));
        assertEquals(sorted(Geometry.cornerCells(Corner.R_NEG.ordinal())), cellsOf(in, Encoder.START));

        position.setSideToMove(2);
        in = encode(position);
        // Mover 2: 98 -> 0, 0 -> 110, 110 -> 98; next is player 0, third is player 1.
        assertEquals(List.of(0), cellsOf(in, Encoder.OWN));
        assertEquals(List.of(110), cellsOf(in, Encoder.NEXT));
        assertEquals(List.of(98), cellsOf(in, Encoder.THIRD));
    }

    /**
     * A position where every plane holds a different set: three pieces of different players on hexagon cells, so no
     * piece plane equals a corner plane. A swap of any two planes changes the result.
     */
    @Test
    void everyPlaneIsDistinct() {
        Position position = Position.empty(RuleSet.defaults(3).withMoveLimit(4))
                .put(0, Geometry.id(0, 0))
                .put(1, Geometry.id(1, 0))
                .put(2, Geometry.id(0, 1));
        position.make(Move.of(Geometry.id(0, 0), Geometry.id(-1, 0)));
        // Player 1 moves now; ply 1 of 4.
        float[] in = encode(position);
        Orientation o = Orientation.of(3, 1);
        assertEquals(List.of(o.toCanonical(Geometry.id(1, 0))), cellsOf(in, Encoder.OWN));
        assertEquals(List.of(o.toCanonical(Geometry.id(0, 1))), cellsOf(in, Encoder.NEXT));
        assertEquals(List.of(o.toCanonical(Geometry.id(-1, 0))), cellsOf(in, Encoder.THIRD));
        // By hand, (q, r) -> (r, -q - r): (1,0) -> (0,-1), (0,1) -> (1,-1), (-1,0) -> (0,1).
        assertEquals(List.of(Geometry.id(0, -1)), cellsOf(in, Encoder.OWN));
        assertEquals(List.of(Geometry.id(1, -1)), cellsOf(in, Encoder.NEXT));
        assertEquals(List.of(Geometry.id(0, 1)), cellsOf(in, Encoder.THIRD));
        for (int c = 0; c < HexGrid.CELLS; c++) {
            assertEquals(0.25f, in[Encoder.index(c, Encoder.PROGRESS)]);
        }
        List<List<Integer>> sets = new ArrayList<>();
        for (int p = 0; p < Encoder.PROGRESS; p++) {
            sets.add(cellsOf(in, p));
        }
        for (int a = 0; a < sets.size(); a++) {
            for (int b = a + 1; b < sets.size(); b++) {
                assertFalse(sets.get(a).equals(sets.get(b)), "planes " + a + " and " + b);
            }
        }
    }

    /**
     * Two players: after player 0 steps (1,-5) -> (1,-4), player 1 moves and sees the board turned by 180 degrees.
     * Compared with the start position seen by player 1, exactly two rows of plane NEXT change, at the images
     * (-1,5) and (-1,4), and the progress plane becomes 1/200.
     */
    @Test
    void encodingAfterAMoveDiffersExactlyWhereExpected() {
        Position before = Position.initial(RuleSet.defaults(2)).setSideToMove(1);
        float[] reference = encode(before);

        Position after = Position.initial(RuleSet.defaults(2));
        after.make(Move.of(Geometry.id(1, -5), Geometry.id(1, -4)));
        float[] in = encode(after);

        int left = Geometry.id(-1, 5);
        int entered = Geometry.id(-1, 4);
        List<String> differences = new ArrayList<>();
        for (int row = 0; row < HexGrid.ROWS; row++) {
            for (int p = 0; p < Encoder.PROGRESS; p++) {
                if (in[Encoder.index(row, p)] != reference[Encoder.index(row, p)]) {
                    differences.add(row + "/" + p);
                }
            }
        }
        assertEquals(List.of(entered + "/" + Encoder.NEXT, left + "/" + Encoder.NEXT), differences);
        assertEquals(1f, in[Encoder.index(entered, Encoder.NEXT)]);
        assertEquals(0f, in[Encoder.index(left, Encoder.NEXT)]);
        for (int c = 0; c < HexGrid.CELLS; c++) {
            assertEquals(1f / 200, in[Encoder.index(c, Encoder.PROGRESS)]);
        }
        assertEquals(0f, in[Encoder.index(HexGrid.PADDING, Encoder.PROGRESS)]);
    }

    // ---- symmetry ----

    /** The start position is symmetric under the player rotation: every mover sees the same planes. */
    @Test
    void startPositionLooksTheSameForEveryMover() {
        for (int players = 2; players <= 3; players++) {
            float[] first = encode(Position.initial(RuleSet.defaults(players)));
            for (int mover = 1; mover < players; mover++) {
                float[] other = encode(Position.initial(RuleSet.defaults(players)).setSideToMove(mover));
                assertArrayEquals(first, other, players + " players, mover " + mover);
            }
        }
    }

    /** Item 7 of docs/network.md: player 1 to move equals the 180-degree image with the players swapped. */
    @Test
    void twoPlayersMoverOneEqualsTheSwappedImage() {
        int compared = 0;
        for (Position position : Playouts.positions(2, 2, 120, 77)) {
            if (position.sideToMove() == 1) {
                Position real = Playouts.atPlyZero(position);
                Position swapped = position.transformed(Geometry::rotate180, true);
                assertEquals(0, swapped.sideToMove());
                assertArrayEquals(encode(swapped), encode(real), position.toString());
                compared++;
            }
        }
        assertTrue(compared > 100, "compared " + compared);
    }

    /** For every mover: the encoding equals the identity encoding of the canonical position built by hand. */
    @Test
    void encodingEqualsTheCanonicalPositionForEveryMover() {
        for (int players = 2; players <= 3; players++) {
            for (Position position : Playouts.positions(players, 2, 150, 4242 + players)) {
                Position real = Playouts.atPlyZero(position);
                assertArrayEquals(encode(Playouts.canonical(position)), encode(real), position.toString());
            }
        }
    }

    // ---- invariants over random positions ----

    @Test
    void planeInvariantsOnRandomPositions() {
        for (int players = 2; players <= 3; players++) {
            for (Position position : Playouts.positions(players, 2, 150, 99 + players)) {
                float[] in = encode(position);
                Orientation o = Orientation.of(position);
                Layout layout = position.layout();
                int mover = position.sideToMove();
                String where = position.toString();

                // Piece planes: one-hot per row, 10 pieces each, a zero third plane with two players.
                assertEquals(10, cellsOf(in, Encoder.OWN).size(), where);
                assertEquals(10, cellsOf(in, Encoder.NEXT).size(), where);
                assertEquals(players == 3 ? 10 : 0, cellsOf(in, Encoder.THIRD).size(), where);
                for (int row = 0; row < HexGrid.ROWS; row++) {
                    float sum = in[Encoder.index(row, Encoder.OWN)] + in[Encoder.index(row, Encoder.NEXT)]
                            + in[Encoder.index(row, Encoder.THIRD)];
                    assertTrue(sum <= 1f, where + " row " + row);
                }
                for (int c = 0; c < HexGrid.CELLS; c++) {
                    int row = o.toCanonical(c);
                    int owner = position.owner(c);
                    for (int i = 0; i < 3; i++) {
                        float expected = owner >= 0 && i < players && o.relativePlayer(owner) == i ? 1f : 0f;
                        assertEquals(expected, in[Encoder.index(row, Encoder.OWN + i)], where + " cell " + c);
                    }
                    assertEquals(layout.allowed(mover, c) ? 1f : 0f, in[Encoder.index(row, Encoder.PLAYABLE)]);
                    assertEquals(Geometry.cornerOf(c) == layout.goalCorner(mover) ? 1f : 0f,
                            in[Encoder.index(row, Encoder.GOAL)]);
                    assertEquals(Geometry.cornerOf(c) == layout.startCorner(mover) ? 1f : 0f,
                            in[Encoder.index(row, Encoder.START)]);
                }
                // Corner planes in canonical orientation: goal = R_POS, start = R_NEG, both inside playable.
                assertEquals(sorted(Geometry.cornerCells(Corner.R_POS.ordinal())), cellsOf(in, Encoder.GOAL));
                assertEquals(sorted(Geometry.cornerCells(Corner.R_NEG.ordinal())), cellsOf(in, Encoder.START));
                List<Integer> playable = cellsOf(in, Encoder.PLAYABLE);
                assertEquals(81, playable.size());
                assertTrue(playable.containsAll(cellsOf(in, Encoder.GOAL)) && playable.containsAll(cellsOf(in, Encoder.START)));
                assertTrue(playable.containsAll(cellsOf(in, Encoder.OWN)), "own pieces stand on playable cells");
                // Progress.
                float progress = (float) position.ply() / 1000;
                for (int c = 0; c < HexGrid.CELLS; c++) {
                    assertEquals(progress, in[Encoder.index(c, Encoder.PROGRESS)], where);
                }
                assertPaddingRowIsZero(in, 0);
            }
        }
    }

    /** The progress plane is min(ply, moveLimit) / moveLimit; past the limit it stays at 1. */
    @Test
    void progressIsClampedAtTheMoveLimit() {
        Position position = Position.initial(RuleSet.defaults(2).withMoveLimit(4));
        float[] seen = new float[8];
        for (int ply = 0; ply < seen.length; ply++) {
            seen[ply] = encode(position)[Encoder.index(37, Encoder.PROGRESS)];
            position.make(position.legalMoves()[0]); // make() itself does not stop at the move limit
        }
        assertArrayEquals(new float[] {0f, 0.25f, 0.5f, 0.75f, 1f, 1f, 1f, 1f}, seen);
    }

    // ---- buffer handling ----

    /** The slice is overwritten completely, and nothing outside it is touched. */
    @Test
    void encodeOverwritesTheWholeSliceAndNothingElse() {
        Position position = Position.initial(RuleSet.defaults(3));
        position.make(position.legalMoves()[3]);
        float[] expected = encode(position);

        int offset = 5;
        float[] buffer = new float[offset + Encoder.INPUT_SIZE + 5];
        Arrays.fill(buffer, Float.NaN);
        buffer[offset - 1] = -7f;
        buffer[offset + Encoder.INPUT_SIZE] = -8f;
        Encoder.encode(position, buffer, offset);
        assertArrayEquals(expected, Arrays.copyOfRange(buffer, offset, offset + Encoder.INPUT_SIZE));
        assertEquals(-7f, buffer[offset - 1]);
        assertEquals(-8f, buffer[offset + Encoder.INPUT_SIZE]);
        for (int i = 0; i < offset - 1; i++) {
            assertTrue(Float.isNaN(buffer[i]));
            assertTrue(Float.isNaN(buffer[buffer.length - 1 - i]));
        }

        // A second position into the same, now dirty, slice must not keep pieces of the first.
        Position other = Position.initial(RuleSet.defaults(3)).setSideToMove(2);
        Encoder.encode(other, buffer, offset);
        assertArrayEquals(encode(other), Arrays.copyOfRange(buffer, offset, offset + Encoder.INPUT_SIZE));
    }

    @Test
    void sliceMustFit() {
        Position position = Position.initial(RuleSet.defaults(2));
        assertThrows(IndexOutOfBoundsException.class, () -> Encoder.encode(position, new float[853], 0));
        assertThrows(IndexOutOfBoundsException.class, () -> Encoder.encode(position, new float[900], 47));
        assertThrows(IndexOutOfBoundsException.class, () -> Encoder.encode(position, new float[900], -1));
        Encoder.encode(position, new float[900], 46);
    }

    // ---- move logits ----

    /**
     * A policy whose "from" logit of canonical cell f is 1000 f and whose "to" logit of t is t, so a logit names its
     * canonical (f, t) uniquely. A swapped channel or a missed map shows as a wrong pair.
     */
    @Test
    void moveLogitByHand() {
        float[] policy = new float[2 * Encoder.POLICY_SIZE];
        Arrays.fill(policy, 0, Encoder.POLICY_SIZE, Float.NaN); // the first position must not be read
        for (int c = 0; c < HexGrid.CELLS; c++) {
            policy[Encoder.POLICY_SIZE + Encoder.policyIndex(c, Encoder.FROM)] = 1000f * c;
            policy[Encoder.POLICY_SIZE + Encoder.policyIndex(c, Encoder.TO)] = c;
        }
        int off = Encoder.POLICY_SIZE;
        // Mover 0: identity. Move (4,-8) -> (4,-7) is 0 -> 2.
        assertEquals(1000f * 0 + 2, Encoder.moveLogit(Orientation.of(2, 0), policy, off, Move.of(0, 2)));
        assertEquals(1000f * 2 + 0, Encoder.moveLogit(Orientation.of(2, 0), policy, off, Move.of(2, 0)));
        // Mover 1 of 2: 0 -> 120 and (4,-7) -> (-4,7).
        assertEquals(1000f * 120 + Geometry.id(-4, 7), Encoder.moveLogit(Orientation.of(2, 1), policy, off, Move.of(0, 2)));
        // Mover 1 of 3: 110 -> 0 and (3,4) -> (4,-7) = 2.
        assertEquals(1000f * 0 + 2, Encoder.moveLogit(Orientation.of(3, 1), policy, off, Move.of(110, Geometry.id(3, 4))));
        // Mover 2 of 3: 98 -> 0 and (-7,4) -> (3,-7) = 1.
        assertEquals(1000f * 0 + 1, Encoder.moveLogit(Orientation.of(3, 2), policy, off, Move.of(98, Geometry.id(-7, 4))));
    }

    /** Every legal move of random positions: the logit sums the "from" entry of f' and the "to" entry of t'. */
    @Test
    void moveLogitOnRandomPositions() {
        java.util.Random random = new java.util.Random(5);
        float[] policy = new float[Encoder.POLICY_SIZE];
        for (int i = 0; i < policy.length; i++) {
            policy[i] = random.nextInt(1 << 10);   // integers: the float sum is exact
        }
        for (int players = 2; players <= 3; players++) {
            for (Position position : Playouts.positions(players, 1, 100, 31 + players)) {
                Orientation o = Orientation.of(position);
                float[] in = encode(position);
                for (int m : position.legalMoves()) {
                    int f = o.toCanonical(Move.from(m));
                    int t = o.toCanonical(Move.to(m));
                    assertEquals(policy[2 * f] + policy[2 * t + 1], Encoder.moveLogit(o, policy, 0, m));
                    // The encoder and the move mapping agree: a move leaves an own piece for an empty playable cell.
                    assertEquals(1f, in[Encoder.index(f, Encoder.OWN)]);
                    assertEquals(0f, in[Encoder.index(t, Encoder.OWN)] + in[Encoder.index(t, Encoder.NEXT)]
                            + in[Encoder.index(t, Encoder.THIRD)]);
                    assertEquals(1f, in[Encoder.index(t, Encoder.PLAYABLE)]);
                }
            }
        }
    }

    /** Review finding: the two logits are added in double, so a sum beyond the float range stays finite and exact. */
    @Test
    void moveLogitSumsInDouble() {
        float[] policy = new float[Encoder.POLICY_SIZE];
        policy[Encoder.policyIndex(0, Encoder.FROM)] = Float.MAX_VALUE;
        policy[Encoder.policyIndex(2, Encoder.TO)] = Float.MAX_VALUE;
        policy[Encoder.policyIndex(2, Encoder.FROM)] = 1f;
        policy[Encoder.policyIndex(0, Encoder.TO)] = 1e-8f;
        Orientation o = Orientation.of(2, 0);
        assertEquals(2.0 * Float.MAX_VALUE, Encoder.moveLogit(o, policy, 0, Move.of(0, 2)));
        // 1 + 1e-8 is 1 in float, not in double.
        assertEquals(1.0 + (double) 1e-8f, Encoder.moveLogit(o, policy, 0, Move.of(2, 0)));
    }

    @Test
    void moveLogitChecksTheSlice() {
        Orientation o = Orientation.of(2, 0);
        assertThrows(IndexOutOfBoundsException.class, () -> Encoder.moveLogit(o, new float[241], 0, Move.of(0, 2)));
        assertThrows(IndexOutOfBoundsException.class, () -> Encoder.moveLogit(o, new float[300], -1, Move.of(0, 2)));
        assertThrows(IndexOutOfBoundsException.class, () -> Encoder.moveLogit(o, new float[300], 59, Move.of(0, 2)));
    }

    // ---- helpers ----

    private static float[] encode(Position position) {
        float[] out = new float[Encoder.INPUT_SIZE];
        Arrays.fill(out, Float.NaN);
        Encoder.encode(position, out, 0);
        for (float v : out) {
            assertFalse(Float.isNaN(v), "the encoder left a float unwritten");
        }
        return out;
    }

    /** The rows where a plane is 1, in ascending order; fails on any value other than 0 or 1. */
    private static List<Integer> cellsOf(float[] in, int plane) {
        List<Integer> rows = new ArrayList<>();
        for (int row = 0; row < HexGrid.ROWS; row++) {
            float v = in[Encoder.index(row, plane)];
            assertTrue(v == 0f || v == 1f, "plane " + plane + " row " + row + " = " + v);
            if (v == 1f) {
                rows.add(row);
            }
        }
        return rows;
    }

    private static void assertPaddingRowIsZero(float[] in, int offset) {
        for (int p = 0; p < Encoder.PLANES; p++) {
            assertEquals(0f, in[offset + Encoder.index(HexGrid.PADDING, p)], "padding row, plane " + p);
        }
    }

    private static List<Integer> range(int from, int to) {
        List<Integer> result = new ArrayList<>();
        for (int i = from; i < to; i++) {
            result.add(i);
        }
        return result;
    }

    private static List<Integer> sorted(int[] values) {
        return Arrays.stream(values).sorted().boxed().toList();
    }
}
