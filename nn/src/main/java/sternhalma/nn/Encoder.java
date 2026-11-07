package sternhalma.nn;

import java.util.Arrays;
import java.util.Objects;
import sternhalma.engine.Geometry;
import sternhalma.engine.Layout;
import sternhalma.engine.Move;
import sternhalma.engine.Position;

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
    /** Number of input planes. */
    public static final int PLANES = 7;
    /** Floats per encoded position: 122 rows times 7 planes = 854. */
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

    /**
     * Writes the 7 planes of a position into {@code out[offset .. offset + INPUT_SIZE)}. Every float of that
     * slice is written, so the caller need not clear the buffer. Values are 0 or 1, except plane
     * {@link #PROGRESS}, which lies in [0, 1].
     *
     * @throws IndexOutOfBoundsException if the slice does not fit into {@code out}
     */
    public static void encode(Position position, float[] out, int offset) {
        Objects.checkFromIndexSize(offset, INPUT_SIZE, out.length);
        Orientation orientation = Orientation.of(position);
        Layout layout = position.layout();
        int mover = orientation.mover();
        int goal = layout.goalCorner(mover);
        int start = layout.startCorner(mover);
        int limit = position.rules().moveLimit();
        float progress = (float) Math.min(position.ply(), limit) / limit;

        // Clear the whole slice first: this zeroes the padding row and every plane that stays 0 below.
        Arrays.fill(out, offset, offset + INPUT_SIZE, 0f);
        for (int c = 0; c < Geometry.CELLS; c++) {
            int base = offset + index(orientation.toCanonical(c), 0);
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
