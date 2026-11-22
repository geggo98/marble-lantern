package sternhalma.selfplay;

import java.util.Objects;

import sternhalma.engine.Move;
import sternhalma.engine.Position;
import sternhalma.nn.Encoder;
import sternhalma.nn.ForwardPass;
import sternhalma.nn.Orientation;
import sternhalma.nn.PlaneSet;
import sternhalma.search.Player;

/**
 * The raw policy of a network as a player (no search): the legal move with the highest masked policy logit, the first
 * one in {@code Position.legalMoves()} order if several are equal. Deterministic, two players (a network of another player count is not meant for it),
 * and it can only return a legal move, because it picks from the legal moves. A non-finite logit of a legal move means broken weights and raises
 * {@link IllegalStateException}, as in {@code Evaluator}.
 *
 * <p>Thread-safe when the network is ({@link ForwardPass#threadSafe()}): all scratch arrays are per call.
 */
public final class RawPolicyPlayer implements Player {

    private final ForwardPass network;
    private final String name;

    public RawPolicyPlayer(ForwardPass network, String name) {
        this.network = Objects.requireNonNull(network, "network");
        this.name = Objects.requireNonNull(name, "name");
    }

    @Override
    public int choose(Position position) {
        int[] legal = position.legalMoves();
        if (legal.length == 0) {
            throw new IllegalArgumentException("the position has no legal move");
        }
        if (!network.config().supports(position.rules().triangleRows())) {
            throw new IllegalArgumentException("the network is for triangle_rows="
                    + sternhalma.nn.NetworkConfig.rowsText(network.config().rows()) + ", the position has "
                    + position.rules().triangleRows().rows() + " rows");
        }
        PlaneSet planes = network.config().planes();
        float[] input = new float[planes.inputSize()];
        Encoder.encode(planes, position, input, 0);
        float[] policy = new float[Encoder.POLICY_SIZE];
        float[] values = new float[network.config().players()];
        network.forward(input, 1, policy, values);
        Orientation orientation = Orientation.of(position);
        int best = -1;
        double bestLogit = Double.NEGATIVE_INFINITY;
        for (int move : legal) {
            double logit = Encoder.moveLogit(orientation, policy, 0, move);
            if (!Double.isFinite(logit)) {
                throw new IllegalStateException("network output is not finite: logit " + logit + " of move "
                        + Move.toString(move));
            }
            if (best < 0 || logit > bestLogit) {
                best = move;
                bestLogit = logit;
            }
        }
        return best;
    }

    @Override
    public String name() {
        return name;
    }
}
