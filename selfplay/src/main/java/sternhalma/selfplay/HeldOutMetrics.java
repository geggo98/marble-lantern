package sternhalma.selfplay;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import sternhalma.nn.Encoder;
import sternhalma.nn.ForwardPass;
import sternhalma.nn.ReferenceLoss;
import sternhalma.nn.TrainingBatch;

/**
 * The cheap strength proxies (c1) on a fixed held-out set (docs/training.md, "Run monitor"): policy KL to the teacher
 * target, top-1 agreement with the teacher, value loss and winner prediction.
 *
 * <ul>
 *   <li><b>KL</b>: {@code sum(wp * KL) / sum(wp)} over the samples with a policy weight, with
 *       {@code KL = CE(target, softmax over the legal moves) - H(target)}; random opening plies (wp 0) do not count.</li>
 *   <li><b>Top-1</b>: the share (weighted by wp) of samples whose highest-logit legal move is the highest-target move
 *       (the first one of equal targets).</li>
 *   <li><b>Value loss</b>: {@code sum(wv * MSE) / sum(wv)} against the value target, as in the training loss.</li>
 *   <li><b>Winner prediction</b>: among the samples whose game has a winner for the mover or the opponent, the share
 *       where {@code sign(v_mover - v_opponent)} equals the result of the game. A tie of the two outputs is wrong. This
 *       is the yardstick of the linear evaluation (78.8 % of the positions of 400 held-out games).</li>
 * </ul>
 */
final class HeldOutMetrics {

    /** Samples per forward batch. */
    private static final int CHUNK = 32;

    /** The values of one evaluation; NaN where no sample counts. */
    record Metrics(int samples, int policySamples, double klPolicy, double top1, double valueLoss, int winnerSamples,
            double winnerAccuracy) {
    }

    /** Sums over batches; {@link #metrics()} divides. */
    static final class Accumulator {
        private int samples;
        private int policySamples;
        private double wpSum;
        private double klSum;
        private double topSum;
        private double wvSum;
        private double valueSum;
        private int winnerSamples;
        private int winnerCorrect;

        /**
         * Adds a batch.
         *
         * @param policy  the network's policy output for the batch, [n][121][2]
         * @param values  the network's value output, [n][P]
         * @param results per sample +1, -1 or 0 (unknown or no winner): the result of its game for the mover
         */
        void add(TrainingBatch batch, float[] policy, float[] values, int[] results) {
            int[] winners = new int[results.length];
            int[] counts = new int[results.length];
            for (int s = 0; s < results.length; s++) {
                winners[s] = results[s] == 1 ? 0 : results[s] == -1 ? 1 : -1;
                counts[s] = 2;
            }
            add(batch, policy, values, winners, counts);
        }

        /**
         * Adds a batch of samples of any player count.
         *
         * @param winners per sample the relative player who wins its game, or -1 (unknown, shared top value)
         * @param counts  per sample its player count (2 or 3): only the first {@code counts[s]} outputs name players
         */
        void add(TrainingBatch batch, float[] policy, float[] values, int[] winners, int[] counts) {
            int players = batch.players();
            for (int s = 0; s < batch.size(); s++) {
                samples++;
                int first = batch.moveOffsets()[s];
                int end = batch.moveOffsets()[s + 1];
                double wp = batch.policyWeight()[s];
                if (wp > 0 && end > first) {
                    policySamples++;
                    double ce = ReferenceLoss.policy(policy, s * Encoder.POLICY_SIZE, batch.moveFrom(), batch.moveTo(),
                            batch.moveTarget(), first, end, 0, null, 0);
                    wpSum += wp;
                    klSum += wp * (ce - batch.targetEntropy()[s]);
                    topSum += argmaxLogit(batch, policy, s, first, end) == argmaxTarget(batch, first, end) ? wp : 0;
                }
                double wv = batch.valueWeight()[s];
                if (wv > 0) {
                    wvSum += wv;
                    valueSum += wv * ReferenceLoss.value(values, s * players, batch.valueTarget(), s * players, players,
                            0, null, 0);
                }
                if (winners[s] >= 0) {
                    winnerSamples++;
                    int best = 0;
                    boolean tie = false;
                    for (int i = 1; i < counts[s]; i++) {
                        double v = values[s * players + i];
                        double top = values[s * players + best];
                        if (v > top) {
                            best = i;
                            tie = false;
                        } else if (v == top) {
                            tie = true;
                        }
                    }
                    if (!tie && best == winners[s]) {
                        winnerCorrect++;
                    }
                }
            }
        }

        Metrics metrics() {
            return new Metrics(samples, policySamples, wpSum > 0 ? klSum / wpSum : Double.NaN,
                    wpSum > 0 ? topSum / wpSum : Double.NaN, wvSum > 0 ? valueSum / wvSum : Double.NaN, winnerSamples,
                    winnerSamples > 0 ? (double) winnerCorrect / winnerSamples : Double.NaN);
        }
    }

    private HeldOutMetrics() {
    }

    private static int argmaxLogit(TrainingBatch batch, float[] policy, int sample, int first, int end) {
        int best = first;
        double bestLogit = Double.NEGATIVE_INFINITY;
        int offset = sample * Encoder.POLICY_SIZE;
        for (int m = first; m < end; m++) {
            double logit = (double) policy[offset + Encoder.policyIndex(batch.moveFrom()[m], Encoder.FROM)]
                    + policy[offset + Encoder.policyIndex(batch.moveTo()[m], Encoder.TO)];
            if (logit > bestLogit) {
                bestLogit = logit;
                best = m;
            }
        }
        return best;
    }

    private static int argmaxTarget(TrainingBatch batch, int first, int end) {
        int best = first;
        for (int m = first + 1; m < end; m++) {
            if (batch.moveTarget()[m] > batch.moveTarget()[best]) {
                best = m;
            }
        }
        return best;
    }

    /**
     * Evaluates {@code evalSamples} samples of the held-out buffer, evenly spaced and the same at every call (the
     * buffer's order is fixed), in batches of 32, without mirroring.
     *
     * @param evalSamples at most this many samples; the whole buffer if it is smaller
     * @param threads     forward passes of different batches run in this many threads (the network must be
     *                    thread-safe if more than 1); the sums are added in batch order, so the result does not depend on it
     */
    static Metrics evaluate(ForwardPass network, ReplayBuffer heldOut, int evalSamples, int threads) {
        return evaluate(network, heldOut, null, evalSamples, threads);
    }

    /**
     * The metrics of every variant that the held-out buffer holds, each on at most {@code evalSamples} samples of
     * that variant, in {@link Variant} order. A variant has its own value loss and winner accuracy: the winner of a
     * three-player sample is the player with the highest output (yardstick: chance is 1/3, not 1/2).
     */
    static java.util.Map<Variant, Metrics> evaluateByVariant(ForwardPass network, ReplayBuffer heldOut,
            int evalSamples, int threads) {
        java.util.Map<Variant, Metrics> result = new java.util.LinkedHashMap<>();
        for (Variant v : heldOut.variants()) {
            result.put(v, evaluate(network, heldOut, v, evalSamples, threads));
        }
        return result;
    }

    /** As {@link #evaluate(ForwardPass, ReplayBuffer, int, int)}, on the samples of one variant (all if null). */
    static Metrics evaluate(ForwardPass network, ReplayBuffer heldOut, Variant variant, int evalSamples,
            int threads) {
        Objects.requireNonNull(network, "network");
        int[] subset = variant == null ? null : heldOut.indicesOf(variant);
        int size = subset == null ? heldOut.size() : subset.length;
        if (size == 0 || evalSamples < 1) {
            throw new IllegalArgumentException("need a held-out sample and evalSamples >= 1");
        }
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be >= 1");
        }
        int n = Math.min(size, evalSamples);
        int players = network.config().players();
        List<TrainingBatch> batches = new ArrayList<>();
        List<int[]> allWinners = new ArrayList<>();
        List<int[]> allCounts = new ArrayList<>();
        for (int start = 0; start < n; start += CHUNK) {
            int count = Math.min(CHUNK, n - start);
            int[] indices = new int[count];
            int[] winners = new int[count];
            int[] counts = new int[count];
            for (int i = 0; i < count; i++) {
                int at = (int) ((long) (start + i) * size / n);
                indices[i] = subset == null ? at : subset[at];
                winners[i] = heldOut.winner(indices[i]);
                counts[i] = heldOut.variantOf(indices[i]).players();
            }
            batches.add(heldOut.batchOf(indices, new boolean[count]));
            allWinners.add(winners);
            allCounts.add(counts);
        }
        List<float[][]> outputs = new ArrayList<>();
        if (threads == 1) {
            for (TrainingBatch b : batches) {
                outputs.add(forward(network, b, players));
            }
        } else {
            try (ExecutorService pool = Executors.newFixedThreadPool(Math.min(threads, batches.size()))) {
                List<Future<float[][]>> futures = new ArrayList<>();
                for (TrainingBatch b : batches) {
                    futures.add(pool.submit(() -> forward(network, b, players)));
                }
                for (Future<float[][]> f : futures) {
                    try {
                        outputs.add(f.get());
                    } catch (ExecutionException e) {
                        throw new IllegalStateException("forward pass failed", e.getCause());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("interrupted", e);
                    }
                }
            }
        }
        Accumulator acc = new Accumulator();
        for (int i = 0; i < batches.size(); i++) {
            acc.add(batches.get(i), outputs.get(i)[0], outputs.get(i)[1], allWinners.get(i), allCounts.get(i));
        }
        return acc.metrics();
    }

    private static float[][] forward(ForwardPass network, TrainingBatch batch, int players) {
        float[] policy = new float[batch.size() * Encoder.POLICY_SIZE];
        float[] values = new float[batch.size() * players];
        network.forward(batch.input(), batch.size(), policy, values);
        return new float[][] {policy, values};
    }
}
