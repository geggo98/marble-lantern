package sternhalma.search;

import java.util.Arrays;

import sternhalma.engine.Layout;
import sternhalma.engine.Move;
import sternhalma.engine.Outcome;
import sternhalma.engine.Position;

/**
 * Two-player negamax with alpha-beta pruning, iterative deepening, a transposition table and move ordering
 * by distance progress. This is the baseline of docs/search-and-evaluation.md.
 *
 * <p>Scores are integers from the side to move. A win at search height h scores {@link #MATE} - h. A game
 * that ends without a winner scores its outcome value times {@link #END_SCALE}.
 *
 * <p>The transposition table is keyed by the position hash only. A stored value can depend on the ply
 * through the move limit; the table ignores that, as the limit is far beyond the search depth in the
 * opening and middle game. Stored mate scores are relative to the height where they were found.
 *
 * <p>Not thread-safe; one instance per thread.
 */
public final class AlphaBeta {

    public static final int MATE = 1_000_000;
    public static final int END_SCALE = 10_000;
    public static final int MAX_DEPTH = 64;

    private static final int EXACT = 0;
    private static final int LOWER = 1;
    private static final int UPPER = 2;

    private final Evaluator evaluator;
    private final boolean useTable;

    // transposition table: parallel arrays, always-replace
    private final long[] keys;
    private final int[] scores;
    private final int[] info; // depth (8 bits) | flag (2 bits) | move+1 (21 bits)
    private final int mask;

    private final int[][] moveBuffers = new int[MAX_DEPTH + 1][Position.MAX_MOVES];
    private final int[][] orderKeys = new int[MAX_DEPTH + 1][Position.MAX_MOVES];

    private long nodes;
    private long deadlineNanos;
    private boolean stopped;
    private boolean timed;
    private Position root;

    public AlphaBeta(Evaluator evaluator) {
        this(evaluator, 20, true);
    }

    /**
     * @param tableBits log2 of the number of transposition table entries
     * @param useTable  false disables the table (for tests that compare against plain search)
     */
    public AlphaBeta(Evaluator evaluator, int tableBits, boolean useTable) {
        this.evaluator = evaluator;
        this.useTable = useTable;
        int size = useTable ? 1 << tableBits : 1;
        this.keys = new long[size];
        this.scores = new int[size];
        this.info = new int[size];
        this.mask = size - 1;
    }

    /** Forgets all stored positions. */
    public void clear() {
        Arrays.fill(keys, 0L);
        Arrays.fill(info, 0);
    }

    /** Searches to a fixed depth with no time limit. */
    public SearchResult search(Position position, int depth) {
        return search(position, depth, Long.MAX_VALUE);
    }

    /**
     * Iterative deepening up to {@code maxDepth} plies or until the time limit passes. An unfinished
     * iteration is discarded, so the result always comes from a completed depth (depth 1 always completes).
     */
    public SearchResult search(Position position, int maxDepth, long timeLimitMillis) {
        if (position.rules().players() != 2) {
            throw new UnsupportedOperationException("alpha-beta supports two players only");
        }
        if (maxDepth < 1 || maxDepth > MAX_DEPTH) {
            throw new IllegalArgumentException("maxDepth must be in 1.." + MAX_DEPTH);
        }
        root = position.copy();
        nodes = 0;
        stopped = false;
        deadlineNanos = timeLimitMillis == Long.MAX_VALUE
                ? Long.MAX_VALUE : System.nanoTime() + timeLimitMillis * 1_000_000L;
        int[] rootMoves = new int[Position.MAX_MOVES];
        int rootCount = root.generate(rootMoves);
        if (rootCount == 0 || root.outcome() != null) {
            return new SearchResult(-1, terminalScore(root, 0), 0, 0, true);
        }
        int bestMove = rootMoves[0];
        int bestScore = 0;
        int doneDepth = 0;
        boolean complete = true;
        for (int depth = 1; depth <= maxDepth; depth++) {
            timed = depth > 1; // depth 1 always completes, so there is always a move to return
            int[] iteration = searchRoot(depth, bestMove);
            if (stopped && depth > 1) {
                complete = false;
                break;
            }
            bestMove = iteration[0];
            bestScore = iteration[1];
            doneDepth = depth;
            if (Math.abs(bestScore) >= MATE - MAX_DEPTH) {
                break; // a forced win or loss is known; a deeper search cannot change who wins
            }
        }
        return new SearchResult(bestMove, bestScore, doneDepth, nodes, complete);
    }

    /** One root iteration; returns {move, score}. */
    private int[] searchRoot(int depth, int firstGuess) {
        int[] moves = moveBuffers[0];
        int count = root.generate(moves);
        order(root, moves, count, 0, firstGuess);
        int alpha = -MATE - 1;
        int beta = MATE + 1;
        int bestMove = moves[0];
        int bestScore = -MATE - 1;
        for (int i = 0; i < count; i++) {
            root.make(moves[i]);
            int score = -negamax(depth - 1, -beta, -alpha, 1);
            root.unmake();
            if (stopped) {
                break;
            }
            if (score > bestScore) {
                bestScore = score;
                bestMove = moves[i];
                if (score > alpha) {
                    alpha = score;
                }
            }
        }
        if (useTable && !stopped) {
            store(root.hash(), bestScore, depth, EXACT, bestMove);
        }
        return new int[] {bestMove, bestScore};
    }

    private int negamax(int depth, int alphaIn, int betaIn, int height) {
        nodes++;
        if (timed && (nodes & 2047) == 0 && System.nanoTime() > deadlineNanos) {
            stopped = true;
        }
        if (stopped) {
            return 0;
        }
        Outcome outcome = root.outcome();
        if (outcome != null) {
            return terminalScore(root, height);
        }
        if (depth == 0) {
            return evaluator.evaluate(root);
        }
        int alpha = alphaIn;
        int beta = betaIn;
        long key = root.hash();
        int ttMove = -1;
        if (useTable) {
            int slot = (int) (key ^ (key >>> 32)) & mask;
            if (keys[slot] == key) {
                int stored = info[slot];
                int storedDepth = stored >>> 24;
                int flag = (stored >>> 22) & 3;
                ttMove = (stored & 0x1FFFFF) - 1;
                if (storedDepth >= depth) {
                    int s = scores[slot];
                    if (flag == EXACT) {
                        return s;
                    }
                    if (flag == LOWER && s > alpha) {
                        alpha = s;
                    } else if (flag == UPPER && s < beta) {
                        beta = s;
                    }
                    if (alpha >= beta) {
                        return s;
                    }
                }
            }
        }
        int[] moves = moveBuffers[height];
        int count = root.generate(moves);
        if (count == 0) {
            return terminalScoreNoMoves(root);
        }
        order(root, moves, count, height, ttMove);
        int best = -MATE - 1;
        int bestMove = moves[0];
        int originalAlpha = alpha;
        for (int i = 0; i < count; i++) {
            root.make(moves[i]);
            int score = -negamax(depth - 1, -beta, -alpha, height + 1);
            root.unmake();
            if (stopped) {
                return 0;
            }
            if (score > best) {
                best = score;
                bestMove = moves[i];
            }
            if (score > alpha) {
                alpha = score;
            }
            if (alpha >= beta) {
                break;
            }
        }
        if (useTable) {
            int flag = best <= originalAlpha ? UPPER : best >= beta ? LOWER : EXACT;
            store(key, best, depth, flag, bestMove);
        }
        return best;
    }

    private void store(long key, int score, int depth, int flag, int move) {
        int slot = (int) (key ^ (key >>> 32)) & mask;
        keys[slot] = key;
        scores[slot] = score;
        info[slot] = (Math.min(depth, 255) << 24) | (flag << 22) | (move + 1);
    }

    /** Orders moves best-first: the table move, then by the distance a move gains for the mover. */
    private void order(Position pos, int[] moves, int count, int height, int firstMove) {
        Layout layout = pos.layout();
        int player = pos.sideToMove();
        int[] gain = orderKeys[height];
        for (int i = 0; i < count; i++) {
            int m = moves[i];
            gain[i] = m == firstMove
                    ? Integer.MAX_VALUE
                    : layout.distance(player, Move.from(m)) - layout.distance(player, Move.to(m));
        }
        for (int i = 0; i < count - 1; i++) { // selection sort: the front is searched first, the rest rarely
            int best = i;
            for (int j = i + 1; j < count; j++) {
                if (gain[j] > gain[best]) {
                    best = j;
                }
            }
            if (best != i) {
                int t = moves[i];
                moves[i] = moves[best];
                moves[best] = t;
                t = gain[i];
                gain[i] = gain[best];
                gain[best] = t;
            }
        }
    }

    static int terminalScore(Position pos, int height) {
        Outcome outcome = pos.outcome();
        if (outcome == null) {
            return terminalScoreNoMoves(pos);
        }
        int me = pos.sideToMove();
        if (outcome.hasWinner()) {
            return outcome.winner() == me ? MATE - height : -(MATE - height);
        }
        return (int) Math.round(outcome.value(me) * END_SCALE);
    }

    private static int terminalScoreNoMoves(Position pos) {
        return (int) Math.round(pos.noMovesOutcome().value(pos.sideToMove()) * END_SCALE);
    }
}
