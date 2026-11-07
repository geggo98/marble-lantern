package sternhalma.search;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;

/**
 * The round-2 refit of the linear weights (E04): the passed-position filter, the moves-only game files, the
 * replayed samples and the frozen weight set {@code linear-r2}.
 */
class LinearRefitTest {

    private static final String CHEAP = "ab:eval=linear,pvs=1,killers=1,history=1,lmr=2,asp=1,ttrep=1,mate=1,"
            + "partial=1,nodes=300,tt=12";

    /** The player of the E04 data set: BEST_SPEC of commit b66e7e2 at 20000 nodes (eval=linear = R1 weights). */
    static final String DATA_SPEC = "ab:eval=linear,pvs=1,killers=1,history=1,lmr=2,asp=1,ttrep=1,mate=1,partial=1,"
            + "nodes=20000";

    /** Held-out games of the E04 data set (see the resource's header), committed so that the check runs anywhere. */
    private static final String HELD_OUT = "e04-heldout-games.txt";

    private static Position twoPlayer(int[][] p0, int[][] p1) {
        Position pos = Position.empty(RuleSet.defaults(2));
        for (int[] qr : p0) {
            pos.put(0, qr[0], qr[1]);
        }
        for (int[] qr : p1) {
            pos.put(1, qr[0], qr[1]);
        }
        return pos;
    }

    @Test
    void passedNeedsEveryPieceOfPlayer0AboveEveryPieceOfPlayer1() {
        assertFalse(LinearTraining.passed(Position.initial(RuleSet.defaults(2))));
        // player 0 moves towards +r; its rearmost piece one row ahead of player 1's foremost piece: passed
        assertTrue(LinearTraining.passed(twoPlayer(new int[][] {{0, 1}, {-3, 6}}, new int[][] {{0, 0}, {3, -6}})));
        // the same row: not passed (they can still touch sideways)
        assertFalse(LinearTraining.passed(twoPlayer(new int[][] {{0, 0}, {-3, 6}}, new int[][] {{1, 0}, {3, -6}})));
        // one straggler of player 0 behind one piece of player 1: not passed
        assertFalse(LinearTraining.passed(twoPlayer(new int[][] {{0, -1}, {-3, 6}}, new int[][] {{0, 0}, {3, -6}})));
        // reversed armies (player 1 above player 0) is contact-free too, but it is not a pass: not passed
        assertFalse(LinearTraining.passed(twoPlayer(new int[][] {{3, -6}}, new int[][] {{-3, 6}})));
        // three players: never
        assertFalse(LinearTraining.passed(Position.empty(RuleSet.defaults(3)).put(0, 0, 2).put(1, 0, -2)));
    }

    @Test
    void gameLinesRoundTripAndBadFilesAreRejected(@TempDir Path dir) throws IOException {
        GameFiles.Game g = new GameFiles.Game(17, -0.3125, new int[] {1, 2, 70000});
        GameFiles.Game back = GameFiles.parse(GameFiles.line(g));
        assertEquals(g.id(), back.id());
        assertEquals(g.value0(), back.value0());
        assertArrayEquals(g.moves(), back.moves());
        assertThrows(IllegalArgumentException.class, () -> GameFiles.parse("3 1.0 4 5 6"));
        Path a = dir.resolve("a.txt");
        Files.writeString(a, GameFiles.header("x", 1, 4) + GameFiles.line(g) + GameFiles.line(g));
        assertThrows(IllegalArgumentException.class, () -> GameFiles.readAll(a.toString()));
        assertEquals("x", GameFiles.headerValue(a, "spec"));
        // a shard is complete only with exactly its ids
        assertFalse(GameFiles.complete(a, 0, 10));
    }

    @Test
    void generateWritesCompleteShardsResumesAndRefusesASecondRun(@TempDir Path dir) throws Exception {
        assertEquals(0, GameFiles.generate(dir, 3, 5, 2, CHEAP));
        Path shard = GameFiles.shardFile(dir, 5, 0);
        assertTrue(GameFiles.complete(shard, 0, 3));
        String first = Files.readString(shard);
        List<GameFiles.Game> games = GameFiles.read(shard);
        assertEquals(3, games.size());
        for (GameFiles.Game g : games) {
            // the opening is the arena's, and the stored game replays legally to its recorded end
            assertArrayEquals(GameFiles.openingMoves(5, g.id(), 4), Arrays.copyOf(g.moves(), 4));
            Position pos = Position.initial(RuleSet.defaults(2));
            for (int m : g.moves()) {
                assertTrue(Arrays.stream(pos.legalMoves()).anyMatch(x -> x == m), "illegal stored move");
                pos.make(m);
            }
            assertNotNull(pos.outcome(), "a stored game ends in a terminal position");
            assertEquals(pos.outcome().value(0), g.value0());
            // the player is deterministic: playing the game again gives the same moves
            assertArrayEquals(g.moves(), GameFiles.play(PlayerSpec.parse(CHEAP), 5, g.id(), 4).moves());
        }
        // a leftover temporary file is removed, the complete shard is skipped and left untouched
        Path leftover = dir.resolve("games-s5-0001.txt.tmp.123");
        Files.writeString(leftover, "partial");
        assertEquals(0, GameFiles.generate(dir, 3, 5, 2, CHEAP));
        assertFalse(Files.exists(leftover));
        assertEquals(first, Files.readString(shard));
        // a different spec must not silently reuse the shard
        assertEquals(1, GameFiles.generate(dir, 3, 5, 2, CHEAP.replace("nodes=300", "nodes=301")));
        // while another run holds the lock: exit code 75, nothing played
        try (FileChannel ch = FileChannel.open(dir.resolve(".games.lock"), StandardOpenOption.WRITE);
                FileLock lock = ch.lock()) {
            assertTrue(lock.isValid());
            assertEquals(75, GameFiles.generate(dir, 60, 5, 2, CHEAP));
        }
        assertFalse(Files.exists(GameFiles.shardFile(dir, 5, 1)));
    }

    @Test
    void samplesDropPassedPositionsAndLabelTheSideToMove(@TempDir Path dir) throws Exception {
        assertEquals(0, GameFiles.generate(dir, 4, 9, 2, CHEAP));
        List<GameFiles.Game> games = GameFiles.readAll(dir.toString());
        long[] filtered = new long[2];
        long[] unfiltered = new long[2];
        List<LinearTraining.Sample> kept = LinearTraining.samples(games, 6, true, filtered);
        List<LinearTraining.Sample> all = LinearTraining.samples(games, 6, false, unfiltered);
        assertEquals(0, unfiltered[1]);
        assertTrue(filtered[1] > 0, "games at this level end with a race, so some positions are passed");
        assertEquals(all.size(), filtered[0] + filtered[1]);
        // one sample per non-terminal position from ply 6: plies 6 .. n-1 of every game
        assertEquals(games.stream().mapToInt(g -> g.moves().length - 6).sum(), all.size());
        double[] x = new double[LinearEvaluator.FEATURES];
        for (GameFiles.Game g : games) {
            Position pos = Position.initial(RuleSet.defaults(2));
            int keptHere = 0;
            for (int i = 0; i < g.moves().length; i++) {
                if (pos.ply() >= 6 && !LinearTraining.passed(pos)) {
                    LinearTraining.Sample s = kept.stream()
                            .filter(t -> t.game() == g.id() && t.ply() == pos.ply()).findFirst().orElseThrow();
                    double value = pos.sideToMove() == 0 ? g.value0() : -g.value0();
                    assertEquals((1 + value) / 2, s.label(), 1e-12);
                    LinearEvaluator.features(pos, x);
                    assertArrayEquals(x, s.x(), 1e-12);
                    keptHere++;
                }
                pos.make(g.moves()[i]);
            }
            int id = g.id();
            assertEquals(keptHere, kept.stream().filter(t -> t.game() == id).count());
        }
    }

    @Test
    void linearR2IsRegisteredFrozenAndCoveredByTheContract() {
        assertTrue(Evaluators.names().contains("linear-r2"));
        assertTrue(Strategies.registered().stream().anyMatch(s -> s.name().startsWith("ab:eval=linear-r2,")),
                "the strategy contract must cover linear-r2");
        assertFalse(Arrays.equals(LinearWeights.R1, LinearWeights.ROUND2), "linear-r2 is a refit, not a copy of R1");
        double[] x = new double[LinearEvaluator.FEATURES];
        Position pos = Position.initial(RuleSet.defaults(2));
        for (int i = 0; i < 20; i++) {
            pos.make(pos.legalMoves()[i % pos.legalMoves().length]);
        }
        LinearEvaluator.features(pos, x);
        assertEquals(Math.round(LinearWeights.SCALE * LinearTraining.dot(LinearWeights.ROUND2, x)),
                Evaluators.byName("linear-r2").evaluate(pos), "linear-r2 evaluates with the R2 weights");
    }

    /** The committed held-out games; the file holds no training game (every id is 0 mod 5). */
    private static List<GameFiles.Game> heldOutGames(Path dir) throws IOException {
        Path file = dir.resolve(HELD_OUT);
        try (InputStream in = LinearRefitTest.class.getResourceAsStream(HELD_OUT)) {
            assertNotNull(in, "test resource " + HELD_OUT);
            Files.copy(in, file);
        }
        List<GameFiles.Game> games = GameFiles.read(file);
        assertTrue(games.stream().allMatch(g -> g.id() % 5 == 0), "held-out games only");
        assertEquals(DATA_SPEC, GameFiles.headerValue(file, "spec"));
        return games;
    }

    @Test
    void theRefitPredictsHeldOutBestGamesBetterThanR1(@TempDir Path dir) throws IOException {
        List<LinearTraining.Sample> test = LinearTraining.samples(heldOutGames(dir), 6, true, null);
        assertTrue(test.size() >= 5_000, "held-out samples: " + test.size());
        double[] r1 = LinearTraining.score(test, LinearWeights.R1);
        double[] r2 = LinearTraining.score(test, LinearWeights.ROUND2);
        assertTrue(r2[0] < r1[0], "held-out log loss: R2 " + r2[0] + " must be below R1 " + r1[0]);
    }

    @Test
    void theDataToolReproducesAStoredGame(@TempDir Path dir) throws IOException {
        GameFiles.Game stored = heldOutGames(dir).get(0);
        // the data were played with eval=linear holding the round-1 weights; linear-r1 is the frozen copy, so this
        // stays reproducible after a later commit changes the live weights or BEST_SPEC
        GameFiles.Game again = GameFiles.play(PlayerSpec.parse(DATA_SPEC.replace("eval=linear,", "eval=linear-r1,")),
                1003, stored.id(), 4);
        assertArrayEquals(stored.moves(), again.moves(), "self-play from the arena opening is deterministic");
        assertEquals(stored.value0(), again.value0());
    }
}
