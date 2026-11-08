package sternhalma.nn;

import java.util.Objects;

/**
 * Where a {@link TrainingRun} gets its batches from. The batch of a step is a pure function of the run seed, the
 * generation and the step, so that a resumed run draws the same batches (docs/training.md, "Checkpoints").
 * {@link #ofPool} draws from one expanded {@link TrainingBatch}; a replay buffer expands the drawn samples on demand.
 */
public interface BatchSource {

    /** The player count of the batches. */
    int players();

    /**
     * The batch of step {@code step}.
     *
     * @param generation the training generation
     * @param step       0-based index of the step within its generation
     */
    TrainingBatch batch(long runSeed, int generation, long step, int batchSize);

    /** The source that draws uniformly with replacement from {@code pool} ({@link BatchSampler#batch}). */
    static BatchSource ofPool(TrainingBatch pool) {
        Objects.requireNonNull(pool, "pool");
        return new BatchSource() {
            @Override
            public int players() {
                return pool.players();
            }

            @Override
            public TrainingBatch batch(long runSeed, int generation, long step, int batchSize) {
                return BatchSampler.batch(pool, runSeed, generation, step, batchSize);
            }
        };
    }
}
