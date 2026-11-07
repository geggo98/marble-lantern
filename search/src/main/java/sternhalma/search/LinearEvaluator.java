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
 */
public final class LinearEvaluator implements Evaluator {

    public static final int FEATURES = 12;
    public static final String[] NAMES = {
        "dsum", "dmax", "dsq", "inGoal", "inStart", "spread", "lateral", "jumps", "adjacent", "steps",
        "dsumPhase", "tempo"
    };

    private static final int[][] NEIGHBOR = new int[Geometry.CELLS][Geometry.DIRECTIONS];
    private static final int[] LATERAL = new int[Geometry.CELLS];

    static {
        for (int c = 0; c < Geometry.CELLS; c++) {
            for (int d = 0; d < Geometry.DIRECTIONS; d++) {
                NEIGHBOR[c][d] = Geometry.neighbor(c, d);
            }
            LATERAL[c] = Math.abs(Geometry.q(c) - Geometry.s(c));
        }
    }

    private final double[] weights;
    private final double scale;
    private final double[] scratch = new double[FEATURES];
    private final int[][] cells = new int[2][Position.PIECES];

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
        features(position, scratch, cells);
        double sum = 0;
        for (int i = 0; i < FEATURES; i++) {
            sum += weights[i] * scratch[i];
        }
        return (int) Math.round(scale * sum);
    }

    /** The feature vector of a position (side to move minus opponent), into {@code out}. */
    public static void features(Position position, double[] out) {
        features(position, out, new int[2][Position.PIECES]);
    }

    private static void features(Position position, double[] out, int[][] cells) {
        int me = position.sideToMove();
        int n0 = 0;
        int n1 = 0;
        int[] c0 = cells[0];
        int[] c1 = cells[1];
        for (int c = 0; c < Geometry.CELLS; c++) {
            int o = position.owner(c);
            if (o == 0) {
                if (n0 < Position.PIECES) {
                    c0[n0++] = c;
                }
            } else if (o == 1 && n1 < Position.PIECES) {
                c1[n1++] = c;
            }
        }
        java.util.Arrays.fill(out, 0, 10, 0.0);
        Layout layout = position.layout();
        int dMe = side(position, layout, me, cells[me], me == 0 ? n0 : n1, out, 1);
        int dOpp = side(position, layout, 1 - me, cells[1 - me], me == 0 ? n1 : n0, out, -1);
        out[10] = out[0] * (dMe + dOpp) / 100.0;
        out[11] = 1.0;
    }

    /** Adds {@code sign} times the per-side features of player p to out[0..9]; returns the distance sum. */
    private static int side(Position position, Layout layout, int p, int[] pieces, int n, double[] out, int sign) {
        int goal = layout.goalCorner(p);
        int start = layout.startCorner(p);
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
            int c = pieces[i];
            int d = layout.distance(p, c);
            dsum += d;
            dsq += d * d;
            dmax = Math.max(dmax, d);
            dmin = Math.min(dmin, d);
            int corner = Geometry.cornerOf(c);
            if (corner == goal) {
                inGoal++;
            } else if (corner == start) {
                inStart++;
            }
            lateral += LATERAL[c];
            int[] nb = NEIGHBOR[c];
            for (int dir = 0; dir < Geometry.DIRECTIONS; dir++) {
                int a = nb[dir];
                if (a < 0) {
                    continue;
                }
                int owner = position.owner(a);
                if (owner < 0) {
                    if (layout.allowed(p, a) && layout.distance(p, a) < d) {
                        steps++;
                    }
                } else {
                    if (owner == p && a > c) {
                        adjacent++;
                    }
                    int land = NEIGHBOR[a][dir];
                    if (land >= 0 && position.owner(land) < 0 && layout.allowed(p, land)
                            && layout.distance(p, land) < d) {
                        jumps++;
                    }
                }
            }
        }
        out[0] += sign * dsum;
        out[1] += sign * dmax;
        out[2] += sign * (dsq / 10.0);
        out[3] += sign * inGoal;
        out[4] += sign * inStart;
        out[5] += sign * (n == 0 ? 0 : dmax - dmin);
        out[6] += sign * lateral;
        out[7] += sign * jumps;
        out[8] += sign * adjacent;
        out[9] += sign * steps;
        return dsum;
    }
}
