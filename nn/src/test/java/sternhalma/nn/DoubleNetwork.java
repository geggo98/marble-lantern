package sternhalma.nn;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import sternhalma.engine.Geometry;

/**
 * A test-only forward pass in double precision, written from the layer table of docs/network.md and not from
 * {@link ReferenceOps}. The finite-difference gradient check needs it: a float forward pass has a rounding
 * noise of about 1e-7 in the loss, which a central difference with h = 1e-6 would turn into an error of 0.1.
 *
 * <p>Besides the outputs it records the sign pattern of every ReLU pre-activation, for the kink guard: if a
 * perturbation of a parameter flips any of them, the finite difference straddles a kink and says nothing about
 * the derivative.
 */
final class DoubleNetwork {

    /** Outputs of one position: policy logits [121][2], values [P] and the ReLU sign pattern. */
    record Output(double[] policy, double[] values, boolean[] positive) {
    }

    private final NetworkConfig config;
    private final Map<String, double[]> params = new LinkedHashMap<>();

    DoubleNetwork(Parameters parameters) {
        this.config = parameters.config();
        for (Map.Entry<String, Tensor> e : parameters.tensors().entrySet()) {
            float[] data = e.getValue().data();
            double[] copy = new double[data.length];
            for (int i = 0; i < data.length; i++) {
                copy[i] = data[i];
            }
            params.put(e.getKey(), copy);
        }
    }

    /** The live parameter array of a tensor, for perturbing it. */
    double[] param(String name) {
        return params.get(name);
    }

    Output forward(float[] input, int offset) {
        int c = config.channels();
        int rows = HexGrid.ROWS;
        List<Boolean> signs = new ArrayList<>();
        double[] in = new double[rows * Encoder.PLANES];
        for (int i = 0; i < in.length; i++) {
            in[i] = input[offset + i];
        }
        double[] x = relu(conv(in, Encoder.PLANES, params.get("conv_in.weight"), params.get("conv_in.bias"), c),
                signs);
        for (int i = 0; i < config.blocks(); i++) {
            String b = "block" + i;
            double[] t = relu(conv(x, c, params.get(b + ".conv1.weight"), params.get(b + ".conv1.bias"), c), signs);
            double[] z = conv(t, c, params.get(b + ".conv2.weight"), params.get(b + ".conv2.bias"), c);
            for (int j = 0; j < z.length; j++) {
                z[j] += x[j];
            }
            x = relu(z, signs);
        }
        double[] policy = new double[Encoder.POLICY_SIZE];
        double[] wp = params.get("head_policy.weight");
        double[] bp = params.get("head_policy.bias");
        double[] pooled = new double[c];
        for (int cell = 0; cell < HexGrid.CELLS; cell++) {
            for (int o = 0; o < 2; o++) {
                double sum = bp[o];
                for (int ch = 0; ch < c; ch++) {
                    sum += wp[o * c + ch] * x[cell * c + ch];
                }
                policy[cell * 2 + o] = sum;
            }
            for (int ch = 0; ch < c; ch++) {
                pooled[ch] += x[cell * c + ch] / HexGrid.CELLS;
            }
        }
        double[] hidden = relu(dense(pooled, params.get("head_value.dense1.weight"),
                params.get("head_value.dense1.bias")), signs);
        double[] y = dense(hidden, params.get("head_value.dense2.weight"), params.get("head_value.dense2.bias"));
        double[] values = new double[y.length];
        for (int i = 0; i < y.length; i++) {
            values[i] = Math.tanh(y[i]);
        }
        boolean[] pattern = new boolean[signs.size()];
        for (int i = 0; i < pattern.length; i++) {
            pattern[i] = signs.get(i);
        }
        return new Output(policy, values, pattern);
    }

    private static double[] conv(double[] in, int cin, double[] w, double[] b, int cout) {
        double[] out = new double[HexGrid.ROWS * cout];
        for (int cell = 0; cell < HexGrid.CELLS; cell++) {
            for (int o = 0; o < cout; o++) {
                double sum = b[o];
                for (int k = 0; k < 7; k++) {
                    int row = k == 0 ? cell : Geometry.neighbor(cell, k - 1);
                    if (row < 0) {
                        continue;
                    }
                    for (int ch = 0; ch < cin; ch++) {
                        sum += w[o * 7 * cin + k * cin + ch] * in[row * cin + ch];
                    }
                }
                out[cell * cout + o] = sum;
            }
        }
        return out;
    }

    private static double[] dense(double[] in, double[] w, double[] b) {
        double[] out = new double[b.length];
        for (int o = 0; o < out.length; o++) {
            double sum = b[o];
            for (int i = 0; i < in.length; i++) {
                sum += w[o * in.length + i] * in[i];
            }
            out[o] = sum;
        }
        return out;
    }

    /** ReLU in place; records for every element whether the pre-activation is positive. */
    private static double[] relu(double[] z, List<Boolean> signs) {
        for (int i = 0; i < z.length; i++) {
            signs.add(z[i] > 0);
            z[i] = Math.max(z[i], 0);
        }
        return z;
    }

    /**
     * The data term of the batch loss of docs/training.md in double:
     * {@code (1/N) sum_s (wp_s Lp_s + cv wv_s Lv_s)}. The sign patterns of all samples are appended to
     * {@code patterns}.
     */
    double loss(TrainingBatch batch, double cv, List<boolean[]> patterns) {
        int players = config.players();
        double total = 0;
        for (int s = 0; s < batch.size(); s++) {
            Output out = forward(batch.input(), s * Encoder.INPUT_SIZE);
            patterns.add(out.positive());
            int first = batch.moveOffsets()[s];
            int end = batch.moveOffsets()[s + 1];
            double lp = 0;
            if (end > first) {
                double[] l = new double[end - first];
                double max = Double.NEGATIVE_INFINITY;
                for (int m = first; m < end; m++) {
                    l[m - first] = out.policy()[2 * batch.moveFrom()[m]] + out.policy()[2 * batch.moveTo()[m] + 1];
                    max = Math.max(max, l[m - first]);
                }
                double sum = 0;
                for (double v : l) {
                    sum += Math.exp(v - max);
                }
                double logZ = max + Math.log(sum);
                for (int m = first; m < end; m++) {
                    if (batch.moveTarget()[m] > 0) {
                        lp += batch.moveTarget()[m] * (logZ - l[m - first]);
                    }
                }
            }
            double lv = 0;
            for (int i = 0; i < players; i++) {
                double d = out.values()[i] - batch.valueTarget()[s * players + i];
                lv += d * d / players;
            }
            total += (batch.policyWeight()[s] * lp + cv * batch.valueWeight()[s] * lv) / batch.size();
        }
        return total;
    }
}
