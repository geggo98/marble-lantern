package sternhalma.search;

import java.util.Arrays;

import sternhalma.engine.Geometry;
import sternhalma.engine.Move;
import sternhalma.engine.Position;

/**
 * The incremental state of a {@link LinearEvaluator} (round-2 experiment E13): the integer sums of both sides, kept
 * up to date by {@link #make} and {@link #unmake} instead of being summed over all pieces at every leaf. The score
 * comes out of the same {@link LinearEvaluator#combine} and {@link LinearEvaluator#score} as the full evaluation, from
 * the same integers, so it is bit-identical to it ({@code IncrementalEvaluationTest}).
 *
 * <p><b>Piece terms.</b> {@code dsum}, {@code dsq}, {@code inGoal}, {@code inStart} and {@code lateral} change for the
 * moving piece only: add the table value of the new cell, subtract that of the old one. {@code dmax} and {@code dmin}
 * (the latter for {@code spread}) come from a histogram of the piece distances per side. Both are restored on
 * {@link #unmake} from a copy taken at {@link #make}, together with all other sums.
 *
 * <p><b>Local terms.</b> {@code steps}, {@code jumps} and {@code adjacent} are sums over pairs (piece c, direction d)
 * that look at the occupancy of the neighbour {@code c + d} and of the jump landing {@code c + 2d}. Emptying or
 * filling one cell x therefore changes only the pairs in which x is the piece, the neighbour or the landing: at most
 * 18 pairs, found by looping over the six directions from x. A move is the two toggles "empty from" and "fill to",
 * applied one after the other to a private copy of the occupancy, so each toggle sees a consistent board and no
 * special case is needed when from and to are near each other.
 *
 * <p>{@code bestGain} is not local (a jump chain is as long as the board), so it is searched at the leaf, but on a
 * {@link JumpGraph} that this state keeps up to date with twelve bit flips per move (the sets of landings over occupied
 * hurdles), which makes finding the components of the jump graph a flood fill with set operations instead of a
 * walk over per-cell edge lists. The pieces of each side are kept sorted by distance for the same search.
 *
 * <p>Two players and at most {@link Position#PIECES} pieces per side only; {@link #reset} refuses anything else.
 */
final class IncrementalLinear implements EvalState {

    /** Slots per side in {@link #sum}. */
    private static final int DSUM = 0;
    private static final int DSQ = 1;
    private static final int GOAL = 2;
    private static final int START = 3;
    private static final int LAT = 4;
    private static final int STEPS = 5;
    private static final int JUMPS = 6;
    private static final int ADJ = 7;
    private static final int WIDTH = 8;
    /** Slots after the sums, per side: the largest and the smallest piece distance. */
    private static final int DMAX = 2 * WIDTH;
    private static final int DMIN = DMAX + 2;
    private static final int SAVED = DMIN + 2;

    private static final byte[][] GOOD = LinearEvaluator.GOOD[0];
    private static final int[][] DIST = LinearEvaluator.DIST[0];
    private static final byte[][] KIND = LinearEvaluator.KIND[0];
    private static final int[] STEP = LinearEvaluator.STEP;
    private static final int[] LAND = LinearEvaluator.LAND;
    private static final int MAX_DISTANCE = Arrays.stream(DIST).flatMapToInt(Arrays::stream).max().orElseThrow();

    private final LinearEvaluator evaluator;
    private final JumpGraph graph = new JumpGraph();
    /** Owner of every cell, 0 or 1, or -1; the toggles edit it step by step, so it is not the position's array. */
    private final byte[] own = new byte[Geometry.CELLS];
    /** Per side {@link #WIDTH} sums, then dmax and dmin per side. */
    private final int[] sum = new int[SAVED];
    private final int[][] histogram = new int[2][MAX_DISTANCE + 1];
    private final int[] count = new int[2];
    /** Per side the pieces as packed distance &lt;&lt; 8 | cell, falling: the order that the {@code bestGain} search wants. */
    private final int[][] keys = new int[2][Position.PIECES];
    /** The stack: per height the sums before the move, then the move's from cell, to cell and player. */
    private int[][] saved = new int[64][SAVED + 3];
    private int height;
    private final double[] features = new double[LinearEvaluator.FEATURES];
    private final int[] mine = new int[LinearEvaluator.SIDE_SUMS];
    private final int[] theirs = new int[LinearEvaluator.SIDE_SUMS];

    IncrementalLinear(LinearEvaluator evaluator) {
        this.evaluator = evaluator;
    }

    @Override
    public boolean reset(Position root) {
        if (root.rules().players() != 2 || root.pieceCount(0) > Position.PIECES || root.pieceCount(1) > Position.PIECES) {
            return false;
        }
        height = 0;
        Arrays.fill(own, (byte) -1);
        Arrays.fill(histogram[0], 0);
        Arrays.fill(histogram[1], 0);
        graph.clear();
        int[] s = new int[LinearEvaluator.SIDE_SUMS];
        for (int p = 0; p < 2; p++) {
            for (int i = 0, n = root.pieceCount(p); i < n; i++) {
                int c = root.pieceCell(p, i);
                if (DIST[p][c] < 0) {
                    return false; // a piece on a cell its owner may not enter
                }
                own[c] = (byte) p;
                histogram[p][DIST[p][c]]++;
                graph.toggle(c);
            }
            // the sums by the full computation, so that the state starts from the oracle's numbers
            LinearEvaluator.side(root, 0, p, s);
            sum[p * WIDTH + DSUM] = s[LinearEvaluator.DSUM];
            sum[p * WIDTH + DSQ] = s[LinearEvaluator.DSQ];
            sum[p * WIDTH + GOAL] = s[LinearEvaluator.GOAL];
            sum[p * WIDTH + START] = s[LinearEvaluator.START];
            sum[p * WIDTH + LAT] = s[LinearEvaluator.LAT];
            sum[p * WIDTH + STEPS] = s[LinearEvaluator.STEPS];
            sum[p * WIDTH + JUMPS] = s[LinearEvaluator.JUMPS];
            sum[p * WIDTH + ADJ] = s[LinearEvaluator.ADJ];
            sum[DMAX + p] = s[LinearEvaluator.DMAX];
            sum[DMIN + p] = s[LinearEvaluator.DMIN];
            count[p] = s[LinearEvaluator.COUNT];
            for (int i = 0; i < count[p]; i++) {
                int c = root.pieceCell(p, i);
                int key = DIST[p][c] << 8 | c;
                int j = i;
                while (j > 0 && keys[p][j - 1] < key) {
                    keys[p][j] = keys[p][j - 1];
                    j--;
                }
                keys[p][j] = key;
            }
        }
        return true;
    }

    @Override
    public void make(Position position, int move) {
        int from = Move.from(move);
        int to = Move.to(move);
        int p = position.sideToMove();
        if (height == saved.length) {
            saved = Arrays.copyOf(saved, height * 2);
            for (int i = height; i < saved.length; i++) {
                saved[i] = new int[SAVED + 3];
            }
        }
        int[] frame = saved[height++];
        System.arraycopy(sum, 0, frame, 0, SAVED);
        frame[SAVED] = from;
        frame[SAVED + 1] = to;
        frame[SAVED + 2] = p;
        position.make(move);

        // the moving piece: the per-piece terms
        int[] dist = DIST[p];
        int df = dist[from];
        int dt = dist[to];
        int base = p * WIDTH;
        sum[base + DSUM] += dt - df;
        sum[base + DSQ] += dt * dt - df * df;
        sum[base + LAT] += LinearEvaluator.LATERAL[to] - LinearEvaluator.LATERAL[from];
        byte[] kind = KIND[p];
        int kf = kind[from];
        int kt = kind[to];
        if (kf != kt) {
            if (kf == LinearEvaluator.IN_GOAL) {
                sum[base + GOAL]--;
            } else if (kf == LinearEvaluator.IN_START) {
                sum[base + START]--;
            }
            if (kt == LinearEvaluator.IN_GOAL) {
                sum[base + GOAL]++;
            } else if (kt == LinearEvaluator.IN_START) {
                sum[base + START]++;
            }
        }
        replaceKey(keys[p], count[p], df << 8 | from, dt << 8 | to);
        if (df != dt) {
            int[] h = histogram[p];
            h[df]--;
            h[dt]++;
            if (dt > sum[DMAX + p]) {
                sum[DMAX + p] = dt;
            } else {
                int top = sum[DMAX + p];
                while (h[top] == 0) {
                    top--;
                }
                sum[DMAX + p] = top;
            }
            if (dt < sum[DMIN + p]) {
                sum[DMIN + p] = dt;
            } else {
                int low = sum[DMIN + p];
                while (h[low] == 0) {
                    low++;
                }
                sum[DMIN + p] = low;
            }
        }

        // the pair terms
        toggle(from, p, -1);
        toggle(to, p, +1);
        graph.toggle(from);
        graph.toggle(to);
    }

    @Override
    public void unmake(Position position) {
        int[] frame = saved[--height];
        int from = frame[SAVED];
        int to = frame[SAVED + 1];
        int p = frame[SAVED + 2];
        position.unmake();
        int df = DIST[p][from];
        int dt = DIST[p][to];
        replaceKey(keys[p], count[p], dt << 8 | to, df << 8 | from);
        if (df != dt) {
            histogram[p][dt]--;
            histogram[p][df]++;
        }
        own[to] = -1;
        own[from] = (byte) p;
        System.arraycopy(frame, 0, sum, 0, SAVED);
        graph.toggle(to);
        graph.toggle(from);
    }

    /** Replaces {@code oldKey} by {@code newKey} in the falling array {@code a} of {@code n} keys, keeping it falling. */
    private static void replaceKey(int[] a, int n, int oldKey, int newKey) {
        int i = 0;
        while (a[i] != oldKey) {
            i++;
        }
        while (i > 0 && a[i - 1] < newKey) {
            a[i] = a[i - 1];
            i--;
        }
        while (i + 1 < n && a[i + 1] > newKey) {
            a[i] = a[i + 1];
            i++;
        }
        a[i] = newKey;
    }

    /**
     * Fills (sign +1) or empties (sign -1) the cell x for player p and adds the change of the pair terms to the
     * sums. The six loop passes cover, per direction e from x with a = x + e and l = x + 2e: the pairs of x itself
     * (neighbour a, landing l), the pair of a piece on a that looks at x as its neighbour (direction e ^ 1) and the
     * pair of a piece on l that jumps over a onto x. No term looks at x in any other role, and no term of these
     * pairs looks at x's own owner, so the order of {@code own[x]} and the sums does not matter.
     */
    private void toggle(int x, int p, int sign) {
        own[x] = (byte) (sign > 0 ? p : -1);
        byte[] ownGood = GOOD[p];
        int[] s = sum;
        for (int e = 0; e < Geometry.DIRECTIONS; e++) {
            int i = x * Geometry.DIRECTIONS + e;
            int a = STEP[i];
            if (a < 0) {
                continue;
            }
            int oa = own[a];
            int g = ownGood[i];
            if (oa < 0) {
                // x's own pair: the neighbour is free, a step if it is closer
                s[p * WIDTH + STEPS] += sign * (g & LinearEvaluator.STEP_CLOSER);
            } else {
                // x's own pair: the neighbour is occupied, an adjacent pair if it is ours and above x, a jump if free
                // landing closer
                if (oa == p && a > x) {
                    s[p * WIDTH + ADJ] += sign;
                }
                if ((g & LinearEvaluator.JUMP_CLOSER) != 0 && own[LAND[i]] < 0) {
                    s[p * WIDTH + JUMPS] += sign;
                }
                // the piece on a looks at x as its neighbour: a free step becomes blocked, a blocked cell is an
                // adjacent pair or a possible jump
                int j = a * Geometry.DIRECTIONS + (e ^ 1);
                int q = oa;
                int gj = GOOD[q][j];
                s[q * WIDTH + STEPS] -= sign * (gj & LinearEvaluator.STEP_CLOSER);
                if (q == p && x > a) {
                    s[p * WIDTH + ADJ] += sign;
                }
                if ((gj & LinearEvaluator.JUMP_CLOSER) != 0 && own[LAND[j]] < 0) {
                    s[q * WIDTH + JUMPS] += sign;
                }
                // the piece on l jumps over the occupied a onto x: possible while x is free
                int l = LAND[i];
                if (l >= 0) {
                    int r = own[l];
                    if (r >= 0 && (GOOD[r][l * Geometry.DIRECTIONS + (e ^ 1)] & LinearEvaluator.JUMP_CLOSER) != 0) {
                        s[r * WIDTH + JUMPS] -= sign;
                    }
                }
            }
        }
    }

    @Override
    public int evaluate(Position position) {
        int me = position.sideToMove();
        fill(me, mine);
        fill(1 - me, theirs);
        LinearEvaluator.combine(mine, theirs, features);
        double gain = 0.0;
        if (evaluator.gainMode() != null) {
            graph.begin();
            int mineGain = graph.bestGain(me, keys[me], count[me]);
            gain = evaluator.gainMode() == LinearEvaluator.GainMode.MOVER ? mineGain
                    : mineGain - graph.bestGain(1 - me, keys[1 - me], count[1 - me]);
        }
        return evaluator.score(features, gain);
    }

    /** The sums of player p in the layout of {@link LinearEvaluator#side}. */
    private void fill(int p, int[] out) {
        int base = p * WIDTH;
        out[LinearEvaluator.DSUM] = sum[base + DSUM];
        out[LinearEvaluator.DMAX] = sum[DMAX + p];
        out[LinearEvaluator.DMIN] = sum[DMIN + p];
        out[LinearEvaluator.DSQ] = sum[base + DSQ];
        out[LinearEvaluator.GOAL] = sum[base + GOAL];
        out[LinearEvaluator.START] = sum[base + START];
        out[LinearEvaluator.LAT] = sum[base + LAT];
        out[LinearEvaluator.JUMPS] = sum[base + JUMPS];
        out[LinearEvaluator.ADJ] = sum[base + ADJ];
        out[LinearEvaluator.STEPS] = sum[base + STEPS];
        out[LinearEvaluator.COUNT] = count[p];
    }

    @Override
    public void verify(Position position) {
        int[] full = new int[LinearEvaluator.SIDE_SUMS];
        int[] mine = new int[LinearEvaluator.SIDE_SUMS];
        for (int p = 0; p < 2; p++) {
            LinearEvaluator.side(position, 0, p, full);
            fill(p, mine);
            if (!Arrays.equals(full, mine)) {
                throw new AssertionError("incremental sums of player " + p + " " + Arrays.toString(mine)
                        + " differ from the full " + Arrays.toString(full) + "\n" + position);
            }
        }
        for (int p = 0; p < 2; p++) {
            int[] expected = new int[count[p]];
            for (int i = 0; i < count[p]; i++) {
                int c = position.pieceCell(p, i);
                expected[i] = DIST[p][c] << 8 | c;
            }
            Arrays.sort(expected);
            for (int i = 0; i < count[p]; i++) {
                if (keys[p][i] != expected[count[p] - 1 - i]) {
                    throw new AssertionError("piece order of player " + p + " " + Arrays.toString(keys[p])
                            + " against " + Arrays.toString(expected) + "\n" + position);
                }
            }
        }
        graph.verify(position);
        for (int c = 0; c < Geometry.CELLS; c++) {
            if (own[c] != position.owner(c)) {
                throw new AssertionError("owner of cell " + c + ": " + own[c] + " against " + position.owner(c));
            }
        }
    }
}
