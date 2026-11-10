package sternhalma.tornado;

import sternhalma.nn.Optimizer;
import sternhalma.nn.Parameters;
import sternhalma.nn.StepTrainer;

/**
 * The entry for callers that must compile and run without TornadoVM: the training loop asks {@link #unavailableReason()}
 * and creates a {@link StepTrainer} with {@link #create}. No method has a TornadoVM type in its signature, so a class
 * that calls only these compiles without the TornadoVM API, and {@link #unavailableReason()} runs in a JVM that was
 * started without the TornadoVM flags (it reads {@link TornadoSupport#status()}, which never touches TornadoVM then).
 */
public final class GpuTrainers {

    /** Largest number of samples that one device execution of {@link #create} computes (a bigger batch is cut). */
    public static final int MAX_MICROBATCH = 2048;

    private GpuTrainers() {
    }

    /** Why the device trainer cannot run in this JVM, or null if it can. */
    public static String unavailableReason() {
        TornadoSupport.Status status = TornadoSupport.status();
        return status.usable() ? null : status.reason();
    }

    /** The name of the device and its backend, for example {@code Apple M3 Max (METAL)}. */
    public static String description() {
        TornadoSupport.Status status = TornadoSupport.status();
        return status.usable() ? status.device() + " (" + status.backend() + ")" : "none: " + status.reason();
    }

    /** The samples of one device execution for a batch of {@code batch}: the largest divisor that is at most {@link #MAX_MICROBATCH}. */
    public static int microbatchFor(int batch) {
        if (batch < 1) {
            throw new IllegalArgumentException("batch must be >= 1, got " + batch);
        }
        for (int m = Math.min(batch, MAX_MICROBATCH); m > 1; m--) {
            if (batch % m == 0) {
                return m;
            }
        }
        return 1;
    }

    /**
     * A trainer on the default device for steps of {@code batch} samples with the state of {@code optimizer}; see
     * {@code new TornadoTrainer(parameters, optimizer, batch, microbatch, ...)}.
     * The caller closes it.
     *
     * @throws IllegalStateException if {@link #unavailableReason()} is not null
     */
    public static StepTrainer create(Parameters parameters, Optimizer optimizer, int batch, double valueCoefficient) {
        return new TornadoTrainer(parameters, optimizer, batch, microbatchFor(batch), valueCoefficient,
                TornadoSupport.defaultDevice());
    }
}
