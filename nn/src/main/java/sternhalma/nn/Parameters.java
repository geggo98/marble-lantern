package sternhalma.nn;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/**
 * The named parameter tensors of one network (docs/network.md, "Tensor names", "Initialisation").
 *
 * <p>The tensor names and shapes follow {@link #shapes(NetworkConfig)} exactly, in that order. The float
 * arrays are mutable and shared with callers, so that a trainer can update them in place; a {@link Network}
 * reads them on every forward pass.
 */
public final class Parameters {

    private final NetworkConfig config;
    private final Map<String, Tensor> tensors;
    private final Map<String, String> extraMetadata;

    private Parameters(NetworkConfig config, LinkedHashMap<String, Tensor> tensors,
            Map<String, String> extraMetadata) {
        this.config = config;
        this.tensors = Collections.unmodifiableMap(tensors);
        this.extraMetadata = new LinkedHashMap<>(extraMetadata);
    }

    /**
     * Names and shapes of all tensors of a config, in file order: conv_in.weight, conv_in.bias, then for each
     * block i the weights and biases of block{i}.conv1 and block{i}.conv2, then head_policy.weight,
     * head_policy.bias, head_value.dense1.weight, head_value.dense1.bias, head_value.dense2.weight,
     * head_value.dense2.bias. The returned map is new and owned by the caller.
     */
    public static LinkedHashMap<String, int[]> shapes(NetworkConfig config) {
        int c = config.channels();
        int h = config.valueHidden();
        int p = config.players();
        int conv = HexGrid.STENCIL * c;
        LinkedHashMap<String, int[]> shapes = new LinkedHashMap<>();
        shapes.put("conv_in.weight", new int[] {c, HexGrid.STENCIL * Encoder.PLANES});
        shapes.put("conv_in.bias", new int[] {c});
        for (int i = 0; i < config.blocks(); i++) {
            shapes.put("block" + i + ".conv1.weight", new int[] {c, conv});
            shapes.put("block" + i + ".conv1.bias", new int[] {c});
            shapes.put("block" + i + ".conv2.weight", new int[] {c, conv});
            shapes.put("block" + i + ".conv2.bias", new int[] {c});
        }
        shapes.put("head_policy.weight", new int[] {Encoder.POLICY_CHANNELS, c});
        shapes.put("head_policy.bias", new int[] {Encoder.POLICY_CHANNELS});
        shapes.put("head_value.dense1.weight", new int[] {h, c});
        shapes.put("head_value.dense1.bias", new int[] {h});
        shapes.put("head_value.dense2.weight", new int[] {p, h});
        shapes.put("head_value.dense2.bias", new int[] {p});
        return shapes;
    }

    /**
     * Initial parameters: weights uniform in [-a, a] with a = sqrt(6 / fanIn), biases 0. One
     * {@code java.util.Random(seed)} fills the tensors in {@link #shapes} order, elements in row-major order,
     * each as {@code (random.nextFloat() * 2 - 1) * a}. The same seed gives the same parameters.
     *
     * <p>Every weight tensor has the shape [out][in], so fanIn is its second dimension: 7 * C_in for a hex
     * convolution, the input width for the policy head and the dense layers. Biases draw no random numbers,
     * so the first element of {@code block0.conv1.weight} is draw number 7 * 7 * C + 1.
     */
    public static Parameters init(NetworkConfig config, long seed) {
        Random random = new Random(seed);
        LinkedHashMap<String, Tensor> tensors = new LinkedHashMap<>();
        for (Map.Entry<String, int[]> e : shapes(config).entrySet()) {
            Tensor t = Tensor.zeros(e.getKey(), e.getValue());
            if (!isBias(e.getKey())) {
                float a = (float) Math.sqrt(6.0 / e.getValue()[1]);
                fillUniform(t.data(), random, a);
            }
            tensors.put(e.getKey(), t);
        }
        return new Parameters(config, tensors, Map.of());
    }

    /**
     * Parameters for tests: every tensor, biases included, uniform in [-bound, bound], drawn as in
     * {@link #init} with {@code (random.nextFloat() * 2 - 1) * bound}.
     *
     * @throws IllegalArgumentException if bound is not positive and finite
     */
    public static Parameters uniform(NetworkConfig config, long seed, float bound) {
        if (!(bound > 0) || !Float.isFinite(bound)) {
            throw new IllegalArgumentException("bound must be positive and finite, got " + bound);
        }
        Random random = new Random(seed);
        LinkedHashMap<String, Tensor> tensors = new LinkedHashMap<>();
        for (Map.Entry<String, int[]> e : shapes(config).entrySet()) {
            Tensor t = Tensor.zeros(e.getKey(), e.getValue());
            fillUniform(t.data(), random, bound);
            tensors.put(e.getKey(), t);
        }
        return new Parameters(config, tensors, Map.of());
    }

    /**
     * Parameters from given tensors, for example hand-made weights in a test. The tensors are used, not
     * copied. The map may have any iteration order; {@link #tensors()} is in {@link #shapes} order.
     *
     * @throws IllegalArgumentException if the names or shapes differ from {@link #shapes} of the config, or a
     *                                  key differs from its tensor's name
     */
    public static Parameters of(NetworkConfig config, Map<String, Tensor> tensors) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(tensors, "tensors");
        String problem = mismatch(config, tensors);
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
        return new Parameters(config, ordered(config, tensors), Map.of());
    }

    /**
     * Reads a safetensors file written by {@link #save}. The config comes from the metadata
     * ({@link NetworkConfig#fromMetadata}); metadata keys beyond the config are kept as
     * {@link #extraMetadata()}.
     *
     * <p>Memory use is bounded by the file: the tensors are those of the file, and the config only names the
     * expected shapes, which {@link NetworkConfig}'s size limits keep small. Every tensor name and shape is
     * compared with the config before any {@code Parameters} is built.
     *
     * @throws IOException if the file is not a valid safetensors file, its metadata does not describe a known
     *                     config (also a size above the limits of {@link NetworkConfig}), or its tensor names
     *                     or shapes differ from {@link #shapes} of that config; never an unchecked exception
     *                     for a malformed file
     */
    public static Parameters load(Path file) throws IOException {
        Safetensors.Contents contents = Safetensors.read(file);
        NetworkConfig config;
        try {
            config = NetworkConfig.fromMetadata(contents.metadata());
        } catch (IllegalArgumentException e) {
            throw new IOException(file + ": " + e.getMessage(), e);
        }
        String problem = mismatch(config, contents.tensors());
        if (problem != null) {
            throw new IOException(file + ": " + problem);
        }
        Map<String, String> extra = new LinkedHashMap<>(contents.metadata());
        extra.keySet().removeAll(config.toMetadata().keySet());
        return new Parameters(config, ordered(config, contents.tensors()), extra);
    }

    /**
     * Writes the tensors in {@link #shapes} order with the metadata {@link NetworkConfig#toMetadata()} followed
     * by {@link #extraMetadata()}.
     *
     * @throws IllegalStateException if the extra metadata contains a config key, a null key or a null value
     * @throws IOException           if writing fails
     */
    public void save(Path file) throws IOException {
        Map<String, String> metadata = config.toMetadata();
        for (Map.Entry<String, String> e : extraMetadata.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                throw new IllegalStateException("extra metadata has a null key or value: " + e);
            }
            if (metadata.containsKey(e.getKey())) {
                throw new IllegalStateException("extra metadata must not contain the config key " + e.getKey());
            }
        }
        metadata.putAll(extraMetadata);
        Safetensors.write(file, tensors, metadata);
    }

    public NetworkConfig config() {
        return config;
    }

    /**
     * The tensor with the given name.
     *
     * @throws IllegalArgumentException if the config has no tensor of that name
     */
    public Tensor get(String name) {
        Tensor t = tensors.get(name);
        if (t == null) {
            throw new IllegalArgumentException("no tensor named " + name + " in " + config);
        }
        return t;
    }

    /** All tensors in {@link #shapes} order, as an unmodifiable view. */
    public Map<String, Tensor> tensors() {
        return tensors;
    }

    /**
     * Metadata saved with the parameters besides the config, for example a training step. The map is
     * modifiable and is written by {@link #save}.
     */
    public Map<String, String> extraMetadata() {
        return extraMetadata;
    }

    private static boolean isBias(String name) {
        return name.endsWith(".bias");
    }

    private static void fillUniform(float[] data, Random random, float bound) {
        for (int i = 0; i < data.length; i++) {
            data[i] = (random.nextFloat() * 2 - 1) * bound;
        }
    }

    /** A description of the first difference from {@link #shapes}, or null if there is none. */
    private static String mismatch(NetworkConfig config, Map<String, Tensor> tensors) {
        LinkedHashMap<String, int[]> expected = shapes(config);
        for (Map.Entry<String, int[]> e : expected.entrySet()) {
            Tensor t = tensors.get(e.getKey());
            if (t == null) {
                return "tensor " + e.getKey() + " is missing";
            }
            if (!t.name().equals(e.getKey())) {
                return "key " + e.getKey() + " holds the tensor " + t.name();
            }
            if (!Arrays.equals(t.shape(), e.getValue())) {
                return "tensor " + e.getKey() + " has shape " + Arrays.toString(t.shape()) + ", expected "
                        + Arrays.toString(e.getValue());
            }
        }
        for (String name : tensors.keySet()) {
            if (!expected.containsKey(name)) {
                return "unexpected tensor " + name + " for " + config;
            }
        }
        return null;
    }

    private static LinkedHashMap<String, Tensor> ordered(NetworkConfig config, Map<String, Tensor> tensors) {
        LinkedHashMap<String, Tensor> result = new LinkedHashMap<>();
        for (String name : shapes(config).keySet()) {
            result.put(name, tensors.get(name));
        }
        return result;
    }
}
