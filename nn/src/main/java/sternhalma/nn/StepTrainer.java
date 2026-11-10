package sternhalma.nn;

/**
 * One optimiser step of a training run: the gradients of a batch and the update of the parameters. The CPU
 * reference ({@link #cpu}) does it on the host; the GPU trainer of module nn-tornado keeps parameters and
 * optimiser state on the device and brings them back in {@link #sync()}. {@link TrainingRun} drives either one
 * (docs/training.md, "As implemented (block B6-B10)").
 *
 * <p>The {@link Parameters} and the {@link Optimizer} of the run are the host copy. After {@link #step} their
 * step count is current (the optimiser's {@link Optimizer#steps()} is the run's progress), but a trainer that
 * works on a device may leave the parameter and state arrays stale until {@link #sync()}. A run calls
 * {@link #sync()} before it writes a checkpoint and before anyone reads the parameters.
 */
public interface StepTrainer extends AutoCloseable {

    /**
     * One step: the gradient of the batch loss, then the update with the learning rate {@code lr}; the step count
     * of the optimiser goes up by one.
     *
     * @return the terms of the batch loss before the update
     * @throws IllegalStateException if a loss, a gradient or an updated parameter is NaN or infinite. The
     *                               parameters are undefined afterwards, because the update may have run: the caller
     *                               must end the run and not write them anywhere
     */
    ReferenceTrainer.Result step(TrainingBatch batch, double lr);

    /** Makes the host parameters and the host optimiser state equal to the trainer's. */
    void sync();

    /** Frees what the trainer holds, such as device memory. */
    @Override
    void close();

    /**
     * The CPU reference trainer with the {@link Optimizer} on the host: {@link ReferenceTrainer} in microbatches
     * of {@code microbatch} samples on {@code threads} threads, then {@link Optimizer#step}. {@link #sync()} does
     * nothing.
     */
    static StepTrainer cpu(Parameters parameters, Optimizer optimizer, double valueCoefficient, int microbatch,
            int threads) {
        ReferenceTrainer trainer = new ReferenceTrainer(parameters, valueCoefficient);
        return new StepTrainer() {
            @Override
            public ReferenceTrainer.Result step(TrainingBatch batch, double lr) {
                trainer.zeroGradients();
                ReferenceTrainer.Result result = trainer.accumulate(batch, microbatch, threads);
                optimizer.step(trainer.gradients(), lr);
                return result;
            }

            @Override
            public void sync() {
            }

            @Override
            public void close() {
            }
        };
    }
}
