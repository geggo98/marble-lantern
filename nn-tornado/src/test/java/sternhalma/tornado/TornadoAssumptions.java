package sternhalma.tornado;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import sternhalma.tornado.TornadoSupport.Availability;

/**
 * Skips a test when TornadoVM cannot run kernels here, so that the build passes on a machine
 * without the SDK or without a device.
 *
 * <p>Three cases fail instead of skipping, because skipping would hide a broken build behind green
 * tests: the build says it passed the TornadoVM flags (system property {@value #FLAGS_PROPERTY}), but
 * the runtime module is missing ({@link Availability#NO_FLAGS}) or the runtime failed to start
 * ({@link Availability#START_FAILED}); both happened while the build was written. The third: a backend was
 * named ({@code TORNADO_BACKEND}), for example cuda on a cloud machine, and it is not usable there.
 */
final class TornadoAssumptions {

    /** Set to {@code true} by nn-tornado/build.gradle.kts when it adds the TornadoVM JVM flags. */
    static final String FLAGS_PROPERTY = "sternhalma.tornado.flags";

    private TornadoAssumptions() {
    }

    /** Aborts the calling test (skipped, not failed) unless TornadoVM can run kernels. */
    static void assumeTornado() {
        TornadoSupport.Status status = TornadoSupport.status();
        boolean flagsPassed = Boolean.getBoolean(FLAGS_PROPERTY);
        if (flagsPassed && (status.availability() == Availability.NO_FLAGS
                || status.availability() == Availability.START_FAILED)) {
            fail("the build passed the TornadoVM flags, but TornadoVM is not usable: " + status.reason());
        }
        if (flagsPassed && !status.usable() && TornadoSupport.requestedBackend().isPresent()) {
            fail("backend " + TornadoSupport.requestedBackend().get() + " was requested ("
                    + TornadoSupport.BACKEND_ENV + " or -D" + TornadoSupport.BACKEND_PROPERTY
                    + "), but TornadoVM is not usable: " + status.reason());
        }
        assumeTrue(status.usable(), () -> "TornadoVM not usable: " + status.reason());
    }
}
