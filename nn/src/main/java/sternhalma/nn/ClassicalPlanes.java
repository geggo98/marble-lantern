package sternhalma.nn;

import java.util.Arrays;
import sternhalma.engine.Geometry;
import sternhalma.engine.Layout;
import sternhalma.engine.Move;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;

/**
 * The classical input planes of {@link PlaneSet#CLASSIC1} (docs/network.md, "Classical planes and residual
 * priors"): what classical code computes in microseconds from the cells of a position, written into planes
 * {@link Encoder#DIST_OWN} to {@link Encoder#EVAL}.
 *
 * <ul>
 *   <li>{@link Encoder#DIST_OWN}, {@link Encoder#DIST_NEXT}: the single-agent distance of the cell for the mover and
 *       for relative player 1 ({@code Layout.distance}), divided by {@link #DIST_SCALE}; 0 where that player may not
 *       stand. In canonical orientation these are constants of the player count.
 *   <li>{@link Encoder#REACH_OWN}, {@link Encoder#REACH_NEXT}: 1 on every cell on which some legal move of the mover,
 *       or of relative player 1 if it were to move, ends ({@code Position.generate}; the second one on a copy with
 *       the side to move swapped).
 *   <li>{@link Encoder#PASSED}: 1 in every cell row if the armies have passed each other (two players), else 0.
 *   <li>{@link Encoder#EVAL}: the linear evaluation of the position for the mover in logit units
 *       ({@link #linearLogit}), in every cell row; 0 for three players.
 * </ul>
 *
 * <p>All planes follow from the position alone. They are never stored in a sample file: {@link SampleExpander}
 * rebuilds the position from the canonical cells and calls the same code as {@link Encoder#encode}.
 */
public final class ClassicalPlanes {

    /** The divisor of the distance planes: a power of 2 above the largest single-agent distance (13, at the start tip). */
    public static final int DIST_SCALE = 16;

    /**
     * The weights of the 12 features of the linear evaluation, in the order of {@code LinearEvaluator.NAMES}: the
     * round-2 refit {@code linear-r2} (the 12-feature weights that {@code BEST_SPEC}'s evaluator extends by
     * {@code bestGain}); {@code search} pins them to {@code LinearWeights.ROUND2} in a test.
     */
    private static final double[] LINEAR_WEIGHTS = {
        -0.264514, -0.385392, -0.167810, 0.230892, 0.110844, 0.119476, -0.109568, 0.0946884, -0.0295270, 0.0637964,
        0.223242, 0.282466
    };

    private static final int FEATURES = 12;
    // per-side integer sums, as in LinearEvaluator
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

    /** [players - 2][player][cell]: the distance divided by {@link #DIST_SCALE}, 0 for a cell the player may not enter. */
    private static final float[][][] DIST_PLANE = new float[2][][];

    static {
        for (int players = 2; players <= 3; players++) {
            Layout layout = Layout.of(players);
            float[][] table = new float[players][Geometry.CELLS];
            for (int p = 0; p < players; p++) {
                for (int c = 0; c < Geometry.CELLS; c++) {
                    table[p][c] = layout.allowed(p, c) ? (float) layout.distance(p, c) / DIST_SCALE : 0f;
                }
            }
            DIST_PLANE[players - 2] = table;
        }
    }

    private ClassicalPlanes() {
    }

    /** The weights of {@link #linearLogit}, a copy. */
    public static double[] linearWeights() {
        return LINEAR_WEIGHTS.clone();
    }

    /**
     * The distance plane value of a board cell for an absolute player: {@code distance / DIST_SCALE}, or 0 if the player
     * may not stand there.
     */
    static float distancePlane(int players, int player, int cell) {
        return DIST_PLANE[players - 2][player][cell];
    }

    /**
     * Writes the six classical planes of a position into {@code out}, at the rows of the canonical cells.
     *
     * <p>Only the six planes of the 121 cell rows are written; the caller owns the other planes and the padding row.
     *
     * @param position the position; its side to move is the mover of {@code orientation}
     * @param mirror   write each row to its left-right mirror (two players); the planes of the mirrored position
     * @param variants the plane set is {@link PlaneSet#CLASSIC1V}: {@link Encoder#EVAL} is 0 under the LONG jump rule
     *                 (the linear evaluation is fitted for adjacent jumps), as it is for three players
     * @param offset   start of the position in {@code out}
     * @param stride   planes per row of {@code out}, at least {@link Encoder#CLASSIC_PLANES}
     */
    static void fill(Position position, Orientation orientation, boolean mirror, boolean variants, float[] out,
            int offset, int stride) {
        int players = orientation.players();
        int mover = orientation.mover();
        int next = (mover + 1) % players;
        float[] distOwn = DIST_PLANE[players - 2][mover];
        float[] distNext = DIST_PLANE[players - 2][next];
        int[] row = new int[Geometry.CELLS];
        for (int c = 0; c < Geometry.CELLS; c++) {
            int canonical = orientation.toCanonical(c);
            row[c] = offset + (mirror ? Geometry.mirror(canonical) : canonical) * stride;
            out[row[c] + Encoder.DIST_OWN] = distOwn[c];
            out[row[c] + Encoder.DIST_NEXT] = distNext[c];
            out[row[c] + Encoder.REACH_OWN] = 0f;
            out[row[c] + Encoder.REACH_NEXT] = 0f;
        }
        int[] moves = new int[Position.MAX_MOVES];
        int n = position.generate(moves);
        for (int i = 0; i < n; i++) {
            out[row[Move.to(moves[i])] + Encoder.REACH_OWN] = 1f;
        }
        // The side that is not to move: a copy with the side swapped. transformed() sets up a fresh position (no
        // history), where the side to move can be chosen.
        Position other = position.transformed(cell -> cell, false).setSideToMove(next);
        n = other.generate(moves);
        for (int i = 0; i < n; i++) {
            out[row[Move.to(moves[i])] + Encoder.REACH_NEXT] = 1f;
        }
        float passed = passed(position) ? 1f : 0f;
        boolean evalOn = players == 2 && !(variants && position.rules().jumpRule() == RuleSet.JumpRule.LONG);
        float eval = evalOn ? (float) linearLogit(position) : 0f;
        for (int c = 0; c < Geometry.CELLS; c++) {
            out[row[c] + Encoder.PASSED] = passed;
            out[row[c] + Encoder.EVAL] = eval;
        }
    }

    /**
     * Whether the armies have passed each other: all 10 pieces of both players are on the board and every piece of
     * player 0 has a larger {@code r} than every piece of player 1. Two players only; the same as
     * {@code RaceAdjudication.passed} ({@code search} tests it).
     */
    public static boolean passed(Position position) {
        if (position.rules().players() != 2) {
            return false;
        }
        if (position.pieceCount(0) != Position.PIECES || position.pieceCount(1) != Position.PIECES) {
            return false;
        }
        // The piece lists are sorted by cell id, and ids are ordered by r first.
        return Geometry.r(position.pieceCell(0, 0)) > Geometry.r(position.pieceCell(1, Position.PIECES - 1));
    }

    /**
     * The linear evaluation of a two-player position for the side to move, in logit units: the sum of the weights of
     * {@code linear-r2} times the 12 features "side to move minus opponent" of {@code LinearEvaluator}. The score of
     * {@code LinearEvaluator} is {@code round(100 * logit)}; {@code search} tests that they agree for every position.
     *
     * @throws IllegalArgumentException if the position is not a two-player position
     */
    public static double linearLogit(Position position) {
        if (position.rules().players() != 2) {
            throw new IllegalArgumentException("the linear evaluation is defined for two players only");
        }
        int me = position.sideToMove();
        int[] mine = new int[SIDE_SUMS];
        int[] theirs = new int[SIDE_SUMS];
        side(position, me, mine);
        side(position, 1 - me, theirs);
        double[] f = new double[FEATURES];
        Arrays.fill(f, 0, 10, 0.0);
        add(f, mine, 1);
        add(f, theirs, -1);
        f[10] = f[0] * (mine[DSUM] + theirs[DSUM]) / 100.0;
        f[11] = 1.0;
        double sum = 0;
        for (int i = 0; i < FEATURES; i++) {
            sum += LINEAR_WEIGHTS[i] * f[i];
        }
        return sum;
    }

    /** {@code sign} times the per-side features into f[0..9], with the operations of the original code. */
    private static void add(double[] f, int[] s, int sign) {
        int n = s[COUNT];
        f[0] += sign * s[DSUM];
        f[1] += sign * s[DMAX];
        f[2] += sign * (s[DSQ] / 10.0);
        f[3] += sign * s[GOAL];
        f[4] += sign * s[START];
        f[5] += sign * (n == 0 ? 0 : s[DMAX] - s[DMIN]);
        f[6] += sign * s[LAT];
        f[7] += sign * s[JUMPS];
        f[8] += sign * s[ADJ];
        f[9] += sign * s[STEPS];
    }

    /** The integer sums of player p over its pieces: distance, goal and start corner, lateral, jumps, bridges, steps. */
    private static void side(Position position, int p, int[] s) {
        Layout layout = position.layout();
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
            int d = layout.distance(p, c);
            dsum += d;
            dsq += d * d;
            dmax = Math.max(dmax, d);
            dmin = Math.min(dmin, d);
            int corner = Geometry.cornerOf(c);
            if (corner == layout.goalCorner(p)) {
                inGoal++;
            } else if (corner == layout.startCorner(p)) {
                inStart++;
            }
            lateral += Math.abs(Geometry.q(c) - Geometry.s(c));
            for (int dir = 0; dir < Geometry.DIRECTIONS; dir++) {
                int a = Geometry.neighbor(c, dir);
                if (a < 0) {
                    continue;
                }
                int owner = position.owner(a);
                if (owner < 0) {
                    if (closer(layout, p, a, d)) {
                        steps++;
                    }
                } else {
                    if (owner == p && a > c) {
                        adjacent++;
                    }
                    int land = Geometry.jumpLanding(c, dir);
                    if (closer(layout, p, land, d) && position.owner(land) < 0) {
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

    /** Whether {@code cell} is on the board, allowed for the player and strictly closer to its goal than {@code here}. */
    private static boolean closer(Layout layout, int p, int cell, int here) {
        return cell >= 0 && layout.allowed(p, cell) && layout.distance(p, cell) < here;
    }
}
