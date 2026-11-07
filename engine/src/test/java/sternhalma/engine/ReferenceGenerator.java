package sternhalma.engine;

import java.util.HashSet;
import java.util.Set;

/**
 * A deliberately naive second implementation of the move rules, used as a test oracle.
 *
 * <p>It shares no tables with the engine: it works on axial coordinates, finds jump chains by recursion
 * and re-checks the leaveGoal and suicide rules by playing each move on a copy of the board.
 */
final class ReferenceGenerator {

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, -1}, {-1, 1}};

    private ReferenceGenerator() {
    }

    /** Legal moves of the side to move, as {@code from * 1000 + to}. */
    static Set<Integer> legalMoves(Position pos) {
        RuleSet rules = pos.rules();
        int p = pos.sideToMove();
        Set<Integer> result = new HashSet<>();
        for (int from = 0; from < Geometry.CELLS; from++) {
            if (pos.owner(from) != p) {
                continue;
            }
            Set<Integer> ends = new HashSet<>();
            int fq = Geometry.q(from);
            int fr = Geometry.r(from);
            for (int[] d : DIRS) {
                int cell = Geometry.id(fq + d[0], fr + d[1]);
                if (cell >= 0 && pos.owner(cell) < 0 && allowed(rules.players(), p, cell)) {
                    ends.add(cell);
                }
            }
            Set<Integer> seen = new HashSet<>();
            seen.add(from);
            jump(pos, rules.players(), p, from, from, seen, ends);
            for (int to : ends) {
                if (isLegal(pos, p, from, to)) {
                    result.add(from * 1000 + to);
                }
            }
        }
        return result;
    }

    /** Recursive search for jump landings; the start cell counts as empty. */
    private static void jump(Position pos, int players, int p, int start, int at, Set<Integer> seen, Set<Integer> ends) {
        int q = Geometry.q(at);
        int r = Geometry.r(at);
        for (int[] d : DIRS) {
            int over = Geometry.id(q + d[0], r + d[1]);
            int land = Geometry.id(q + 2 * d[0], r + 2 * d[1]);
            if (over < 0 || land < 0 || over == start || pos.owner(over) < 0) {
                continue;
            }
            boolean landEmpty = land == start || pos.owner(land) < 0;
            if (!landEmpty || !allowed(players, p, land) || !seen.add(land)) {
                continue;
            }
            ends.add(land);
            jump(pos, players, p, start, land, seen, ends);
        }
    }

    private static boolean isLegal(Position pos, int p, int from, int to) {
        RuleSet rules = pos.rules();
        if (rules.leaveGoal() == RuleSet.LeaveGoal.FORBIDDEN && inGoal(rules.players(), p, from) && !inGoal(rules.players(), p, to)) {
            return false;
        }
        if (rules.suicideMoves() == RuleSet.SuicideMoves.ILLEGAL) {
            int[] board = new int[Geometry.CELLS];
            for (int c = 0; c < Geometry.CELLS; c++) {
                board[c] = pos.owner(c);
            }
            board[from] = -1;
            board[to] = p;
            for (int q = 0; q < rules.players(); q++) {
                if (q != p && wins(rules, board, q)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Win condition computed from scratch on a plain array. */
    static boolean wins(RuleSet rules, int[] board, int q) {
        int own = 0;
        int cells = 0;
        for (int c = 0; c < Geometry.CELLS; c++) {
            if (inGoal(rules.players(), q, c)) {
                cells++;
                if (board[c] < 0) {
                    return false;
                }
                if (board[c] == q) {
                    own++;
                }
            }
        }
        if (cells != 10) {
            throw new AssertionError("goal has " + cells + " cells");
        }
        return rules.blockadeRule() == RuleSet.BlockadeRule.OPPONENT_FILLS ? own >= 1 : own == 10;
    }

    /** Corner kinds by condition on (q, r, s): 'T' r&lt;-4, 'B' r&gt;4, 'L' q&lt;-4, 'R' q&gt;4, 'U' s&gt;4, 'D' s&lt;-4. */
    private static boolean inCorner(char kind, int cell) {
        int q = Geometry.q(cell);
        int r = Geometry.r(cell);
        int s = -q - r;
        return switch (kind) {
            case 'T' -> r < -4;
            case 'B' -> r > 4;
            case 'L' -> q < -4;
            case 'R' -> q > 4;
            case 'U' -> s > 4;
            case 'D' -> s < -4;
            default -> throw new AssertionError(kind);
        };
    }

    private static char startKind(int players, int p) {
        return players == 2 ? (p == 0 ? 'T' : 'B') : "TDL".charAt(p);
    }

    private static char goalKind(int players, int p) {
        return switch (startKind(players, p)) {
            case 'T' -> 'B';
            case 'B' -> 'T';
            case 'D' -> 'U';
            case 'L' -> 'R';
            default -> throw new AssertionError();
        };
    }

    static boolean inGoal(int players, int p, int cell) {
        return inCorner(goalKind(players, p), cell);
    }

    static boolean allowed(int players, int p, int cell) {
        for (char kind : "TBLRUD".toCharArray()) {
            if (inCorner(kind, cell)) {
                return kind == startKind(players, p) || kind == goalKind(players, p);
            }
        }
        return true; // central hexagon
    }
}
