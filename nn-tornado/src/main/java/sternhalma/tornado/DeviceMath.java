package sternhalma.tornado;

import uk.ac.manchester.tornado.api.math.TornadoMath;

/**
 * Activations for kernels, written so that the Metal backend keeps the semantics of
 * {@link sternhalma.nn.ReferenceOps}: NaN stays NaN, and tanh saturates to exactly +-1 (docs/tornado.md,
 * "Pitfalls", items 9 and 10). Every kernel of this package uses these helpers instead of {@code Math.max} and
 * {@code TornadoMath.tanh}; TornadoVM inlines them. Called from Java, they run as plain Java.
 */
final class DeviceMath {

    /**
     * Above this magnitude {@link #tanh} returns +-1 without calling Metal's tanh. {@code (float) Math.tanh(x)}
     * is exactly 1 from 9.010914 on (0.99999994 just below). Metal's tanh returns 1.0000001 at some arguments from
     * 8.521233 on, 0 from about 43.67 and NaN from 44.36142 on (measured, docs/tornado.md); below 8.5 it stayed
     * within [-1, 1]. So the result stays in [-1, 1], is exact from 9.010914 on, and agrees with the reference
     * within the tolerance of docs/network.md below (largest error measured: 1.8e-7).
     */
    static final float TANH_SATURATION = 8.5f;

    /** System property that selects the activations: {@code portable} (default) or {@code native}. */
    static final String MODE_PROPERTY = "sternhalma.tornado.devicemath";

    /**
     * Whether the kernels use the plain {@code Math.max} and {@code TornadoMath.tanh} instead of the workarounds
     * (property {@value #MODE_PROPERTY} = {@code native}). The default, {@code portable}, is the code that was
     * validated on Metal, and it is also correct on every other backend: the comparison form of ReLU keeps NaN
     * everywhere, and saturating tanh at 8.5 changes no result beyond the tolerance. {@code native} exists to find
     * out on CUDA and OpenCL whether the workarounds are needed there (docs/tornado.md, "Backends"); on Metal it is
     * refused ({@link #requireMode}), because Metal gives wrong results with it. A {@code static final} read from
     * a system property, so the compiled kernel contains only the chosen branch.
     */
    static final boolean NATIVE = "native".equals(System.getProperty(MODE_PROPERTY, "portable"));

    private DeviceMath() {
    }

    /**
     * ReLU that keeps NaN, like {@code Math.max(v, 0f)} in Java. Metal compiles {@code Math.max} to
     * {@code fmax}, which returns the other operand for a NaN, so a kernel must not use it (measured,
     * docs/tornado.md). -0 stays -0, where {@code Math.max} gives +0; the two compare equal.
     */
    static float relu(float v) {
        if (NATIVE) {
            return Math.max(v, 0f);
        }
        return v < 0f ? 0f : v;
    }

    /**
     * tanh that is exactly +-1 for |x| &gt; {@value #TANH_SATURATION} and +-infinity, and NaN for NaN. Written
     * with comparisons that a NaN fails, so NaN reaches Metal's tanh, which returns NaN. Clamping the argument
     * instead ({@code x > 10 ? 10 : x < -10 ? -10 : x}) turned NaN into 1 on the device: the compiled clamp
     * dropped NaN.
     */
    static float tanh(float x) {
        if (NATIVE) {
            return TornadoMath.tanh(x);
        }
        return x > TANH_SATURATION ? 1f : (x < -TANH_SATURATION ? -1f : TornadoMath.tanh(x));
    }

    /**
     * Whether the workarounds of this class are known to be required on the backend: true on Metal (measured,
     * docs/tornado.md, "Pitfalls" 9 to 11), unknown on the others, where they stay on.
     */
    static boolean workaroundsRequired(DeviceBackend backend) {
        return backend == DeviceBackend.METAL;
    }

    /**
     * Fails if mode {@code native} is selected on a backend that needs the workarounds.
     *
     * @throws IllegalStateException on Metal with {@code native}
     */
    static void requireMode(DeviceBackend backend) {
        if (NATIVE && workaroundsRequired(backend)) {
            throw new IllegalStateException("-D" + MODE_PROPERTY + "=native is wrong on " + backend
                    + ": its tanh and max need the workarounds of DeviceMath");
        }
    }

    /**
     * True for NaN and +-infinity: the exponent bits of the float are all 1. Written on the bits because every
     * floating-point formulation failed somewhere on the Metal backend of TornadoVM 7.3.1: {@code x - x != 0},
     * {@code x != x} and {@code Float.isNaN(x)} give "finite" for NaN, and so does {@code !Float.isFinite(x)} when its
     * result is used as a value ({@code ? 1 : 0}); it worked only as the condition of an {@code if}, probably because the
     * negated comparison is turned into the opposite comparison, which is false for NaN too (docs/tornado.md,
     * "Pitfalls", item 11).
     */
    static boolean notFinite(float x) {
        return (Float.floatToRawIntBits(x) & 0x7f800000) == 0x7f800000;
    }
}
