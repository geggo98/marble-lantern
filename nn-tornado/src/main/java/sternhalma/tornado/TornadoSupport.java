package sternhalma.tornado;

import java.util.List;
import java.util.Optional;

import uk.ac.manchester.tornado.api.TornadoBackend;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.common.TornadoDevice;
import uk.ac.manchester.tornado.api.enums.TornadoVMBackendType;
import uk.ac.manchester.tornado.api.exceptions.TornadoBackendNotFound;
import uk.ac.manchester.tornado.api.exceptions.TornadoDeviceNotFound;
import uk.ac.manchester.tornado.api.exceptions.TornadoNoMetalPlatformException;
import uk.ac.manchester.tornado.api.exceptions.TornadoNoOpenCLPlatformException;

/**
 * Finds out whether this JVM can run TornadoVM kernels and on which device.
 *
 * <p>TornadoVM is usable only if the JVM was started with the flags of the SDK (see docs/tornado.md)
 * and the runtime starts and finds at least one device. The probe runs once, on first use, and never
 * throws: every other outcome is a {@link Status} with an {@link Availability} and a reason.
 */
public final class TornadoSupport {

    /** Name of the TornadoVM runtime module that the SDK flags add to the boot layer. */
    static final String RUNTIME_MODULE = "tornado.runtime";

    /** Outcome of the probe. */
    public enum Availability {
        /** Kernels can run on {@link #defaultDevice()}. */
        USABLE,
        /** The JVM was started without the TornadoVM flags: the runtime module is not in the boot layer. */
        NO_FLAGS,
        /** The runtime started, or reported a missing platform, but there is no device. */
        NO_DEVICE,
        /** The runtime is present but failed to start: a configuration error, not a missing GPU. */
        START_FAILED
    }

    /**
     * Result of the probe.
     *
     * @param availability whether and why not TornadoVM is usable
     * @param backend      backend of the default device, for example {@code METAL}; empty if not usable
     * @param device       name of the default device, for example {@code Apple M3 Max}; empty if not usable
     * @param devices      number of devices over all backends; 0 if not usable
     * @param reason       why TornadoVM is not usable; empty if it is
     */
    public record Status(Availability availability, String backend, String device, int devices, String reason) {

        /** True if kernels can run on {@link #defaultDevice()}. */
        public boolean usable() {
            return availability == Availability.USABLE;
        }

        static Status unusable(Availability availability, String reason) {
            return new Status(availability, "", "", 0, reason);
        }
    }

    private TornadoSupport() {
    }

    /** The probe result; computed once per JVM. */
    public static Status status() {
        return Holder.STATUS;
    }

    /** True if kernels can run on {@link #defaultDevice()}. */
    public static boolean isUsable() {
        return status().usable();
    }

    /**
     * The device TornadoVM uses when an execution plan names none (system properties
     * {@code tornado.driver} and {@code tornado.device}, both 0 by default).
     *
     * @throws IllegalStateException if TornadoVM is not usable
     */
    public static TornadoDevice defaultDevice() {
        requireUsable();
        return TornadoExecutionPlan.DEFAULT_DEVICE;
    }

    /**
     * The first device of the given backend, for example {@link TornadoVMBackendType#METAL} or
     * {@link TornadoVMBackendType#OPENCL}. Empty if that backend is not installed in the SDK.
     *
     * @throws IllegalStateException if TornadoVM is not usable
     */
    public static Optional<TornadoDevice> device(TornadoVMBackendType backend) {
        requireUsable();
        return backends().stream()
                .filter(b -> b.getBackendType() == backend && b.getNumDevices() > 0)
                .map(b -> b.getDevice(0))
                .findFirst();
    }

    private static void requireUsable() {
        Status status = status();
        if (!status.usable()) {
            throw new IllegalStateException("TornadoVM is not usable: " + status.reason());
        }
    }

    private static List<TornadoBackend> backends() {
        return TornadoExecutionPlan.getTornadoDeviceMap().getAllBackends();
    }

    /** Probes on first access, so that loading TornadoSupport alone never touches TornadoVM. */
    private static final class Holder {
        static final Status STATUS = probe();
    }

    private static Status probe() {
        if (ModuleLayer.boot().findModule(RUNTIME_MODULE).isEmpty()) {
            return Status.unusable(Availability.NO_FLAGS, "module " + RUNTIME_MODULE
                    + " is not in the boot layer: the JVM was started without the TornadoVM flags (see docs/tornado.md)");
        }
        try {
            List<TornadoBackend> backends = backends();
            int devices = backends.stream().mapToInt(TornadoBackend::getNumDevices).sum();
            if (devices == 0) {
                return Status.unusable(Availability.NO_DEVICE,
                        "TornadoVM found " + backends.size() + " backends but no device");
            }
            TornadoDevice device = TornadoExecutionPlan.DEFAULT_DEVICE;
            return new Status(Availability.USABLE, device.getTornadoVMBackend().name(),
                    device.getPhysicalDevice().getDeviceName(), devices, "");
        } catch (RuntimeException | LinkageError e) {
            Availability availability = noPlatform(e) ? Availability.NO_DEVICE : Availability.START_FAILED;
            return Status.unusable(availability, "TornadoVM failed to start: " + e);
        }
    }

    /** True if the failure, or one of its causes, says that the backend found no platform or device. */
    private static boolean noPlatform(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof TornadoNoMetalPlatformException || t instanceof TornadoNoOpenCLPlatformException
                    || t instanceof TornadoDeviceNotFound || t instanceof TornadoBackendNotFound) {
                return true;
            }
        }
        return false;
    }
}
