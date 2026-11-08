package sternhalma.nn;

import java.util.Objects;
import sternhalma.engine.Geometry;
import sternhalma.engine.Layout;

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

    /** Constant planes (3 to 5) of canonical orientation as a full input; index [players - 2][mirror]. */
    private static final float[][][] TEMPLATE = new float[2][2][];

    static {
        for (int players = 2; players <= 3; players++) {
            Layout layout = Layout.of(players);
            for (int m = 0; m < (players == 2 ? 2 : 1); m++) {
                float[] template = new float[Encoder.INPUT_SIZE];
                int goal = layout.goalCorner(0);
                int start = layout.startCorner(0);
                for (int c = 0; c < Geometry.CELLS; c++) {
                    int base = Encoder.index(m == 1 ? mirrorCell(c) : c, 0);
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
                TEMPLATE[players - 2][m] = template;
            }
        }
    }

    private SampleExpander() {
    }

    /** The left-right mirror of a canonical cell: {@code Geometry.mirror}, (q, r, s) to (s, r, q). */
    public static int mirrorCell(int canonicalCell) {
        return Geometry.mirror(canonicalCell);
    }

    /**
     * Writes the 7 planes of one sample into {@code out[outOffset .. outOffset + INPUT_SIZE)}; every float of the
     * slice is written.
     *
     * @param players   2 or 3
     * @param cells     canonical cells, {@link HexGrid#CELLS} bytes from {@code cellsOffset}: 0 empty, 1 + relative
     *                  player otherwise
     * @param ply       {@code Position.ply()} of the original position, &gt;= 0
     * @param moveLimit {@code rules().moveLimit()}, &gt;= 1
     * @param mirror    mirror the sample; defined for two players only
     * @throws IllegalArgumentException  if players is not 2 or 3, mirror is requested for three players, ply or
     *                                   moveLimit is out of range, or a cell byte is larger than players
     * @throws IndexOutOfBoundsException if a slice does not fit its array
     */
    public static void expand(int players, byte[] cells, int cellsOffset, int ply, int moveLimit, boolean mirror,
            float[] out, int outOffset) {
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
        Objects.checkFromIndexSize(outOffset, Encoder.INPUT_SIZE, out.length);
        System.arraycopy(TEMPLATE[players - 2][mirror ? 1 : 0], 0, out, outOffset, Encoder.INPUT_SIZE);
        float progress = (float) Math.min(ply, moveLimit) / moveLimit;
        for (int c = 0; c < HexGrid.CELLS; c++) {
            int base = outOffset + Encoder.index(mirror ? mirrorCell(c) : c, 0);
            int owner = cells[cellsOffset + c] & 0xFF;
            if (owner > players) {
                throw new IllegalArgumentException("cell " + c + " holds " + owner + " with " + players + " players");
            }
            if (owner > 0) {
                out[base + Encoder.OWN + owner - 1] = 1f;
            }
            out[base + Encoder.PROGRESS] = progress;
        }
    }
}
