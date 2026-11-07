package sternhalma.search;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntUnaryOperator;

import sternhalma.engine.Geometry;
import sternhalma.engine.Layout;
import sternhalma.engine.RuleSet.LeaveGoal;
import sternhalma.tablebase.RaceCounts;
import sternhalma.tablebase.Rows;

/**
 * The solitaire game of one player: only that player's pieces are on the board, they are the only hurdles, and
 * the player wants all of them in the goal corner in as few moves as possible. Used by {@link RaceMove} to play a
 * race and by {@link RaceAdjudication} to find the winner's plan.
 *
 * <p>Rows are in the player's own orientation ({@link Rows#row}). Move generation restricts <em>every</em> landing
 * cell of a move, the intermediate landings of a jump chain included, to rows {@code >= minLanding}; the path a
 * generated move used is reproducible ({@link #path}). With {@code LeaveGoal.FORBIDDEN} a piece in the goal corner
 * never leaves it. {@code SolitaireTest} checks the generator against the engine's move list on single-player
 * positions.
 *
 * <p>{@link #plan} is a beam search over configurations, layer by layer (one layer per move), with a per-move
 * constraint on landing rows and on the row of the rearmost piece after the move, ordered by the race table's exact
 * count where the table covers a configuration and by the distance sum otherwise. The first layer that contains the
 * goal gives the plan; the plan's configurations are pairwise distinct because each layer is deduplicated and a
 * configuration of an earlier layer cannot reappear in a later one without being a longer route to the same place,
 * which the plan asserts.
 *
 * <p>{@link #lowerBound} is the parity-assignment bound of docs/endgame-and-opening.md: a piece outside the goal
 * needs one move if its assigned goal cell is adjacent (a step) or in the same class {@code (q mod 2, r mod 2)} (a
 * jump chain keeps the class), else two; pieces already in the goal stay, which is never worse; the minimum over
 * assignments of outside pieces to free goal cells is {@code 2 * outside - maximumMatching}. It is admissible
 * because every move moves exactly one piece. Not thread-safe (scratch space); one instance per thread.
 */
public final class Solitaire {

    /** A planned move: the start cell, the landing cells of its chain in order (the last is the end cell). */
    public record Step(int from, int[] path) {
        public int to() {
            return path[path.length - 1];
        }

        public int move() {
            return sternhalma.engine.Move.of(from, to());
        }

        /** The smallest row (in the player's orientation) among the landing cells. */
        public int pathMinRow(int player) {
            int min = Integer.MAX_VALUE;
            for (int c : path) {
                min = Math.min(min, Rows.row(player, c));
            }
            return min;
        }
    }

    /**
     * A plan: the moves and the configurations after each move ({@code states[0]} is the start; configurations as
     * ascending cell arrays).
     */
    public record Plan(List<Step> steps, List<int[]> states) {
        public int moves() {
            return steps.size();
        }
    }

    private final int player;
    private final LeaveGoal leaveGoal;
    private final RaceCounts table;
    private final Layout layout = Layout.of(2);
    private final int goalCorner;
    private final long goalLo;
    private final long goalHi;
    private final boolean[] allowed = new boolean[Geometry.CELLS];
    private final int[] rowOf = new int[Geometry.CELLS];
    private final int[] stack = new int[Geometry.CELLS];
    private final int[] parent = new int[Geometry.CELLS];

    /**
     * @param player    0 or 1 (two-player layout)
     * @param leaveGoal the rule in force
     * @param table     race counts for the beam's ordering, or null to order by distance sums only
     */
    public Solitaire(int player, LeaveGoal leaveGoal, RaceCounts table) {
        this.player = player;
        this.leaveGoal = leaveGoal;
        this.table = table;
        this.goalCorner = layout.goalCorner(player);
        long lo = 0;
        long hi = 0;
        for (int c : Geometry.cornerCells(goalCorner)) {
            lo = withLo(lo, c);
            hi = withHi(hi, c);
        }
        this.goalLo = lo;
        this.goalHi = hi;
        for (int c = 0; c < Geometry.CELLS; c++) {
            allowed[c] = layout.allowed(player, c);
            rowOf[c] = Rows.row(player, c);
        }
    }

    public int player() {
        return player;
    }

    // ---- bitboards ----

    static boolean has(long lo, long hi, int cell) {
        return cell < 64 ? (lo >>> cell & 1L) != 0 : (hi >>> (cell - 64) & 1L) != 0;
    }

    static long withLo(long lo, int cell) {
        return cell < 64 ? lo | 1L << cell : lo;
    }

    static long withHi(long hi, int cell) {
        return cell >= 64 ? hi | 1L << (cell - 64) : hi;
    }

    static long withoutLo(long lo, int cell) {
        return cell < 64 ? lo & ~(1L << cell) : lo;
    }

    static long withoutHi(long hi, int cell) {
        return cell >= 64 ? hi & ~(1L << (cell - 64)) : hi;
    }

    static int[] cellsOf(long lo, long hi) {
        int[] cells = new int[Long.bitCount(lo) + Long.bitCount(hi)];
        int n = 0;
        for (long b = lo; b != 0; b &= b - 1) {
            cells[n++] = Long.numberOfTrailingZeros(b);
        }
        for (long b = hi; b != 0; b &= b - 1) {
            cells[n++] = 64 + Long.numberOfTrailingZeros(b);
        }
        return cells;
    }

    /** The smallest row among the pieces of a configuration (the rearmost piece). */
    public int rearRow(long lo, long hi) {
        int min = Integer.MAX_VALUE;
        for (long b = lo; b != 0; b &= b - 1) {
            min = Math.min(min, rowOf[Long.numberOfTrailingZeros(b)]);
        }
        for (long b = hi; b != 0; b &= b - 1) {
            min = Math.min(min, rowOf[64 + Long.numberOfTrailingZeros(b)]);
        }
        return min;
    }

    /** The smallest row among the given cells. */
    public int rearRow(int[] cells) {
        int min = Integer.MAX_VALUE;
        for (int c : cells) {
            min = Math.min(min, rowOf[c]);
        }
        return min;
    }

    /** The largest row among the given cells (the frontmost piece). */
    public int frontRow(int[] cells) {
        int max = Integer.MIN_VALUE;
        for (int c : cells) {
            max = Math.max(max, rowOf[c]);
        }
        return max;
    }

    public boolean isGoal(long lo, long hi) {
        return lo == goalLo && hi == goalHi;
    }

    /** Whether every cell is in the goal corner. */
    public boolean isGoal(int[] cells) {
        for (int c : cells) {
            if (Geometry.cornerOf(c) != goalCorner) {
                return false;
            }
        }
        return cells.length == Geometry.CORNER_SIZE;
    }

    // ---- move generation ----

    /** Receives the end cell of a generated move. */
    public interface LandingConsumer {
        void accept(int to);
    }

    /** Whether a move from {@code from} may end on {@code to} under the leaveGoal rule. */
    private boolean endAllowed(int from, int to) {
        return leaveGoal == LeaveGoal.ALLOWED || Geometry.cornerOf(from) != goalCorner
                || Geometry.cornerOf(to) == goalCorner;
    }

    /**
     * Enumerates the end cells of all moves of the piece on {@code from} whose landing cells (all of them) have rows
     * {@code >= minLanding}, given the occupancy of the player's pieces. Steps first, then jump chain landings in
     * breadth-first order. Afterwards {@link #path} reproduces the chain of the last generated landing set.
     */
    public void landings(long lo, long hi, int from, int minLanding, LandingConsumer out) {
        long occLo = withoutLo(lo, from);
        long occHi = withoutHi(hi, from);
        int[] nb = Geometry_NEIGHBOR[from];
        for (int d = 0; d < Geometry.DIRECTIONS; d++) {
            int n = nb[d];
            if (n >= 0 && allowed[n] && rowOf[n] >= minLanding && !has(occLo, occHi, n) && endAllowed(from, n)) {
                out.accept(n);
            }
        }
        Arrays.fill(parent, -1);
        parent[from] = from;
        int head = 0;
        int tail = 0;
        stack[tail++] = from;
        while (head < tail) {
            int c = stack[head++];
            int[] n2 = Geometry_NEIGHBOR[c];
            for (int d = 0; d < Geometry.DIRECTIONS; d++) {
                int over = n2[d];
                if (over < 0 || !has(occLo, occHi, over)) {
                    continue;
                }
                int land = Geometry_NEIGHBOR[over][d];
                if (land < 0 || has(occLo, occHi, land) || !allowed[land] || rowOf[land] < minLanding
                        || parent[land] >= 0) {
                    continue;
                }
                parent[land] = c;
                stack[tail++] = land;
                if (endAllowed(from, land)) {
                    out.accept(land);
                }
            }
        }
    }

    private static final int[][] Geometry_NEIGHBOR = new int[Geometry.CELLS][Geometry.DIRECTIONS];

    static {
        for (int c = 0; c < Geometry.CELLS; c++) {
            for (int d = 0; d < Geometry.DIRECTIONS; d++) {
                Geometry_NEIGHBOR[c][d] = Geometry.neighbor(c, d);
            }
        }
    }

    /**
     * The landing cells (in order, ending in {@code to}) of the move {@code from -> to} as the last call of
     * {@link #landings} found it; a step has the single cell {@code to}.
     */
    public int[] path(int from, int to) {
        if (parent[to] < 0 || to == from) {
            // a step, or a chain landing that is also adjacent: reproduce as the step if adjacent
            for (int d = 0; d < Geometry.DIRECTIONS; d++) {
                if (Geometry_NEIGHBOR[from][d] == to) {
                    return new int[] {to};
                }
            }
            throw new IllegalStateException("no path from " + from + " to " + to);
        }
        int n = 0;
        for (int c = to; c != from; c = parent[c]) {
            n++;
        }
        int[] path = new int[n];
        for (int c = to, i = n - 1; c != from; c = parent[c], i--) {
            path[i] = c;
        }
        return path;
    }

    /**
     * The move {@code from -> to} with its chain, or null if it is not a legal solitaire move with all landings at
     * rows {@code >= minLanding}. Steps are reported as steps even when a chain also reaches the cell.
     */
    public Step step(long lo, long hi, int from, int to, int minLanding) {
        boolean[] found = {false};
        landings(lo, hi, from, minLanding, c -> {
            if (c == to) {
                found[0] = true;
            }
        });
        return found[0] ? new Step(from, path(from, to)) : null;
    }

    // ---- lower bound ----

    /** Whether a piece on {@code from} can reach {@code to} in one move on some board: adjacent or same class. */
    static boolean oneMoveClass(int from, int to) {
        if (((Geometry.q(from) ^ Geometry.q(to)) & 1) == 0 && ((Geometry.r(from) ^ Geometry.r(to)) & 1) == 0) {
            return true;
        }
        for (int d = 0; d < Geometry.DIRECTIONS; d++) {
            if (Geometry_NEIGHBOR[from][d] == to) {
                return true;
            }
        }
        return false;
    }

    /**
     * The parity-assignment lower bound of the moves needed to bring all pieces into the goal corner, valid on
     * any board (admissible for the real game as long as the player uses only own pieces as hurdles, which is
     * irrelevant for the bound: it counts moves, not paths).
     */
    public int lowerBound(int[] cells) {
        int[] goal = Geometry.cornerCells(goalCorner);
        boolean[] cellTaken = new boolean[Geometry.CELLS];
        for (int c : cells) {
            cellTaken[c] = true;
        }
        int[] outside = new int[cells.length];
        int m = 0;
        for (int c : cells) {
            if (Geometry.cornerOf(c) != goalCorner) {
                outside[m++] = c;
            }
        }
        int[] free = new int[goal.length];
        int f = 0;
        for (int g : goal) {
            if (!cellTaken[g]) {
                free[f++] = g;
            }
        }
        // maximum bipartite matching (Kuhn) between outside pieces and free goal cells over one-move edges
        int[] matchOfFree = new int[f];
        Arrays.fill(matchOfFree, -1);
        int matched = 0;
        boolean[] seen = new boolean[f];
        for (int i = 0; i < m; i++) {
            Arrays.fill(seen, false);
            if (augment(i, outside, free, f, matchOfFree, seen)) {
                matched++;
            }
        }
        return 2 * m - matched;
    }

    private static boolean augment(int piece, int[] outside, int[] free, int f, int[] matchOfFree, boolean[] seen) {
        for (int j = 0; j < f; j++) {
            if (seen[j] || !oneMoveClass(outside[piece], free[j])) {
                continue;
            }
            seen[j] = true;
            if (matchOfFree[j] < 0 || augment(matchOfFree[j], outside, free, f, matchOfFree, seen)) {
                matchOfFree[j] = piece;
                return true;
            }
        }
        return false;
    }

    // ---- beam search ----

    /** Ordering key of a configuration for the beam: the table count where covered, else the distance sum. */
    private int heuristic(long lo, long hi) {
        int[] cells = cellsOf(lo, hi);
        if (table != null) {
            int count = table.count(Rows.toPlayer0(player, cells));
            if (count >= 0) {
                return count;
            }
        }
        int dsum = 0;
        for (int c : cells) {
            dsum += layout.distance(player, c);
        }
        // outside the table: configurations ordered by their distance sum, behind every covered configuration
        // (variants with the straggler, squared distances or jump counts found no shorter solitaire from the start)
        return 100 + dsum;
    }

    /**
     * A shortest plan the beam finds from {@code cells} to the goal, with at most {@code maxMoves} moves, or null.
     *
     * @param width      beam width (configurations kept per layer)
     * @param minLanding for move number t (1-based) the smallest allowed landing row of that move
     * @param minRear    for move number t the smallest allowed row of the rearmost piece after that move; not applied
     *                   to the goal configuration; {@code Integer.MIN_VALUE} for no constraint
     */
    public Plan plan(int[] cells, int width, int maxMoves, IntUnaryOperator minLanding, IntUnaryOperator minRear) {
        long lo = 0;
        long hi = 0;
        for (int c : cells) {
            lo = withLo(lo, c);
            hi = withHi(hi, c);
        }
        if (isGoal(lo, hi)) {
            return new Plan(List.of(), List.of(cells.clone()));
        }
        List<long[]> layerLo = new ArrayList<>();
        List<long[]> layerHi = new ArrayList<>();
        List<int[]> layerParent = new ArrayList<>();
        List<int[]> layerMove = new ArrayList<>();
        layerLo.add(new long[] {lo});
        layerHi.add(new long[] {hi});
        layerParent.add(new int[] {-1});
        layerMove.add(new int[] {-1});
        ConfigSet seen = new ConfigSet(width * 32);
        for (int t = 1; t <= maxMoves; t++) {
            long[] prevLo = layerLo.get(t - 1);
            long[] prevHi = layerHi.get(t - 1);
            int landing = minLanding.applyAsInt(t);
            int rear = minRear.applyAsInt(t);
            seen.clear();
            LongList candLo = new LongList();
            LongList candHi = new LongList();
            IntList candParent = new IntList();
            IntList candMove = new IntList();
            for (int s = 0; s < prevLo.length; s++) {
                long sLo = prevLo[s];
                long sHi = prevHi[s];
                int[] pieces = cellsOf(sLo, sHi);
                for (int from : pieces) {
                    long baseLo = withoutLo(sLo, from);
                    long baseHi = withoutHi(sHi, from);
                    int parentIndex = s;
                    landings(sLo, sHi, from, landing, to -> {
                        long nLo = withLo(baseLo, to);
                        long nHi = withHi(baseHi, to);
                        if (!seen.add(nLo, nHi)) {
                            return;
                        }
                        if (!isGoal(nLo, nHi) && rear != Integer.MIN_VALUE && rearRow(nLo, nHi) < rear) {
                            return;
                        }
                        candLo.add(nLo);
                        candHi.add(nHi);
                        candParent.add(parentIndex);
                        candMove.add(sternhalma.engine.Move.of(from, to));
                    });
                }
            }
            if (candLo.size == 0) {
                return null;
            }
            // the goal in this layer?
            for (int i = 0; i < candLo.size; i++) {
                if (isGoal(candLo.data[i], candHi.data[i])) {
                    layerLo.add(new long[] {candLo.data[i]});
                    layerHi.add(new long[] {candHi.data[i]});
                    layerParent.add(new int[] {candParent.data[i]});
                    layerMove.add(new int[] {candMove.data[i]});
                    return reconstruct(layerLo, layerHi, layerParent, layerMove, t, 0, minLanding);
                }
            }
            int keep = Math.min(width, candLo.size);
            long[] keys = new long[candLo.size];
            for (int i = 0; i < candLo.size; i++) {
                keys[i] = ((long) heuristic(candLo.data[i], candHi.data[i]) << 32) | i;
            }
            Arrays.sort(keys);
            long[] nLo = new long[keep];
            long[] nHi = new long[keep];
            int[] nParent = new int[keep];
            int[] nMove = new int[keep];
            for (int i = 0; i < keep; i++) {
                int idx = (int) keys[i];
                nLo[i] = candLo.data[idx];
                nHi[i] = candHi.data[idx];
                nParent[i] = candParent.data[idx];
                nMove[i] = candMove.data[idx];
            }
            layerLo.add(nLo);
            layerHi.add(nHi);
            layerParent.add(nParent);
            layerMove.add(nMove);
        }
        return null;
    }

    private Plan reconstruct(List<long[]> layerLo, List<long[]> layerHi, List<int[]> layerParent,
            List<int[]> layerMove, int depth, int index, IntUnaryOperator minLanding) {
        int[] moves = new int[depth];
        int[] indices = new int[depth + 1];
        int i = index;
        for (int t = depth; t >= 1; t--) {
            indices[t] = i;
            moves[t - 1] = layerMove.get(t)[i];
            i = layerParent.get(t)[i];
        }
        indices[0] = i;
        List<Step> steps = new ArrayList<>(depth);
        List<int[]> states = new ArrayList<>(depth + 1);
        states.add(cellsOf(layerLo.get(0)[indices[0]], layerHi.get(0)[indices[0]]));
        for (int t = 1; t <= depth; t++) {
            long lo = layerLo.get(t - 1)[indices[t - 1]];
            long hi = layerHi.get(t - 1)[indices[t - 1]];
            int from = sternhalma.engine.Move.from(moves[t - 1]);
            int to = sternhalma.engine.Move.to(moves[t - 1]);
            Step step = step(lo, hi, from, to, minLanding.applyAsInt(t));
            if (step == null) {
                throw new IllegalStateException("beam produced a move its generator cannot reproduce");
            }
            steps.add(step);
            states.add(cellsOf(layerLo.get(t)[indices[t]], layerHi.get(t)[indices[t]]));
        }
        for (int a = 0; a < states.size(); a++) {
            for (int b = a + 1; b < states.size(); b++) {
                if (Arrays.equals(states.get(a), states.get(b))) {
                    throw new IllegalStateException("plan states are not pairwise distinct");
                }
            }
        }
        return new Plan(List.copyOf(steps), List.copyOf(states));
    }

    /** A plan without rear constraints: landing rows {@code >= minLanding} for every move. */
    public Plan plan(int[] cells, int width, int maxMoves, int minLanding) {
        return plan(cells, width, maxMoves, t -> minLanding, t -> Integer.MIN_VALUE);
    }

    // ---- small growable arrays and an open-addressing set of configurations ----

    private static final class LongList {
        long[] data = new long[256];
        int size;

        void add(long v) {
            if (size == data.length) {
                data = Arrays.copyOf(data, size * 2);
            }
            data[size++] = v;
        }
    }

    private static final class IntList {
        int[] data = new int[256];
        int size;

        void add(int v) {
            if (size == data.length) {
                data = Arrays.copyOf(data, size * 2);
            }
            data[size++] = v;
        }
    }

    /** Open-addressing hash set of (lo, hi) pairs; 0/0 is never a configuration. */
    private static final class ConfigSet {
        private long[] lo;
        private long[] hi;
        private int mask;
        private int size;

        ConfigSet(int expected) {
            int cap = Integer.highestOneBit(Math.max(16, expected) * 2 - 1) << 1;
            lo = new long[cap];
            hi = new long[cap];
            mask = cap - 1;
        }

        void clear() {
            Arrays.fill(lo, 0);
            Arrays.fill(hi, 0);
            size = 0;
        }

        boolean add(long l, long h) {
            if (size * 2 >= lo.length) {
                grow();
            }
            int i = (int) ((l * 0x9E3779B97F4A7C15L ^ h * 0xC2B2AE3D27D4EB4FL) >>> 40) & mask;
            while (lo[i] != 0 || hi[i] != 0) {
                if (lo[i] == l && hi[i] == h) {
                    return false;
                }
                i = (i + 1) & mask;
            }
            lo[i] = l;
            hi[i] = h;
            size++;
            return true;
        }

        private void grow() {
            long[] oldLo = lo;
            long[] oldHi = hi;
            lo = new long[oldLo.length * 2];
            hi = new long[oldLo.length * 2];
            mask = lo.length - 1;
            size = 0;
            for (int i = 0; i < oldLo.length; i++) {
                if (oldLo[i] != 0 || oldHi[i] != 0) {
                    add(oldLo[i], oldHi[i]);
                }
            }
        }
    }
}
