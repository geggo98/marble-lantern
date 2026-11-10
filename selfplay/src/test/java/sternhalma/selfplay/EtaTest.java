package sternhalma.selfplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sternhalma.selfplay.Eta.Estimate;

/** The ETA of a generation and of the run: arithmetic with injected costs, the lower bound, and the history file. */
class EtaTest {

    private static final Map<String, Double> UNITS = Map.of("selfplay", 300.0, "train", 400.0, "gate", 50.0,
            "baseline", 50.0, "strength", 150.0);

    private static Eta measured() {
        Eta eta = new Eta();
        eta.observe("selfplay", 300, 150); // 0.5 s per game
        eta.observe("train", 400, 100); // 0.25 s per step
        eta.observe("gate", 50, 100); // 2 s per pair
        eta.observe("baseline", 50, 50); // 1 s per pair
        eta.observe("strength", 150, 75); // 0.5 s per pair
        return eta;
    }

    @Test
    void theRemainingTimeIsTheRunningPhaseAtItsLiveRatePlusTheLaterPhasesPlusTheFurtherGenerations() {
        Eta eta = measured();
        // 100 games left at 4 games/s: 25 s; then train 100 s, gate 100 s, baseline 50 s, strength 75 s
        Estimate e = eta.estimate("selfplay", 100, 4, UNITS, 2);
        assertEquals(25 + 100 + 100 + 50 + 75, e.generationSeconds(), 1e-9);
        // a whole generation costs 150 + 100 + 100 + 50 + 75 = 475 s, two more of them
        assertEquals(350 + 2 * 475, e.runSeconds(), 1e-9);
        assertTrue(e.exact());
        // in the gate: 20 pairs left at 0.5 pairs/s = 40 s, then baseline and strength
        Estimate g = eta.estimate("gate", 20, 0.5, UNITS, 0);
        assertEquals(40 + 50 + 75, g.generationSeconds(), 1e-9);
        assertEquals(g.generationSeconds(), g.runSeconds(), 1e-9, "no generation follows");
        // in the last phase nothing follows it in the generation
        assertEquals(10, eta.estimate("strength", 20, 2, UNITS, 0).generationSeconds(), 1e-9);
    }

    @Test
    void baselineAndStrengthOfARunningGenerationThatMayBeRejectedAreLeftOutAndSaid() {
        Eta eta = measured();
        Map<String, Double> thisGeneration = new java.util.HashMap<>(UNITS);
        thisGeneration.remove("baseline");
        thisGeneration.remove("strength");
        // 100 games left at 4 games/s: 25 s, train 100 s, gate 100 s; baseline (50 s) and strength (75 s) wait for the gate
        Estimate e = eta.estimate("selfplay", 100, 4, thisGeneration, UNITS, 2, true);
        assertEquals(25 + 100 + 100, e.generationSeconds(), 1e-9);
        // the further generations are counted whole, as if every candidate were promoted: 2 x 475 s
        assertEquals(225 + 2 * 475, e.runSeconds(), 1e-9);
        assertFalse(e.exact(), "a lower bound for this generation");
        assertTrue(e.text(2).contains("gen ETA 3m45s+, run ETA ") && e.text(2).endsWith(Eta.PENDING_NOTE), e.text(2));
        assertTrue(Eta.PENDING_NOTE.contains("above 0.5"));
        // once the gate score is above one half they count: the estimate of before
        Estimate due = eta.estimate("gate", 20, 0.5, UNITS, UNITS, 0, false);
        assertEquals(40 + 50 + 75, due.generationSeconds(), 1e-9);
        assertTrue(due.exact());
        assertFalse(due.text(0).contains("above 0.5"), due.text(0));
        assertEquals(due, eta.estimate("gate", 20, 0.5, UNITS, 0));
    }

    @Test
    void withoutALiveRateTheHistoryCostIsUsed() {
        Estimate e = measured().estimate("train", 40, 0, UNITS, 0);
        assertEquals(40 * 0.25 + 100 + 50 + 75, e.generationSeconds(), 1e-9);
    }

    @Test
    void aPhaseThatWasNeverMeasuredMakesTheEstimateALowerBound() {
        Eta eta = new Eta();
        eta.observe("selfplay", 100, 100);
        Estimate e = eta.estimate("selfplay", 50, 1, UNITS, 1);
        assertEquals(50, e.generationSeconds(), 1e-9, "only what is known: the later phases are 0");
        assertFalse(e.exact());
        assertTrue(e.text(1).contains("+"), e.text(1));
        // the running phase of the first generation prices the further generations at its live rate
        assertEquals(50 + 300, e.runSeconds(), 1e-9);
    }

    @Test
    void theCostOfAPhaseIsTheMeanOfItsLatestObservations() {
        Eta eta = new Eta();
        eta.observe("gate", 10, 100); // 10 s per pair: forgotten after three newer observations
        eta.observe("gate", 10, 10);
        eta.observe("gate", 10, 10);
        eta.observe("gate", 10, 40);
        assertEquals(60.0 / 30, eta.secondsPerUnit("gate"), 1e-12);
        assertEquals(10, eta.lastUnits("gate"), 0);
        eta.observe("gate", 0, 5); // nothing done: no information
        eta.observe("gate", -1, 5);
        assertEquals(60.0 / 30, eta.secondsPerUnit("gate"), 1e-12);
        assertTrue(Double.isNaN(eta.secondsPerUnit("train")));
    }

    @Test
    void theHistoryComesFromThePhaseLinesOfTheMetricsFileAndIgnoresTheRest(@TempDir Path dir) throws Exception {
        Path metrics = dir.resolve("metrics.jsonl");
        Files.writeString(metrics, """
                {"event":"phase","t":1,"gen":1,"phase":"selfplay","units":100,"wall_ns":50000000000,"complete":true}
                {"event":"cost","t":2,"category":"selfplay","kind":"","where":"loop","step":1,"count":100,"wall_ns":50000000000,"cpu_ns":1}
                {"event":"phase","t":3,"gen":1,"phase":"train","units":20,"wall_ns":10000000000,"complete":false}
                {"event":"phase","t":4,"gen":1,"phase":"gate","units":10
                not json at all
                {"event":"phase","t":5,"gen":1,"phase":"nonsense","units":10,"wall_ns":1}
                """);
        Eta eta = Eta.fromMetrics(metrics);
        assertEquals(0.5, eta.secondsPerUnit("selfplay"), 1e-12);
        assertEquals(0.5, eta.secondsPerUnit("train"), 1e-12);
        assertTrue(Double.isNaN(eta.secondsPerUnit("gate")), "a torn line is no history");
        assertTrue(Double.isNaN(Eta.fromMetrics(dir.resolve("missing.jsonl")).secondsPerUnit("selfplay")));
    }

    @Test
    void theEstimateTextNamesTheGenerationsToCome() {
        Estimate e = new Estimate(125, 3725, true);
        assertEquals("gen ETA 2m05s, run ETA 1h02m (3 more generations)", e.text(3));
        assertEquals("gen ETA 2m05s+, run ETA 1h02m+ (1 more generation)", new Estimate(125, 3725, false).text(1));
    }
}
