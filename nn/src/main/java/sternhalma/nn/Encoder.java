package sternhalma.nn;

import java.util.Arrays;
import java.util.Objects;
import sternhalma.engine.Geometry;
import sternhalma.engine.Layout;
import sternhalma.engine.Move;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;

/**
 * Input planes and the policy layout (docs/network.md, "Input planes" and "Policy head and move logits").
 *
 * <p>The input of one position is a flat array of {@link #INPUT_SIZE} floats, [{@link HexGrid#ROWS}][{@link
 * #PLANES}], channels last, written in canonical orientation: plane p of canonical cell c' is at
 * {@code c' * PLANES + p}. Row {@link HexGrid#PADDING} is zero in every plane.
 *
 * <p>The policy output of one position is [{@link HexGrid#CELLS}][2]: the "from" logit of canonical cell c' is
 * at {@code 2 * c'} and the "to" logit at {@code 2 * c' + 1}. The logit of a move is the sum of the "from"
 * logit of its canonical start cell and the "to" logit of its canonical end cell.
 */
public final class Encoder {

    /** Plane 0: pieces of the mover (relative player 0). */
    public static final int OWN = 0;
    /** Plane 1: pieces of relative player 1. */
    public static final int NEXT = 1;
    /** Plane 2: pieces of relative player 2; zero with two players. */
    public static final int THIRD = 2;
    /** Plane 3: the mover's goal corner. */
    public static final int GOAL = 3;
    /** Plane 4: the mover's start corner. */
    public static final int START = 4;
    /** Plane 5: cells the mover may stand on ({@code Layout.allowed}). */
    public static final int PLAYABLE = 5;
    /** Plane 6: min(ply, moveLimit) / moveLimit in every cell row, 0 in the padding row. */
    public static final int PROGRESS = 6;
    /** Number of plain input planes (format version 1, {@link PlaneSet#PLAIN}). */
    public static final int PLANES = 7;
    /** Plane 7 (classical planes only): single-agent distance of the mover per cell, divided by 16. */
    public static final int DIST_OWN = 7;
    /** Plane 8: the same for relative player 1. */
    public static final int DIST_NEXT = 8;
    /** Plane 9: 1 on every end cell of a legal move of the mover. */
    public static final int REACH_OWN = 9;
    /** Plane 10: 1 on every end cell of a legal move that relative player 1 would have. */
    public static final int REACH_NEXT = 10;
    /** Plane 11: 1 in every cell row if the armies have passed each other (two players). */
    public static final int PASSED = 11;
    /** Plane 12: linear evaluation of the position for the mover in logit units, in every cell row; 0 for three players. */
    public static final int EVAL = 12;
    /** Number of input planes of {@link PlaneSet#CLASSIC1}. */
    public static final int CLASSIC_PLANES = 13;
    /** Plane 13 (variant planes only): 1 in every cell row if the position's jump rule is LONG. */
    public static final int LONG = 13;
    /** Plane 14 (variant planes only): 1 in every cell row if the position has three players. */
    public static final int THREE_PLAYERS = 14;
    /** Number of input planes of {@link PlaneSet#CLASSIC1V}. */
    public static final int VARIANT_PLANES = 15;
    /** Floats per encoded position of the plain planes: 122 rows times 7 planes = 854. */
    public static final int INPUT_SIZE = HexGrid.ROWS * PLANES;

    /** Policy channel of the "from" logit. */
    public static final int FROM = 0;
    /** Policy channel of the "to" logit. */
    public static final int TO = 1;
    /** Policy channels per cell. */
    public static final int POLICY_CHANNELS = 2;
    /** Floats of policy output per position: 121 cells times 2 channels = 242. */
    public static final int POLICY_SIZE = HexGrid.CELLS * POLICY_CHANNELS;

    private Encoder() {
    }

    /** Index of (row, plane) inside one encoded position. */
    public static int index(int row, int plane) {
        return row * PLANES + plane;
    }

    /** Index of (canonical cell, policy channel) inside the policy output of one position. */
    public static int policyIndex(int canonicalCell, int channel) {
        return canonicalCell * POLICY_CHANNELS + channel;
    }

    /** Index of (row, plane) inside one encoded position that has {@code planes} planes per row. */
    public static int index(int planes, int row, int plane) {
        return row * planes + plane;
    }

    /** {@link #encode(PlaneSet, Position, float[], int)} with the plain planes. */
    public static void encode(Position position, float[] out, int offset) {
        encode(PlaneSet.PLAIN, position, out, offset);
    }

    /**
     * Writes the planes of a position into {@code out[offset .. offset + planes.inputSize())}: the 7 plain planes and,
     * for a classical set, the six planes of {@link ClassicalPlanes}. Every float of that slice is written, so the caller
     * need not clear the buffer. Values are 0 or 1, except plane {@link #PROGRESS} (in [0, 1]), the distance planes (in
     * [0, 1]) and {@link #EVAL}.
     *
     * @throws IndexOutOfBoundsException if the slice does not fit into {@code out}
     */
    public static void encode(PlaneSet planes, Position position, float[] out, int offset) {
        int stride = planes.planes();
        int size = planes.inputSize();
        Objects.checkFromIndexSize(offset, size, out.length);
        Orientation orientation = Orientation.of(position);
        Layout layout = position.layout();
        int mover = orientation.mover();
        int goal = layout.goalCorner(mover);
        int start = layout.startCorner(mover);
        int limit = position.rules().moveLimit();
        float progress = (float) Math.min(position.ply(), limit) / limit;

        // Clear the whole slice first: this zeroes the padding row and every plane that stays 0 below.
        Arrays.fill(out, offset, offset + size, 0f);
        for (int c = 0; c < Geometry.CELLS; c++) {
            int base = offset + index(stride, orientation.toCanonical(c), 0);
            int owner = position.owner(c);
            if (owner >= 0) {
                // Relative player 0, 1, 2 are planes OWN, NEXT, THIRD.
                out[base + OWN + orientation.relativePlayer(owner)] = 1f;
            }
            int corner = Geometry.cornerOf(c);
            if (corner == goal) {
                out[base + GOAL] = 1f;
            }
            if (corner == start) {
                out[base + START] = 1f;
            }
            if (layout.allowed(mover, c)) {
                out[base + PLAYABLE] = 1f;
            }
            out[base + PROGRESS] = progress;
        }
        if (planes.classical()) {
            ClassicalPlanes.fill(position, orientation, false, planes.variants(), out, offset, stride);
        }
        if (planes.variants()) {
            float isLong = position.rules().jumpRule() == RuleSet.JumpRule.LONG ? 1f : 0f;
            float isThree = position.rules().players() == 3 ? 1f : 0f;
            for (int c = 0; c < Geometry.CELLS; c++) {
                int base = offset + index(stride, orientation.toCanonical(c), 0);
                out[base + LONG] = isLong;
                out[base + THREE_PLAYERS] = isThree;
            }
        }
    }

    /**
     * The logit of an engine move: {@code policy[offset + policyIndex(f', FROM)] +
     * policy[offset + policyIndex(t', TO)]}, where f' and t' are the canonical start and end cells under
     * the orientation.
     *
     * @param orientation the orientation of the position the policy was computed for
     * @param policy      policy output, [121][2] per position
     * @param offset      start of this position's policy in {@code policy}
     * @param move        an engine move ({@code Move.of(from, to)}, board cells, not canonical)
     * @return the sum in double: two finite float logits never give an infinite move logit
     */
    public static double moveLogit(Orientation orientation, float[] policy, int offset, int move) {
        Objects.checkFromIndexSize(offset, POLICY_SIZE, policy.length);
        int from = orientation.toCanonical(Move.from(move));
        int to = orientation.toCanonical(Move.to(move));
        return (double) policy[offset + policyIndex(from, FROM)] + policy[offset + policyIndex(to, TO)];
    }
}
