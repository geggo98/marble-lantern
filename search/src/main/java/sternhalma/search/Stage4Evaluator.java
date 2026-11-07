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

    @Override
    public int evaluate(Position position) {
        Layout layout = position.layout();
        int[] sum = new int[2];
        int[] max = new int[2];
        int[] squares = new int[2];
        int[] pairs = new int[2];
        int[] jumps = new int[2];
        boolean needNeighbours = wc != 0 || wj != 0;
        for (int c = 0; c < Geometry.CELLS; c++) {
            int p = position.owner(c);
            if (p < 0) {
                continue;
            }
            int d = layout.distance(p, c);
            sum[p] += d;
            squares[p] += d * d;
            if (d > max[p]) {
                max[p] = d;
            }
            if (!needNeighbours) {
                continue;
            }
            for (int dir = 0; dir < Geometry.DIRECTIONS; dir++) {
                int n = Geometry.neighbor(c, dir);
                if (n < 0) {
                    continue;
                }
                int o = position.owner(n);
                if (o < 0) {
                    continue;
                }
                // each adjacent pair once: count it from the smaller cell id
                if (o == p && n > c && (d > 0 || layout.distance(p, n) > 0)) {
                    pairs[p]++;
                }
                int land = Geometry.neighbor(n, dir);
                if (land >= 0 && position.owner(land) < 0 && layout.allowed(p, land)
                        && layout.distance(p, land) < d) {
                    jumps[p]++;
                }
            }
        }
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
