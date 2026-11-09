package sternhalma.search;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

import sternhalma.nn.Encoder;
import sternhalma.nn.ForwardPass;
import sternhalma.nn.NetworkConfig;

/**
 * A fake network for the MCTS tests: the outputs of a position are a function of its encoding alone (a seeded random
 * generator fed with the hash of the input), so that every position gets the same logits and value in any batch, in
 * any order and in any thread; the value is zero-sum, {@code tanh} of a random number. Cheap, so that tests can run
 * thousands of simulations. It records the size of every batch it sees.
 */
final class HashedNetwork implements ForwardPass {

    private final NetworkConfig config;
    private final long salt;
    private final double valueScale;
    private final double policyScale;
    private final List<Integer> batches = new ArrayList<>();
    /** The batch sizes seen so far (tests of one thread only). */
    int evaluations;

    HashedNetwork(int players, long salt) {
        this(players, salt, 1.0);
    }

    HashedNetwork(int players, long salt, double valueScale) {
        this(players, salt, valueScale, 1.0);
    }

    /** @param policyScale 0 gives flat priors, 1 logits in [-2, 2] */
    HashedNetwork(int players, long salt, double valueScale, double policyScale) {
        this.config = NetworkConfig.tiny(players);
        this.salt = salt;
        this.valueScale = valueScale;
        this.policyScale = policyScale;
    }

    @Override
    public NetworkConfig config() {
        return config;
    }

    @Override
    public boolean threadSafe() {
        return true;
    }

    synchronized List<Integer> batches() {
        return List.copyOf(batches);
    }

    @Override
    public void forward(float[] input, int batch, float[] policyLogits, float[] values) {
        synchronized (this) {
            batches.add(batch);
            evaluations += batch;
        }
        int players = config.players();
        for (int b = 0; b < batch; b++) {
            int h = Arrays.hashCode(Arrays.copyOfRange(input, b * Encoder.INPUT_SIZE, (b + 1) * Encoder.INPUT_SIZE));
            SplittableRandom r = new SplittableRandom(salt * 0x9E3779B97F4A7C15L + h);
            for (int i = 0; i < Encoder.POLICY_SIZE; i++) {
                policyLogits[b * Encoder.POLICY_SIZE + i] = (float) ((r.nextDouble() * 4 - 2) * policyScale);
            }
            double v = Math.tanh((r.nextDouble() * 2 - 1) * 2 * valueScale);
            for (int p = 0; p < players; p++) {
                values[b * players + p] = (float) (p == 0 ? v : -v / (players - 1));
            }
        }
    }
}
