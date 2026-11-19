package sternhalma.webnet;

import sternhalma.nn.Encoder;

/**
 * A deterministic stand-in for the forward pass: the outputs are a hash of the encoded input, so every position gets
 * different, reproducible priors and values and the search explores a real tree. Integer arithmetic and float
 * rounding only, so teavm-net/test/harness.mjs computes the same numbers in JavaScript.
 *
 * <p>hash: {@code h = 0; for each i with input[i] != 0: h = (h ^ (i+1)) * 0x9E3779B1; h ^= (int)(input[i] * 1024f)}.
 * Output k (k = 0..241 policy index 2*cell+channel; 1000+k for the values): {@code x = (h + k * 0x632BE5AB) * 0x85EBCA6B;
 * x ^= x >>> 13; (float)(((x & 0xFFFF) / 65536.0 - 0.5) * scale)}, scale 4 for logits, 1 for values.
 */
final class HashBackend {
    private HashBackend() {
    }

    static int hash(float[] input, int offset, int size) {
        int h = 0;
        for (int i = 0; i < size; i++) {
            float v = input[offset + i];
            if (v != 0f) {
                h = (h ^ (i + 1)) * 0x9E3779B1;
                h ^= (int) (v * 1024f);
            }
        }
        return h;
    }

    static float output(int h, int k, double scale) {
        int x = (h + k * 0x632BE5AB) * 0x85EBCA6B;
        x ^= x >>> 13;
        return (float) (((x & 0xFFFF) / 65536.0 - 0.5) * scale);
    }

    /** The three arrays of {@code apply} for the inputs of n positions: policyFrom, policyTo, values. */
    static float[][] forward(float[] input, int inputSize, int n, int valueWidth) {
        float[] from = new float[n * 121];
        float[] to = new float[n * 121];
        float[] values = new float[n * valueWidth];
        for (int b = 0; b < n; b++) {
            int h = hash(input, b * inputSize, inputSize);
            for (int c = 0; c < 121; c++) {
                from[b * 121 + c] = output(h, 2 * c + Encoder.FROM, 4);
                to[b * 121 + c] = output(h, 2 * c + Encoder.TO, 4);
            }
            for (int k = 0; k < valueWidth; k++) {
                values[b * valueWidth + k] = output(h, 1000 + k, 1);
            }
        }
        return new float[][] {from, to, values};
    }
}
