package sternhalma.selfplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.nn.TrainingBatch;
import sternhalma.selfplay.SyntheticSamples.Sample;

/**
 * The game filter of {@link ReplayBuffer} (the held-out split of generation 0) and the game results that the winner
 * prediction needs.
 */
class ReplayBufferGamesTest {

    static final int PER_GAME = 6;
    static final double FRACTION = 0.25;

    @TempDir
    static Path dir;

    static List<Path> files = new ArrayList<>();

    @BeforeAll
    static void write() throws IOException {
        // two seeds with overlapping game ids: the split must key on (seed, id), not on id alone
        files.add(SyntheticShards.write(dir, "samples-s1-0000.safetensors", 1, 0, 40, PER_GAME, 11));
        files.add(SyntheticShards.write(dir, "samples-s1-0001.safetensors", 1, 40, 40, PER_GAME, 12));
        files.add(SyntheticShards.write(dir, "samples-s2-0000.safetensors", 2, 0, 40, PER_GAME, 13));
    }

    /** The buffers as the tool builds them. */
    private static ReplayBuffer buffer(List<Path> from, boolean held) throws IOException {
        Gen0.Split split = Gen0.split(from, FRACTION, false);
        return held ? split.heldOut() : split.train();
    }

    /** The value targets of the samples (random floats, so a fingerprint of the sample). */
    private static Set<String> fingerprints(ReplayBuffer b) {
        int n = b.size();
        int[] all = new int[n];
        for (int i = 0; i < n; i++) {
            all[i] = i;
        }
        TrainingBatch batch = b.batchOf(all, new boolean[n]);
        Set<String> result = new HashSet<>();
        for (int i = 0; i < n; i++) {
            result.add(batch.valueTarget()[2 * i] + "/" + batch.valueTarget()[2 * i + 1] + "/"
                    + (batch.moveOffsets()[i + 1] - batch.moveOffsets()[i]));
        }
        assertEquals(n, result.size(), "the samples are distinguishable");
        return result;
    }

    @Test
    void trainingAndHeldOutGamesAreDisjointAndTogetherComplete() throws IOException {
        ReplayBuffer train = buffer(files, false);
        ReplayBuffer held = buffer(files, true);
        ReplayBuffer everything = new ReplayBuffer(2, Integer.MAX_VALUE, false);
        everything.addGeneration(0, files);
        assertEquals(3 * 40 * PER_GAME, everything.size());
        assertEquals(everything.size(), train.size() + held.size());
        Set<String> t = fingerprints(train);
        Set<String> h = fingerprints(held);
        Set<String> all = fingerprints(everything);
        Set<String> overlap = new HashSet<>(t);
        overlap.retainAll(h);
        assertTrue(overlap.isEmpty(), "leak: " + overlap.size() + " samples are in both sets");
        Set<String> union = new HashSet<>(t);
        union.addAll(h);
        assertEquals(all, union);
    }

    @Test
    void theSplitIsAFixedFunctionOfSeedAndGameIdAndHoldsOutAboutTheFraction() throws IOException {
        ReplayBuffer held = buffer(files, true);
        // about a quarter of the 120 games; the bound is wide, the point is that it is neither empty nor everything
        double share = (double) held.size() / (3 * 40 * PER_GAME);
        assertTrue(share > 0.12 && share < 0.40, "held-out share " + share);
        // deterministic and independent of the file order
        List<Path> reversed = new ArrayList<>(files);
        Collections.reverse(reversed);
        assertEquals(fingerprints(held), fingerprints(buffer(reversed, true)));
        assertEquals(fingerprints(held), fingerprints(buffer(files, true)));
        // keyed on (seed, id): the same ids in the files of seed 1 and seed 2 are not split alike
        int differ = 0;
        for (int id = 0; id < 40; id++) {
            boolean a = Gen0.heldOut(java.util.Map.of("seed", "1"), id, FRACTION);
            boolean b = Gen0.heldOut(java.util.Map.of("seed", "2"), id, FRACTION);
            differ += a != b ? 1 : 0;
        }
        assertTrue(differ > 5, "the split of seed 1 and seed 2 differs on " + differ + " of 40 ids");
        // a bigger fraction holds out a superset (the threshold is monotone)
        for (int id = 0; id < 200; id++) {
            if (Gen0.heldOut(java.util.Map.of("seed", "1"), id, 0.1)) {
                assertTrue(Gen0.heldOut(java.util.Map.of("seed", "1"), id, 0.3));
            }
        }
    }

    @Test
    void theResultIsTheLabelSeenByTheMover() throws IOException {
        ReplayBuffer all = new ReplayBuffer(2, Integer.MAX_VALUE, false);
        all.addGeneration(0, List.of(files.get(0)), (m, id) -> true);
        List<Sample> samples = SyntheticSamples.samples(2, 11, 40 * PER_GAME);
        assertEquals(samples.size(), all.size());
        int[] counts = new int[3];
        for (int s = 0; s < samples.size(); s++) {
            int mover = samples.get(s).position().sideToMove();
            float[] v = SyntheticShards.values(s / PER_GAME);
            int expected = (int) Math.signum(v[mover] - v[1 - mover]);
            assertEquals(expected, all.result(s), "sample " + s);
            counts[expected + 1]++;
        }
        assertTrue(counts[0] > 20 && counts[1] > 10 && counts[2] > 20, "all three results occur: "
                + List.of(counts[0], counts[1], counts[2]));
        // the mover of ply p is p mod 2 (the buffer relies on it): the synthetic positions start at ply 0
        for (Sample s : samples) {
            assertEquals(s.position().ply() & 1, s.position().sideToMove());
        }
    }

    @Test
    void withoutAFilterTheResultsAreStillKeptAndAFileWithoutGameTensorsStillLoads() throws IOException {
        Path plain = SyntheticSamples.write(dir, "plain.safetensors", 2, SyntheticSamples.samples(2, 5, 12));
        ReplayBuffer b = new ReplayBuffer(2, 100, false);
        b.addGeneration(0, List.of(files.get(0), plain));
        assertEquals(40 * PER_GAME + 12, b.size());
        assertTrue(java.util.stream.IntStream.range(0, 40 * PER_GAME).anyMatch(i -> b.result(i) != 0));
        assertEquals(0, b.result(40 * PER_GAME + 3), "no game tensors, no result");
        assertThrows(IllegalArgumentException.class, () -> b.result(b.size()));
    }

    @Test
    void aFilterNeedsTheGameTensorsAndLeavesTheFileAlone() throws IOException {
        Path plain = SyntheticSamples.write(dir, "plain2.safetensors", 2, SyntheticSamples.samples(2, 6, 12));
        ReplayBuffer b = new ReplayBuffer(2, 100, false);
        assertThrows(IllegalArgumentException.class, () -> b.addGeneration(0, List.of(plain), (m, id) -> true));
        assertTrue(Files.exists(plain), "not renamed: the file is a wrong configuration, not corrupt");
        assertEquals(0, b.size());
        assertFalse(Files.exists(plain.resolveSibling("plain2.safetensors.corrupt")));
    }

    @Test
    void realGamesGetTheResultOfTheirLabelForTheMoverOfEachPly() throws IOException {
        SelfPlayGame.Settings settings = SelfPlayGameTest.settings(ReplayBufferEndToEndTest.SPEC, 0, true,
                Adjudication.Config.OFF);
        SelfPlayGame game = new SelfPlayGame(settings);
        List<GameRecord> games = new ArrayList<>();
        for (int id = 0; id < 3; id++) {
            games.add(game.play(id));
        }
        Path file = dir.resolve("samples-real.safetensors");
        GameShard.write(file, settings, 0, 0, games);
        ReplayBuffer b = new ReplayBuffer(2, Integer.MAX_VALUE, false);
        b.addGeneration(0, List.of(file), (m, id) -> id != 1);
        int index = 0;
        int nonZero = 0;
        for (GameRecord g : games) {
            if (g.id() == 1) {
                continue;
            }
            Position p = Position.initial(RuleSet.defaults(2));
            for (int s = 0; s < g.samples().count(); s++) {
                double[] v = g.labelValues();
                int mover = p.sideToMove();
                assertEquals((int) Math.signum(v[mover] - v[1 - mover]), b.result(index), "game " + g.id() + " ply " + s);
                nonZero += b.result(index) != 0 ? 1 : 0;
                p.make(g.moves()[s]);
                index++;
            }
        }
        assertEquals(b.size(), index, "game 1 is filtered out, the others are all there");
        assertTrue(nonZero > 50, "decisive results: " + nonZero);
    }

    @Test
    void gameValuesHaveOneColumnPerPlayerOfTheBuffer(@TempDir Path tmp) throws IOException {
        // [games, 3] for three players: the shape check must use the buffer's player count, not 2
        Path three = SyntheticShards.write(tmp, "samples-s3-0000.safetensors", 3, 1, 0, 10, PER_GAME, 31);
        ReplayBuffer buffer = new ReplayBuffer(3, Integer.MAX_VALUE, false);
        ReplayBuffer.Loaded loaded = buffer.addGeneration(0, List.of(three), (m, id) -> id % 2 == 0);
        assertTrue(loaded.skipped().isEmpty(), "a three-player file with [G, 3] game values reads: " + loaded.skipped());
        assertEquals(5 * PER_GAME, buffer.size(), "the games of even id");
    }
}
