package sternhalma.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import sternhalma.engine.Corner;
import sternhalma.engine.Geometry;
import sternhalma.engine.Move;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;

/**
 * Futility pruning (round-2 plan E06) must never cut an immediate win or the only defence against the opponent's
 * immediate win. Moves are ordered by distance gain and the loop breaks at the first move whose bound fails, so a
 * gain-1 goal entry behind larger-gain jumps is exactly what an unguarded rule would cut.
 *
 * <p>The window tests call {@link AlphaBeta#searchNode} with a chosen alpha far above the static value, so that the
 * bound of every non-winning move fails; each has a control position without the near-win in which the same call
 * does prune, which shows that the test position is guarded by the exclusion and not by luck.
 */
class WinInOneTest {

    private static final String OPTIONS = "pvs=1,killers=1,history=1,lmr=2,asp=1,ttrep=1,mate=1,partial=1";
    private static final SearchOptions FUT = PlayerSpec.parse("ab:" + OPTIONS + ",fut=1,futk=30,fut2=1").options();
    private static final SearchOptions OFF = PlayerSpec.parse("ab:" + OPTIONS).options();
    private static final Evaluator EVAL = Evaluators.byName("linear-r2");

    private static int cell(int q, int r) {
        return Geometry.id(q, r);
    }

    private static int gain(Position pos, int move) {
        return DeltaProbe.gain(pos, move);
    }

    /**
     * Player 0 to move. Its goal (r &gt; 4) holds 7 own and 2 opponent pieces (the opponent's start pieces, which
     * fill the goal under the default blockade rule); the hole is (-1, 5) and the own piece at (0, 4) wins by a
     * gain-1 step. Two own pieces far back can jump over opponent pieces and gain more. With
     * {@code goalPieces = 8} one own goal piece stands at (-2, 1) instead, so there is no win.
     */
    static Position winInOne(int goalPieces) {
        Position pos = Position.empty(RuleSet.defaults(2));
        List<Integer> goal = new ArrayList<>();
        for (int c : Geometry.cornerCells(Corner.R_POS.ordinal())) {
            if (c != cell(-1, 5)) {
                goal.add(c);
            }
        }
        // the two deepest goal cells hold opponent pieces
        goal.sort((x, y) -> Integer.compare(Geometry.r(y), Geometry.r(x)));
        pos.put(1, goal.get(0)).put(1, goal.get(1));
        int own = 0;
        for (int i = 2; i < goal.size(); i++) {
            if (own < goalPieces - 2) {
                pos.put(0, goal.get(i));
                own++;
            }
        }
        for (int i = own; i < 7; i++) {
            pos.put(0, -2 + i - own, 1); // spare pieces (only for the control position)
        }
        pos.put(0, 0, 4);
        // far back: jumps over opponent pieces
        pos.put(0, 0, -2).put(1, 0, -1);
        pos.put(0, 2, -3).put(1, 2, -2);
        // the other opponent pieces in the middle, away from both goals
        pos.put(1, -3, 1).put(1, -4, 2).put(1, 3, -1).put(1, 4, -1).put(1, -3, 3).put(1, 3, 1);
        return pos;
    }

    /**
     * Player 0 to move; player 1 threatens to win: its goal (r &lt; -4) holds 9 pieces, the hole is (1, -5), and its
     * piece at (1, -3) jumps over player 0's piece at (1, -4) into it. Player 0 defends only by moving that piece
     * sideways (gain 0); its forward cells are blocked. With {@code goalPieces = 7} two opponent goal pieces stand in
     * the middle instead, so there is no threat.
     */
    static Position threat(int goalPieces) {
        Position pos = Position.empty(RuleSet.defaults(2));
        int placed = 0;
        for (int c : Geometry.cornerCells(Corner.R_NEG.ordinal())) {
            if (c != cell(1, -5) && placed < goalPieces) {
                pos.put(1, c);
                placed++;
            }
        }
        int[][] spare = {{-3, 3}, {3, 1}};
        for (int i = 0; placed + i < 9; i++) {
            pos.put(1, spare[i][0], spare[i][1]);
        }
        pos.put(1, 1, -3);
        pos.put(0, 1, -4).put(0, 0, -3).put(0, -1, -2).put(0, 1, -2);
        pos.put(0, -2, 1).put(0, -1, 1).put(0, 0, 1).put(0, -3, 2).put(0, 1, 0).put(0, 2, -1);
        return pos;
    }

    /** Whether the opponent (to move after {@code move}) can win at once. */
    private static boolean opponentWinsAfter(Position pos, int move) {
        pos.make(move);
        boolean wins = false;
        for (int reply : pos.legalMoves()) {
            pos.make(reply);
            wins |= pos.outcome() != null && pos.outcome().hasWinner() && pos.outcome().winner() == 1 - pos.sideToMove();
            pos.unmake();
        }
        pos.unmake();
        return wins;
    }

    @Test
    void theWinInOnePositionIsWhatTheTestNeeds() {
        Position pos = winInOne(9);
        assertEquals(9, pos.goalOccupied(0));
        int win = Move.of(cell(0, 4), cell(-1, 5));
        int maxGain = Integer.MIN_VALUE;
        int winners = 0;
        for (int m : pos.legalMoves()) {
            maxGain = Math.max(maxGain, gain(pos, m));
            pos.make(m);
            if (pos.outcome() != null && pos.outcome().hasWinner()) {
                winners++;
                assertEquals(win, m);
            }
            pos.unmake();
        }
        assertEquals(1, winners, "exactly one winning move");
        assertEquals(1, gain(pos, win));
        assertTrue(maxGain >= 2, "larger-gain moves come first, max gain " + maxGain);
        assertTrue(winInOne(8).goalOccupied(0) == 8 && winInOne(7).goalOccupied(0) == 7);
    }

    @Test
    void theRootSearchFindsTheWinWithFutilityOn() {
        int win = Move.of(cell(0, 4), cell(-1, 5));
        for (int depth = 1; depth <= 3; depth++) {
            SearchResult r = new AlphaBeta(EVAL, 16, true, FUT).search(winInOne(9), depth);
            assertEquals(win, r.move(), "depth " + depth);
            assertEquals(AlphaBeta.MATE - 1, r.score(), "depth " + depth);
        }
    }

    /** At an interior node with depth 1 or 2 left and alpha far above the static value, the win is still found. */
    @Test
    void anInteriorNodeNeverCutsTheWinningGoalEntry() {
        for (int depth = 1; depth <= 2; depth++) {
            Position pos = winInOne(9);
            int alpha = EVAL.evaluate(pos) + 400;
            AlphaBeta ab = new AlphaBeta(EVAL, 16, true, FUT);
            int score = ab.searchNode(pos, depth, alpha, alpha + 1);
            assertEquals(AlphaBeta.MATE - 2, score, "depth " + depth);
            assertEquals(0, ab.futilityCuts(), "depth " + depth);
        }
    }

    /** Control: without the near-win the same call prunes, so the test above depends on the exclusion. */
    @Test
    void withoutANearWinTheSameNodeIsPruned() {
        for (int depth = 1; depth <= 2; depth++) {
            Position pos = winInOne(depth == 1 ? 8 : 7);
            int alpha = EVAL.evaluate(pos) + 400;
            AlphaBeta ab = new AlphaBeta(EVAL, 16, true, FUT);
            int score = ab.searchNode(pos, depth, alpha, alpha + 1);
            assertTrue(score <= alpha, "depth " + depth + ": " + score);
            assertTrue(ab.futilityCuts() > 0, "depth " + depth);
        }
    }

    @Test
    void theThreatPositionIsWhatTheTestNeeds() {
        Position pos = threat(9);
        assertEquals(9, pos.goalOccupied(1));
        List<Integer> defences = new ArrayList<>();
        int maxGain = Integer.MIN_VALUE;
        for (int m : pos.legalMoves()) {
            maxGain = Math.max(maxGain, gain(pos, m));
            if (!opponentWinsAfter(pos, m)) {
                defences.add(m);
            }
        }
        assertTrue(!defences.isEmpty(), "player 0 can defend");
        for (int m : defences) {
            assertEquals(cell(1, -4), Move.from(m), "only the jumped piece defends: " + Move.toString(m));
            assertEquals(0, gain(pos, m), Move.toString(m));
        }
        assertTrue(maxGain >= 1, "moves with a larger gain come first, max gain " + maxGain);
        Position quiet = threat(7);
        assertEquals(7, quiet.goalOccupied(1));
        for (int m : quiet.legalMoves()) {
            assertTrue(!opponentWinsAfter(quiet, m));
        }
    }

    @Test
    void theRootSearchDefendsWithFutilityOn() {
        for (int depth = 2; depth <= 3; depth++) {
            Position pos = threat(9);
            SearchResult r = new AlphaBeta(EVAL, 16, true, FUT).search(pos, depth);
            assertTrue(!opponentWinsAfter(pos, r.move()), "depth " + depth + ": " + Move.toString(r.move()));
            assertTrue(r.score() > -AlphaBeta.MATE_BOUND, "depth " + depth + ": " + r.score());
        }
    }

    /**
     * At an interior node with 2 plies left the defence is not cut: for every window the result equals the search
     * without futility, and nothing is pruned (also not at the replies, where the opponent has its win in 1).
     */
    @Test
    void anInteriorNodeNeverCutsTheOnlyDefence() {
        Position pos = threat(9);
        int stand = EVAL.evaluate(pos);
        for (int alpha : new int[] {stand - 200, stand, stand + 100, stand + 400}) {
            AlphaBeta fut = new AlphaBeta(EVAL, 16, true, FUT);
            int with = fut.searchNode(pos, 2, alpha, alpha + 1);
            int without = new AlphaBeta(EVAL, 16, true, OFF).searchNode(pos, 2, alpha, alpha + 1);
            assertEquals(without, with, "alpha " + alpha);
            assertEquals(0, fut.futilityCuts(), "alpha " + alpha);
        }
        // control: the same node without the threat is pruned
        Position quiet = threat(7);
        AlphaBeta fut = new AlphaBeta(EVAL, 16, true, FUT);
        int alpha = EVAL.evaluate(quiet) + 400;
        assertTrue(fut.searchNode(quiet, 2, alpha, alpha + 1) <= alpha);
        assertTrue(fut.futilityCuts() > 0);
    }
}
