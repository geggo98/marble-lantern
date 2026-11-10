package sternhalma.selfplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.LongFunction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sternhalma.engine.Position;
import sternhalma.nn.NetworkConfig;
import sternhalma.nn.Parameters;
import sternhalma.search.MctsPlayer;
import sternhalma.search.Player;

/** The text forms of the network players of the strength tools. */
class NetPlayersTest {

    @TempDir
    Path dir;

    @Test
    void recognisesRawAndMctsOnly() {
        assertTrue(NetPlayers.isNetPlayer("raw"));
        assertTrue(NetPlayers.isNetPlayer("mcts:sims=3"));
        assertTrue(NetPlayers.isNetPlayer("mcts"));
        assertEquals(false, NetPlayers.isNetPlayer("ab:nodes=3"));
        assertEquals(false, NetPlayers.isNetPlayer("raw-policy"));
    }

    @Test
    void rawAndMctsWithoutNetUseTheNetworkOfTheTool() {
        Parameters own = Parameters.init(NetworkConfig.tiny(2), 1);
        assertInstanceOf(RawPolicyPlayer.class, NetPlayers.factory("raw", own).apply(0));
        MctsPlayer mcts = (MctsPlayer) NetPlayers.factory("mcts:sims=5,race=0", own).apply(0);
        assertEquals(5, mcts.mcts().options().sims());
        assertThrows(IllegalArgumentException.class, () -> NetPlayers.factory("raw", null));
        assertThrows(NullPointerException.class, () -> NetPlayers.factory("mcts:sims=5", null));
    }

    @Test
    void aSpecWithNetLoadsThatFileAndRefusesAMissingOne() throws IOException {
        Path file = dir.resolve("n.safetensors");
        Parameters.init(NetworkConfig.tiny(2), 9).save(file);
        Position start = Position.initial(sternhalma.engine.RuleSet.defaults(2));
        Parameters other = Parameters.init(NetworkConfig.tiny(2), 2);
        int viaFile = NetPlayers.factory("mcts:net=" + file + ",sims=20,race=0", other).apply(0).choose(start);
        int direct = NetPlayers.factory("mcts:sims=20,race=0", Parameters.init(NetworkConfig.tiny(2), 9)).apply(0).choose(start);
        assertEquals(direct, viaFile, "the file's network, not the tool's");
        assertThrows(IllegalArgumentException.class,
                () -> NetPlayers.factory("mcts:net=" + dir.resolve("missing.safetensors"), other));
    }

    @Test
    void playersOfSeveralThreadsGetAForwardPassOfTheirOwnAndTheSameMoves() throws Exception {
        Parameters own = Parameters.init(NetworkConfig.small(2), 4);
        LongFunction<Player> factory = NetPlayers.factory("mcts:sims=12,batch=4,race=0", own);
        Position start = Position.initial(sternhalma.engine.RuleSet.defaults(2));
        int expected = factory.apply(0).choose(start);
        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                futures.add(pool.submit(() -> factory.apply(0).choose(start)));
            }
            for (Future<Integer> f : futures) {
                assertEquals(expected, f.get(), "a shared vector network would throw or mix its buffers");
            }
        }
    }

    /** E11: a network file with classical planes and priors loads and plays, as MCTS and as raw policy. */
    @Test
    void aClassicalNetworkFileLoadsAndPlays() throws IOException {
        Path file = dir.resolve("c.safetensors");
        Parameters.init(NetworkConfig.tiny(2).withPlanes(sternhalma.nn.PlaneSet.CLASSIC1), 9).save(file);
        Position start = Position.initial(sternhalma.engine.RuleSet.defaults(2));
        int mcts = NetPlayers.factory("mcts:net=" + file + ",sims=20,race=0", null).apply(0).choose(start);
        int raw = NetPlayers.factory("raw", Parameters.load(file)).apply(0).choose(start);
        assertTrue(java.util.Arrays.stream(start.legalMoves()).anyMatch(m -> m == mcts));
        assertTrue(java.util.Arrays.stream(start.legalMoves()).anyMatch(m -> m == raw));
    }
}
