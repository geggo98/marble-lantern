package sternhalma.nn;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Tests of {@link BatchSampler} and {@link TrainingBatch#select} (docs/training.md, "Checkpoints"). */
class BatchSamplerTest {

    @Test
    void sameSeedGenerationAndStepGiveTheSameBatch() {
        int[] a = BatchSampler.indices(7, 2, 1234, 500, 64);
        int[] b = BatchSampler.indices(7, 2, 1234, 500, 64);
        assertArrayEquals(a, b);
        assertEquals(64, a.length);
        for (int i : a) {
            assertTrue(i >= 0 && i < 500);
        }
    }

    /** A resumed run draws from step t only: the draw of step t must not depend on earlier draws. */
    @Test
    void aStepNeedsNoStateFromEarlierSteps() {
        int[] direct = BatchSampler.indices(7, 0, 50, 100, 8);
        for (long step = 0; step < 50; step++) {
            BatchSampler.indices(7, 0, step, 100, 8);
        }
        assertArrayEquals(direct, BatchSampler.indices(7, 0, 50, 100, 8));
    }

    @Test
    void seedGenerationAndStepAllChangeTheDraw() {
        int[] base = BatchSampler.indices(7, 2, 10, 100000, 16);
        assertFalse(Arrays.equals(base, BatchSampler.indices(8, 2, 10, 100000, 16)), "seed");
        assertFalse(Arrays.equals(base, BatchSampler.indices(7, 3, 10, 100000, 16)), "generation");
        assertFalse(Arrays.equals(base, BatchSampler.indices(7, 2, 11, 100000, 16)), "step");
    }

    /** (generation 1, step 0) and (generation 0, step 1) must not collide, as a plain sum of the two would. */
    @Test
    void mixSeparatesTheArguments() {
        Set<Long> seen = new HashSet<>();
        for (long seed = 0; seed < 4; seed++) {
            for (int generation = 0; generation < 20; generation++) {
                for (long step = 0; step < 200; step++) {
                    assertTrue(seen.add(BatchSampler.mix(seed, generation, step)),
                            "collision at " + seed + ", " + generation + ", " + step);
                }
            }
        }
        assertNotEquals(BatchSampler.mix(5, 1, 0), BatchSampler.mix(5, 0, 1));
        assertNotEquals(BatchSampler.mix(5, 0, 1), BatchSampler.mix(6, 0, 0));
    }

    /** The draws of consecutive steps are spread over the pool: each of 10 cells gets 10 % +- 1 %. */
    @Test
    void drawsAreUniform() {
        int pool = 10;
        long[] counts = new long[pool];
        int steps = 20000;
        for (long step = 0; step < steps; step++) {
            for (int i : BatchSampler.indices(3, 0, step, pool, 5)) {
                counts[i]++;
            }
        }
        for (long c : counts) {
            assertEquals(0.1, c / (5.0 * steps), 0.01);
        }
    }

    @Test
    void batchIsTheSelectedSamplesOfThePool() {
        TrainingBatch pool = RunFixtures.pool();
        int[] indices = BatchSampler.indices(9, 1, 3, pool.size(), 6);
        TrainingBatch batch = BatchSampler.batch(pool, 9, 1, 3, 6);
        assertEquals(6, batch.size());
        for (int i = 0; i < indices.length; i++) {
            TrainingBatch expected = TestBatches.single(pool, indices[i]);
            TrainingBatch actual = TestBatches.single(batch, i);
            assertArrayEquals(expected.input(), actual.input(), "input " + i);
            assertArrayEquals(expected.moveFrom(), actual.moveFrom(), "from " + i);
            assertArrayEquals(expected.moveTo(), actual.moveTo(), "to " + i);
            assertArrayEquals(expected.moveTarget(), actual.moveTarget(), "target " + i);
            assertArrayEquals(expected.valueTarget(), actual.valueTarget(), "value " + i);
            assertEquals(expected.policyWeight()[0], actual.policyWeight()[0]);
            assertEquals(expected.valueWeight()[0], actual.valueWeight()[0]);
            assertEquals(expected.targetEntropy()[0], actual.targetEntropy()[0]);
        }
    }

    @Test
    void selectRejectsBadIndices() {
        TrainingBatch pool = RunFixtures.pool();
        assertThrows(IllegalArgumentException.class, () -> pool.select(new int[] {pool.size()}));
        assertThrows(IllegalArgumentException.class, () -> pool.select(new int[] {-1}));
        assertThrows(IllegalArgumentException.class, () -> pool.select(new int[0]));
        assertThrows(IllegalArgumentException.class, () -> BatchSampler.indices(1, 0, 0, 0, 4));
        assertThrows(IllegalArgumentException.class, () -> BatchSampler.indices(1, 0, 0, 4, 0));
        assertThrows(IllegalArgumentException.class, () -> BatchSampler.indices(1, -1, 0, 4, 4));
        assertThrows(IllegalArgumentException.class, () -> BatchSampler.indices(1, 0, -1, 4, 4));
    }
}
