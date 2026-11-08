package sternhalma.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.search.LinearEvaluator.GainMode;
import sternhalma.tablebase.RaceTable;

/**
 * The refit tools of round-2 experiment E07: 13-feature samples, labels from the first S1 verdict, the fit with the
 * tempo intercept no longer in the last column, and the frozen weight set {@code linear-r3} on held-out games of its
 * own data set.
 */
class LinearRefitGainTest {

    /** Held-out games (id % 5 == 0) of the E07 round-2 data set, see the resource's header. */
    private static final String HELD_OUT = "e07-heldout-games.txt";

    static List<GameFiles.Game> heldOutGames(Path dir) throws IOException {
        Path file = dir.resolve(HELD_OUT);
        try (InputStream in = LinearRefitGainTest.class.getResourceAsStream(HELD_OUT)) {
            assertNotNull(in, "test resource " + HELD_OUT);
            Files.copy(in, file);
        }
        List<GameFiles.Game> games = GameFiles.read(file);
        assertTrue(games.size() >= 100, "games: " + games.size());
        assertTrue(games.stream().allMatch(g -> g.id() % 5 == 0), "held-out games only");
        return games;
    }

    /** The player of the data set: the wave-1 BEST candidate at 20000 nodes (frozen evaluator names only). */
    static final String DATA_SPEC = "ab:eval=linear-r2,pvs=1,killers=1,history=1,lmr=2,asp=1,ttrep=1,mate=1,partial=1,"
            + "fut=1,futk=30,fut2=1,race=1,nodes=20000";

    @Test
    void aStoredGameReplaysMoveForMove(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("copy.txt");
        List<GameFiles.Game> games = heldOutGames(dir);
        Files.copy(dir.resolve(HELD_OUT), file);
        assertEquals(DATA_SPEC, GameFiles.headerValue(file, "spec"));
        assertEquals("1008", GameFiles.headerValue(file, "seed"));
        GameFiles.Game stored = games.get(1);
        GameFiles.Game again = GameFiles.play(PlayerSpec.parse(DATA_SPEC), 1008, stored.id(), 4);
        org.junit.jupiter.api.Assertions.assertArrayEquals(stored.moves(), again.moves());
        assertEquals(stored.value0(), again.value0());
    }

    @Test
    void gainSamplesCarryTheTwelveFeaturesAndTheGainOfTheReplayedPosition(@TempDir Path dir) throws IOException {
        List<GameFiles.Game> games = heldOutGames(dir).subList(0, 10);
        List<LinearTraining.Sample> twelve = LinearTraining.samples(games, 6, true, null);
        List<LinearTraining.Sample> both = LinearTraining.samples(games, 6, true, null, GainMode.BOTH, null);
        List<LinearTraining.Sample> mover = LinearTraining.samples(games, 6, true, null, GainMode.MOVER, null);
        assertEquals(twelve.size(), both.size());
        assertEquals(twelve.size(), mover.size());
        int nonzero = 0;
        for (int i = 0; i < twelve.size(); i++) {
            assertEquals(LinearEvaluator.GAIN_FEATURES, both.get(i).x().length);
            for (int f = 0; f < LinearEvaluator.FEATURES; f++) {
                assertEquals(twelve.get(i).x()[f], both.get(i).x()[f], 0.0);
            }
            assertEquals(twelve.get(i).label(), both.get(i).label(), 0.0);
            // independent replay of the same position
            LinearTraining.Sample s = both.get(i);
            Position pos = replay(games, s.game(), s.ply());
            int me = pos.sideToMove();
            assertEquals(LinearEvaluator.bestGain(pos, me) - LinearEvaluator.bestGain(pos, 1 - me),
                    s.x()[LinearEvaluator.BEST_GAIN], 0.0);
            assertEquals(LinearEvaluator.bestGain(pos, me), mover.get(i).x()[LinearEvaluator.BEST_GAIN], 0.0);
            if (s.x()[LinearEvaluator.BEST_GAIN] != 0) {
                nonzero++;
            }
        }
        assertTrue(nonzero > twelve.size() / 4, "gain differences: " + nonzero + " of " + twelve.size());
    }

    private static Position replay(List<GameFiles.Game> games, int id, int ply) {
        GameFiles.Game g = games.stream().filter(x -> x.id() == id).findFirst().orElseThrow();
        Position pos = Position.initial(RuleSet.defaults(2));
        for (int i = 0; i < ply; i++) {
            pos.make(g.moves()[i]);
        }
        return pos;
    }

    @Test
    void theFirstS1VerdictLabelsTheGameAndOverridesARecordedResult(@TempDir Path dir) throws IOException {
        RaceAdjudication s1 = new RaceAdjudication(RaceTable.rows2());
        List<GameFiles.Game> games = heldOutGames(dir).subList(0, 20);
        int fired = 0;
        List<GameFiles.Game> flipped = new ArrayList<>();
        for (GameFiles.Game g : games) {
            Double v = LinearTraining.s1Value0(g, s1);
            if (v != null) {
                fired++;
                // the verdict is the first one on the replay, recomputed here independently
                assertEquals(v, firstVerdict(g, s1), g.id() + "");
            }
            flipped.add(new GameFiles.Game(g.id(), -g.value0(), g.moves()));
        }
        assertTrue(fired >= 15, "S1 fired in " + fired + " of 20 games");
        long[] counts = new long[4];
        List<LinearTraining.Sample> honest = LinearTraining.samples(games, 6, true, counts, null, s1);
        assertEquals(fired, counts[2]);
        assertEquals(0, counts[3], "race play converts every won race in these games");
        long[] flippedCounts = new long[4];
        List<LinearTraining.Sample> relabelled = LinearTraining.samples(flipped, 6, true, flippedCounts, null, s1);
        assertEquals(fired, flippedCounts[3], "every flipped result with a verdict is relabelled");
        // a game with a verdict keeps its true labels; one without keeps the (flipped) record
        for (int i = 0; i < honest.size(); i++) {
            LinearTraining.Sample a = honest.get(i);
            LinearTraining.Sample b = relabelled.get(i);
            GameFiles.Game g = games.stream().filter(x -> x.id() == a.game()).findFirst().orElseThrow();
            double expected = LinearTraining.s1Value0(g, s1) != null ? a.label() : 1 - a.label();
            assertEquals(expected, b.label(), 1e-12);
        }
        // without S1 the recorded result is the label
        List<LinearTraining.Sample> plain = LinearTraining.samples(flipped, 6, true, null, null, null);
        for (int i = 0; i < honest.size(); i++) {
            assertEquals(1 - honest.get(i).label(), plain.get(i).label(), 1e-12);
        }
    }

    private static Double firstVerdict(GameFiles.Game g, RaceAdjudication s1) {
        Position pos = Position.initial(RuleSet.defaults(2));
        for (int m : g.moves()) {
            RaceAdjudication.Verdict v = s1.adjudicate(pos);
            if (v != null) {
                return v.winner() == 0 ? 1.0 : -1.0;
            }
            pos.make(m);
        }
        return null;
    }

    @Test
    void theFitRecoversKnownWeightsWithTheGainColumnAfterTheTempoTerm() {
        Random random = new Random(11);
        int f = LinearEvaluator.GAIN_FEATURES;
        double[] truth = new double[f];
        truth[0] = -0.3;
        truth[LinearEvaluator.TEMPO] = 0.2;
        truth[LinearEvaluator.BEST_GAIN] = 0.15;
        List<LinearTraining.Sample> data = new ArrayList<>();
        for (int i = 0; i < 30_000; i++) {
            double[] x = new double[f];
            for (int j = 0; j < f; j++) {
                x[j] = j == LinearEvaluator.TEMPO ? 1 : random.nextGaussian() * 3 + 1;
            }
            double p = LinearTraining.sigmoid(LinearTraining.dot(truth, x));
            data.add(new LinearTraining.Sample(i, 0, random.nextDouble() < p ? 1.0 : 0.0, x));
        }
        double[] w = LinearTraining.logistic(data, LinearTraining.mask("all", LinearEvaluator.GAIN_NAMES), 1e-9);
        assertEquals(f, w.length);
        for (int j = 0; j < f; j++) {
            assertEquals(truth[j], w[j], 0.04, LinearEvaluator.GAIN_NAMES[j]);
        }
        // masking the gain out gives a zero weight there
        boolean[] mask = LinearTraining.mask("all", LinearEvaluator.GAIN_NAMES);
        mask[LinearEvaluator.BEST_GAIN] = false;
        assertEquals(0.0, LinearTraining.logistic(data, mask, 1e-9)[LinearEvaluator.BEST_GAIN], 0.0);
    }

    /**
     * The frozen weights must predict their own held-out games better than {@code linear-r2} and better than a
     * 12-feature fit would allow: on the committed held-out games, {@code linear-r3} (with the gain) has a lower log
     * loss than {@code linear-r2}, and dropping its gain weight makes it worse.
     */
    @Test
    void linearR3PredictsItsHeldOutGamesBetterThanLinearR2(@TempDir Path dir) throws IOException {
        RaceAdjudication s1 = new RaceAdjudication(RaceTable.rows2());
        List<LinearTraining.Sample> test = LinearTraining.samples(heldOutGames(dir), 6, true, null, GainMode.BOTH,
                s1);
        assertTrue(test.size() >= 5_000, "held-out samples: " + test.size());
        double[] r2 = LinearTraining.score(test, LinearWeights.ROUND2);
        double[] r3 = LinearTraining.score(test, LinearWeights.ROUND3);
        double[] withoutGain = LinearTraining.score(test,
                java.util.Arrays.copyOf(LinearWeights.ROUND3, LinearEvaluator.FEATURES));
        assertTrue(r3[0] < r2[0], "held-out log loss: linear-r3 " + r3[0] + " must be below linear-r2 " + r2[0]);
        assertTrue(r3[0] < withoutGain[0], "the gain weight must matter: " + r3[0] + " vs " + withoutGain[0]);
    }
}
