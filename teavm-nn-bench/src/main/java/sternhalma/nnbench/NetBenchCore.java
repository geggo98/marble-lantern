package sternhalma.nnbench;

import java.io.IOException;
import java.util.Base64;
import sternhalma.nn.Encoder;
import sternhalma.nn.ForwardPass;
import sternhalma.nn.Network;
import sternhalma.nn.NetworkConfig;
import sternhalma.nn.Parameters;
import sternhalma.nn.Safetensors;

/**
 * The state of the network benchmark, plain Java: it runs on the JVM (JvmNetBench) and, compiled by TeaVM, in the
 * browser (NetBenchWeb). Nothing here may use the Vector API; the JVM driver plugs it in through {@link #use}.
 *
 * <p>Data crosses the boundary as Base64 strings, so that the same entry points work for the Wasm GC and the JS target
 * of TeaVM (a {@code float[]} argument is not portable between them). The weights and inputs are decoded once; a call to
 * {@link #run} then measures only the forward pass.
 */
public final class NetBenchCore {

    private Parameters parameters;
    private ForwardPass pass;
    private float[] inputs = new float[0];
    private int positions;
    private float[] batchInput = new float[0];
    private float[] logits = new float[0];
    private float[] values = new float[0];
    private int preparedBatch = -1;

    public Parameters parameters() {
        return parameters;
    }

    /** Uses another forward pass (the JVM driver passes the Vector API network). */
    public void use(ForwardPass other) {
        this.pass = other;
        this.preparedBatch = -1;
    }

    /** Parses the weights (Safetensors.fromBytes, NetworkConfig.fromMetadata and Parameters.of inside) and builds the scalar network. */
    public String load(String netBase64) {
        try {
            byte[] bytes = Base64.getDecoder().decode(netBase64);
            Safetensors.Contents contents = Safetensors.fromBytes(bytes);
            NetworkConfig config = NetworkConfig.fromMetadata(contents.metadata());
            parameters = Parameters.of(config, new java.util.LinkedHashMap<>(contents.tensors()));
            pass = new Network(parameters);
            preparedBatch = -1;
            return "ok " + config.channels() + "x" + config.blocks() + " planes=" + config.inputPlanes() + " players="
                    + config.players() + " params=" + config.parameterCount();
        } catch (IOException | RuntimeException e) {
            return "error " + e;
        }
    }

    /** The golden inputs, {@code positions} x [122][planes] little-endian float32 as Base64. */
    public String setInputs(String base64, int count) {
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            inputs = floatsLe(bytes);
            positions = count;
            if (inputs.length != count * parameters.config().inputSize()) {
                return "error inputs have " + inputs.length + " floats, expected " + count * parameters.config().inputSize();
            }
            preparedBatch = -1;
            return "ok " + inputs.length;
        } catch (RuntimeException e) {
            return "error " + e;
        }
    }

    private void prepare(int batch) {
        if (preparedBatch == batch) {
            return;
        }
        int size = parameters.config().inputSize();
        int players = parameters.config().players();
        batchInput = new float[batch * size];
        for (int b = 0; b < batch; b++) {
            System.arraycopy(inputs, (b % positions) * size, batchInput, b * size, size);
        }
        logits = new float[batch * Encoder.POLICY_SIZE];
        values = new float[batch * players];
        preparedBatch = batch;
    }

    /** {@code repeats} forward passes of one batch (position b of the batch is golden position b modulo the number of positions). */
    public String run(int batch, int repeats) {
        try {
            prepare(batch);
            for (int i = 0; i < repeats; i++) {
                pass.forward(batchInput, batch, logits, values);
            }
            return "ok";
        } catch (RuntimeException e) {
            return "error " + e;
        }
    }

    /** One forward pass of the batch; the logits and then the values as little-endian float32 in Base64. */
    public String check(int batch) {
        try {
            prepare(batch);
            pass.forward(batchInput, batch, logits, values);
            byte[] out = new byte[(logits.length + values.length) * 4];
            int k = 0;
            for (float f : logits) {
                k = putLe(out, k, Float.floatToRawIntBits(f));
            }
            for (float f : values) {
                k = putLe(out, k, Float.floatToRawIntBits(f));
            }
            return "ok " + Base64.getEncoder().encodeToString(out);
        } catch (RuntimeException e) {
            return "error " + e;
        }
    }

    private static int putLe(byte[] out, int k, int bits) {
        out[k] = (byte) bits;
        out[k + 1] = (byte) (bits >>> 8);
        out[k + 2] = (byte) (bits >>> 16);
        out[k + 3] = (byte) (bits >>> 24);
        return k + 4;
    }

    static float[] floatsLe(byte[] bytes) {
        float[] f = new float[bytes.length / 4];
        for (int i = 0; i < f.length; i++) {
            int bits = (bytes[4 * i] & 0xff) | (bytes[4 * i + 1] & 0xff) << 8 | (bytes[4 * i + 2] & 0xff) << 16
                    | (bytes[4 * i + 3] & 0xff) << 24;
            f[i] = Float.intBitsToFloat(bits);
        }
        return f;
    }
}
