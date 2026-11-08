package sternhalma.nn;

import java.util.SplittableRandom;

/**
 * The seeded batch sampler of docs/training.md, "Checkpoints": the batch of step t draws from
 * {@code new SplittableRandom(mix(runSeed, generation, t))}, so no random state is stored in a checkpoint and a
 * resumed run draws the same batches.
 *
 * <p>The draw is uniform with replacement from a pool of {@code poolSize} samples.
 */
public final class BatchSampler {

    private BatchSampler() {
    }

    /**
     * Combines the three numbers into one seed. Each argument goes through the SplitMix64 finaliser before the
     * next one is added, so that (generation 1, step 0) and (generation 0, step 1) give unrelated seeds, as a
     * plain sum or xor would not.
     */
    public static long mix(long runSeed, int generation, long step) {
        long h = finalise(runSeed + 0x9E3779B97F4A7C15L);
        h = finalise(h ^ (generation + 0x9E3779B97F4A7C15L));
        return finalise(h ^ (step + 0x9E3779B97F4A7C15L));
    }

    /** The SplitMix64 output function: a bijection on 64 bits with full avalanche. */
    private static long finalise(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /**
     * The sample indices of step {@code step}.
     *
     * @param step 0-based index of the step about to run within its generation
     * @throws IllegalArgumentException if poolSize or batchSize is less than 1, or generation or step is negative
     */
    public static int[] indices(long runSeed, int generation, long step, int poolSize, int batchSize) {
        if (poolSize < 1 || batchSize < 1) {
            throw new IllegalArgumentException("poolSize and batchSize must be >= 1, got " + poolSize + ", "
                    + batchSize);
        }
        if (generation < 0 || step < 0) {
            throw new IllegalArgumentException("generation and step must be >= 0, got " + generation + ", " + step);
        }
        SplittableRandom random = new SplittableRandom(mix(runSeed, generation, step));
        int[] indices = new int[batchSize];
        for (int i = 0; i < batchSize; i++) {
            indices[i] = random.nextInt(poolSize);
        }
        return indices;
    }

    /** The batch of step {@code step}: {@link TrainingBatch#select} of {@link #indices}. */
    public static TrainingBatch batch(TrainingBatch pool, long runSeed, int generation, long step, int batchSize) {
        return pool.select(indices(runSeed, generation, step, pool.size(), batchSize));
    }
}
