package sternhalma.search;

import sternhalma.engine.Layout;
import sternhalma.engine.Move;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.engine.RuleSet.LeaveGoal;
import sternhalma.tablebase.RaceCounts;
import sternhalma.tablebase.Rows;

/**
 * Race play: once the armies have passed ({@link RaceAdjudication#passed}), the side to move plays its solitaire
 * instead of searching. Among the legal moves it takes the one whose resulting configuration has the smallest race
 * table count (ties: larger distance gain, then move order); when the table does not cover the configuration, the
 * first move of a beam plan whose landings all lie above the opponent's rearmost piece; when neither gives a legal
 * move, {@link #choose} returns -1 and the caller searches. Every returned move is taken from the position's legal
 * move list.
 *
 * <p>This is a strength heuristic, not a soundness claim: it ignores that the opponent could still block cells
 * inside the mover's region, and it never labels a position. Mode 2 ({@code race=2}) races only while the table
 * arithmetic does not say the race is lost (both configurations covered and the opponent finishes first under the
 * conversion rule of docs/endgame-and-opening.md); a lost race goes back to the search, which may find a block.
 * Three-player games and positions with a missing piece are left to the search. Not thread-safe; one instance per
 * player.
 */
public final class RaceMove {

    /** Beam width of the fallback plan; plans here are short, so a narrow beam is enough. */
    public static final int BEAM_WIDTH = 200;
    private static final int MAX_PLAN_MOVES = 60;

    private final RaceCounts table;
    private final int mode;
    private final int minRow;
    private final Solitaire[] solitaire = new Solitaire[2];
    private LeaveGoal leaveGoal;
    private final Layout layout = Layout.of(2);
    private long tableMoves;
    private long planMoves;

    /**
     * @param table the race table, or null for beam-only play
     * @param mode  1 always races in a passed position, 2 only while the race is not known to be lost
     */
    public RaceMove(RaceCounts table, int mode) {
        this(table, mode, table instanceof sternhalma.tablebase.RaceTable t ? t.minRow() : -1);
    }

    /**
     * @param minRow the first row of the region the table is meant to cover (1 or 2), or -1 if unknown; a wrapped table
     *               (an estimate, a block-restricted one) is not a {@link RaceTable} and says it here
     */
    public RaceMove(RaceCounts table, int mode, int minRow) {
        if (mode != 1 && mode != 2) {
            throw new IllegalArgumentException("race mode must be 1 or 2, got " + mode);
        }
        this.table = table;
        this.mode = mode;
        this.minRow = minRow;
    }

    public int mode() {
        return mode;
    }

    public boolean usesTable() {
        return table != null;
    }

    /** The minimum row of the race table's region (2 default, 1 extended), or -1 if unknown. */
    public int tableMinRow() {
        return minRow;
    }

    /** The race table, or null for beam-only play. */
    public RaceCounts table() {
        return table;
    }

    /** Moves chosen from the race table so far. */
    public long tableMoves() {
        return tableMoves;
    }

    /** Moves chosen from a solitaire plan (the table did not cover the configuration) so far. */
    public long planMoves() {
        return planMoves;
    }

    private Solitaire solitaire(int player, RuleSet rules) {
        if (leaveGoal != rules.leaveGoal()) {
            solitaire[0] = null;
            solitaire[1] = null;
            leaveGoal = rules.leaveGoal();
        }
        if (solitaire[player] == null) {
            solitaire[player] = new Solitaire(player, leaveGoal, table);
        }
        return solitaire[player];
    }

    /** A legal race move for the side to move, or -1 to let the search decide. */
    public int choose(Position pos) {
        RuleSet rules = pos.rules();
        if (rules.players() != 2 || rules.jumpRule() != RuleSet.JumpRule.ADJACENT
                || rules.blockingStates() != RuleSet.BlockingStates.NONE
                || !RaceAdjudication.passed(pos)) {
            return -1;
        }
        int me = pos.sideToMove();
        int opp = 1 - me;
        int[] mine = pos.pieces(me);
        int[] theirs = pos.pieces(opp);
        Solitaire sol = solitaire(me, rules);
        if (sol.isGoal(mine)) {
            return -1;
        }
        int[] legal = pos.legalMoves();
        if (legal.length == 0) {
            return -1;
        }
        int[] mine0 = Rows.toPlayer0(me, mine);
        if (mode == 2 && table != null) {
            int a = table.count(mine0);
            int b = table.count(Rows.toPlayer0(opp, theirs));
            if (a >= 0 && b >= 0) {
                int left = rules.moveLimit() - pos.ply();
                if (b < a && 2 * b <= left) {
                    return -1; // the opponent finishes first: let the search look for a block
                }
            }
        }
        if (table != null && table.covers(mine0)) {
            int best = -1;
            int bestCount = Integer.MAX_VALUE;
            int bestGain = Integer.MIN_VALUE;
            int[] cells = mine.clone();
            for (int m : legal) {
                int from = Move.from(m);
                int to = Move.to(m);
                for (int i = 0; i < cells.length; i++) {
                    if (cells[i] == from) {
                        cells[i] = to;
                    }
                }
                int count = table.count(Rows.toPlayer0(me, cells));
                for (int i = 0; i < cells.length; i++) {
                    if (cells[i] == to) {
                        cells[i] = from;
                    }
                }
                if (count < 0) {
                    continue;
                }
                int gain = layout.distance(me, from) - layout.distance(me, to);
                if (count < bestCount || (count == bestCount && gain > bestGain)) {
                    best = m;
                    bestCount = count;
                    bestGain = gain;
                }
            }
            if (best >= 0) {
                tableMoves++;
                return best;
            }
        }
        int oppRear = sol.frontRow(theirs); // the opponent piece closest to me, in my rows
        Solitaire.Plan plan = sol.plan(mine, BEAM_WIDTH, MAX_PLAN_MOVES, oppRear + 1);
        if (plan != null && plan.moves() > 0) {
            int m = plan.steps().get(0).move();
            for (int l : legal) {
                if (l == m) {
                    planMoves++;
                    return m;
                }
            }
        }
        return -1;
    }
}
