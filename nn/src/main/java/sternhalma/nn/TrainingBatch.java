package sternhalma.nn;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One batch of training samples in flat arrays (docs/training.md, "Batch layout").
 *
 * <p>Everything is in canonical orientation and relative player order, so training needs no {@code Position},
 * no {@link Orientation} and no move generator. The constructor validates the layout and copies the arrays;
 * the accessors return the internal arrays for speed, and callers must not modify them.
 *
 * @param size           number of samples N
 * @param players        number of players P
 * @param input          encoded planes, [N][122][7], as {@link Encoder#encode} writes them; row 121 is 0
 * @param moveOffsets    [N + 1], the moves of sample s are {@code moveOffsets[s] .. moveOffsets[s + 1] - 1}
 * @param moveFrom       canonical start cells of the M moves, 0 to 120
 * @param moveTo         canonical end cells of the M moves, 0 to 120
 * @param moveTarget     policy targets pi_m &gt;= 0, normalised per sample (sum 1 where the policy weight is
 *                       positive)
 * @param valueTarget    [N][P], relative players (mover first), each in [-1, 1]
 * @param policyWeight   wp_s &gt;= 0; positive only for a sample with at least one move
 * @param valueWeight    wv_s &gt;= 0
 * @param targetEntropy  H_s = -sum pi_m log pi_m, for reporting the KL divergence
 */
public record TrainingBatch(int size, int players, float[] input, int[] moveOffsets, int[] moveFrom,
        int[] moveTo, float[] moveTarget, float[] valueTarget, float[] policyWeight, float[] valueWeight,
        double[] targetEntropy) {

    /** Largest allowed deviation of a target sum from 1 (the targets are floats). */
    static final double SUM_TOLERANCE = 1e-5;
    /** Largest allowed deviation of {@code targetEntropy} from the entropy of the targets. */
    static final double ENTROPY_TOLERANCE = 1e-5;

    /**
     * @throws NullPointerException     if an array is null
     * @throws IllegalArgumentException if a length is wrong, a cell is outside 0 to 120, a number is not
     *                                  finite or out of range, a padding row is not 0, a weighted sample has no
     *                                  moves or targets that do not sum to 1, a sample lists the same (from, to)
     *                                  pair twice, or an entropy differs from the targets
     */
    public TrainingBatch {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(moveOffsets, "moveOffsets");
        Objects.requireNonNull(moveFrom, "moveFrom");
        Objects.requireNonNull(moveTo, "moveTo");
        Objects.requireNonNull(moveTarget, "moveTarget");
        Objects.requireNonNull(valueTarget, "valueTarget");
        Objects.requireNonNull(policyWeight, "policyWeight");
        Objects.requireNonNull(valueWeight, "valueWeight");
        Objects.requireNonNull(targetEntropy, "targetEntropy");
        if (size < 1) {
            throw new IllegalArgumentException("size must be >= 1, got " + size);
        }
        if (players != 2 && players != 3) {
            throw new IllegalArgumentException("players must be 2 or 3, got " + players);
        }
        requireLength("input", input.length, (long) size * Encoder.INPUT_SIZE);
        requireLength("moveOffsets", moveOffsets.length, size + 1L);
        requireLength("valueTarget", valueTarget.length, (long) size * players);
        requireLength("policyWeight", policyWeight.length, size);
        requireLength("valueWeight", valueWeight.length, size);
        requireLength("targetEntropy", targetEntropy.length, size);
        int moves = moveOffsets[size];
        requireLength("moveFrom", moveFrom.length, moves);
        requireLength("moveTo", moveTo.length, moves);
        requireLength("moveTarget", moveTarget.length, moves);
        if (moveOffsets[0] != 0) {
            throw new IllegalArgumentException("moveOffsets[0] must be 0, got " + moveOffsets[0]);
        }
        input = input.clone();
        moveOffsets = moveOffsets.clone();
        moveFrom = moveFrom.clone();
        moveTo = moveTo.clone();
        moveTarget = moveTarget.clone();
        valueTarget = valueTarget.clone();
        policyWeight = policyWeight.clone();
        valueWeight = valueWeight.clone();
        targetEntropy = targetEntropy.clone();

        for (int s = 0; s < size; s++) {
            if (moveOffsets[s + 1] < moveOffsets[s]) {
                throw new IllegalArgumentException("moveOffsets decrease at sample " + s + ": " + moveOffsets[s]
                        + " then " + moveOffsets[s + 1]);
            }
        }
        int[] seen = new int[HexGrid.CELLS * HexGrid.CELLS]; // sample + 1 of the last sample that used a pair
        for (int s = 0; s < size; s++) {
            validateSample(s, players, input, moveOffsets, moveFrom, moveTo, moveTarget, valueTarget,
                    policyWeight, valueWeight, targetEntropy, seen);
        }
    }

    private static void validateSample(int s, int players, float[] input, int[] offsets, int[] from, int[] to,
            float[] target, float[] z, float[] wp, float[] wv, double[] entropy, int[] seen) {
        int first = offsets[s];
        int end = offsets[s + 1];
        for (int i = 0; i < Encoder.INPUT_SIZE; i++) {
            float v = input[s * Encoder.INPUT_SIZE + i];
            if (!Float.isFinite(v)) {
                throw new IllegalArgumentException("sample " + s + ": input is not finite at " + i);
            }
            if (i >= HexGrid.CELLS * Encoder.PLANES && v != 0) {
                throw new IllegalArgumentException("sample " + s + ": padding row of the input is not zero");
            }
        }
        for (int i = 0; i < players; i++) {
            float v = z[s * players + i];
            if (!(v >= -1 && v <= 1)) {
                throw new IllegalArgumentException("sample " + s + ": value target " + v + " is not in [-1, 1]");
            }
        }
        if (!(wp[s] >= 0) || !Float.isFinite(wp[s])) {
            throw new IllegalArgumentException("sample " + s + ": policy weight " + wp[s]);
        }
        if (!(wv[s] >= 0) || !Float.isFinite(wv[s])) {
            throw new IllegalArgumentException("sample " + s + ": value weight " + wv[s]);
        }
        double sum = 0;
        for (int m = first; m < end; m++) {
            if (from[m] < 0 || from[m] >= HexGrid.CELLS || to[m] < 0 || to[m] >= HexGrid.CELLS) {
                throw new IllegalArgumentException("sample " + s + ": move " + from[m] + " -> " + to[m]
                        + " has a cell outside 0 to " + (HexGrid.CELLS - 1));
            }
            if (!(target[m] >= 0) || !Float.isFinite(target[m])) {
                throw new IllegalArgumentException("sample " + s + ": move target " + target[m]);
            }
            int pair = from[m] * HexGrid.CELLS + to[m];
            if (seen[pair] == s + 1) {
                throw new IllegalArgumentException("sample " + s + " lists the move " + from[m] + " -> " + to[m]
                        + " twice");
            }
            seen[pair] = s + 1;
            sum += target[m];
        }
        if (wp[s] > 0) {
            if (first == end) {
                throw new IllegalArgumentException("sample " + s + " has a policy weight but no moves");
            }
            if (Math.abs(sum - 1) > SUM_TOLERANCE) {
                throw new IllegalArgumentException("sample " + s + ": move targets sum to " + sum + ", not 1");
            }
        }
        double h = ReferenceLoss.entropy(target, first, end);
        if (!(Math.abs(entropy[s] - h) <= ENTROPY_TOLERANCE)) {
            throw new IllegalArgumentException("sample " + s + ": target entropy " + entropy[s]
                    + " differs from the entropy of the targets, " + h);
        }
    }

    private static void requireLength(String name, long actual, long expected) {
        if (actual != expected) {
            throw new IllegalArgumentException(name + " needs " + expected + " elements, has " + actual);
        }
    }

    /** Number of moves M of all samples. */
    public int moveCount() {
        return moveOffsets[size];
    }

    /**
     * A new batch of the samples with the given indices, in that order; an index may occur several times. This is
     * how a seeded sample of a pool becomes a training batch ({@link BatchSampler}).
     *
     * @throws IllegalArgumentException if {@code indices} is empty or an index is outside 0 to size - 1
     */
    public TrainingBatch select(int[] indices) {
        if (indices.length == 0) {
            throw new IllegalArgumentException("no indices");
        }
        int n = indices.length;
        int[] offsets = new int[n + 1];
        for (int i = 0; i < n; i++) {
            int s = indices[i];
            if (s < 0 || s >= size) {
                throw new IllegalArgumentException("index " + s + " is outside 0 to " + (size - 1));
            }
            offsets[i + 1] = offsets[i] + (moveOffsets[s + 1] - moveOffsets[s]);
        }
        float[] newInput = new float[n * Encoder.INPUT_SIZE];
        int[] from = new int[offsets[n]];
        int[] to = new int[offsets[n]];
        float[] target = new float[offsets[n]];
        float[] z = new float[n * players];
        float[] wp = new float[n];
        float[] wv = new float[n];
        double[] entropy = new double[n];
        for (int i = 0; i < n; i++) {
            int s = indices[i];
            int first = moveOffsets[s];
            int count = moveOffsets[s + 1] - first;
            System.arraycopy(input, s * Encoder.INPUT_SIZE, newInput, i * Encoder.INPUT_SIZE, Encoder.INPUT_SIZE);
            System.arraycopy(moveFrom, first, from, offsets[i], count);
            System.arraycopy(moveTo, first, to, offsets[i], count);
            System.arraycopy(moveTarget, first, target, offsets[i], count);
            System.arraycopy(valueTarget, s * players, z, i * players, players);
            wp[i] = policyWeight[s];
            wv[i] = valueWeight[s];
            entropy[i] = targetEntropy[s];
        }
        return new TrainingBatch(n, players, newInput, offsets, from, to, target, z, wp, wv, entropy);
    }

    /**
     * The value targets of a game result in relative player order: {@code z_i = values[(mover + i) mod P]}
     * (docs/training.md, "Value loss"). {@code values} are the absolute {@code Outcome.values}.
     */
    public static float[] relativeValues(double[] values, int mover) {
        int p = values.length;
        float[] z = new float[p];
        for (int i = 0; i < p; i++) {
            z[i] = (float) values[(mover + i) % p];
        }
        return z;
    }

    /**
     * Collects samples one by one. {@link #add} normalises the targets in double so that they sum to 1 and
     * computes the entropy; {@link #build} makes the validated batch.
     */
    public static final class Builder {
        private final int players;
        private final List<float[]> inputs = new ArrayList<>();
        private final List<int[]> froms = new ArrayList<>();
        private final List<int[]> tos = new ArrayList<>();
        private final List<float[]> targets = new ArrayList<>();
        private final List<float[]> values = new ArrayList<>();
        private final List<float[]> weights = new ArrayList<>();

        public Builder(int players) {
            this.players = players;
        }

        /**
         * Adds a sample.
         *
         * @param input        {@link Encoder#INPUT_SIZE} floats
         * @param from         canonical start cells of the legal moves
         * @param to           canonical end cells
         * @param rawTarget    unnormalised policy targets, &gt;= 0
         * @param value        value targets, relative players
         * @throws IllegalArgumentException if the targets sum to 0 while the policy weight is positive, or the
         *                                  arrays differ in length
         */
        public Builder add(float[] input, int[] from, int[] to, double[] rawTarget, float[] value,
                float policyWeight, float valueWeight) {
            if (from.length != to.length || from.length != rawTarget.length) {
                throw new IllegalArgumentException("from, to and target differ in length");
            }
            if (input.length != Encoder.INPUT_SIZE) {
                throw new IllegalArgumentException("input needs " + Encoder.INPUT_SIZE + " floats, has "
                        + input.length);
            }
            if (value.length != players) {
                throw new IllegalArgumentException("value needs " + players + " targets (one per player), has "
                        + value.length);
            }
            double sum = 0;
            for (double t : rawTarget) {
                sum += t;
            }
            if (policyWeight > 0 && !(sum > 0)) {
                throw new IllegalArgumentException("targets sum to " + sum + " while the policy weight is "
                        + policyWeight);
            }
            float[] target = new float[rawTarget.length];
            for (int m = 0; m < target.length; m++) {
                target[m] = sum > 0 ? (float) (rawTarget[m] / sum) : 0f;
            }
            inputs.add(input.clone());
            froms.add(from.clone());
            tos.add(to.clone());
            targets.add(target);
            values.add(value.clone());
            weights.add(new float[] {policyWeight, valueWeight});
            return this;
        }

        public TrainingBatch build() {
            int n = inputs.size();
            int moves = froms.stream().mapToInt(a -> a.length).sum();
            float[] input = new float[n * Encoder.INPUT_SIZE];
            int[] offsets = new int[n + 1];
            int[] from = new int[moves];
            int[] to = new int[moves];
            float[] target = new float[moves];
            float[] z = new float[n * players];
            float[] wp = new float[n];
            float[] wv = new float[n];
            double[] entropy = new double[n];
            for (int s = 0; s < n; s++) {
                System.arraycopy(inputs.get(s), 0, input, s * Encoder.INPUT_SIZE, Encoder.INPUT_SIZE);
                int at = offsets[s];
                int len = froms.get(s).length;
                System.arraycopy(froms.get(s), 0, from, at, len);
                System.arraycopy(tos.get(s), 0, to, at, len);
                System.arraycopy(targets.get(s), 0, target, at, len);
                offsets[s + 1] = at + len;
                if (values.get(s).length != players) {
                    throw new IllegalArgumentException("sample " + s + " has " + values.get(s).length
                            + " value targets, expected " + players);
                }
                System.arraycopy(values.get(s), 0, z, s * players, players);
                wp[s] = weights.get(s)[0];
                wv[s] = weights.get(s)[1];
                entropy[s] = ReferenceLoss.entropy(target, at, at + len);
            }
            return new TrainingBatch(n, players, input, offsets, from, to, target, z, wp, wv, entropy);
        }
    }
}
