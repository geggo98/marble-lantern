package sternhalma.selfplay;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sternhalma.nn.TrainingRun;

/**
 * The overhead monitor of every training run (docs/training.md, "Run monitor"): wall time and thread CPU time per
 * category, appended as JSON lines to {@code <run>/metrics.jsonl}, and the report that compares the shares with their
 * budgets and proposes new intervals.
 *
 * <p><b>Display only.</b> Nothing reads the file to decide what a run does; a run that loses it loses only the
 * report. A line is written with one append of the complete line, so lines of two processes (the training run and a
 * separate strength estimate) never mix, and a torn last line after a kill is skipped by the report.
 *
 * <p><b>Metering is not duplicated.</b> The time of the training steps, the checkpoints and the progress lines is
 * measured by {@link TrainingRun} ({@link TrainingRun.Timing}); {@link #recordRun} turns it into lines. The monitor
 * measures only what the loop cannot see, the strength estimates and the monitor's own writes, with the same
 * {@link TrainingRun.Clock}.
 *
 * <p>Lines (flat objects of strings and numbers):
 * <ul>
 *   <li>{@code cost}: {@code category}, {@code kind}, {@code where} ({@code loop} or {@code separate}),
 *       {@code count}, {@code wall_ns}, {@code cpu_ns}, and for a single event its {@code step}</li>
 *   <li>{@code run}: one per process that ended a training loop: {@code wall_ns}, {@code cpu_ns} of the whole loop,
 *       {@code steps} done and {@code batch} size</li>
 *   <li>{@code metric}: the values of a strength estimate (display)</li>
 * </ul>
 */
public final class RunMonitor {

    /** The categories of the time budget. */
    public enum Category {
        TRAIN, SELFPLAY, CHECKPOINT, PROGRESS, STRENGTH, GATE;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Kind of the strength category: the cheap proxies on the held-out set. */
    public static final String PROXY = "proxy";
    /** Kind of the strength category: the Elo estimate of the mini-arena. */
    public static final String ELO = "elo";

    /** A budget: the share of the run's wall time that a category may take. */
    public record Budget(Category category, String kind, double share, String label) {
    }

    /** The budgets of the plan: checkpoints 1 %, progress lines 0.1 %, cheap proxies 2 %, Elo estimate 5 %. */
    public static final List<Budget> BUDGETS = List.of(
            new Budget(Category.CHECKPOINT, "", 0.01, "checkpoint"),
            new Budget(Category.PROGRESS, "", 0.001, "progress"),
            new Budget(Category.STRENGTH, PROXY, 0.02, "strength proxies"),
            new Budget(Category.STRENGTH, ELO, 0.05, "Elo estimate"));

    private final Path file;
    private final TrainingRun.Clock clock;

    /**
     * @param file  the metrics file; created on the first line, its directory must exist
     * @param clock the clock of the run (tests inject one)
     */
    public RunMonitor(Path file, TrainingRun.Clock clock) {
        this.file = Objects.requireNonNull(file, "file");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Path file() {
        return file;
    }

    public TrainingRun.Clock clock() {
        return clock;
    }

    // ---- writing ----

    /** One line: {@code name} as the field {@code event} followed by the fields (String, Number or Boolean). */
    public synchronized void event(String name, Map<String, ?> fields) throws IOException {
        Map<String, Object> all = new LinkedHashMap<>();
        all.put("event", name);
        all.put("t", System.currentTimeMillis());
        all.putAll(fields);
        append(file, json(all));
    }

    /** Appends one complete line with one write; the file is opened in append mode. */
    static void append(Path file, String line) throws IOException {
        byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                ch.write(buffer);
            }
        }
    }

    /** A cost line for {@code count} events. */
    public void cost(Category category, String kind, boolean separateProcess, long step, long count, long wallNanos,
            long cpuNanos) throws IOException {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("category", category.id());
        f.put("kind", kind);
        f.put("where", separateProcess ? "separate" : "loop");
        f.put("step", step);
        f.put("count", count);
        f.put("wall_ns", wallNanos);
        f.put("cpu_ns", cpuNanos);
        event("cost", f);
    }

    /**
     * Runs the action, measures its wall and thread CPU time with the run's clock and writes a cost line for one
     * event. The cost of the write itself is not part of it (it belongs to the observer time of the loop).
     */
    public TrainingRun.Spent measure(Category category, String kind, boolean separateProcess, long step,
            Runnable action) throws IOException {
        long wall0 = clock.wallNanos().getAsLong();
        long cpu0 = clock.cpuNanos().getAsLong();
        action.run();
        long cpu = clock.cpuNanos().getAsLong() - cpu0;
        long wall = clock.wallNanos().getAsLong() - wall0;
        cost(category, kind, separateProcess, step, 1, wall, cpu);
        return new TrainingRun.Spent(wall, cpu);
    }

    /**
     * Like {@link #measure}, for an action that can be cut short: it returns whether it ran to the end. A finished
     * action is one event of {@code kind}; a cut-short one is not counted as an event of that kind (its time would
     * make the cost per event wrong): its time goes into a line of the kind {@code kind + "-partial"} with count 0.
     */
    public TrainingRun.Spent measureAttempt(Category category, String kind, boolean separateProcess, long step,
            java.util.function.BooleanSupplier action) throws IOException {
        long wall0 = clock.wallNanos().getAsLong();
        long cpu0 = clock.cpuNanos().getAsLong();
        boolean finished = action.getAsBoolean();
        long cpu = clock.cpuNanos().getAsLong() - cpu0;
        long wall = clock.wallNanos().getAsLong() - wall0;
        if (finished) {
            cost(category, kind, separateProcess, step, 1, wall, cpu);
        } else {
            cost(category, kind + "-partial", separateProcess, step, 0, wall, cpu);
        }
        return new TrainingRun.Spent(wall, cpu);
    }

    /**
     * Writes the totals of a finished (or aborted) training loop: the time of the steps and the progress lines as
     * {@link TrainingRun} measured them, the part of the observer time that the strength estimates did not claim
     * (the monitor's own writes, a display cost) as the kind {@code monitor} of the progress category, and the {@code run} line with the
     * wall time of the whole loop, which is the denominator of every share.
     *
     * @param strengthInObservers wall and CPU time of the in-loop strength estimates (they are inside
     *                            {@code timing.observer()} and must not be counted twice)
     * @param checkpointsLogged   whether the checkpoints were logged one by one already (then no total is written)
     * @param progressLines       progress lines printed by the loop (the events of the progress budget)
     */
    public void recordRun(TrainingRun.Timing timing, long batchSize, TrainingRun.Spent strengthInObservers,
            boolean checkpointsLogged, long progressLines) throws IOException {
        cost(Category.TRAIN, "", false, -1, timing.steps(), timing.step().wallNanos(), timing.step().cpuNanos());
        if (!checkpointsLogged) {
            cost(Category.CHECKPOINT, "", false, -1, timing.checkpoints(), timing.checkpoint().wallNanos(),
                    timing.checkpoint().cpuNanos());
        }
        long observerWall = Math.max(0, timing.observer().wallNanos() - strengthInObservers.wallNanos());
        long observerCpu = Math.max(0, timing.observer().cpuNanos() - strengthInObservers.cpuNanos());
        cost(Category.PROGRESS, "", false, -1, progressLines, timing.progress().wallNanos(), timing.progress().cpuNanos());
        cost(Category.PROGRESS, "monitor", false, -1, 1, observerWall, observerCpu);
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("wall_ns", timing.loopWallNanos());
        f.put("cpu_ns", timing.loopCpuNanos());
        f.put("steps", timing.steps());
        f.put("batch", batchSize);
        event("run", f);
    }

    // ---- JSON lines ----

    static String json(Map<String, ?> fields) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, ?> e : fields.entrySet()) {
            sb.append(first ? "" : ",").append(quote(e.getKey())).append(':');
            Object v = e.getValue();
            if (v instanceof Double d) {
                sb.append(Double.isFinite(d) ? d.toString() : "null");
            } else if (v instanceof Float x) {
                sb.append(Float.isFinite(x) ? x.toString() : "null");
            } else if (v instanceof Number || v instanceof Boolean) {
                sb.append(v);
            } else {
                sb.append(quote(String.valueOf(v)));
            }
            first = false;
        }
        return sb.append('}').toString();
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    private static final Pattern FIELD = Pattern.compile(
            "\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:\\s*(?:\"((?:[^\"\\\\]|\\\\.)*)\"|([-+0-9.eE]+|null|true|false))");

    /** Parses a flat object of {@link #json}; null for a line that is not one (a torn line). */
    static Map<String, String> parse(String line) {
        String t = line.strip();
        if (!t.startsWith("{") || !t.endsWith("}")) {
            return null;
        }
        Map<String, String> result = new LinkedHashMap<>();
        Matcher m = FIELD.matcher(t);
        int end = 1;
        while (m.find()) {
            if (!t.substring(end, m.start()).strip().matches(",?")) {
                return null;
            }
            end = m.end();
            result.put(unescape(m.group(1)), m.group(2) != null ? unescape(m.group(2)) : m.group(3));
        }
        return t.substring(end, t.length() - 1).isBlank() && !result.isEmpty() ? result : null;
    }

    private static String unescape(String s) {
        if (s.indexOf('\\') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char n = s.charAt(++i);
            switch (n) {
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                    i += 4;
                }
                default -> sb.append(n);
            }
        }
        return sb.toString();
    }

    // ---- the report ----

    /**
     * One row of the report.
     *
     * @param count             events (checkpoints, estimates, progress and observer writes counted as one total)
     * @param share             wall time of the category over the wall time of all training loops
     * @param costPerEventNanos wall time per event
     * @param meanIntervalSeconds loop wall time per event now (0 if no event)
     * @param proposedSeconds   the interval that keeps the category within its budget:
     *                          {@code ceil125(cost per event / budget)}; 0 if the events cost nothing
     * @param proposedSteps     the same in training steps at the measured step rate (0 if unknown)
     */
    public record Row(String label, Category category, String kind, boolean separateProcess, long count, long wallNanos,
            long cpuNanos, double share, double cpuShare, double budget, double costPerEventNanos,
            double meanIntervalSeconds, double proposedSeconds, long proposedSteps) {

        public boolean withinBudget() {
            return share <= budget;
        }
    }

    /**
     * The report of a run directory's metrics.
     *
     * @param genWallNanos         wall time of all generation segments (the denominator of the shares of the loop)
     * @param generationsDone      generations of the mean: finished, promoted, not generation 0 (it has a strength record
     *                             and nothing else) and not a rejected one (no baseline, no strength record)
     * @param countedGenWallNanos  wall time of all segments of those generations; the mean generation is this over
     *                             {@code generationsDone}
     */
    public record Report(int processes, long loopWallNanos, long loopCpuNanos, long steps, long batch, List<Row> rows,
            long trainWallNanos, long otherWallNanos, int unreadableLines, long genWallNanos, int generationsDone,
            long countedGenWallNanos) {

        /** A report of training loops only (no generation lines). */
        public Report(int processes, long loopWallNanos, long loopCpuNanos, long steps, long batch, List<Row> rows,
                long trainWallNanos, long otherWallNanos, int unreadableLines) {
            this(processes, loopWallNanos, loopCpuNanos, steps, batch, rows, trainWallNanos, otherWallNanos,
                    unreadableLines, 0, 0, 0);
        }

        /** Wall time of a category over all its kinds. */
        public long categoryWallNanos(Category category) {
            long wall = 0;
            for (Row r : rows) {
                if (r.category == category) {
                    wall += r.wallNanos;
                }
            }
            return wall;
        }

        /** Samples per wall second of the training loops, 0 without data. */
        public double samplesPerSecond() {
            return loopWallNanos > 0 ? (double) steps * batch / (loopWallNanos / 1e9) : 0;
        }

        public Row row(Category category, String kind) {
            for (Row r : rows) {
                if (r.category == category && r.kind.equals(kind)) {
                    return r;
                }
            }
            return null;
        }
    }

    /** Reads {@code <run>/metrics.jsonl} and builds the report. */
    public static Report report(Path run) throws IOException {
        Path f = run.resolve("metrics.jsonl");
        if (!Files.exists(f)) {
            throw new IOException("no metrics file " + f);
        }
        List<Map<String, String>> events = new ArrayList<>();
        int bad = 0;
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            Map<String, String> e = parse(line);
            if (e == null) {
                bad++;
            } else {
                events.add(e);
            }
        }
        return report(events, bad);
    }

    /** The report of parsed lines (tests inject numbers here). */
    static Report report(List<Map<String, String>> events, int unreadable) {
        int processes = 0;
        long loopWall = 0;
        long loopCpu = 0;
        long steps = 0;
        long batch = 0;
        long genWall = 0;
        Map<String, Long> wallOfGen = new TreeMap<>(); // generation -> wall time of all its segments
        java.util.Set<String> counted = new java.util.TreeSet<>(); // the generations of the mean
        Map<String, long[]> sums = new TreeMap<>(); // key -> count, wall, cpu
        for (Map<String, String> e : events) {
            switch (e.getOrDefault("event", "")) {
                case "run" -> {
                    processes++;
                    loopWall += Long.parseLong(e.get("wall_ns"));
                    loopCpu += Long.parseLong(e.get("cpu_ns"));
                    steps += Long.parseLong(e.get("steps"));
                    batch = Long.parseLong(e.get("batch"));
                }
                case "gen" -> {
                    long wall = Long.parseLong(e.get("wall_ns"));
                    genWall += wall;
                    String gen = e.getOrDefault("gen", "?");
                    wallOfGen.merge(gen, wall, Long::sum);
                    // lines without the field (older runs) count as full
                    if ("1".equals(e.get("done")) && !"0".equals(e.get("full"))) {
                        counted.add(gen);
                    }
                }
                case "cost" -> {
                    String key = e.get("category") + "/" + e.get("kind") + "/" + e.get("where");
                    long[] s = sums.computeIfAbsent(key, k -> new long[3]);
                    s[0] += Long.parseLong(e.get("count"));
                    s[1] += Long.parseLong(e.get("wall_ns"));
                    s[2] += Long.parseLong(e.get("cpu_ns"));
                }
                default -> {
                }
            }
        }
        double stepsPerSecond = loopWall > 0 ? steps / (loopWall / 1e9) : 0;
        List<Row> rows = new ArrayList<>();
        long trainWall = 0;
        long otherWall = 0;
        for (Map.Entry<String, long[]> e : sums.entrySet()) {
            String[] k = e.getKey().split("/", -1);
            Category category = Category.valueOf(k[0].toUpperCase(Locale.ROOT));
            String kind = k[1];
            boolean separate = k[2].equals("separate");
            long[] s = e.getValue();
            Budget budget = null;
            for (Budget b : BUDGETS) {
                if (b.category == category && b.kind.equals(kind)) {
                    budget = b;
                }
            }
            if (category == Category.TRAIN) {
                trainWall += s[1];
            } else {
                otherWall += s[1];
            }
            // the checkpoints, progress lines and proxies happen inside the training loop and are budgeted against it;
            // everything else (self-play, gating, Elo estimates) against the whole loop, once generation lines exist
            boolean inTraining = category == Category.CHECKPOINT || category == Category.PROGRESS
                    || category == Category.STRENGTH && kind.equals(PROXY);
            long denominator = genWall > 0 && !inTraining ? genWall : loopWall;
            double share = denominator > 0 ? (double) s[1] / denominator : 0;
            double cpuShare = loopCpu > 0 ? (double) s[2] / loopCpu : 0;
            double cost = s[0] > 0 ? (double) s[1] / s[0] : 0;
            double proposed = budget == null ? 0 : ceil125(cost / 1e9 / budget.share);
            long proposedSteps = proposed > 0 && stepsPerSecond > 0 && (inTraining || genWall == 0)
                    ? Math.max(1, Math.round(ceil125(proposed * stepsPerSecond))) : 0;
            String label = budget != null ? budget.label : category.id() + (kind.isEmpty() ? "" : "/" + kind);
            rows.add(new Row(label + (separate ? " (separate process)" : ""), category, kind, separate, s[0], s[1],
                    s[2], share, cpuShare, budget == null ? Double.NaN : budget.share, cost,
                    s[0] > 0 ? denominator / 1e9 / s[0] : 0, proposed, proposedSteps));
        }
        return new Report(processes, loopWall, loopCpu, steps, batch, List.copyOf(rows), trainWall, otherWall,
                unreadable, genWall, counted.size(),
                counted.stream().mapToLong(wallOfGen::get).sum());
    }

    /** The smallest 1, 2, 5 times a power of ten that is at least {@code x}; 0 for {@code x <= 0}. */
    static double ceil125(double x) {
        if (!(x > 0)) {
            return 0;
        }
        double exp = Math.floor(Math.log10(x));
        double unit = Math.pow(10, exp);
        double m = x / unit;
        double picked = m <= 1 + 1e-9 ? 1 : m <= 2 + 1e-9 ? 2 : m <= 5 + 1e-9 ? 5 : 10;
        return picked * unit;
    }

    /** Prints the report: shares against budgets and the proposed intervals. */
    public static void print(Report r, PrintStream out) {
        if (r.processes() == 0) {
            out.println("monitor-report: no finished training loop in the metrics; the shares need the 'run' line"
                    + " that a loop writes when it ends");
        }
        out.printf(Locale.ROOT, "loops: %d process(es), %.1f s wall, %d steps, %.2f steps/s, %.1f samples/s%n",
                r.processes(), r.loopWallNanos() / 1e9, r.steps(),
                r.loopWallNanos() > 0 ? r.steps() / (r.loopWallNanos() / 1e9) : 0, r.samplesPerSecond());
        out.printf(Locale.ROOT, "%-34s %7s %10s %8s %9s %7s %-6s %12s %12s %s%n", "category", "events", "wall s",
                "share", "cpu s", "budget", "status", "cost/event", "interval now", "proposed interval");
        for (Row row : r.rows()) {
            boolean budgeted = !Double.isNaN(row.budget());
            String proposal = "";
            if (budgeted && row.count() > 0) {
                proposal = row.proposedSeconds() == 0 ? "any" : seconds(row.proposedSeconds())
                        + (row.proposedSteps() > 0 ? " (about " + row.proposedSteps() + " steps)" : "");
                if (row.category() == Category.PROGRESS) {
                    proposal = "lines: " + proposal + " apart";
                }
            }
            out.printf(Locale.ROOT, "%-34s %7d %10.3f %7.3f%% %9.3f %7s %-6s %12s %12s %s%n", row.label(),
                    row.count(), row.wallNanos() / 1e9, 100 * row.share(), row.cpuNanos() / 1e9,
                    budgeted ? String.format(Locale.ROOT, "%.1f%%", 100 * row.budget()) : "-",
                    !budgeted ? "-" : row.withinBudget() ? "ok" : "OVER",
                    row.count() > 0 ? seconds(row.costPerEventNanos() / 1e9) : "-",
                    row.count() > 0 && row.category() != Category.TRAIN ? seconds(row.meanIntervalSeconds()) : "-",
                    proposal);
        }
        if (r.genWallNanos() > 0) {
            printLoopShares(r, out);
        }
        if (r.unreadableLines() > 0) {
            out.println("unreadable lines skipped: " + r.unreadableLines());
        }
        out.println("cpu s = thread CPU time of the loop thread; the trainer's worker threads are not counted in it (the train row), so the budgets use wall time");
        out.println("proposed interval = cost per event / budget share, rounded up to 1, 2 or 5 times a power of ten;"
                + " the monitor's own writes are one total (progress/monitor), not an interval");
    }

    /** The shares of the six categories of a training loop of generations, and the Elo frequency in generations. */
    private static void printLoopShares(Report r, PrintStream out) {
        long wall = r.genWallNanos();
        long accounted = 0;
        StringBuilder sb = new StringBuilder();
        for (Category c : Category.values()) {
            long w = r.categoryWallNanos(c);
            accounted += w;
            sb.append(String.format(Locale.ROOT, "%s %.1f%% (%.1f s), ", c.id(), 100.0 * w / wall, w / 1e9));
        }
        out.printf(Locale.ROOT, "loop shares of %.1f s wall in %d completed generation(s) (promoted ones only; generation 0 and rejected"
                + " generations are in the wall time but not in the count): %sother (loading, buffers, decisions) %.1f%%%n",
                wall / 1e9, r.generationsDone(), sb, 100.0 * (wall - accounted) / wall);
        Row elo = r.row(Category.STRENGTH, ELO);
        if (elo != null && elo.count() > 0 && r.generationsDone() > 0) {
            double generationSeconds = r.countedGenWallNanos() / 1e9 / r.generationsDone();
            double perEstimate = elo.costPerEventNanos() / 1e9;
            double budget = elo.budget();
            long every = Math.max(1, (long) Math.ceil(perEstimate / budget / generationSeconds - 1e-9));
            out.printf(Locale.ROOT, "strength: one Elo estimate costs %s, %.1f%% of a generation of %s; within the"
                    + " %.0f%% budget: one estimate every %d generation(s) (--strength-every %d)%n", seconds(perEstimate),
                    100 * perEstimate / generationSeconds, seconds(generationSeconds), 100 * budget, every, every);
        }
    }

    private static String seconds(double s) {
        if (s >= 100) {
            return String.format(Locale.ROOT, "%.0f s", s);
        }
        if (s >= 1) {
            return String.format(Locale.ROOT, "%.1f s", s);
        }
        if (s >= 1e-3) {
            return String.format(Locale.ROOT, "%.1f ms", s * 1e3);
        }
        return String.format(Locale.ROOT, "%.0f us", s * 1e6);
    }
}
