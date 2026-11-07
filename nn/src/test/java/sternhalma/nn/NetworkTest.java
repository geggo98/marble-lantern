package sternhalma.nn;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import sternhalma.engine.Geometry;

class NetworkTest {

    private static final int CENTRE = 60;

    /**
     * A network with one channel, one block, value width H = 1 and two players, every weight set by hand, and
     * one input piece on the centre cell (plane 0). Every number below is derived on paper.
     *
     * <pre>
     * conv_in:  h0[c] = ReLU(0.5 + 2 * x[c] - x[nb(c, 0)])        (k = 0 weight 2, k = 1 weight -1)
     *           h0 = 2.5 at the centre, 0 at m = nb(centre, 1) (whose direction-0 neighbour is the centre),
     *           0.5 everywhere else.
     * conv1:    t[c] = ReLU(-2 + sum of h0 over the 6 neighbours), padding counts 0
     *           far from the centre: n neighbours give ReLU(-2 + 0.5 n): n=6 -> 1, n=5 -> 0.5, n=4 -> 0, n=2 -> 0
     * conv2:    u[c] = t[c] - 0.75                                   (k = 0 weight 1, bias -0.75)
     * block:    y[c] = ReLU(u[c] + h0[c])
     *           far from the centre: n=6 -> 0.75, n=5 -> 0.25, n=4 -> ReLU(-0.25) = 0, n=2 -> 0
     *           centre: t = -2 + (5 * 0.5 + 0) = 0.5, y = 0.5 - 0.75 + 2.5 = 2.25
     *           m:      t = -2 + (2.5 + 5 * 0.5) = 3, y = 3 - 0.75 + 0 = 2.25
     *           the 2 common neighbours of centre and m: t = -2 + 2.5 + 0 + 4 * 0.5 = 2.5, y = 2.25
     *           the other 3 neighbours of the centre:   t = -2 + 2.5 + 5 * 0.5 = 3,      y = 2.75
     *           the other 3 neighbours of m:            t = -2 + 0 + 5 * 0.5 = 0.5,      y = 0.25
     * sum of y: 73 * 0.75 + 6 * 0.25 (the base, with 73 cells of 6 and 6 of 5 neighbours)
     *           + 1.5 + 1.5 + 2 * 1.5 + 3 * 2 - 3 * 0.5 (the 10 changed cells, against the base 0.75)
     *           = 56.25 + 10.5 = 66.75
     * policy:   from = y, to = -2 y + 0.1
     * value:    hidden = ReLU(2 * 66.75 / 121 - 0.5); v0 = tanh(hidden), v1 = tanh(-3 hidden + 0.2)
     * </pre>
     *
     * This fails for a wrong neighbour direction (m would be a different cell), a padding row that is not
     * zero (the n = 4 and n = 2 cells), a missing ReLU after conv1 (n = 2 cells: t = -1) or after the residual
     * add (n = 4 cells: -0.25), a skip connection that adds conv1's output or nothing, a mean over 122 rows,
     * a swapped policy channel, and a missing ReLU or tanh in the value head (second half of the test).
     */
    @Test
    void handComputedForwardPass() {
        NetworkConfig config = new NetworkConfig(1, 1, 2, 1);
        Map<String, Tensor> t = new LinkedHashMap<>();
        float[] convIn = new float[49];
        convIn[0] = 2f; // k = 0, plane 0
        convIn[HexGrid.STENCIL] = -1f; // k = 1 (direction 0), plane 0: index k * 7 + plane
        t.put("conv_in.weight", new Tensor("conv_in.weight", new int[] {1, 49}, convIn));
        t.put("conv_in.bias", new Tensor("conv_in.bias", new int[] {1}, new float[] {0.5f}));
        t.put("block0.conv1.weight", new Tensor("block0.conv1.weight", new int[] {1, 7},
                new float[] {0, 1, 1, 1, 1, 1, 1}));
        t.put("block0.conv1.bias", new Tensor("block0.conv1.bias", new int[] {1}, new float[] {-2f}));
        t.put("block0.conv2.weight", new Tensor("block0.conv2.weight", new int[] {1, 7},
                new float[] {1, 0, 0, 0, 0, 0, 0}));
        t.put("block0.conv2.bias", new Tensor("block0.conv2.bias", new int[] {1}, new float[] {-0.75f}));
        t.put("head_policy.weight", new Tensor("head_policy.weight", new int[] {2, 1}, new float[] {1f, -2f}));
        t.put("head_policy.bias", new Tensor("head_policy.bias", new int[] {2}, new float[] {0f, 0.1f}));
        t.put("head_value.dense1.weight", new Tensor("head_value.dense1.weight", new int[] {1, 1},
                new float[] {2f}));
        t.put("head_value.dense1.bias", new Tensor("head_value.dense1.bias", new int[] {1},
                new float[] {-0.5f}));
        t.put("head_value.dense2.weight", new Tensor("head_value.dense2.weight", new int[] {2, 1},
                new float[] {1f, -3f}));
        t.put("head_value.dense2.bias", new Tensor("head_value.dense2.bias", new int[] {2},
                new float[] {0f, 0.2f}));
        Network network = new Network(Parameters.of(config, t));

        float[] input = new float[Encoder.INPUT_SIZE];
        input[Encoder.index(CENTRE, Encoder.OWN)] = 1f;

        // The expected block output y, from the derivation above.
        int m = Geometry.neighbor(CENTRE, 1);
        assertEquals(CENTRE, Geometry.neighbor(m, 0), "direction 1 is the opposite of direction 0");
        float[] y = new float[HexGrid.CELLS];
        int[] perCount = new int[7];
        for (int c = 0; c < HexGrid.CELLS; c++) {
            int n = neighbourCount(c);
            perCount[n]++;
            y[c] = switch (n) {
                case 6 -> 0.75f;
                case 5 -> 0.25f;
                default -> 0f;
            };
        }
        assertArrayEquals(new int[] {0, 0, 6, 0, 36, 6, 73}, perCount, "neighbour counts of docs/network.md");
        y[CENTRE] = 2.25f;
        y[m] = 2.25f;
        for (int d = 0; d < Geometry.DIRECTIONS; d++) {
            int a = Geometry.neighbor(CENTRE, d);
            int b = Geometry.neighbor(m, d);
            if (a != m) {
                y[a] = isNeighbour(a, m) ? 2.25f : 2.75f;
            }
            if (b != CENTRE) {
                y[b] = isNeighbour(b, CENTRE) ? 2.25f : 0.25f;
            }
        }
        double sum = 0;
        for (float v : y) {
            sum += v;
        }
        assertEquals(66.75, sum, 1e-12, "the paper sum");

        float[] policy = new float[Encoder.POLICY_SIZE];
        float[] values = new float[2];
        network.forward(input, 1, policy, values);
        for (int c = 0; c < HexGrid.CELLS; c++) {
            assertEquals(y[c], policy[Encoder.policyIndex(c, Encoder.FROM)], 1e-6, "from logit of " + c);
            assertEquals(-2 * y[c] + 0.1, policy[Encoder.policyIndex(c, Encoder.TO)], 1e-6, "to logit of " + c);
        }
        double hidden = 2 * 66.75 / 121 - 0.5;
        assertEquals(Math.tanh(hidden), values[0], 1e-6);
        assertEquals(Math.tanh(-3 * hidden + 0.2), values[1], 1e-6);

        // With dense1 bias -2 the hidden unit is ReLU(1.103 - 2) = 0: v0 = tanh(0) = 0, v1 = tanh(0.2).
        // In-place parameter updates take effect at the next call.
        t.get("head_value.dense1.bias").data()[0] = -2f;
        network.forward(input, 1, policy, values);
        assertEquals(0.0, values[0], 1e-7);
        assertEquals(Math.tanh(0.2), values[1], 1e-6);
    }

    /** B = 1 with conv2 weights and bias at zero gives ReLU(0 + x) = x, so it equals the network with B = 0. */
    @Test
    void zeroBlockIsTheIdentityThroughTheSkipConnection() {
        Parameters withBlock = Parameters.uniform(NetworkConfig.tiny(2), 21, 0.5f);
        Arrays.fill(withBlock.get("block0.conv2.weight").data(), 0f);
        Arrays.fill(withBlock.get("block0.conv2.bias").data(), 0f);
        NetworkConfig noBlocks = new NetworkConfig(8, 0, 2, NetworkConfig.VALUE_HIDDEN);
        Map<String, Tensor> rest = new LinkedHashMap<>(withBlock.tensors());
        rest.keySet().removeIf(name -> name.startsWith("block"));
        Network a = new Network(withBlock);
        Network b = new Network(Parameters.of(noBlocks, rest));

        float[] input = randomInputs(new Random(4), 2);
        float[] pa = new float[2 * Encoder.POLICY_SIZE];
        float[] va = new float[4];
        float[] pb = new float[2 * Encoder.POLICY_SIZE];
        float[] vb = new float[4];
        a.forward(input, 2, pa, va);
        b.forward(input, 2, pb, vb);
        assertArrayEquals(pb, pa);
        assertArrayEquals(vb, va);
        // Control: a network that skipped the blocks entirely would pass the check above, but not this one.
        withBlock.get("block0.conv2.weight").data()[0] = 1f;
        a.forward(input, 2, pa, va);
        assertFalse(Arrays.equals(pa, pb));
    }

    /** Batched results equal one-at-a-time results bit for bit, also in a different batch order. */
    @Test
    void batchEqualsSingleEvaluations() {
        Network network = new Network(Parameters.uniform(NetworkConfig.tiny(3), 8, 0.5f));
        int batch = 5;
        float[] input = randomInputs(new Random(12), batch);
        float[] policy = filled(batch * Encoder.POLICY_SIZE);
        float[] values = filled(batch * 3);
        network.forward(input, batch, policy, values);
        for (int b = 0; b < batch; b++) {
            float[] one = Arrays.copyOfRange(input, b * Encoder.INPUT_SIZE, (b + 1) * Encoder.INPUT_SIZE);
            float[] p = new float[Encoder.POLICY_SIZE];
            float[] v = new float[3];
            network.forward(one, 1, p, v);
            assertArrayEquals(p, Arrays.copyOfRange(policy, b * Encoder.POLICY_SIZE,
                    (b + 1) * Encoder.POLICY_SIZE), "policy of sample " + b);
            assertArrayEquals(v, Arrays.copyOfRange(values, b * 3, (b + 1) * 3), "values of sample " + b);
        }
        // The samples differ, so an offset bug that reads sample 0 for every slot would be caught above.
        assertFalse(Arrays.equals(Arrays.copyOfRange(values, 0, 3), Arrays.copyOfRange(values, 3, 6)));
    }

    /** The output arrays may be longer than needed; the extra elements stay untouched. */
    @Test
    void forwardWritesOnlyItsSlices() {
        Network network = new Network(Parameters.uniform(NetworkConfig.tiny(2), 8, 0.5f));
        float[] policy = filled(Encoder.POLICY_SIZE + 3);
        float[] values = filled(2 + 3);
        network.forward(randomInputs(new Random(1), 1), 1, policy, values);
        for (int i = 0; i < 3; i++) {
            assertTrue(Float.isNaN(policy[Encoder.POLICY_SIZE + i]));
            assertTrue(Float.isNaN(values[2 + i]));
        }
        for (int i = 0; i < Encoder.POLICY_SIZE; i++) {
            assertFalse(Float.isNaN(policy[i]));
        }
    }

    /** Large weights drive the value head into saturation; tanh must keep it in [-1, 1]. */
    @Test
    void valuesStayInTheUnitInterval() {
        Network network = new Network(Parameters.uniform(NetworkConfig.tiny(3), 30, 2f));
        int batch = 8;
        float[] values = new float[batch * 3];
        network.forward(randomInputs(new Random(30), batch), batch, new float[batch * Encoder.POLICY_SIZE],
                values);
        double largest = 0;
        for (float v : values) {
            assertTrue(v >= -1f && v <= 1f, "value " + v);
            largest = Math.max(largest, Math.abs(v));
        }
        assertTrue(largest > 0.9, "the test must reach the saturated range, largest |v| = " + largest);
    }

    /**
     * Every single parameter influences the output. Biases of +1 and small weights keep every ReLU active
     * (pre-activations stay near 1), so a parameter without effect can only be one that the forward pass
     * never reads, or reads at the wrong place where it is overwritten. Inputs are nonzero in every plane, so
     * every conv_in weight has an input to multiply.
     */
    @Test
    void everyParameterChangesTheOutput() {
        NetworkConfig config = NetworkConfig.tiny(2);
        Parameters parameters = Parameters.uniform(config, 77, 0.1f);
        for (Map.Entry<String, Tensor> e : parameters.tensors().entrySet()) {
            if (e.getKey().endsWith(".bias")) {
                Arrays.fill(e.getValue().data(), 1f);
            }
        }
        Network network = new Network(parameters);
        float[] input = randomInputs(new Random(77), 1);
        float[] basePolicy = new float[Encoder.POLICY_SIZE];
        float[] baseValues = new float[2];
        network.forward(input, 1, basePolicy, baseValues);
        float[] policy = new float[Encoder.POLICY_SIZE];
        float[] values = new float[2];
        int checked = 0;
        for (Map.Entry<String, Tensor> e : parameters.tensors().entrySet()) {
            float[] data = e.getValue().data();
            for (int i = 0; i < data.length; i++) {
                float old = data[i];
                data[i] = old + 0.25f;
                network.forward(input, 1, policy, values);
                data[i] = old;
                assertTrue(!Arrays.equals(policy, basePolicy) || !Arrays.equals(values, baseValues),
                        e.getKey() + "[" + i + "] has no effect");
                checked++;
            }
        }
        assertEquals(config.parameterCount(), checked);
        network.forward(input, 1, policy, values);
        assertArrayEquals(basePolicy, policy, "parameters restored");
    }

    @Test
    void forwardRejectsBadArguments() {
        Network network = new Network(Parameters.init(NetworkConfig.tiny(2), 1));
        float[] in = new float[2 * Encoder.INPUT_SIZE];
        float[] p = new float[2 * Encoder.POLICY_SIZE];
        float[] v = new float[4];
        assertThrows(IllegalArgumentException.class, () -> network.forward(in, 0, p, v));
        assertThrows(IllegalArgumentException.class, () -> network.forward(new float[1000], 2, p, v));
        assertThrows(IllegalArgumentException.class,
                () -> network.forward(in, 2, new float[2 * Encoder.POLICY_SIZE - 1], v));
        assertThrows(IllegalArgumentException.class, () -> network.forward(in, 2, p, new float[3]));
        float[] dirty = new float[Encoder.INPUT_SIZE];
        dirty[Encoder.index(HexGrid.PADDING, Encoder.PROGRESS)] = 0.5f;
        assertThrows(IllegalArgumentException.class, () -> network.forward(dirty, 1, p, v));
        assertThrows(NullPointerException.class, () -> new Network(null));
        assertEquals(NetworkConfig.tiny(2), network.config());
    }

    @Test
    void toleranceRuleOfTheDesignDocument() {
        assertTrue(Network.agrees(1.001, 1.0)); // 0.001 <= 1e-4 + 1e-3
        assertFalse(Network.agrees(1.0012, 1.0));
        assertTrue(Network.agrees(-5e-5, 0.0));
        assertFalse(Network.agrees(2e-4, 0.0));
        assertTrue(Network.agrees(-100.09, -100.0)); // 0.09 <= 1e-4 + 0.1
        assertFalse(Network.agrees(100.2, 100.0));
    }

    /** Random inputs, nonzero in every plane of every cell, zero in the padding row. */
    static float[] randomInputs(Random random, int batch) {
        float[] input = new float[batch * Encoder.INPUT_SIZE];
        for (int b = 0; b < batch; b++) {
            for (int c = 0; c < HexGrid.CELLS; c++) {
                for (int plane = 0; plane < Encoder.PLANES; plane++) {
                    input[b * Encoder.INPUT_SIZE + Encoder.index(c, plane)] = 0.05f + 0.95f * random.nextFloat();
                }
            }
        }
        return input;
    }

    private static float[] filled(int n) {
        float[] a = new float[n];
        Arrays.fill(a, Float.NaN);
        return a;
    }

    private static int neighbourCount(int cell) {
        int n = 0;
        for (int d = 0; d < Geometry.DIRECTIONS; d++) {
            if (Geometry.neighbor(cell, d) >= 0) {
                n++;
            }
        }
        return n;
    }

    private static boolean isNeighbour(int a, int b) {
        for (int d = 0; d < Geometry.DIRECTIONS; d++) {
            if (Geometry.neighbor(a, d) == b) {
                return true;
            }
        }
        return false;
    }
}
