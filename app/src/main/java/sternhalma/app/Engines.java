package sternhalma.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.nn.ForwardPass;
import sternhalma.nn.Networks;
import sternhalma.nn.Parameters;
import sternhalma.search.AlphaBetaPlayer;
import sternhalma.search.MctsPlayer;
import sternhalma.search.MctsResult;
import sternhalma.search.MctsSpec;
import sternhalma.search.PlayerSpec;
import sternhalma.search.SearchResult;
import sternhalma.search.Strategies;
import sternhalma.tablebase.RaceTable;

/**
 * The server engines. Every call is stateless: it replays the history from the start position and builds a fresh
 * player, so no transposition table carries over between calls. A semaphore keeps the number of concurrent searches
 * at the number of cores.
 *
 * <p>Properties: {@code sternhalma.app.weights} names a safetensors file for the {@code net} engine;
 * {@code sternhalma.app.backend=tornado} computes its evaluations with TornadoVM (the server must then have started
 * with the TornadoVM flags, {@code devenv tasks run app:run-tornado}).
 */
@Service
public class Engines {
    public static final int MIN_NODES = 1;
    public static final int MAX_NODES = 5_000_000;
    /** Simulations of the network player per node of the budget: nodes / SIMS_DIVISOR, at least 1. */
    static final int SIMS_DIVISOR = 100;
    private static final long WAIT_SECONDS = 30;

    private final Path weights;
    private final boolean tornado;
    private final Semaphore slots = new Semaphore(Runtime.getRuntime().availableProcessors(), true);
    private final Supplier<Boolean> rows1Available = memoize(() -> RaceTable.rows1IfCached() != null);
    private volatile Loaded loaded;

    public Engines(@Value("${sternhalma.app.weights:}") String weights,
            @Value("${sternhalma.app.backend:}") String backend) {
        this.weights = weights == null || weights.isBlank() ? null : Path.of(weights);
        this.tornado = "tornado".equals(backend);
    }

    /** What this server can offer: {@code browser} always, {@code best} with rows1, {@code net} with a weights file. */
    public List<ServerEngine> list() {
        List<ServerEngine> engines = new ArrayList<>();
        engines.add(new ServerEngine("browser", "engine.serverBrowser", "jvm"));
        if (rows1Available.get()) {
            engines.add(new ServerEngine("best", "engine.serverBest", "jvm"));
        }
        if (weights != null && Files.isReadable(weights)) {
            String backend = tornado ? "tornadovm" : Networks.vectorAvailable() ? "jvm-vector" : "jvm";
            engines.add(new ServerEngine("net", "engine.serverNet", backend));
        }
        return engines;
    }

    public int[] legalMoves(int[] history) {
        Position position = replay(history);
        return position.outcome() != null ? new int[0] : position.legalMoves();
    }

    public ServerChoice choose(String engineId, int[] history, int nodes) {
        if (nodes < MIN_NODES || nodes > MAX_NODES) {
            throw new BadRequest("nodes must be in " + MIN_NODES + ".." + MAX_NODES + ", got " + nodes);
        }
        if (list().stream().noneMatch(e -> e.id().equals(engineId))) {
            throw new BadRequest("unknown or unavailable engine: " + engineId);
        }
        Position position = replay(history);
        if (position.outcome() != null || position.legalMoves().length == 0) {
            throw new BadRequest("the game is over after " + history.length + " plies");
        }
        try {
            if (!slots.tryAcquire(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new BadRequest("the server is busy, try again");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BadRequest("interrupted");
        }
        try {
            return "net".equals(engineId) ? searchNet(position, nodes) : searchClassical(engineId, position, nodes);
        } finally {
            slots.release();
        }
    }

    private static ServerChoice searchClassical(String engineId, Position position, int nodes) {
        String base = "best".equals(engineId) ? Strategies.BEST_SPEC : Strategies.BROWSER_SPEC;
        // racerows=1 asks for the rows1 table when the player is created, so the window starts before create().
        int rows1Before = RaceTable.rows1Requests();
        AlphaBetaPlayer player = PlayerSpec.parse(base + ",nodes=" + nodes).create();
        long t0 = System.nanoTime();
        int move = player.choose(position);
        double ms = (System.nanoTime() - t0) / 1e6;
        int rows1 = RaceTable.rows1Requests() - rows1Before;
        SearchResult result = player.lastResult();
        // The counter is global: with concurrent searches the delta can include the other searches' requests.
        // lastResult() is null when the race module chose the move: no search, no score.
        return result == null
                ? new ServerChoice(move, 0, 0, 0, ms, true, rows1)
                : new ServerChoice(move, result.score(), result.depth(), result.nodes(), ms, false, rows1);
    }

    private ServerChoice searchNet(Position position, int nodes) {
        int sims = Math.max(1, nodes / SIMS_DIVISOR);
        MctsSpec spec = MctsSpec.parse("mcts:sims=" + sims + ",batch=1,race=1,racerows=2");
        ForwardPass network = network();
        // TornadoNetwork and VectorNetwork are not thread-safe; a non-thread-safe pass is used by one search at a time.
        Object guard = network.threadSafe() ? new Object() : network;
        synchronized (guard) {
            MctsPlayer player = spec.create(network, 0);
            long t0 = System.nanoTime();
            int move = player.choose(position);
            double ms = (System.nanoTime() - t0) / 1e6;
            MctsResult result = player.lastResult();
            return result == null
                    ? new ServerChoice(move, 0, 0, 0, ms, true, 0)
                    : new ServerChoice(move, (int) Math.round(result.rootValue() * 100), 0, result.nodes(), ms, false, 0);
        }
    }

    /** The loaded weights and, with the TornadoVM backend, the network built from them; published as one unit. */
    private record Loaded(Parameters parameters, ForwardPass tornadoNetwork) {
    }

    private ForwardPass network() {
        Loaded l = loaded;
        if (l == null) {
            synchronized (this) {
                l = loaded;
                if (l == null) {
                    Parameters p;
                    try {
                        p = Parameters.load(weights);
                    } catch (IOException e) {
                        throw new IllegalStateException("cannot read the weights " + weights + ": " + e.getMessage(), e);
                    }
                    // Published only after newTornado succeeded: a failure repeats with its message on the next call.
                    l = new Loaded(p, tornado ? newTornado(p) : null);
                    loaded = l;
                }
            }
        }
        if (tornado) {
            return l.tornadoNetwork();
        }
        // One pass per search: the Vector API pass is not thread-safe. Its construction is cheap next to a search.
        return Networks.fastest(l.parameters());
    }

    private static ForwardPass newTornado(Parameters p) {
        try {
            Class<?> type = Class.forName("sternhalma.tornado.TornadoNetwork");
            return (ForwardPass) type.getConstructor(Parameters.class).newInstance(p);
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IllegalStateException("sternhalma.app.backend=tornado, but TornadoVM does not start: " + e, e);
        }
    }

    private static Position replay(int[] history) {
        if (history == null) {
            throw new BadRequest("history is missing");
        }
        Position position = Position.initial(RuleSet.defaults(2));
        for (int ply = 0; ply < history.length; ply++) {
            int move = history[ply];
            boolean legal = false;
            for (int m : position.legalMoves()) {
                if (m == move) {
                    legal = true;
                    break;
                }
            }
            if (!legal) {
                throw new BadRequest("illegal-history: move " + move + " at ply " + ply + " is not legal");
            }
            position.make(move);
        }
        return position;
    }

    private static <T> Supplier<T> memoize(Supplier<T> supplier) {
        return new Supplier<>() {
            private volatile T value;

            @Override
            public T get() {
                T v = value;
                if (v == null) {
                    synchronized (this) {
                        if (value == null) {
                            value = supplier.get();
                        }
                        v = value;
                    }
                }
                return v;
            }
        };
    }

    /** A request the server rejects; the endpoint turns it into an {@code EndpointException} the client can read. */
    public static final class BadRequest extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public BadRequest(String message) {
            super(message);
        }
    }
}
