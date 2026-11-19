package sternhalma.search;

import java.util.Arrays;

import sternhalma.engine.Geometry;
import sternhalma.engine.Move;
import sternhalma.engine.Position;

/**
 * Paranoid alpha-beta for three players (docs/search-and-evaluation.md, "Three-player browser player"). The player
 * at the root maximises its own score, both other players minimise it; that turns the three-player game into a
 * zero-sum game between the root player and a coalition, so alpha-beta applies.
 *
 * <p>Score of a position for player {@code r}: the mean over the two opponents {@code q} of the linear evaluation of
 * {@code r} against {@code q} (the 12 features of {@link LinearEvaluator} as "r minus q", without the tempo term), in
 * {@link LinearWeights#SCALE} units. A win of {@code r} at search height h scores {@link AlphaBeta#MATE} - h, a win
 * of an opponent the negative. The race table is not used, so the search works for both jump rules.
 *
 * <p>Iterative deepening, transposition table (keyed by position hash and root player, because the same position
 * has other scores for another root player), killer moves and a gain-first move order (the mover's distance gain,
 * jump chains first). Nodes are counted as in {@link AlphaBeta}: one per call, leaves included; an unfinished
 * iteration is discarded, the first iteration is always finished. Deterministic for equal inputs (without a time
 * limit).
 *
 * <p>With {@code ranking=FULL_RANKING} a finished player makes no more moves (the turn order skips it) and the search
 * goes on until one active player is left. The root player then scores: first place {@code MATE - h}, last place
 * {@code -(MATE - h)}, second place {@link #SECOND} minus h. While one other player has finished, only one opponent
 * is active: the search is a two-player zero-sum search of the root player against that opponent, and the
 * evaluation compares the root player with that opponent alone, minus {@link #NOT_FIRST} because first place is gone.
 */
final class ThreePlayerSearch {

    static final int MAX_DEPTH = 40;
    private static final long[] ROOT_KEY = {0x9E3779B97F4A7C15L, 0xC2B2AE3D27D4EB4FL, 0x165667B19E3779F9L};
    private static final int EXACT = 0;
    private static final int LOWER = 1; // score >= stored
    private static final int UPPER = 2; // score <= stored
    private static final int MATE = AlphaBeta.MATE;
    private static final int MATE_BOUND = MATE - 1000;
    /** Score of second place (the game is over, the root player finished second), before the height is subtracted. */
    static final int SECOND = MATE / 2;
    private static final int SECOND_BOUND = SECOND - 1000;
    /** Penalty of a position in which another player has already finished first, in {@link LinearWeights#SCALE} units. */
    static final int NOT_FIRST = 4000;

    private final int tableMask;
    private final long[] keys;
    private final int[] scores;
    private final int[] meta; // depth << 2 | bound
    private final int[] bestMoves;
    private final int[][] killers = new int[MAX_DEPTH + 2][2];
    private final int[][] moveBuffers = new int[MAX_DEPTH + 2][];
    private final int[][] orderBuffers = new int[MAX_DEPTH + 2][];
    private final int[][] sums = new int[3][LinearEvaluator.SIDE_SUMS];
    private final double[] features = new double[LinearEvaluator.FEATURES];
    private final LinearEvaluator linear;
    /** Per player and cell: the distance from the centre line of the player's own start-goal axis, see {@link #evaluate}. */
    private static final int[][] LATERAL = new int[3][Geometry.CELLS];

    static {
        for (int c = 0; c < Geometry.CELLS; c++) {
            int q = Geometry.q(c);
            int r = Geometry.r(c);
            int s = Geometry.s(c);
            LATERAL[0][c] = Math.abs(q - s); // player 0: axis r
            LATERAL[1][c] = Math.abs(q - r); // player 1: axis s
            LATERAL[2][c] = Math.abs(r - s); // player 2: axis q
        }
    }

    private Position pos;
    private int root;
    private int[][] dist;
    private boolean playOn;
    private long nodes;
    private long nodeLimit;
    private long deadlineNanos;
    private boolean limited;
    private boolean aborted;

    ThreePlayerSearch(double[] weights, int tableBits) {
        this.linear = new LinearEvaluator(weights, LinearWeights.SCALE);
        int size = 1 << tableBits;
        tableMask = size - 1;
        keys = new long[size];
        scores = new int[size];
        meta = new int[size];
        bestMoves = new int[size];
    }

    /**
     * @param maxDepth    deepest iteration
     * @param timeLimitMs time per move, {@code Long.MAX_VALUE} for none
     * @param maxNodes    node limit, {@code Long.MAX_VALUE} for none
     */
    SearchResult search(Position position, int maxDepth, long timeLimitMs, long maxNodes) {
        if (position.rules().players() != 3) {
            throw new IllegalArgumentException("three-player search needs a three-player position");
        }
        pos = position;
        root = position.sideToMove();
        playOn = position.rules().playsOnAfterWin();
        dist = LinearEvaluator.DIST[1];
        nodes = 0;
        nodeLimit = maxNodes;
        aborted = false;
        deadlineNanos = timeLimitMs == Long.MAX_VALUE ? Long.MAX_VALUE : System.nanoTime() + timeLimitMs * 1_000_000L;
        for (int[] k : killers) {
            Arrays.fill(k, 0);
        }
        int[] moves = new int[Position.MAX_MOVES];
        int n = position.generate(moves);
        if (n == 0) {
            return new SearchResult(-1, evaluate(root), 0, 0, true);
        }
        int[] ordered = Arrays.copyOf(moves, n);
        int bestMove = ordered[0];
        int bestScore = 0;
        int doneDepth = 0;
        boolean complete = true;
        int top = Math.min(maxDepth, MAX_DEPTH);
        for (int depth = 1; depth <= top; depth++) {
            limited = depth > 1; // the first iteration always finishes, so there is always a move
            int[] result = searchRoot(ordered, depth, bestMove);
            if (aborted) {
                complete = false;
                break;
            }
            bestMove = result[0];
            bestScore = result[1];
            doneDepth = depth;
            if (Math.abs(bestScore) >= MATE_BOUND) {
                break; // decided
            }
        }
        return new SearchResult(bestMove, bestScore, doneDepth, nodes, complete);
    }

    private int[] searchRoot(int[] ordered, int depth, int previousBest) {
        int n = ordered.length;
        int[] orderKeys = new int[n];
        for (int i = 0; i < n; i++) {
            orderKeys[i] = ordered[i] == previousBest ? Integer.MAX_VALUE : gain(root, ordered[i]);
        }
        sortDescending(ordered, orderKeys, n); // stable: equal keys keep the generator's order
        int alpha = -MATE - 1;
        int best = ordered[0];
        int bestScore = -MATE - 1;
        for (int i = 0; i < n; i++) {
            int move = ordered[i];
            pos.make(move);
            int score = after(root, depth - 1, alpha, MATE + 1, 1);
            pos.unmake();
            if (aborted) {
                return null;
            }
            if (score > bestScore) {
                bestScore = score;
                best = move;
                alpha = Math.max(alpha, score);
            }
        }
        return new int[] {best, bestScore};
    }

    /** The value of the position after the player {@code mover} made a move (not yet unmade). */
    private int after(int mover, int depth, int alpha, int beta, int height) {
        if (playOn) {
            int finished = pos.finishedMask();
            if (finished != 0) {
                if (pos.isFinished(root)) {
                    nodes++;
                    return pos.finishers()[0] == root ? MATE - height : SECOND - height;
                }
                if (Integer.bitCount(finished) >= 2) {
                    nodes++;
                    return -(MATE - height); // the root player is the last one
                }
            }
            return ab(depth, alpha, beta, height);
        }
        if (pos.hasWon(mover)) {
            nodes++;
            return mover == root ? MATE - height : -(MATE - height);
        }
        return ab(depth, alpha, beta, height);
    }

    private boolean stop() {
        return limited && (nodes >= nodeLimit || ((nodes & 1023) == 0 && System.nanoTime() > deadlineNanos));
    }

    private int ab(int depth, int alpha, int beta, int height) {
        nodes++;
        if (stop()) {
            aborted = true;
            return 0;
        }
        if (depth <= 0 || pos.ply() >= pos.rules().moveLimit() || height >= MAX_DEPTH) {
            return evaluate(root);
        }
        int mover = pos.sideToMove();
        boolean maximising = mover == root;
        long key = pos.hash() ^ ROOT_KEY[root];
        int slot = (int) (key ^ (key >>> 29)) & tableMask;
        int ttMove = -1;
        if (keys[slot] == key) {
            ttMove = bestMoves[slot];
            if ((meta[slot] >> 2) >= depth) {
                int bound = meta[slot] & 3;
                int s = fromTable(scores[slot], height);
                if (bound == EXACT || (bound == LOWER && s >= beta) || (bound == UPPER && s <= alpha)) {
                    return s;
                }
            }
        }
        int[] moves = moveBuffers[height];
        if (moves == null) {
            moves = moveBuffers[height] = new int[Position.MAX_MOVES];
            orderBuffers[height] = new int[Position.MAX_MOVES];
        }
        int[] order = orderBuffers[height];
        int n = pos.generate(moves);
        if (n == 0) {
            return evaluate(root);
        }
        for (int i = 0; i < n; i++) {
            int m = moves[i];
            order[i] = m == ttMove ? Integer.MAX_VALUE
                    : m == killers[height][0] ? 1_000_000
                    : m == killers[height][1] ? 999_000
                    : gain(mover, m);
        }
        int originalAlpha = alpha;
        int originalBeta = beta;
        int best = maximising ? -MATE - 1 : MATE + 1;
        int bestMove = moves[0];
        for (int i = 0; i < n; i++) {
            int pick = i; // selection: the best remaining move is tried next, so a cutoff skips the sorting
            for (int j = i + 1; j < n; j++) {
                if (order[j] > order[pick]) {
                    pick = j;
                }
            }
            int move = moves[pick];
            moves[pick] = moves[i];
            order[pick] = order[i];
            moves[i] = move;
            pos.make(move);
            int score = after(mover, depth - 1, alpha, beta, height + 1);
            pos.unmake();
            if (aborted) {
                return 0;
            }
            if (maximising) {
                if (score > best) {
                    best = score;
                    bestMove = move;
                    alpha = Math.max(alpha, score);
                }
            } else if (score < best) {
                best = score;
                bestMove = move;
                beta = Math.min(beta, score);
            }
            if (alpha >= beta) {
                int[] k = killers[height];
                if (k[0] != move) {
                    k[1] = k[0];
                    k[0] = move;
                }
                break;
            }
        }
        int bound = best <= originalAlpha ? UPPER : best >= originalBeta ? LOWER : EXACT;
        keys[slot] = key;
        scores[slot] = toTable(best, height);
        meta[slot] = depth << 2 | bound;
        bestMoves[slot] = bestMove;
        return best;
    }

    private static int toTable(int score, int height) {
        return score >= MATE_BOUND || (score >= SECOND_BOUND && score <= SECOND) ? score + height
                : score <= -MATE_BOUND ? score - height : score;
    }

    private static int fromTable(int score, int height) {
        return score >= MATE_BOUND || (score >= SECOND_BOUND && score <= SECOND) ? score - height
                : score <= -MATE_BOUND ? score + height : score;
    }

    /** The distance the mover gains with the move; jump chains gain several steps at once. */
    private int gain(int player, int move) {
        return dist[player][Move.from(move)] - dist[player][Move.to(move)];
    }

    /** Sets the position that {@link #evaluate} looks at, without a search (tests). */
    void setPositionForEvaluation(Position position) {
        pos = position;
        playOn = position.rules().playsOnAfterWin();
        dist = LinearEvaluator.DIST[1];
    }

    /**
     * The score of the current position for player {@code r}: the mean evaluation of r against the active opponents
     * (both, unless a player has finished; then the one that is left, minus {@link #NOT_FIRST}).
     */
    int evaluate(int r) {
        for (int p = 0; p < 3; p++) {
            if (pos.isFinished(p)) {
                continue;
            }
            LinearEvaluator.side(pos, 1, p, sums[p]);
            int lateral = 0;
            for (int i = 0, n = pos.pieceCount(p); i < n; i++) {
                lateral += LATERAL[p][pos.pieceCell(p, i)];
            }
            sums[p][LinearEvaluator.LAT] = lateral;
        }
        int total = 0;
        int opponents = 0;
        for (int q = 0; q < 3; q++) {
            if (q != r && !pos.isFinished(q)) {
                opponents++;
                LinearEvaluator.combine(sums[r], sums[q], features);
                features[LinearEvaluator.TEMPO] = 0.0; // no side-to-move bonus: the score is the root player's
                total += linear.score(features, 0.0);
            }
        }
        return opponents == 2 ? total / 2 : total - (playOn ? NOT_FIRST : 0);
    }

    private static void sortDescending(int[] moves, int[] orderKeys, int n) {
        for (int i = 1; i < n; i++) {
            int m = moves[i];
            int k = orderKeys[i];
            int j = i - 1;
            while (j >= 0 && orderKeys[j] < k) {
                moves[j + 1] = moves[j];
                orderKeys[j + 1] = orderKeys[j];
                j--;
            }
            moves[j + 1] = m;
            orderKeys[j + 1] = k;
        }
    }
}
