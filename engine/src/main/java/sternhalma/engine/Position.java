package sternhalma.engine;

import java.util.Arrays;
import java.util.SplittableRandom;

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

    /** Pieces per player. */
    public static final int PIECES = Geometry.CORNER_SIZE;
    /** Upper bound on legal moves: 10 pieces times 121 end cells. */
    public static final int MAX_MOVES = PIECES * Geometry.CELLS;

    private static final long[][] PIECE_KEY = new long[3][Geometry.CELLS];
    private static final long[] SIDE_KEY = new long[3];

    static {
        SplittableRandom random = new SplittableRandom(0x5E7A_11A1_0000_0001L);
        for (int p = 0; p < 3; p++) {
            SIDE_KEY[p] = random.nextLong();
            for (int c = 0; c < Geometry.CELLS; c++) {
                PIECE_KEY[p][c] = random.nextLong();
            }
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
    private int sideToMove;
    private int ply;
    private long hash;
    private int[] moves = new int[65];
    private long[] hashes = new long[65];

    // scratch space for move generation
    private final int[] visited = new int[Geometry.CELLS];
    private int stamp;
    private final int[] stack = new int[Geometry.CELLS];

    private Position(RuleSet rules) {
        this.rules = rules;
        this.layout = Layout.of(rules.players());
        this.goalOccupied = new int[rules.players()];
        this.goalOwn = new int[rules.players()];
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
            for (int cell : Geometry.cornerCells(position.layout.startCorner(p))) {
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
        c.sideToMove = sideToMove;
        c.ply = ply;
        c.hash = hash;
        c.moves = moves.clone();
        c.hashes = hashes.clone();
        return c;
    }

    // ---- setup (before the first move only) ----

    /** Places a piece. For setup before the first {@link #make}. */
    public Position put(int player, int cell) {
        if (owner[cell] >= 0) {
            throw new IllegalStateException("cell " + cell + " is occupied");
        }
        owner[cell] = (byte) player;
        hash ^= PIECE_KEY[player][cell];
        updateGoalCounts(player, cell, +1);
        return this;
    }

    /** Places a piece at axial coordinates. */
    public Position put(int player, int q, int r) {
        int cell = Geometry.id(q, r);
        if (cell < 0) {
            throw new IllegalArgumentException("(" + q + "," + r + ") is off the board");
        }
        return put(player, cell);
    }

    public Position setSideToMove(int player) {
        hash ^= SIDE_KEY[sideToMove] ^ SIDE_KEY[player];
        sideToMove = player;
        return this;
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

    /** Number of plies played since the position was set up. */
    public int ply() {
        return ply;
    }

    /** Zobrist hash of the pieces and the side to move. */
    public long hash() {
        return hash;
    }

    /** Owner of a cell, or -1 if it is empty. */
    public int owner(int cell) {
        return owner[cell];
    }

    /** The cells of a player's pieces in ascending order. */
    public int[] pieces(int player) {
        int[] result = new int[PIECES];
        int n = 0;
        for (int c = 0; c < Geometry.CELLS; c++) {
            if (owner[c] == player) {
                if (n == PIECES) {
                    throw new IllegalStateException("player " + player + " has more than " + PIECES + " pieces");
                }
                result[n++] = c;
            }
        }
        return n == PIECES ? result : Arrays.copyOf(result, n);
    }

    /** Sum of the single-agent distances of all pieces of a player. */
    public int distanceSum(int player) {
        int sum = 0;
        for (int c = 0; c < Geometry.CELLS; c++) {
            if (owner[c] == player) {
                sum += layout.distance(player, c);
            }
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
        }
        hashes[ply] = hash;
        moves[ply] = move;
        owner[from] = -1;
        owner[to] = (byte) p;
        hash ^= PIECE_KEY[p][from] ^ PIECE_KEY[p][to];
        updateGoalCounts(p, from, -1);
        updateGoalCounts(p, to, +1);
        int next = (p + 1) % rules.players();
        hash ^= SIDE_KEY[p] ^ SIDE_KEY[next];
        sideToMove = next;
        ply++;
        hashes[ply] = hash;
    }

    /** Takes back the last move. */
    public void unmake() {
        ply--;
        int move = moves[ply];
        int from = Move.from(move);
        int to = Move.to(move);
        int p = (sideToMove + rules.players() - 1) % rules.players();
        owner[to] = -1;
        owner[from] = (byte) p;
        updateGoalCounts(p, to, -1);
        updateGoalCounts(p, from, +1);
        sideToMove = p;
        hash = hashes[ply];
    }

    private void updateGoalCounts(int player, int cell, int delta) {
        int q = layout.goalPlayer(Geometry.cornerOf(cell));
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
     */
    public int generate(int[] out) {
        int p = sideToMove;
        int count = 0;
        for (int from = 0; from < Geometry.CELLS; from++) {
            if (owner[from] != p) {
                continue;
            }
            owner[from] = -1; // the moving piece leaves its cell, so the cell counts as empty during jumps
            for (int d = 0; d < Geometry.DIRECTIONS; d++) {
                int n = Geometry.neighbor(from, d);
                if (n >= 0 && owner[n] < 0 && layout.allowed(p, n) && endLegal(p, from, n)) {
                    out[count++] = Move.of(from, n);
                }
            }
            count = addJumps(p, from, out, count);
            owner[from] = (byte) p;
        }
        return count;
    }

    /** Breadth-first search over jump landings; the visited mark deduplicates (start, end) pairs. */
    private int addJumps(int p, int from, int[] out, int countIn) {
        int count = countIn;
        if (++stamp == Integer.MAX_VALUE) {
            Arrays.fill(visited, 0);
            stamp = 1;
        }
        int top = 0;
        stack[top++] = from;
        visited[from] = stamp;
        while (top > 0) {
            int c = stack[--top];
            for (int d = 0; d < Geometry.DIRECTIONS; d++) {
                int over = Geometry.neighbor(c, d);
                if (over < 0 || owner[over] < 0) {
                    continue;
                }
                int land = Geometry.neighbor(over, d);
                if (land < 0 || owner[land] >= 0 || visited[land] == stamp || !layout.allowed(p, land)) {
                    continue;
                }
                visited[land] = stamp;
                stack[top++] = land;
                if (endLegal(p, from, land)) {
                    out[count++] = Move.of(from, land);
                }
            }
        }
        return count;
    }

    /** Rules that depend on the start and end cell only: leaveGoal and suicideMoves. */
    private boolean endLegal(int p, int from, int to) {
        int goal = layout.goalCorner(p);
        if (rules.leaveGoal() == LeaveGoal.FORBIDDEN
                && Geometry.cornerOf(from) == goal && Geometry.cornerOf(to) != goal) {
            return false;
        }
        return rules.suicideMoves() != SuicideMoves.ILLEGAL || !handsOpponentTheWin(p, from, to);
    }

    /** Whether the move would meet the winning condition of another player (Definition 2). */
    private boolean handsOpponentTheWin(int p, int from, int to) {
        int fromGoal = layout.goalPlayer(Geometry.cornerOf(from));
        int toGoal = layout.goalPlayer(Geometry.cornerOf(to));
        for (int q = 0; q < rules.players(); q++) {
            if (q == p) {
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
        if (occupied != Geometry.CORNER_SIZE) {
            return false;
        }
        return rules.blockadeRule() == BlockadeRule.OPPONENT_FILLS ? own >= 1 : own == Geometry.CORNER_SIZE;
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
        return Outcome.noWinner(Outcome.Reason.NO_MOVES, drawValues());
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
        double scale = (double) PIECES * maxDistance; // no player can be farther than this from the goal
        for (int p = 0; p < players; p++) {
            values[p] = (mean - sums[p]) / scale;
        }
        return values;
    }

    // ---- symmetry and display ----

    /**
     * The position under a cell map; with {@code swapPlayers} players 0 and 1 and the side to move are
     * swapped (two players only). The result has no move history.
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
        result.setSideToMove(swapPlayers ? 1 - sideToMove : sideToMove);
        return result;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Position other
                && sideToMove == other.sideToMove
                && rules.players() == other.rules.players()
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
        return sb.append("side to move: ").append(sideToMove).append(", ply ").append(ply).toString();
    }
}
