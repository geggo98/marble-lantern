package sternhalma.nn;

import java.nio.file.Path;

/**
 * A hook into every training run, for measurement and monitoring ({@link TrainingRun}). The run calls the
 * observers on its own thread, between the steps, and measures the wall time and the thread CPU time that the
 * calls take ({@link TrainingRun.Timing#observer()}), so that the cost of observing is itself known.
 *
 * <p>Both methods do nothing by default. An exception from an observer ends the run: a measurement hook that
 * fails silently would hide the failure.
 */
public interface TrainingObserver {

    /**
     * After a step.
     *
     * @param step       number of completed steps, 1 after the first step of a fresh run
     * @param loss       data term of the loss of the batch of this step, before the update
     * @param klPolicy   mean policy KL divergence of the batch
     * @param valueLoss  value loss of the batch
     * @param wallNanos  wall time of the step (batch, gradients, update; not checkpoint, not observers)
     */
    default void onStep(long step, double loss, double klPolicy, double valueLoss, long wallNanos) {
    }

    /**
     * After a checkpoint was written and old ones were removed.
     *
     * @param path      the checkpoint file
     * @param wallNanos wall time of writing the file and removing old checkpoints
     * @param bytes     size of the file
     */
    default void onCheckpoint(Path path, long wallNanos, long bytes) {
    }
}
