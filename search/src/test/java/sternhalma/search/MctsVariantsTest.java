package sternhalma.search;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import sternhalma.engine.Geometry;
import sternhalma.engine.Outcome;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.engine.RuleSet.JumpRule;
import sternhalma.tablebase.RaceTable;

/** The search for three players and for the long hop: no race table, no S1, complete legal games, exact terminals. */
class MctsVariantsTest {

    private static final RuleSet LONG2 = RuleSet.defaults(2).withJumpRule(JumpRule.LONG);

    // ---- race code applies to two players with the adjacent hop only ----

    @Test
    void aPassedPositionOfTheLongHopGetsNoS1VerdictNoRaceMove() {
        Position adjacent = RaceAdjudicationTest.nineAndOne(RuleSet.defaults(2), Geometry.id(-2, 4), Geometry.id(2, -4), 0);
        Position hop = RaceAdjudicationTest.nineAndOne(LONG2, Geometry.id(-2, 4), Geometry.id(2, -4), 0);
        RaceAdjudication adj = new RaceAdjudication(RaceTable.rows2());
        assertNotNull(adj.adjudicate(adjacent), "control: the adjacent rule still fires");
        assertNull(adj.adjudicate(hop));
        assertFalse(RaceAdjudication.applicable(LONG2));
        assertTrue(RaceAdjudication.passed(adjacent));
        assertFalse(RaceAdjudication.passed(hop));
        RaceMove race = new RaceMove(RaceTable.rows2(), 1);
        assertTrue(race.choose(adjacent) >= 0, "control: the adjacent rule gets a race move");
        assertEquals(-1, race.choose(hop));
        assertFalse(RaceEvaluator.rulesApply(LONG2));
    }

    @Test
    void mctsWithTheLongHopDoesNotUseTheRaceMove() {
        Position hop = RaceAdjudicationTest.nineAndOne(LONG2, Geometry.id(-2, 4), Geometry.id(2, -4), 0);
        MctsPlayer player = MctsPlayer.create(new HashedNetwork(2, 2),
                MctsOptions.defaults().withRace(1).withS1(true).withSims(20).withBatch(4), 1, "p");
        player.choose(hop);
        assertEquals(0, player.raceMoves());
        assertNotNull(player.lastResult());
        Position adjacent = RaceAdjudicationTest.nineAndOne(RuleSet.defaults(2), Geometry.id(-2, 4), Geometry.id(2, -4), 0);
        player.choose(adjacent);
        assertEquals(1, player.raceMoves(), "control: the adjacent twin is played by the race module");
    }

    // ---- complete games ----

    private static Position start(int players, JumpRule jump, int moveLimit) {
        return Position.initial(RuleSet.defaults(players).withJumpRule(jump).withMoveLimit(moveLimit));
    }

    private static List<Integer> play(Position start, long seed, MctsOptions options) {
        MctsPlayer[] players = new MctsPlayer[start.rules().players()];
        for (int i = 0; i < players.length; i++) {
            players[i] = MctsPlayer.create(new HashedNetwork(start.rules().players(), 7), options, seed + i, "m" + i);
        }
        Position pos = start.copy();
        List<Integer> moves = new ArrayList<>();
        while (pos.outcome() == null) {
            int m = players[pos.sideToMove()].choose(pos);
            assertTrue(RaceMoveTest.legal(pos, m));
            moves.add(m);
            pos.make(m);
        }
        Outcome o = pos.outcome();
        assertEquals(start.rules().players(), o.values().length);
        double sum = 0;
        for (double v : o.values()) {
            sum += v;
        }
        assertEquals(0.0, sum, 1e-9);
        for (MctsPlayer p : players) {
            assertEquals(0, p.raceMoves());
        }
        return moves;
    }

    private static final MctsOptions GAME = MctsOptions.defaults().withSims(12).withBatch(4);

    @Test
    void threePlayerGamesRunToTheEndInBothJumpRules() {
        for (JumpRule jump : JumpRule.values()) {
            assertEquals(30, play(start(3, jump, 30), 5, GAME).size(), jump + ": ended at the move limit");
        }
    }

    @Test
    void twoPlayerLongGameRunsToTheEnd() {
        assertEquals(40, play(start(2, JumpRule.LONG, 40), 9, GAME).size());
    }

    @Test
    void aFixedSeedGivesTheSameGame() {
        MctsOptions noisy = GAME.withNoise(0.25).withTemperature(1.0);
        for (int players = 2; players <= 3; players++) {
            Position s = start(players, JumpRule.LONG, 24);
            assertEquals(play(s, 3, noisy), play(s, 3, noisy));
        }
    }

    // ---- terminal values ----

    @Test
    void terminalValuesAtTheMoveLimitAreTheOutcomeVectorOfAllPlayers() {
        for (int players = 2; players <= 3; players++) {
            for (JumpRule jump : JumpRule.values()) {
                Position p = start(players, jump, 6);
                Mcts mcts = new Mcts(new HashedNetwork(players, 1), GAME.withSims(200).withBatch(1), 1);
                for (int i = 0; i < 5; i++) {
                    p.make(p.legalMoves()[0]);
                }
                MctsResult r = mcts.search(p);
                MctsNode root = mcts.root();
                int checked = 0;
                for (int e = 0; e < root.moves.length; e++) {
                    Position q = p.copy();
                    q.make(root.moves[e]);
                    assertNotNull(q.outcome(), "the move limit ends the game");
                    if (root.child[e] != null) {
                        assertArrayEquals(q.outcome().values(), root.child[e].exact, players + " " + jump);
                        checked++;
                    }
                }
                assertTrue(checked > 0 && r.sims() == 200);
            }
        }
    }
}
