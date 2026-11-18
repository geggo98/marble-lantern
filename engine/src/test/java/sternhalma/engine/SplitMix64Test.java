package sternhalma.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

/** SplitMix64 must give the stream of {@link SplittableRandom}: the Zobrist keys of {@link Position} depend on it. */
class SplitMix64Test {

    @Test
    void producesTheSameStreamAsSplittableRandomForTheZobristSeed() {
        long seed = 0x5E7A_11A1_0000_0001L;
        SplittableRandom reference = new SplittableRandom(seed);
        SplitMix64 mine = new SplitMix64(seed);
        for (int i = 0; i < 10_000; i++) {
            assertEquals(reference.nextLong(), mine.nextLong(), "value " + i);
        }
    }

    @Test
    void producesTheSameStreamForOtherSeeds() {
        for (long seed : new long[] {0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, 0x9e3779b97f4a7c15L}) {
            SplittableRandom reference = new SplittableRandom(seed);
            SplitMix64 mine = new SplitMix64(seed);
            for (int i = 0; i < 100; i++) {
                assertEquals(reference.nextLong(), mine.nextLong(), "seed " + seed + " value " + i);
            }
        }
    }
}
