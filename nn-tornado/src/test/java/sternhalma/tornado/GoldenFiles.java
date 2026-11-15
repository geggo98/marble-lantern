package sternhalma.tornado;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import sternhalma.nn.Encoder;
import sternhalma.nn.Network;
import sternhalma.nn.Parameters;
import sternhalma.nn.Safetensors;
import sternhalma.nn.Tensor;

/**
 * Reads the golden files of {@code :nn:goldenExport} (cpp/golden) for the network benchmarks of this module: the manifest
 * check, the weights, the inputs and the reference outputs, and the tolerance rule of the C++ port
 * (docs/cpp-comparison-plan.md, WP8).
 */
final class GoldenFiles {

    static final int POSITIONS = 1024;
    private static final String REAL_WEIGHTS = "loop4/gen-0011/candidate.safetensors";

    private final Path dir;
    private final Map<String, String> sha = new LinkedHashMap<>();
    private final Map<String, String> figures = new LinkedHashMap<>();

    GoldenFiles(Path dir) throws IOException {
        this.dir = dir;
        Path manifest = dir.resolve("MANIFEST.tsv");
        if (!Files.exists(manifest)) {
            throw new IOException("no MANIFEST.tsv in " + dir + " (run :nn:goldenExport)");
        }
        for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("file\t") || line.startsWith("# ")) {
                continue;
            }
            String[] f = line.split("\t");
            if (line.startsWith("#")) {
                figures.put(f[0].substring(1), f.length > 1 ? f[1] : "");
            } else {
                sha.put(f[0], f[2]);
            }
        }
    }

    Path dir() {
        return dir;
    }

    /** The key of a preset name: small, large or gen11 (LARGE with the real weights). */
    static String key(String preset) {
        return switch (preset) {
            case "small" -> "small-u5";
            case "large" -> "large-u5";
            case "gen11" -> "large-gen11";
            default -> throw new IllegalArgumentException("unknown preset " + preset + " (small, large or gen11)");
        };
    }

    /** The tensors of a listed golden file, after the SHA-256 check against the manifest. */
    Safetensors.Contents load(String name) throws IOException {
        String expected = sha.get(name);
        if (expected == null) {
            throw new IOException(name + " is not listed in MANIFEST.tsv (run :nn:goldenExport)");
        }
        byte[] bytes = Files.readAllBytes(dir.resolve(name));
        String actual = sha256(bytes);
        if (!actual.equals(expected)) {
            throw new IOException(name + ": SHA-256 " + actual + " differs from MANIFEST.tsv " + expected);
        }
        return Safetensors.fromBytes(bytes);
    }

    float[] inputs() throws IOException {
        return load("nn-inputs.safetensors").tensors().get("inputs").data();
    }

    /** {logits, values} of the scalar reference, or {vlogits, vvalues} of the Vector API network. */
    float[][] reference(String preset, boolean vector) throws IOException {
        Map<String, Tensor> t = load("nn-ref-" + key(preset) + ".safetensors").tensors();
        return vector ? new float[][] {t.get("vlogits").data(), t.get("vvalues").data()}
                : new float[][] {t.get("logits").data(), t.get("values").data()};
    }

    Parameters weights(String preset) throws IOException {
        if (preset.equals("gen11")) {
            Path file = Path.of(System.getenv().getOrDefault("STERNHALMA_DATA_DIR", "data")).resolve(REAL_WEIGHTS);
            return Parameters.load(file);
        }
        String name = "nn-weights-" + key(preset) + ".safetensors";
        load(name); // the SHA-256 check
        return Parameters.load(dir.resolve(name));
    }

    /** The extra relative term (of the largest logit of a position) that the manifest allows for an implementation. */
    double extraRel(String impl, String preset) {
        String v = figures.get("extra_rel_of_max_logit." + impl + "." + key(preset));
        return v == null ? 0 : Double.parseDouble(v);
    }

    boolean has(String preset) {
        return sha.containsKey("nn-ref-" + key(preset) + ".safetensors");
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Largest error of {@code logits}/{@code values} (positions {@code first ..}) against the reference as a fraction of the
     * bound 1e-4 + 1e-3 |ref| + extra * (largest |logit| of the position, policy only), and the extra term that would be needed.
     * NaN counts as an error unless the reference is NaN too.
     */
    static double[] compare(float[] logits, float[] values, float[] refLogits, float[] refValues, int first, int n,
            int players, double extra) {
        double fraction = 0;
        double needed = 0;
        for (int b = 0; b < n; b++) {
            int rb = (first + b) % (refValues.length / players); // reference positions wrap around, as the callers build their inputs
            double largest = 0;
            for (int i = 0; i < Encoder.POLICY_SIZE; i++) {
                largest = Math.max(largest, Math.abs(refLogits[rb * Encoder.POLICY_SIZE + i]));
            }
            for (int i = 0; i < Encoder.POLICY_SIZE; i++) {
                double ref = refLogits[rb * Encoder.POLICY_SIZE + i];
                double err = error(logits[b * Encoder.POLICY_SIZE + i], ref);
                double base = Network.ABSOLUTE_TOLERANCE + Network.RELATIVE_TOLERANCE * Math.abs(ref);
                fraction = Math.max(fraction, err / (base + extra * largest));
                if (largest > 0) {
                    needed = Math.max(needed, Math.max(0, err - base) / largest);
                }
            }
            for (int i = 0; i < players; i++) {
                double ref = refValues[rb * players + i];
                double bound = Network.ABSOLUTE_TOLERANCE + Network.RELATIVE_TOLERANCE * Math.abs(ref);
                fraction = Math.max(fraction, error(values[b * players + i], ref) / bound);
            }
        }
        return new double[] {fraction, needed};
    }

    private static double error(float fast, double ref) {
        if (Double.isNaN(fast) || Double.isNaN(ref)) {
            return Double.isNaN(fast) && Double.isNaN(ref) ? 0 : Double.POSITIVE_INFINITY;
        }
        return Math.abs(fast - ref);
    }
}
