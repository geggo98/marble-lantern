package sternhalma.selfplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import sternhalma.selfplay.RunMonitor.Category;
import sternhalma.selfplay.RunMonitor.Report;
import sternhalma.selfplay.RunMonitor.Row;

/** The monitor's report for the training loop: generation lines, the shares of the six categories, the Elo frequency. */
class RunMonitorLoopTest {

    private static final long S = 1_000_000_000L;

    private static Map<String, String> event(String name, String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("event", name);
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Map<String, String> cost(String category, String kind, long count, double seconds) {
        return event("cost", "category", category, "kind", kind, "where", "loop", "count", Long.toString(count), "wall_ns",
                Long.toString(Math.round(seconds * S)), "cpu_ns", Long.toString(Math.round(seconds * S / 2)));
    }

    /** A generation line: {@code full} marks a finished promoted generation after generation 0. */
    private static Map<String, String> gen(int g, double seconds, boolean done, boolean full) {
        return event("gen", "gen", Integer.toString(g), "wall_ns", Long.toString(Math.round(seconds * S)), "done",
                done ? "1" : "0", "full", full ? "1" : "0");
    }

    /** Two generations of 100 s each: 40 s self-play, 20 s train, 15 s gate, 20 s Elo, 1 s checkpoints, 0.2 s progress. */
    private static List<Map<String, String>> loop() {
        List<Map<String, String>> events = new ArrayList<>();
        events.add(cost("selfplay", "", 600, 80));
        events.add(cost("train", "", 800, 40));
        events.add(cost("gate", "vs-best", 100, 20));
        events.add(cost("gate", "baseline", 50, 10));
        events.add(cost("strength", "elo", 2, 40));
        events.add(cost("strength", "proxy", 4, 2));
        events.add(cost("checkpoint", "", 4, 2));
        events.add(cost("progress", "", 20, 0.4));
        events.add(event("run", "wall_ns", Long.toString(60 * S), "cpu_ns", Long.toString(50 * S), "steps", "800", "batch", "256"));
        events.add(gen(1, 100, true, true));
        events.add(gen(2, 100, true, true));
        return events;
    }

    @Test
    void sharesOfTheLoopAreAgainstTheWholeGenerationWallAndTheTrainingBudgetsAgainstTheTrainingLoop() {
        Report r = RunMonitor.report(loop(), 0);
        assertEquals(200 * S, r.genWallNanos());
        assertEquals(2, r.generationsDone());
        assertEquals(60 * S, r.loopWallNanos());
        assertEquals(80.0 / 200, r.row(Category.SELFPLAY, "").share(), 1e-12);
        assertEquals(40.0 / 200, r.row(Category.TRAIN, "").share(), 1e-12);
        assertEquals(20.0 / 200, r.row(Category.GATE, "vs-best").share(), 1e-12);
        assertEquals(10.0 / 200, r.row(Category.GATE, "baseline").share(), 1e-12);
        Row elo = r.row(Category.STRENGTH, RunMonitor.ELO);
        assertEquals(40.0 / 200, elo.share(), 1e-12, "the Elo estimates against the whole loop");
        assertFalse(elo.withinBudget(), "20 % is over the 5 % budget");
        // 20 s per estimate / 5 % = 400 s of loop wall time between two estimates
        assertEquals(500, elo.proposedSeconds(), 1e-9);
        // the checkpoints, progress lines and proxies happen in the training loop and are budgeted against it
        assertEquals(2.0 / 60, r.row(Category.CHECKPOINT, "").share(), 1e-12);
        assertEquals(0.4 / 60, r.row(Category.PROGRESS, "").share(), 1e-12);
        assertEquals(2.0 / 60, r.row(Category.STRENGTH, RunMonitor.PROXY).share(), 1e-12);
        assertEquals(80 * S, r.categoryWallNanos(Category.SELFPLAY));
        assertEquals(30 * S, r.categoryWallNanos(Category.GATE), "the kinds of a category add up");
        assertEquals(42 * S, r.categoryWallNanos(Category.STRENGTH));
    }

    @Test
    void theReportPrintsTheSixSharesAndTheEloFrequencyInGenerations() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RunMonitor.print(RunMonitor.report(loop(), 0), new PrintStream(out, true, StandardCharsets.UTF_8));
        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("loop shares of 200.0 s wall in 2 completed generation(s) (promoted ones only; generation 0"
                + " and rejected generations are in the wall time but not in the count): train 20.0% (40.0 s), selfplay"
                + " 40.0% (80.0 s), checkpoint 1.0% (2.0 s), progress 0.2% (0.4 s), strength 21.0% (42.0 s), gate 15.0%"
                + " (30.0 s), other (loading, buffers, decisions)"), text);
        // an estimate costs 20 s of a generation of 100 s; the 5 % budget allows one in 4 generations
        assertTrue(text.contains("one Elo estimate costs 20.0 s, 20.0% of a generation of 100 s"), text);
        assertTrue(text.contains("one estimate every 4 generation(s) (--strength-every 4)"), text);
    }

    @Test
    void anAbortedGenerationCountsItsWallTimeButNotAsCompleted() {
        List<Map<String, String>> events = loop();
        events.add(gen(3, 30, false, false));
        Report r = RunMonitor.report(events, 0);
        assertEquals(230 * S, r.genWallNanos());
        assertEquals(2, r.generationsDone());
    }

    @Test
    void generationZeroAndRejectedGenerationsAreNoCompletedGenerationsInTheMean() {
        // review finding: generation 0 (a strength record only, 20 s) and a rejected generation (no baseline, no
        // strength record, 60 s) counted as generations and made the mean 70 s instead of 100 s: the Elo estimate
        // looked like 28.6 % of a generation instead of 20 %
        List<Map<String, String>> events = loop();
        events.add(gen(0, 20, true, false));
        events.add(gen(3, 60, true, false));
        Report r = RunMonitor.report(events, 0);
        assertEquals(280 * S, r.genWallNanos(), "their time is spent and counts in the shares");
        assertEquals(2, r.generationsDone());
        assertEquals(200 * S, r.countedGenWallNanos());
        assertEquals(80.0 / 280, r.row(Category.SELFPLAY, "").share(), 1e-12);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RunMonitor.print(r, new PrintStream(out, true, StandardCharsets.UTF_8));
        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("one Elo estimate costs 20.0 s, 20.0% of a generation of 100 s"), text);
        assertTrue(text.contains("one estimate every 4 generation(s) (--strength-every 4)"), text);
    }

    @Test
    void aGenerationThatWasResumedCountsOnceWithTheTimeOfAllItsSegments() {
        List<Map<String, String>> events = new ArrayList<>(loop().subList(0, 9));
        events.add(gen(1, 30, false, false)); // aborted
        events.add(gen(1, 70, true, true)); // finished by the next process
        events.add(gen(2, 100, true, true));
        Report r = RunMonitor.report(events, 0);
        assertEquals(2, r.generationsDone());
        assertEquals(200 * S, r.countedGenWallNanos());
        assertEquals(200 * S, r.genWallNanos());
    }

    @Test
    void generationLinesOfOlderRunsWithoutTheFullFieldCountAsBefore() {
        List<Map<String, String>> events = new ArrayList<>(loop().subList(0, 9));
        events.add(event("gen", "gen", "1", "wall_ns", Long.toString(100 * S), "done", "1"));
        events.add(event("gen", "gen", "2", "wall_ns", Long.toString(100 * S), "done", "1"));
        Report r = RunMonitor.report(events, 0);
        assertEquals(2, r.generationsDone());
        assertEquals(200 * S, r.countedGenWallNanos());
    }

    @Test
    void withoutGenerationLinesTheReportIsTheOneOfATrainingRun() {
        List<Map<String, String>> events = loop().subList(0, 9); // no gen lines
        Report r = RunMonitor.report(events, 0);
        assertEquals(0, r.genWallNanos());
        assertEquals(80.0 / 60, r.row(Category.SELFPLAY, "").share(), 1e-12, "everything against the training loop, as before");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RunMonitor.print(r, new PrintStream(out, true, StandardCharsets.UTF_8));
        assertFalse(out.toString(StandardCharsets.UTF_8).contains("loop shares of"));
    }
}
