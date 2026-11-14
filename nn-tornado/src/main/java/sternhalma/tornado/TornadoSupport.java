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

    /** Environment variable that names the backend ({@code metal}, {@code cuda}, {@code ptx}, {@code opencl}). */
    public static final String BACKEND_ENV = "TORNADO_BACKEND";
    /** System property for the backend; it wins over {@link #BACKEND_ENV}. */
    public static final String BACKEND_PROPERTY = "sternhalma.tornado.backend";
    /** Environment variable that names the device index within the backend (default 0). */
    public static final String DEVICE_ENV = "TORNADO_DEVICE";
    /** System property for the device index; it wins over {@link #DEVICE_ENV}. */
    public static final String DEVICE_PROPERTY = "sternhalma.tornado.device";

    /**
     * The backend named by {@link #BACKEND_PROPERTY} or {@link #BACKEND_ENV}, empty if none or {@code auto}. A named
     * backend that is not usable is an error, never a reason to run on something else.
     */
    public static Optional<String> requestedBackend() {
        String named = setting(BACKEND_PROPERTY, BACKEND_ENV);
        return named == null || named.isBlank() || named.trim().equalsIgnoreCase("auto")
                ? Optional.empty() : Optional.of(named.trim());
    }

    /** The probe result; computed once per JVM. */
    public static Status status() {
        return Holder.PROBE.status();
    }

    /** True if kernels can run on {@link #defaultDevice()}. */
    public static boolean isUsable() {
        return status().usable();
    }

    /**
     * The device this project runs on. With a backend named by {@link #BACKEND_PROPERTY} or {@link #BACKEND_ENV}
     * it is device number {@link #DEVICE_PROPERTY} / {@link #DEVICE_ENV} (default 0) of that backend; a backend or
     * index that does not exist makes TornadoVM not usable, with the reason, and never selects another device.
     * Without a name it is the device TornadoVM uses when an execution plan names none (system properties
     * {@code tornado.driver} and {@code tornado.device}, both 0 by default). The choice is logged once.
     *
     * @throws IllegalStateException if TornadoVM is not usable
     */
    public static TornadoDevice defaultDevice() {
        requireUsable();
        return Holder.PROBE.device();
    }

    /** The {@link DeviceBackend} of {@link #defaultDevice()}. */
    public static DeviceBackend backend() {
        return DeviceBackend.of(defaultDevice());
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

    /** The probe result and the selected device (null if not usable). */
    private record Probe(Status status, TornadoDevice device) {
    }

    /** Probes on first access, so that loading TornadoSupport alone never touches TornadoVM. */
    private static final class Holder {
        static final Probe PROBE = probe();
    }

    private static Probe probe() {
        if (ModuleLayer.boot().findModule(RUNTIME_MODULE).isEmpty()) {
            return new Probe(Status.unusable(Availability.NO_FLAGS, "module " + RUNTIME_MODULE
                    + " is not in the boot layer: the JVM was started without the TornadoVM flags (see docs/tornado.md)"),
                    null);
        }
        try {
            List<TornadoBackend> backends = backends();
            int devices = backends.stream().mapToInt(TornadoBackend::getNumDevices).sum();
            if (devices == 0) {
                return new Probe(Status.unusable(Availability.NO_DEVICE,
                        "TornadoVM found " + backends.size() + " backends but no device"), null);
            }
            String named = setting(BACKEND_PROPERTY, BACKEND_ENV);
            TornadoDevice device;
            if (named == null || named.isBlank() || named.trim().equalsIgnoreCase("auto")) {
                device = TornadoExecutionPlan.DEFAULT_DEVICE;
            } else {
                DeviceBackend wanted = DeviceBackend.parse(named);
                int index = Integer.parseInt(setting(DEVICE_PROPERTY, DEVICE_ENV, "0").trim());
                Optional<TornadoDevice> found = Optional.empty();
                for (TornadoBackend b : backends) {
                    if (DeviceBackend.of(b.getBackendType()) == wanted && index >= 0 && index < b.getNumDevices()) {
                        found = Optional.of(b.getDevice(index));
                        break;
                    }
                }
                if (found.isEmpty()) {
                    return new Probe(Status.unusable(Availability.NO_DEVICE, "backend " + wanted + " device " + index
                            + " was requested (" + BACKEND_ENV + "=" + named + ") but is not available; installed: "
                            + describe(backends)), null);
                }
                device = found.get();
            }
            Status status = new Status(Availability.USABLE, device.getTornadoVMBackend().name(),
                    device.getPhysicalDevice().getDeviceName(), devices, "");
            System.getLogger("sternhalma.tornado").log(System.Logger.Level.INFO,
                    "TornadoVM device: backend {0}, name {1}, {2} device(s) in total; available: {3}",
                    status.backend(), status.device(), devices, describe(backends));
            return new Probe(status, device);
        } catch (RuntimeException | LinkageError e) {
            Availability availability = noPlatform(e) ? Availability.NO_DEVICE : Availability.START_FAILED;
            return new Probe(Status.unusable(availability, "TornadoVM failed to start: " + e), null);
        }
    }

    /** {@code BACKEND[index]=name, ...} of every device. */
    private static String describe(List<TornadoBackend> backends) {
        StringBuilder sb = new StringBuilder();
        for (TornadoBackend b : backends) {
            for (int i = 0; i < b.getNumDevices(); i++) {
                if (!sb.isEmpty()) {
                    sb.append(", ");
                }
                sb.append(b.getBackendType()).append('[').append(i).append("]=")
                        .append(b.getDevice(i).getPhysicalDevice().getDeviceName());
            }
        }
        return sb.isEmpty() ? "none" : sb.toString();
    }

    private static String setting(String property, String env) {
        return setting(property, env, null);
    }

    private static String setting(String property, String env, String fallback) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            value = System.getenv(env);
        }
        return value == null || value.isBlank() ? fallback : value;
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
