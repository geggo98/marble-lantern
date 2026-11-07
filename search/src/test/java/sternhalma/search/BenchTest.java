package sternhalma.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class BenchTest {

    private static final String SMALL = Fingerprint.withNodes(ReferencePositions.R2_SPEC, 300);

    @Test
    void theSameSpecInAnIsolatedCopyOfTheBuildSearchesTheSameTrees() {
        List<String> log = new ArrayList<>();
        try (Bench.Build current = Bench.Build.current(); Bench.Build copy = Bench.Build.isolatedCopyOfCurrent()) {
            Bench.Result r = Bench.run(new Bench.Side("A", current, SMALL), new Bench.Side("B", copy, SMALL),
                    2, 4, 1, log::add);
            assertTrue(r.identicalTrees(), r.toString());
            assertEquals(4, r.identicalPositions());
            assertEquals(1, r.identicalGames());
            assertEquals(2, r.repRatios().length);
            assertEquals(2, log.size(), "one line per repetition");
            for (int side = 0; side < 2; side++) {
                assertEquals(4 * 2, r.positions()[side].moves(), "4 positions x 2 repetitions");
                assertTrue(r.games()[side].moves() > 2 * 20, "a full game has more than 20 moves");
                assertTrue(r.all(side).cpuNanos() > 0);
                assertTrue(r.all(side).nodesPerCpuSecond() > 0);
            }
            assertEquals(r.all(0).nodes(), r.all(1).nodes(), "identical trees visit identical node counts");
            assertTrue(r.medianRatio() > 0.2 && r.medianRatio() < 5, "ratio " + r.medianRatio());
        }
    }

    @Test
    void theIsolatedCopyReallyHasItsOwnClasses() {
        try (Bench.Build copy = Bench.Build.isolatedCopyOfCurrent()) {
            Object pos = copy.position(new int[0]);
            assertNotSame(sternhalma.engine.Position.class, pos.getClass());
            assertEquals(sternhalma.engine.Position.class.getName(), pos.getClass().getName());
            assertTrue(copy.knowsEvaluator("linear-r1"));
        }
    }

    @Test
    void differentSpecsAreReportedAsDifferentTrees() {
        try (Bench.Build current = Bench.Build.current()) {
            Bench.Result r = Bench.run(new Bench.Side("A", current, SMALL),
                    new Bench.Side("B", current, Fingerprint.withNodes(SMALL, 200)), 1, 3, 0, line -> { });
            assertFalse(r.identicalTrees());
            assertEquals(0, r.identicalPositions());
        }
    }

    @Test
    void aSingleSideHasNoRatio() {
        try (Bench.Build current = Bench.Build.current()) {
            Bench.Result r = Bench.run(new Bench.Side("A", current, SMALL), null, 1, 2, 0, line -> { });
            assertFalse(r.hasB());
            assertEquals(0, r.repRatios().length);
            assertTrue(Double.isNaN(r.medianRatio()));
        }
    }

    @Test
    void theMedianOfTheRepetitionsIsReported() {
        Bench.Totals t = new Bench.Totals(1, 1, 1, 1);
        Bench.Totals[] two = {t, t};
        assertEquals(1.1, new Bench.Result("a", "b", two, two, new double[] {1.3, 0.9, 1.1}, 1, 0, 1, 0)
                .medianRatio(), 1e-12);
        assertEquals(1.0, new Bench.Result("a", "b", two, two, new double[] {0.9, 1.1}, 1, 0, 1, 0)
                .medianRatio(), 1e-12);
    }

    @Test
    void loadAboveTheCoreCountRefuses() {
        assertTrue(Bench.tooLoaded(8.5, 8));
        assertFalse(Bench.tooLoaded(8.0, 8));
        assertFalse(Bench.tooLoaded(-1, 8), "an unknown load (-1) does not refuse");
    }

    @Test
    void theAliasIsKeptForABuildThatKnowsIt() {
        try (Bench.Build current = Bench.Build.current()) {
            assertEquals(ReferencePositions.R2_SPEC, Bench.specFor(current, ReferencePositions.R2_SPEC, line -> { }));
        }
    }

    /**
     * End-to-end check against the frozen pre-round build, if this machine has one: R2 must search the same
     * trees there (with {@code eval=linear}) as here. Skipped without the frozen jars.
     */
    @Test
    void theFrozenBuildSearchesTheSameTreesAsR2() {
        Path dir = Path.of(System.getProperty(Bench.REFERENCE_DIR_PROPERTY, "../" + Bench.DEFAULT_REFERENCE_DIR));
        assumeTrue(Files.isRegularFile(dir.resolve("search.jar")), "no frozen build in " + dir.toAbsolutePath());
        try (Bench.Build current = Bench.Build.current(); Bench.Build frozen = Bench.Build.reference(dir)) {
            String spec = Bench.specFor(frozen, ReferencePositions.R2_SPEC, line -> { });
            Bench.Result r = Bench.run(new Bench.Side("A", current, ReferencePositions.R2_SPEC),
                    new Bench.Side("B", frozen, spec), 1, 8, 0, line -> { });
            assertEquals(8, r.identicalPositions(), "R2 differs from the frozen build: " + r);
        }
    }
}
