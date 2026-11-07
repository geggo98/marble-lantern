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

    private DeviceMath() {
    }

    /**
     * ReLU that keeps NaN, like {@code Math.max(v, 0f)} in Java. Metal compiles {@code Math.max} to
     * {@code fmax}, which returns the other operand for a NaN, so a kernel must not use it (measured,
     * docs/tornado.md). -0 stays -0, where {@code Math.max} gives +0; the two compare equal.
     */
    static float relu(float v) {
        return v < 0f ? 0f : v;
    }

    /**
     * tanh that is exactly +-1 for |x| &gt; {@value #TANH_SATURATION} and +-infinity, and NaN for NaN. Written
     * with comparisons that a NaN fails, so NaN reaches Metal's tanh, which returns NaN. Clamping the argument
     * instead ({@code x > 10 ? 10 : x < -10 ? -10 : x}) turned NaN into 1 on the device: the compiled clamp
     * dropped NaN.
     */
    static float tanh(float x) {
        return x > TANH_SATURATION ? 1f : (x < -TANH_SATURATION ? -1f : TornadoMath.tanh(x));
    }
}
