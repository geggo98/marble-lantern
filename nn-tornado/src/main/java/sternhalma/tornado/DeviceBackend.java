package sternhalma.tornado;

import java.util.Locale;

import uk.ac.manchester.tornado.api.common.TornadoDevice;
import uk.ac.manchester.tornado.api.enums.TornadoVMBackendType;

/**
 * The TornadoVM backend kinds this module distinguishes, and what each one needs from the kernels
 * (docs/tornado.md, "Backends"). Only METAL has the SIMD-group matrix operations; every other backend runs the
 * generic {@code KernelContext} kernels.
 *
 * <p>TornadoVM 7.3.1 has the backends OPENCL, METAL and CUDA (CUDA C compiled with NVRTC; the PTX and SPIR-V
 * backends were removed in 6.0.0). {@code ptx} is accepted as an alias of {@code cuda} when a backend is named.
 */
public enum DeviceBackend {
    /** Apple Metal: SIMD-group matrix kernels, and the workarounds of {@link DeviceMath}. */
    METAL,
    /** NVIDIA, CUDA C through NVRTC. */
    CUDA,
    /** Any OpenCL device. */
    OPENCL,
    /** Anything else, for example the JAVA backend; treated like OPENCL. */
    OTHER;

    /** System property that forces the generic kernels on every backend, also on METAL ({@code true}). */
    public static final String FORCE_GENERIC_PROPERTY = "sternhalma.tornado.generic";

    /**
     * Local work-group size that no kernel of this module exceeds: {@code OpsKernels.VALUE_GROUP}, and the group of
     * {@link KernelsConvBackward#weightPartialTiled}. CUDA allows 1024, OpenCL at least 256 on every GPU.
     */
    public static final int MAX_GROUP_SIZE = 256;

    /** Local memory that the kernels of this module need per work group, with room: 32 KiB (the OpenCL minimum). */
    public static final long MIN_LOCAL_MEMORY = 32 * 1024;

    /** The backend of a TornadoVM backend type. */
    public static DeviceBackend of(TornadoVMBackendType type) {
        return switch (type) {
            case METAL -> METAL;
            case CUDA -> CUDA;
            case OPENCL -> OPENCL;
            default -> OTHER;
        };
    }

    /** The backend of a device. */
    public static DeviceBackend of(TornadoDevice device) {
        return of(device.getTornadoVMBackend());
    }

    /**
     * Parses a backend name, case-insensitive: {@code metal}, {@code cuda}, {@code ptx} (= cuda), {@code opencl}.
     *
     * @throws IllegalArgumentException for any other name
     */
    public static DeviceBackend parse(String name) {
        return switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "metal" -> METAL;
            case "cuda", "ptx" -> CUDA;
            case "opencl" -> OPENCL;
            default -> throw new IllegalArgumentException("unknown TornadoVM backend '" + name
                    + "' (metal|cuda|ptx|opencl)");
        };
    }

    /** Whether kernels of this backend may use the SIMD-group matrix operations of {@code KernelContext}. */
    public boolean hasSimdgroupMatrix() {
        return this == METAL;
    }

    /** True if the system property {@link #FORCE_GENERIC_PROPERTY} is {@code true}. */
    public static boolean forceGeneric() {
        return Boolean.getBoolean(FORCE_GENERIC_PROPERTY);
    }

    /**
     * Whether the SIMD-group kernels run on this device: the backend has the matrix operations and the generic
     * kernels are not forced.
     */
    public static boolean useSimdgroup(TornadoDevice device) {
        return of(device).hasSimdgroupMatrix() && !forceGeneric();
    }

    /**
     * Checks the configuration and the limits that the kernels of this module rely on: the {@link DeviceMath} mode, a work group of {@link #MAX_GROUP_SIZE} items and
     * {@link #MIN_LOCAL_MEMORY} bytes of local memory. The device is queried, not assumed.
     *
     * @throws IllegalStateException if the device is below a limit
     */
    public static void requireLimits(TornadoDevice device) {
        DeviceMath.requireMode(of(device));
        var physical = device.getPhysicalDevice();
        long[] group = physical.getDeviceMaxWorkGroupSize();
        long local = physical.getDeviceLocalMemorySize();
        if (group.length > 0 && group[0] < MAX_GROUP_SIZE) {
            throw new IllegalStateException(physical.getDeviceName() + " allows work groups of " + group[0]
                    + " items, the kernels need " + MAX_GROUP_SIZE);
        }
        if (local < MIN_LOCAL_MEMORY) {
            throw new IllegalStateException(physical.getDeviceName() + " has " + local
                    + " bytes of local memory, the kernels need " + MIN_LOCAL_MEMORY);
        }
    }

    /**
     * Fails if {@code device} cannot run the SIMD-group kernels; used where a caller forces
     * {@code Variant.SIMDGROUP}, so that a forced variant never silently becomes another one.
     *
     * @throws IllegalStateException on a device without SIMD-group matrix operations
     */
    public static void requireSimdgroup(TornadoDevice device) {
        if (!of(device).hasSimdgroupMatrix()) {
            throw new IllegalStateException("the SIMDGROUP kernels need the Metal backend, this device is "
                    + of(device) + " (use TILED)");
        }
    }
}
