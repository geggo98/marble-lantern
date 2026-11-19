package sternhalma.nnbench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import sternhalma.nn.Encoder;
import sternhalma.nn.Safetensors;
import sternhalma.nn.Tensor;
import sternhalma.nn.VectorNetwork;

/**
 * The JVM rows of the network benchmark (scripts/bench-net-wasm.zsh): the scalar {@code Network} and the Vector API
 * {@code VectorNetwork}, with the same schedule as the browser driver (cpp/bench/bench-driver.mjs): a warm-up of at least 1 s and
 * 10 calls, then 5 rounds of at least 0.8 s; positions/s is the median round. One JSON line per case on stdout.
 * Usage: {@code JvmNetBench <net.safetensors> <reference.safetensors> [batches e.g. 1,8,32,128]}.
 */
public final class JvmNetBench {

    private static final double WARMUP_SECONDS = 1.0;
    private static final int WARMUP_CALLS = 10;
    private static final double ROUND_SECONDS = 0.8;
    private static final int ROUNDS = 5;

    private JvmNetBench() {
    }

    public static void main(String[] args) throws IOException {
        byte[] net = Files.readAllBytes(Path.of(args[0]));
        Safetensors.Contents ref = Safetensors.read(Path.of(args[1]));
        int[] batches = Arrays.stream((args.length > 2 ? args[2] : "1,8,32,128").split(",")).mapToInt(Integer::parseInt).toArray();
        Tensor inputs = ref.tensors().get("inputs");
        int positions = inputs.shape()[0];
        NetBenchCore core = new NetBenchCore();
        expectOk(core.load(Base64.getEncoder().encodeToString(net)));
        byte[] raw = new byte[inputs.data().length * 4];
        for (int i = 0; i < inputs.data().length; i++) {
            int bits = Float.floatToRawIntBits(inputs.data()[i]);
            raw[4 * i] = (byte) bits;
            raw[4 * i + 1] = (byte) (bits >>> 8);
            raw[4 * i + 2] = (byte) (bits >>> 16);
            raw[4 * i + 3] = (byte) (bits >>> 24);
        }
        expectOk(core.setInputs(Base64.getEncoder().encodeToString(raw), positions));
        float[] refLogits = ref.tensors().get("logits").data();
        float[] refValues = ref.tensors().get("values").data();
        int players = core.parameters().config().players();

        for (String impl : new String[] {"jvm-scalar", "jvm-vector"}) {
            if (impl.equals("jvm-vector")) {
                core.use(new VectorNetwork(core.parameters()));
            }
            List<int[]> schedule = new ArrayList<>();
            for (int b : batches) {
                schedule.add(new int[] {b});
            }
            schedule.add(new int[] {32}); // the self-ratio case: batch 32 again at the end
            for (int i = 0; i < schedule.size(); i++) {
                int batch = schedule.get(i)[0];
                boolean again = i == schedule.size() - 1;
                double errFraction = errFraction(core, batch, positions, players, refLogits, refValues);
                warmUp(core, batch);
                double[] msPerCall = new double[ROUNDS];
                for (int r = 0; r < ROUNDS; r++) {
                    msPerCall[r] = round(core, batch);
                }
                report(impl + (again ? "-again" : ""), batch, msPerCall, errFraction);
            }
        }
    }

    private static void warmUp(NetBenchCore core, int batch) {
        long t0 = System.nanoTime();
        int calls = 0;
        while (calls < WARMUP_CALLS || (System.nanoTime() - t0) / 1e9 < WARMUP_SECONDS) {
            expectOk(core.run(batch, 1));
            calls++;
        }
    }

    /** ms per call over one round of at least ROUND_SECONDS. */
    private static double round(NetBenchCore core, int batch) {
        long t0 = System.nanoTime();
        int calls = 0;
        do {
            expectOk(core.run(batch, 1));
            calls++;
        } while ((System.nanoTime() - t0) / 1e9 < ROUND_SECONDS);
        return (System.nanoTime() - t0) / 1e6 / calls;
    }

    private static double errFraction(NetBenchCore core, int batch, int positions, int players, float[] refLogits, float[] refValues) {
        String reply = core.check(batch);
        expectOk(reply);
        float[] out = NetBenchCore.floatsLe(Base64.getDecoder().decode(reply.substring(3)));
        double worst = 0;
        for (int b = 0; b < batch; b++) {
            int p = b % positions;
            for (int i = 0; i < Encoder.POLICY_SIZE; i++) {
                worst = Math.max(worst, fraction(out[b * Encoder.POLICY_SIZE + i], refLogits[p * Encoder.POLICY_SIZE + i]));
            }
            int base = batch * Encoder.POLICY_SIZE;
            for (int i = 0; i < players; i++) {
                worst = Math.max(worst, fraction(out[base + b * players + i], refValues[p * players + i]));
            }
        }
        return worst;
    }

    /** |fast - ref| as a fraction of the bound 1e-4 + 1e-3 |ref|. */
    private static double fraction(double fast, double ref) {
        return Math.abs(fast - ref) / (1e-4 + 1e-3 * Math.abs(ref));
    }

    private static void report(String impl, int batch, double[] msPerCall, double errFraction) {
        double[] sorted = msPerCall.clone();
        Arrays.sort(sorted);
        double median = sorted[sorted.length / 2];
        StringBuilder rounds = new StringBuilder();
        for (double m : msPerCall) {
            rounds.append(rounds.isEmpty() ? "" : ",").append(String.format("%.4f", batch * 1000.0 / m));
        }
        System.out.println(String.format(java.util.Locale.ROOT,
                "{\"impl\":\"%s\",\"runtime\":\"jvm\",\"batch\":%d,\"pos_per_s\":%.1f,\"ms_per_batch\":%.4f,\"min_pos_per_s\":%.1f,"
                        + "\"max_pos_per_s\":%.1f,\"rounds_pos_per_s\":[%s],\"err_fraction\":%.4f}",
                impl, batch, batch * 1000.0 / median, median, batch * 1000.0 / sorted[sorted.length - 1],
                batch * 1000.0 / sorted[0], rounds, errFraction));
    }

    private static void expectOk(String reply) {
        if (!reply.startsWith("ok")) {
            throw new IllegalStateException(reply);
        }
    }
}
