package sternhalma.nn;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import sternhalma.engine.Move;
import sternhalma.engine.Position;

/**
 * Turns positions into {@link Evaluation}s with a {@link ForwardPass}: the reference {@link Network} or a fast
 * path such as {@link VectorNetwork} (docs/network.md, "Policy head and move logits", "Value head").
 *
 * <p>For each position: encode it ({@link Encoder#encode}), run the network, score every legal move with
 * {@link Encoder#moveLogit}, take the softmax over the legal moves (subtract the largest logit, sum in
 * double), and reorder the values from relative to absolute players: the value of absolute player a is
 * output {@code (a - mover) mod players}.
 *
 * <p>A network of {@link PlaneSet#CLASSIC1V} serves positions of two and three players: its value head has three
 * outputs, and a position of n players takes the first n of them (relative players 0 to n - 1). Every other network
 * accepts only positions with its own player count.
 *
 * <p>The evaluator does not check whether the game is over; the caller asks {@code Position.outcome()}
 * first.
 *
 * <p><b>Non-finite outputs.</b> A move logit is the sum of two float logits, added in double, so finite
 * network outputs always give finite priors that sum to 1. If the logit of a legal move is infinite or NaN,
 * or a value is NaN, {@link #evaluate} throws {@link IllegalStateException}: such outputs mean broken
 * weights, and NaN priors must not reach a search. Logits of cells that no legal move uses are not checked.
 *
 * <p><b>Threads.</b> The evaluator itself has no mutable state. It is safe to share between threads exactly
 * when its network is ({@link ForwardPass#threadSafe()}): {@link Network} is, {@link VectorNetwork} is not.
 * A {@code VectorNetwork} detects a concurrent call and throws {@link IllegalStateException} instead of
 * returning wrong values; use one evaluator with its own {@code VectorNetwork} per thread.
 */
public final class Evaluator {

    private final ForwardPass network;
    private final NetworkConfig config;

    /**
     * @throws NullPointerException if network is null
     */
    public Evaluator(ForwardPass network) {
        this(Objects.requireNonNull(network, "network"), network.config());
    }

    /**
     * An evaluator without a network, for a caller that runs the forward pass elsewhere (the browser engine): only
     * {@link #encode} and {@link #decode} work, {@link #evaluate} throws {@link IllegalStateException}.
     *
     * @throws NullPointerException if config is null
     */
    public static Evaluator withoutNetwork(NetworkConfig config) {
        return new Evaluator(null, Objects.requireNonNull(config, "config"));
    }

    private Evaluator(ForwardPass network, NetworkConfig config) {
        this.network = network;
        this.config = config;
    }

    /**
     * Evaluates one position. The position is not changed.
     *
     * @throws IllegalArgumentException if the position's player count differs from the network's, or, for a network of
     *                                  {@link PlaneSet#CLASSIC1V}, is not 2 or 3
     */
    public Evaluation evaluate(Position position) {
        return evaluate(List.of(position)).getFirst();
    }

    /**
     * Evaluates positions in one forward pass. Result i belongs to position i and equals
     * {@code evaluate(positions.get(i))} within the tolerance of docs/network.md. The positions are not
     * changed.
     *
     * @throws IllegalArgumentException if the list is empty or a position's player count differs from the
     *                                  network's
     * @throws IllegalStateException    if the network gives a non-finite logit for a legal move or a NaN
     *                                  value, or (for a network that is not thread-safe) another thread is
     *                                  inside the forward pass
     */
    public List<Evaluation> evaluate(List<Position> positions) {
        if (network == null) {
            throw new IllegalStateException("this evaluator has no network; use encode and decode");
        }
        float[] input = encode(positions);
        int batch = positions.size();
        float[] policy = new float[batch * Encoder.POLICY_SIZE];
        float[] values = new float[batch * config.players()];
        network.forward(input, batch, policy, values);
        return decode(positions, policy, values);
    }

    /**
     * The input of {@link ForwardPass#forward} for the positions: {@code batch * inputSize} floats, position b at
     * {@code b * inputSize}. The first half of {@link #evaluate}.
     *
     * @throws IllegalArgumentException as {@link #evaluate}
     */
    public float[] encode(List<Position> positions) {
        int netPlayers = config.players();
        boolean variants = config.planes().variants();
        int batch = positions.size();
        if (batch == 0) {
            throw new IllegalArgumentException("no positions to evaluate");
        }
        PlaneSet planes = config.planes();
        int inputSize = planes.inputSize();
        float[] input = new float[batch * inputSize];
        for (int b = 0; b < batch; b++) {
            Position position = positions.get(b);
            int positionPlayers = position.rules().players();
            if (variants ? positionPlayers != 2 && positionPlayers != 3 : positionPlayers != netPlayers) {
                throw new IllegalArgumentException("position " + b + " has " + positionPlayers
                        + " players, the network has " + netPlayers + (variants ? " (serves 2 and 3)" : ""));
            }
            Encoder.encode(planes, position, input, b * inputSize);
        }
        return input;
    }

    /**
     * Turns the raw outputs of a forward pass into evaluations: the softmax over the legal moves and the values in
     * absolute order. The second half of {@link #evaluate}.
     *
     * @param policy {@code batch * Encoder.POLICY_SIZE} floats, the logits of the forward pass
     * @param values {@code batch * config().players()} floats, relative players
     * @throws IllegalArgumentException if an array is too short for the batch
     * @throws IllegalStateException    as {@link #evaluate}
     */
    public List<Evaluation> decode(List<Position> positions, float[] policy, float[] values) {
        int netPlayers = config.players();
        int batch = positions.size();
        if (policy.length < batch * Encoder.POLICY_SIZE || values.length < batch * netPlayers) {
            throw new IllegalArgumentException("outputs too short for " + batch + " positions: policy " + policy.length
                    + ", values " + values.length);
        }
        List<Evaluation> result = new ArrayList<>(batch);
        for (int b = 0; b < batch; b++) {
            Position position = positions.get(b);
            int[] moves = position.legalMoves();
            float[] priors = priors(Orientation.of(position), policy, b * Encoder.POLICY_SIZE, moves);
            int mover = position.sideToMove();
            int players = position.rules().players();
            float[] absolute = new float[players];
            for (int a = 0; a < players; a++) {
                absolute[a] = values[b * netPlayers + Math.floorMod(a - mover, players)];
                if (Float.isNaN(absolute[a])) {
                    throw new IllegalStateException("network output is NaN: value of player " + a
                            + " in position " + b);
                }
            }
            result.add(new Evaluation(moves, priors, absolute));
        }
        return result;
    }

    /**
     * Softmax of the move logits over the given moves only, in double; empty for no moves.
     *
     * @throws IllegalStateException if the logit of a given move is infinite or NaN
     */
    static float[] priors(Orientation orientation, float[] policy, int offset, int[] moves) {
        float[] priors = new float[moves.length];
        if (moves.length == 0) {
            return priors;
        }
        double[] logits = new double[moves.length];
        double max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < moves.length; i++) {
            // In double, so the sum of two finite float logits is finite (docs/network.md).
            logits[i] = Encoder.moveLogit(orientation, policy, offset, moves[i]);
            if (!Double.isFinite(logits[i])) {
                throw new IllegalStateException("network output is not finite: logit " + logits[i] + " of move "
                        + Move.toString(moves[i]));
            }
            max = Math.max(max, logits[i]);
        }
        double sum = 0;
        for (int i = 0; i < moves.length; i++) {
            logits[i] = Math.exp(logits[i] - max);
            sum += logits[i];
        }
        for (int i = 0; i < moves.length; i++) {
            priors[i] = (float) (logits[i] / sum);
        }
        return priors;
    }
}
