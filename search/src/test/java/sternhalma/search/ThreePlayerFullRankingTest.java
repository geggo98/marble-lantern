package sternhalma.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import sternhalma.engine.Geometry;
import sternhalma.engine.Layout;
import sternhalma.engine.Move;
import sternhalma.engine.Outcome;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.engine.RuleSet.JumpRule;
import sternhalma.engine.RuleSet.Ranking;

/** The three-player search with {@code ranking=FULL_RANKING}: finished players, two active players, second place. */
class ThreePlayerFullRankingTest {

    private static RuleSet full(JumpRule jump) {
        return RuleSet.defaults(3).withRanking(Ranking.FULL_RANKING).withJumpRule(jump);
    }

    private static ThreePlayerPlayer ab(JumpRule jump, long nodes) {
        return ThreePlayerSpec.parse("ab3:nodes=" + nodes + ",tt=16" + (jump == JumpRule.LONG ? ",jump=long" : ""))
                .create();
    }

    /** Players 1 and 2 on their start corners, player 0 as given; player 0 has finished and player 1 is to move. */
    private static Position continued(JumpRule jump, int[] zeroCells) {
        Position pos = Position.empty(full(jump));
        for (int p = 1; p < 3; p++) {
            for (int c : Geometry.cornerCells(pos.layout().startCorner(p))) {
                pos.put(p, c);
            }
        }
        for (int c : zeroCells) {
            pos.put(0, c);
        }
        pos.finish(0).setSideToMove(1);
        return pos;
    }

    @Test
    void theEvaluationLooksOnlyAtTheActiveOpponent() {
        ThreePlayerSearch search = new ThreePlayerSearch(LinearWeights.WEIGHTS, 10);
        Layout layout = Layout.of(3);
        int[] home = Geometry.cornerCells(layout.startCorner(0));
        int[] goal = Geometry.cornerCells(layout.goalCorner(0));
        Position a = continued(JumpRule.ADJACENT, home);
        Position b = continued(JumpRule.ADJACENT, goal);
        setRoot(search, a);
        int scoreA = search.evaluate(1);
        setRoot(search, b);
        int scoreB = search.evaluate(1);
        assertEquals(scoreA, scoreB, "the finished player's pieces do not enter the score");
    }

    private static void setRoot(ThreePlayerSearch search, Position pos) {
        search.setPositionForEvaluation(pos);
    }

    @Test
    void theRootPlayerTakesSecondPlaceWhenItIsOneStepAway() {
        for (JumpRule jump : JumpRule.values()) {
            Position pos = Position.empty(full(jump));
            Layout layout = pos.layout();
            int goal = layout.goalCorner(1);
            int tip = -1;
            int next = -1;
            outer:
            for (int x : Geometry.cornerCells(goal)) {
                for (int d = 0; d < 6; d++) {
                    int n = Geometry.neighbor(x, d);
                    if (n >= 0 && Geometry.cornerOf(n) != goal && layout.allowed(1, n)) {
                        tip = x;
                        next = n;
                        break outer;
                    }
                }
            }
            for (int c : Geometry.cornerCells(goal)) {
                if (c != tip) {
                    pos.put(1, c);
                }
            }
            pos.put(1, next);
            pos.put(0, Geometry.cornerCells(layout.startCorner(0))[0]);
            pos.put(2, Geometry.cornerCells(layout.startCorner(2))[0]);
            pos.finish(0).setSideToMove(1);
            SearchResult result = ab(jump, 3000).analyse(pos);
            assertEquals(Move.of(next, tip), result.move(), result.toString());
            pos.make(result.move());
            Outcome outcome = pos.outcome();
            assertNotNull(outcome);
            assertEquals(1, outcome.ranking()[1]);
            assertTrue(result.score() >= ThreePlayerSearch.SECOND - 10, result.toString());
            assertTrue(result.score() < AlphaBeta.MATE - 1000, "second place is no mate: " + result);
        }
    }

    @Test
    void avoidsAMoveThatLetsTheLastActiveOpponentFinishFirst() {
        // player 2 is one step from the goal; player 1 (root) cannot stop it, but must not do worse than needed.
        // With player 0 finished, player 2's finish ends the game and the root is last: score -(MATE - h).
        Position pos = Position.empty(full(JumpRule.ADJACENT));
        Layout layout = pos.layout();
        int goal = layout.goalCorner(2);
        int tip = -1;
        int next = -1;
        outer:
        for (int x : Geometry.cornerCells(goal)) {
            for (int d = 0; d < 6; d++) {
                int n = Geometry.neighbor(x, d);
                if (n >= 0 && Geometry.cornerOf(n) != goal && layout.allowed(2, n)) {
                    tip = x;
                    next = n;
                    break outer;
                }
            }
        }
        for (int c : Geometry.cornerCells(goal)) {
            if (c != tip) {
                pos.put(2, c);
            }
        }
        pos.put(2, next);
        pos.put(0, Geometry.cornerCells(layout.startCorner(0))[0]);
        pos.put(1, Geometry.cornerCells(layout.startCorner(1))[0]);
        pos.finish(0).setSideToMove(1);
        SearchResult result = ab(JumpRule.ADJACENT, 3000).analyse(pos);
        assertTrue(result.score() <= -(AlphaBeta.MATE - 10), "the opponent finishes next ply: " + result);
    }

    /** From the start with player 0 already finished, the engine takes second place against a random player. */
    @Test
    void takesSecondPlaceAgainstARandomPlayerInTheContinuedPhase() {
        int games = 12;
        for (JumpRule jump : JumpRule.values()) {
            int[] seconds = new int[2];
            for (int seat = 1; seat <= 2; seat++) {
                for (int g = 0; g < games; g++) {
                    Position pos = Position.initial(full(jump)).finish(0);
                    Player random = new RandomPlayer(500L * seat + g);
                    ThreePlayerPlayer engine = ab(jump, 20_000);
                    Outcome outcome = null;
                    while ((outcome = pos.outcome()) == null) {
                        int side = pos.sideToMove();
                        assertTrue(side != 0, "the finished player is never to move");
                        int move = side == seat ? engine.choose(pos) : random.choose(pos);
                        assertTrue(Arrays.stream(pos.legalMoves()).anyMatch(m -> m == move));
                        pos.make(move);
                    }
                    assertEquals(0, outcome.ranking()[0]);
                    if (outcome.ranking()[1] == seat) {
                        seconds[seat - 1]++;
                    }
                }
            }
            System.out.println("3p continued phase vs random, jump=" + jump + ": second places " + Arrays.toString(seconds)
                    + " of " + games);
            assertTrue(seconds[0] >= games - 1 && seconds[1] >= games - 1, jump + ": " + Arrays.toString(seconds));
        }
    }

    @Test
    void threeEnginesPlayAWholeRankingLegallyAndDeterministically() {
        for (JumpRule jump : JumpRule.values()) {
            Outcome first = wholeGame(jump);
            assertNotNull(first);
            assertEquals(3, first.ranking().length);
            assertEquals(first, wholeGame(jump));
        }
    }

    private static Outcome wholeGame(JumpRule jump) {
        Position pos = Position.initial(full(jump).withMoveLimit(400));
        ThreePlayerPlayer[] seats = {ab(jump, 3000), ab(jump, 3000), ab(jump, 3000)};
        Outcome outcome;
        while ((outcome = pos.outcome()) == null) {
            assertTrue(!pos.isFinished(pos.sideToMove()));
            int move = seats[pos.sideToMove()].choose(pos);
            assertTrue(Arrays.stream(pos.legalMoves()).anyMatch(m -> m == move));
            pos.make(move);
        }
        return outcome;
    }
}
