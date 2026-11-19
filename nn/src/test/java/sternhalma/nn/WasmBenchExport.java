package sternhalma.nn;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import sternhalma.engine.Position;

/**
 * Not a test: writes the input and reference file of the network benchmark in the browser (scripts/bench-net-wasm.zsh).
 * Usage: {@code WasmBenchExport <net.safetensors> <out.safetensors> [positions] [seed]}. The output has the tensors
 * {@code inputs} [N][122][planes] (N evenly spaced positions of seeded two-player random playouts under the adjacent
 * jump rule, encoded with the plane set of the net), {@code logits} [N][242] and {@code values} [N][players] of the
 * scalar reference {@link Network}.
 */
public final class WasmBenchExport {

    private WasmBenchExport() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: WasmBenchExport <net.safetensors> <out.safetensors> [positions] [seed]");
            System.exit(64);
        }
        Parameters parameters = Parameters.load(Path.of(args[0]));
        int n = args.length > 2 ? Integer.parseInt(args[2]) : 64;
        long seed = args.length > 3 ? Long.parseLong(args[3]) : 1;
        NetworkConfig config = parameters.config();
        List<Position> all = Playouts.positions(2, 60, 160, seed);
        int size = config.inputSize();
        float[] inputs = new float[n * size];
        for (int i = 0; i < n; i++) {
            Encoder.encode(config.planes(), all.get((int) ((long) i * all.size() / n)), inputs, i * size);
        }
        float[] logits = new float[n * Encoder.POLICY_SIZE];
        float[] values = new float[n * config.players()];
        new Network(parameters).forward(inputs, n, logits, values);
        Map<String, Tensor> out = new LinkedHashMap<>();
        out.put("inputs", new Tensor("inputs", new int[] {n, HexGrid.ROWS, config.inputPlanes()}, inputs));
        out.put("logits", new Tensor("logits", new int[] {n, Encoder.POLICY_SIZE}, logits));
        out.put("values", new Tensor("values", new int[] {n, config.players()}, values));
        Safetensors.write(Path.of(args[1]), out, Map.of("source", "WasmBenchExport, seed " + seed + ", " + all.size()
                + " playout positions, " + config));
        System.err.println("wrote " + args[1] + ": " + n + " positions, " + config + ", "
                + Files.size(Path.of(args[1])) + " bytes");
    }
}
