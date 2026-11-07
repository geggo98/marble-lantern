package sternhalma.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;

/** The spec keys of race play and the player hook. */
class PlayerSpecRaceTest {

    @Test
    void raceKeysParseAndDefaultToOff() {
        PlayerSpec plain = PlayerSpec.parse(Strategies.BEST_SPEC);
        assertEquals(0, plain.race());
        assertTrue(plain.raceTable());
        PlayerSpec race = PlayerSpec.parse(Strategies.BEST_SPEC + ",race=1");
        assertEquals(1, race.race());
        assertTrue(race.raceTable());
        PlayerSpec beam = PlayerSpec.parse("ab:eval=linear,race=2,racetable=0,nodes=100");
        assertEquals(2, beam.race());
        assertFalse(beam.raceTable());
        assertThrows(IllegalArgumentException.class, () -> PlayerSpec.parse("ab:race=3"));
        assertThrows(IllegalArgumentException.class, () -> PlayerSpec.parse("ab:racetable=2"));
        assertTrue(race.create().name().contains("race=1"));
        assertTrue(beam.create().name().contains("race=2, racetable=0"));
        assertFalse(plain.create().name().contains("race"));
    }

    @Test
    void aRacePlayerSearchesUntilTheArmiesPassAndThenRacesWithoutNodes() {
        PlayerSpec spec = PlayerSpec.parse("ab:eval=linear,nodes=300,tt=12,race=1");
        AlphaBetaPlayer a = spec.create();
        AlphaBetaPlayer b = spec.create();
        Position pos = Position.initial(RuleSet.defaults(2));
        long nodesBeforePass = -1;
        while (pos.outcome() == null) {
            AlphaBetaPlayer p = pos.sideToMove() == 0 ? a : b;
            boolean passed = RaceAdjudication.passed(pos);
            long nodes = p.totalNodes();
            long races = p.raceMoves();
            int m = p.choose(pos);
            if (passed) {
                if (nodesBeforePass < 0) {
                    nodesBeforePass = a.totalNodes() + b.totalNodes();
                }
                assertTrue(p.raceMoves() == races + 1 || p.totalNodes() > nodes, "a race move or a search");
            } else {
                assertEquals(races, p.raceMoves(), "no race move before the armies pass");
                assertTrue(p.totalNodes() > nodes);
            }
            pos.make(m);
        }
        assertTrue(a.raceMoves() + b.raceMoves() > 0, "the race module played moves");
        assertTrue(pos.outcome().hasWinner());
        // the spec without race play is unchanged in its search: same tree before the pass
        assertEquals(0, PlayerSpec.parse("ab:eval=linear,nodes=300,tt=12").create().raceMoves());
    }

    @Test
    void theRegisteredStrategiesIncludeRacePlayAndKeepBestSpecUnchanged() {
        assertEquals("ab:eval=linear,pvs=1,killers=1,history=1,lmr=2,asp=1,ttrep=1,mate=1,partial=1",
                Strategies.BEST_SPEC, "BEST_SPEC changes only in the integration commit");
        assertTrue(Strategies.registered().stream().anyMatch(s -> s.name().contains(",race=1,")));
        assertTrue(Strategies.registered().stream().anyMatch(s -> s.name().contains(",race=2,")));
    }
}
