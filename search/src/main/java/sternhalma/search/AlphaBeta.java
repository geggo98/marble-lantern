package sternhalma.search;

import java.util.Arrays;

import sternhalma.engine.Geometry;
import sternhalma.engine.Layout;
import sternhalma.engine.Move;
import sternhalma.engine.Outcome;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;

/**
 * Two-player negamax with alpha-beta pruning, iterative deepening, a transposition table and move ordering
 * by distance progress. This is the baseline of docs/search-and-evaluation.md.
 *
 * <p>Scores are integers from the side to move. A win at search height h scores {@link #MATE} - h. A game
 * that ends without a winner scores its outcome value times {@link #END_SCALE}.
 *
 * <p>The transposition table is keyed by the position hash only. A stored value can depend on the ply
 * through the move limit; the table ignores that, as the limit is far beyond the search depth in the
 * opening and middle game. By default mate scores are stored as they are, that is relative to the root of
 * the search that found them; {@link SearchOptions#mateFix()} stores them relative to the node instead.
 *
 * <p>{@link SearchOptions} switches on further enhancements (PVS, killers, history, LMR, aspiration windows,
 * a depth-preferred table, futility pruning). With {@link SearchOptions#DEFAULT} the search is the original
 * baseline.
 *
 * <p>Not thread-safe; one instance per thread.
 */
public final class AlphaBeta {

    public static final int MATE = 1_000_000;
    public static final int END_SCALE = 10_000;
    public static final int MAX_DEPTH = 64;

    /** Scores at least this far from zero are wins or losses in a known number of plies. */
    static final int MATE_BOUND = MATE - 1000;

    private static final int EXACT = 0;
    private static final int LOWER = 1;
    private static final int UPPER = 2;

    /** Moves searched at full depth before late move reductions start. */
    private static final int LMR_FULL_MOVES = 3;
    /** Minimum remaining depth for a late move reduction. */
    private static final int LMR_MIN_DEPTH = 3;
    private static final int HISTORY_MAX = (1 << 20) - 1;
    /** Beyond this half-width an aspiration window becomes the full window. */
    private static final int ASPIRATION_MAX = 2000;

    private final Evaluator evaluator;
    /** The incremental state of the evaluator (E13), or null if it has none; follows {@link #root} when tracking. */
    private final EvalState state;
    private boolean tracking;
    private final boolean useTable;
    private final SearchOptions options;

    // transposition table: parallel arrays, always-replace (default) or depth-preferred buckets of two
    private final long[] keys;
    private final int[] scores;
    private final int[] info; // depth (8 bits) | flag (2 bits) | move+1 (21 bits)
    private final short[] generations; // search number of the entry, only for depth-preferred
    private final int mask;
    private short generation;

    private final int[][] moveBuffers = new int[MAX_DEPTH + 1][Position.MAX_MOVES];
    private final int[][] orderKeys = new int[MAX_DEPTH + 1][Position.MAX_MOVES];
    private final int[][] killerMoves = new int[MAX_DEPTH + 2][2];
    private final int[] historyTable;
    /** Reductions by remaining depth and move index; shared, read-only, see {@link #LMR_TABLES}. */
    private final int[][] lmrTable;

    /**
     * Late move reductions per {@link SearchOptions#lmr()} mode, indexed [depth][move index]. Computed once
     * per JVM rather than per searcher (78k {@code Math.log} calls each), with the same formula as before.
     * Mode 0 (off) shares the mode-1 table, which is never read then.
     */
    private static final int[][][] LMR_TABLES = new int[4][][];

    static {
        for (int mode = 1; mode <= 3; mode++) {
            LMR_TABLES[mode] = lmrTable(mode);
        }
        LMR_TABLES[0] = LMR_TABLES[1];
    }

    static int[][] lmrTable(int mode) {
        int[][] table = new int[MAX_DEPTH + 1][Position.MAX_MOVES];
        for (int d = 1; d <= MAX_DEPTH; d++) {
            for (int i = 1; i < Position.MAX_MOVES; i++) {
                int r = switch (mode) {
                    case 2 -> (int) (0.5 + Math.log(d) * Math.log(i) / 2.0);
                    case 3 -> (int) (0.5 + Math.log(d) * Math.log(i) / 1.25);
                    default -> 1;
                };
                table[d][i] = Math.max(1, Math.min(r, d - 2));
            }
        }
        return table;
    }

    /** The reduction this searcher applies at remaining depth {@code depth} to the move at {@code index}. */
    int lmrReduction(int depth, int index) {
        return lmrTable[depth][index];
    }

    private long nodes;
    private long futilityCuts;
    private long nodeLimit;
    private long deadlineNanos;
    private boolean stopped;
    private boolean timed;
    private Position root;
    /**
     * Mixed into every table key; 0 in {@link #search}. {@link #rootScores} sets a new value per call, so that the
     * entries of earlier calls (other depths, other roots) are never found and the scores cannot depend on them.
     */
    private long keySalt;
    /** Only table entries of exactly the depth asked for count; set by {@link #rootScores}. */
    private boolean exactDepth;
    private long saltCounter;

    public AlphaBeta(Evaluator evaluator) {
        this(evaluator, 20, true);
    }

    /**
     * @param tableBits log2 of the number of transposition table entries
     * @param useTable  false disables the table (for tests that compare against plain search)
     */
    public AlphaBeta(Evaluator evaluator, int tableBits, boolean useTable) {
        this(evaluator, tableBits, useTable, SearchOptions.DEFAULT);
    }

    public AlphaBeta(Evaluator evaluator, int tableBits, boolean useTable, SearchOptions options) {
        this(evaluator, tableBits, useTable, options, false);
    }

    /**
     * @param verifyIncremental assert mode of the incremental evaluation (E13): every evaluation is compared with the
     *                          full one and the internal sums are checked after every move; slow, for tests
     */
    public AlphaBeta(Evaluator evaluator, int tableBits, boolean useTable, SearchOptions options,
            boolean verifyIncremental) {
        this.evaluator = evaluator;
        EvalState incremental = evaluator.incremental();
        this.state = incremental != null && verifyIncremental ? new VerifyingEvalState(evaluator, incremental)
                : incremental;
        this.useTable = useTable;
        this.options = options;
        int size = useTable ? 1 << tableBits : 1;
        if (options.depthPreferred() && size < 2) {
            size = 2; // one bucket of two entries
        }
        this.keys = new long[size];
        this.scores = new int[size];
        this.info = new int[size];
        this.generations = options.depthPreferred() ? new short[size] : null;
        this.mask = size - 1;
        this.historyTable = options.history() ? new int[Geometry.CELLS * Geometry.CELLS] : null;
        this.lmrTable = LMR_TABLES[options.lmr()];
        clearKillers();
    }

    public SearchOptions options() {
        return options;
    }

    /** Forgets all stored positions. */
    public void clear() {
        Arrays.fill(keys, 0L);
        Arrays.fill(info, 0);
        if (generations != null) {
            Arrays.fill(generations, (short) 0);
        }
        if (historyTable != null) {
            Arrays.fill(historyTable, 0);
        }
        clearKillers();
    }

    private void clearKillers() {
        for (int[] k : killerMoves) {
            k[0] = -1;
            k[1] = -1;
        }
    }

    /** Searches to a fixed depth with no time limit. */
    public SearchResult search(Position position, int depth) {
        return search(position, depth, Long.MAX_VALUE);
    }

    /** Iterative deepening with a time limit only; see {@link #search(Position, int, long, long)}. */
    public SearchResult search(Position position, int maxDepth, long timeLimitMillis) {
        return search(position, maxDepth, timeLimitMillis, Long.MAX_VALUE);
    }

    /**
     * Iterative deepening up to {@code maxDepth} plies, until the time limit passes or until {@code maxNodes}
     * nodes are visited, whichever comes first. An unfinished iteration is discarded, so the result always
     * comes from a completed depth (depth 1 always completes). A node limit makes a search independent of
     * machine load, which is what tournaments between evaluation variants need.
     */
    public SearchResult search(Position position, int maxDepth, long timeLimitMillis, long maxNodes) {
        if (position.rules().players() != 2) {
            throw new UnsupportedOperationException("alpha-beta supports two players only");
        }
        if (maxDepth < 1 || maxDepth > MAX_DEPTH) {
            throw new IllegalArgumentException("maxDepth must be in 1.." + MAX_DEPTH);
        }
        root = position.copy();
        tracking = state != null && state.reset(root);
        nodeLimit = maxNodes;
        nodes = 0;
        futilityCuts = 0;
        stopped = false;
        deadlineNanos = timeLimitMillis == Long.MAX_VALUE
                ? Long.MAX_VALUE : System.nanoTime() + timeLimitMillis * 1_000_000L;
        generation++;
        if (generation == 0) {
            generation = 1; // 0 marks an empty entry
        }
        clearKillers();
        if (historyTable != null) {
            for (int i = 0; i < historyTable.length; i++) {
                historyTable[i] >>= 2;
            }
        }
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
            // a previous score outside the ordinary band (a mate, or a race value of E08's racelin) gets the full
            // window: a narrow window around such a value fails and would only be widened until it is full anyway
            int[] iteration = options.aspiration() > 0 && depth > 1 && Scores.kind(bestScore) == Scores.Kind.ORDINARY
                    ? aspirationRoot(depth, bestMove, bestScore)
                    : searchRoot(depth, bestMove, -MATE - 1, MATE + 1);
            if (stopped && depth > 1) {
                complete = false;
                if (options.partial() && iteration[2] != 0) {
                    bestMove = iteration[0];
                    bestScore = iteration[1];
                }
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

    /** A root iteration in a window around the previous score, widened until the score falls inside. */
    private int[] aspirationRoot(int depth, int firstGuess, int previous) {
        int delta = options.aspiration();
        int alpha = Math.max(-MATE - 1, previous - delta);
        int beta = Math.min(MATE + 1, previous + delta);
        int guess = firstGuess;
        while (true) {
            int[] iteration = searchRoot(depth, guess, alpha, beta);
            if (stopped) {
                return iteration;
            }
            int score = iteration[1];
            if (score <= alpha && alpha > -MATE - 1) {
                delta *= 4;
                alpha = delta > ASPIRATION_MAX ? -MATE - 1 : Math.max(-MATE - 1, score - delta);
            } else if (score >= beta && beta < MATE + 1) {
                guess = iteration[0]; // a fail high has found a better move; search it first
                delta *= 4;
                beta = delta > ASPIRATION_MAX ? MATE + 1 : Math.min(MATE + 1, score + delta);
            } else {
                return iteration;
            }
        }
    }

    /**
     * One root iteration in the window (alpha, beta); returns {move, score, usable}. If the limit stops the
     * iteration, move and score come from the root moves searched completely, and usable is 1 if there was at
     * least one such move and its score is above alpha (so it is a real value or a fail high, not a bound
     * from a fail low).
     */
    private int[] searchRoot(int depth, int firstGuess, int alphaIn, int beta) {
        int[] moves = moveBuffers[0];
        int count = root.generate(moves);
        order(root, moves, count, 0, firstGuess);
        int[] keys = orderKeys[0];
        pick(moves, keys, 0, count);
        int alpha = alphaIn;
        int bestMove = moves[0];
        int bestScore = -MATE - 1;
        int completed = 0;
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                pick(moves, keys, i, count);
            }
            make(moves[i]);
            int score;
            if (options.pvs() && i > 0) {
                score = -negamax(depth - 1, -alpha - 1, -alpha, 1);
                if (score > alpha && score < beta && !stopped) {
                    score = -negamax(depth - 1, -beta, -alpha, 1);
                }
            } else {
                score = -negamax(depth - 1, -beta, -alpha, 1);
            }
            unmake();
            if (stopped) {
                break;
            }
            completed++;
            if (score > bestScore) {
                bestScore = score;
                bestMove = moves[i];
                if (score > alpha) {
                    alpha = score;
                }
            }
            if (alpha >= beta) {
                break; // only inside an aspiration window
            }
        }
        if (useTable && !stopped) {
            int flag = bestScore <= alphaIn ? UPPER : bestScore >= beta ? LOWER : EXACT;
            store(root.hash(), bestScore, depth, flag, bestMove, 0);
        }
        return new int[] {bestMove, bestScore, completed > 0 && bestScore > alphaIn ? 1 : 0};
    }

    private int negamax(int depth, int alphaIn, int betaIn, int height) {
        nodes++;
        if (timed && (nodes >= nodeLimit || ((nodes & 2047) == 0 && System.nanoTime() > deadlineNanos))) {
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
            return evaluate();
        }
        int alpha = alphaIn;
        int beta = betaIn;
        long key = root.hash() ^ keySalt;
        int ttMove = -1;
        if (useTable) {
            int slot = probe(key);
            if (slot >= 0) {
                int stored = info[slot];
                int storedDepth = stored >>> 24;
                int flag = (stored >>> 22) & 3;
                ttMove = (stored & 0x1FFFFF) - 1;
                if (exactDepth ? storedDepth == depth : storedDepth >= depth) {
                    int s = options.mateFix() ? fromTable(scores[slot], height) : scores[slot];
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
        int[] keys = orderKeys[height];
        pick(moves, keys, 0, count);
        int best = -MATE - 1;
        int bestMove = moves[0];
        int originalAlpha = alpha;
        boolean pvs = options.pvs();
        boolean lmr = options.lmr() > 0 && depth >= LMR_MIN_DEPTH;
        int[] killers = killerMoves[height];
        int margin = depth == 1 ? options.futility() : depth == 2 ? options.futility2() : 0;
        boolean futile = margin > 0 && futilityAllowed(root, depth, alpha, evaluator);
        int stand = 0;
        boolean standKnown = false;
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                pick(moves, keys, i, count);
            }
            int move = moves[i];
            if (futile && i > 0 && move != ttMove && move != killers[0] && move != killers[1]) {
                // the rest is ordered by distance gain, so once this move cannot reach alpha, none can
                int gain = historyTable == null ? keys[i] : keys[i] >> 20;
                if (!standKnown) {
                    stand = evaluate();
                    standKnown = true;
                }
                int bound = stand + options.futilityGain() * gain + margin;
                if (bound <= alpha) {
                    futilityCuts++;
                    if (bound > best) {
                        best = bound;
                    }
                    break;
                }
            }
            make(move);
            int score;
            int reduction = lmr && i >= LMR_FULL_MOVES && move != ttMove
                    && move != killers[0] && move != killers[1] ? lmrTable[depth][i] : 0;
            if (i == 0 || (!pvs && reduction == 0)) {
                score = -negamax(depth - 1, -beta, -alpha, height + 1);
            } else {
                score = -negamax(depth - 1 - reduction, -alpha - 1, -alpha, height + 1);
                if (reduction > 0 && score > alpha && !stopped) {
                    score = -negamax(depth - 1, pvs ? -alpha - 1 : -beta, -alpha, height + 1);
                }
                if (pvs && score > alpha && score < beta && !stopped) {
                    score = -negamax(depth - 1, -beta, -alpha, height + 1);
                }
            }
            unmake();
            if (stopped) {
                return 0;
            }
            if (score > best) {
                best = score;
                bestMove = move;
            }
            if (score > alpha) {
                alpha = score;
            }
            if (alpha >= beta) {
                if (options.killers() && move != killers[0]) {
                    killers[1] = killers[0];
                    killers[0] = move;
                }
                if (historyTable != null) {
                    int h = Move.from(move) * Geometry.CELLS + Move.to(move);
                    historyTable[h] = Math.min(HISTORY_MAX, historyTable[h] + depth * depth);
                }
                break;
            }
        }
        if (useTable) {
            int flag = best <= originalAlpha ? UPPER : best >= beta ? LOWER : EXACT;
            store(key, best, depth, flag, bestMove, height);
        }
        return best;
    }

    /**
     * Whether futility pruning may cut moves at this node (round-2 plan E06). It is off where the bound
     * "static value + futilityGain x distance gain + margin" can fail because a child within {@code depth}
     * plies is not scored by the evaluation:
     * <ul>
     *   <li>near a win: a goal holds {@code CORNER_SIZE - depth} or more pieces, for either player, so a gain-1
     *       goal entry that wins, or the only move that stops the opponent's win, is never cut (a move adds at
     *       most one piece to a goal, so below this count no game can end by a win within {@code depth} plies);</li>
     *   <li>near the move limit: the game would end within {@code depth} plies with a distance score at
     *       {@link #END_SCALE};</li>
     *   <li>with repetition draws: a child may end as a draw scored by distance;</li>
     *   <li>where the evaluator says so ({@link Evaluator#futilitySafe}: {@link RaceEvaluator} returns false where a
     *       race value can appear within {@code depth} plies);</li>
     *   <li>when alpha is not an ordinary score ({@link Scores#kind}): a mate, a race value or a move-limit outcome
     *       (which scale with {@link #END_SCALE}); the per-step bound says nothing about those.</li>
     * </ul>
     * Never called at the root, which {@link #searchRoot} searches without futility. The no-moves terminal is not
     * excluded: with 10 pieces a child without a legal move is practically unreachable (accepted gap). A table entry
     * from a deeper iteration can return any value for a child; that lies outside the bound's scope for ordinary and
     * race values alike.
     */
    static boolean futilityAllowed(Position pos, int depth, int alpha, Evaluator evaluator) {
        if (Scores.kind(alpha) != Scores.Kind.ORDINARY) {
            return false;
        }
        int nearWin = Geometry.CORNER_SIZE - depth;
        int me = pos.sideToMove();
        if (pos.goalOccupied(me) >= nearWin || pos.goalOccupied(1 - me) >= nearWin) {
            return false;
        }
        RuleSet rules = pos.rules();
        if (pos.ply() + depth >= rules.moveLimit() || rules.repetition() == RuleSet.Repetition.REPETITION_IS_DRAW) {
            return false;
        }
        return evaluator.futilitySafe(pos, depth);
    }

    /** Moves cut by futility pruning in the last search. */
    public long futilityCuts() {
        return futilityCuts;
    }

    /**
     * Searches one interior node (height 1, so never treated as the root) in the window (alpha, beta) with
     * {@code depth} plies left and no node limit; for tests of the pruning rules, which need a chosen window.
     */
    int searchNode(Position position, int depth, int alpha, int beta) {
        root = position.copy();
        tracking = state != null && state.reset(root);
        nodeLimit = Long.MAX_VALUE;
        deadlineNanos = Long.MAX_VALUE;
        nodes = 0;
        futilityCuts = 0;
        stopped = false;
        timed = false;
        return negamax(depth, alpha, beta, 1);
    }

    /**
     * The exact score of every root move at a fixed depth (round-2 plan E10, teacher samples). Entry {@code i} is the
     * score, from the side to move at the root, of the {@code i}-th move of {@link Position#legalMoves()}: the
     * negamax value of the child after {@code depth - 1} further plies, searched with the full window, so each score
     * is exact and not a bound as the later root moves of {@link #search} are. The searcher must have futility and
     * late move reductions off ({@link SearchOptions#exactRoot()}); otherwise this throws, because pruned subtrees
     * give scores that are not the depth-{@code depth} value. No aspiration window is used.
     *
     * <p>The table is used but cannot make a result depend on earlier calls: every call mixes a fresh salt into the
     * table keys, so entries of other calls are invisible, and only entries of exactly the depth asked for count
     * inside the call. The history table is cleared and the killers are reset, as for a fresh searcher. The scores
     * are in the bands of {@link Scores}; a consumer that makes labels from them asks {@link Scores#kind(int)}.
     * Without legal moves or at a finished game the result is empty.
     *
     * @param depth plies from the root, at least 1
     */
    public int[] rootScores(Position position, int depth) {
        if (position.rules().players() != 2) {
            throw new UnsupportedOperationException("alpha-beta supports two players only");
        }
        if (depth < 1 || depth > MAX_DEPTH) {
            throw new IllegalArgumentException("depth must be in 1.." + MAX_DEPTH);
        }
        if (!options.exactRoot()) {
            throw new IllegalStateException("rootScores needs futility and late move reductions off, got "
                    + options.describe());
        }
        root = position.copy();
        tracking = state != null && state.reset(root);
        nodeLimit = Long.MAX_VALUE;
        deadlineNanos = Long.MAX_VALUE;
        nodes = 0;
        futilityCuts = 0;
        stopped = false;
        timed = false;
        generation++;
        if (generation == 0) {
            generation = 1; // 0 marks an empty entry
        }
        clearKillers();
        if (historyTable != null) {
            Arrays.fill(historyTable, 0);
        }
        saltCounter++;
        keySalt = saltCounter * 0x9E3779B97F4A7C15L;
        exactDepth = true;
        try {
            int[] moves = new int[Position.MAX_MOVES];
            int count = root.outcome() == null ? root.generate(moves) : 0;
            int[] result = new int[count];
            for (int i = 0; i < count; i++) {
                make(moves[i]);
                result[i] = -negamax(depth - 1, -MATE - 1, MATE + 1, 1);
                unmake();
            }
            return result;
        } finally {
            keySalt = 0;
            exactDepth = false;
        }
    }

    /** Nodes visited by the last search or {@link #rootScores} call. */
    public long nodes() {
        return nodes;
    }

    /** Plays a move on the root position, through the incremental state when there is one. */
    private void make(int move) {
        if (tracking) {
            state.make(root, move);
        } else {
            root.make(move);
        }
    }

    private void unmake() {
        if (tracking) {
            state.unmake(root);
        } else {
            root.unmake();
        }
    }

    /** The static value of the current position of {@link #root}. */
    private int evaluate() {
        return tracking ? state.evaluate(root) : evaluator.evaluate(root);
    }

    /** The slot holding {@code key}, or -1. */
    private int probe(long key) {
        int slot = (int) (key ^ (key >>> 32)) & mask;
        if (!options.depthPreferred()) {
            return keys[slot] == key ? slot : -1;
        }
        int first = slot & ~1;
        if (keys[first] == key && generations[first] != 0) {
            return first;
        }
        int second = first | 1;
        return keys[second] == key && generations[second] != 0 ? second : -1;
    }

    private void store(long key, int score, int depth, int flag, int move, int height) {
        int slot = (int) (key ^ (key >>> 32)) & mask;
        if (options.depthPreferred()) {
            int first = slot & ~1;
            boolean replaceFirst = keys[first] == key
                    || generations[first] != generation
                    || depth >= (info[first] >>> 24);
            slot = replaceFirst ? first : first | 1;
            generations[slot] = generation;
        }
        keys[slot] = key;
        scores[slot] = options.mateFix() ? toTable(score, height) : score;
        info[slot] = (Math.min(depth, 255) << 24) | (flag << 22) | (move + 1);
    }

    /** A score at {@code height} as stored in the table: mate scores relative to the node. */
    static int toTable(int score, int height) {
        if (score >= MATE_BOUND) {
            return score + height;
        }
        if (score <= -MATE_BOUND) {
            return score - height;
        }
        return score;
    }

    /** The inverse of {@link #toTable(int, int)}. */
    static int fromTable(int score, int height) {
        if (score >= MATE_BOUND) {
            return score - height;
        }
        if (score <= -MATE_BOUND) {
            return score + height;
        }
        return score;
    }

    /**
     * Computes the ordering keys of the moves into {@code orderKeys[height]}, without sorting: the table
     * move first, then the killer moves (if on), then by the distance a move gains for the mover, with ties
     * broken by the history table (if on). The caller brings move i to the front with {@link #pick} just
     * before it searches move i, so a node that cuts off after a few moves never sorts the rest.
     */
    private void order(Position pos, int[] moves, int count, int height, int firstMove) {
        Layout layout = pos.layout();
        int player = pos.sideToMove();
        int[] gain = orderKeys[height];
        int killer0 = options.killers() ? killerMoves[height][0] : -1;
        int killer1 = options.killers() ? killerMoves[height][1] : -1;
        int[] hist = historyTable;
        for (int i = 0; i < count; i++) {
            int m = moves[i];
            if (m == firstMove) {
                gain[i] = Integer.MAX_VALUE;
            } else if (m == killer0) {
                gain[i] = Integer.MAX_VALUE - 1;
            } else if (m == killer1) {
                gain[i] = Integer.MAX_VALUE - 2;
            } else {
                int from = Move.from(m);
                int to = Move.to(m);
                int g = layout.distance(player, from) - layout.distance(player, to);
                gain[i] = hist == null ? g : (g << 20) + hist[from * Geometry.CELLS + to];
            }
        }
    }

    /**
     * Step {@code i} of a selection sort on {@code keys} (descending), applied to both arrays: swaps the
     * first entry with the largest key among positions i..count-1 into position i. The strict comparison
     * keeps the earliest of tied keys, so calling it for i = 0, 1, ... in turn yields exactly the order of
     * the full selection sort that the search used before (round 2, E01); any other sort orders ties
     * differently and changes the search tree.
     */
    static void pick(int[] moves, int[] keys, int i, int count) {
        int best = i;
        int bestKey = keys[i];
        for (int j = i + 1; j < count; j++) {
            if (keys[j] > bestKey) {
                best = j;
                bestKey = keys[j];
            }
        }
        if (best != i) {
            int t = moves[i];
            moves[i] = moves[best];
            moves[best] = t;
            keys[best] = keys[i];
            keys[i] = bestKey;
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
