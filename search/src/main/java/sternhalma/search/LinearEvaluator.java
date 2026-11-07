package sternhalma.search;

import sternhalma.engine.Geometry;
import sternhalma.engine.Layout;
import sternhalma.engine.Position;

/**
 * A learned linear evaluation: a weighted sum of cheap features, each the difference "side to move minus
 * opponent". The weights come from a logistic regression of the game result on positions from baseline
 * games ({@link LinearWeights} records how they were produced).
 *
 * <p>Per-side features, all symmetric under the player flip, so a colour-swapped mirror image scores the
 * same:
 * <ol start="0">
 *   <li>{@code dsum}: summed single-agent distance (stage 2)</li>
 *   <li>{@code dmax}: largest piece distance (straggler)</li>
 *   <li>{@code dsq}: summed squared distance, divided by 10 (soft straggler)</li>
 *   <li>{@code inGoal}: pieces in the goal corner</li>
 *   <li>{@code inStart}: pieces still in the start corner</li>
 *   <li>{@code spread}: largest minus smallest piece distance (cohesion along the race)</li>
 *   <li>{@code lateral}: summed |q - s|, the distance from the centre line (centre control)</li>
 *   <li>{@code jumps}: single jumps over any piece that land on a free allowed cell closer to the goal</li>
 *   <li>{@code adjacent}: pairs of own pieces that touch (bridges)</li>
 *   <li>{@code steps}: steps to a free allowed neighbour closer to the goal (mobility)</li>
 * </ol>
 * plus {@code dsumPhase}: the {@code dsum} difference times the total remaining distance / 100 (lets the
 * weight of the race change with the game phase), and {@code tempo}: a constant 1 for the side to move.
 *
 * <p>Implementation (experiment E02): integer sums over the position's piece lists with precomputed per-player
 * tables, then the doubles in the order of the original cell-scanning code, so features and scores are
 * bit-identical to it ({@code LinearEvaluatorEquivalenceTest}). Only players 0 and 1 are evaluated.
 */
public final class LinearEvaluator implements Evaluator {

    public static final int FEATURES = 12;
    public static final String[] NAMES = {
        "dsum", "dmax", "dsq", "inGoal", "inStart", "spread", "lateral", "jumps", "adjacent", "steps",
        "dsumPhase", "tempo"
    };

    /** Flat neighbour and jump-landing tables, index {@code cell * DIRECTIONS + direction}. */
    private static final int[] STEP = new int[Geometry.CELLS * Geometry.DIRECTIONS];
    private static final int[] LAND = new int[Geometry.CELLS * Geometry.DIRECTIONS];
    private static final int[] LATERAL = new int[Geometry.CELLS];
    /** Bit in {@link #GOOD}: the neighbour is an allowed cell closer to the goal than the piece. */
    private static final int STEP_CLOSER = 1;
    /** Bit in {@link #GOOD}: the jump landing is an allowed cell closer to the goal than the piece. */
    private static final int JUMP_CLOSER = 2;
    /** Per layout (index players - 2) and player: {@code STEP_CLOSER | JUMP_CLOSER} per (cell, direction). */
    private static final byte[][][] GOOD = new byte[2][][];
    /** Per layout and player: the single-agent distance per cell. */
    private static final int[][][] DIST = new int[2][][];
    /** Per layout and player: 1 for a goal cell, 2 for a start cell, 0 otherwise. */
    private static final byte[][][] KIND = new byte[2][][];
    private static final int IN_GOAL = 1;
    private static final int IN_START = 2;

    static {
        for (int c = 0; c < Geometry.CELLS; c++) {
            for (int d = 0; d < Geometry.DIRECTIONS; d++) {
                STEP[c * Geometry.DIRECTIONS + d] = Geometry.neighbor(c, d);
                LAND[c * Geometry.DIRECTIONS + d] = Geometry.jumpLanding(c, d);
            }
            LATERAL[c] = Math.abs(Geometry.q(c) - Geometry.s(c));
        }
        for (int players = 2; players <= 3; players++) {
            Layout layout = Layout.of(players);
            byte[][] good = new byte[players][Geometry.CELLS * Geometry.DIRECTIONS];
            int[][] dist = new int[players][Geometry.CELLS];
            byte[][] kind = new byte[players][Geometry.CELLS];
            for (int p = 0; p < players; p++) {
                for (int c = 0; c < Geometry.CELLS; c++) {
                    dist[p][c] = layout.distance(p, c);
                    int corner = Geometry.cornerOf(c);
                    kind[p][c] = (byte) (corner == layout.goalCorner(p) ? IN_GOAL
                            : corner == layout.startCorner(p) ? IN_START : 0);
                    if (!layout.allowed(p, c)) {
                        continue; // a piece of p never stands here
                    }
                    int here = layout.distance(p, c);
                    for (int d = 0; d < Geometry.DIRECTIONS; d++) {
                        int i = c * Geometry.DIRECTIONS + d;
                        int bits = 0;
                        if (closer(layout, p, STEP[i], here)) {
                            bits |= STEP_CLOSER;
                        }
                        if (closer(layout, p, LAND[i], here)) {
                            bits |= JUMP_CLOSER;
                        }
                        good[p][i] = (byte) bits;
                    }
                }
            }
            GOOD[players - 2] = good;
            DIST[players - 2] = dist;
            KIND[players - 2] = kind;
        }
    }

    private static boolean closer(Layout layout, int p, int cell, int here) {
        return cell >= 0 && layout.allowed(p, cell) && layout.distance(p, cell) < here;
    }

    // per-side integer sums, indices into the scratch array of side()
    private static final int DSUM = 0;
    private static final int DMAX = 1;
    private static final int DMIN = 2;
    private static final int DSQ = 3;
    private static final int GOAL = 4;
    private static final int START = 5;
    private static final int LAT = 6;
    private static final int JUMPS = 7;
    private static final int ADJ = 8;
    private static final int STEPS = 9;
    private static final int COUNT = 10;
    private static final int SIDE_SUMS = 11;

    private final double[] weights;
    private final double scale;
    private final double[] scratch = new double[FEATURES];
    private final int[] sums = new int[SIDE_SUMS];

    public LinearEvaluator() {
        this(LinearWeights.WEIGHTS, LinearWeights.SCALE);
    }

    /** @param scale score units per logit unit */
    public LinearEvaluator(double[] weights, double scale) {
        if (weights.length != FEATURES) {
            throw new IllegalArgumentException("need " + FEATURES + " weights, got " + weights.length);
        }
        this.weights = weights.clone();
        this.scale = scale;
    }

    @Override
    public int evaluate(Position position) {
        features(position, scratch, sums);
        double sum = 0;
        for (int i = 0; i < FEATURES; i++) {
            sum += weights[i] * scratch[i];
        }
        return (int) Math.round(scale * sum);
    }

    /** The feature vector of a position (side to move minus opponent), into {@code out}. */
    public static void features(Position position, double[] out) {
        features(position, out, new int[SIDE_SUMS]);
    }

    /**
     * Integer sums per side from the piece lists and the tables, then the doubles in exactly the order of the
     * original cell-scanning code (LinearEvaluatorReference in the tests), so that every feature and the rounded
     * score stay bit-identical: out[i] = 0.0 + me + (-1 * opponent), dsq as dsq / 10.0 per side.
     */
    private static void features(Position position, double[] out, int[] sums) {
        int me = position.sideToMove();
        int layout = position.rules().players() - 2;
        java.util.Arrays.fill(out, 0, 10, 0.0);
        side(position, layout, me, sums);
        int dMe = sums[DSUM];
        add(out, sums, 1);
        side(position, layout, 1 - me, sums);
        int dOpp = sums[DSUM];
        add(out, sums, -1);
        out[10] = out[0] * (dMe + dOpp) / 100.0;
        out[11] = 1.0;
    }

    /** Adds {@code sign} times the per-side features to out[0..9], with the operations of the original code. */
    private static void add(double[] out, int[] s, int sign) {
        int n = s[COUNT];
        out[0] += sign * s[DSUM];
        out[1] += sign * s[DMAX];
        out[2] += sign * (s[DSQ] / 10.0);
        out[3] += sign * s[GOAL];
        out[4] += sign * s[START];
        out[5] += sign * (n == 0 ? 0 : s[DMAX] - s[DMIN]);
        out[6] += sign * s[LAT];
        out[7] += sign * s[JUMPS];
        out[8] += sign * s[ADJ];
        out[9] += sign * s[STEPS];
    }

    /** The integer sums of player p into {@code s}, over at most {@link Position#PIECES} pieces in cell order. */
    private static void side(Position position, int layout, int p, int[] s) {
        byte[] good = GOOD[layout][p];
        int[] dist = DIST[layout][p];
        byte[] kind = KIND[layout][p];
        int n = Math.min(position.pieceCount(p), Position.PIECES);
        int dsum = 0;
        int dmax = 0;
        int dmin = Integer.MAX_VALUE;
        int dsq = 0;
        int inGoal = 0;
        int inStart = 0;
        int lateral = 0;
        int jumps = 0;
        int adjacent = 0;
        int steps = 0;
        for (int i = 0; i < n; i++) {
            int c = position.pieceCell(p, i);
            int d = dist[c];
            dsum += d;
            dsq += d * d;
            dmax = Math.max(dmax, d);
            dmin = Math.min(dmin, d);
            int k = kind[c];
            if (k == IN_GOAL) {
                inGoal++;
            } else if (k == IN_START) {
                inStart++;
            }
            lateral += LATERAL[c];
            int base = c * Geometry.DIRECTIONS;
            for (int dir = 0; dir < Geometry.DIRECTIONS; dir++) {
                int a = STEP[base + dir];
                if (a < 0) {
                    continue;
                }
                int owner = position.owner(a);
                int g = good[base + dir];
                if (owner < 0) {
                    steps += g & STEP_CLOSER;
                } else {
                    if (owner == p && a > c) {
                        adjacent++;
                    }
                    if ((g & JUMP_CLOSER) != 0 && position.owner(LAND[base + dir]) < 0) {
                        jumps++;
                    }
                }
            }
        }
        s[DSUM] = dsum;
        s[DMAX] = dmax;
        s[DMIN] = dmin;
        s[DSQ] = dsq;
        s[GOAL] = inGoal;
        s[START] = inStart;
        s[LAT] = lateral;
        s[JUMPS] = jumps;
        s[ADJ] = adjacent;
        s[STEPS] = steps;
        s[COUNT] = n;
    }
}
