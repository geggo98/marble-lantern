package sternhalma.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.engine.RuleSet.JumpRule;

/**
 * The spec key {@code jump=long}: parsing, the rejected combinations (the race table and the race arithmetic count
 * single jumps only), the guard against a position of another rule, and a whole long-hop game with legal moves.
 */
class PlayerSpecJumpTest {

    private static final RuleSet LONG = RuleSet.defaults(2).withJumpRule(JumpRule.LONG);

    @Test
    void jumpKeyParsesAndDefaultsToAdjacent() {
        assertEquals(JumpRule.ADJACENT, PlayerSpec.parse("ab:eval=linear").jumpRule());
        assertEquals(JumpRule.ADJACENT, PlayerSpec.parse("ab:eval=linear,jump=adjacent").jumpRule());
        assertEquals(JumpRule.LONG, PlayerSpec.parse("ab:eval=linear,jump=long").jumpRule());
        assertThrows(IllegalArgumentException.class, () -> PlayerSpec.parse("ab:jump=far"));
        // the old constructors keep their meaning
        assertEquals(JumpRule.ADJACENT, new PlayerSpec("t", "distance", 10, 3, Long.MAX_VALUE, 12, SearchOptions.DEFAULT).jumpRule());
    }

    @Test
    void longJumpIsRejectedWithRacePlay() {
        for (String race : new String[] {"race=1", "race=2", "race=2,racetable=0", "race=1,racerows=2"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> PlayerSpec.parse("ab:eval=linear,jump=long," + race), race);
            assertTrue(e.getMessage().contains("jump=long") && e.getMessage().contains("race"), e.getMessage());
        }
        // the same specs without jump=long are fine
        assertEquals(1, PlayerSpec.parse("ab:eval=linear,race=1").race());
    }

    @Test
    void longJumpIsRejectedWithRaceEvaluators() {
        for (String eval : new String[] {"racelin", "racelin1", "racelin-r3", "racelin1-r3", "racelin:linear",
                "racelin1:linear-r3"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> PlayerSpec.parse("ab:eval=" + eval + ",jump=long"), eval);
            assertTrue(e.getMessage().contains("eval=" + eval), e.getMessage());
        }
        // the base evaluators are accepted
        for (String eval : new String[] {"distance", "linear", "linear-r3", "stage4"}) {
            PlayerSpec spec = PlayerSpec.parse("ab:eval=" + eval + ",jump=long" + (eval.equals("stage4") ? ",ws=15" : ""));
            assertEquals(JumpRule.LONG, spec.jumpRule());
        }
    }

    @Test
    void browserLongSpecIsTheBrowserSpecWithoutRaceAndWithLongHops() {
        PlayerSpec spec = PlayerSpec.parse(Strategies.BROWSER_LONG_SPEC);
        PlayerSpec browser = PlayerSpec.parse(Strategies.BROWSER_SPEC);
        assertEquals(JumpRule.LONG, spec.jumpRule());
        assertEquals(0, spec.race());
        assertEquals(browser.options(), spec.options(), "the search enhancements are those of BROWSER_SPEC");
        assertEquals(browser.nodes(), spec.nodes());
        assertEquals(browser.depth(), spec.depth());
        assertEquals("linear-r3", spec.evaluator(), "the base evaluator of racelin-r3");
        assertFalse(Evaluators.usesRaceTable(spec.evaluator()));
    }

    @Test
    void aLongSpecPlaysAWholeLongHopGameWithLegalMoves() {
        PlayerSpec spec = PlayerSpec.parse(Strategies.BROWSER_LONG_SPEC + ",nodes=300,tt=12");
        for (int seed = 1; seed <= 2; seed++) {
            AlphaBetaPlayer ab = spec.create();
            RandomPlayer random = new RandomPlayer(seed);
            Player[] players = seed == 1 ? new Player[] {ab, random} : new Player[] {random, ab};
            // GameRunner rejects an illegal move with IllegalMoveException
            GameRunner.Result result = GameRunner.play(Position.initial(LONG), players);
            assertTrue(result.plies() > 0);
            assertTrue(ab.name().contains("jump=long"), ab.name());
            assertTrue(ab.totalNodes() > 0);
            assertTrue(result.outcome().hasWinner() && result.outcome().winner() == (seed == 1 ? 0 : 1),
                    "the search beats a random player, game " + seed);
        }
    }

    @Test
    void aPlayerRefusesAPositionOfAnotherJumpRule() {
        AlphaBetaPlayer longPlayer = PlayerSpec.parse("ab:eval=linear,jump=long,nodes=50,tt=10").create();
        AlphaBetaPlayer adjacentPlayer = PlayerSpec.parse("ab:eval=linear,nodes=50,tt=10").create();
        Position adjacent = Position.initial(RuleSet.defaults(2));
        Position longHop = Position.initial(LONG);
        assertThrows(IllegalArgumentException.class, () -> longPlayer.choose(adjacent));
        assertThrows(IllegalArgumentException.class, () -> adjacentPlayer.choose(longHop));
        assertTrue(Position.initial(LONG).legalMoves().length > 0);
        longPlayer.choose(longHop);
        adjacentPlayer.choose(adjacent);
    }

    @Test
    void aRaceMoveCannotBeBuiltForLongHops() {
        assertThrows(IllegalArgumentException.class, () -> new AlphaBetaPlayer(new DistanceEvaluator(), 3,
                Long.MAX_VALUE, 100, 10, "distance", SearchOptions.DEFAULT, new RaceMove(null, 1), JumpRule.LONG));
    }
}
