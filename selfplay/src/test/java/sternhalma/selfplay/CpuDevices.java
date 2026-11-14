package sternhalma.selfplay;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import sternhalma.nn.ForwardPass;
import sternhalma.nn.NetworkConfig;
import sternhalma.nn.Networks;
import sternhalma.nn.Parameters;
import sternhalma.nn.PooledEvaluator;
import sternhalma.tornado.GpuEvaluators;

/**
 * Devices for the tests of {@code --evaluator gpu} that need no TornadoVM: a {@link PooledEvaluator} whose backends are CPU
 * forward passes (the Vector API pass gives a position the same bits in any batch, as the device network does), optionally
 * slow, and a count of the batches.
 */
final class CpuDevices implements Loop.Devices {

    /** Batches computed by the backends of all pools opened here. */
    final AtomicInteger batches = new AtomicInteger();
    /** Pools opened here, with their networks counted. */
    final AtomicInteger opened = new AtomicInteger();
    /** The services of the pools opened here, in order. */
    final List<PooledEvaluator> services = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** The networks of the last pool opened. */
    volatile int lastNetworks;
    private final long delayMillis;

    CpuDevices(long delayMillis) {
        this.delayMillis = delayMillis;
    }

    @Override
    public String unavailableReason() {
        return null;
    }

    @Override
    public String description() {
        return "cpu-backed test device";
    }

    @Override
    public GpuEvaluators.Pool open(List<Parameters> networks, int cohorts, int gamesInFlight) {
        opened.incrementAndGet();
        lastNetworks = networks.size();
        PooledEvaluator service = new PooledEvaluator(0);
        services.add(service);
        List<PooledEvaluator.Model> models = new ArrayList<>();
        List<Supplier<String>> timings = new ArrayList<>();
        for (Parameters p : networks) {
            models.add(service.add(counted(Networks.fastest(p)), cohorts, Math.max(1, gamesInFlight)));
            timings.add(() -> "cpu-backed");
        }
        return new GpuEvaluators.Pool(service, List.copyOf(models), List.copyOf(timings));
    }

    private ForwardPass counted(ForwardPass inner) {
        return new ForwardPass() {
            @Override
            public NetworkConfig config() {
                return inner.config();
            }

            @Override
            public boolean threadSafe() {
                return false;
            }

            @Override
            public void forward(float[] input, int batch, float[] policyLogits, float[] values) {
                batches.incrementAndGet();
                if (delayMillis > 0) {
                    try {
                        Thread.sleep(delayMillis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                inner.forward(input, batch, policyLogits, values);
            }
        };
    }
}
