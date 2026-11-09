package sternhalma.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** The text form of an MCTS player and the checks of its options. */
class MctsSpecTest {

    @Test
    void parsesEveryKeyAndKeepsTheDefaultsOtherwise() {
        MctsSpec spec = MctsSpec.parse("mcts:net=a/b.safetensors,sims=25,batch=8,cpuct=2.5,ptemp=1.5,noise=0.25,alpha=0.2,"
                + "temp=0.7,fpu=0.1,race=2,racerows=1,s1=0,win1=0,reuse=1,ms=500");
        assertEquals(Path.of("a/b.safetensors"), spec.net());
        MctsOptions o = spec.options();
        assertEquals(new MctsOptions(25, 8, 2.5, 1.5, 0.25, 0.2, 0.7, 0.1, 2, 1, false, false, true, 500), o);
        assertEquals(null, MctsSpec.parse("mcts").net(), "the network at hand");
        assertEquals(MctsOptions.defaults().withSims(7), MctsSpec.parse("mcts:sims=7").options());
        MctsOptions d = MctsSpec.parse("mcts:net=x").options();
        assertEquals(MctsOptions.defaults(), d);
        assertEquals(100, d.sims());
        assertEquals(1, d.batch());
        assertEquals(1.0, d.cpuct());
        assertEquals(1, d.race());
        assertEquals(0, d.noise());
        assertEquals(0, d.temperature());
        assertEquals(Long.MAX_VALUE, d.millis());
    }

    @Test
    void refusesWhatItDoesNotUnderstand() {
        assertThrows(IllegalArgumentException.class, () -> MctsSpec.parse("ab:nodes=10"));
        assertThrows(IllegalArgumentException.class, () -> MctsSpec.parse("mcts:net=,sims=10"), "empty network");
        assertThrows(IllegalArgumentException.class, () -> MctsSpec.parse("mcts:net=x,bogus=1"));
        assertThrows(IllegalArgumentException.class, () -> MctsSpec.parse("mcts:net=x,sims"));
        assertThrows(IllegalArgumentException.class, () -> MctsSpec.parse("mcts:net=x,sims=abc"));
        assertThrows(IllegalArgumentException.class, () -> MctsSpec.parse("mcts:net=x,sims=0"));
        assertThrows(IllegalArgumentException.class, () -> MctsSpec.parse("mcts:net=x,sims=10,sims=20"));
        assertThrows(IllegalArgumentException.class, () -> MctsSpec.parse("mcts:net=x,batch=65"));
        assertThrows(IllegalArgumentException.class, () -> MctsSpec.parse("mcts:net=x,batch=0"));
        assertThrows(IllegalArgumentException.class, () -> MctsSpec.parse("mcts:net=x,noise=1.5"));
        assertThrows(IllegalArgumentException.class, () -> MctsSpec.parse("mcts:net=x,cpuct=-1"));
        assertThrows(IllegalArgumentException.class, () -> MctsSpec.parse("mcts:net=x,ptemp=0"));
        assertThrows(IllegalArgumentException.class, () -> MctsSpec.parse("mcts:net=x,temp=-0.5"));
        assertThrows(IllegalArgumentException.class, () -> MctsSpec.parse("mcts:net=x,race=3"));
        assertThrows(IllegalArgumentException.class, () -> MctsSpec.parse("mcts:net=x,race=0,racerows=1"));
        assertThrows(IllegalArgumentException.class, () -> MctsSpec.parse("mcts:net=x,racerows=3"));
        assertThrows(IllegalArgumentException.class, () -> MctsSpec.parse("mcts:net=x,s1=2"));
        assertThrows(IllegalArgumentException.class, () -> MctsSpec.parse("mcts:net=x,ms=0"));
        assertThrows(IllegalArgumentException.class, () -> MctsSpec.parse("mcts:net=x,cpuct=NaN"));
    }

    @Test
    void recognisesTheKind() {
        assertTrue(MctsSpec.isMcts("mcts:net=x"));
        assertTrue(MctsSpec.isMcts("mcts"));
        assertFalse(MctsSpec.isMcts("ab:nodes=1"));
        assertFalse(MctsSpec.isMcts("raw"));
    }

    @Test
    void aPlayerFromASpecCarriesItsTextAsItsName() {
        MctsSpec spec = MctsSpec.parse("mcts:net=x,sims=5,race=0");
        MctsPlayer player = spec.create(new HashedNetwork(2, 1), 7);
        assertEquals("mcts:net=x,sims=5,race=0", player.name());
        assertEquals(5, player.mcts().options().sims());
        assertTrue(player.mcts().options().s1());
    }
}
