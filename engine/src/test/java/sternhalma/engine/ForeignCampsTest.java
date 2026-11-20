package sternhalma.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import sternhalma.engine.RuleSet.ForeignCamps;
import sternhalma.engine.RuleSet.JumpRule;
import sternhalma.engine.RuleSet.LeaveGoal;
import sternhalma.engine.RuleSet.SuicideMoves;

/**
 * The switch {@link ForeignCamps} (docs/rules.md, "Foreign corners"). The hand-made position uses the foreign corner
 * q &gt; 4 of player 0 in a two-player game; the property tests compare the engine with {@link ForeignCampsOracle},
 * which shares no code with the engine.
 */
class ForeignCampsTest {

    private static final RuleSet CLOSED = RuleSet.defaults(2);
    private static final RuleSet PASS = CLOSED.withForeignCamps(ForeignCamps.PASS_THROUGH);
    private static final RuleSet OPEN = CLOSED.withForeignCamps(ForeignCamps.OPEN);

    private static int cell(int q, int r) {
        int id = Geometry.id(q, r);
        assertTrue(id >= 0, "(" + q + "," + r + ") is off the board");
        return id;
    }

    private static Set<Integer> ends(Position pos, int from) {
        Set<Integer> ends = new TreeSet<>();
        for (int move : pos.legalMoves()) {
            if (Move.from(move) == from) {
                assertTrue(ends.add(Move.to(move)), "duplicate move to " + Move.to(move));
            }
        }
        return ends;
    }

    private static Set<Integer> cells(int... qr) {
        Set<Integer> set = new TreeSet<>();
        for (int i = 0; i < qr.length; i += 2) {
            set.add(cell(qr[i], qr[i + 1]));
        }
        return set;
    }

    // ---- the switch itself ----

    @Test
    void closedIsTheDefaultOfEveryConstructor() {
        assertEquals(ForeignCamps.CLOSED, RuleSet.defaults(2).foreignCamps());
        assertEquals(ForeignCamps.CLOSED, RuleSet.defaults(3).foreignCamps());
        RuleSet d = RuleSet.defaults(2);
        assertEquals(ForeignCamps.CLOSED, new RuleSet(2, 200, d.leaveGoal(), d.blockadeRule(), d.suicideMoves(),
                d.blockingStates(), d.repetition(), d.drawHandling(), d.ranking()).foreignCamps());
        assertEquals(ForeignCamps.CLOSED, new RuleSet(2, 200, d.leaveGoal(), d.blockadeRule(), d.suicideMoves(),
                d.blockingStates(), d.repetition(), d.drawHandling(), d.ranking(), JumpRule.LONG).foreignCamps());
    }

    @Test
    void withMethodsKeepTheSwitch() {
        assertEquals(ForeignCamps.OPEN, OPEN.foreignCamps());
        assertEquals(CLOSED, OPEN.withForeignCamps(ForeignCamps.CLOSED));
        for (RuleSet r : new RuleSet[] {OPEN.withMoveLimit(9), OPEN.withLeaveGoal(LeaveGoal.ALLOWED),
                OPEN.withSuicideMoves(SuicideMoves.LEGAL), OPEN.withJumpRule(JumpRule.LONG),
                OPEN.withRanking(RuleSet.Ranking.FULL_RANKING)}) {
            assertEquals(ForeignCamps.OPEN, r.foreignCamps(), r.toString());
        }
        assertTrue(PASS.toString().contains("foreignCamps=PASS_THROUGH"), PASS.toString());
        // the text of a closed rule set is the record text from before the switch (shard metadata and their hashes hold it)
        assertEquals("RuleSet[players=2, moveLimit=200, leaveGoal=FORBIDDEN, blockadeRule=OPPONENT_FILLS, "
                + "suicideMoves=ILLEGAL, blockingStates=NONE, repetition=MOVE_LIMIT, drawHandling=SCORE_BY_DISTANCE, "
                + "ranking=FIRST_WINNER_ONLY, jumpRule=ADJACENT]", CLOSED.toString());
    }

    @Test
    void positionsUnderDifferentSwitchesAreNotEqual() {
        assertNotEquals(Position.initial(CLOSED), Position.initial(PASS));
        assertNotEquals(Position.initial(PASS), Position.initial(OPEN));
        assertEquals(Position.initial(OPEN), Position.initial(OPEN));
        // the hash does not hold the rules (as for the jump rule)
        assertEquals(Position.initial(CLOSED).hash(), Position.initial(OPEN).hash());
    }

    // ---- hand-made position ----

    /**
     * Player 0 on (3,-1); player 1 on (4,-1) and (4,0). The jump over (4,-1) lands on (5,-1) in the foreign corner
     * q &gt; 4; from there the jump over (4,0) lands on (3,1) in the hexagon. (5,0) is off the board.
     */
    private static Position throughTheCorner(RuleSet rules) {
        return Position.empty(rules).put(0, 3, -1).put(1, 4, -1).put(1, 4, 0);
    }

    private static final Set<Integer> STEPS = cells(2, -1, 3, 0, 3, -2, 4, -2, 2, 0);

    @Test
    void closedNeitherEndsNorPassesInAForeignCorner() {
        assertEquals(STEPS, ends(throughTheCorner(CLOSED), cell(3, -1)));
    }

    @Test
    void passThroughReachesTheCellBehindTheCornerButDoesNotEndInIt() {
        Set<Integer> expected = new TreeSet<>(STEPS);
        expected.add(cell(3, 1));
        assertEquals(expected, ends(throughTheCorner(PASS), cell(3, -1)));
    }

    @Test
    void openAlsoEndsInTheCorner() {
        Set<Integer> expected = new TreeSet<>(STEPS);
        expected.add(cell(3, 1));
        expected.add(cell(5, -1));
        assertEquals(expected, ends(throughTheCorner(OPEN), cell(3, -1)));
    }

    @Test
    void aStepIntoAForeignCornerIsAMoveOnlyWhenOpen() {
        // player 0 on (4,-1): the neighbours (5,-1) and (5,-2) are in the corner q > 4
        int from = cell(4, -1);
        for (RuleSet rules : new RuleSet[] {CLOSED, PASS}) {
            Set<Integer> e = ends(Position.empty(rules).put(0, from).put(1, 0, 4), from);
            assertFalse(e.contains(cell(5, -1)) || e.contains(cell(5, -2)), rules.foreignCamps() + ": " + e);
        }
        Set<Integer> open = ends(Position.empty(OPEN).put(0, from).put(1, 0, 4), from);
        assertTrue(open.contains(cell(5, -1)) && open.contains(cell(5, -2)), open.toString());
    }

    @Test
    void leaveGoalStillHoldsUnderOpen() {
        // player 0 in its goal (r > 4) next to the foreign corner q < -4: under OPEN the piece still may not leave the goal
        int from = cell(-1, 5);
        Set<Integer> e = ends(Position.empty(OPEN).put(0, from).put(1, 0, -4), from);
        for (int to : e) {
            assertTrue(Geometry.r(to) > 4, "left the goal to " + to);
        }
        assertFalse(e.isEmpty());
    }

    @Test
    void setupOnAForeignCellOnlyWhenOpen() {
        int foreign = cell(5, -1);
        assertThrows(IllegalArgumentException.class, () -> Position.empty(CLOSED).put(0, foreign));
        assertThrows(IllegalArgumentException.class, () -> Position.empty(PASS).put(0, foreign));
        assertEquals(0, Position.empty(OPEN).put(0, foreign).owner(foreign));
    }

    // ---- distances ----

    @Test
    void foreignCellsHaveTheStepDistanceOverTheWholeBoardAndHomeCellsKeepTheirs() {
        for (int players = 2; players <= 3; players++) {
            Layout layout = Layout.of(players);
            for (int p = 0; p < players; p++) {
                int[] whole = wholeBoardDistance(layout.goalCorner(p));
                for (int c = 0; c < Geometry.CELLS; c++) {
                    if (!layout.allowed(p, c)) {
                        assertEquals(whole[c], layout.distance(p, c), "player " + p + " cell " + c);
                        assertTrue(layout.distance(p, c) > 0 && layout.distance(p, c) <= layout.maxDistance(p));
                    }
                }
            }
        }
        // the home values the engine has always used (2 players: the start tip is 13 steps from the goal)
        assertEquals(13, Layout.of(2).maxDistance(0));
        assertEquals(13, Layout.of(2).maxDistance(1));
    }

    private static int[] wholeBoardDistance(int goalCorner) {
        int[] dist = new int[Geometry.CELLS];
        Arrays.fill(dist, -1);
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int c : Geometry.cornerCells(goalCorner)) {
            dist[c] = 0;
            queue.add(c);
        }
        while (!queue.isEmpty()) {
            int c = queue.poll();
            for (int d = 0; d < 6; d++) {
                int n = Geometry.neighbor(c, d);
                if (n >= 0 && dist[n] < 0) {
                    dist[n] = dist[c] + 1;
                    queue.add(n);
                }
            }
        }
        return dist;
    }

    // ---- property tests against the oracle ----

    /** Seeded random playouts: at every ply the engine's move set equals the oracle's, without duplicates. */
    @Test
    void engineAgreesWithTheOracleOnSeededPlayouts() {
        int foreignEnds = 0;
        int passOnly = 0;
        for (int players = 2; players <= 3; players++) {
            for (JumpRule jump : JumpRule.values()) {
                for (ForeignCamps camps : ForeignCamps.values()) {
                    RuleSet rules = RuleSet.defaults(players).withJumpRule(jump).withForeignCamps(camps)
                            .withLeaveGoal(LeaveGoal.ALLOWED).withSuicideMoves(SuicideMoves.LEGAL).withMoveLimit(1000);
                    for (long seed = 1; seed <= 6; seed++) {
                        SplitMix64 random = new SplitMix64(0xF0E1_0000L + seed * 31 + players * 7 + camps.ordinal());
                        Position pos = Position.initial(rules);
                        for (int ply = 0; ply < 160 && pos.outcome() == null; ply++) {
                            int[] generated = pos.legalMoves();
                            TreeSet<Integer> set = new TreeSet<>();
                            for (int m : generated) {
                                assertTrue(set.add(m), "duplicate " + Move.from(m) + "-" + Move.to(m));
                            }
                            TreeSet<Integer> oracle = ForeignCampsOracle.moves(pos, jump == JumpRule.LONG, camps);
                            assertEquals(oracle, set, rules + " seed " + seed + " ply " + ply + "\n" + pos);
                            for (int m : generated) {
                                boolean home = ForeignCampsOracle.home(players, pos.sideToMove(), Move.to(m));
                                if (!home) {
                                    foreignEnds++;
                                }
                            }
                            if (camps == ForeignCamps.PASS_THROUGH) {
                                passOnly += set.size() - ForeignCampsOracle.moves(pos, jump == JumpRule.LONG,
                                        ForeignCamps.CLOSED).size();
                            }
                            if (generated.length == 0) {
                                break;
                            }
                            long hash = pos.hash();
                            int move = generated[(int) ((random.nextLong() >>> 1) % generated.length)];
                            pos.make(move);
                            pos.unmake();
                            assertEquals(hash, pos.hash());
                            pos.make(move);
                        }
                    }
                }
            }
        }
        assertTrue(foreignEnds > 100, "moves that end on a foreign cell (OPEN only): " + foreignEnds);
        assertTrue(passOnly > 0, "moves that only PASS_THROUGH has: " + passOnly);
    }

    /** CLOSED ⊆ PASS_THROUGH ⊆ OPEN on the same piece placement (default rules otherwise). */
    @Test
    void theThreeMoveSetsAreNested() {
        int strictPass = 0;
        int strictOpen = 0;
        for (int players = 2; players <= 3; players++) {
            for (long seed = 1; seed <= 20; seed++) {
                SplitMix64 random = new SplitMix64(0xAB00L + seed + 100L * players);
                Position pos = Position.initial(RuleSet.defaults(players).withForeignCamps(ForeignCamps.PASS_THROUGH));
                for (int ply = 0; ply < 120 && pos.outcome() == null; ply++) {
                    Set<Integer> closed = moveSet(rebuilt(pos, ForeignCamps.CLOSED));
                    Set<Integer> pass = moveSet(pos);
                    Set<Integer> open = moveSet(rebuilt(pos, ForeignCamps.OPEN));
                    assertTrue(pass.containsAll(closed));
                    assertTrue(open.containsAll(pass));
                    strictPass += pass.size() - closed.size();
                    strictOpen += open.size() - pass.size();
                    int[] moves = pos.legalMoves();
                    if (moves.length == 0) {
                        break;
                    }
                    pos.make(moves[(int) ((random.nextLong() >>> 1) % moves.length)]);
                }
            }
        }
        assertTrue(strictPass > 0 && strictOpen > 0, strictPass + " / " + strictOpen);
    }

    /** No piece rests on a foreign cell in a game under PASS_THROUGH (pieces only end on home cells). */
    @Test
    void passThroughGamesNeverLeaveAPieceOnAForeignCell() {
        for (int players = 2; players <= 3; players++) {
            SplitMix64 random = new SplitMix64(77 + players);
            Position pos = Position.initial(RuleSet.defaults(players).withForeignCamps(ForeignCamps.PASS_THROUGH));
            while (pos.outcome() == null) {
                int[] moves = pos.legalMoves();
                if (moves.length == 0) {
                    break;
                }
                pos.make(moves[(int) ((random.nextLong() >>> 1) % moves.length)]);
                for (int p = 0; p < players; p++) {
                    for (int c : pos.pieces(p)) {
                        assertTrue(ForeignCampsOracle.home(players, p, c), "player " + p + " on " + c);
                    }
                }
            }
        }
    }

    private static Position rebuilt(Position pos, ForeignCamps camps) {
        Position copy = Position.empty(pos.rules().withForeignCamps(camps));
        for (int p = 0; p < pos.rules().players(); p++) {
            for (int c : pos.pieces(p)) {
                copy.put(p, c);
            }
        }
        return copy.setSideToMove(pos.sideToMove());
    }

    private static Set<Integer> moveSet(Position pos) {
        Set<Integer> set = new HashSet<>();
        for (int m : pos.legalMoves()) {
            set.add(m);
        }
        return set;
    }
}
