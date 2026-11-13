package sternhalma.nn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests of {@link LearningRateSchedule} (docs/training.md, "Learning-rate schedule"). */
class LearningRateScheduleTest {

    private static final double LR = 1e-2;

    private static LearningRateSchedule schedule(List<LearningRateSchedule.Milestone> milestones) {
        return new LearningRateSchedule(LR, 200, 1000, 0.1, 100, milestones);
    }

    /** Generation 0: the first step already has a positive rate; the rate reaches lr at the end of the warm-up. */
    @Test
    void generationZeroWarmsUpLinearlyOverTwoHundredSteps() {
        LearningRateSchedule s = schedule(List.of());
        assertEquals(LR / 200, s.rate(0, 0), 1e-15);
        assertEquals(LR * 100 / 200, s.rate(0, 99), 1e-15);
        assertEquals(LR, s.rate(0, 199), 1e-15);
        assertEquals(LR, s.rate(0, 200), 1e-15, "the cosine starts at lr");
    }

    @Test
    void generationZeroDecaysByCosineToTenPercentAtTheLastPlannedStep() {
        LearningRateSchedule s = schedule(List.of());
        assertEquals(0.1 * LR, s.rate(0, 999), 1e-15, "last planned step (index 999 of 1000)");
        // The middle of the cosine, between steps 200 and 999 (799 steps apart): half way between lr and 0.1 lr.
        assertEquals(0.55 * LR, s.rate(0, 200 + 799 / 2), 2e-3 * LR); // step 599 is half a step before the exact middle
        double previous = Double.MAX_VALUE;
        for (int step = 200; step < 1000; step++) {
            double r = s.rate(0, step);
            assertTrue(r <= previous, "monotone at " + step);
            assertTrue(r >= 0.1 * LR - 1e-15 && r <= LR + 1e-15);
            previous = r;
        }
        assertEquals(0.1 * LR, s.rate(0, 5000), 1e-15, "beyond the plan the rate stays at the end value");
    }

    @Test
    void selfPlayGenerationsWarmUpOverOneHundredStepsThenStayConstant() {
        LearningRateSchedule s = schedule(List.of());
        assertEquals(LR / 100, s.rate(1, 0), 1e-15);
        assertEquals(LR, s.rate(1, 99), 1e-15);
        assertEquals(LR, s.rate(1, 100), 1e-15);
        assertEquals(LR, s.rate(7, 123456), 1e-15);
    }

    @Test
    void milestonesLowerTheRateFromTheirGenerationOn() {
        LearningRateSchedule s = schedule(List.of(new LearningRateSchedule.Milestone(3, 0.3 * LR),
                new LearningRateSchedule.Milestone(6, 0.09 * LR)));
        assertEquals(LR, s.rate(2, 500), 1e-15);
        assertEquals(0.3 * LR, s.rate(3, 500), 1e-15);
        assertEquals(0.3 * LR, s.rate(5, 500), 1e-15);
        assertEquals(0.09 * LR, s.rate(6, 500), 1e-15);
        assertEquals(0.09 * LR, s.rate(40, 500), 1e-15);
        assertEquals(0.3 * LR / 100, s.rate(3, 0), 1e-15, "the warm-up scales the milestone rate");
        assertEquals(0.1 * LR, s.rate(0, 999), 1e-15, "generation 0 ignores milestones");
    }

    /** Resume depends on this: the rate is a function of its arguments only. */
    @Test
    void theRateIsAPureFunction() {
        LearningRateSchedule a = schedule(List.of(new LearningRateSchedule.Milestone(2, 0.3 * LR)));
        LearningRateSchedule b = schedule(List.of(new LearningRateSchedule.Milestone(2, 0.3 * LR)));
        for (int gen : new int[] {0, 1, 2, 9}) {
            for (long step : new long[] {0, 1, 99, 100, 199, 200, 500, 999, 4000}) {
                double first = a.rate(gen, step);
                a.rate(gen + 1, step + 1);
                assertEquals(first, a.rate(gen, step), 0.0);
                assertEquals(first, b.rate(gen, step), 0.0);
            }
        }
    }

    @Test
    void aSelfPlayGenerationCanDecayWithACosineAfterItsWarmUp() {
        LearningRateSchedule s = new LearningRateSchedule(LR, 200, 1000, 0.1, 100, List.of(), 1000, 0.01 * LR);
        assertEquals(LR / 100, s.rate(1, 0), 1e-18, "the warm-up is unchanged");
        assertEquals(LR, s.rate(1, 99), 1e-18);
        assertEquals(LR, s.rate(1, 100), 1e-18, "the cosine starts at the base rate");
        assertEquals(0.506 * LR, s.rate(5, 549), 0.001 * LR, "about the middle");
        assertEquals(0.01 * LR, s.rate(1, 999), 1e-18, "and ends at the final rate");
        assertEquals(0.01 * LR, s.rate(1, 5000), 1e-18, "which then holds");
        for (long step = 100; step < 999; step++) {
            assertTrue(s.rate(2, step + 1) <= s.rate(2, step), "monotone at " + step);
        }
        assertEquals(LR, new LearningRateSchedule(LR, 200, 1000, 0.1, 100, List.of(), 0, 0.5 * LR).rate(1, 5000), 0.0,
                "0 decay steps: constant");
        assertThrows(IllegalArgumentException.class,
                () -> new LearningRateSchedule(LR, 200, 1000, 0.1, 100, List.of(), 101, 0), "no room for a cosine");
        assertThrows(IllegalArgumentException.class,
                () -> new LearningRateSchedule(LR, 200, 1000, 0.1, 100, List.of(), 1000, -1));
    }

    @Test
    void rejectsInconsistentConfigurations() {
        assertThrows(IllegalArgumentException.class, () -> new LearningRateSchedule(0, 200, 1000, 0.1, 100, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new LearningRateSchedule(LR, 200, 201, 0.1, 100, List.of()), "no room for a cosine");
        assertThrows(IllegalArgumentException.class, () -> new LearningRateSchedule(LR, 0, 1000, 0.1, 100, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new LearningRateSchedule(LR, 200, 1000, 1.5, 100, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> schedule(List.of(new LearningRateSchedule.Milestone(0, LR))));
        assertThrows(IllegalArgumentException.class, () -> schedule(List.of(new LearningRateSchedule.Milestone(3, LR),
                new LearningRateSchedule.Milestone(3, LR))));
        assertThrows(IllegalArgumentException.class, () -> schedule(List.of(new LearningRateSchedule.Milestone(5, LR),
                new LearningRateSchedule.Milestone(3, LR))));
        assertThrows(IllegalArgumentException.class, () -> schedule(List.of()).rate(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> schedule(List.of()).rate(0, -1));
    }
}
