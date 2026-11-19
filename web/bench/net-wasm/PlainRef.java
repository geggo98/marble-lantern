package sternhalma.nn;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import sternhalma.engine.Position;

/**
 * Golden files of the plain plane set (7 planes, no priors) for the WebAssembly net backend test (not part of the Gradle build).
 * Compiled and run by scripts/web/net-wasm-golden.zsh after :nn:goldenExport has written the golden directory. Argument: GOLDEN_DIR.
 * Writes GOLDEN_DIR/nn-weights-small-plain.safetensors (SMALL, 2 players, random weights, seed 5) and
 * GOLDEN_DIR/nn-ref-small-plain.safetensors with the tensors inputs [n][122][7], logits [n][242] and values [n][2] of the scalar Network.
 */
public final class PlainRef {
    private PlainRef() {}

    public static void main(String[] args) throws Exception {
        Path golden = Path.of(args[0]);
        Parameters p = Parameters.init(NetworkConfig.small(2), 5);
        p.save(golden.resolve("nn-weights-small-plain.safetensors"));
        List<Position> all = Playouts.positions(2, 60, 160, 1);
        int n = 256;
        int size = PlaneSet.PLAIN.inputSize();
        float[] in = new float[n * size];
        for (int i = 0; i < n; i++) {
            Encoder.encode(PlaneSet.PLAIN, all.get((int) ((long) i * all.size() / n)), in, i * size);
        }
        float[] logits = new float[n * Encoder.POLICY_SIZE];
        float[] values = new float[n * 2];
        new Network(p).forward(in, n, logits, values);
        Map<String, Tensor> t = new LinkedHashMap<>();
        t.put("inputs", new Tensor("inputs", new int[] {n, 122, 7}, in));
        t.put("logits", new Tensor("logits", new int[] {n, Encoder.POLICY_SIZE}, logits));
        t.put("values", new Tensor("values", new int[] {n, 2}, values));
        Safetensors.write(golden.resolve("nn-ref-small-plain.safetensors"), t, Map.of("format", "plain-ref"));
        System.out.println("plain golden written, " + n + " positions");
    }
}
