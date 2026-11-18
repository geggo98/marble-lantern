package sternhalma.app;

import java.util.List;

import com.vaadin.hilla.BrowserCallable;
import com.vaadin.hilla.exception.EndpointException;
import com.vaadin.flow.server.auth.AnonymousAllowed;

/** The server engines for the online mode. URLs: {@code POST /connect/GameEndpoint/<method>}. */
@BrowserCallable
@AnonymousAllowed
public class GameEndpoint {
    private final Engines engines;

    public GameEndpoint(Engines engines) {
        this.engines = engines;
    }

    public List<ServerEngine> engines() {
        return engines.list();
    }

    public int[] legalMoves(int[] history) {
        try {
            return engines.legalMoves(history);
        } catch (Engines.BadRequest e) {
            throw new EndpointException(e.getMessage());
        }
    }

    public ServerChoice choose(String engineId, int[] history, int nodes) {
        try {
            return engines.choose(engineId, history, nodes);
        } catch (Engines.BadRequest e) {
            throw new EndpointException(e.getMessage());
        }
    }
}
