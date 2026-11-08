package sternhalma.selfplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.nn.Network;
import sternhalma.nn.NetworkConfig;
import sternhalma.nn.Parameters;
import sternhalma.search.Elo;
import sternhalma.selfplay.StrengthEstimate.Rung;

/** The Elo estimate: paired games against a classical rung, seeded, repeatable, discarded when stopped. */
class StrengthEstimateTest {

    static final String BASE = "ab:eval=distance";

    private static Network network(long seed) {
        return new Network(Parameters.init(NetworkConfig.tiny(2), seed));
    }

    @Test
    void theResultIsRepeatableIndependentOfThreadsAndConsistentWithItsGames() {
        Network net = network(1);
        AtomicInteger progress = new AtomicInteger();
        List<Rung> one = StrengthEstimate.estimate(net, BASE, List.of(20, 60), 3, 7, 1, () -> false,
                progress::set);
        List<Rung> two = StrengthEstimate.estimate(net, BASE, List.of(20, 60), 3, 7, 3, () -> false, n -> { });
        assertEquals(one, two, "same seed, same games, whatever the thread count");
        assertEquals(6, progress.get(), "pairs done over both rungs");
        assertEquals(2, one.size());
        assertEquals(20, one.get(0).rung());
        assertEquals(60, one.get(1).rung());
        for (Rung r : one) {
            assertEquals(6, r.wins() + r.draws() + r.losses(), "two games per pair");
            assertEquals(3, r.pairs());
            assertEquals(Elo.fromScore(r.score()), r.elo(), 1e-12);
            assertEquals(StrengthEstimate.spec(BASE, r.rung()), r.spec());
            // a win scores 1, a loss 0 and a move-limit game something in between
            assertTrue(r.score() >= r.wins() / 6.0 - 1e-12 && r.score() <= (r.wins() + r.draws()) / 6.0 + 1e-12,
                    "score " + r.score() + " with " + r + " lies between the wins and the wins plus draws");
            // an untrained network loses to a classical player: the score is the network's, not the opponent's
            assertTrue(r.score() < 0.5, "an untrained network does not beat the classical rung: " + r);
            assertEquals(StrengthEstimate.clamped(r.score()), r.clamped());
        }
        // another seed plays other openings
        List<Rung> other = StrengthEstimate.estimate(net, BASE, List.of(20), 3, 8, 1, () -> false, n -> { });
        assertEquals(8, other.get(0).seed());
    }

    @Test
    void theOpeningsAreSeededPerPairAndLegal() {
        RuleSet rules = RuleSet.defaults(2);
        for (int pair = 0; pair < 5; pair++) {
            Position a = StrengthEstimate.opening(rules, 3, pair, 4);
            Position b = StrengthEstimate.opening(rules, 3, pair, 4);
            assertEquals(a.hash(), b.hash());
            assertEquals(4, a.ply());
        }
        // the pairs of one seed start from different openings: with equal ones the paired CI would be a single game
        Set<Long> pairs = new HashSet<>();
        for (int pair = 0; pair < 5; pair++) {
            pairs.add(StrengthEstimate.opening(rules, 3, pair, 4).hash());
        }
        assertTrue(pairs.size() >= 3, "openings of pairs 0..4 under one seed: " + pairs.size() + " distinct");
        // and another seed gives other openings
        Set<Long> seeds = new HashSet<>();
        for (long seed = 3; seed < 8; seed++) {
            seeds.add(StrengthEstimate.opening(rules, seed, 0, 4).hash());
        }
        assertTrue(seeds.size() >= 3, "openings of pair 0 under five seeds: " + seeds.size() + " distinct");
    }

    @Test
    void aMeanScoreOfExactlyZeroOrOneIsClamped() {
        assertTrue(StrengthEstimate.clamped(0.0));
        assertTrue(StrengthEstimate.clamped(1.0), "a perfect score is only a bound, like a zero score");
        assertFalse(StrengthEstimate.clamped(0.5));
        assertFalse(StrengthEstimate.clamped(0.02));
        assertFalse(StrengthEstimate.clamped(0.98));
        Rung top = new Rung(20, "spec", 3, 1, 1.0, 0.0, Elo.fromScore(1.0), 0.0, 6, 0, 0, StrengthEstimate.clamped(1.0));
        assertEquals(true, top.fields(10).get("clamped"));
        assertTrue(top.toString().contains("clamped"), top.toString());
    }

    @Test
    void aStopDiscardsTheWholeEstimate() {
        AtomicInteger asked = new AtomicInteger();
        List<Rung> result = StrengthEstimate.estimate(network(1), BASE, List.of(20), 6, 1, 1,
                () -> asked.incrementAndGet() > 2, n -> { });
        assertNull(result);
    }

    @Test
    void theResultFileIsCompleteOnlyForTheRequestedLadderPairsAndSeed(@TempDir Path dir) throws IOException {
        List<Rung> rungs = StrengthEstimate.estimate(network(1), BASE, List.of(20, 40), 2, 5, 1, () -> false,
                n -> { });
        Path file = StrengthEstimate.file(dir, 500);
        assertEquals("step-00000500.jsonl", file.getFileName().toString());
        assertFalse(StrengthEstimate.complete(file, BASE, List.of(20, 40), 2, 5), "no file yet");
        StrengthEstimate.write(file, 500, rungs);
        assertTrue(StrengthEstimate.complete(file, BASE, List.of(20, 40), 2, 5));
        assertFalse(StrengthEstimate.complete(file, BASE, List.of(20, 40), 3, 5), "other pairs");
        assertFalse(StrengthEstimate.complete(file, BASE, List.of(20, 40), 2, 6), "other seed");
        assertFalse(StrengthEstimate.complete(file, BASE, List.of(20), 2, 5), "other ladder");
        assertFalse(StrengthEstimate.complete(file, BASE, List.of(20, 41), 2, 5), "other rung");
        assertFalse(StrengthEstimate.complete(file, "ab:eval=rows", List.of(20, 40), 2, 5), "other opponent");
        // a torn file is not complete
        String text = Files.readString(file);
        Files.writeString(file, text.substring(0, text.length() - 30));
        assertFalse(StrengthEstimate.complete(file, BASE, List.of(20, 40), 2, 5));
        assertFalse(Files.exists(file.resolveSibling(file.getFileName() + ".tmp")), "written atomically");
    }
}
