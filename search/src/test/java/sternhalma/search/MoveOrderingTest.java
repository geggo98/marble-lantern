package sternhalma.search;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;

import org.junit.jupiter.api.Test;

import sternhalma.engine.Position;

/**
 * The lazy move ordering of round 2, E01: {@link AlphaBeta#pick} called for i = 0, 1, ... must reproduce the
 * full selection sort that the search used before, also for tied keys, since the order of ties decides the
 * search tree. The fingerprint tests check the whole search; this test checks the ordering step alone, on
 * key patterns the reference positions may not contain.
 */
class MoveOrderingTest {

    /** The pre-E01 ordering, copied verbatim from AlphaBeta.order: a full selection sort, strict '>'. */
    private static void fullSelectionSort(int[] moves, int[] gain, int count) {
        for (int i = 0; i < count - 1; i++) {
            int best = i;
            for (int j = i + 1; j < count; j++) {
                if (gain[j] > gain[best]) {
                    best = j;
                }
            }
            if (best != i) {
                int t = moves[i];
                moves[i] = moves[best];
                moves[best] = t;
                t = gain[i];
                gain[i] = gain[best];
                gain[best] = t;
            }
        }
    }

    /** Random keys; a small range forces many ties, the extremes stand for table and killer moves. */
    private static int[] randomKeys(Random random, int count, int range) {
        int[] keys = new int[count];
        for (int i = 0; i < count; i++) {
            int k = random.nextInt(range + 3);
            keys[i] = k == range ? Integer.MAX_VALUE : k == range + 1 ? Integer.MAX_VALUE - 1
                    : k == range + 2 ? Integer.MIN_VALUE + random.nextInt(2) : k - range / 2;
        }
        return keys;
    }

    private static int[] identityMoves(int count) {
        int[] moves = new int[count];
        for (int i = 0; i < count; i++) {
            moves[i] = 1000 + i; // distinct, so a tie broken the other way shows up as a different move
        }
        return moves;
    }

    @Test
    void pickingStepByStepYieldsTheFullSelectionSortOrderIncludingTies() {
        Random random = new Random(20261107L);
        int[] ranges = {1, 2, 3, 5, 20, 1 << 24};
        for (int trial = 0; trial < 20_000; trial++) {
            int count = 1 + random.nextInt(trial % 10 == 0 ? Position.MAX_MOVES : 70);
            int range = ranges[trial % ranges.length];
            int[] keys = randomKeys(random, count, range);
            int[] expectedMoves = identityMoves(count);
            int[] expectedKeys = keys.clone();
            fullSelectionSort(expectedMoves, expectedKeys, count);

            int[] moves = identityMoves(count);
            int[] lazyKeys = keys.clone();
            // stop early in some trials, as a cutoff does: the prefix must already be final
            int steps = trial % 3 == 0 ? random.nextInt(count) + 1 : count;
            for (int i = 0; i < steps; i++) {
                AlphaBeta.pick(moves, lazyKeys, i, count);
                assertEquals(expectedMoves[i], moves[i], () -> "trial with " + count + " moves, step");
                assertEquals(expectedKeys[i], lazyKeys[i]);
            }
            if (steps == count) {
                assertArrayEquals(expectedMoves, moves);
                assertArrayEquals(expectedKeys, lazyKeys);
            }
        }
    }

    @Test
    void tiesKeepTheirGeneratorOrderAmongEqualKeysAsTheOldSortDid() {
        // keys 5,5,9,5: the old sort gives 9 first, then the 5s in the order that its swaps leave them
        int[] keys = {5, 5, 9, 5};
        int[] expected = identityMoves(4);
        int[] expectedKeys = keys.clone();
        fullSelectionSort(expected, expectedKeys, 4);
        int[] moves = identityMoves(4);
        for (int i = 0; i < 4; i++) {
            AlphaBeta.pick(moves, keys, i, 4);
        }
        // selection sort is not stable: the swap of step 0 moves the first 5 behind the second
        assertArrayEquals(new int[] {1002, 1001, 1000, 1003}, expected);
        assertArrayEquals(expected, moves);
    }

    @Test
    void pickOnlyTouchesPositionsFromIOnwardAndOnlyWithinCount() {
        int[] moves = {1, 2, 3, 4, 5, 6};
        int[] keys = {0, 0, 1, 7, 3, 99};
        AlphaBeta.pick(moves, keys, 1, 5); // the 99 lies beyond count and must stay put
        assertArrayEquals(new int[] {1, 4, 3, 2, 5, 6}, moves);
        assertArrayEquals(new int[] {0, 7, 1, 0, 3, 99}, keys);
    }

    @Test
    void lmrTablesMatchTheFormulaInEverySearcher() {
        for (int mode = 1; mode <= 3; mode++) {
            SearchOptions options = new SearchOptions(true, true, true, mode, 0, false, false);
            AlphaBeta a = new AlphaBeta(new DistanceEvaluator(), 4, true, options);
            AlphaBeta b = new AlphaBeta(new DistanceEvaluator(), 4, true, options);
            for (int d = 1; d <= AlphaBeta.MAX_DEPTH; d++) {
                for (int i = 1; i < Position.MAX_MOVES; i++) {
                    double divisor = mode == 2 ? 2.0 : 1.25;
                    int r = mode == 1 ? 1 : (int) Math.floor(0.5 + Math.log(d) * Math.log(i) / divisor);
                    int expected = Math.max(1, Math.min(r, d - 2));
                    assertEquals(expected, a.lmrReduction(d, i), "mode " + mode + " depth " + d + " move " + i);
                    assertEquals(expected, b.lmrReduction(d, i));
                }
            }
        }
        // spot values, so a wrong divisor cannot hide behind a formula copied into this test
        AlphaBeta lmr2 = new AlphaBeta(new DistanceEvaluator(), 4, true,
                new SearchOptions(true, true, true, 2, 0, false, false));
        AlphaBeta lmr3 = new AlphaBeta(new DistanceEvaluator(), 4, true,
                new SearchOptions(true, true, true, 3, 0, false, false));
        assertEquals(1, lmr2.lmrReduction(3, 3));   // capped at depth - 2
        assertEquals(3, lmr2.lmrReduction(8, 20));  // 0.5 + ln 8 ln 20 / 2 = 3.61
        assertEquals(3, lmr2.lmrReduction(10, 10)); // 0.5 + ln 10 ln 10 / 2 = 3.15
        assertEquals(4, lmr3.lmrReduction(10, 10)); // 0.5 + ln 10 ln 10 / 1.25 = 4.74
        assertEquals(1, lmr2.lmrReduction(64, 1));  // ln 1 = 0, floor 1
    }
}
