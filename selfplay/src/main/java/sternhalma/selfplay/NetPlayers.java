package sternhalma.selfplay;

import java.io.IOException;
import java.util.Objects;
import java.util.function.LongFunction;

import sternhalma.nn.ForwardPass;
import sternhalma.nn.Network;
import sternhalma.nn.Networks;
import sternhalma.nn.Parameters;
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
}
