package sternhalma.tornado;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import sternhalma.nn.ForwardPass;
import sternhalma.nn.Parameters;
import sternhalma.nn.PooledEvaluator;

/**
 * The entry for callers that must compile and run without TornadoVM, for the self-play on the device (docs/training.md, "As
 * implemented (block G1)"): the loop asks {@link #unavailableReason()}, then opens a {@link Session} (or builds the pieces
 * with {@link #network} and adds them to its own {@link PooledEvaluator}). No method has a TornadoVM type in its
 * signature, as in {@link GpuTrainers}, so a class that calls only these compiles without the TornadoVM API.
 */
public final class GpuEvaluators {

    private GpuEvaluators() {
    }

    /** Why the device cannot run kernels in this JVM, or null if it can. */
    public static String unavailableReason() {
        return GpuTrainers.unavailableReason();
    }

    /** The name of the device and its backend, for example {@code Apple M3 Max (METAL)}. */
    public static String description() {
        return GpuTrainers.description();
    }

    /** The batch size of a device plan that holds {@code positions}: the next power of two, at most 1,024. */
    public static int maxBatchFor(int positions) {
        if (positions < 1) {
            throw new IllegalArgumentException("positions must be >= 1, got " + positions);
        }
        int power = 1;
        while (power < positions && power < TornadoNetwork.DEFAULT_MAX_BATCH) {
            power <<= 1;
        }
        return power;
    }

    /**
     * A forward pass on the default device for batches of up to {@code maxBatch}. It is {@link AutoCloseable}: a
     * {@link PooledEvaluator} closes it, on its device thread, when it is closed itself. Not thread-safe: give it to one
     * {@link PooledEvaluator} (which calls it from one thread) or call it from one thread only.
     *
     * @throws IllegalStateException if {@link #unavailableReason()} is not null
     */
    public static ForwardPass network(Parameters parameters, int maxBatch) {
        return new TornadoNetwork(parameters, TornadoSupport.defaultDevice(), maxBatch);
    }

    /**
     * A pooled evaluator with one network on the device.
     *
     * @param service the evaluator: one device thread, the compute permits; close it to release the device memory
     * @param model   the network in it; its clients are the forward passes of the game threads
     * @param timings where the time of the device thread went (see {@code TornadoNetwork.timings}); read it after
     *                the games, or on the device thread's behalf
     */
    public record Session(PooledEvaluator service, PooledEvaluator.Model model, Supplier<String> timings)
            implements AutoCloseable {

        /** A session without timings (tests with another backend). */
        public Session(PooledEvaluator service, PooledEvaluator.Model model) {
            this(service, model, () -> "");
        }

        @Override
        public void close() {
            service.close();
        }
    }

    /**
     * Opens a session for {@code gamesInFlight} games: the network on the device, {@code cohorts} groups of games, and
     * {@code computeThreads} compute permits (0: none, for games on virtual threads whose scheduler is as wide as the compute
     * threads wanted, see {@link PooledEvaluator#PooledEvaluator(int, long, long)}); every plan the games can need (the powers of two up to the bucket of a
     * full cohort) is built before the method returns, so no game waits for a compiler.
     *
     * @throws IllegalStateException if {@link #unavailableReason()} is not null
     */
    public static Session open(Parameters parameters, int computeThreads, int cohorts, int gamesInFlight) {
        Pool pool = openAll(List.of(parameters), computeThreads, cohorts, gamesInFlight);
        return new Session(pool.service(), pool.models().get(0), pool.timings().get(0));
    }

    /**
     * A pooled evaluator with several networks on the device and one device thread (the matches of the loop: the candidate and
     * the best network, docs/training.md, "As implemented (block G2)").
     *
     * @param service the evaluator; close it to release the device memory of every network
     * @param models  the networks in it, in the order they were given; each has {@code cohorts} cohorts
     * @param timings per network, where the time of the device thread went
     */
    public record Pool(PooledEvaluator service, List<PooledEvaluator.Model> models, List<Supplier<String>> timings)
            implements AutoCloseable {

        @Override
        public void close() {
            service.close();
        }
    }

    /** {@link #open} for several networks that share the device thread; each is sized for {@code gamesInFlight} games. */
    public static Pool openAll(List<Parameters> networks, int computeThreads, int cohorts, int gamesInFlight) {
        if (networks.isEmpty()) {
            throw new IllegalArgumentException("at least one network is needed");
        }
        int capacity = maxBatchFor(gamesInFlight);
        PooledEvaluator service = new PooledEvaluator(computeThreads);
        try {
            List<PooledEvaluator.Model> models = new ArrayList<>();
            List<Supplier<String>> timings = new ArrayList<>();
            int largest = maxBatchFor((gamesInFlight + cohorts - 1) / cohorts);
            int sizes = Integer.numberOfTrailingZeros(largest) + 1;
            int[] warm = new int[sizes];
            for (int i = 0; i < sizes; i++) {
                warm[i] = 1 << i;
            }
            for (Parameters parameters : networks) {
                TornadoNetwork device = new TornadoNetwork(parameters, TornadoSupport.defaultDevice(), capacity);
                PooledEvaluator.Model model = service.add(device, cohorts, capacity);
                service.warmup(model, warm);
                models.add(model);
                timings.add(device::timings);
            }
            return new Pool(service, List.copyOf(models), List.copyOf(timings));
        } catch (RuntimeException | Error e) {
            service.close();
            throw e;
        }
    }
}
