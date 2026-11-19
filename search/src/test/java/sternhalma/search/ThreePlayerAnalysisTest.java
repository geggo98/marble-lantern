package sternhalma.search;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.engine.RuleSet.JumpRule;

/**
 * The per-move analysis and the static evaluation vector of the three-player player: scores are paranoid scores from
 * the point of view of the player who makes the move, independent of earlier calls, and mate scores count from the
 * position before the move.
 */
class ThreePlayerAnalysisTest {

    private static ThreePlayerPlayer player(long nodes, JumpRule jump) {
        return ThreePlayerSpec.parse("ab3:eval=linear-r2,nodes=" + nodes + ",tt=16" + (jump == JumpRule.LONG ? ",jump=long" : ""))
                .create();
    }

    /** One ply before a won game of seeded greedy play: the side to move has a winning move. */
    private static Position onePlyBeforeEnd(JumpRule jump) {
        Position pos = Position.initial(RuleSet.defaults(3).withJumpRule(jump));
        ThreePlayerPlayer p = player(200, jump);
        while (pos.outcome() == null) {
            pos.make(p.choose(pos));
        }
        pos.unmake();
        return pos;
    }

    @Test
    void depthOneScoreIsTheWorstStaticReplyForTheMover() {
        // nodes=1: only the first iteration runs, ab(1) at the child: the opponent to move picks the reply that
        // minimises the static score of the mover; no reply ends the game in these positions.
        for (int id : new int[] {3, 8, 12, 21}) {
            Position pos = ThreePlayerChooseGolden.position(id);
            JumpRule jump = pos.rules().jumpRule();
            ThreePlayerPlayer p = player(1, jump);
            int mover = pos.sideToMove();
            for (int move : pos.legalMoves()) {
                pos.make(move);
                int expected = Integer.MAX_VALUE;
                for (int reply : pos.legalMoves()) {
                    pos.make(reply);
                    expected = Math.min(expected, p.evaluation(pos)[mover]);
                    pos.unmake();
                }
                pos.unmake();
                SearchResult r = p.analyseMove(pos, move);
                assertEquals(expected, r.score(), "position " + id + " move " + move);
                assertEquals(1, r.depth());
            }
        }
    }

    @Test
    void scoresDoNotDependOnEarlierCallsOrTheirOrder() {
        Position pos = ThreePlayerChooseGolden.position(9);
        int[] moves = pos.legalMoves();
        ThreePlayerPlayer shared = player(1500, pos.rules().jumpRule());
        int[] forward = new int[moves.length];
        for (int i = 0; i < moves.length; i++) {
            forward[i] = shared.analyseMove(pos, moves[i]).score();
        }
        for (int i = moves.length - 1; i >= 0; i--) {
            assertEquals(forward[i], shared.analyseMove(pos, moves[i]).score(), "move " + moves[i]);
            assertEquals(forward[i], player(1500, pos.rules().jumpRule()).analyseMove(pos, moves[i]).score());
        }
    }

    @Test
    void positionIsRestoredAndNodesRespectTheBudget() {
        Position pos = ThreePlayerChooseGolden.position(14);
        long hash = pos.hash();
        int ply = pos.ply();
        ThreePlayerPlayer p = player(800, pos.rules().jumpRule());
        for (int move : pos.legalMoves()) {
            SearchResult r = p.analyseMove(pos, move);
            assertEquals(hash, pos.hash());
            assertEquals(ply, pos.ply());
            assertTrue(r.nodes() >= 1 && r.nodes() < 2 * 800, "nodes " + r.nodes());
            assertEquals(-1, r.move());
        }
    }

    @Test
    void aMoveThatWinsAtOnceScoresMateMinusOneAndIsTheBest() {
        for (JumpRule jump : JumpRule.values()) {
            Position pos = onePlyBeforeEnd(jump);
            ThreePlayerPlayer p = player(300, jump);
            int winning = p.choose(pos);
            assertEquals(AlphaBeta.MATE - 1, p.analyseMove(pos, winning).score(), jump.name());
            for (int move : pos.legalMoves()) {
                assertTrue(p.analyseMove(pos, move).score() <= AlphaBeta.MATE - 1);
            }
        }
    }

    @Test
    void aMoveThatLetsTheNextPlayerWinScoresMinusMateMinusTwo() {
        for (JumpRule jump : JumpRule.values()) {
            Position pos = Position.initial(RuleSet.defaults(3).withJumpRule(jump));
            ThreePlayerPlayer p = player(200, jump);
            List<Position> before = new ArrayList<>();
            List<Integer> played = new ArrayList<>();
            while (pos.outcome() == null) {
                before.add(pos.copy());
                int move = p.choose(pos);
                played.add(move);
                pos.make(move);
            }
            int last = played.size() - 1;
            // the player before the winner moved into a win of the next seat: lost at once, two plies from that position
            assertEquals(-(AlphaBeta.MATE - 2), p.analyseMove(before.get(last - 1), played.get(last - 1)).score(), jump.name());
        }
    }

    @Test
    void evaluationVectorIsPerAbsolutePlayerAndSymmetricInTheStart() {
        for (JumpRule jump : JumpRule.values()) {
            Position start = Position.initial(RuleSet.defaults(3).withJumpRule(jump));
            int[] e = player(1, jump).evaluation(start);
            assertEquals(3, e.length);
            assertEquals(e[0], e[1], jump.name());
            assertEquals(e[1], e[2], jump.name());
        }
        Position pos = ThreePlayerChooseGolden.position(25);
        ThreePlayerPlayer p = player(1, pos.rules().jumpRule());
        int[] e = p.evaluation(pos);
        // the vector does not depend on who is to move
        int mover = pos.sideToMove();
        pos.make(pos.legalMoves()[0]);
        pos.unmake();
        assertArrayEquals(e, p.evaluation(pos));
        assertTrue(e[mover] != 0 || e[(mover + 1) % 3] != 0, "a greedy midgame is not balanced");
    }

    @Test
    void refusesOtherGames() {
        Position two = Position.initial(RuleSet.defaults(2));
        ThreePlayerPlayer p = player(10, JumpRule.ADJACENT);
        assertThrows(IllegalArgumentException.class, () -> p.evaluation(two));
        Position longGame = Position.initial(RuleSet.defaults(3).withJumpRule(JumpRule.LONG));
        assertThrows(IllegalArgumentException.class, () -> p.analyseMove(longGame, longGame.legalMoves()[0]));
    }
}
