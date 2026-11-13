package sternhalma.nn;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Evaluates the positions of many games at once with one device thread (docs/training.md, "As implemented (block G1)").
 *
 * <p><b>The idea.</b> A self-play game searches with batch-1 simulations (no virtual loss, so no cost in strength), and
 * every simulation needs one forward pass. A device is slow for one position and fast for hundreds, so the games run in
 * their own threads and hand their positions to this class. Each game thread calls {@link Client#forward} like any
 * {@link ForwardPass}; the call returns when the batch that holds its position has been computed. So a game is the same
 * code as on the CPU, and what it computes depends on its own seed and on the network only: every position is evaluated
 * on its own, so the batch it was in does not change its result (with a device whose result does not depend on the slot
 * and the batch shape; the {@code TornadoNetwork} is bit-identical there, tested).
 *
 * <h2>Cohorts</h2>
 *
 * <p>The games of a model are split into {@code cohorts} groups (clients are dealt round robin). A cohort has one
 * batch buffer, and fills it while the device computes another cohort: with two cohorts, the CPU prepares one while the
 * device runs the other (ping-pong). One cohort is lockstep: every game waits for every other. A cohort is dispatched when
 * <ul>
 *   <li>all of its active games have published their position (a <em>full</em> batch), or
 *   <li>the device thread is idle and nothing arrived for {@code quiet} (the others are busy elsewhere, for example in a
 *       long adjudication or waiting for a compute permit; the batch does not wait for them), or
 *   <li>the oldest position has waited {@code maxWait}.
 * </ul>
 * Games that are not running (not between {@link Client#begin} and {@link Client#end}) are never waited for, so the tail of
 * a generation, where fewer games than the cohort size are left, does not idle: the batch is full as soon as the games that
 * are left have published.
 *
 * <h2>Compute permits</h2>
 *
 * <p>There are as many game threads as games in flight (hundreds), but only {@code computeThreads} of them may compute at a
 * time: {@link Client#begin} takes a permit, {@link Client#forward} gives it back while it waits for the device and takes
 * it again afterwards, {@link Client#end} gives it back. So the CPU use of the job is bounded by one number, whatever the
 * number of games. The permit has a price: with hundreds of platform threads, every evaluation parks and unparks its
 * thread twice and the permit is fought for by the whole batch (measured: a third of the throughput of the alternative).
 * With {@code computeThreads} 0 there are no permits, and the games are virtual threads on a scheduler with as many carriers
 * as compute threads wanted ({@code -Djdk.virtualThreadScheduler.parallelism}): a parked virtual thread costs nothing, and
 * the carriers bound the CPU use. This class works the same way with both.
 *
 * <h2>Threads and models</h2>
 *
 * <p>Exactly one thread, the evaluator thread of this class, calls the backends: a {@code TornadoNetwork} is not thread-safe.
 * One instance can serve several networks ({@link #add}), for example a candidate and the best network, with one device
 * thread; the cohorts of all models compete for it, the one that has waited longest first. A backend that is
 * {@link AutoCloseable} is closed on that thread when this instance is closed.
 *
 * <p>{@link #abort()} ends the work of the games without a failure: they get an {@link Aborted}, the caller discards
 * the games. A failure of a backend (an exception) fails every game that waits in that batch and every later call with an
 * {@link IllegalStateException} that names the cause.
 */
public final class PooledEvaluator implements AutoCloseable {

    /** The default quiet time after which an idle device thread takes a partial batch: 250 microseconds. */
    public static final long DEFAULT_QUIET_NANOS = 250_000L;
    /** The default longest wait of the oldest position of a batch: 20 milliseconds. */
    public static final long DEFAULT_MAX_WAIT_NANOS = 20_000_000L;

    private final ReentrantLock lock = new ReentrantLock();
    /** Signalled when the device thread has something to do: a full cohort, a task, close. */
    private final Condition work = lock.newCondition();
    /** The compute permits, or null if the caller bounds the compute threads itself (virtual threads on a small scheduler). */
    private final Semaphore permits;
    private final long quietNanos;
    private final long maxWaitNanos;
    private final List<Model> models = new ArrayList<>();
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    private final Thread thread;
    private boolean closed;
    private boolean aborted;
    private Throwable failure;
    private Throwable closeFailure;
    private long batches;
    private long positions;
    private long executeNanos;
    private long idleNanos;
    private final long[] histogram = new long[32];

    /** A pool with the default quiet time and longest wait. */
    public PooledEvaluator(int computeThreads) {
        this(computeThreads, DEFAULT_QUIET_NANOS, DEFAULT_MAX_WAIT_NANOS);
    }

    /**
     * @param computeThreads game threads that may compute at the same time, at least 1; 0 for no permits (the games are
     *                       virtual threads on a scheduler of as many carriers as compute threads wanted: a permit that
     *                       many threads fight for costs a park and an unpark per game and evaluation)
     * @param quietNanos     an idle device thread takes a partial batch when nothing was published for this long
     * @param maxWaitNanos   a batch is taken when its oldest position has waited this long, whatever happens
     */
    public PooledEvaluator(int computeThreads, long quietNanos, long maxWaitNanos) {
        if (computeThreads < 0 || quietNanos < 0 || maxWaitNanos < quietNanos) {
            throw new IllegalArgumentException("computeThreads >= 0 and 0 <= quiet <= maxWait expected, got "
                    + computeThreads + ", " + quietNanos + ", " + maxWaitNanos);
        }
        this.permits = computeThreads == 0 ? null : new Semaphore(computeThreads);
        this.quietNanos = quietNanos;
        this.maxWaitNanos = maxWaitNanos;
        this.thread = new Thread(this::serve, "pooled-evaluator");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    /** Thrown to a game thread when {@link #abort()} was called: the game is to be discarded, nothing went wrong. */
    public static final class Aborted extends RuntimeException {

        private static final long serialVersionUID = 1L;

        Aborted() {
            super("the evaluator was aborted");
        }
    }

    /**
     * Ends the work: every game that waits in {@link Client#forward} or calls it or {@link Client#begin} later gets an
     * {@link Aborted} as soon as the batch in flight, if any, is back (the device thread does not start new work for
     * games after that). It is what a stop request (SIGINT, SIGTERM) does, so that a stop costs one batch and not the
     * rest of every game in flight. {@link #close()} is still needed.
     */
    public void abort() {
        lock.lock();
        try {
            aborted = true;
            work.signalAll();
            for (Model m : models) {
                for (Cohort c : m.cohorts) {
                    c.free.signalAll();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /** What the device thread did so far. */
    public record Stats(long batches, long positions, long executeNanos, long idleNanos, long[] sizeHistogram) {

        /** Mean positions per batch, 0 before the first batch. */
        public double meanBatch() {
            return batches == 0 ? 0 : (double) positions / batches;
        }
    }

    /**
     * The counters of the device thread: batches, positions, time in the backends, time waiting for work, and a histogram
     * of the batch sizes (entry k counts batches of 2^(k-1)+1 to 2^k positions; entry 0 batches of 1).
     */
    public Stats stats() {
        lock.lock();
        try {
            return new Stats(batches, positions, executeNanos, idleNanos, histogram.clone());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Adds a network. The backend is called by the device thread only.
     *
     * @param backend  the forward pass; closed by {@link #close()} if it is {@link AutoCloseable}
     * @param cohorts  groups of games that fill their batch while another group is computed, at least 1
     * @param capacity positions per batch at most (the backend's largest batch), at least 1; the games that run in one
     *                 cohort at the same time must not publish more positions than this
     */
    public Model add(ForwardPass backend, int cohorts, int capacity) {
        Objects.requireNonNull(backend, "backend");
        if (cohorts < 1 || capacity < 1) {
            throw new IllegalArgumentException("cohorts and capacity must be >= 1, got " + cohorts + ", " + capacity);
        }
        Model model = new Model(backend, cohorts, capacity);
        lock.lock();
        try {
            if (closed) {
                throw new IllegalStateException("the evaluator is closed");
            }
            models.add(model);
        } finally {
            lock.unlock();
        }
        return model;
    }

    /**
     * Runs the backend of a model once on the device thread for each batch size, so that plans and kernels are built before
     * the games start (the outputs are discarded). Blocks until done.
     */
    public void warmup(Model model, int... batchSizes) {
        FutureTask<Void> task = new FutureTask<>(() -> {
            NetworkConfig config = model.backend.config();
            for (int n : batchSizes) {
                model.backend.forward(new float[n * config.inputSize()], n, new float[n * Encoder.POLICY_SIZE],
                        new float[n * config.players()]);
            }
            return null;
        });
        lock.lock();
        try {
            requireOpen();
            tasks.add(task);
            work.signal();
        } finally {
            lock.unlock();
        }
        try {
            task.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted during the warm-up", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("the warm-up failed", e.getCause());
        }
    }

    /**
     * Stops the device thread after it has computed every batch that is complete, and closes the backends. Games that wait
     * for a slot get an {@link IllegalStateException}. A second call does nothing.
     *
     * @throws IllegalStateException if closing a backend failed
     */
    @Override
    public void close() {
        lock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            work.signalAll();
            for (Model m : models) {
                for (Cohort c : m.cohorts) {
                    c.free.signalAll();
                }
            }
        } finally {
            lock.unlock();
        }
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (closeFailure != null) {
            throw new IllegalStateException("could not close a backend", closeFailure);
        }
    }

    private void requireOpen() {
        if (aborted) {
            throw new Aborted();
        }
        if (failure != null) {
            throw new IllegalStateException("the evaluator failed: " + failure, failure);
        }
        if (closed) {
            throw new IllegalStateException("the evaluator is closed");
        }
    }

    // ---- the model and its clients ----

    /** One network and its cohorts. */
    public final class Model {

        private final ForwardPass backend;
        private final int capacity;
        private final List<Cohort> cohorts = new ArrayList<>();
        private int dealt;

        private Model(ForwardPass backend, int cohortCount, int capacity) {
            this.backend = backend;
            this.capacity = capacity;
            int size = backend.config().inputSize();
            for (int i = 0; i < cohortCount; i++) {
                cohorts.add(new Cohort(this, new float[capacity * size]));
            }
        }

        public NetworkConfig config() {
            return backend.config();
        }

        /** Positions per batch at most. */
        public int capacity() {
            return capacity;
        }

        /** A forward pass for one game thread; it joins the cohorts round robin. */
        public Client newClient() {
            lock.lock();
            try {
                return new Client(this, cohorts.get(dealt++ % cohorts.size()));
            } finally {
                lock.unlock();
            }
        }
    }

    /** The positions of one group of games: the batch that is filled, and whether it is being computed. */
    private final class Cohort {

        final Model model;
        final float[] input;
        final Condition free = lock.newCondition();
        Batch batch = new Batch();
        /** Clients between begin and end. */
        int active;
        /** Positions that have a slot; {@link #published} of them have their planes in it. */
        int count;
        int published;
        /** Clients that have published and wait for the batch. */
        int waiting;
        boolean executing;
        long first;
        long last;

        Cohort(Model model, float[] input) {
            this.model = model;
            this.input = input;
        }
    }

    /** The results of one execution, handed to the games that waited for it. */
    private static final class Batch {
        final CountDownLatch done = new CountDownLatch(1);
        float[] policy;
        float[] values;
        Throwable failure;
    }

    /**
     * The forward pass of one game thread. Not shared between threads. A game calls {@link #begin} first and {@link #end}
     * last (in a finally block); {@link #forward} blocks until the batch with its positions is computed.
     */
    public final class Client implements ForwardPass {

        private final Model model;
        private final Cohort cohort;
        private boolean begun;

        private Client(Model model, Cohort cohort) {
            this.model = model;
            this.cohort = cohort;
        }

        @Override
        public NetworkConfig config() {
            return model.backend.config();
        }

        /** False: a client belongs to one thread. */
        @Override
        public boolean threadSafe() {
            return false;
        }

        /** Takes a compute permit and joins the active games of the cohort. */
        public void begin() {
            if (begun) {
                throw new IllegalStateException("begin() twice");
            }
            acquire();
            lock.lock();
            try {
                requireOpen();
                cohort.active++;
                begun = true;
            } catch (RuntimeException e) {
                release();
                throw e;
            } finally {
                lock.unlock();
            }
        }

        /** Leaves the active games and gives the compute permit back; a batch that waited for this game is complete now. */
        public void end() {
            if (!begun) {
                throw new IllegalStateException("end() without begin()");
            }
            begun = false;
            lock.lock();
            try {
                cohort.active--;
                if (cohort.waiting > 0 && cohort.waiting >= cohort.active) {
                    work.signal();
                }
            } finally {
                lock.unlock();
                release();
            }
        }

        /**
         * Puts the positions into the next batch of the cohort and waits for the results.
         *
         * @throws IllegalStateException if the client has not begun, the evaluator is closed or failed, or the batch failed
         * @throws IllegalArgumentException if batch exceeds the capacity of the model or an array is too short
         */
        @Override
        public void forward(float[] input, int batch, float[] policyLogits, float[] values) {
            NetworkConfig config = model.backend.config();
            int size = config.inputSize();
            int players = config.players();
            if (!begun) {
                throw new IllegalStateException("forward() before begin()");
            }
            if (batch < 1 || batch > model.capacity) {
                throw new IllegalArgumentException("batch must be 1 to " + model.capacity + ", got " + batch);
            }
            if (input.length < (long) batch * size || policyLogits.length < (long) batch * Encoder.POLICY_SIZE
                    || values.length < (long) batch * players) {
                throw new IllegalArgumentException("an array is too short for a batch of " + batch);
            }
            release();
            try {
                Batch mine;
                int slot;
                lock.lock();
                try {
                    while (true) {
                        requireOpen();
                        if (!cohort.executing && cohort.count + batch <= model.capacity) {
                            break;
                        }
                        cohort.free.awaitUninterruptibly();
                    }
                    slot = cohort.count;
                    cohort.count += batch;
                    mine = cohort.batch;
                } finally {
                    lock.unlock();
                }
                // the slots are ours alone until the batch is taken, and it is not taken before they are published
                System.arraycopy(input, 0, cohort.input, slot * size, batch * size);
                lock.lock();
                try {
                    long now = System.nanoTime();
                    cohort.published += batch;
                    if (cohort.waiting++ == 0) {
                        cohort.first = now;
                    }
                    cohort.last = now;
                    // a full cohort is due at once; the first position of a batch makes the device thread arm its timers
                    if (cohort.waiting == 1 || cohort.waiting >= cohort.active || cohort.count >= model.capacity) {
                        work.signal();
                    }
                } finally {
                    lock.unlock();
                }
                try {
                    mine.done.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while waiting for the device", e);
                }
                if (mine.failure != null) {
                    throw new IllegalStateException("the evaluator failed: " + mine.failure, mine.failure);
                }
                if (aborted) {
                    throw new Aborted();
                }
                System.arraycopy(mine.policy, slot * Encoder.POLICY_SIZE, policyLogits, 0, batch * Encoder.POLICY_SIZE);
                System.arraycopy(mine.values, slot * players, values, 0, batch * players);
            } finally {
                acquire();
            }
        }

        private void acquire() {
            if (permits != null) {
                permits.acquireUninterruptibly();
            }
        }

        private void release() {
            if (permits != null) {
                permits.release();
            }
        }
    }

    // ---- the device thread ----

    private void serve() {
        lock.lock();
        try {
            long idleSince = System.nanoTime();
            while (true) {
                Runnable task = tasks.poll();
                if (task != null) {
                    lock.unlock();
                    try {
                        task.run();
                    } finally {
                        lock.lock();
                    }
                    idleSince = System.nanoTime();
                    continue;
                }
                long now = System.nanoTime();
                long[] wake = {Long.MAX_VALUE};
                Cohort c = pick(now, wake);
                if (c == null && closed) {
                    break;
                }
                if (c == null) {
                    long wait = wake[0] == Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(1, wake[0] - now);
                    try {
                        if (wait == Long.MAX_VALUE) {
                            work.await();
                        } else {
                            work.awaitNanos(wait);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }
                idleNanos += now - idleSince;
                execute(c);
                idleSince = System.nanoTime();
            }
        } finally {
            lock.unlock();
            closeBackends();
            lock.lock();
            try {
                // wake everybody that still waits for a slot; the ones that wait for results get them or the failure
                closed = true;
                for (Model m : models) {
                    for (Cohort c : m.cohorts) {
                        c.free.signalAll();
                        if (c.count > 0 && !c.executing) {
                            c.batch.failure = new IllegalStateException("the evaluator is closed");
                            c.batch.done.countDown();
                        }
                    }
                }
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * The cohort to compute now, or null; with null, {@code wake[0]} is the time at which one becomes due (MAX if none
     * waits). The cohort that waited longest wins. While closed, every complete batch is due.
     */
    private Cohort pick(long now, long[] wake) {
        Cohort best = null;
        for (Model m : models) {
            for (Cohort c : m.cohorts) {
                if (c.executing || c.count == 0 || c.published != c.count) {
                    continue;
                }
                boolean full = c.waiting >= c.active || c.count >= m.capacity;
                long due = Math.min(c.last + quietNanos, c.first + maxWaitNanos);
                if (full || closed || aborted || now >= due) {
                    if (best == null || c.first < best.first) {
                        best = c;
                    }
                } else {
                    wake[0] = Math.min(wake[0], due);
                }
            }
        }
        return best;
    }

    /** Computes the batch of a cohort (the lock is held; it is released around the backend call). */
    private void execute(Cohort c) {
        Batch b = c.batch;
        int n = c.count;
        c.executing = true;
        NetworkConfig config = c.model.backend.config();
        lock.unlock();
        long t0 = System.nanoTime();
        try {
            float[] policy = new float[n * Encoder.POLICY_SIZE];
            float[] values = new float[n * config.players()];
            try {
                c.model.backend.forward(c.input, n, policy, values);
                b.policy = policy;
                b.values = values;
            } catch (Throwable t) { // anything: the games must not wait for ever
                b.failure = t;
            }
        } finally {
            long took = System.nanoTime() - t0;
            lock.lock();
            executeNanos += took;
            batches++;
            positions += n;
            histogram[32 - Integer.numberOfLeadingZeros(n - 1)]++;
            if (b.failure != null && failure == null) {
                failure = b.failure;
                closed = true;
                work.signalAll();
            }
            c.executing = false;
            c.count = 0;
            c.published = 0;
            c.waiting = 0;
            c.batch = new Batch();
            c.free.signalAll();
            for (Model m : models) {
                for (Cohort other : m.cohorts) {
                    other.free.signalAll();
                }
            }
            b.done.countDown();
        }
    }

    private void closeBackends() {
        List<Model> copy;
        lock.lock();
        try {
            copy = new ArrayList<>(models);
        } finally {
            lock.unlock();
        }
        for (Model m : copy) {
            if (m.backend instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception e) {
                    if (closeFailure == null) {
                        closeFailure = e;
                    } else {
                        closeFailure.addSuppressed(e);
                    }
                }
            }
        }
    }
}
