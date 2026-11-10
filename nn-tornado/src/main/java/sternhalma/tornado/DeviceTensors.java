package sternhalma.tornado;

import java.util.LinkedHashMap;
import java.util.Map;

import sternhalma.nn.NetworkConfig;
import sternhalma.nn.Parameters;
import sternhalma.nn.Tensor;
import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;

/**
 * One zero-filled {@link FloatArray} per tensor of {@link Parameters#shapes}, in that order and with the layout
 * of the tensor: the shape of the gradients and of the optimiser state (Adam's m and v, or the SGD velocity) on
 * the device. The parameters themselves are {@link OpsParameters}.
 *
 * <p>"Flat order" of docs/training.md, Part B means this order: the existing forward kernels take one array per
 * tensor, so the device does not keep one contiguous buffer; {@link #flatten} and {@link #unflatten} convert to
 * and from the file-order float array of a host copy.
 */
public final class DeviceTensors {

    private final NetworkConfig config;
    private final LinkedHashMap<String, FloatArray> arrays = new LinkedHashMap<>();
    private final FloatArray[] all;
    private final String[] names;

    /** New arrays, all 0, for the tensors of {@code config}. */
    public DeviceTensors(NetworkConfig config) {
        this.config = config;
        for (Map.Entry<String, int[]> e : Parameters.shapes(config).entrySet()) {
            FloatArray a = new FloatArray((int) Tensor.elementCount(e.getValue()));
            a.init(0f);
            arrays.put(e.getKey(), a);
        }
        this.all = arrays.values().toArray(new FloatArray[0]);
        this.names = arrays.keySet().toArray(new String[0]);
    }

    public NetworkConfig config() {
        return config;
    }

    /**
     * The array of a tensor.
     *
     * @throws IllegalArgumentException if the config has no tensor of that name
     */
    public FloatArray get(String name) {
        FloatArray a = arrays.get(name);
        if (a == null) {
            throw new IllegalArgumentException("no tensor named " + name + " in " + config);
        }
        return a;
    }

    /** The tensor names in {@link Parameters#shapes} order. */
    String[] names() {
        return names.clone();
    }

    /** All arrays in {@link Parameters#shapes} order (the live arrays, not copies). */
    FloatArray[] arrays() {
        return all.clone();
    }

    /** Adds every array to the graph with the given transfer mode (a {@code DataTransferMode} constant). */
    TaskGraph declareOn(TaskGraph graph, int mode) {
        return graph.transferToDevice(mode, (Object[]) all);
    }

    /** The values of a tensor as a new heap array. */
    float[] copyOf(String name) {
        return get(name).toHeapArray();
    }

    /** Copies the values of a map of host arrays (one per tensor, same lengths) into the arrays. */
    void set(Map<String, float[]> values) {
        for (Map.Entry<String, FloatArray> e : arrays.entrySet()) {
            float[] v = values.get(e.getKey());
            if (v == null || v.length != e.getValue().getSize()) {
                throw new IllegalArgumentException("tensor " + e.getKey() + " is missing or has the wrong length");
            }
            OpsCopy.copy(v, 0, e.getValue(), 0, v.length);
        }
    }

    /** All tensors in one float array, in {@link Parameters#shapes} order. */
    public float[] flatten() {
        int total = 0;
        for (FloatArray a : all) {
            total += a.getSize();
        }
        float[] flat = new float[total];
        int at = 0;
        for (FloatArray a : all) {
            OpsCopy.copy(a, 0, flat, at, a.getSize());
            at += a.getSize();
        }
        return flat;
    }

    /**
     * The inverse of {@link #flatten}.
     *
     * @throws IllegalArgumentException if the length is not the total size of the tensors
     */
    public void unflatten(float[] flat) {
        int total = 0;
        for (FloatArray a : all) {
            total += a.getSize();
        }
        if (flat.length != total) {
            throw new IllegalArgumentException("flat array has " + flat.length + " floats, the tensors have " + total);
        }
        int at = 0;
        for (FloatArray a : all) {
            OpsCopy.copy(flat, at, a, 0, a.getSize());
            at += a.getSize();
        }
    }
}
