package sternhalma.tornado;

import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import sternhalma.engine.Move;
import sternhalma.engine.Position;
import sternhalma.nn.Encoder;
import sternhalma.nn.HexGrid;
import sternhalma.nn.Network;
import sternhalma.nn.NetworkConfig;
import sternhalma.nn.Orientation;
import sternhalma.nn.Parameters;
import sternhalma.nn.PlaneSet;
import sternhalma.nn.ReferenceBackward;
import sternhalma.nn.ReferenceLoss;
import sternhalma.nn.ReferenceOps;
import sternhalma.nn.ResidualPriors;
import sternhalma.nn.TrainingBatch;

/**
 * Batches and per-sample reference computations for the tests of the training kernels. The batches follow
 * {@code TestBatches} of module nn (which this module cannot see); the reference follows
 * {@code ReferenceTrainer.Worker}, whose buffers are package-private, so the forward pass and the head backward
 * are written out here with the same {@code ReferenceOps} and {@code ReferenceBackward} calls.
 */
final class TrainTesting {

    private TrainTesting() {
    }

    /**
     * {@code n} samples from seeded random games with every mover: the planes of the plane set, the legal moves in
     * canonical cells with random positive targets (every fourth target 0), a seeded win, policy weights cycling
     * through 1, 0.5, 2, 1.5 and value weights through 1, 2, 0.5, 1.
     */
    static TrainingBatch batch(int players, PlaneSet planes, int n, long seed) {
        List<Position> all = Playouts.positions(players, 4, 40, seed);
        List<Position> usable = new ArrayList<>();
        for (Position p : all) {
            if (p.legalMoves().length > 0) {
                usable.add(p);
            }
        }
        Random random = new Random(seed);
        TrainingBatch.Builder builder = new TrainingBatch.Builder(players, planes);
        float[] policyWeights = {1f, 0.5f, 2f, 1.5f};
        float[] valueWeights = {1f, 2f, 0.5f, 1f};
        for (int i = 0; i < n; i++) {
            Position position = usable.get((int) ((long) i * usable.size() / n));
            int[] moves = position.legalMoves();
            Orientation orientation = Orientation.of(position);
            float[] input = new float[planes.inputSize()];
            Encoder.encode(planes, position, input, 0);
            int[] from = new int[moves.length];
            int[] to = new int[moves.length];
            double[] target = new double[moves.length];
            for (int m = 0; m < moves.length; m++) {
                from[m] = orientation.toCanonical(Move.from(moves[m]));
                to[m] = orientation.toCanonical(Move.to(moves[m]));
                target[m] = m % 4 == 3 ? 0 : 0.1 + random.nextDouble();
            }
            target[0] = 1;
            builder.add(input, from, to, target, winner(players, random, position.sideToMove()),
                    policyWeights[i % 4], valueWeights[i % 4]);
        }
        return builder.build();
    }

    private static float[] winner(int players, Random random, int mover) {
        double[] absolute = new double[players];
        Arrays.fill(absolute, -1.0 / (players - 1));
        absolute[random.nextInt(players)] = 1;
        return TrainingBatch.relativeValues(absolute, mover);
    }

    /**
     * The edge cases in one batch of {@code base} samples: sample 0 has a single legal move, sample 1 has no policy
     * weight, sample 2 no value weight, sample 3 no moves at all (and no policy weight), sample 4 moves on the extreme
     * cells 0 and 120 that share from and to cells.
     */
    static TrainingBatch edgeBatch(TrainingBatch base) {
        TrainingBatch.Builder builder = new TrainingBatch.Builder(base.players(),
                base.inputPlanes() == Encoder.PLANES ? PlaneSet.PLAIN : PlaneSet.CLASSIC1);
        for (int s = 0; s < base.size(); s++) {
            int first = base.moveOffsets()[s];
            int end = base.moveOffsets()[s + 1];
            float[] input = Arrays.copyOfRange(base.input(), s * base.inputSize(), (s + 1) * base.inputSize());
            int[] from = Arrays.copyOfRange(base.moveFrom(), first, end);
            int[] to = Arrays.copyOfRange(base.moveTo(), first, end);
            double[] target = new double[end - first];
            for (int m = 0; m < target.length; m++) {
                target[m] = base.moveTarget()[first + m];
            }
            float[] z = Arrays.copyOfRange(base.valueTarget(), s * base.players(), (s + 1) * base.players());
            float wp = base.policyWeight()[s];
            float wv = base.valueWeight()[s];
            switch (s) {
                case 0 -> {
                    from = new int[] {from[0]};
                    to = new int[] {to[0]};
                    target = new double[] {1};
                }
                case 1 -> wp = 0f;
                case 2 -> wv = 0f;
                case 3 -> {
                    from = new int[0];
                    to = new int[0];
                    target = new double[0];
                    wp = 0f;
                }
                case 4 -> {
                    from = new int[] {0, 120, 0, 120, 60};
                    to = new int[] {120, 0, 0, 120, 61};
                    target = new double[] {0.3, 0.2, 0.1, 0.25, 0.15};
                }
                default -> {
                }
            }
            builder.add(input, from, to, target, z, wp, wv);
        }
        return builder.build();
    }

    /** One sample as the reference computes it: every stored activation, the outputs and the head gradients. */
    static final class Sample {
        /** Slot 0 is x_0, 2i + 1 is t_i, 2i + 2 is u_i; each [122][C]. */
        float[][] slots;
        float[] policy;
        float[] pooled;
        float[] hidden;
        float[] values;
        double[] dPolicy;
        double[] dy;
        double[] dz1;
        double[] dPooled;
        double[] dxTop;
        double policyLoss;
        double valueLoss;
    }

    /**
     * Forward pass, losses and head backward of sample {@code s} of {@code batch} in the reference arithmetic.
     * The loss scales use the size of the whole batch, as the trainer does.
     */
    static Sample reference(Parameters p, TrainingBatch batch, int s, double cv) {
        NetworkConfig config = p.config();
        int c = config.channels();
        int blocks = config.blocks();
        int players = config.players();
        int h = config.valueHidden();
        int planes = config.inputPlanes();
        int activation = HexGrid.ROWS * c;
        boolean priors = config.planes().residualPriors();
        float[] input = batch.input();
        int inputOffset = s * config.inputSize();
        Sample r = new Sample();
        float[] acts = new float[(1 + 2 * blocks) * activation];
        ReferenceOps.hexConv(input, inputOffset, planes, p.get("conv_in.weight").data(), p.get("conv_in.bias").data(), c,
                acts, 0);
        ReferenceOps.relu(acts, 0, activation);
        for (int i = 0; i < blocks; i++) {
            int x = 2 * i * activation;
            int t = x + activation;
            int u = t + activation;
            String block = "block" + i;
            ReferenceOps.hexConv(acts, x, c, p.get(block + ".conv1.weight").data(),
                    p.get(block + ".conv1.bias").data(), c, acts, t);
            ReferenceOps.relu(acts, t, activation);
            ReferenceOps.hexConv(acts, t, c, p.get(block + ".conv2.weight").data(),
                    p.get(block + ".conv2.bias").data(), c, acts, u);
            ReferenceOps.add(acts, u, acts, x, activation);
            ReferenceOps.relu(acts, u, activation);
        }
        r.slots = new float[1 + 2 * blocks][];
        for (int i = 0; i < r.slots.length; i++) {
            r.slots[i] = Arrays.copyOfRange(acts, i * activation, (i + 1) * activation);
        }
        int top = 2 * blocks * activation;
        r.policy = new float[Encoder.POLICY_SIZE];
        ReferenceOps.pointwise(acts, top, HexGrid.CELLS, c, p.get("head_policy.weight").data(),
                p.get("head_policy.bias").data(), Encoder.POLICY_CHANNELS, r.policy, 0);
        if (priors) {
            ResidualPriors.addPolicy(p.get(ResidualPriors.BETA).data()[0], input, inputOffset, planes, r.policy, 0);
        }
        r.pooled = new float[c];
        ReferenceOps.meanOverCells(acts, top, c, r.pooled, 0);
        r.hidden = new float[h];
        ReferenceOps.dense(r.pooled, 0, c, p.get("head_value.dense1.weight").data(),
                p.get("head_value.dense1.bias").data(), h, r.hidden, 0);
        ReferenceOps.relu(r.hidden, 0, h);
        r.values = new float[players];
        ReferenceOps.dense(r.hidden, 0, h, p.get("head_value.dense2.weight").data(),
                p.get("head_value.dense2.bias").data(), players, r.values, 0);
        for (int i = 0; i < players && priors; i++) {
            r.values[i] += p.get(ResidualPriors.K).data()[0] * ResidualPriors.valueBase(input, inputOffset, planes, i);
        }
        ReferenceOps.tanh(r.values, 0, players);

        int n = batch.size();
        r.dPolicy = new double[Encoder.POLICY_SIZE];
        r.dy = new double[players];
        float wp = batch.policyWeight()[s];
        float wv = batch.valueWeight()[s];
        if (wp > 0) {
            r.policyLoss = ReferenceLoss.policy(r.policy, 0, batch.moveFrom(), batch.moveTo(), batch.moveTarget(),
                    batch.moveOffsets()[s], batch.moveOffsets()[s + 1], (double) wp / n, r.dPolicy, 0);
        }
        if (wv > 0) {
            r.valueLoss = ReferenceLoss.value(r.values, 0, batch.valueTarget(), s * players, players, cv * wv / n, r.dy,
                    0);
        }
        // Order of the backward pass, steps 2 to 6.
        double[] dHidden = new double[h];
        ReferenceBackward.denseBackward(r.hidden, 0, h, p.get("head_value.dense2.weight").data(), players, r.dy, 0,
                new double[players * h], new double[players], dHidden, 0);
        ReferenceBackward.reluBackward(r.hidden, 0, dHidden, 0, h);
        r.dz1 = dHidden.clone();
        r.dPooled = new double[c];
        ReferenceBackward.denseBackward(r.pooled, 0, c, p.get("head_value.dense1.weight").data(), h, dHidden, 0,
                new double[h * c], new double[h], r.dPooled, 0);
        r.dxTop = new double[activation];
        ReferenceBackward.meanBackward(r.dPooled, 0, c, r.dxTop, 0);
        ReferenceBackward.pointwiseBackward(acts, top, HexGrid.CELLS, c, p.get("head_policy.weight").data(),
                Encoder.POLICY_CHANNELS, r.dPolicy, 0, new double[2 * c], new double[2], r.dxTop, 0);
        return r;
    }

    /** The error of one comparison: the largest error as a fraction of its bound, and where. */
    static final class Worst {
        final String what;
        double fraction;

        Worst(String what) {
            this.what = what;
        }

        @Override
        public String toString() {
            return String.format("%s %.4f", what, fraction);
        }
    }

    /** {@code |gpu - ref| <= 1e-4 + 1e-3 |ref|} (docs/network.md, "Numeric conventions") for floats; fails otherwise. */
    static void forwardAgrees(Worst worst, String where, float[] ref, int refOffset, float[] gpu, int gpuOffset,
            int length) {
        for (int i = 0; i < length; i++) {
            double expected = ref[refOffset + i];
            double bound = Network.ABSOLUTE_TOLERANCE + Network.RELATIVE_TOLERANCE * Math.abs(expected);
            double error = Math.abs(gpu[gpuOffset + i] - expected);
            if (!(error <= bound)) {
                fail(worst.what + ", " + where + ", index " + i + ": expected " + expected + ", got "
                        + gpu[gpuOffset + i]);
            }
            worst.fraction = Math.max(worst.fraction, error / bound);
        }
    }

    /**
     * {@code |gpu - ref| <= 1e-3 |ref| + 1e-4 max |ref|} (docs/training.md, "Verification against Part A"), with the
     * maximum over the whole reference tensor {@code scale}.
     */
    static void gradientAgrees(Worst worst, String where, double[] ref, int refOffset, float[] gpu, int gpuOffset,
            int length, double scale) {
        for (int i = 0; i < length; i++) {
            double expected = ref[refOffset + i];
            double bound = 1e-3 * Math.abs(expected) + 1e-4 * scale;
            double error = Math.abs(gpu[gpuOffset + i] - expected);
            if (!(error <= bound)) {
                fail(worst.what + ", " + where + ", index " + i + ": expected " + expected + ", got "
                        + gpu[gpuOffset + i] + ", bound " + bound);
            }
            if (bound > 0) {
                worst.fraction = Math.max(worst.fraction, error / bound);
            } else if (error > 0) {
                fail(worst.what + ", " + where + ", index " + i + ": expected exactly 0, got " + gpu[gpuOffset + i]);
            }
        }
    }

    /** Largest absolute value of an array, 0 for an empty array. */
    static double maxAbs(double[] a) {
        double m = 0;
        for (double v : a) {
            m = Math.max(m, Math.abs(v));
        }
        return m;
    }

    /** The tensor bound for the init of the config, as the tests of module nn use it. */
    static float initBound(NetworkConfig config) {
        return (float) Math.sqrt(6.0 / (7 * config.channels()));
    }
}
