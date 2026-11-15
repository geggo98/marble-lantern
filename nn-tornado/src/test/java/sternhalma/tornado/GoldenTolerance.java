package sternhalma.tornado;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.Map;

import sternhalma.nn.Encoder;
import sternhalma.nn.Parameters;

/**
 * Not a test: the tolerance check of the C++ port for {@link TornadoNetwork} on the u5 weights (docs/cpp-comparison-plan.md,
 * WP8, step 2). Runs the network on the device for all 1,024 golden inputs of SMALL and LARGE (and LARGE gen11 if the
 * export has it), compares with the scalar reference and writes the largest error as a fraction of the bound
 * 1e-4 + 1e-3 |ref| into MANIFEST.tsv: {@code #max_err_frac.tornado.<preset>}, and, if a figure exceeds 1, the extra term
 * {@code #extra_rel_of_max_logit.tornado.<preset>} (relative to the largest |logit| of the position; docs/network.md,
 * "Numeric conventions"). Run: {@code ./gradlew --no-build-cache :nn-tornado:goldenTolerance}.
 */
final class GoldenTolerance {

    private GoldenTolerance() {
    }

    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args.length > 0 ? args[0] : "cpp/golden");
        TornadoSupport.Status status = TornadoSupport.status();
        System.out.println("TornadoVM: " + status);
        if (!status.usable()) {
            System.exit(3);
        }
        GoldenFiles golden = new GoldenFiles(dir);
        float[] inputs = golden.inputs();
        Map<String, String> figures = new TreeMap<>();
        for (String preset : List.of("small", "large", "gen11")) {
            if (!golden.has(preset)) {
                continue;
            }
            Parameters parameters = golden.weights(preset);
            float[][] ref = golden.reference(preset, false);
            int players = parameters.config().players();
            float[] logits = new float[GoldenFiles.POSITIONS * Encoder.POLICY_SIZE];
            float[] values = new float[GoldenFiles.POSITIONS * players];
            try (TornadoNetwork gpu = new TornadoNetwork(parameters)) {
                for (int i = 0; i < 3; i++) {
                    gpu.forward(inputs, GoldenFiles.POSITIONS, logits, values);
                }
            }
            double[] r = GoldenFiles.compare(logits, values, ref[0], ref[1], 0, GoldenFiles.POSITIONS, players, 0);
            String key = GoldenFiles.key(preset);
            figures.put("max_err_frac.tornado." + key, String.format(Locale.ROOT, "%.4f", r[0]));
            if (r[0] > 1.0) {
                figures.put("extra_rel_of_max_logit.tornado." + key, String.format(Locale.ROOT, "%.3e", r[1]));
            }
            System.out.printf(Locale.ROOT, "%s: largest error %.4f of the bound%s%n", key, r[0],
                    r[0] > 1.0 ? ", extra term needed " + figures.get("extra_rel_of_max_logit.tornado." + key) : "");
        }
        Path manifest = dir.resolve("MANIFEST.tsv");
        List<String> lines = new ArrayList<>();
        for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
            if (!line.startsWith("#max_err_frac.tornado.") && !line.startsWith("#extra_rel_of_max_logit.tornado.")) {
                lines.add(line);
            }
        }
        figures.forEach((k, v) -> lines.add("#" + k + "\t" + v));
        Path tmp = dir.resolve("MANIFEST.tsv.tmp");
        Files.writeString(tmp, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        Files.move(tmp, manifest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        System.out.println("MANIFEST.tsv updated: " + figures);
    }
}
