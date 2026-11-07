package sternhalma.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import sternhalma.engine.Geometry;
import sternhalma.engine.Layout;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.engine.RuleSet.BlockadeRule;
import sternhalma.engine.RuleSet.LeaveGoal;
import sternhalma.engine.RuleSet.Repetition;
import sternhalma.engine.RuleSet.SuicideMoves;
import sternhalma.tablebase.RaceTable;

/** Preconditions, parity, the move limit and constructed verdicts of the S1 criterion. */
class RaceAdjudicationTest {

    private static final RuleSet RULES = RuleSet.defaults(2);

    /**
     * A passed position: player 0 (rows = r) has nine pieces in its goal and one on (q, r); player 1 has nine
     * pieces in its goal and one on cell {@code loCell}.
     */
    static Position nineAndOne(RuleSet rules, int p0Cell, int p1Cell, int sideToMove) {
        Position pos = Position.empty(rules);
        Layout layout = Layout.of(2);
        int free0 = Geometry.id(-2, 5);
        int free1 = Geometry.id(2, -5);
        for (int c : Geometry.cornerCells(layout.goalCorner(0))) {
            if (c != free0) {
                pos.put(0, c);
            }
        }
        for (int c : Geometry.cornerCells(layout.goalCorner(1))) {
            if (c != free1) {
                pos.put(1, c);
            }
        }
        pos.put(0, p0Cell);
        pos.put(1, p1Cell);
        pos.setSideToMove(sideToMove);
        return pos;
    }

    @Test
    void passedIsTheStrictRowSeparationOfTheArmies() {
        assertFalse(RaceAdjudication.passed(Position.initial(RULES)), "the start is not passed");
        Position pos = nineAndOne(RULES, Geometry.id(-2, 4), Geometry.id(2, -4), 0);
        assertTrue(RaceAdjudication.passed(pos));
        // a player 1 piece level with player 0's rearmost piece: not passed
        Position level = nineAndOne(RULES, Geometry.id(-2, 4), Geometry.id(-1, 4), 0);
        assertFalse(RaceAdjudication.passed(level));
        assertFalse(RaceAdjudication.passed(Position.initial(RuleSet.defaults(3))), "three players: never");
        Position missing = Position.empty(RULES).put(0, Geometry.id(0, 3)).put(1, Geometry.id(0, -3));
        assertFalse(RaceAdjudication.passed(missing), "a piece short: not a race position");
    }

    @Test
    void rulePreconditionsAreChecked() {
        assertTrue(RaceAdjudication.applicable(RULES));
        assertTrue(RaceAdjudication.applicable(RULES.withLeaveGoal(LeaveGoal.ALLOWED)));
        assertTrue(RaceAdjudication.applicable(RULES.withBlockadeRule(BlockadeRule.NONE)
                .withSuicideMoves(SuicideMoves.LEGAL)));
        assertFalse(RaceAdjudication.applicable(RuleSet.defaults(3)));
        assertFalse(RaceAdjudication.applicable(RULES.withRepetition(Repetition.REPETITION_IS_DRAW)));
        RaceAdjudication adj = new RaceAdjudication(RaceTable.rows2());
        Position threePlayers = Position.initial(RuleSet.defaults(3));
        assertNull(adj.adjudicate(threePlayers));
        Position drawRule = nineAndOne(RULES.withRepetition(Repetition.REPETITION_IS_DRAW), Geometry.id(-2, 4),
                Geometry.id(2, -4), 0);
        assertNull(adj.adjudicate(drawRule), "repetition draws are outside the proof");
        assertNull(adj.adjudicate(Position.initial(RULES)), "not passed");
    }

    @Test
    void aWonRaceWithTheWinnerToMoveFiresWithTheRightEndPly() {
        // player 0 to move, one step from the win; player 1 needs one move too, but moves second
        Position pos = nineAndOne(RULES, Geometry.id(-2, 4), Geometry.id(2, -4), 0);
        RaceAdjudication adj = new RaceAdjudication(RaceTable.rows2());
        RaceAdjudication.Verdict v = adj.adjudicate(pos);
        assertNotNull(v);
        assertEquals(0, v.winner());
        assertEquals(1, v.winnerMoves());
        assertEquals(1, v.loserBound());
        assertEquals(1, v.endPly(), "ply 0 + 2*1 - 1");
        assertEquals(List.of(Geometry.id(-2, 5)), List.of(v.plan().steps().get(0).to()));
        // the same position with player 1 to move: player 1 wins in one, player 0's bound is 1: U <= LB - 1 fails for 0
        Position other = nineAndOne(RULES, Geometry.id(-2, 4), Geometry.id(2, -4), 1);
        RaceAdjudication.Verdict v1 = adj.adjudicate(other);
        assertNotNull(v1);
        assertEquals(1, v1.winner());
        assertEquals(1, v1.endPly());
    }

    @Test
    void theLoserToMoveNeedsAStrictlyShorterPlanAndTheGapOfThreeRows() {
        // player 0 needs 1 move (piece on (-2,4) next to the free (-2,5)); player 1 needs 2 moves: piece on (0,0)
        // [class (0,0)] with the free goal cell (2,-5) [class (0,1)], not adjacent. Player 1 to move.
        // Player 0's rear row is 4 (the piece on row 4), player 1's front in player 0's rows is 0: gap 4 >= 3.
        Position pos = nineAndOne(RULES, Geometry.id(-2, 4), Geometry.id(0, 0), 1);
        RaceAdjudication adj = new RaceAdjudication(RaceTable.rows2());
        RaceAdjudication.Verdict v = adj.adjudicate(pos);
        assertNotNull(v);
        assertEquals(0, v.winner());
        assertEquals(2, v.loserBound());
        assertEquals(1, v.winnerMoves());
        assertEquals(2, v.endPly(), "ply 0 + 2*1");
        // with player 1's piece on (-1,2) the gap is 2 rows: the lemma does not apply and S1 stays silent,
        // although player 0 still wins the race
        Position close = nineAndOne(RULES, Geometry.id(-2, 4), Geometry.id(-1, 2), 1);
        assertNull(adj.adjudicate(close), "a two-row gap with the loser to move is not provable");
        // and if the loser also needs only one move, nobody is adjudicated with the loser to move
        Position equal = nineAndOne(RULES, Geometry.id(-2, 4), Geometry.id(2, -4), 1);
        RaceAdjudication.Verdict ve = adj.adjudicate(equal);
        assertNotNull(ve);
        assertEquals(1, ve.winner(), "the side to move finishes first");
    }

    @Test
    void theTempoBoundIsStrictWithTheWinnerToMove() {
        // player 0 to move needs two moves ((-2,3) to (-2,4) to the free (-2,5): same class, but no hurdle for a
        // jump); player 1 needs one move ((2,-4) next to the free (2,-5)) and moves second. Player 1 wins the race,
        // but S1 cannot prove it (the loser is to move and U <= LB - 1 fails), and it must not claim player 0:
        // U = 2 > LB = 1.
        Position pos = nineAndOne(RULES, Geometry.id(-2, 3), Geometry.id(2, -4), 0);
        RaceAdjudication adj = new RaceAdjudication(RaceTable.rows2());
        assertNull(adj.adjudicate(pos), "a plan one move longer than the loser's bound must not fire");
        Position play = pos.copy();
        play.make(sternhalma.engine.Move.of(Geometry.id(-2, 3), Geometry.id(-2, 4)));
        assertNull(play.outcome());
        play.make(sternhalma.engine.Move.of(Geometry.id(2, -4), Geometry.id(2, -5)));
        assertEquals(1, play.outcome().winner(), "player 1 does win the race");
        // one row closer, player 0 needs one move and the verdict fires for player 0 with U = LB = 1
        Position won = nineAndOne(RULES, Geometry.id(-2, 4), Geometry.id(2, -4), 0);
        RaceAdjudication.Verdict v = adj.adjudicate(won);
        assertNotNull(v);
        assertEquals(0, v.winner());
        assertEquals(1, v.winnerMoves());
        assertEquals(1, v.loserBound());
    }

    @Test
    void theMoveLimitIsRespected() {
        // player 0 to move and one move from the win; with no ply left the win cannot happen
        Position pos = nineAndOne(RULES.withMoveLimit(1), Geometry.id(-2, 4), Geometry.id(2, -4), 0);
        RaceAdjudication adj = new RaceAdjudication(RaceTable.rows2());
        assertNotNull(adj.adjudicate(pos), "a win on the last allowed ply counts");
        Position tooLate = nineAndOne(RULES.withMoveLimit(1), Geometry.id(-2, 4), Geometry.id(0, 0), 1);
        assertNull(adj.adjudicate(tooLate), "player 0 would win at ply 2, beyond the limit of 1");
        Position justInTime = nineAndOne(RULES.withMoveLimit(2), Geometry.id(-2, 4), Geometry.id(0, 0), 1);
        assertNotNull(adj.adjudicate(justInTime));
    }

    @Test
    void theVerdictIsConservativeWhereTheLemmaDoesNotApplyEvenIfTheRaceIsWon() {
        // player 0 to move with its last piece far back on (0,-3); player 1's last piece on (2,-4) is one step from
        // its free goal cell (2,-5). Player 1 wins the race (player 0 cannot reach (2,-5) in one move), but S1 says
        // nothing: player 0 cannot finish within player 1's bound of 1, and for player 1 as the winner the loser is
        // to move with a gap of only 1 row (player 1's rear row 4 against player 0's front row 3 in player 1's rows).
        Position pos = nineAndOne(RULES, Geometry.id(0, -3), Geometry.id(2, -4), 0);
        assertTrue(RaceAdjudication.passed(pos));
        RaceAdjudication adj = new RaceAdjudication(RaceTable.rows2());
        assertNull(adj.adjudicate(pos));
        // the race is indeed won by player 1 whatever player 0 does
        for (int m : pos.legalMoves()) {
            Position p = pos.copy();
            p.make(m);
            assertNull(p.outcome());
            p.make(sternhalma.engine.Move.of(Geometry.id(2, -4), Geometry.id(2, -5)));
            assertNotNull(p.outcome());
            assertEquals(1, p.outcome().winner());
        }
    }

    @Test
    void theVerifierRejectsATamperedPlan() {
        Position pos = nineAndOne(RULES, Geometry.id(-2, 4), Geometry.id(2, -4), 0);
        RaceAdjudication adj = new RaceAdjudication(RaceTable.rows2());
        RaceAdjudication.Verdict v = adj.adjudicate(pos);
        assertNotNull(v);
        // a plan whose move lands on a cell no chain reaches
        Solitaire.Plan bogus = new Solitaire.Plan(
                List.of(new Solitaire.Step(Geometry.id(-2, 4), new int[] {Geometry.id(-4, 8)})),
                List.of(pos.pieces(0), pos.pieces(0)));
        assertThrows(IllegalStateException.class, () -> RaceAdjudication.verify(pos, 0, v.front(), bogus, true));
        // a plan that is legal but does not end in the goal
        int[] after = pos.pieces(0).clone();
        for (int i = 0; i < after.length; i++) {
            if (after[i] == Geometry.id(-2, 4)) {
                after[i] = Geometry.id(-3, 4);
            }
        }
        java.util.Arrays.sort(after);
        Solitaire.Plan sideways = new Solitaire.Plan(
                List.of(new Solitaire.Step(Geometry.id(-2, 4), new int[] {Geometry.id(-3, 4)})),
                List.of(pos.pieces(0), after));
        assertThrows(IllegalStateException.class, () -> RaceAdjudication.verify(pos, 0, v.front(), sideways, true));
        // the real plan passes
        RaceAdjudication.verify(pos, 0, v.front(), v.plan(), true);
    }

    @Test
    void bothLeaveGoalRulesAndBothBlockadeRulesAreHandled() {
        for (LeaveGoal leaveGoal : LeaveGoal.values()) {
            for (BlockadeRule blockade : BlockadeRule.values()) {
                RuleSet rules = RULES.withLeaveGoal(leaveGoal).withBlockadeRule(blockade);
                Position pos = nineAndOne(rules, Geometry.id(-2, 4), Geometry.id(0, 0), 1);
                RaceAdjudication.Verdict v = new RaceAdjudication(RaceTable.rows2()).adjudicate(pos);
                assertNotNull(v, rules.toString());
                assertEquals(0, v.winner());
                // play the plan against a loser who steps forward; the engine must agree with the verdict
                Position play = pos.copy();
                play.make(play.legalMoves()[0]);
                play.make(v.plan().steps().get(0).move());
                assertNotNull(play.outcome());
                assertEquals(0, play.outcome().winner());
                assertEquals(v.endPly(), play.ply());
            }
        }
    }
}
