package sternhalma.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.vaadin.hilla.exception.EndpointException;

import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.nn.NetworkConfig;
import sternhalma.nn.Parameters;

class EnginesTest {
    private static final int[] EMPTY = {};

    private static boolean legalAtStart(int move) {
        return Arrays.stream(Position.initial(RuleSet.defaults(2)).legalMoves()).anyMatch(m -> m == move);
    }

    @Test
    void withoutWeightsTheListHasBrowserAndNoNet() {
        List<ServerEngine> list = new Engines("", "").list();
        assertTrue(list.stream().anyMatch(e -> e.id().equals("browser") && e.backend().equals("jvm")), list.toString());
        assertTrue(list.stream().noneMatch(e -> e.id().equals("net")), list.toString());
    }

    @Test
    void aMissingWeightsFileIsNotOffered() {
        List<ServerEngine> list = new Engines("/nonexistent/weights.safetensors", "").list();
        assertTrue(list.stream().noneMatch(e -> e.id().equals("net")), list.toString());
    }

    @Test
    void browserEngineChoosesALegalMoveFromTheStart() {
        ServerChoice choice = new Engines("", "").choose("browser", EMPTY, 2_000);
        assertTrue(legalAtStart(choice.move()), "move " + choice.move());
        assertEquals(0, choice.rows1Requests(), "the browser engine must not read rows1");
        assertTrue(choice.nodes() > 0 || choice.raceMove());
    }

    @Test
    void bestEngineReportsTheRows1RequestsOfItsOwnSearch() {
        Engines engines = new Engines("", "");
        assumeTrue(engines.list().stream().anyMatch(e -> e.id().equals("best")), "rows1 is not available");
        ServerChoice choice = engines.choose("best", EMPTY, 200);
        assertTrue(choice.rows1Requests() > 0, "rows1Requests " + choice.rows1Requests());
    }

    @Test
    void aFailingTornadoStartRepeatsItsMessageOnEveryCall(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("weights.safetensors");
        Parameters.init(NetworkConfig.tiny(2), 1L).save(file);
        Engines engines = new Engines(file.toString(), "tornado"); // the tornado module is not on the app classpath
        for (int call = 0; call < 2; call++) {
            var e = assertThrows(IllegalStateException.class, () -> engines.choose("net", EMPTY, 100));
            assertTrue(e.getMessage().contains("TornadoVM does not start"), e.getMessage());
        }
    }

    @Test
    void legalMovesAtTheStartAreTheEngineMoves() {
        int[] moves = new Engines("", "").legalMoves(EMPTY);
        assertTrue(moves.length > 0);
        assertTrue(Arrays.stream(moves).allMatch(EnginesTest::legalAtStart));
    }

    @Test
    void anIllegalHistoryIsRejectedByBothCalls() {
        Engines engines = new Engines("", "");
        int illegal = 0; // from cell 0 to cell 0
        assertFalse(legalAtStart(illegal));
        var e1 = assertThrows(Engines.BadRequest.class, () -> engines.choose("browser", new int[] {illegal}, 100));
        assertTrue(e1.getMessage().contains("illegal-history"), e1.getMessage());
        assertThrows(Engines.BadRequest.class, () -> engines.legalMoves(new int[] {illegal}));
    }

    @Test
    void nodeBudgetOutsideTheRangeAndUnknownEnginesAreRejected() {
        Engines engines = new Engines("", "");
        assertThrows(Engines.BadRequest.class, () -> engines.choose("browser", EMPTY, 0));
        assertThrows(Engines.BadRequest.class, () -> engines.choose("browser", EMPTY, Engines.MAX_NODES + 1));
        assertThrows(Engines.BadRequest.class, () -> engines.choose("nope", EMPTY, 100));
        assertThrows(Engines.BadRequest.class, () -> engines.choose("net", EMPTY, 100)); // no weights: not offered
    }

    @Test
    void theSmallestNodeBudgetIsAccepted() {
        ServerChoice choice = new Engines("", "").choose("browser", EMPTY, Engines.MIN_NODES);
        assertTrue(legalAtStart(choice.move()), "move " + choice.move());
    }

    @Test
    void theEndpointTurnsABadRequestIntoAnEndpointException() {
        GameEndpoint endpoint = new GameEndpoint(new Engines("", ""));
        assertThrows(EndpointException.class, () -> endpoint.choose("browser", new int[] {0}, 100));
        assertThrows(EndpointException.class, () -> endpoint.legalMoves(new int[] {0}));
        assertTrue(endpoint.engines().stream().anyMatch(e -> e.id().equals("browser")));
    }

    @Test
    void netEngineIsOfferedWithWeightsAndChoosesALegalMove(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("weights.safetensors");
        Parameters.init(NetworkConfig.tiny(2), 1L).save(file);
        Engines engines = new Engines(file.toString(), "");
        assertTrue(engines.list().stream().anyMatch(e -> e.id().equals("net")), engines.list().toString());
        ServerChoice choice = engines.choose("net", EMPTY, 500);
        assertTrue(legalAtStart(choice.move()), "move " + choice.move());
    }
}
