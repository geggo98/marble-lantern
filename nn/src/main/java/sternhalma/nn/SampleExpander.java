package sternhalma.nn;

import java.util.Objects;
import sternhalma.engine.Geometry;
import sternhalma.engine.Layout;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;

/**
 * Expands a stored sample into input planes without a {@code Position} (docs/training.md, "Self-play sample
 * files" and "Symmetric augmentation").
 *
 * <p>A sample file keeps the position as canonical cells: byte 0 for an empty cell, {@code 1 + r} for a piece of
 * relative player r. Planes 0 to 2 follow from those bytes, planes 3 to 5 are constants of the canonical
 * orientation (the mover is relative player 0, so its corners and playable cells are those of player 0), and plane
 * 6 is {@code min(ply, moveLimit) / moveLimit}. The result equals {@link Encoder#encode} of the original position,
 * float for float.
 *
 * <p>With {@code mirror} (two players only) row c' of every plane goes to row {@link #mirrorCell}(c'), which is
 * the encoding of the mirrored position.
 */
public final class SampleExpander {

    /**
     * Constant planes (3 to 5) of canonical orientation as a full input; index [plain or classical][players - 2][mirror].
     * The classical planes are not in the template; they are written after it.
     */
    private static final float[][][][] TEMPLATE = new float[3][2][2][];

    static {
        for (int set = 0; set < 3; set++) {
            int stride = set == 0 ? Encoder.PLANES : set == 1 ? Encoder.CLASSIC_PLANES : Encoder.VARIANT_PLANES;
            for (int players = 2; players <= 3; players++) {
                Layout layout = Layout.of(players);
                for (int m = 0; m < (players == 2 ? 2 : 1); m++) {
                    float[] template = new float[HexGrid.ROWS * stride];
                    int goal = layout.goalCorner(0);
                    int start = layout.startCorner(0);
                    for (int c = 0; c < Geometry.CELLS; c++) {
                        int base = Encoder.index(stride, m == 1 ? mirrorCell(c) : c, 0);
                        int corner = Geometry.cornerOf(c);
                        if (corner == goal) {
                            template[base + Encoder.GOAL] = 1f;
                        }
                        if (corner == start) {
                            template[base + Encoder.START] = 1f;
                        }
                        if (layout.allowed(0, c)) {
                            template[base + Encoder.PLAYABLE] = 1f;
                        }
                    }
                    TEMPLATE[set][players - 2][m] = template;
                }
            }
        }
    }

    private SampleExpander() {
    }

    /** The left-right mirror of a canonical cell: {@code Geometry.mirror}, (q, r, s) to (s, r, q). */
    public static int mirrorCell(int canonicalCell) {
        return Geometry.mirror(canonicalCell);
    }

    /** {@link #expand(PlaneSet, int, RuleSet.JumpRule, byte[], int, int, int, boolean, float[], int)} with adjacent jumps. */
    public static void expand(PlaneSet planes, int players, byte[] cells, int cellsOffset, int ply, int moveLimit,
            boolean mirror, float[] out, int outOffset) {
        expand(planes, players, RuleSet.JumpRule.ADJACENT, cells, cellsOffset, ply, moveLimit, mirror, out, outOffset);
    }

    /** {@link #expand(PlaneSet, int, byte[], int, int, int, boolean, float[], int)} with the plain planes. */
    public static void expand(int players, byte[] cells, int cellsOffset, int ply, int moveLimit, boolean mirror,
            float[] out, int outOffset) {
        expand(PlaneSet.PLAIN, players, cells, cellsOffset, ply, moveLimit, mirror, out, outOffset);
    }

    /**
     * Writes the planes of one sample into {@code out[outOffset .. outOffset + planes.inputSize())}; every float of the
     * slice is written. The classical planes of {@link PlaneSet#CLASSIC1} are recomputed here from the cells: the
     * position is rebuilt in canonical orientation (relative player i as player i, default rules with the given move
     * limit, the mover to move) and the same code as in {@link Encoder#encode} runs on it, so the result equals the
     * encoding of the original position float for float (for positions under the default rules except the jump rule, which the sample
     * files record in {@code rules}).
     *
     * @param planes    the plane set of the network that will read the input
     * @param players   2 or 3
     * @param jumpRule  the jump rule of the sample's {@code rules}: the reach planes are generated under it, and for
     *                  {@link PlaneSet#CLASSIC1V} it sets {@link Encoder#LONG} (and zeroes {@link Encoder#EVAL})
     * @param cells     canonical cells, {@link HexGrid#CELLS} bytes from {@code cellsOffset}: 0 empty, 1 + relative
     *                  player otherwise
     * @param ply       {@code Position.ply()} of the original position, &gt;= 0
     * @param moveLimit {@code rules().moveLimit()}, &gt;= 1
     * @param mirror    mirror the sample; defined for two players only
     * @throws IllegalArgumentException  if players is not 2 or 3, mirror is requested for three players, ply or
     *                                   moveLimit is out of range, or a cell byte is larger than players
     * @throws IndexOutOfBoundsException if a slice does not fit its array
     */
    public static void expand(PlaneSet planes, int players, RuleSet.JumpRule jumpRule, byte[] cells, int cellsOffset,
            int ply, int moveLimit, boolean mirror, float[] out, int outOffset) {
        Objects.requireNonNull(jumpRule, "jumpRule");
        if (players != 2 && players != 3) {
            throw new IllegalArgumentException("players must be 2 or 3, got " + players);
        }
        if (mirror && players != 2) {
            throw new IllegalArgumentException("the mirror is defined for two players only, got " + players);
        }
        if (ply < 0 || moveLimit < 1) {
            throw new IllegalArgumentException("ply " + ply + " and moveLimit " + moveLimit + " out of range");
        }
        Objects.checkFromIndexSize(cellsOffset, HexGrid.CELLS, cells.length);
        int stride = planes.planes();
        Objects.checkFromIndexSize(outOffset, planes.inputSize(), out.length);
        System.arraycopy(TEMPLATE[planes.variants() ? 2 : planes.classical() ? 1 : 0][players - 2][mirror ? 1 : 0], 0, out, outOffset,
                planes.inputSize());
        float progress = (float) Math.min(ply, moveLimit) / moveLimit;
        for (int c = 0; c < HexGrid.CELLS; c++) {
            int base = outOffset + Encoder.index(stride, mirror ? mirrorCell(c) : c, 0);
            int owner = cells[cellsOffset + c] & 0xFF;
            if (owner > players) {
                throw new IllegalArgumentException("cell " + c + " holds " + owner + " with " + players + " players");
            }
            if (owner > 0) {
                out[base + Encoder.OWN + owner - 1] = 1f;
            }
            out[base + Encoder.PROGRESS] = progress;
            if (planes.variants()) {
                out[base + Encoder.LONG] = jumpRule == RuleSet.JumpRule.LONG ? 1f : 0f;
                out[base + Encoder.THREE_PLAYERS] = players == 3 ? 1f : 0f;
            }
        }
        if (planes.classical()) {
            RuleSet rules = RuleSet.defaults(players).withMoveLimit(moveLimit).withJumpRule(jumpRule);
            Position position = Position.empty(rules);
            for (int c = 0; c < HexGrid.CELLS; c++) {
                int owner = cells[cellsOffset + c] & 0xFF;
                if (owner > 0) {
                    position.put(owner - 1, c);
                }
            }
            ClassicalPlanes.fill(position, Orientation.of(players, 0), mirror, planes.variants(), out, outOffset, stride);
        }
    }
}
