package sternhalma.nn;

import java.util.Arrays;
import java.util.Objects;

/**
 * A named float32 tensor: a shape and its elements in row-major order (docs/network.md, "Tensor layout").
 *
 * <p>The shape is copied on construction and on access, so it cannot change. The data array is not copied:
 * it is shared on purpose, so that a training step can update parameters in place. Callers that need a
 * snapshot copy it themselves.
 *
 * <p>{@link #equals} compares name, shape and data with {@link Arrays#equals(float[], float[])}: two NaN
 * elements are equal, and 0.0f differs from -0.0f.
 *
 * @param name  the tensor name, for example {@code conv_in.weight}; not empty
 * @param shape the dimensions, each &gt;= 0; an empty shape is a scalar with one element
 * @param data  the elements, row-major; its length is the product of the dimensions
 */
public record Tensor(String name, int[] shape, float[] data) {

    /**
     * @throws NullPointerException     if an argument is null
     * @throws IllegalArgumentException if the name is empty, a dimension is negative, or the data length
     *                                  differs from the product of the dimensions
     */
    public Tensor {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(shape, "shape");
        Objects.requireNonNull(data, "data");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("tensor name is empty");
        }
        shape = shape.clone();
        long size = elementCount(shape);
        if (size != data.length) {
            throw new IllegalArgumentException("tensor " + name + ": shape " + Arrays.toString(shape)
                    + " needs " + size + " elements, data has " + data.length);
        }
    }

    /** A tensor of zeros. */
    public static Tensor zeros(String name, int... shape) {
        long size = elementCount(shape);
        if (size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("tensor " + name + " is too large: " + size + " elements");
        }
        return new Tensor(name, shape, new float[(int) size]);
    }

    /**
     * The product of the dimensions; 1 for an empty shape, 0 if any dimension is 0 (whatever the others are).
     * Without a zero dimension, a product above {@link Integer#MAX_VALUE} is returned as some value above
     * {@link Integer#MAX_VALUE}, not necessarily the exact product.
     *
     * @throws IllegalArgumentException if a dimension is negative
     */
    public static long elementCount(int[] shape) {
        boolean zero = false;
        for (int d : shape) {
            if (d < 0) {
                throw new IllegalArgumentException("negative dimension in shape " + Arrays.toString(shape));
            }
            zero |= d == 0;
        }
        if (zero) {
            return 0;
        }
        long size = 1;
        for (int d : shape) {
            size *= d;
            // A product above Integer.MAX_VALUE cannot match any float[]; stop before a long overflow.
            if (size > Integer.MAX_VALUE) {
                return size;
            }
        }
        return size;
    }

    /** A copy of the shape. */
    @Override
    public int[] shape() {
        return shape.clone();
    }

    /** Number of elements. */
    public int size() {
        return data.length;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Tensor other
                && name.equals(other.name)
                && Arrays.equals(shape, other.shape)
                && Arrays.equals(data, other.data);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * name.hashCode() + Arrays.hashCode(shape)) + Arrays.hashCode(data);
    }

    @Override
    public String toString() {
        return "Tensor[" + name + ", shape=" + Arrays.toString(shape) + "]";
    }
}
