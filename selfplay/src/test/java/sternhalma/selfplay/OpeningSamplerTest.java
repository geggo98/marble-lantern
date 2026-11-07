package sternhalma.selfplay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import sternhalma.engine.Geometry;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.search.Evaluator;
import sternhalma.search.Evaluators;

/** Reproducible, legal and varied openings; the mirror-canonical key of the diversity statistics. */
class OpeningSamplerTest {

    private static final RuleSet RULES = RuleSet.defaults(2);

    @Test
    void openingsAreReproducibleLegalAndOfTheRequestedLength() {
        OpeningSampler s = new OpeningSampler(OpeningSampler.Mode.UNIFORM, 4, 10, 30, 1, null);
        int[] lengths = new int[11];
        Set<Long> distinct = new HashSet<>();
        for (int id = 0; id < 500; id++) {
            int[] a = s.sample(RULES, 1006, id);
            assertArrayEquals(a, s.sample(RULES, 1006, id));
            assertTrue(a.length >= 4 && a.length <= 10);
            lengths[a.length]++;
            Position pos = Position.initial(RULES);
            for (int m : a) {
                assertTrue(java.util.Arrays.stream(pos.legalMoves()).anyMatch(x -> x == m));
                pos.make(m);
            }
            distinct.add(OpeningSampler.canonicalKey(pos));
        }
        for (int k = 4; k <= 10; k++) {
            assertTrue(lengths[k] > 40, "length " + k + " drawn " + lengths[k] + " times of 500");
        }
        assertTrue(distinct.size() >= 495, "distinct openings: " + distinct.size());
        assertNotEquals(java.util.Arrays.toString(s.sample(RULES, 1006, 1)),
                java.util.Arrays.toString(s.sample(RULES, 1007, 1)), "the seed matters");
    }

    @Test
    void softmaxAtALowTemperatureIsGreedyAndAtAHighOneSpreads() {
        Evaluator eval = Evaluators.byName("linear");
        OpeningSampler greedy = new OpeningSampler(OpeningSampler.Mode.SOFTMAX, 6, 6, 1e-6, 1, eval);
        for (int id = 0; id < 20; id++) {
            Position pos = Position.initial(RULES);
            for (int m : greedy.sample(RULES, 1006, id)) {
                int best = Integer.MIN_VALUE;
                for (int x : pos.legalMoves()) {
                    pos.make(x);
                    best = Math.max(best, -eval.evaluate(pos));
                    pos.unmake();
                }
                pos.make(m);
                assertEquals(best, -eval.evaluate(pos), "the drawn move has the best depth-1 score");
            }
        }
        OpeningSampler warm = new OpeningSampler(OpeningSampler.Mode.SOFTMAX, 6, 6, 30, 1, eval);
        OpeningSampler deep = new OpeningSampler(OpeningSampler.Mode.SOFTMAX, 6, 6, 30, 2, eval);
        Set<String> a = new HashSet<>();
        Set<String> b = new HashSet<>();
        for (int id = 0; id < 50; id++) {
            a.add(java.util.Arrays.toString(warm.sample(RULES, 1006, id)));
            b.add(java.util.Arrays.toString(deep.sample(RULES, 1006, id)));
        }
        assertTrue(a.size() >= 45, "temperature 30 still varies: " + a.size());
        assertTrue(b.size() >= 45, "depth 2 varies: " + b.size());
    }

    @Test
    void canonicalKeyIdentifiesMirrorImagesOnly() {
        Position pos = Position.initial(RULES);
        for (int m : new OpeningSampler(OpeningSampler.Mode.UNIFORM, 7, 7, 30, 1, null).sample(RULES, 1006, 3)) {
            pos.make(m);
        }
        Position mirror = pos.transformed(Geometry::mirror, false);
        assertNotEquals(pos.hash(), mirror.hash(), "an asymmetric position");
        assertEquals(OpeningSampler.canonicalKey(pos), OpeningSampler.canonicalKey(mirror));
        Position other = pos.copy();
        other.make(other.legalMoves()[0]);
        assertNotEquals(OpeningSampler.canonicalKey(pos), OpeningSampler.canonicalKey(other));
    }
}
