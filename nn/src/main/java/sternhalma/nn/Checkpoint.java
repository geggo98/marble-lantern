package sternhalma.nn;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Training checkpoints (docs/training.md, "Checkpoints"): one safetensors file with the parameters, the optimiser
 * state and the metadata that a resumed run needs. {@link Safetensors#write} writes it through a temporary file and
 * a rename, so a file under its final name is complete.
 *
 * <p>Tensors: the names of {@link Parameters#shapes}, and {@code optim.m.<name>} and {@code optim.v.<name>}
 * (AdamW) or {@code optim.u.<name>} (SGD) for every parameter tensor. Metadata: the network keys of
 * {@link NetworkConfig#toMetadata} with {@code format} = {@value #FORMAT}, plus {@code optimizer} ({@code adamw}
 * or {@code sgd}), {@code step} (completed steps), {@code generation}, the six hyperparameters of
 * {@link Optimizer.Settings} as decimal strings ({@code beta1}, {@code beta2}, {@code eps},
 * {@code weight_decay}, {@code momentum}, {@code l2}; {@link Double#toString} round-trips exactly) and
 * {@code run_config_sha256}. Unknown further keys are ignored when reading.
 *
 * <p>Writing does not fsync: after a power loss the newest file may be missing or damaged, and {@link #recover}
 * falls back to an older one.
 *
 * <p>The reader accepts a file only if everything is consistent and finite; any problem is an
 * {@link IOException}, never an unchecked exception. The file format has no checksum: damage inside the tensor data
 * that leaves every number finite is not detected.
 */
public final class Checkpoint {

    /** Value of the metadata key {@code format}. */
    public static final String FORMAT = "sternhalma-train";

    private static final Pattern NAME = Pattern.compile("step-(?<step>\\d{8,18})\\.safetensors");
    private static final Pattern TEMPORARY = Pattern.compile("step-\\d{8,18}\\.safetensors\\.[0-9a-f]+\\.tmp");
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{64}");

    private Checkpoint() {
    }

    /**
     * What a checkpoint holds. The arrays of {@code parameters} and {@code optimizer} are those read from the file.
     *
     * @param generation        the generation that the run was in
     * @param runConfigSha256   hash of the run configuration, for the caller to compare with its own
     */
    public record Loaded(Parameters parameters, Optimizer optimizer, int generation, String runConfigSha256) {

        /** Completed steps. */
        public long step() {
            return optimizer.steps();
        }
    }

    /**
     * The result of {@link #recover}.
     *
     * @param loaded      the newest checkpoint that reads, or null
     * @param quarantined the files that failed and were renamed to {@code *.corrupt}, newest first
     */
    public record Recovery(Loaded loaded, List<Path> quarantined) {
    }

    // ------------------------------------------------------------------ one file

    /**
     * Writes a checkpoint.
     *
     * @param runConfigSha256 64 lowercase hexadecimal digits
     * @return the size of the file in bytes
     * @throws IllegalArgumentException if the generation is negative or the hash is malformed
     * @throws IOException              if writing fails
     */
    public static long write(Path file, Parameters parameters, Optimizer optimizer, int generation,
            String runConfigSha256) throws IOException {
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(optimizer, "optimizer");
        if (generation < 0) {
            throw new IllegalArgumentException("generation must be >= 0, got " + generation);
        }
        requireSha(runConfigSha256);
        Optimizer.Settings s = optimizer.settings();
        Map<String, String> metadata = parameters.config().toMetadata();
        metadata.put("format", FORMAT);
        metadata.put("optimizer", s.kind().id());
        metadata.put("step", Long.toString(optimizer.steps()));
        metadata.put("generation", Integer.toString(generation));
        metadata.put("beta1", Double.toString(s.beta1()));
        metadata.put("beta2", Double.toString(s.beta2()));
        metadata.put("eps", Double.toString(s.eps()));
        metadata.put("weight_decay", Double.toString(s.weightDecay()));
        metadata.put("momentum", Double.toString(s.momentum()));
        metadata.put("l2", Double.toString(s.l2()));
        metadata.put("run_config_sha256", runConfigSha256);

        Map<String, Tensor> tensors = new LinkedHashMap<>(parameters.tensors());
        if (s.kind() == Optimizer.Kind.ADAMW) {
            addState(tensors, parameters, "optim.m.", optimizer.m());
            addState(tensors, parameters, "optim.v.", optimizer.v());
        } else {
            addState(tensors, parameters, "optim.u.", optimizer.u());
        }
        Safetensors.write(file, tensors, metadata);
        return Files.size(file);
    }

    /**  IllegalArgumentException unless the text is 64 lowercase hexadecimal digits */
    static void requireSha(String runConfigSha256) {
        if (!SHA.matcher(Objects.requireNonNull(runConfigSha256, "runConfigSha256")).matches()) {
            throw new IllegalArgumentException("runConfigSha256 must be 64 lowercase hex digits");
        }
    }

    private static void addState(Map<String, Tensor> tensors, Parameters parameters, String prefix,
            Map<String, float[]> state) {
        for (Map.Entry<String, Tensor> e : parameters.tensors().entrySet()) {
            String name = prefix + e.getKey();
            tensors.put(name, new Tensor(name, e.getValue().shape(), state.get(e.getKey())));
        }
    }

    /**
     * Reads a checkpoint.
     *
     * @throws IOException if the file cannot be read, is not a valid safetensors file, or is not a consistent
     *                     checkpoint: format, version, optimiser, hyperparameters, counts or hash missing or
     *                     malformed; tensor names or shapes differ from what the config and the optimiser
     *                     require; a number in the parameters or the state is not finite, or a second moment is
     *                     negative
     */
    public static Loaded read(Path file) throws IOException {
        Safetensors.Contents contents = Safetensors.read(file);
        try {
            return parse(contents);
        } catch (IllegalArgumentException e) {
            throw new IOException(file + ": " + e.getMessage(), e);
        }
    }

    private static Loaded parse(Safetensors.Contents contents) throws IOException {
        Map<String, String> md = contents.metadata();
        if (!FORMAT.equals(md.get("format"))) {
            throw new IOException("format is " + md.get("format") + ", expected " + FORMAT);
        }
        Map<String, String> network = new LinkedHashMap<>(md);
        network.put("format", NetworkConfig.FORMAT);
        NetworkConfig config = NetworkConfig.fromMetadata(network);
        Optimizer.Kind kind = Optimizer.Kind.ofId(md.get("optimizer"));
        if (kind == null) {
            throw new IOException("unknown optimizer: " + md.get("optimizer"));
        }
        long step = count(md, "step", 18);
        int generation = (int) count(md, "generation", 9);
        Optimizer.Settings settings = new Optimizer.Settings(kind, number(md, "beta1"), number(md, "beta2"),
                number(md, "eps"), number(md, "weight_decay"), number(md, "momentum"), number(md, "l2"));
        String sha = md.get("run_config_sha256");
        if (sha == null || !SHA.matcher(sha).matches()) {
            throw new IOException("run_config_sha256 is missing or not 64 lowercase hex digits");
        }

        Map<String, Tensor> parameterTensors = new LinkedHashMap<>();
        Set<String> state = new LinkedHashSet<>();
        for (Map.Entry<String, Tensor> e : contents.tensors().entrySet()) {
            if (e.getKey().startsWith("optim.")) {
                state.add(e.getKey());
            } else {
                parameterTensors.put(e.getKey(), e.getValue());
            }
        }
        Parameters parameters = Parameters.of(config, parameterTensors);
        for (Tensor t : parameters.tensors().values()) {
            requireFinite(t, false);
        }
        Map<String, float[]> m = null;
        Map<String, float[]> v = null;
        Map<String, float[]> u = null;
        if (kind == Optimizer.Kind.ADAMW) {
            m = stateOf(contents, parameters, "optim.m.", false, state);
            v = stateOf(contents, parameters, "optim.v.", true, state);
        } else {
            u = stateOf(contents, parameters, "optim.u.", false, state);
        }
        if (!state.isEmpty()) {
            throw new IOException("unexpected tensor " + state.iterator().next() + " for optimizer " + kind.id());
        }
        return new Loaded(parameters, Optimizer.restore(parameters, settings, step, m, v, u), generation, sha);
    }

    private static Map<String, float[]> stateOf(Safetensors.Contents contents, Parameters parameters, String prefix,
            boolean nonNegative, Set<String> unclaimed) throws IOException {
        Map<String, float[]> result = new LinkedHashMap<>();
        for (Map.Entry<String, Tensor> e : parameters.tensors().entrySet()) {
            String name = prefix + e.getKey();
            Tensor t = contents.tensors().get(name);
            if (t == null) {
                throw new IOException("tensor " + name + " is missing");
            }
            if (!java.util.Arrays.equals(t.shape(), e.getValue().shape())) {
                throw new IOException("tensor " + name + " has shape " + java.util.Arrays.toString(t.shape())
                        + ", expected " + java.util.Arrays.toString(e.getValue().shape()));
            }
            requireFinite(t, nonNegative);
            unclaimed.remove(name);
            result.put(e.getKey(), t.data());
        }
        return result;
    }

    private static void requireFinite(Tensor t, boolean nonNegative) throws IOException {
        for (float x : t.data()) {
            if (!Float.isFinite(x)) {
                throw new IOException("tensor " + t.name() + " holds a value that is not finite");
            }
            if (nonNegative && x < 0) {
                throw new IOException("tensor " + t.name() + " holds a negative value");
            }
        }
    }

    /** A non-negative decimal integer of at most {@code maxDigits} digits, plain digits only. */
    private static long count(Map<String, String> md, String key, int maxDigits) throws IOException {
        String text = md.get(key);
        if (text == null) {
            throw new IOException("metadata key " + key + " is missing");
        }
        if (text.isEmpty() || text.length() > maxDigits || !text.chars().allMatch(ch -> ch >= '0' && ch <= '9')) {
            throw new IOException("metadata key " + key + " is not a decimal count: " + text);
        }
        return Long.parseLong(text);
    }

    private static double number(Map<String, String> md, String key) throws IOException {
        String text = md.get(key);
        if (text == null) {
            throw new IOException("metadata key " + key + " is missing");
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            throw new IOException("metadata key " + key + " is not a number: " + text, e);
        }
    }

    // ------------------------------------------------------------------ a directory

    /** The path {@code dir/step-<8 digits>.safetensors}. */
    public static Path file(Path dir, long step) {
        if (step < 0) {
            throw new IllegalArgumentException("step must be >= 0, got " + step);
        }
        return dir.resolve(String.format("step-%08d.safetensors", step));
    }

    /** The step in the file name, or -1 if the name is not that of a checkpoint. */
    public static long stepOf(Path file) {
        Matcher m = NAME.matcher(file.getFileName().toString());
        return m.matches() ? Long.parseLong(m.group("step")) : -1;
    }

    /** The checkpoint files of a directory by ascending step; empty if the directory does not exist. */
    public static List<Path> list(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(f -> stepOf(f) >= 0 && Files.isRegularFile(f))
                    .sorted(Comparator.comparingLong(Checkpoint::stepOf)).toList();
        }
    }

    /**
     * Loads the newest checkpoint that reads. A file that fails to read, or whose name says another step than its
     * metadata, is renamed to {@code <name>.corrupt} and reported; the search goes on with the next older file.
     * (A read error of the disk looks the same as a damaged file here.)
     */
    public static Recovery recover(Path dir) throws IOException {
        List<Path> files = new ArrayList<>(list(dir));
        List<Path> quarantined = new ArrayList<>();
        for (int i = files.size() - 1; i >= 0; i--) {
            Path file = files.get(i);
            try {
                Loaded loaded = read(file);
                if (loaded.step() != stepOf(file)) {
                    throw new IOException(file + ": the name says step " + stepOf(file) + ", the file step "
                            + loaded.step());
                }
                return new Recovery(loaded, quarantined);
            } catch (IOException e) {
                Files.move(file, file.resolveSibling(file.getFileName() + ".corrupt"),
                        StandardCopyOption.REPLACE_EXISTING);
                quarantined.add(file);
            }
        }
        return new Recovery(null, quarantined);
    }

    /** Deletes the leftovers of interrupted writes (temporary files of {@link Safetensors#write}); returns their count. */
    public static int removeTemporaries(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        int removed = 0;
        List<Path> leftovers;
        try (Stream<Path> files = Files.list(dir)) {
            leftovers = files.filter(f -> TEMPORARY.matcher(f.getFileName().toString()).matches()).toList();
        }
        for (Path f : leftovers) {
            if (Files.deleteIfExists(f)) {
                removed++;
            }
        }
        return removed;
    }

    /** Deletes all but the {@code keep} newest checkpoints; returns how many it deleted. */
    public static int keepLast(Path dir, int keep) throws IOException {
        if (keep < 1) {
            throw new IllegalArgumentException("keep must be >= 1, got " + keep);
        }
        List<Path> files = list(dir);
        int removed = 0;
        for (Path f : files.subList(0, Math.max(0, files.size() - keep))) {
            if (Files.deleteIfExists(f)) {
                removed++;
            }
        }
        return removed;
    }
}
