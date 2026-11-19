package sternhalma.search;

import java.util.Arrays;
import java.util.Map;

import sternhalma.engine.Geometry;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.tablebase.RaceCounts;
import sternhalma.tablebase.Rows;

/**
 * Exact race arithmetic as a leaf value (round-2 experiment E08, spec names {@code racelin} and {@code racelin1}).
 * In a <em>passed</em> two-player position ({@link RaceAdjudication#passed}) whose two configurations the race table
 * both covers, the value is the conversion rule of docs/endgame-and-opening.md: with a the table count of the side to
 * move, b the opponent's and L the plies left before the move limit, the side to move wins if {@code a <= b} and
 * {@code 2a - 1 <= L}, the opponent wins if {@code b < a} and {@code 2b <= L}. The score is
 * {@code RACE_BASE + UNIT x (b - a)} for a won race and {@code -(RACE_BASE + UNIT x (a - b - 1))} for a lost one, so
 * every race value lies in the band {@link Scores.Kind#RACE} and a race won by a larger margin scores higher.
 * Everywhere else (contact positions, a configuration outside the table, nobody finishes before the limit, three
 * players, {@code blockingStates = ILLEGAL}) the wrapped evaluator decides.
 *
 * <p><b>Outside the region</b> ({@code wout=1}, {@link #estimateOutside}): the plan's fallback to the wrapped value
 * for an uncovered configuration has a measured flaw: a side that is losing the race keeps one piece behind the
 * region, because the ordinary value of an uncovered leaf beats {@code -RACE_BASE}, and racelin then never fires at
 * all (0 of about 20 passed plies per game with both sides covered, against 5 to 17 with the linear evaluator). With
 * the estimate on, an uncovered configuration counts {@code (region row - row)} moves for every piece behind the
 * region plus the table count of the configuration with those pieces placed on the nearest free cells of the
 * region's first row. That is a heuristic count (steps only, so an overestimate when jumps are available), but it is
 * continuous across the boundary, and a step into the region lowers it by exactly one.
 *
 * <p>These values are heuristics for the search, never labels: the table counts are exact only for the restricted
 * solitaire (upper bounds of the unrestricted distance) and the arithmetic ignores blockades. The band makes them
 * recognisable: the margin adjudication and the value-target writers ignore {@link Scores.Kind#RACE} scores
 * ({@link Scores#kind}), the aspiration window of {@link AlphaBeta} treats them like mate scores, and futility
 * pruning is switched off wherever this evaluator could return one ({@link #futilitySafe}).
 *
 * <p>Not thread-safe (scratch space for the table lookups); one instance per searcher, as the registry supplies.
 */
public final class RaceEvaluator implements Evaluator {

    /** Score units per move of race margin; 20 moves of margin stay below the mate band (checked by ScoresTest). */
    public static final int UNIT = 200;

    /** Returned by {@link #raceScore} when nobody finishes before the move limit (the evaluator falls back). */
    static final int UNDECIDED = Integer.MIN_VALUE;

    /** The one weight a spec may give: {@code wout=1} estimates the counts of pieces outside the region. */
    static final String WEIGHT_OUTSIDE = "wout";

    private final Evaluator base;
    private final RaceCounts table;
    private final boolean estimateOutside;
    private final int regionMinRow;
    /** The region's cells per row from the first region row upwards, in player 0's orientation. */
    private final int[][] regionRows;
    private final int[] scratch = new int[Position.PIECES];
    private final boolean[] occupied = new boolean[Geometry.CELLS];

    /**
     * @param base  the evaluator for every position without a race value
     * @param table the race counts; null makes this evaluator identical to {@code base}. The region's first row is
     *              taken from a {@link sternhalma.tablebase.RaceTable}; for other counts the futility hook assumes
     *              that every passed position may carry a race value
     */
    public RaceEvaluator(Evaluator base, RaceCounts table) {
        this(base, table, table instanceof sternhalma.tablebase.RaceTable t ? t.minRow() : Integer.MIN_VALUE, false);
    }

    /**
     * @param regionMinRow    the first row of the table's region in a player's own rows (2 or 1): the futility hook
     *                        counts the pieces behind it, and the estimate places them on it
     * @param estimateOutside estimate the count of a configuration with pieces behind the region (see above)
     */
    public RaceEvaluator(Evaluator base, RaceCounts table, int regionMinRow, boolean estimateOutside) {
        this.base = base;
        this.table = table;
        this.estimateOutside = estimateOutside && table != null;
        this.regionMinRow = regionMinRow;
        if (this.estimateOutside) {
            regionRows = new int[Rows.LAST_ROW - regionMinRow + 1][];
            for (int row = regionMinRow; row <= Rows.LAST_ROW; row++) {
                int r = row;
                regionRows[row - regionMinRow] = Arrays.stream(Rows.regionCells(regionMinRow))
                        .filter(c -> Geometry.r(c) == r).toArray();
            }
        } else {
            regionRows = null;
        }
    }

    /** The evaluator of a registry entry: the weights may hold {@value #WEIGHT_OUTSIDE} (0 or 1), nothing else. */
    static RaceEvaluator of(Evaluator base, RaceCounts table, int regionMinRow, Map<String, Integer> weights) {
        for (Map.Entry<String, Integer> e : weights.entrySet()) {
            if (!e.getKey().equals(WEIGHT_OUTSIDE) || e.getValue() < 0 || e.getValue() > 1) {
                throw new IllegalArgumentException("racelin accepts only " + WEIGHT_OUTSIDE + "=0|1, got " + weights);
            }
        }
        return new RaceEvaluator(base, table, regionMinRow, weights.getOrDefault(WEIGHT_OUTSIDE, 0) == 1);
    }

    /** The wrapped evaluator. */
    public Evaluator base() {
        return base;
    }

    /** The race counts, or null. */
    public RaceCounts table() {
        return table;
    }

    /** Whether pieces behind the region are estimated ({@code wout=1}) instead of falling back to the base value. */
    public boolean estimatesOutside() {
        return estimateOutside;
    }

    /** The rule preconditions: two players and no blocking-state rule (the table knows nothing about it). */
    static boolean rulesApply(RuleSet rules) {
        return rules.players() == 2 && rules.jumpRule() == RuleSet.JumpRule.ADJACENT
                && rules.blockingStates() == RuleSet.BlockingStates.NONE;
    }

    /**
     * {@link RaceAdjudication#passed} without the scan over the board: the piece lists are sorted by cell id, and cell
     * ids are ordered by row {@code r} first, so player 0's rearmost row is that of its first piece and player 1's
     * rearmost row (towards player 0) that of its last piece.
     */
    static boolean passed(Position pos) {
        if (pos.pieceCount(0) != Position.PIECES || pos.pieceCount(1) != Position.PIECES) {
            return false;
        }
        return Geometry.r(pos.pieceCell(0, 0)) > Geometry.r(pos.pieceCell(1, Position.PIECES - 1));
    }

    /**
     * The race value of counts a (side to move) and b (opponent) with {@code pliesLeft} plies before the move limit,
     * or {@link #UNDECIDED} when nobody finishes in time.
     */
    static int raceScore(int a, int b, int pliesLeft) {
        if (a <= b && 2 * a - 1 <= pliesLeft) {
            return Scores.RACE_BASE + UNIT * (b - a);
        }
        if (b < a && 2 * b <= pliesLeft) {
            return -(Scores.RACE_BASE + UNIT * (a - b - 1));
        }
        return UNDECIDED;
    }

    /** Fills {@link #scratch} with the player's cells in player 0's orientation, ascending. */
    private void load(Position pos, int player) {
        for (int i = 0; i < Position.PIECES; i++) {
            scratch[i] = pos.pieceCell(player, i);
        }
        if (player == 1) {
            for (int i = 0; i < Position.PIECES; i++) {
                scratch[i] = Geometry.playerFlip(scratch[i]);
            }
            Arrays.sort(scratch);
        }
    }

    /**
     * The count of a player's configuration: the table count if the region covers it; with the estimate on and
     * pieces behind the region, their steps to the region's first row plus the table count of the configuration with
     * them placed on the nearest free cells of that row (next rows if it is full); otherwise -1.
     */
    int count(Position pos, int player) {
        load(pos, player);
        if (!estimateOutside || Geometry.r(scratch[0]) >= regionMinRow) {
            return table.count(scratch);
        }
        return estimate();
    }

    private int estimate() {
        Arrays.fill(occupied, false);
        for (int c : scratch) {
            occupied[c] = true;
        }
        int moves = 0;
        for (int i = 0; i < Position.PIECES && Geometry.r(scratch[i]) < regionMinRow; i++) {
            int from = scratch[i];
            moves += regionMinRow - Geometry.r(from);
            int target = nearestFree(Geometry.q(from));
            occupied[from] = false;
            occupied[target] = true;
            scratch[i] = target;
        }
        Arrays.sort(scratch);
        int count = table.count(scratch);
        return count < 0 ? -1 : moves + count;
    }

    /** The free region cell nearest in q on the region's first row, else on the next rows (ties: lower cell id). */
    private int nearestFree(int q) {
        for (int[] row : regionRows) {
            int best = -1;
            int bestDistance = Integer.MAX_VALUE;
            for (int c : row) {
                int d = Math.abs(Geometry.q(c) - q);
                if (!occupied[c] && d < bestDistance) {
                    best = c;
                    bestDistance = d;
                }
            }
            if (best >= 0) {
                return best;
            }
        }
        throw new IllegalStateException("no free region cell for " + Position.PIECES + " pieces");
    }

    @Override
    public int evaluate(Position position) {
        int score = raceValue(position);
        return score == UNDECIDED ? base.evaluate(position) : score;
    }

    /** The race value of the position, or {@link #UNDECIDED} if the wrapped evaluator decides. */
    private int raceValue(Position position) {
        if (table == null || !rulesApply(position.rules()) || !passed(position)) {
            return UNDECIDED;
        }
        int me = position.sideToMove();
        int a = count(position, me);
        if (a < 0) {
            return UNDECIDED;
        }
        int b = count(position, 1 - me);
        if (b < 0) {
            return UNDECIDED;
        }
        return raceScore(a, b, position.rules().moveLimit() - position.ply());
    }

    /** An incremental state if the wrapped evaluator has one; the race value is cheap to form from scratch. */
    @Override
    public EvalState incremental() {
        EvalState inner = base.incremental();
        return inner == null ? null : new EvalState() {
            @Override
            public boolean reset(Position root) {
                return inner.reset(root);
            }

            @Override
            public void make(Position position, int move) {
                inner.make(position, move);
            }

            @Override
            public void unmake(Position position) {
                inner.unmake(position);
            }

            @Override
            public int evaluate(Position position) {
                int score = raceValue(position);
                return score == UNDECIDED ? inner.evaluate(position) : score;
            }

            @Override
            public void verify(Position position) {
                inner.verify(position);
            }
        };
    }

    /**
     * False wherever a race value can appear within {@code depth} plies, so that futility pruning (E06) never
     * compares a race value with an ordinary one. Two necessary conditions for a race value, each checked against
     * what the moves within {@code depth} plies can change: the side to move moves {@code ceil(depth / 2)} times and
     * the opponent {@code floor(depth / 2)} times, and each move moves one piece.
     * <ul>
     *   <li><em>Passed:</em> after its moves, player 0's rearmost row is at least the rearmost row of the pieces it
     *       did not move, which is at most the row of its (moves + 1)-th rearmost piece, and likewise for player 1
     *       towards player 0; if those two rows do not pass each other, no sequence of that many moves can pass (a
     *       passed position itself fails this test for any number of moves).</li>
     *   <li><em>Covered</em> (without the estimate only): a race value needs both configurations inside the table's
     *       region, so every piece behind the region's first row must move at least once; a side with more such
     *       pieces than moves cannot be covered in time. With the estimate every passed position carries a race
     *       value, so this condition does not apply.</li>
     * </ul>
     * The test is necessary, not exact: it also says "unsafe" when the pieces exist but cannot make the jumps.
     * RaceEvaluatorTest checks it against the race values of every position within {@code depth} plies and prints
     * how often it is conservative.
     */
    @Override
    public boolean futilitySafe(Position position, int depth) {
        if (table == null || !rulesApply(position.rules())
                || position.pieceCount(0) != Position.PIECES || position.pieceCount(1) != Position.PIECES) {
            return true; // never a race value
        }
        int me = position.sideToMove();
        int movesMe = (depth + 1) / 2;
        int movesOpp = depth / 2;
        int moves0 = me == 0 ? movesMe : movesOpp;
        int moves1 = me == 0 ? movesOpp : movesMe;
        if (moves0 >= Position.PIECES || moves1 >= Position.PIECES) {
            return false;
        }
        int rear0 = Geometry.r(position.pieceCell(0, moves0));
        int rear1 = Geometry.r(position.pieceCell(1, Position.PIECES - 1 - moves1));
        if (rear0 <= rear1) {
            return true; // cannot pass within depth plies
        }
        if (estimateOutside) {
            return false;
        }
        // player 0's pieces behind the region have r < minRow and come first in its list; player 1's have -r < minRow
        // and come last in its list
        int behind0 = 0;
        while (behind0 < Position.PIECES && Geometry.r(position.pieceCell(0, behind0)) < regionMinRow) {
            behind0++;
        }
        int behind1 = 0;
        while (behind1 < Position.PIECES
                && -Geometry.r(position.pieceCell(1, Position.PIECES - 1 - behind1)) < regionMinRow) {
            behind1++;
        }
        return behind0 > moves0 || behind1 > moves1;
    }

    @Override
    public String toString() {
        return "racelin[" + base + ", " + (table == null ? "no table" : table) + (estimateOutside ? ", wout" : "") + "]";
    }
}
