package sternhalma.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.Test;

import sternhalma.engine.Geometry;
import sternhalma.engine.Layout;
import sternhalma.engine.Move;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.engine.RuleSet.LeaveGoal;
import sternhalma.search.contract.PositionGenerator;
import sternhalma.tablebase.RaceTable;
import sternhalma.tablebase.Rows;

/** Race play returns legal moves only, improves the table count, and falls through where it does not apply. */
class RaceMoveTest {

    private static final RuleSet RULES = RuleSet.defaults(2);

    static boolean legal(Position pos, int move) {
        return Arrays.stream(pos.legalMoves()).anyMatch(m -> m == move);
    }

    /** A random passed position: player 0 on rows {@code >= split + 1}, player 1 on rows {@code <= split}. */
    static Position randomPassed(Random random, int sideToMove, int minSplit, int maxSplit) {
        Layout layout = Layout.of(2);
        int split = random.nextInt(minSplit, maxSplit + 1);
        Position pos = Position.empty(RULES);
        for (int p = 0; p < 2; p++) {
            int placed = 0;
            while (placed < Position.PIECES) {
                int c = random.nextInt(Geometry.CELLS);
                boolean side = p == 0 ? Geometry.r(c) > split : Geometry.r(c) <= split;
                if (layout.allowed(p, c) && side && pos.owner(c) < 0) {
                    pos.put(p, c);
                    placed++;
                }
            }
        }
        pos.setSideToMove(sideToMove);
        return pos;
    }

    @Test
    void everyReturnedMoveIsLegalInTableAndBeamModesOnRandomPassedPositions() {
        Random random = new Random(21);
        RaceMove withTable = new RaceMove(RaceTable.rows2(), 1);
        RaceMove beamOnly = new RaceMove(null, 1);
        RaceMove mode2 = new RaceMove(RaceTable.rows2(), 2);
        int tableMoves = 0;
        int beamMoves = 0;
        int fallThrough = 0;
        for (int i = 0; i < 600; i++) {
            Position pos = randomPassed(random, random.nextInt(2), -3, 3);
            if (pos.outcome() != null || pos.legalMoves().length == 0) {
                continue;
            }
            for (RaceMove rm : new RaceMove[] {withTable, beamOnly, mode2}) {
                int m = rm.choose(pos);
                if (m >= 0) {
                    assertTrue(legal(pos, m), "illegal race move " + Move.toString(m) + " in\n" + pos);
                    if (rm == withTable) {
                        tableMoves++;
                    } else if (rm == beamOnly) {
                        beamMoves++;
                    }
                } else if (rm == withTable) {
                    fallThrough++;
                }
            }
        }
        assertTrue(tableMoves > 400, tableMoves + " table-mode moves");
        assertTrue(beamMoves > 400, beamMoves + " beam-only moves");
        assertTrue(fallThrough < 100, fallThrough + " fall-throughs with the table");
    }

    @Test
    void theTableMoveStrictlyReducesTheCountWhereTheConfigurationIsCovered() {
        Random random = new Random(22);
        RaceTable table = RaceTable.rows2();
        RaceMove rm = new RaceMove(table, 1);
        int checked = 0;
        for (int i = 0; i < 400; i++) {
            Position pos = randomPassed(random, 0, 0, 1);
            if (pos.outcome() != null) {
                continue;
            }
            int[] mine = pos.pieces(0);
            if (!table.covers(mine)) {
                continue;
            }
            int before = table.count(mine);
            int m = rm.choose(pos);
            assertTrue(m >= 0);
            pos.make(m);
            int after = table.count(pos.pieces(0));
            assertTrue(after >= 0, "the chosen move stays in the region");
            // a solitaire move with count - 1 is always legal here (the opponent is below the region); a jump over an
            // opponent piece may even do better than the solitaire can
            assertTrue(after <= before - 1, "count " + before + " -> " + after);
            checked++;
        }
        assertTrue(checked > 100, checked + " covered positions");
    }

    /**
     * Player 1 reaches the table through the player flip ({@link Rows#toPlayer0}): on the mirror image of a covered
     * position it must also play from the table, and its move must be as good as player 0's (same count after the
     * move, same distance gain; the move itself may differ among equals because ties fall back to the move order).
     */
    @Test
    void racePlayUsesTheTableForBothPlayersAndIsSymmetricUnderThePlayerFlip() {
        Random random = new Random(24);
        RaceTable table = RaceTable.rows2();
        Layout layout = Layout.of(2);
        int checked = 0;
        for (int i = 0; i < 300; i++) {
            Position pos = randomPassed(random, 0, 0, 1);
            if (pos.outcome() != null || !table.covers(pos.pieces(0))) {
                continue;
            }
            Position mirror = pos.transformed(Geometry::playerFlip, true);
            assertEquals(1, mirror.sideToMove());
            assertTrue(RaceAdjudication.passed(mirror));
            RaceMove forZero = new RaceMove(table, 1);
            RaceMove forOne = new RaceMove(table, 1);
            int m0 = forZero.choose(pos);
            int m1 = forOne.choose(mirror);
            assertTrue(m0 >= 0 && legal(pos, m0));
            assertTrue(m1 >= 0 && legal(mirror, m1), "player 1 plays a legal race move");
            assertEquals(1, forZero.tableMoves(), "player 0 plays from the table");
            assertEquals(1, forOne.tableMoves(), "player 1 plays from the table");
            assertEquals(0, forOne.planMoves());
            Position after0 = pos.copy();
            after0.make(m0);
            Position after1 = mirror.copy();
            after1.make(m1);
            assertEquals(table.count(after0.pieces(0)), table.count(Rows.toPlayer0(1, after1.pieces(1))),
                    "the same count after the move");
            assertEquals(layout.distance(0, Move.from(m0)) - layout.distance(0, Move.to(m0)),
                    layout.distance(1, Move.from(m1)) - layout.distance(1, Move.to(m1)), "the same distance gain");
            checked++;
        }
        assertTrue(checked > 100, checked + " mirrored positions");
    }

    @Test
    void raceMovesWinTheSolitaireInExactlyTheTableCountAgainstAPassiveOpponent() {
        // the opponent only shuffles far away: race play from a covered configuration must finish in count moves
        Random random = new Random(23);
        RaceTable table = RaceTable.rows2();
        for (int i = 0; i < 50; i++) {
            Position pos = randomPassed(random, 0, 1, 1);
            int[] mine = pos.pieces(0);
            if (pos.outcome() != null || !table.covers(mine)) {
                continue;
            }
            int count = table.count(mine);
            RaceMove racer = new RaceMove(table, 1);
            int moves = 0;
            while (pos.outcome() == null && moves < 40) {
                if (pos.sideToMove() == 0) {
                    int m = racer.choose(pos);
                    assertTrue(m >= 0 && legal(pos, m));
                    pos.make(m);
                    moves++;
                } else {
                    // player 1 moves a rear piece backwards (away from player 0) or anywhere legal
                    int[] legal = pos.legalMoves();
                    int pick = legal[0];
                    for (int m : legal) {
                        if (Rows.row(1, Move.to(m)) > Rows.row(1, Move.from(m))) {
                            pick = m;
                            break;
                        }
                    }
                    pos.make(pick);
                }
            }
            assertTrue(pos.outcome() != null && pos.outcome().hasWinner() && pos.outcome().winner() == 0, pos.toString());
            assertTrue(moves <= count, "race play needs at most the table count of moves: " + moves + " > " + count);
        }
    }

    @Test
    void itFallsThroughWhereItDoesNotApply() {
        RaceMove rm = new RaceMove(RaceTable.rows2(), 1);
        assertEquals(-1, rm.choose(Position.initial(RULES)), "not passed");
        assertEquals(-1, rm.choose(Position.initial(RuleSet.defaults(3))), "three players");
        Position missing = Position.empty(RULES);
        for (int c : Geometry.cornerCells(Layout.of(2).goalCorner(0))) {
            if (c != Geometry.id(-2, 5)) {
                missing.put(0, c);
            }
        }
        missing.put(1, Geometry.id(0, -4));
        assertEquals(-1, rm.choose(missing), "a side with fewer than ten pieces");
        // mode 2 gives up a lost race: player 0 needs 3 moves ((0,2) is neither adjacent to nor in the class of the
        // free (-2,5), so the parity bound is 2, and with no hurdle near the piece the exact distance is 3: two
        // steps and one more step), player 1 needs 1 and moves right after
        Position lost = RaceAdjudicationTest.nineAndOne(RULES, Geometry.id(0, 2), Geometry.id(2, -4), 0);
        assertTrue(RaceAdjudication.passed(lost));
        assertEquals(2, new Solitaire(0, LeaveGoal.FORBIDDEN, null).lowerBound(lost.pieces(0)));
        assertEquals(3, LowerBoundAdmissibilityTest.exactDistance(0, lost.pieces(0), LeaveGoal.FORBIDDEN, 1_000_000));
        assertEquals(3, RaceTable.rows2().count(lost.pieces(0)));
        assertEquals(1, RaceTable.rows2().count(Rows.toPlayer0(1, lost.pieces(1))));
        RaceMove mode2 = new RaceMove(RaceTable.rows2(), 2);
        assertEquals(-1, mode2.choose(lost), "mode 2 lets the search look for a block in a lost race");
        assertTrue(rm.choose(lost) >= 0, "mode 1 races anyway");
        // a won race is played by both modes
        Position won = RaceAdjudicationTest.nineAndOne(RULES, Geometry.id(-2, 4), Geometry.id(0, 0), 0);
        assertTrue(mode2.choose(won) >= 0);
    }

    @Test
    void itRespectsLeaveGoalAndPlaysLegalMovesInEveryContractVariant() {
        for (int variant = 0; variant < PositionGenerator.VARIANTS; variant++) {
            RuleSet rules = PositionGenerator.rules(2, variant);
            RaceMove rm = new RaceMove(RaceTable.rows2(), 1);
            Random random = new Random(variant);
            int played = 0;
            for (int i = 0; i < 100; i++) {
                Position pos = PositionGenerator.played(rules, random.nextLong(), 60 + random.nextInt(60), 0.95);
                if (!RaceAdjudication.passed(pos)) {
                    continue;
                }
                int m = rm.choose(pos);
                if (m >= 0) {
                    assertTrue(legal(pos, m), rules + "\n" + pos);
                    played++;
                }
            }
            assertTrue(played > 0 || variant == 3, "variant " + variant + " reached no passed positions");
        }
        // leaveGoal FORBIDDEN: a piece in the goal never leaves it under race play
        RaceMove rm = new RaceMove(RaceTable.rows2(), 1);
        Random random = new Random(99);
        for (int i = 0; i < 200; i++) {
            Position pos = randomPassed(random, 0, 0, 2);
            if (pos.outcome() != null) {
                continue;
            }
            int m = rm.choose(pos);
            if (m >= 0) {
                int goal = Layout.of(2).goalCorner(0);
                assertFalse(Geometry.cornerOf(Move.from(m)) == goal && Geometry.cornerOf(Move.to(m)) != goal);
            }
        }
        assertEquals(LeaveGoal.FORBIDDEN, RULES.leaveGoal());
    }
}
