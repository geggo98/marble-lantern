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
 * plus {@code dsumPhase}: the {@code dsum} difference times the total remaining distance / 100, the total in
 * units of 10 pieces on the 5-row board (see {@code phaseDistance}) (lets the
 * weight of the race change with the game phase), and {@code tempo}: a constant 1 for the side to move.
 *
 * <p>Optional 13th feature (round-2 experiment E07, {@link #GAIN_FEATURES} weights): {@code bestGain}, the largest
 * one-move distance gain {@code DIST[from] - DIST[to]} over the pieces of a side outside its goal, over steps and
 * jump chains (any hurdle, allowed landings only, the moving piece's own cell counts as empty), floored at 0 and
 * capped at {@link #GAIN_CAP}. {@link GainMode#BOTH} uses "side to move minus opponent", {@link GainMode#MOVER}
 * the side to move only. The suicide rule (Definition 2) is ignored: the feature is an evaluation term, not a move
 * list. A 12-weight evaluator never computes it, so {@code linear}, {@code linear-r1} and {@code linear-r2} keep
 * their values and their cost.
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
    /** Index of the constant tempo term, in both feature sets. */
    public static final int TEMPO = 11;
    /** Number of features with {@code bestGain} (E07); the gain is appended after {@link #TEMPO}. */
    public static final int GAIN_FEATURES = 13;
    /** Index of {@code bestGain} in a {@link #GAIN_FEATURES} vector. */
    public static final int BEST_GAIN = 12;
    /** The names of the {@link #GAIN_FEATURES} features. */
    public static final String[] GAIN_NAMES = {
        "dsum", "dmax", "dsq", "inGoal", "inStart", "spread", "lateral", "jumps", "adjacent", "steps",
        "dsumPhase", "tempo", "bestGain"
    };
    /** The largest {@code bestGain} reported per side; the search for a side stops once it is reached. */
    public static final int GAIN_CAP = 6;

    /** How the {@code bestGain} feature combines the two sides (E07). */
    public enum GainMode {
        /** The side to move's best gain minus the opponent's. */
        BOTH,
        /** The side to move's best gain only. */
        MOVER
    }

    /** Layouts: two and three players, each on 4-row and 5-row houses. */
    static final int LAYOUTS = 4;
    /** Per layout: the most pieces per side that {@link #side} counts. */
    static final int[] PIECE_CAP = {Position.PIECES, Position.PIECES, Position.MAX_PIECES, Position.MAX_PIECES};

    /**
     * The table index of a rule set: {@code players - 2} for 4-row houses (so 0 and 1 are the tables of the 4-row
     * board), plus 2 for 5-row houses.
     */
    static int layoutIndex(sternhalma.engine.RuleSet rules) {
        return layoutIndex(rules.players(), rules.triangleRows());
    }

    static int layoutIndex(int players, sternhalma.engine.RuleSet.TriangleRows rows) {
        return players - 2 + (rows == sternhalma.engine.RuleSet.TriangleRows.FIVE ? 2 : 0);
    }

    /** Flat neighbour and jump-landing tables, index {@code cell * DIRECTIONS + direction}. */
    static final int[] STEP = new int[Geometry.CELLS * Geometry.DIRECTIONS];
    static final int[] LAND = new int[Geometry.CELLS * Geometry.DIRECTIONS];
    static final int[] LATERAL = new int[Geometry.CELLS];
    /** Bit in {@link #GOOD}: the neighbour is an allowed cell closer to the goal than the piece. */
    static final int STEP_CLOSER = 1;
    /** Bit in {@link #GOOD}: the jump landing is an allowed cell closer to the goal than the piece. */
    static final int JUMP_CLOSER = 2;
    /** Per layout (see {@link #layoutIndex}) and player: {@code STEP_CLOSER | JUMP_CLOSER} per (cell, direction). */
    static final byte[][][] GOOD = new byte[LAYOUTS][][];
    /** Per layout and player: the single-agent distance per cell. */
    static final int[][][] DIST = new int[LAYOUTS][][];
    /** Per layout and player: 1 for a goal cell, 2 for a start cell, 0 otherwise. */
    static final byte[][][] KIND = new byte[LAYOUTS][][];
    /** Per layout and player: the step target per (cell, direction) if it is an allowed cell, else -1. */
    private static final int[][][] STEP_ALLOWED = new int[LAYOUTS][][];
    /** Per layout and player: the jump landing per (cell, direction) if it is an allowed cell, else -1. */
    private static final int[][][] LAND_ALLOWED = new int[LAYOUTS][][];
    static final int IN_GOAL = 1;
    static final int IN_START = 2;

    static {
        for (int c = 0; c < Geometry.CELLS; c++) {
            for (int d = 0; d < Geometry.DIRECTIONS; d++) {
                STEP[c * Geometry.DIRECTIONS + d] = Geometry.neighbor(c, d);
                LAND[c * Geometry.DIRECTIONS + d] = Geometry.jumpLanding(c, d);
            }
            LATERAL[c] = Math.abs(Geometry.q(c) - Geometry.s(c));
        }
        for (int li = 0; li < LAYOUTS; li++) {
            int players = li % 2 + 2;
            Layout layout = Layout.of(players, li >= 2 ? sternhalma.engine.RuleSet.TriangleRows.FIVE : sternhalma.engine.RuleSet.TriangleRows.FOUR);
            byte[][] good = new byte[players][Geometry.CELLS * Geometry.DIRECTIONS];
            int[][] dist = new int[players][Geometry.CELLS];
            byte[][] kind = new byte[players][Geometry.CELLS];
            int[][] stepAllowed = new int[players][Geometry.CELLS * Geometry.DIRECTIONS];
            int[][] landAllowed = new int[players][Geometry.CELLS * Geometry.DIRECTIONS];
            for (int p = 0; p < players; p++) {
                for (int i = 0; i < STEP.length; i++) {
                    stepAllowed[p][i] = STEP[i] >= 0 && layout.allowed(p, STEP[i]) ? STEP[i] : -1;
                    landAllowed[p][i] = LAND[i] >= 0 && layout.allowed(p, LAND[i]) ? LAND[i] : -1;
                }
                for (int c = 0; c < Geometry.CELLS; c++) {
                    dist[p][c] = layout.distance(p, c);
                    kind[p][c] = (byte) (layout.inGoal(p, c) ? IN_GOAL : layout.inStart(p, c) ? IN_START : 0);
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
            GOOD[li] = good;
            DIST[li] = dist;
            KIND[li] = kind;
            STEP_ALLOWED[li] = stepAllowed;
            LAND_ALLOWED[li] = landAllowed;
        }
    }

    private static boolean closer(Layout layout, int p, int cell, int here) {
        return cell >= 0 && layout.allowed(p, cell) && layout.distance(p, cell) < here;
    }

    // per-side integer sums, indices into the scratch array of side()
    static final int DSUM = 0;
    static final int DMAX = 1;
    static final int DMIN = 2;
    static final int DSQ = 3;
    static final int GOAL = 4;
    static final int START = 5;
    static final int LAT = 6;
    static final int JUMPS = 7;
    static final int ADJ = 8;
    static final int STEPS = 9;
    static final int COUNT = 10;
    static final int SIDE_SUMS = 11;

    private final double[] weights;
    private final double scale;
    /** null for the 12-feature evaluator, which never computes {@code bestGain}. */
    private final GainMode gain;
    private final double[] scratch = new double[FEATURES];
    private final int[] sums = new int[SIDE_SUMS];
    private final int[] otherSums = new int[SIDE_SUMS];
    private final GainSearch gainSearch = new GainSearch();

    public LinearEvaluator() {
        this(LinearWeights.WEIGHTS, LinearWeights.SCALE);
    }

    /**
     * A 12-feature evaluator ({@link #FEATURES} weights), or with {@link #GAIN_FEATURES} weights one with
     * {@code bestGain} in mode {@link GainMode#BOTH}.
     *
     * @param scale score units per logit unit
     */
    public LinearEvaluator(double[] weights, double scale) {
        this(weights, scale, weights.length == GAIN_FEATURES ? GainMode.BOTH : null);
    }

    /**
     * @param gain null for the 12 features ({@link #FEATURES} weights), else the mode of {@code bestGain}
     *             ({@link #GAIN_FEATURES} weights)
     */
    public LinearEvaluator(double[] weights, double scale, GainMode gain) {
        int need = gain == null ? FEATURES : GAIN_FEATURES;
        if (weights.length != need) {
            throw new IllegalArgumentException("need " + need + " weights, got " + weights.length);
        }
        this.weights = weights.clone();
        this.scale = scale;
        this.gain = gain;
    }

    /** The mode of {@code bestGain}, or null for the 12-feature evaluator. */
    public GainMode gainMode() {
        return gain;
    }

    @Override
    public int evaluate(Position position) {
        features(position, scratch, sums, otherSums);
        return score(scratch, gain == null ? 0.0 : gainFeature(position, gain, gainSearch));
    }

    /** The rounded score of the 12 features and, for a {@link #GAIN_FEATURES} evaluator, the {@code bestGain} value. */
    int score(double[] features, double bestGain) {
        double sum = 0;
        for (int i = 0; i < FEATURES; i++) {
            sum += weights[i] * features[i];
        }
        if (gain != null) {
            sum += weights[BEST_GAIN] * bestGain;
        }
        return (int) Math.round(scale * sum);
    }

    /** A state that keeps the sums of the features up to date along a line of play (E13). */
    @Override
    public EvalState incremental() {
        return new IncrementalLinear(this);
    }

    /** The feature vector of a position (side to move minus opponent), into {@code out}. */
    public static void features(Position position, double[] out) {
        features(position, out, new int[SIDE_SUMS], new int[SIDE_SUMS]);
    }

    /**
     * The {@link #GAIN_FEATURES} features: the 12 of {@link #features(Position, double[])} (bit-identical) and
     * {@code bestGain} in the given mode at index {@link #BEST_GAIN}.
     */
    public static void features(Position position, double[] out, GainMode mode) {
        features(position, out, new int[SIDE_SUMS], new int[SIDE_SUMS]);
        out[BEST_GAIN] = gainFeature(position, mode, new GainSearch());
    }

    private static double gainFeature(Position position, GainMode mode, GainSearch search) {
        int me = position.sideToMove();
        search.begin(position);
        int mine = search.bestGain(position, me);
        return mode == GainMode.MOVER ? mine : mine - search.bestGain(position, 1 - me);
    }

    /**
     * The largest one-move distance gain of player {@code p} over its pieces outside its goal (steps and jump
     * chains to allowed free cells), floored at 0 and capped at {@link #GAIN_CAP}; see the class comment.
     */
    public static int bestGain(Position position, int p) {
        GainSearch search = new GainSearch();
        search.begin(position);
        return search.bestGain(position, p);
    }

    /** Per layout: whether all players may stand on the same cells (two players: centre and both main corners). */
    private static final boolean[] SHARED_CELLS = new boolean[LAYOUTS];
    /**
     * Per layout and player, the jumps from each allowed cell as a compressed list: the jumps of cell c are
     * {@code EDGES[e]} for {@code EDGE_START[c] <= e < EDGE_START[c + 1]}, each {@code hurdle << 8 | landing}, with
     * the landing an allowed cell. Cells are below 128, so 8 bits each suffice.
     */
    private static final int[][][] EDGE_START = new int[LAYOUTS][][];
    private static final int[][][] EDGES = new int[LAYOUTS][][];

    static {
        for (int li = 0; li < LAYOUTS; li++) {
            int players = li % 2 + 2;
            Layout layout = Layout.of(players, li >= 2 ? sternhalma.engine.RuleSet.TriangleRows.FIVE : sternhalma.engine.RuleSet.TriangleRows.FOUR);
            boolean shared = true;
            for (int p = 1; p < players; p++) {
                for (int c = 0; c < Geometry.CELLS; c++) {
                    shared &= layout.allowed(p, c) == layout.allowed(0, c);
                }
            }
            SHARED_CELLS[li] = shared && players == 2; // the shared graph keeps minima for two players
            if (players == 2 && !shared) {
                throw new AssertionError("two-player layout: players may stand on different cells");
            }
            EDGE_START[li] = new int[players][Geometry.CELLS + 1];
            EDGES[li] = new int[players][];
            for (int p = 0; p < players; p++) {
                int[] start = EDGE_START[li][p];
                int[] list = new int[Geometry.CELLS * Geometry.DIRECTIONS];
                int count = 0;
                for (int c = 0; c < Geometry.CELLS; c++) {
                    start[c] = count;
                    if (!layout.allowed(p, c)) {
                        continue;
                    }
                    for (int d = 0; d < Geometry.DIRECTIONS; d++) {
                        int land = LAND_ALLOWED[li][p][c * Geometry.DIRECTIONS + d];
                        if (land >= 0) {
                            list[count++] = STEP[c * Geometry.DIRECTIONS + d] << 8 | land;
                        }
                    }
                }
                start[Geometry.CELLS] = count;
                EDGES[li][p] = java.util.Arrays.copyOf(list, count);
            }
        }
    }

    /**
     * The {@code bestGain} search, by jump components instead of one jump search per piece. A jump chain moves over
     * the free allowed cells along the edges "X to Y = X + 2e over an occupied hurdle X + e"; these edges are
     * symmetric, so the cells reachable by a chain from a piece on A are the connected components of A's first
     * landings. The moving piece leaves A, but that changes nothing: A is never a hurdle of its own chain (every
     * landing lies at an even offset from A, every hurdle of a jump between such cells at an odd one), and landing
     * on A again only leads back to A's first landings. The components depend only on the occupancy and the allowed
     * cells, so each is labelled once per position, with its smallest distance for every player, and shared by all
     * pieces and (two players: the same allowed cells) both sides. Components are labelled lazily, when a first
     * landing reaches them; a stamp per evaluation marks the valid labels, so nothing is cleared.
     */
    private static final class GainSearch {
        private static final int MAX_PLAYERS = 3;
        /** At most one component per free cell. */
        private static final int MAX_COMPONENTS = Geometry.CELLS;
        /** Occupied cells (any player) as a 128-bit set, cells 0 to 63 in {@code occLo}, 64 to 120 in {@code occHi}. */
        private long occLo;
        private long occHi;
        /** Per graph (one shared graph, or one per player): the cells of each labelled component, as 128-bit sets. */
        private final long[][] compLo = new long[MAX_PLAYERS][MAX_COMPONENTS];
        private final long[][] compHi = new long[MAX_PLAYERS][MAX_COMPONENTS];
        /** Per graph: the union of its labelled components. */
        private final long[] doneLo = new long[MAX_PLAYERS];
        private final long[] doneHi = new long[MAX_PLAYERS];
        /**
         * Per graph, per component: the smallest distance of the graph's player (a graph per player), or of player 0
         * in the shared graph; {@link #minB}: of player 1 in the shared graph.
         */
        private final int[][] minA = new int[MAX_PLAYERS][MAX_COMPONENTS];
        private final int[] minB = new int[MAX_COMPONENTS];
        private final int[] components = new int[MAX_PLAYERS];
        private final int[] stack = new int[Geometry.CELLS];
        private final int[] order = new int[Position.MAX_PIECES];

        /** Starts a new position: no component labelled, the occupancy from the piece lists. */
        void begin(Position position) {
            long lo = 0;
            long hi = 0;
            int players = position.rules().players();
            for (int p = 0; p < players; p++) {
                for (int i = 0, n = position.pieceCount(p); i < n; i++) {
                    int c = position.pieceCell(p, i);
                    if (c < 64) {
                        lo |= 1L << c;
                    } else {
                        hi |= 1L << c; // the shift uses c mod 64
                    }
                }
            }
            occLo = lo;
            occHi = hi;
            for (int g = 0; g < MAX_PLAYERS; g++) {
                components[g] = 0;
                doneLo[g] = 0;
                doneHi[g] = 0;
            }
        }

        /** Bit c of the 128-bit set (lo, hi). */
        private static boolean has(long lo, long hi, int c) {
            return ((c < 64 ? lo : hi) >>> c & 1L) != 0;
        }

        int bestGain(Position position, int p) {
            int layout = layoutIndex(position.rules());
            boolean shared = SHARED_CELLS[layout];
            int g = shared ? 0 : p;
            int[] dist = DIST[layout][p];
            byte[] kind = KIND[layout][p];
            int[] edgeStart = EDGE_START[layout][p];
            int[] edges = EDGES[layout][p];
            int[] steps = STEP_ALLOWED[layout][p];
            int[] mins = shared && p == 1 ? minB : minA[g];
            long lo = occLo;
            long hi = occHi;
            // pieces outside the goal by falling distance (packed distance << 8 | cell), so that the gain found
            // early lets the bound "no gain above the piece's distance" skip the rest
            int n = Math.min(position.pieceCount(p), PIECE_CAP[layout]);
            int m = 0;
            for (int i = 0; i < n; i++) {
                int c = position.pieceCell(p, i);
                if (kind[c] == IN_GOAL) {
                    continue;
                }
                int key = dist[c] << 8 | c;
                int j = m++;
                while (j > 0 && order[j - 1] < key) {
                    order[j] = order[j - 1];
                    j--;
                }
                order[j] = key;
            }
            int best = 0;
            for (int i = 0; i < m; i++) {
                int from = order[i] & 0xFF;
                int d0 = order[i] >>> 8;
                if (d0 <= best) {
                    break; // every later piece is at most as far: none can gain more
                }
                int base = from * Geometry.DIRECTIONS;
                for (int dir = 0; dir < Geometry.DIRECTIONS; dir++) {
                    int a = steps[base + dir];
                    if (a >= 0 && !has(lo, hi, a) && d0 - dist[a] > best) {
                        best = d0 - dist[a];
                    }
                }
                for (int e = edgeStart[from], end = edgeStart[from + 1]; e < end; e++) {
                    int land = edges[e] & 0xFF;
                    if (!has(lo, hi, land) && has(lo, hi, edges[e] >>> 8)) {
                        int gain = d0 - mins[componentOf(layout, shared, g, edgeStart, edges, land)];
                        if (gain > best) {
                            best = gain;
                        }
                    }
                }
                if (best >= GAIN_CAP) {
                    return GAIN_CAP;
                }
            }
            return best;
        }

        /**
         * The component of the free cell {@code start} in graph g, labelled now (with its smallest distances) if it
         * is in no labelled component yet. The graph's edge lists are those of its player (of any player for the
         * shared graph).
         */
        private int componentOf(int layout, boolean shared, int g, int[] edgeStart, int[] edges, int start) {
            long[] cl = compLo[g];
            long[] ch = compHi[g];
            if (has(doneLo[g], doneHi[g], start)) {
                for (int k = 0; ; k++) {
                    if (has(cl[k], ch[k], start)) {
                        return k;
                    }
                }
            }
            long lo = occLo;
            long hi = occHi;
            // blocked = occupied or already in this component
            long bLo = lo;
            long bHi = hi;
            if (start < 64) {
                bLo |= 1L << start;
            } else {
                bHi |= 1L << start;
            }
            int[] distA = DIST[layout][g];
            int[] distB = DIST[layout][1];
            int lowA = Integer.MAX_VALUE;
            int lowB = Integer.MAX_VALUE;
            int top = 0;
            stack[top++] = start;
            while (top > 0) {
                int c = stack[--top];
                lowA = Math.min(lowA, distA[c]);
                lowB = Math.min(lowB, distB[c]);
                for (int e = edgeStart[c], end = edgeStart[c + 1]; e < end; e++) {
                    int y = edges[e] & 0xFF;
                    if (has(bLo, bHi, y) || !has(lo, hi, edges[e] >>> 8)) {
                        continue;
                    }
                    if (y < 64) {
                        bLo |= 1L << y;
                    } else {
                        bHi |= 1L << y;
                    }
                    stack[top++] = y;
                }
            }
            int k = components[g]++;
            cl[k] = bLo & ~lo; // the component's cells: blocked minus occupied
            ch[k] = bHi & ~hi;
            doneLo[g] |= cl[k];
            doneHi[g] |= ch[k];
            minA[g][k] = lowA;
            if (shared) {
                minB[k] = lowB; // the shared graph serves both players of a two-player game
            }
            return k;
        }
    }

    /**
     * Integer sums per side from the piece lists and the tables, then the doubles in exactly the order of the
     * original cell-scanning code (LinearEvaluatorReference in the tests), so that every feature and the rounded
     * score stay bit-identical: out[i] = 0.0 + me + (-1 * opponent), dsq as dsq / 10.0 per side.
     */
    private static void features(Position position, double[] out, int[] sums, int[] otherSums) {
        int me = position.sideToMove();
        int layout = layoutIndex(position.rules());
        side(position, layout, me, sums);
        side(position, layout, 1 - me, otherSums);
        combine(sums, otherSums, out);
    }

    /**
     * The features from the integer sums of the side to move and of the opponent (the entry point that the
     * incremental state shares with the full computation, so that both round identically).
     */
    static void combine(int[] me, int[] opponent, double[] out) {
        java.util.Arrays.fill(out, 0, 10, 0.0);
        add(out, me, 1);
        add(out, opponent, -1);
        out[10] = out[0] * phaseDistance(me, opponent) / 100.0;
        out[11] = 1.0;
    }

    /**
     * The total remaining distance of both sides in units of 10 pieces, the piece count the weights were fitted with:
     * with more than {@link Position#PIECES} pieces (15 on the 5-row board) times {@code PIECES / count}. Without this,
     * the larger total of 15 pieces turned the reward for progress around (dsumPhase has a positive weight, dsum a
     * negative one), and the 3-player browser player of the 5-row board made no progress (PhaseOnFiveRowsTest). With
     * 10 pieces the value is the plain sum, so the 4-row scores stay bit-identical.
     */
    private static double phaseDistance(int[] me, int[] opponent) {
        int total = me[DSUM] + opponent[DSUM];
        int count = me[COUNT];
        return count > Position.PIECES ? (double) total * Position.PIECES / count : total;
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
    static void side(Position position, int layout, int p, int[] s) {
        byte[] good = GOOD[layout][p];
        int[] dist = DIST[layout][p];
        byte[] kind = KIND[layout][p];
        int n = Math.min(position.pieceCount(p), PIECE_CAP[layout]);
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
