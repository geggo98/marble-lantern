package sternhalma.selfplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sternhalma.nn.TrainingRun;
import sternhalma.selfplay.RunMonitor.Category;
import sternhalma.selfplay.RunMonitor.Report;
import sternhalma.selfplay.RunMonitor.Row;

/** The monitor: lines, the report's arithmetic with injected numbers, and the conversion of the loop's timing. */
class RunMonitorTest {

    private static final long S = 1_000_000_000L;

    private static Map<String, String> run(long wallSeconds, long cpuSeconds, long steps, long batch) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("event", "run");
        m.put("wall_ns", Long.toString(wallSeconds * S));
        m.put("cpu_ns", Long.toString(cpuSeconds * S));
        m.put("steps", Long.toString(steps));
        m.put("batch", Long.toString(batch));
        return m;
    }

    private static Map<String, String> cost(String category, String kind, String where, long count, double seconds) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("event", "cost");
        m.put("category", category);
        m.put("kind", kind);
        m.put("where", where);
        m.put("count", Long.toString(count));
        m.put("wall_ns", Long.toString(Math.round(seconds * S)));
        m.put("cpu_ns", Long.toString(Math.round(seconds * S / 2)));
        return m;
    }

    @Test
    void ceil125RoundsUpToOneTwoFiveTimesAPowerOfTen() {
        assertEquals(0, RunMonitor.ceil125(0));
        assertEquals(0, RunMonitor.ceil125(-3));
        assertEquals(1, RunMonitor.ceil125(1));
        assertEquals(2, RunMonitor.ceil125(1.01));
        assertEquals(2, RunMonitor.ceil125(2));
        assertEquals(5, RunMonitor.ceil125(2.0001));
        assertEquals(5, RunMonitor.ceil125(5));
        assertEquals(10, RunMonitor.ceil125(5.0001));
        assertEquals(50, RunMonitor.ceil125(50));
        assertEquals(500, RunMonitor.ceil125(375));
        assertEquals(1000, RunMonitor.ceil125(800));
        assertEquals(0.5, RunMonitor.ceil125(0.5), 1e-12);
        assertEquals(0.002, RunMonitor.ceil125(0.0011), 1e-12);
        // the floating-point noise of 0.5 / 0.1 and 0.03 / 0.01 must not push to the next step
        assertEquals(0.5, RunMonitor.ceil125(0.05 / 0.1), 1e-12);
    }

    @Test
    void reportComputesSharesAgainstBudgetsAndProposesIntervals() {
        List<Map<String, String>> events = new ArrayList<>();
        events.add(run(1000, 900, 2000, 64));
        events.add(cost("train", "", "loop", 2000, 900));
        events.add(cost("checkpoint", "", "loop", 4, 2.0));
        events.add(cost("progress", "", "loop", 100, 0.5));
        events.add(cost("progress", "monitor", "loop", 1, 0.2));
        events.add(cost("strength", "proxy", "loop", 4, 30));
        events.add(cost("strength", "elo", "loop", 1, 40));
        Report r = RunMonitor.report(events, 0);

        assertEquals(1, r.processes());
        assertEquals(2000, r.steps());
        assertEquals(2000.0 * 64 / 1000, r.samplesPerSecond(), 1e-9);

        Row checkpoint = r.row(Category.CHECKPOINT, "");
        assertEquals(0.002, checkpoint.share(), 1e-12);
        assertEquals(0.5 * S, checkpoint.costPerEventNanos(), 1);
        assertTrue(checkpoint.withinBudget());
        // 0.5 s per checkpoint, budget 1 %: one every 50 s, which is 100 steps at 2 steps/s
        assertEquals(50, checkpoint.proposedSeconds(), 1e-9);
        assertEquals(100, checkpoint.proposedSteps());
        assertEquals(250, checkpoint.meanIntervalSeconds(), 1e-9); // 1000 s / 4

        Row progress = r.row(Category.PROGRESS, "");
        assertEquals(0.0005, progress.share(), 1e-12);
        assertTrue(progress.withinBudget());
        assertEquals(0.005 * S, progress.costPerEventNanos(), 1);
        assertEquals(5, progress.proposedSeconds(), 1e-9); // 5 ms per line / 0.1 %

        Row proxy = r.row(Category.STRENGTH, "proxy");
        assertEquals(0.03, proxy.share(), 1e-12);
        assertFalse(proxy.withinBudget(), "3 % is over the 2 % budget");
        assertEquals(500, proxy.proposedSeconds(), 1e-9); // 7.5 s / 2 % = 375 s, rounded up

        Row elo = r.row(Category.STRENGTH, "elo");
        assertEquals(0.04, elo.share(), 1e-12);
        assertTrue(elo.withinBudget());
        assertEquals(1000, elo.proposedSeconds(), 1e-9); // 40 s / 5 % = 800 s

        Row monitor = r.row(Category.PROGRESS, "monitor");
        assertTrue(Double.isNaN(monitor.budget()), "no budget for the monitor's own writes");
        assertEquals(0, monitor.proposedSeconds());
        assertEquals(0.9, r.row(Category.TRAIN, "").share(), 1e-12);
        assertEquals(1.0 / 900, checkpoint.cpuShare(), 1e-12); // 1 s of CPU in a loop of 900 CPU seconds
    }

    @Test
    void severalProcessesAddUpAndASeparateProcessIsItsOwnRow() {
        List<Map<String, String>> events = new ArrayList<>();
        events.add(run(100, 90, 300, 64));
        events.add(run(300, 270, 900, 64));
        events.add(cost("checkpoint", "", "loop", 2, 1.0));
        events.add(cost("checkpoint", "", "loop", 6, 3.0));
        events.add(cost("strength", "elo", "separate", 1, 20));
        Report r = RunMonitor.report(events, 3);
        assertEquals(2, r.processes());
        assertEquals(400 * S, r.loopWallNanos());
        assertEquals(1200, r.steps());
        assertEquals(3, r.unreadableLines());
        Row checkpoint = r.row(Category.CHECKPOINT, "");
        assertEquals(8, checkpoint.count());
        assertEquals(4.0 / 400, checkpoint.share(), 1e-12);
        assertEquals(0.5, checkpoint.costPerEventNanos() / S, 1e-12);
        long separate = r.rows().stream().filter(Row::separateProcess).count();
        assertEquals(1, separate);
        Row elo = r.rows().stream().filter(Row::separateProcess).findFirst().orElseThrow();
        assertEquals(20.0 / 400, elo.share(), 1e-12);
        assertTrue(elo.label().contains("separate"));
    }

    @Test
    void anEventWithoutCostProposesNoIntervalAndWithoutALoopThereIsNoShare() {
        Report none = RunMonitor.report(List.of(cost("checkpoint", "", "loop", 3, 0)), 0);
        Row row = none.row(Category.CHECKPOINT, "");
        assertEquals(0, row.share());
        assertEquals(0, row.proposedSeconds());
        assertEquals(0, none.processes());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        RunMonitor.print(none, new PrintStream(bytes, true, StandardCharsets.UTF_8));
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("no finished training loop"));
    }

    @Test
    void linesRoundTripAndATornLineIsRejected() {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("event", "x");
        f.put("path", "a \"quoted\" \\ name\nwith\ttab");
        f.put("n", 42L);
        f.put("d", 0.125);
        f.put("nan", Double.NaN);
        f.put("flag", true);
        String line = RunMonitor.json(f);
        assertFalse(line.contains("\n"), "one line");
        Map<String, String> back = RunMonitor.parse(line);
        assertNotNull(back);
        assertEquals("a \"quoted\" \\ name\nwith\ttab", back.get("path"));
        assertEquals("42", back.get("n"));
        assertEquals("0.125", back.get("d"));
        assertEquals("null", back.get("nan"));
        assertEquals("true", back.get("flag"));
        assertNull(RunMonitor.parse(line.substring(0, line.length() - 5)), "torn line");
        assertNull(RunMonitor.parse("not json"));
        assertNull(RunMonitor.parse("{\"a\":1 garbage \"b\":2}"));
    }

    @Test
    void measureUsesTheRunClockAndWritesOneCostLine(@TempDir Path dir) throws IOException {
        AtomicLong wall = new AtomicLong(1_000);
        AtomicLong cpu = new AtomicLong(0);
        RunMonitor monitor = new RunMonitor(dir.resolve("metrics.jsonl"), new TrainingRun.Clock(wall::get, cpu::get));
        TrainingRun.Spent spent = monitor.measure(Category.STRENGTH, RunMonitor.PROXY, false, 500, () -> {
            wall.addAndGet(7_000);
            cpu.addAndGet(3_000);
        });
        assertEquals(7_000, spent.wallNanos());
        assertEquals(3_000, spent.cpuNanos());
        List<String> lines = Files.readAllLines(dir.resolve("metrics.jsonl"));
        assertEquals(1, lines.size());
        Map<String, String> e = RunMonitor.parse(lines.get(0));
        assertEquals("cost", e.get("event"));
        assertEquals("strength", e.get("category"));
        assertEquals("proxy", e.get("kind"));
        assertEquals("loop", e.get("where"));
        assertEquals("500", e.get("step"));
        assertEquals("1", e.get("count"));
        assertEquals("7000", e.get("wall_ns"));
        assertEquals("3000", e.get("cpu_ns"));
    }

    @Test
    void recordRunTurnsTheLoopsTimingIntoLinesAndSubtractsTheStrengthFromTheObserverTime(@TempDir Path dir)
            throws IOException {
        RunMonitor monitor = new RunMonitor(dir.resolve("metrics.jsonl"), TrainingRun.Clock.system());
        TrainingRun.Timing timing = new TrainingRun.Timing(500, 5, 1000, 100 * S, 90 * S,
                new TrainingRun.Spent(90 * S, 80 * S), new TrainingRun.Spent(1 * S, S / 2),
                new TrainingRun.Spent(6 * S, 5 * S), new TrainingRun.Spent(S / 10, S / 20));
        // 6 s of observer time, of which 5.5 s are strength estimates: 0.5 s are the monitor's own writes
        monitor.recordRun(timing, 64, new TrainingRun.Spent(S * 11 / 2, S * 9 / 2), false, 10);
        Report r = RunMonitor.report(dir);
        assertEquals(1, r.processes());
        assertEquals(100 * S, r.loopWallNanos());
        assertEquals(500, r.steps());
        assertEquals(0.9, r.row(Category.TRAIN, "").share(), 1e-12);
        Row checkpoint = r.row(Category.CHECKPOINT, "");
        assertEquals(5, checkpoint.count());
        assertEquals(0.01, checkpoint.share(), 1e-12);
        Row progress = r.row(Category.PROGRESS, "");
        assertEquals(10, progress.count());
        assertEquals(0.001, progress.share(), 1e-12);
        Row own = r.row(Category.PROGRESS, "monitor");
        assertEquals(0.005, own.share(), 1e-12);
        assertEquals(0.5 / 90, own.cpuShare(), 1e-12);
        // an observer that took less than the strength it claims cannot give negative time
        Path other = Files.createDirectory(dir.resolve("again"));
        RunMonitor again = new RunMonitor(other.resolve("metrics.jsonl"), TrainingRun.Clock.system());
        again.recordRun(timing, 64, new TrainingRun.Spent(10 * S, 10 * S), true, 10);
        assertEquals(0, RunMonitor.report(other).row(Category.PROGRESS, "monitor").wallNanos());
    }

    @Test
    void concurrentAppendsNeverMixLines(@TempDir Path dir) throws Exception {
        RunMonitor monitor = new RunMonitor(dir.resolve("metrics.jsonl"), TrainingRun.Clock.system());
        Path file = dir.resolve("metrics.jsonl");
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            int id = t;
            threads[t] = new Thread(() -> {
                try {
                    for (int i = 0; i < 100; i++) {
                        if (id % 2 == 0) {
                            monitor.cost(Category.CHECKPOINT, "", false, i, 1, 10, 5);
                        } else {
                            // a second writer without the monitor's lock, like the separate strength process
                            RunMonitor.append(file, RunMonitor.json(Map.of("event", "other", "id", id, "i", i)));
                        }
                    }
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
            threads[t].start();
        }
        for (Thread t : threads) {
            t.join();
        }
        List<String> lines = Files.readAllLines(file);
        assertEquals(400, lines.size());
        for (String line : lines) {
            assertNotNull(RunMonitor.parse(line), line);
        }
    }

    @Test
    void aMissingMetricsFileIsAnError(@TempDir Path dir) {
        assertThrows(IOException.class, () -> RunMonitor.report(dir));
    }
}
