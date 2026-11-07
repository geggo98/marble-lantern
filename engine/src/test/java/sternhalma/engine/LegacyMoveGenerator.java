package sternhalma.engine;

import java.util.Arrays;

import sternhalma.engine.RuleSet.BlockadeRule;
import sternhalma.engine.RuleSet.LeaveGoal;
import sternhalma.engine.RuleSet.SuicideMoves;

/**
 * The move generator of Position as it was before experiment E02 (commit b66e7e2), kept as a test oracle for
 * the exact move ORDER, which the search tree depends on. {@link ReferenceGenerator} checks the move set with
 * independent code; this class checks that the faster generator emits the same moves in the same order.
 *
 * <p>The algorithm is the old one line for line: scan all 121 cells for pieces of the side to move; per piece
 * the six steps by direction, then the jump landings in the order of a stack-driven search; leaveGoal and the
 * Definition-2 suicide test for every end cell. Only the state comes from the public accessors (the owner array
 * is copied, the goal counters are recounted) instead of private fields. Do not optimise this class.
 */
final class LegacyMoveGenerator {

    private final RuleSet rules;
    private final Layout layout;
    private final byte[] owner = new byte[Geometry.CELLS];
    private final int[] goalOccupied;
    private final int[] goalOwn;
    private final int[] visited = new int[Geometry.CELLS];
    private int stamp;
    private final int[] stack = new int[Geometry.CELLS];

    /** Moves rejected only by the Definition-2 test in the last call (shows that the test was exercised). */
    int suicideRejections;
    /** Moves rejected by the leaveGoal rule in the last call. */
    int leaveGoalRejections;

    LegacyMoveGenerator(RuleSet rules) {
        this.rules = rules;
        this.layout = Layout.of(rules.players());
        this.goalOccupied = new int[rules.players()];
        this.goalOwn = new int[rules.players()];
    }

    /** The legal moves of {@code pos} in the old order. */
    int[] legalMoves(Position pos) {
        Arrays.fill(goalOccupied, 0);
        Arrays.fill(goalOwn, 0);
        for (int c = 0; c < Geometry.CELLS; c++) {
            owner[c] = (byte) pos.owner(c);
            if (owner[c] >= 0) {
                int q = layout.goalPlayer(Geometry.cornerOf(c));
                if (q >= 0) {
                    goalOccupied[q]++;
                    if (q == owner[c]) {
                        goalOwn[q]++;
                    }
                }
            }
        }
        suicideRejections = 0;
        leaveGoalRejections = 0;
        int[] out = new int[Position.MAX_MOVES];
        int p = pos.sideToMove();
        int count = 0;
        for (int from = 0; from < Geometry.CELLS; from++) {
            if (owner[from] != p) {
                continue;
            }
            owner[from] = -1;
            for (int d = 0; d < Geometry.DIRECTIONS; d++) {
                int n = Geometry.neighbor(from, d);
                if (n >= 0 && owner[n] < 0 && layout.allowed(p, n) && endLegal(p, from, n)) {
                    out[count++] = Move.of(from, n);
                }
            }
            count = addJumps(p, from, out, count);
            owner[from] = (byte) p;
        }
        return Arrays.copyOf(out, count);
    }

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

    private boolean endLegal(int p, int from, int to) {
        int goal = layout.goalCorner(p);
        if (rules.leaveGoal() == LeaveGoal.FORBIDDEN
                && Geometry.cornerOf(from) == goal && Geometry.cornerOf(to) != goal) {
            leaveGoalRejections++;
            return false;
        }
        if (rules.suicideMoves() == SuicideMoves.ILLEGAL && handsOpponentTheWin(p, from, to)) {
            suicideRejections++;
            return false;
        }
        return true;
    }

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

    private boolean isWin(int occupied, int own) {
        if (occupied != Geometry.CORNER_SIZE) {
            return false;
        }
        return rules.blockadeRule() == BlockadeRule.OPPONENT_FILLS ? own >= 1 : own == Geometry.CORNER_SIZE;
    }
}
