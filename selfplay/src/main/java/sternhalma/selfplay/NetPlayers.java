package sternhalma.selfplay;

import java.io.IOException;
import java.util.Objects;
import java.util.function.LongFunction;
import java.util.function.Supplier;

import sternhalma.nn.ForwardPass;
import sternhalma.nn.Network;
import sternhalma.nn.Networks;
import sternhalma.engine.Position;
import sternhalma.nn.Parameters;
import sternhalma.nn.PooledEvaluator;
import sternhalma.search.MctsSpec;
import sternhalma.search.Player;

/**
 * The network players of the strength tools, built from text: {@code raw} (the raw policy, {@link RawPolicyPlayer}) or
 * {@code mcts:...} ({@link MctsSpec}: PUCT search with the network). A factory makes one player per game and is safe to
 * call from several threads: the scalar network of {@code raw} is thread-safe, and MCTS players get a forward pass of
 * their thread ({@link Networks#fastest}, the Vector API path if the JVM has the module, else the scalar one).
 */
final class NetPlayers {

    /** The player text of the raw policy. */
    static final String RAW = "raw";

    private NetPlayers() {
    }

    /** Whether the text is a network player ({@code raw} or {@code mcts...}). */
    static boolean isNetPlayer(String text) {
        return text.equals(RAW) || MctsSpec.isMcts(text);
    }

    /**
     * A factory of players of the text; the argument of the factory seeds the player (the noise and the sampling of an
     * MCTS player; the raw policy ignores it).
     *
     * @param own the network the tool has at hand (a checkpoint); used by {@code raw} and by an {@code mcts} spec
     *            without {@code net}; may be null if every spec names its network
     * @throws IllegalArgumentException if the text is not understood or a network is missing or unreadable
     */
    static LongFunction<Player> factory(String text, Parameters own) {
        if (text.equals(RAW)) {
            if (own == null) {
                throw new IllegalArgumentException("raw needs the network of the tool");
            }
            RawPolicyPlayer raw = new RawPolicyPlayer(new Network(own), "raw-policy");
            return seed -> raw;
        }
        MctsSpec spec = MctsSpec.parse(text);
        Parameters parameters = own;
        if (spec.net() != null) {
            try {
                parameters = Parameters.load(spec.net());
            } catch (IOException e) {
                throw new IllegalArgumentException("cannot read the network of " + text + ": " + e.getMessage(), e);
            }
        }
        Objects.requireNonNull(parameters, "mcts spec without net needs the network of the tool");
        Parameters network = parameters;
        ThreadLocal<ForwardPass> perThread = ThreadLocal.withInitial(() -> Networks.fastest(network));
        return seed -> spec.create(perThread.get(), seed);
    }

    /**
     * A factory of MCTS players whose searches take their evaluations from a model of a {@link PooledEvaluator} (the matches
     * of the loop on the device, docs/training.md, "As implemented (block G2)"). Each thread has its own client; a player
     * joins the active games of its cohort for the duration of one move only ({@link Scoped}), so that a batch waits for the
     * searches that are running, not for the games that are waiting for the other side's move.
     *
     * @param model the model of the network; asked on first use (the device opens when the first game starts)
     * @throws IllegalArgumentException if the text is not an {@code mcts} spec without {@code net}, or has a batch above 1
     */
    static LongFunction<Player> onDevice(String text, Supplier<PooledEvaluator.Model> model) {
        if (!runsOnDevice(text)) {
            throw new IllegalArgumentException("a device player is an mcts spec without net and with batch=1, got " + text);
        }
        MctsSpec spec = MctsSpec.parse(text);
        ThreadLocal<PooledEvaluator.Client> mine = ThreadLocal.withInitial(() -> model.get().newClient());
        return seed -> {
            PooledEvaluator.Client client = mine.get();
            return new Scoped(spec.create(client, seed), client);
        };
    }

    /** Whether the text is an MCTS player that can run on the device: no {@code net=} and one leaf per forward pass. */
    static boolean runsOnDevice(String text) {
        if (!MctsSpec.isMcts(text)) {
            return false;
        }
        MctsSpec spec = MctsSpec.parse(text);
        return spec.net() == null && spec.options().batch() == 1;
    }

    /**
     * A player whose moves are chosen between {@code begin} and {@code end} of a pooled client: the client is active in its
     * cohort while it searches and not while the opponent thinks or the other game of the pair is played.
     */
    record Scoped(Player inner, PooledEvaluator.Client client) implements Player {

        @Override
        public int choose(Position position) {
            client.begin();
            try {
                return inner.choose(position);
            } finally {
                client.end();
            }
        }

        @Override
        public String name() {
            return inner.name();
        }
    }

    /** The player a {@link Scoped} wraps, or the player itself (for the statistics of the search). */
    static Player unwrap(Player player) {
        return player instanceof Scoped s ? s.inner() : player;
    }
}
