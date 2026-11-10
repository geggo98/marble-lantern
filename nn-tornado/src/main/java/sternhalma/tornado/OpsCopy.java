package sternhalma.tornado;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/** Bulk copies between Java float arrays and TornadoVM {@link FloatArray}s (host memory, no device access). */
final class OpsCopy {

    private OpsCopy() {
    }

    /**
     * Copies {@code length} floats from {@code src[srcIndex...]} to {@code dst[dstIndex...]}.
     *
     * @throws IndexOutOfBoundsException if a range is outside its array
     */
    static void copy(float[] src, int srcIndex, FloatArray dst, int dstIndex, int length) {
        MemorySegment.copy(src, srcIndex, dst.getSegment(), ValueLayout.JAVA_FLOAT, 4L * dstIndex, length);
    }

    /**
     * Copies {@code length} floats from {@code src[srcIndex...]} to {@code dst[dstIndex...]}.
     *
     * @throws IndexOutOfBoundsException if a range is outside its array
     */
    static void copy(FloatArray src, int srcIndex, float[] dst, int dstIndex, int length) {
        MemorySegment.copy(src.getSegment(), ValueLayout.JAVA_FLOAT, 4L * srcIndex, dst, dstIndex, length);
    }

    /**
     * Copies {@code length} ints from {@code src[srcIndex...]} to {@code dst[dstIndex...]}.
     *
     * @throws IndexOutOfBoundsException if a range is outside its array
     */
    static void copy(int[] src, int srcIndex, IntArray dst, int dstIndex, int length) {
        MemorySegment.copy(src, srcIndex, dst.getSegment(), ValueLayout.JAVA_INT, 4L * dstIndex, length);
    }
}
