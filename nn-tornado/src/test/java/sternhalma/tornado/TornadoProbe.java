package sternhalma.tornado;

import java.util.Arrays;
import java.util.SplittableRandom;

import uk.ac.manchester.tornado.api.common.TornadoDevice;

/**
 * Prints the TornadoVM device and the cost of the feasibility kernels: first execution (with kernel
 * compilation) against warm executions, and what moves between host and device.
 * A main, not a test: run {@code ./gradlew --no-build-cache :nn-tornado:probe}. See docs/tornado.md.
 */
public final class TornadoProbe {

    private static final int WARM_RUNS = 20;

    private TornadoProbe() {
    }

    public static void main(String[] args) {
        TornadoSupport.Status status = TornadoSupport.status();
        System.out.println("status: " + status);
        if (!status.usable()) {
            return;
        }
        TornadoDevice device = TornadoSupport.defaultDevice();
        System.out.println("default device: " + device.getDeviceName() + " / " + device.getDescription());

        // Shapes near the network's: SMALL has 1440 inputs; batches from 1 to 1024.
        int[][] shapes = {{64, 1440, 1}, {64, 1440, 64}, {256, 1440, 256}, {256, 1440, 1024}};
        for (int[] shape : shapes) {
            probeMatMul(shape[0], shape[1], shape[2], device);
        }
    }

    private static void probeMatMul(int rows, int cols, int batch, TornadoDevice device) {
        SplittableRandom random = new SplittableRandom(1);
        float[] weights = random(random, rows * cols);
        float[] in = random(random, batch * cols);
        long t0 = System.nanoTime();
        try (BatchedMatMul mm = new BatchedMatMul(weights, rows, cols, batch, device)) {
            mm.apply(in);
            long first = System.nanoTime() - t0;
            ExecutionProfile firstProfile = mm.lastProfile();
            long[] warm = new long[WARM_RUNS];
            for (int i = 0; i < WARM_RUNS; i++) {
                long t = System.nanoTime();
                mm.apply(in);
                warm[i] = System.nanoTime() - t;
            }
            ExecutionProfile warmProfile = mm.lastProfile();
            Arrays.sort(warm);
            double flops = 2.0 * rows * cols * batch;
            System.out.printf("matmul rows=%d cols=%d batch=%d: first %.1f ms (compile %.1f ms, in %d B);"
                            + " warm median %.3f ms, min %.3f ms (kernel %.3f ms, in %d B, out %d B, %.1f GFLOP/s)%n",
                    rows, cols, batch, first / 1e6, firstProfile.compileNanos() / 1e6, firstProfile.bytesToDevice(),
                    warm[WARM_RUNS / 2] / 1e6, warm[0] / 1e6, warmProfile.kernelNanos() / 1e6,
                    warmProfile.bytesToDevice(), warmProfile.bytesToHost(), flops / warm[WARM_RUNS / 2]);
        }
    }

    private static float[] random(SplittableRandom random, int n) {
        float[] values = new float[n];
        for (int i = 0; i < n; i++) {
            values[i] = (float) random.nextDouble(-1, 1);
        }
        return values;
    }
}
