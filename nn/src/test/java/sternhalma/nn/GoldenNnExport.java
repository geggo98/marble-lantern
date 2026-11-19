package sternhalma.nn;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;

/**
 * Not a test: writes the golden files of the C++ network port (docs/cpp-comparison-plan.md, WP8). Run with
 *
 * <pre>
 * ./gradlew --no-build-cache :nn:goldenExport [--args="--out cpp/golden --real"]
 * </pre>
 *
 * <p>Every file is a safetensors file (F32 tensors; the C++ reader is needed anyway). The files are:
 * <ul>
 *   <li>{@code nn-weights-small-u5} and {@code nn-weights-large-u5}: the u5 weights of SMALL and LARGE
 *       {@code classic1}, two players, exactly the weights of {@code GpuFeedBenchmark};
 *   <li>{@code nn-inputs}: tensor {@code inputs} [1024][122][13], 1,024 evenly spaced positions of
 *       {@code Playouts.positions(2, 60, 160, 1)}, encoded with {@code classic1};
 *   <li>{@code nn-ref-<preset>}: {@code logits} [1024][242] and {@code values} [1024][2] of {@link Network} (the scalar
 *       reference), {@code vlogits} and {@code vvalues} of {@link VectorNetwork};
 *   <li>{@code nn-ref-large-gen11} (only with {@code --real}): the same for the real weights of
 *       {@code $STERNHALMA_DATA_DIR/loop4/gen-0011/candidate.safetensors} (default data directory {@code data});
 *   <li>{@code nn-weights-tiny-v3} and {@code nn-weights-small-v3}: seeded TINY and SMALL {@code classic1v} weights (format
 *       version 3, three value outputs), {@code Parameters.uniform(config, 5, sqrt(6 / 7C))};
 *   <li>{@code nn-inputs-v3}: {@code inputs} [64][122][15] and the facts of each position ({@code players}, {@code mover},
 *       {@code long}, each [64]): 16 positions of every variant (two and three players, adjacent and long jumps) from seeded
 *       playouts, encoded with {@code classic1v}; every mover of each variant occurs;
 *   <li>{@code nn-ref-tiny-v3} and {@code nn-ref-small-v3}: {@code logits} [64][242], {@code values} [64][3] of
 *       {@link Network}, {@code vlogits}, {@code vvalues} of {@link VectorNetwork}, and {@code absolute} [64][3]: the
 *       absolute values {@link Evaluator} returns for the position (zero in the third column of a two-player position);
 *   <li>{@code nn-cases}: hand-checkable cases (see {@link #writeCases});
 *   <li>{@code MANIFEST.tsv}: file name, line count (0 for binary files), SHA-256, git commit, then comment lines
 *       {@code #max_err_frac.<impl>.<preset>} with the measured tolerance figures.
 * </ul>
 *
 * <p>The export is deterministic: two runs give files with equal SHA-256. {@code MANIFEST.tsv} lines of other
 * exporters (names not starting with {@code nn-}) are kept.
 */
public final class GoldenNnExport {

    /** Number of golden positions. */
    static final int POSITIONS = 1024;

    private static final String REAL_WEIGHTS = "loop4/gen-0011/candidate.safetensors";

    private GoldenNnExport() {
    }

    /** One preset of the export: its name, network and parameters. */
    private record Preset(String name, NetworkConfig config, Parameters parameters, boolean writeWeights) {
    }

    public static void main(String[] args) throws Exception {
        Path out = Path.of("cpp/golden");
        boolean real = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--out" -> out = Path.of(args[++i]);
                case "--real" -> real = true;
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        Files.createDirectories(out);
        List<String> written = new ArrayList<>();
        Map<String, String> figures = new TreeMap<>();

        List<Preset> presets = new ArrayList<>();
        for (NetworkConfig base : List.of(NetworkConfig.small(2), NetworkConfig.large(2))) {
            NetworkConfig config = base.withPlanes(PlaneSet.CLASSIC1);
            presets.add(new Preset(name(config) + "-u5", config, u5(config), true));
        }
        if (real) {
            Path data = Path.of(System.getenv().getOrDefault("STERNHALMA_DATA_DIR", "data")).resolve(REAL_WEIGHTS);
            Parameters parameters = Parameters.load(data);
            if (parameters.config().channels() != 64 || parameters.config().players() != 2
                    || parameters.config().planes() != PlaneSet.CLASSIC1) {
                throw new IOException("the real weights are not LARGE classic1 for two players: " + parameters.config());
            }
            presets.add(new Preset("large-gen11", parameters.config(), parameters, false));
        }

        float[] inputs = encodePositions();
        write(out, "nn-inputs", Map.of("inputs", tensor("inputs", inputs, POSITIONS, HexGrid.ROWS, Encoder.CLASSIC_PLANES)),
                meta("golden-nn-inputs", "positions", Integer.toString(POSITIONS), "planes", "classic1", "source",
                        "Playouts.positions(2,60,160,1) evenly spaced"), written);

        for (Preset preset : presets) {
            if (preset.writeWeights()) {
                Path weights = out.resolve("nn-weights-" + preset.name() + ".safetensors");
                preset.parameters().save(weights);
                written.add(weights.getFileName().toString());
            }
            writeReferences(out, preset, inputs, written, figures);
        }
        writeCases(out, inputs, written);
        writeVariants(out, written, figures);
        writeManifest(out, written, figures);
        System.out.println("golden files written to " + out + ": " + written);
        System.out.println("tolerance figures: " + figures);
    }

    /** Positions per variant in the {@code classic1v} files. */
    static final int VARIANT_POSITIONS_EACH = 16;

    private static final int[] VARIANT_PLAYERS = {2, 2, 3, 3};
    private static final RuleSet.JumpRule[] VARIANT_JUMPS = {RuleSet.JumpRule.ADJACENT, RuleSet.JumpRule.LONG,
            RuleSet.JumpRule.ADJACENT, RuleSet.JumpRule.LONG};

    /** The golden positions of the four variants, in the order 2p adjacent, 2p long, 3p adjacent, 3p long. */
    static List<Position> variantPositions() {
        List<Position> result = new ArrayList<>();
        for (int v = 0; v < VARIANT_PLAYERS.length; v++) {
            List<Position> all = Playouts.positions(VARIANT_PLAYERS[v], VARIANT_JUMPS[v], 60, 160, 7 + v);
            java.util.Set<Integer> movers = new java.util.TreeSet<>();
            for (int i = 0; i < VARIANT_POSITIONS_EACH; i++) {
                // the offset i breaks the stride's parity, so that both movers of a two-player game occur
                Position p = all.get((int) ((long) i * all.size() / VARIANT_POSITIONS_EACH) + i);
                movers.add(p.sideToMove());
                result.add(p);
            }
            if (movers.size() != VARIANT_PLAYERS[v]) {
                throw new IllegalStateException("variant " + v + " covers only the movers " + movers);
            }
        }
        return result;
    }

    /** {@code nn-weights-*-v3}, {@code nn-inputs-v3} and {@code nn-ref-*-v3}. */
    private static void writeVariants(Path out, List<String> written, Map<String, String> figures) throws IOException {
        List<Position> positions = variantPositions();
        int n = positions.size();
        int size = PlaneSet.CLASSIC1V.inputSize();
        float[] inputs = new float[n * size];
        float[] players = new float[n];
        float[] mover = new float[n];
        float[] isLong = new float[n];
        for (int i = 0; i < n; i++) {
            Position p = positions.get(i);
            Encoder.encode(PlaneSet.CLASSIC1V, p, inputs, i * size);
            players[i] = p.rules().players();
            mover[i] = p.sideToMove();
            isLong[i] = p.rules().jumpRule() == RuleSet.JumpRule.LONG ? 1f : 0f;
        }
        Map<String, Tensor> in = new LinkedHashMap<>();
        in.put("inputs", tensor("inputs", inputs, n, HexGrid.ROWS, Encoder.VARIANT_PLANES));
        in.put("players", tensor("players", players, n));
        in.put("mover", tensor("mover", mover, n));
        in.put("long", tensor("long", isLong, n));
        write(out, "nn-inputs-v3", in, meta("golden-nn-inputs", "positions", Integer.toString(n), "planes", "classic1v",
                "source", "Playouts.positions(players, jump, 60, 160, 7 + variant), 16 evenly spaced per variant"), written);

        for (NetworkConfig base : List.of(NetworkConfig.tiny(3), NetworkConfig.small(3))) {
            NetworkConfig config = base.withPlanes(PlaneSet.CLASSIC1V);
            String key = name(config, "tiny") + "-v3";
            Parameters parameters = u5(config);
            Path weights = out.resolve("nn-weights-" + key + ".safetensors");
            parameters.save(weights);
            written.add(weights.getFileName().toString());

            float[] logits = new float[n * Encoder.POLICY_SIZE];
            float[] values = new float[n * 3];
            new Network(parameters).forward(inputs, n, logits, values);
            VectorNetwork vector = new VectorNetwork(parameters);
            float[] vlogits = new float[logits.length];
            float[] vvalues = new float[values.length];
            for (int i = 0; i < 3; i++) {
                vector.forward(inputs, n, vlogits, vvalues);
            }
            // the absolute values as the Evaluator of this network returns them, one position at a time
            Evaluator evaluator = new Evaluator(new Network(parameters));
            float[] absolute = new float[n * 3];
            for (int i = 0; i < n; i++) {
                float[] v = evaluator.evaluate(positions.get(i)).values();
                System.arraycopy(v, 0, absolute, i * 3, v.length);
            }
            Map<String, Tensor> tensors = new LinkedHashMap<>();
            tensors.put("logits", tensor("logits", logits, n, Encoder.POLICY_SIZE));
            tensors.put("values", tensor("values", values, n, 3));
            tensors.put("vlogits", tensor("vlogits", vlogits, n, Encoder.POLICY_SIZE));
            tensors.put("vvalues", tensor("vvalues", vvalues, n, 3));
            tensors.put("absolute", tensor("absolute", absolute, n, 3));
            write(out, "nn-ref-" + key, tensors, meta("golden-nn-ref", "preset", key, "positions", Integer.toString(n)), written);
            Figure figure = figure(logits, values, vlogits, vvalues, 3);
            figures.put("max_err_frac.vector." + key, format(figure.fraction));
            if (figure.fraction > 1.0) {
                figures.put("extra_rel_of_max_logit.vector." + key, format(figure.extra));
            }
        }
    }

    private static String name(NetworkConfig config, String small) {
        return config.channels() == 8 ? small : "small";
    }

    /** The u5 weights of {@code GpuFeedBenchmark}: seed 5, bound sqrt(6 / 7C). */
    static Parameters u5(NetworkConfig config) {
        return Parameters.uniform(config, 5, (float) Math.sqrt(6.0 / (7 * config.channels())));
    }

    static String name(NetworkConfig config) {
        return config.channels() == 32 ? "small" : "large";
    }

    /** The 1,024 encoded golden inputs, {@code [1024][122][13]}. */
    static float[] encodePositions() {
        List<Position> all = Playouts.positions(2, 60, 160, 1);
        if (all.size() < POSITIONS) {
            throw new IllegalStateException("only " + all.size() + " positions");
        }
        int size = PlaneSet.CLASSIC1.inputSize();
        float[] inputs = new float[POSITIONS * size];
        for (int i = 0; i < POSITIONS; i++) {
            Encoder.encode(PlaneSet.CLASSIC1, all.get((int) ((long) i * all.size() / POSITIONS)), inputs, i * size);
        }
        return inputs;
    }

    private static void writeReferences(Path out, Preset preset, float[] inputs, List<String> written,
            Map<String, String> figures) throws IOException {
        NetworkConfig config = preset.config();
        int players = config.players();
        float[] logits = new float[POSITIONS * Encoder.POLICY_SIZE];
        float[] values = new float[POSITIONS * players];
        new Network(preset.parameters()).forward(inputs, POSITIONS, logits, values);

        VectorNetwork vector = new VectorNetwork(preset.parameters());
        float[] vlogits = new float[logits.length];
        float[] vvalues = new float[values.length];
        // Last-digit differences between the first calls and later ones were seen (docs/network.md): warm up first.
        for (int i = 0; i < 3; i++) {
            vector.forward(inputs, POSITIONS, vlogits, vvalues);
        }
        float[] again = new float[logits.length];
        float[] againValues = new float[values.length];
        vector.forward(inputs, POSITIONS, again, againValues);
        boolean stable = java.util.Arrays.equals(vlogits, again) && java.util.Arrays.equals(vvalues, againValues);
        System.out.println(preset.name() + ": VectorNetwork repeats bit for bit: " + stable);

        Map<String, Tensor> tensors = new LinkedHashMap<>();
        tensors.put("logits", tensor("logits", logits, POSITIONS, Encoder.POLICY_SIZE));
        tensors.put("values", tensor("values", values, POSITIONS, players));
        tensors.put("vlogits", tensor("vlogits", vlogits, POSITIONS, Encoder.POLICY_SIZE));
        tensors.put("vvalues", tensor("vvalues", vvalues, POSITIONS, players));
        write(out, "nn-ref-" + preset.name(), tensors,
                meta("golden-nn-ref", "preset", preset.name(), "positions", Integer.toString(POSITIONS)), written);

        Figure figure = figure(logits, values, vlogits, vvalues, players);
        figures.put("max_err_frac.vector." + preset.name(), format(figure.fraction));
        if (figure.fraction > 1.0) {
            figures.put("extra_rel_of_max_logit.vector." + preset.name(), format(figure.extra));
        }
    }

    /** Largest error against the bound 1e-4 + 1e-3 |ref| as a fraction of it, and the extra term that would be needed. */
    record Figure(double fraction, double extra) {
    }

    static Figure figure(float[] refLogits, float[] refValues, float[] logits, float[] values, int players) {
        double fraction = 0;
        double extra = 0;
        int n = refLogits.length / Encoder.POLICY_SIZE;
        for (int b = 0; b < n; b++) {
            double largest = 0;
            for (int i = 0; i < Encoder.POLICY_SIZE; i++) {
                largest = Math.max(largest, Math.abs(refLogits[b * Encoder.POLICY_SIZE + i]));
            }
            for (int i = 0; i < Encoder.POLICY_SIZE; i++) {
                int k = b * Encoder.POLICY_SIZE + i;
                double bound = Network.ABSOLUTE_TOLERANCE + Network.RELATIVE_TOLERANCE * Math.abs(refLogits[k]);
                double err = error(logits[k], refLogits[k]);
                fraction = Math.max(fraction, err / bound);
                if (largest > 0) {
                    extra = Math.max(extra, Math.max(0, err - bound) / largest);
                }
            }
            for (int i = 0; i < players; i++) {
                int k = b * players + i;
                double bound = Network.ABSOLUTE_TOLERANCE + Network.RELATIVE_TOLERANCE * Math.abs(refValues[k]);
                fraction = Math.max(fraction, error(values[k], refValues[k]) / bound);
            }
        }
        return new Figure(fraction, extra);
    }

    private static double error(float fast, float ref) {
        if (Float.isNaN(fast) || Float.isNaN(ref)) {
            return Float.isNaN(fast) && Float.isNaN(ref) ? 0 : Double.POSITIVE_INFINITY;
        }
        return Math.abs((double) fast - ref);
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    /**
     * {@code nn-cases}: a TINY classic1 network with u5 weights (tensors {@code base.*}, expected outputs
     * {@code base.logits}/{@code base.values} on {@code input}) and four hand-checkable cases:
     * <ul>
     *   <li>{@code nan.conv_in.weight}: replaces {@code conv_in.weight}, element 0 is NaN; expected outputs
     *       {@code nan.logits}, {@code nan.values} (NaN where the NaN reaches);
     *   <li>{@code sat_a.head_value.dense2.bias} = {44.3, 100} and {@code sat_b.head_value.dense2.bias} = {-1000, +inf}:
     *       replace {@code head_value.dense2.bias}; the values must be exactly {1, 1} and {-1, 1}
     *       ({@code sat_a.values}, {@code sat_b.values});
     *   <li>{@code dirty_input}: row 121 of plane PROGRESS is 0.5; every forward pass must refuse it.
     * </ul>
     */
    private static void writeCases(Path out, float[] inputs, List<String> written) throws IOException {
        NetworkConfig config = NetworkConfig.tiny(2).withPlanes(PlaneSet.CLASSIC1);
        Parameters base = u5(config);
        int size = config.inputSize();
        float[] input = java.util.Arrays.copyOfRange(inputs, 500 * size, 501 * size);
        float[] dirty = input.clone();
        dirty[Encoder.index(config.inputPlanes(), HexGrid.PADDING, Encoder.PROGRESS)] = 0.5f;

        Map<String, Tensor> tensors = new LinkedHashMap<>();
        for (Map.Entry<String, Tensor> e : base.tensors().entrySet()) {
            tensors.put("base." + e.getKey(), new Tensor("base." + e.getKey(), e.getValue().shape(), e.getValue().data()));
        }
        tensors.put("input", tensor("input", input, 1, HexGrid.ROWS, config.inputPlanes()));
        tensors.put("dirty_input", tensor("dirty_input", dirty, 1, HexGrid.ROWS, config.inputPlanes()));
        float[][] baseOut = run(base, input);
        tensors.put("base.logits", tensor("base.logits", baseOut[0], 1, Encoder.POLICY_SIZE));
        tensors.put("base.values", tensor("base.values", baseOut[1], 1, 2));

        float[] nanWeight = base.get("conv_in.weight").data().clone();
        nanWeight[0] = Float.NaN;
        tensors.put("nan.conv_in.weight", tensor("nan.conv_in.weight", nanWeight, base.get("conv_in.weight").shape()));
        float[][] nan = run(with(base, "conv_in.weight", nanWeight), input);
        tensors.put("nan.logits", tensor("nan.logits", nan[0], 1, Encoder.POLICY_SIZE));
        tensors.put("nan.values", tensor("nan.values", nan[1], 1, 2));
        if (!Float.isNaN(nan[1][0]) || !Float.isNaN(nan[1][1])) {
            throw new IllegalStateException("the NaN case does not give NaN values: " + java.util.Arrays.toString(nan[1]));
        }

        float[][] saturation = {{44.3f, 100f}, {-1000f, Float.POSITIVE_INFINITY}};
        float[][] expected = {{1f, 1f}, {-1f, 1f}};
        for (int i = 0; i < 2; i++) {
            String tag = i == 0 ? "sat_a" : "sat_b";
            tensors.put(tag + ".head_value.dense2.bias", tensor(tag + ".head_value.dense2.bias", saturation[i], 2));
            float[][] result = run(with(base, "head_value.dense2.bias", saturation[i]), input);
            if (!java.util.Arrays.equals(result[1], expected[i])) {
                throw new IllegalStateException(tag + ": values " + java.util.Arrays.toString(result[1])
                        + " are not exactly " + java.util.Arrays.toString(expected[i]));
            }
            tensors.put(tag + ".logits", tensor(tag + ".logits", result[0], 1, Encoder.POLICY_SIZE));
            tensors.put(tag + ".values", tensor(tag + ".values", result[1], 1, 2));
        }
        try {
            new Network(base).forward(dirty, 1, new float[Encoder.POLICY_SIZE], new float[2]);
            throw new IllegalStateException("the scalar reference accepted a dirty padding row");
        } catch (IllegalArgumentException expectedRefusal) {
            // the case is a refusal
        }
        Map<String, String> metadata = new LinkedHashMap<>(config.toMetadata());
        metadata.put("golden", "golden-nn-cases");
        write(out, "nn-cases", tensors, metadata, written);
    }

    private static Parameters with(Parameters base, String name, float[] data) {
        Map<String, Tensor> tensors = new LinkedHashMap<>(base.tensors());
        tensors.put(name, new Tensor(name, base.get(name).shape(), data));
        return Parameters.of(base.config(), tensors);
    }

    private static float[][] run(Parameters parameters, float[] input) {
        float[] logits = new float[Encoder.POLICY_SIZE];
        float[] values = new float[parameters.config().players()];
        new Network(parameters).forward(input, 1, logits, values);
        return new float[][] {logits, values};
    }

    private static Tensor tensor(String name, float[] data, int... shape) {
        return new Tensor(name, shape, data);
    }

    private static Map<String, String> meta(String format, String... pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("format", format);
        for (int i = 0; i < pairs.length; i += 2) {
            m.put(pairs[i], pairs[i + 1]);
        }
        return m;
    }

    private static void write(Path out, String name, Map<String, Tensor> tensors, Map<String, String> metadata,
            List<String> written) throws IOException {
        Safetensors.write(out.resolve(name + ".safetensors"), tensors, metadata);
        written.add(name + ".safetensors");
    }

    /** Rewrites the {@code nn-} entries and figures of MANIFEST.tsv; lines of other exporters stay. */
    private static void writeManifest(Path out, List<String> written, Map<String, String> figures) throws Exception {
        Path manifest = out.resolve("MANIFEST.tsv");
        List<String> keep = new ArrayList<>();
        if (Files.exists(manifest)) {
            for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
                boolean ours = line.startsWith("nn-") || line.startsWith("#max_err_frac.") || line.startsWith("#extra_rel_of_max_logit.")
                        || line.startsWith("# nn-golden") || line.startsWith("file\t");
                if (!ours && !line.isBlank()) {
                    keep.add(line);
                }
            }
        }
        List<String> lines = new ArrayList<>();
        lines.add("file\tlines\tsha256\tcommit");
        lines.addAll(keep);
        lines.add("# nn-golden: binary files have line count 0; #key<TAB>value lines are measured figures (WP8)");
        String commit = commit();
        for (String name : written.stream().sorted().toList()) {
            byte[] bytes = Files.readAllBytes(out.resolve(name));
            lines.add(name + "\t0\t" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)) + "\t" + commit);
        }
        // Figures of an earlier run (for example the TornadoVM ones) for other presets stay in the sorted block below.
        for (Map.Entry<String, String> e : figures.entrySet()) {
            lines.add("#" + e.getKey() + "\t" + e.getValue());
        }
        Path tmp = out.resolve("MANIFEST.tsv.tmp");
        Files.writeString(tmp, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        Files.move(tmp, manifest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static String commit() {
        try {
            Process p = new ProcessBuilder("git", "rev-parse", "HEAD").redirectErrorStream(true).start();
            String text = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return p.waitFor() == 0 && text.matches("[0-9a-f]{40}") ? text : "unknown";
        } catch (IOException | InterruptedException e) {
            return "unknown";
        }
    }
}
