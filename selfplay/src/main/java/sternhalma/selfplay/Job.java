package sternhalma.selfplay;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * A long-running job over numbered items, written in shards, following the project's job conventions
 * (docs/architecture.md, "Long-running jobs"; docs/training.md, "Job conventions").
 *
 * <ul>
 *   <li><b>Resumable:</b> the state comes from the shard files alone. A shard that reads and validates is SKIP; one
 *       that fails to read is renamed to {@code *.corrupt} (CLEANUP) and redone; leftover {@code *.tmp.*} files are
 *       deleted (CLEANUP). A shard written with other settings stops the run with exit code 1.</li>
 *   <li><b>Atomic:</b> a shard is written under a temporary name and renamed when complete.</li>
 *   <li><b>Abortable:</b> SIGINT and SIGTERM (a shutdown hook) set a stop flag; workers start no new item, shards
 *       whose items all finished are still written, the summary is printed, and the JVM ends with 130 or 143. A
 *       stop costs at most the shards in flight.</li>
 *   <li><b>Observable:</b> a progress line on stderr at most every {@code progressSeconds} (count, rate, ETA,
 *       elapsed), no carriage returns; the rate counts only items done by this process. {@link #status} probes the
 *       lock: exit 0 and "not running", or exit 75 with pid, start, elapsed time and the last progress line.</li>
 *   <li><b>Locked:</b> {@code FileChannel.tryLock} on {@code .<name>.lock}, released by the kernel when the process
 *       dies; a second run exits with 75.</li>
 * </ul>
 * Exit codes: 0 done or nothing to do, 1 error, 75 running already, 130/143 aborted.
 *
 * @param <R> the result of one item
 */
abstract class Job<R> {

    /** The per-thread worker of a job. */
    interface Worker<R> {
        R run(int item) throws Exception;
    }

    /** The state of an existing shard file. */
    enum ShardState { COMPLETE, CORRUPT, MISMATCH }

    static final int EXIT_OK = 0;
    static final int EXIT_ERROR = 1;
    static final int EXIT_RUNNING = 75;
    static final int EXIT_ABORTED = 130;

    final Path dir;
    final String name;
    final int items;
    final int shardSize;
    final int threads;
    final PrintStream err;
    double progressSeconds = 10;
    /** What the progress and summary lines call this job (the file names keep {@code name}). */
    String label;
    /** Items done by the last {@link #run()} of this process (not the ones that were skipped). */
    int doneItems;
    /** Test hook: request a stop after this many items done in this process (0 = never). */
    int stopAfter;
    private volatile boolean stop;
    private String mismatch;

    Job(Path dir, String name, int items, int shardSize, int threads, PrintStream err) {
        if (items < 0 || shardSize < 1 || threads < 1) {
            throw new IllegalArgumentException("bad job size");
        }
        this.dir = dir;
        this.name = name;
        this.label = name;
        this.items = items;
        this.shardSize = shardSize;
        this.threads = threads;
        this.err = err;
    }

    /** The pool that runs the items: {@code threads} threads (a subclass may use virtual threads). */
    ExecutorService newPool() {
        return Executors.newFixedThreadPool(threads);
    }

    /**
     * Virtual threads for games that wait for a device: {@code threads} of them (a parked one costs nothing) on a scheduler of
     * {@code carriers} carriers, which bounds the CPU use (the properties are read when the first virtual thread of the JVM
     * starts: the first value in a JVM wins).
     */
    static ExecutorService virtualPool(int threads, int carriers) {
        System.setProperty("jdk.virtualThreadScheduler.parallelism", Integer.toString(carriers));
        System.setProperty("jdk.virtualThreadScheduler.maxPoolSize", Integer.toString(carriers));
        return Executors.newFixedThreadPool(threads, Thread.ofVirtual().name("game-", 0).factory());
    }

    /**
     * The tail of the job: the last this many items of the to-do list run in {@link #newTailPool()} with
     * {@link #tailWorkers()} from the start, so that the end of a phase on a device does not run with a few positions per
     * execution (docs/training.md, "As implemented (block G2)"). 0: no tail, one pool. Which items form the tail is a function of
     * the to-do list, not of the timing, so a restart plays the same items in the same way.
     */
    int tailItems() {
        return 0;
    }

    /** The pool of the tail items; null if {@link #tailItems()} is 0. Closed by the job. */
    ExecutorService newTailPool() {
        return null;
    }

    /** The workers of the tail items. */
    Supplier<Worker<R>> tailWorkers() {
        return workers();
    }

    abstract Path shardFile(int shard);

    /** The state of an existing shard file; sets {@link #mismatch(String)} for MISMATCH. */
    abstract ShardState check(Path file, int shard, int first, int count);

    abstract Supplier<Worker<R>> workers();

    abstract void write(Path file, int shard, int first, List<R> results) throws IOException;

    /** One-line description of a result for the progress line (may be empty). */
    String progressExtra() {
        return "";
    }

    void mismatch(String message) {
        this.mismatch = message;
    }

    void requestStop() {
        stop = true;
    }

    boolean stopped() {
        return stop;
    }

    Path lockFile() {
        return dir.resolve("." + name + ".lock");
    }

    Path statusFile() {
        return dir.resolve("." + name + ".status");
    }

    int shards() {
        return (items + shardSize - 1) / shardSize;
    }

    private static FileLock tryLock(FileChannel channel) throws IOException {
        try {
            return channel.tryLock();
        } catch (OverlappingFileLockException e) {
            return null; // held by this JVM
        }
    }

    /** {@code --status}: 0 when no run holds the lock, 75 when one does (pid, start, elapsed, last progress). */
    static int status(Path dir, String name, PrintStream out) throws IOException {
        return status(dir, dir.resolve("." + name + ".lock"), dir.resolve("." + name + ".status"), out);
    }

    /** {@link #status(Path, String, PrintStream)} for a job whose lock and status files have other names. */
    static int status(Path dir, Path lock, Path status, PrintStream out) throws IOException {
        if (!Files.isDirectory(dir)) {
            out.println("not running (no directory " + dir + ")");
            return EXIT_OK;
        }
        try (FileChannel ch = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock held = tryLock(ch)) {
            if (held != null) {
                out.println("not running" + (Files.exists(status)
                        ? " (leftover status: " + Files.readString(status).strip().replace('\n', ' ') + ")" : ""));
                return EXIT_OK;
            }
        }
        List<String> lines = Files.exists(status) ? Files.readAllLines(status) : List.of();
        String pidLine = lines.isEmpty() ? "?" : lines.get(0);
        String elapsed = "?";
        try {
            long pid = Long.parseLong(pidLine.replaceAll(".*pid=(\\d+).*", "$1"));
            elapsed = ProcessHandle.of(pid).flatMap(p -> p.info().startInstant())
                    .map(s -> human(Duration.between(s, Instant.now()).toSeconds())).orElse("? (process gone)");
        } catch (NumberFormatException e) {
            // no pid in the status file
        }
        out.println("running already: " + pidLine + " elapsed=" + elapsed
                + (lines.size() > 1 ? "\n  last progress: " + lines.get(lines.size() - 1) : ""));
        return EXIT_RUNNING;
    }

    static String human(double seconds) {
        long s = Math.round(seconds);
        if (s < 60) {
            return s + "s";
        }
        if (s < 3600) {
            return s / 60 + "m" + String.format(Locale.ROOT, "%02d", s % 60) + "s";
        }
        return s / 3600 + "h" + String.format(Locale.ROOT, "%02d", s % 3600 / 60) + "m";
    }

    private void writeStatus(String start, String progress) {
        try {
            Path tmp = statusFile().resolveSibling(statusFile().getFileName() + ".tmp." + ProcessHandle.current().pid());
            Files.writeString(tmp, "pid=" + ProcessHandle.current().pid() + " start=" + start + "\n"
                    + (progress == null ? "" : progress + "\n"));
            Files.move(tmp, statusFile(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            err.println(label + ": cannot write status: " + e);
        }
    }

    /** Runs the job and returns the exit code. */
    int run() throws IOException, InterruptedException {
        Files.createDirectories(dir);
        try (FileChannel lockChannel = FileChannel.open(lockFile(), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE); FileLock lock = tryLock(lockChannel)) {
            if (lock == null) {
                status(dir, name, err);
                return EXIT_RUNNING;
            }
            String start = Instant.now().toString();
            writeStatus(start, null);
            int cleanup = 0;
            try (Stream<Path> s = Files.list(dir)) {
                for (Path f : s.filter(f -> f.getFileName().toString().contains(".tmp.")).toList()) {
                    Files.deleteIfExists(f);
                    cleanup++;
                }
            }
            List<Integer> todo = new ArrayList<>();
            int skip = 0;
            for (int sh = 0; sh < shards(); sh++) {
                Path f = shardFile(sh);
                int first = sh * shardSize;
                int count = Math.min(items, first + shardSize) - first;
                if (!Files.exists(f)) {
                    todo.add(sh);
                    continue;
                }
                switch (check(f, sh, first, count)) {
                    case COMPLETE -> skip++;
                    case MISMATCH -> {
                        err.println(label + ": " + f + ": " + mismatch + "; use another directory or delete the shards");
                        return EXIT_ERROR;
                    }
                    case CORRUPT -> {
                        Files.move(f, f.resolveSibling(f.getFileName() + ".corrupt"),
                                StandardCopyOption.REPLACE_EXISTING);
                        cleanup++;
                        todo.add(sh);
                    }
                }
            }
            int todoItems = 0;
            for (int sh : todo) {
                todoItems += Math.min(items, sh * shardSize + shardSize) - sh * shardSize;
            }
            err.printf(Locale.ROOT, "%s: %d shards: %d complete (skip), %d to do (%d items), %d cleanup%n", label,
                    shards(), skip, todo.size(), todoItems, cleanup);
            CountDownLatch finished = new CountDownLatch(1);
            Thread hook = new Thread(() -> {
                requestStop();
                try {
                    finished.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, name + "-shutdown");
            Runtime.getRuntime().addShutdownHook(hook);
            int ok = 0;
            int errors = 0;
            int aborted = 0;
            AtomicInteger done = new AtomicInteger();
            long t0 = System.nanoTime();
            long[] last = {t0};
            int total = todoItems;
            Supplier<Worker<R>> factory = workers();
            ThreadLocal<Worker<R>> local = ThreadLocal.withInitial(factory);
            int tail = Math.min(tailItems(), todoItems);
            ThreadLocal<Worker<R>> tailLocal = tail > 0 ? ThreadLocal.withInitial(tailWorkers()) : null;
            int ordinal = 0;
            try (ExecutorService pool = newPool(); ExecutorService tailPool = tail > 0 ? newTailPool() : null) {
                List<List<Future<R>>> perShard = new ArrayList<>();
                for (int sh : todo) {
                    List<Future<R>> fs = new ArrayList<>();
                    for (int item = sh * shardSize; item < Math.min(items, sh * shardSize + shardSize); item++) {
                        int it = item;
                        boolean inTail = ordinal++ >= todoItems - tail;
                        fs.add((inTail ? tailPool : pool).submit(() -> {
                            if (stop) {
                                return null;
                            }
                            R r = (inTail ? tailLocal : local).get().run(it);
                            int n = done.incrementAndGet();
                            if (stopAfter > 0 && n >= stopAfter) {
                                requestStop();
                            }
                            synchronized (last) {
                                long now = System.nanoTime();
                                if (now - last[0] >= progressSeconds * 1e9 || n == total) {
                                    last[0] = now;
                                    double el = (now - t0) / 1e9;
                                    double rate = n / el;
                                    String line = String.format(Locale.ROOT,
                                            "%s: %d/%d items  %.2f/s  ETA %s  elapsed %s%s", label, n, total, rate,
                                            human((total - n) / rate), human(el), progressExtra());
                                    err.println(line);
                                    writeStatus(start, line);
                                }
                            }
                            return r;
                        }));
                    }
                    perShard.add(fs);
                }
                for (int i = 0; i < todo.size(); i++) {
                    int sh = todo.get(i);
                    List<R> results = new ArrayList<>();
                    boolean complete = true;
                    boolean failed = false;
                    for (Future<R> f : perShard.get(i)) {
                        try {
                            R r = f.get();
                            if (r == null) {
                                complete = false;
                            } else {
                                results.add(r);
                            }
                        } catch (java.util.concurrent.ExecutionException e) {
                            failed = true;
                            err.println(label + ": shard " + sh + ": item failed: " + e.getCause());
                            e.getCause().printStackTrace(err);
                        }
                    }
                    if (failed) {
                        errors++;
                    } else if (!complete) {
                        aborted++;
                    } else {
                        try {
                            write(shardFile(sh), sh, sh * shardSize, results);
                            ok++;
                        } catch (IOException e) {
                            err.println(label + ": shard " + sh + ": write failed: " + e);
                            errors++;
                        }
                    }
                }
            }
            int left = shards() - skip - ok;
            doneItems = done.get();
            double el = (System.nanoTime() - t0) / 1e9;
            err.printf(Locale.ROOT, "%s: %d ok, %d skip, %d error, %d cleanup, %d left (shards); %d items in %s%s%n",
                    label, ok, skip, errors, cleanup, left, done.get(), human(el),
                    stop ? " - aborted, run again to continue"
                            : left == 0 ? " - nothing more to do" : " - run again to retry the failed shards");
            finished.countDown();
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException e) {
                // shutting down because of a signal; the JVM ends with 128 + signal
            }
            if (stop) {
                return EXIT_ABORTED;
            }
            return errors > 0 ? EXIT_ERROR : EXIT_OK;
        }
    }
}
