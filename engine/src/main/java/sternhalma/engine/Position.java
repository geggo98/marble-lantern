package sternhalma.engine;

import java.util.Arrays;

import sternhalma.engine.RuleSet.BlockadeRule;
import sternhalma.engine.RuleSet.DrawHandling;
import sternhalma.engine.RuleSet.LeaveGoal;
import sternhalma.engine.RuleSet.Repetition;
import sternhalma.engine.RuleSet.SuicideMoves;

/**
 * A mutable game position with make/unmake, legal move generation and end-of-game detection.
 *
 * <p>The implementation follows docs/rules.md. Not thread-safe.
 */
public final class Position {

    /** Pieces per player on the 4-row board ({@link RuleSet.TriangleRows#FOUR}); see {@link Layout#pieces()}. */
    public static final int PIECES = Geometry.CORNER_SIZE;
    /** Pieces per player on the 5-row board ({@link RuleSet.TriangleRows#FIVE}). */
    public static final int MAX_PIECES = RuleSet.TriangleRows.FIVE.pieces();
    /** Upper bound on legal moves: 15 pieces (the larger house) times 121 end cells. */
    public static final int MAX_MOVES = MAX_PIECES * Geometry.CELLS;

    private static final long[][] PIECE_KEY = new long[3][Geometry.CELLS];
    private static final long[] SIDE_KEY = new long[3];
    /** Zobrist keys of the finished players; drawn after all other keys, so the older hashes stay as they were. */
    private static final long[] FINISHED_KEY = new long[3];

    static {
        SplitMix64 random = new SplitMix64(0x5E7A_11A1_0000_0001L);
        for (int p = 0; p < 3; p++) {
            SIDE_KEY[p] = random.nextLong();
            for (int c = 0; c < Geometry.CELLS; c++) {
                PIECE_KEY[p][c] = random.nextLong();
            }
        }
        for (int p = 0; p < 3; p++) {
            FINISHED_KEY[p] = random.nextLong();
        }
    }

    private final RuleSet rules;
    private final Layout layout;
    /** Owner of each cell, or -1 if empty. */
    private final byte[] owner = new byte[Geometry.CELLS];
    /** Cells of goal(p) that hold any piece. */
    private final int[] goalOccupied;
    /** Cells of goal(p) that hold a piece of p. */
    private final int[] goalOwn;
    /**
     * The cells of each player's pieces, ascending; kept by {@link #put}, {@link #make} and {@link #unmake}.
     * A row has room for {@link Layout#pieces()} entries and grows only if a setup places more.
     */
    private final int[][] pieceCells;
    private final int[] pieceCount;
    /** For an occupied cell, its index in its owner's row of {@link #pieceCells}; undefined for empty cells. */
    private final int[] slot = new int[Geometry.CELLS];
    /** Per player the layout's step and jump tables with the allowed cells folded in (shared, read-only). */
    private final int[][] stepTo;
    private final int[][] jumpTo;
    /** Whether the rules use the Halma long hop ({@link RuleSet.JumpRule#LONG}). */
    private final boolean longJump;
    /**
     * Whether a jump chain may land on a cell of a foreign corner ({@link RuleSet.ForeignCamps} other than CLOSED);
     * then {@link #jumpTo} holds the plain landing table of the board.
     */
    private final boolean passForeign;
    /** Whether a move may end on a cell of a foreign corner ({@link RuleSet.ForeignCamps#OPEN}). */
    private final boolean endForeign;
    /** Whether a player who fills the goal leaves the game and the others play on ({@link RuleSet#playsOnAfterWin}). */
    private final boolean playOn;
    private int sideToMove;
    private int ply;
    private long hash;
    private int[] moves = new int[65];
    private long[] hashes = new long[65];
    /** Per ply: the player who made the move, and the set of finished players before it. */
    private int[] movers = new int[65];
    private int[] masksBefore = new int[65];
    /** Bit p is set if player p has finished (filled the goal) and makes no more moves. */
    private int finishedMask;
    /** The finished players by rank, first place first; {@code finishedCount} entries are valid. */
    private final int[] finishers = new int[3];
    private int finishedCount;

    // scratch space for move generation
    private final int[] stack = new int[Geometry.CELLS];

    private Position(RuleSet rules) {
        this.rules = rules;
        this.layout = Layout.of(rules.players(), rules.triangleRows());
        this.longJump = rules.jumpRule() == RuleSet.JumpRule.LONG;
        this.passForeign = rules.foreignCamps() != RuleSet.ForeignCamps.CLOSED;
        this.endForeign = rules.foreignCamps() == RuleSet.ForeignCamps.OPEN;
        this.playOn = rules.playsOnAfterWin();
        this.goalOccupied = new int[rules.players()];
        this.goalOwn = new int[rules.players()];
        this.pieceCells = new int[rules.players()][layout.pieces()];
        this.pieceCount = new int[rules.players()];
        this.stepTo = new int[rules.players()][];
        this.jumpTo = new int[rules.players()][];
        for (int p = 0; p < rules.players(); p++) {
            stepTo[p] = endForeign ? Geometry.STEP : layout.stepTable(p);
            jumpTo[p] = passForeign ? Geometry.LAND : layout.jumpTable(p);
        }
        Arrays.fill(owner, (byte) -1);
        this.hash = SIDE_KEY[0];
    }

    /** An empty board with player 0 to move. Use {@link #put} to set up a position. */
    public static Position empty(RuleSet rules) {
        return new Position(rules);
    }

    /** The start position: every player fills the own start corner, player 0 moves first. */
    public static Position initial(RuleSet rules) {
        Position position = new Position(rules);
        for (int p = 0; p < rules.players(); p++) {
            for (int cell : position.layout.startCells(p)) {
                position.put(p, cell);
            }
        }
        return position;
    }

    public Position copy() {
        Position c = new Position(rules);
        System.arraycopy(owner, 0, c.owner, 0, owner.length);
        System.arraycopy(goalOccupied, 0, c.goalOccupied, 0, goalOccupied.length);
        System.arraycopy(goalOwn, 0, c.goalOwn, 0, goalOwn.length);
        for (int p = 0; p < pieceCells.length; p++) {
            c.pieceCells[p] = pieceCells[p].clone();
        }
        System.arraycopy(pieceCount, 0, c.pieceCount, 0, pieceCount.length);
        System.arraycopy(slot, 0, c.slot, 0, slot.length);
        c.sideToMove = sideToMove;
        c.ply = ply;
        c.hash = hash;
        c.moves = moves.clone();
        c.hashes = hashes.clone();
        c.movers = movers.clone();
        c.masksBefore = masksBefore.clone();
        c.finishedMask = finishedMask;
        System.arraycopy(finishers, 0, c.finishers, 0, finishers.length);
        c.finishedCount = finishedCount;
        return c;
    }

    // ---- setup (before the first move only) ----

    /**
     * Places a piece. For setup before the first {@link #make}.
     *
     * @throws IllegalArgumentException if the player or cell does not exist, or no move of the player may end on the
     *                                  cell (a foreign corner unless {@link RuleSet.ForeignCamps#OPEN})
     * @throws IllegalStateException    if the cell is occupied or a move has been played
     */
    public Position put(int player, int cell) {
        requireSetup();
        requirePlayer(player);
        if (cell < 0 || cell >= Geometry.CELLS) {
            throw new IllegalArgumentException("no such cell: " + cell);
        }
        if (!endForeign && !layout.allowed(player, cell)) {
            throw new IllegalArgumentException("player " + player + " may not enter cell " + cell);
        }
        if (owner[cell] >= 0) {
            throw new IllegalStateException("cell " + cell + " is occupied");
        }
        owner[cell] = (byte) player;
        hash ^= PIECE_KEY[player][cell];
        updateGoalCounts(player, cell, +1);
        insertPiece(player, cell);
        return this;
    }

    /** Inserts a cell into the sorted piece list of a player (setup only). */
    private void insertPiece(int player, int cell) {
        int n = pieceCount[player];
        if (n == pieceCells[player].length) {
            pieceCells[player] = Arrays.copyOf(pieceCells[player], n + layout.pieces());
        }
        int[] cells = pieceCells[player];
        int i = n;
        while (i > 0 && cells[i - 1] > cell) {
            cells[i] = cells[i - 1];
            slot[cells[i]] = i;
            i--;
        }
        cells[i] = cell;
        slot[cell] = i;
        pieceCount[player] = n + 1;
    }

    /** Replaces {@code from} by {@code to} in the sorted piece list of a player, keeping it sorted. */
    private void movePiece(int player, int from, int to) {
        int[] cells = pieceCells[player];
        int i = slot[from];
        if (to > from) {
            int last = pieceCount[player] - 1;
            while (i < last && cells[i + 1] < to) {
                int c = cells[i + 1];
                cells[i] = c;
                slot[c] = i;
                i++;
            }
        } else {
            while (i > 0 && cells[i - 1] > to) {
                int c = cells[i - 1];
                cells[i] = c;
                slot[c] = i;
                i--;
            }
        }
        cells[i] = to;
        slot[to] = i;
    }

    /** Places a piece at axial coordinates. */
    public Position put(int player, int q, int r) {
        int cell = Geometry.id(q, r);
        if (cell < 0) {
            throw new IllegalArgumentException("(" + q + "," + r + ") is off the board");
        }
        return put(player, cell);
    }

    /**
     * Declares a player finished with the next free rank, as if the player had filled the goal. For setup before
     * the first {@link #make}, with {@code ranking=FULL_RANKING} and three players only. If the player is to move,
     * the turn passes to the next player who has not finished.
     */
    public Position finish(int player) {
        requireSetup();
        requirePlayer(player);
        if (!playOn) {
            throw new IllegalStateException("finished players need ranking=FULL_RANKING with three players");
        }
        if (isFinished(player) || finishedCount >= rules.players() - 1) {
            throw new IllegalStateException("player " + player + " cannot finish now");
        }
        markFinished(player);
        if (sideToMove == player) {
            int next = nextActive(player);
            hash ^= SIDE_KEY[player] ^ SIDE_KEY[next];
            sideToMove = next;
        }
        return this;
    }

    private void markFinished(int player) {
        finishedMask |= 1 << player;
        finishers[finishedCount++] = player;
        hash ^= FINISHED_KEY[player];
    }

    /** The next player after {@code p}, in turn order, who has not finished; {@code p} if there is none. */
    private int nextActive(int p) {
        int players = rules.players();
        for (int i = 1; i <= players; i++) {
            int q = (p + i) % players;
            if ((finishedMask & (1 << q)) == 0) {
                return q;
            }
        }
        return p;
    }

    public Position setSideToMove(int player) {
        requireSetup();
        requirePlayer(player);
        if (isFinished(player)) {
            throw new IllegalArgumentException("player " + player + " has finished and cannot be to move");
        }
        hash ^= SIDE_KEY[sideToMove] ^ SIDE_KEY[player];
        sideToMove = player;
        return this;
    }

    private void requireSetup() {
        if (ply > 0) {
            throw new IllegalStateException("setup is only allowed before the first move (ply " + ply + ")");
        }
    }

    private void requirePlayer(int player) {
        if (player < 0 || player >= rules.players()) {
            throw new IllegalArgumentException("player " + player + " outside 0.." + (rules.players() - 1));
        }
    }

    // ---- accessors ----

    public RuleSet rules() {
        return rules;
    }

    public Layout layout() {
        return layout;
    }

    public int sideToMove() {
        return sideToMove;
    }

    /** Whether the player has finished: filled the goal with {@code ranking=FULL_RANKING}, three players. */
    public boolean isFinished(int player) {
        return (finishedMask & (1 << player)) != 0;
    }

    /** The finished players as a bit set (bit p for player p); 0 unless {@code ranking=FULL_RANKING}. */
    public int finishedMask() {
        return finishedMask;
    }

    /** The finished players by rank, first place first, as a new array. */
    public int[] finishers() {
        return Arrays.copyOf(finishers, finishedCount);
    }

    /** Number of plies played since the position was set up. */
    public int ply() {
        return ply;
    }

    /** Zobrist hash of the pieces, the side to move and the set of finished players. */
    public long hash() {
        return hash;
    }

    /** Owner of a cell, or -1 if it is empty. */
    public int owner(int cell) {
        return owner[cell];
    }

    /** The cells of a player's pieces in ascending order, as a new array. */
    public int[] pieces(int player) {
        int n = pieceCount[player];
        if (n > layout.pieces()) {
            throw new IllegalStateException("player " + player + " has more than " + layout.pieces() + " pieces");
        }
        return Arrays.copyOf(pieceCells[player], n);
    }

    /** Number of pieces of a player on the board. */
    public int pieceCount(int player) {
        return pieceCount[player];
    }

    /**
     * The cell of a player's piece number {@code index}, counted in ascending cell order
     * ({@code 0 <= index < pieceCount(player)}). Allocation-free access to {@link #pieces}.
     */
    public int pieceCell(int player, int index) {
        return pieceCells[player][index];
    }

    /** Number of cells of the player's goal corner that hold a piece of any player. */
    public int goalOccupied(int player) {
        return goalOccupied[player];
    }

    /** Sum of the single-agent distances of all pieces of a player. */
    public int distanceSum(int player) {
        int[] cells = pieceCells[player];
        int sum = 0;
        for (int i = 0, n = pieceCount[player]; i < n; i++) {
            sum += layout.distance(player, cells[i]);
        }
        return sum;
    }

    // ---- make / unmake ----

    /** Plays a move. The move must come from {@link #generate}. */
    public void make(int move) {
        int from = Move.from(move);
        int to = Move.to(move);
        int p = sideToMove;
        assert owner[from] == p && owner[to] < 0 : "illegal move " + Move.toString(move);
        if (ply + 1 >= hashes.length) {
            hashes = Arrays.copyOf(hashes, hashes.length * 2);
            moves = Arrays.copyOf(moves, moves.length * 2);
            movers = Arrays.copyOf(movers, movers.length * 2);
            masksBefore = Arrays.copyOf(masksBefore, masksBefore.length * 2);
        }
        hashes[ply] = hash;
        moves[ply] = move;
        movers[ply] = p;
        masksBefore[ply] = finishedMask;
        owner[from] = -1;
        owner[to] = (byte) p;
        hash ^= PIECE_KEY[p][from] ^ PIECE_KEY[p][to];
        updateGoalCounts(p, from, -1);
        updateGoalCounts(p, to, +1);
        movePiece(p, from, to);
        if (playOn) {
            recordFinishers(p);
        }
        int next = nextActive(p);
        hash ^= SIDE_KEY[p] ^ SIDE_KEY[next];
        sideToMove = next;
        ply++;
        hashes[ply] = hash;
    }

    /** Takes back the last move. */
    public void unmake() {
        if (ply == 0) {
            throw new IllegalStateException("no move to take back");
        }
        ply--;
        int move = moves[ply];
        int from = Move.from(move);
        int to = Move.to(move);
        int p = movers[ply];
        finishedMask = masksBefore[ply];
        finishedCount = Integer.bitCount(finishedMask);
        owner[to] = -1;
        owner[from] = (byte) p;
        updateGoalCounts(p, to, -1);
        updateGoalCounts(p, from, +1);
        movePiece(p, to, from);
        sideToMove = p;
        hash = hashes[ply];
    }

    /**
     * After a move of {@code p}: every active player whose goal is now filled finishes with the next free rank, in
     * turn order starting after {@code p} (as {@link #outcome} checks a win without ranking). The last active player
     * is not marked: it takes the last rank when the game is over.
     */
    private void recordFinishers(int p) {
        int players = rules.players();
        for (int i = 1; i <= players; i++) {
            int q = (p + i) % players;
            if (finishedCount < players - 1 && !isFinished(q) && hasWon(q)) {
                markFinished(q);
            }
        }
    }

    private void updateGoalCounts(int player, int cell, int delta) {
        int q = layout.goalPlayerOfCell(cell);
        if (q >= 0) {
            goalOccupied[q] += delta;
            if (q == player) {
                goalOwn[q] += delta;
            }
        }
    }

    // ---- legal moves ----

    /** Legal moves as a new array. */
    public int[] legalMoves() {
        int[] buffer = new int[MAX_MOVES];
        return Arrays.copyOf(buffer, generate(buffer));
    }

    /**
     * Writes the legal moves of the side to move into {@code out} and returns their number.
     * The buffer needs room for {@link #MAX_MOVES} entries. Each (start, end) pair appears once.
     *
     * <p>Order: pieces by ascending cell; per piece the steps by direction, then the jump landings in the
     * order of the depth-first search in {@link #addJumps}. The search tree depends on this order.
     */
    public int generate(int[] out) {
        int p = sideToMove;
        int[] steps = stepTo[p];
        int[] jumps = jumpTo[p];
        boolean[] goalMask = layout.goalMask(p);
        boolean leaveForbidden = rules.leaveGoal() == LeaveGoal.FORBIDDEN;
        // Definition 2 can only forbid a move when an opponent's goal already holds all but one of its cells: a move
        // adds at most one piece to another goal (a pure pre-check: a lower threshold changes no result). Below that, handsOpponentTheWin is false for every move.
        boolean suicideCheck = rules.suicideMoves() == SuicideMoves.ILLEGAL && opponentGoalNearlyFull(p);
        int[] cells = pieceCells[p];
        int pieces = pieceCount[p];
        int count = 0;
        for (int i = 0; i < pieces; i++) {
            int from = cells[i];
            boolean stayInGoal = leaveForbidden && goalMask[from];
            owner[from] = -1; // the moving piece leaves its cell, so the cell counts as empty during jumps
            int base = from * Geometry.DIRECTIONS;
            for (int d = 0; d < Geometry.DIRECTIONS; d++) {
                int n = steps[base + d];
                if (n >= 0 && owner[n] < 0
                        && (!stayInGoal || goalMask[n])
                        && (!suicideCheck || !handsOpponentTheWin(p, from, n))) {
                    out[count++] = Move.of(from, n);
                }
            }
            boolean[] required = stayInGoal ? goalMask : null;
            // PASS_THROUGH: the chain may land on a foreign corner, but a move must not end there
            boolean homeEnd = passForeign && !endForeign;
            count = longJump
                    ? addLongJumps(p, from, out, count, required, suicideCheck, homeEnd)
                    : addJumps(p, from, out, count, jumps, required, suicideCheck, homeEnd);
            owner[from] = (byte) p;
        }
        return count;
    }

    /** Whether some opponent's goal holds at least {@code goalSize - 1} pieces. */
    private boolean opponentGoalNearlyFull(int p) {
        for (int q = 0; q < goalOccupied.length; q++) {
            if (q != p && !isFinished(q) && goalOccupied[q] >= layout.goalSize() - 1) {
                return true;
            }
        }
        return false;
    }

    /**
     * Depth-first search over jump landings; the visited set deduplicates (start, end) pairs. It is a 128-bit
     * set in two longs (cells 0 to 120), kept in registers; a shift by {@code cell} uses {@code cell mod 64}.
     * {@code required} is the goal-house membership of the player if the piece may not leave the goal, else null.
     * {@code homeEnd}: a landing is a move only if it is a home cell of {@code p} ({@link Layout#allowed}); the
     * search still continues from every landing ({@link RuleSet.ForeignCamps#PASS_THROUGH}).
     */
    private int addJumps(int p, int from, int[] out, int countIn, int[] jumps, boolean[] required,
            boolean suicideCheck, boolean homeEnd) {
        int count = countIn;
        long low = 0;
        long high = 0;
        if (from < 64) {
            low = 1L << from;
        } else {
            high = 1L << from;
        }
        int top = 0;
        stack[top++] = from;
        while (top > 0) {
            int c = stack[--top];
            int base = c * Geometry.DIRECTIONS;
            for (int d = 0; d < Geometry.DIRECTIONS; d++) {
                int land = jumps[base + d]; // -1 if off the board or not allowed; then the old code skipped it too
                if (land < 0 || owner[Geometry.STEP[base + d]] < 0 || owner[land] >= 0) {
                    continue;
                }
                long bit = 1L << land;
                if (land < 64) {
                    if ((low & bit) != 0) {
                        continue;
                    }
                    low |= bit;
                } else {
                    if ((high & bit) != 0) {
                        continue;
                    }
                    high |= bit;
                }
                stack[top++] = land;
                if ((required == null || required[land])
                        && (!homeEnd || layout.allowed(p, land))
                        && (!suicideCheck || !handsOpponentTheWin(p, from, land))) {
                    out[count++] = Move.of(from, land);
                }
            }
        }
        return count;
    }

    /**
     * The long-hop variant of {@link #addJumps} ({@link RuleSet.JumpRule#LONG}): the same depth-first search, visited
     * set and order, with another landing rule. From cell c in direction d the piece walks over empty cells until the
     * first piece P, k steps away (k &gt;= 1); the landing is the cell k steps behind P, the mirror of c across P. The
     * k - 1 cells between c and P and the k - 1 cells between P and the landing must be empty, the landing too, and
     * the player must be allowed to stand on it. For k = 1 this is exactly the single jump of {@link #addJumps}.
     * There is at most one landing per direction.
     */
    private int addLongJumps(int p, int from, int[] out, int countIn, boolean[] required, boolean suicideCheck,
            boolean homeEnd) {
        int count = countIn;
        long low = 0;
        long high = 0;
        if (from < 64) {
            low = 1L << from;
        } else {
            high = 1L << from;
        }
        int top = 0;
        stack[top++] = from;
        while (top > 0) {
            int c = stack[--top];
            for (int d = 0; d < Geometry.DIRECTIONS; d++) {
                int land = longLanding(p, c, d);
                if (land < 0) {
                    continue;
                }
                long bit = 1L << land;
                if (land < 64) {
                    if ((low & bit) != 0) {
                        continue;
                    }
                    low |= bit;
                } else {
                    if ((high & bit) != 0) {
                        continue;
                    }
                    high |= bit;
                }
                stack[top++] = land;
                if ((required == null || required[land])
                        && (!homeEnd || layout.allowed(p, land))
                        && (!suicideCheck || !handsOpponentTheWin(p, from, land))) {
                    out[count++] = Move.of(from, land);
                }
            }
        }
        return count;
    }

    /** The landing cell of the long hop from {@code c} in direction {@code d}, or -1 if there is none. */
    private int longLanding(int p, int c, int d) {
        int over = c;
        int k = 0;
        do {
            over = Geometry.STEP[over * Geometry.DIRECTIONS + d];
            k++;
            if (over < 0) {
                return -1;
            }
        } while (owner[over] < 0);
        int land = over;
        for (int i = 0; i < k; i++) {
            land = Geometry.STEP[land * Geometry.DIRECTIONS + d];
            if (land < 0 || owner[land] >= 0) {
                return -1;
            }
        }
        return passForeign || layout.allowed(p, land) ? land : -1;
    }

    /** Whether the move would meet the winning condition of another player (Definition 2). */
    private boolean handsOpponentTheWin(int p, int from, int to) {
        int fromGoal = layout.goalPlayerOfCell(from);
        int toGoal = layout.goalPlayerOfCell(to);
        for (int q = 0; q < rules.players(); q++) {
            if (q == p || isFinished(q)) {
                continue;
            }
            int occupied = goalOccupied[q] - (fromGoal == q ? 1 : 0) + (toGoal == q ? 1 : 0);
            if (isWin(occupied, goalOwn[q])) {
                return true;
            }
        }
        return false;
    }

    // ---- end of game ----

    /** Definition 1 (or the plain rule with {@code BlockadeRule.NONE}): goal full, with an own piece. */
    private boolean isWin(int occupied, int own) {
        int size = layout.goalSize();
        if (occupied != size) {
            return false;
        }
        return rules.blockadeRule() == BlockadeRule.OPPONENT_FILLS ? own >= 1 : own == size;
    }

    /** Whether the player meets the winning condition in this position. */
    public boolean hasWon(int player) {
        return isWin(goalOccupied[player], goalOwn[player]);
    }

    /**
     * The outcome if the game is over, else {@code null}. The checks run in this order: a win, a repeated
     * position (only with {@code REPETITION_IS_DRAW}), the move limit. A win on the last allowed ply counts.
     * If a move meets the winning condition of both players (only with {@code SuicideMoves.LEGAL}), the
     * player who did not move wins.
     */
    public Outcome outcome() {
        if (playOn) {
            return playOnOutcome(false);
        }
        int players = rules.players();
        for (int i = 0; i < players; i++) {
            int q = (sideToMove + i) % players; // starts with the player who did not just move
            if (hasWon(q)) {
                return Outcome.win(q, players);
            }
        }
        if (rules.repetition() == Repetition.REPETITION_IS_DRAW && isRepeated()) {
            return Outcome.noWinner(Outcome.Reason.REPETITION, drawValues());
        }
        if (ply >= rules.moveLimit()) {
            return Outcome.noWinner(Outcome.Reason.MOVE_LIMIT, drawValues());
        }
        return null;
    }

    /** The outcome to use when the side to move has no legal move. */
    public Outcome noMovesOutcome() {
        if (playOn) {
            return playOnOutcome(true);
        }
        return Outcome.noWinner(Outcome.Reason.NO_MOVES, drawValues());
    }

    /**
     * {@link #outcome} (or {@link #noMovesOutcome} with {@code noMoves}) for {@code ranking=FULL_RANKING} with three
     * players. The game is over when one active player is left, which takes the last rank; then the reason is
     * {@code WIN}. Otherwise the checks of {@link #outcome} apply, and the finished players keep their ranks.
     */
    private Outcome playOnOutcome(boolean noMoves) {
        int players = rules.players();
        if (finishedCount >= players - 1) {
            int[] ranking = new int[players];
            System.arraycopy(finishers, 0, ranking, 0, finishedCount);
            ranking[finishedCount] = nextActive(finishers[finishedCount - 1]);
            return Outcome.ranked(Outcome.Reason.WIN, ranking, rankValues(ranking, players));
        }
        Outcome.Reason reason;
        if (noMoves) {
            reason = Outcome.Reason.NO_MOVES;
        } else if (rules.repetition() == Repetition.REPETITION_IS_DRAW && isRepeated()) {
            reason = Outcome.Reason.REPETITION;
        } else if (ply >= rules.moveLimit()) {
            reason = Outcome.Reason.MOVE_LIMIT;
        } else {
            return null;
        }
        return unfinishedOutcome(reason);
    }

    /** The value of rank {@code r} (0 = first): 1 for the first, -1 for the last, evenly spaced. */
    private static double rankValue(int r, int players) {
        return (double) (players - 1 - 2 * r) / (players - 1);
    }

    private static double[] rankValues(int[] ranking, int players) {
        double[] values = new double[players];
        for (int r = 0; r < players; r++) {
            values[ranking[r]] = rankValue(r, players);
        }
        return values;
    }

    /**
     * The end without a complete ranking: the finished players keep their ranks and values; the others share the
     * remaining rank values, evenly with {@code DRAW}, or by the distance score of {@link #drawValues} with
     * {@code SCORE_BY_DISTANCE}. Their order is by distance sum, the smaller sum first, ties by player number.
     */
    private Outcome unfinishedOutcome(Outcome.Reason reason) {
        int players = rules.players();
        int open = players - finishedCount;
        int[] rest = new int[open];
        int[] sums = new int[players];
        int n = 0;
        for (int q = 0; q < players; q++) {
            sums[q] = distanceSum(q);
            if (!isFinished(q)) {
                int i = n++;
                while (i > 0 && sums[rest[i - 1]] > sums[q]) {
                    rest[i] = rest[i - 1];
                    i--;
                }
                rest[i] = q;
            }
        }
        int[] ranking = new int[players];
        System.arraycopy(finishers, 0, ranking, 0, finishedCount);
        System.arraycopy(rest, 0, ranking, finishedCount, open);
        double[] values = new double[players];
        double slots = 0;
        for (int r = 0; r < players; r++) {
            if (r < finishedCount) {
                values[ranking[r]] = rankValue(r, players);
            } else {
                slots += rankValue(r, players);
            }
        }
        double share = slots / open;
        double mean = 0;
        int maxDistance = 0;
        for (int q : rest) {
            mean += sums[q];
        }
        for (int q = 0; q < players; q++) {
            maxDistance = Math.max(maxDistance, layout.maxDistance(q));
        }
        mean /= open;
        double scale = (double) layout.pieces() * maxDistance;
        for (int q : rest) {
            values[q] = rules.drawHandling() == DrawHandling.DRAW ? share : share + (mean - sums[q]) / scale;
        }
        return Outcome.ranked(reason, ranking, values);
    }

    private boolean isRepeated() {
        for (int k = 0; k < ply; k++) {
            if (hashes[k] == hash) {
                return true;
            }
        }
        return false;
    }

    /** Values for a game without a winner, as set by {@code drawHandling}. */
    private double[] drawValues() {
        int players = rules.players();
        double[] values = new double[players];
        if (rules.drawHandling() == DrawHandling.DRAW) {
            return values;
        }
        double mean = 0;
        int[] sums = new int[players];
        int maxDistance = 0;
        for (int p = 0; p < players; p++) {
            sums[p] = distanceSum(p);
            mean += sums[p];
            maxDistance = Math.max(maxDistance, layout.maxDistance(p));
        }
        mean /= players;
        double scale = (double) layout.pieces() * maxDistance; // no player can be farther than this from the goal
        for (int p = 0; p < players; p++) {
            values[p] = (mean - sums[p]) / scale;
        }
        return values;
    }

    // ---- symmetry and display ----

    /**
     * The position under a cell map; with {@code swapPlayers} players 0 and 1 and the side to move are
     * swapped (two players only). The result has no move history.
     *
     * @throws IllegalArgumentException if the image holds a piece on a cell its owner may not enter
     */
    public Position transformed(java.util.function.IntUnaryOperator cellMap, boolean swapPlayers) {
        if (swapPlayers && rules.players() != 2) {
            throw new IllegalArgumentException("swapping players is defined for two players only");
        }
        Position result = new Position(rules);
        for (int c = 0; c < Geometry.CELLS; c++) {
            if (owner[c] >= 0) {
                int player = swapPlayers ? 1 - owner[c] : owner[c];
                result.put(player, cellMap.applyAsInt(c));
            }
        }
        for (int i = 0; i < finishedCount; i++) {
            result.finish(finishers[i]);
        }
        result.setSideToMove(swapPlayers ? 1 - sideToMove : sideToMove);
        return result;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Position other
                && sideToMove == other.sideToMove
                && rules.players() == other.rules.players()
                && rules.jumpRule() == other.rules.jumpRule()
                && rules.foreignCamps() == other.rules.foreignCamps()
                && rules.triangleRows() == other.rules.triangleRows()
                && rules.playsOnAfterWin() == other.rules.playsOnAfterWin()
                && finishedMask == other.finishedMask
                && Arrays.equals(owner, other.owner);
    }

    @Override
    public int hashCode() {
        return Long.hashCode(hash);
    }

    /** A text picture of the board: '.' empty, the digit of the player otherwise. */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int r = -8; r <= 8; r++) {
            char[] row = new char[50];
            Arrays.fill(row, ' ');
            for (int q = -8; q <= 8; q++) {
                int cell = Geometry.id(q, r);
                if (cell >= 0) {
                    row[2 * q + r + 24] = owner[cell] < 0 ? '.' : (char) ('0' + owner[cell]);
                }
            }
            sb.append(new String(row).stripTrailing()).append('\n');
        }
        sb.append("side to move: ").append(sideToMove).append(", ply ").append(ply);
        if (finishedCount > 0) {
            sb.append(", finished ").append(Arrays.toString(finishers()));
        }
        return sb.toString();
    }
}
