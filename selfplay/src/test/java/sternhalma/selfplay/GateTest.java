package sternhalma.selfplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sternhalma.search.Elo;
import sternhalma.selfplay.Gate.Decision;
import sternhalma.selfplay.MctsMatch.PairLine;

/**
 * The promotion rule with injected match results, and the shards of a match: a pair is a function of the match, an
 * aborted match resumes to the same pairs, a shard of another match is not reused.
 */
class GateTest {

    private static double[] repeat(double[] pattern, int times) {
        double[] out = new double[pattern.length * times];
        for (int i = 0; i < out.length; i++) {
            out[i] = pattern[i % pattern.length];
        }
        return out;
    }

    private static final double[] NO_CLAUSE = new double[0];

    // ---- the promotion inequality ----

    /** {@code wins} pairs of 1, {@code draws} of 0.5 and {@code losses} of 0, interleaved so that no order is assumed. */
    private static double[] pairs(int wins, int draws, int losses) {
        List<Double> l = new ArrayList<>();
        for (int i = 0; i < Math.max(wins, Math.max(draws, losses)); i++) {
            if (i < wins) {
                l.add(1.0);
            }
            if (i < draws) {
                l.add(0.5);
            }
            if (i < losses) {
                l.add(0.0);
            }
        }
        return l.stream().mapToDouble(Double::doubleValue).toArray();
    }

    private static final double Z90 = 1.28;

    @Test
    void theGateNeedsTheScoreToBeSignificantlyAboveOneHalf() {
        double[] strong = pairs(33, 99, 18); // 150 pairs, score 0.55, se about 0.024
        Decision s = Gate.decide(strong, NO_CLAUSE, NO_CLAUSE, 2, Z90);
        assertEquals(0.55, s.score(), 1e-12);
        assertEquals(0.0235, s.scoreStandardError(), 5e-4);
        assertEquals((s.score() - 0.5) / s.scoreStandardError(), s.z(), 1e-12);
        assertEquals(2.1, s.z(), 0.1);
        assertTrue(s.promoted());
        assertEquals(Z90, s.promoteZ());

        double[] weak = pairs(31, 97, 22); // score 0.53, z about 1.25
        Decision w = Gate.decide(weak, NO_CLAUSE, NO_CLAUSE, 2, Z90);
        assertEquals(0.53, w.score(), 1e-12);
        assertEquals(1.25, w.z(), 0.05);
        assertFalse(w.promoted(), "above 0.5, but within 1.28 standard errors of it");
        assertTrue(Gate.decide(weak, NO_CLAUSE, NO_CLAUSE, 2, 0).promoted(), "the old rule promotes it");
        assertTrue(Gate.decide(weak, NO_CLAUSE, NO_CLAUSE, 2, 1.2).promoted());
    }

    @Test
    void theBoundaryIsExactAndStrict() {
        double[] gate = pairs(31, 98, 21); // 0.5333
        Decision d = Gate.decide(gate, NO_CLAUSE, NO_CLAUSE, 2, 0);
        double boundary = (d.score() - 0.5) / d.scoreStandardError();
        assertTrue(Gate.decide(gate, NO_CLAUSE, NO_CLAUSE, 2, boundary - 1e-9).promoted());
        assertFalse(Gate.decide(gate, NO_CLAUSE, NO_CLAUSE, 2, boundary + 1e-9).promoted());
        assertTrue(boundary > 1.28 && boundary < 1.5, "this match promotes at 1.28 by a small margin: " + boundary);
        assertTrue(Gate.decide(gate, NO_CLAUSE, NO_CLAUSE, 2, Z90).promoted());
    }

    @Test
    void zeroIsTheOldRuleScoreAboveOneHalf() {
        double[][] gates = {pairs(33, 99, 18), pairs(31, 97, 22), pairs(20, 100, 20), pairs(18, 99, 33), pairs(1, 0, 1),
            pairs(0, 0, 3), repeat(new double[] {1, 0}, 15)};
        for (double[] g : gates) {
            assertEquals(Elo.mean(g) > 0.5, Gate.decide(g, NO_CLAUSE, NO_CLAUSE, 2, 0).promoted(), Arrays.toString(g));
        }
    }

    @Test
    void drawsAndPairsThatAreAllEqualHaveNoSpread() {
        double[] draws = pairs(0, 40, 0);
        for (double z : new double[] {0, Z90, 3}) {
            Decision d = Gate.decide(draws, NO_CLAUSE, NO_CLAUSE, 2, z);
            assertFalse(d.promoted(), "all draws: exactly 0.5 never promotes");
            assertEquals(0, d.z());
        }
        double[] wins = pairs(40, 0, 0);
        Decision d = Gate.decide(wins, NO_CLAUSE, NO_CLAUSE, 2, Z90);
        assertEquals(0, d.scoreStandardError());
        assertEquals(Double.POSITIVE_INFINITY, d.z());
        assertTrue(d.promoted(), "a standard error of 0 and a score above 0.5");
        Decision l = Gate.decide(pairs(0, 0, 40), NO_CLAUSE, NO_CLAUSE, 2, Z90);
        assertEquals(Double.NEGATIVE_INFINITY, l.z());
        assertFalse(l.promoted());
        double[] mostlyDraws = pairs(3, 117, 0); // 0.5125, few decisive pairs
        assertTrue(Gate.decide(mostlyDraws, NO_CLAUSE, NO_CLAUSE, 2, 0).promoted());
        assertEquals(Gate.decide(mostlyDraws, NO_CLAUSE, NO_CLAUSE, 2, 0).z() > Z90,
                Gate.decide(mostlyDraws, NO_CLAUSE, NO_CLAUSE, 2, Z90).promoted());
    }

    @Test
    void theBaselineClauseStillVetoesASignificantGateAndTheLogLineNamesZ() {
        double[] gate = pairs(33, 99, 18);
        double[] best = repeat(new double[] {1, 0.5, 0.5, 0}, 10);
        double[] worse = repeat(new double[] {0, 0.5, 0.5, 0}, 10);
        Decision vetoed = Gate.decide(gate, worse, best, 2, Z90);
        assertFalse(vetoed.promoted());
        assertFalse(vetoed.baselineOk());
        assertTrue(Gate.decide(gate, best, best, 2, Z90).promoted());
        String text = Gate.decide(gate, best, best, 2, Z90).describe();
        assertTrue(text.startsWith("promoted: score 0.550 +- 0.024 (95% interval"), text);
        assertTrue(text.contains("z 2.1") && text.contains("(needs above 1.28)"), text);
        assertThrows(IllegalArgumentException.class, () -> Gate.decide(gate, NO_CLAUSE, NO_CLAUSE, 2, -0.1));
    }

    @Test
    void aScoreAboveOneHalfPromotesAndOneHalfExactlyDoesNot() {
        // 30 pairs: 18 of 1.0, 12 of 0.0 -> 0.6;  15 of 1.0 and 15 of 0.0 -> exactly 0.5
        double[] above = repeat(new double[] {1, 1, 1, 0, 0}, 6);
        double[] exactly = repeat(new double[] {1, 0}, 15);
        assertEquals(0.6, Elo.mean(above), 1e-12);
        assertTrue(Gate.decide(above, NO_CLAUSE, NO_CLAUSE, 2, 0).promoted());
        assertEquals(0.5, Elo.mean(exactly), 1e-12);
        assertFalse(Gate.decide(exactly, NO_CLAUSE, NO_CLAUSE, 2, 0).promoted(), "0.5 is not more than 0.5");
        assertFalse(Gate.decide(repeat(new double[] {0, 0, 1, 0.5}, 5), NO_CLAUSE, NO_CLAUSE, 2, 0).promoted(), "0.375");
    }

    @Test
    void theIntervalAndTheStatisticsAreRecorded() {
        double[] gate = repeat(new double[] {1, 0.5, 0.5, 0}, 10); // mean 0.5
        double[] above = repeat(new double[] {1, 1, 0.5, 0.5, 0.5, 0}, 10);
        Decision d = Gate.decide(above, NO_CLAUSE, NO_CLAUSE, 2, 0);
        assertEquals(60, d.pairs());
        assertEquals(Elo.mean(above), d.score(), 1e-12);
        assertEquals(Elo.standardError(above), d.scoreStandardError(), 1e-12);
        assertEquals(d.score() - 1.96 * d.scoreStandardError(), d.ci95Low(), 1e-12);
        assertEquals(d.score() + 1.96 * d.scoreStandardError(), d.ci95High(), 1e-12);
        assertTrue(d.ci95Low() < d.score() && d.score() < d.ci95High());
        assertEquals(0, d.baselinePairs());
        assertTrue(d.baselineOk(), "no clause: nothing to violate");
        assertEquals(0.5, Elo.mean(gate), 1e-12);
    }

    @Test
    void theIntervalStaysInsideTheScoresThatExist() {
        // review finding: 4 pairs of 1, 1, 1, 0.5 printed "95% interval 0.653 to 1.033"
        Decision high = Gate.decide(new double[] {1, 1, 1, 0.5}, NO_CLAUSE, NO_CLAUSE, 2, 0);
        assertTrue(high.score() + 1.96 * high.scoreStandardError() > 1, "the raw interval leaves [0, 1]");
        assertEquals(1.0, high.ci95High(), 0);
        assertTrue(high.ci95Low() > 0.5 && high.ci95Low() < high.score());
        assertTrue(high.describe().contains("to 1.000)"), high.describe());
        Decision low = Gate.decide(new double[] {0, 0, 0, 0.5}, NO_CLAUSE, NO_CLAUSE, 2, 0);
        assertEquals(0.0, low.ci95Low(), 0);
        assertTrue(low.describe().contains("interval 0.000 to "), low.describe());
        // inside [0, 1] the interval is the plain one
        Decision middle = Gate.decide(new double[] {1, 0, 0.5, 0.5}, NO_CLAUSE, NO_CLAUSE, 2, 0);
        assertEquals(middle.score() - 1.96 * middle.scoreStandardError(), middle.ci95Low(), 1e-12);
        assertEquals(middle.score() + 1.96 * middle.scoreStandardError(), middle.ci95High(), 1e-12);
    }

    @Test
    void theBaselineClauseRejectsARegressionOfMoreThanTwoStandardErrors() {
        double[] gate = repeat(new double[] {1, 1, 1, 0}, 10); // 0.75: clearly better than the best network
        // the best network scores 0.6 against the rung, the candidate 0.2 lower, pair by pair
        double[] best = repeat(new double[] {1, 0.5, 0.5, 0.5, 0.5}, 10); // mean 0.6
        double[] worse = repeat(new double[] {0.5, 0.5, 0.5, 0.0, 0.0}, 10); // mean 0.3, differences -0.5 -0 -0 -0.5 -0.5
        Decision d = Gate.decide(gate, worse, best, 2, 0);
        assertTrue(d.score() > 0.5);
        assertEquals(50, d.baselinePairs());
        assertEquals(Elo.mean(worse), d.baselineCandidate(), 1e-12);
        assertEquals(Elo.mean(best), d.baselineBest(), 1e-12);
        assertEquals(-0.3, d.baselineDiff(), 1e-12);
        assertFalse(d.baselineOk());
        assertFalse(d.promoted(), "better than its parent, but worse against the rung by more than two standard errors");
        // the same candidate, the same gate, a clause that is not violated
        Decision same = Gate.decide(gate, best, best, 2, 0);
        assertEquals(0, same.baselineDiff(), 1e-12);
        assertTrue(same.baselineOk());
        assertTrue(same.promoted());
        // better against the rung than the best network
        assertTrue(Gate.decide(gate, best, worse, 2, 0).promoted());
    }

    @Test
    void theClauseBoundaryIsTwoStandardErrorsOfThePairedDifferencesAndInclusive() {
        double[] gate = repeat(new double[] {1}, 10);
        double[] best = new double[10];
        Arrays.fill(best, 0.5);
        // differences d: the candidate scores 0.5 + d_i; mean and standard error of d are known exactly
        double[] diff = {0.1, -0.2, 0.0, -0.2, -0.1, 0.0, -0.3, 0.1, -0.1, -0.1};
        double[] cand = new double[10];
        for (int i = 0; i < 10; i++) {
            cand[i] = best[i] + diff[i];
        }
        double mean = Elo.mean(diff);
        double se = Elo.standardError(diff);
        assertTrue(mean < 0 && mean > -2 * se, "the case is a regression inside two standard errors: " + mean + ", " + se);
        assertTrue(Gate.decide(gate, cand, best, 2, 0).promoted());
        assertFalse(Gate.decide(gate, cand, best, 0, 0).promoted(), "zero standard errors allowed: any drop rejects");
        // the sigma at which the clause turns over is |mean| / se
        double turn = -mean / se;
        assertTrue(Gate.decide(gate, cand, best, turn + 1e-9, 0).promoted());
        assertFalse(Gate.decide(gate, cand, best, turn - 1e-6, 0).promoted());
    }

    @Test
    void theClauseUsesPairedDifferencesNotTwoIndependentStandardErrors() {
        double[] gate = repeat(new double[] {1}, 10);
        // both networks score between 0 and 1 widely, but the candidate is exactly 0.05 below on every pair
        double[] best = {0.0, 1.0, 0.5, 0.0, 1.0, 0.5, 0.0, 1.0, 0.5, 1.0};
        double[] cand = new double[10];
        for (int i = 0; i < 10; i++) {
            cand[i] = best[i] - 0.05;
        }
        Decision d = Gate.decide(gate, cand, best, 2, 0);
        assertEquals(-0.05, d.baselineDiff(), 1e-12);
        assertEquals(0.0, d.baselineDiffStandardError(), 1e-12);
        assertFalse(d.promoted(), "a constant drop has standard error 0, so any drop is a regression");
        // an independent comparison would have found it inside the noise: the standard error of each mean is large
        assertTrue(2 * Elo.standardError(best) > 0.05);
    }

    @Test
    void aRejectedGateIsRejectedWhateverTheBaselineSays() {
        double[] gate = repeat(new double[] {0, 0.5, 1, 0}, 10); // 0.375
        double[] best = repeat(new double[] {0, 0.5}, 25);
        double[] better = repeat(new double[] {1, 1}, 25);
        assertFalse(Gate.decide(gate, better, best, 2, 0).promoted());
    }

    @Test
    void badInputIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> Gate.decide(new double[] {1}, NO_CLAUSE, NO_CLAUSE, 2, 0));
        assertThrows(IllegalArgumentException.class,
                () -> Gate.decide(new double[] {1, 0}, new double[] {1, 0}, new double[] {1}, 2, 0));
        assertThrows(IllegalArgumentException.class,
                () -> Gate.decide(new double[] {1, 0}, new double[] {1}, new double[] {1}, 2, 0));
    }

    // ---- the shards of a match ----

    private static PairLine line(int pair, double score) {
        return new PairLine(pair, score, score == 1 ? 2 : 0, score == 0.5 ? 2 : 0, score == 0 ? 2 : 0, 80, 1000 + pair, 40,
                400, 3, 500, 40, 4000);
    }

    private static Gate.PairJob job(Path dir, String identity, int pairs, int shard, AtomicInteger played, Loop.StopHook hook) {
        return new Gate.PairJob(dir, "vs-test", "test match", identity, pairs, shard, 2, new java.io.PrintStream(
                java.io.OutputStream.nullOutputStream()), () -> pair -> {
                    played.incrementAndGet();
                    return line(pair, pair % 3 / 2.0);
                }, hook, "gate");
    }

    @Test
    void aMatchIsWrittenInShardsAndRestartedFromThem(@TempDir Path dir) throws Exception {
        AtomicInteger played = new AtomicInteger();
        // stop after 5 pairs: the shards whose pairs are all done are written, the others are discarded
        Gate.PairJob first = job(dir, "m1", 25, 10, played, (p, n) -> n >= 5);
        assertEquals(Job.EXIT_ABORTED, first.run());
        List<Path> after = shardNames(dir);
        assertTrue(after.size() <= 1, "at most the shard that was complete: " + after);
        assertNull(first.results(), "an aborted match has no complete result");

        Gate.PairJob second = job(dir, "m1", 25, 10, played, (p, n) -> false);
        assertEquals(Job.EXIT_OK, second.run());
        assertEquals(3, shardNames(dir).size(), "25 pairs in shards of 10: 10, 10, 5");
        List<PairLine> all = second.results();
        assertEquals(25, all.size());
        for (int i = 0; i < 25; i++) {
            assertEquals(i, all.get(i).pair());
            assertEquals(i % 3 / 2.0, all.get(i).score(), 0);
        }
        assertEquals(25 - 10 * after.size(), second.doneItems, "the second run did the pairs that the first one had not finished");

        // a third run does nothing
        int before = played.get();
        Gate.PairJob third = job(dir, "m1", 25, 10, played, (p, n) -> false);
        assertEquals(Job.EXIT_OK, third.run());
        assertEquals(0, third.doneItems);
        assertEquals(before, played.get());
    }

    @Test
    void aShardOfAnotherMatchIsNotReused(@TempDir Path dir) throws Exception {
        AtomicInteger played = new AtomicInteger();
        Gate.PairJob a = job(dir, "net=AAA", 10, 10, played, (p, n) -> false);
        assertEquals(Job.EXIT_OK, a.run());
        assertEquals(10, played.get());
        Gate.PairJob b = job(dir, "net=BBB", 10, 10, played, (p, n) -> false);
        assertEquals(Job.EXIT_OK, b.run());
        assertEquals(20, played.get(), "the shard of match A names another match: redone");
        try (var files = Files.list(dir)) {
            assertEquals(1, files.filter(f -> f.getFileName().toString().endsWith(".corrupt")).count());
        }
        assertEquals(10, b.results().size());
    }

    @Test
    void aTornOrShortShardIsRedone(@TempDir Path dir) throws Exception {
        AtomicInteger played = new AtomicInteger();
        assertEquals(Job.EXIT_OK, job(dir, "m", 10, 10, played, (p, n) -> false).run());
        Path shard = dir.resolve("vs-test-000.jsonl");
        List<String> lines = Files.readAllLines(shard);
        Files.writeString(shard, String.join("\n", lines.subList(0, lines.size() - 1)) + "\n"); // a pair missing
        Gate.PairJob redo = job(dir, "m", 10, 10, played, (p, n) -> false);
        assertEquals(Job.EXIT_OK, redo.run());
        assertEquals(20, played.get());
        assertEquals(10, redo.results().size());
        assertEquals(lines, Files.readAllLines(shard), "the redone shard equals the first one");
    }

    private static List<Path> shardNames(Path dir) throws IOException {
        List<Path> out = new ArrayList<>();
        try (var s = Files.list(dir)) {
            s.filter(f -> f.getFileName().toString().endsWith(".jsonl")).sorted().forEach(out::add);
        }
        return out;
    }
}
