package sternhalma.search;

import java.util.Map;
import java.util.Set;

import sternhalma.engine.Geometry;
import sternhalma.engine.Layout;
import sternhalma.engine.Position;

/**
 * Stage 4 of docs/search-and-evaluation.md for two players: the stage 2 distance difference plus correction
 * terms, each as a difference between the opponent and the side to move.
 *
 * <ul>
 *   <li>{@code ws} straggler: score units per step of the largest piece distance. The game ends only when
 *       the last piece arrives.</li>
 *   <li>{@code wq} spread: score units per 10 of the sum of squared piece distances, a smooth straggler
 *       term (a high-power mean of the distances).</li>
 *   <li>{@code wc} cohesion: score units per pair of adjacent own pieces, not counting pairs that both
 *       stand on goal cells.</li>
 *   <li>{@code wj} jump bridges: score units per forward single jump that a piece of the player could make
 *       now (over any piece, onto an empty cell with a smaller distance), ignoring whose turn it is.</li>
 * </ul>
 *
 * <p>All weights default to 0; with all weights 0 the evaluation equals {@link DistanceEvaluator}. The
 * weights are meant to be set by tournament tuning through the arena spec, for example
 * {@code ab:eval=stage4,ws=5,wc=2}.
 */
public final class Stage4Evaluator implements Evaluator {

    /** The weight keys this evaluator accepts. */
    public static final Set<String> KEYS = Set.of("ws", "wq", "wc", "wj");

    private final int ws;
    private final int wq;
    private final int wc;
    private final int wj;

    public Stage4Evaluator(int ws, int wq, int wc, int wj) {
        this.ws = ws;
        this.wq = wq;
        this.wc = wc;
        this.wj = wj;
    }

    /** From arena weights; missing keys are 0, unknown keys are an error. */
    public static Stage4Evaluator of(Map<String, Integer> weights) {
        for (String key : weights.keySet()) {
            if (!KEYS.contains(key)) {
                throw new IllegalArgumentException("unknown stage4 weight '" + key + "', known: " + KEYS);
            }
        }
        return new Stage4Evaluator(weights.getOrDefault("ws", 0), weights.getOrDefault("wq", 0),
                weights.getOrDefault("wc", 0), weights.getOrDefault("wj", 0));
    }

    /**
     * Forward single jumps per player and cell, precomputed for the two-player layout: for player p and
     * cell c, the entries {@code JUMP_START[p][c]} to {@code JUMP_START[p][c + 1] - 1} of {@code JUMP_OVER[p]}
     * and {@code JUMP_LAND[p]} are the bridge and landing cells of every jump from c onto an allowed cell
     * with a smaller distance.
     */
    private static final int[][] JUMP_START = new int[2][Geometry.CELLS + 1];
    private static final int[][] JUMP_OVER = new int[2][];
    private static final int[][] JUMP_LAND = new int[2][];
    /** Neighbours per cell with a larger id, for counting each adjacent pair once. */
    private static final int[][] UPPER_NEIGHBOURS = new int[Geometry.CELLS][];

    static {
        Layout layout = Layout.of(2);
        for (int p = 0; p < 2; p++) {
            int[] over = new int[Geometry.CELLS * Geometry.DIRECTIONS];
            int[] land = new int[Geometry.CELLS * Geometry.DIRECTIONS];
            int n = 0;
            for (int c = 0; c < Geometry.CELLS; c++) {
                JUMP_START[p][c] = n;
                if (!layout.allowed(p, c)) {
                    continue;
                }
                for (int dir = 0; dir < Geometry.DIRECTIONS; dir++) {
                    int bridge = Geometry.neighbor(c, dir);
                    int target = bridge < 0 ? -1 : Geometry.neighbor(bridge, dir);
                    if (target >= 0 && layout.allowed(p, target) && layout.distance(p, target) < layout.distance(p, c)) {
                        over[n] = bridge;
                        land[n] = target;
                        n++;
                    }
                }
            }
            JUMP_START[p][Geometry.CELLS] = n;
            JUMP_OVER[p] = java.util.Arrays.copyOf(over, n);
            JUMP_LAND[p] = java.util.Arrays.copyOf(land, n);
        }
        for (int c = 0; c < Geometry.CELLS; c++) {
            int[] upper = new int[Geometry.DIRECTIONS];
            int n = 0;
            for (int dir = 0; dir < Geometry.DIRECTIONS; dir++) {
                int nb = Geometry.neighbor(c, dir);
                if (nb > c) {
                    upper[n++] = nb;
                }
            }
            UPPER_NEIGHBOURS[c] = java.util.Arrays.copyOf(upper, n);
        }
    }

    @Override
    public int evaluate(Position position) {
        if (position.rules().triangleRows() != sternhalma.engine.RuleSet.TriangleRows.FOUR) {
            throw new UnsupportedOperationException("stage4 supports the 4-row board only");
        }
        if (position.rules().players() != 2) {
            throw new UnsupportedOperationException("stage4 supports two players only");
        }
        Layout layout = position.layout();
        int sum0 = 0, sum1 = 0, max0 = 0, max1 = 0, sq0 = 0, sq1 = 0;
        int pairs0 = 0, pairs1 = 0, jumps0 = 0, jumps1 = 0;
        for (int c = 0; c < Geometry.CELLS; c++) {
            int p = position.owner(c);
            if (p < 0) {
                continue;
            }
            int d = layout.distance(p, c);
            int pairs = 0;
            int jumps = 0;
            if (wc != 0) {
                for (int nb : UPPER_NEIGHBOURS[c]) {
                    if (position.owner(nb) == p && (d > 0 || layout.distance(p, nb) > 0)) {
                        pairs++;
                    }
                }
            }
            if (wj != 0) {
                int[] over = JUMP_OVER[p];
                int[] land = JUMP_LAND[p];
                for (int k = JUMP_START[p][c], end = JUMP_START[p][c + 1]; k < end; k++) {
                    if (position.owner(over[k]) >= 0 && position.owner(land[k]) < 0) {
                        jumps++;
                    }
                }
            }
            if (p == 0) {
                sum0 += d;
                sq0 += d * d;
                max0 = Math.max(max0, d);
                pairs0 += pairs;
                jumps0 += jumps;
            } else {
                sum1 += d;
                sq1 += d * d;
                max1 = Math.max(max1, d);
                pairs1 += pairs;
                jumps1 += jumps;
            }
        }
        int[] sum = {sum0, sum1};
        int[] max = {max0, max1};
        int[] squares = {sq0, sq1};
        int[] pairs = {pairs0, pairs1};
        int[] jumps = {jumps0, jumps1};
        int me = position.sideToMove();
        int op = 1 - me;
        int score = DistanceEvaluator.UNIT * (sum[op] - sum[me]);
        score += ws * (max[op] - max[me]);
        score += wq * (squares[op] - squares[me]) / 10;
        score += wc * (pairs[me] - pairs[op]);
        score += wj * (jumps[me] - jumps[op]);
        return score;
    }

    @Override
    public String toString() {
        return "stage4[ws=" + ws + ",wq=" + wq + ",wc=" + wc + ",wj=" + wj + "]";
    }
}
