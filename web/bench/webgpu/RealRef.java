package sternhalma.nn;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import sternhalma.engine.Position;

/**
 * Java reference outputs of the REAL SMALL nets for the WebGPU benchmark (not part of the Gradle build).
 * Compiled and run by scripts/bench-net-webgpu.zsh (step "ref"), after :nn:goldenExport has written cpp/golden.
 * Arguments: GOLDEN_DIR NET_V2 NET_V3.
 * Writes GOLDEN_DIR/real-ref-v2.safetensors (the positions of nn-inputs, 1,024 two-player classic1) and
 * real-ref-v3.safetensors (the positions of nn-inputs-v3, 64 classic1v), tensors logits [n][242] and values [n][P]
 * of the scalar Network (policy logits with the distance prior, values after tanh).
 */
public final class RealRef {
    private RealRef() {}

    public static void main(String[] args) throws Exception {
        Path golden = Path.of(args[0]);
        write(golden.resolve("real-ref-v2.safetensors"), Parameters.load(Path.of(args[1])), GoldenNnExport.encodePositions(), 1024);
        List<Position> v = GoldenNnExport.variantPositions();
        int n = v.size();
        int size = PlaneSet.CLASSIC1V.inputSize();
        float[] in = new float[n * size];
        for (int i = 0; i < n; i++) {
            Encoder.encode(PlaneSet.CLASSIC1V, v.get(i), in, i * size);
        }
        write(golden.resolve("real-ref-v3.safetensors"), Parameters.load(Path.of(args[2])), in, n);
    }

    private static void write(Path out, Parameters p, float[] inputs, int n) throws Exception {
        int players = p.config().players();
        float[] logits = new float[n * Encoder.POLICY_SIZE];
        float[] values = new float[n * players];
        new Network(p).forward(inputs, n, logits, values);
        Map<String, Tensor> t = new LinkedHashMap<>();
        t.put("logits", new Tensor("logits", new int[] {n, Encoder.POLICY_SIZE}, logits));
        t.put("values", new Tensor("values", new int[] {n, players}, values));
        Safetensors.write(out, t, Map.of("format", "real-ref", "config", p.config().toString()));
        System.out.println(out + " " + p.config());
    }
}
